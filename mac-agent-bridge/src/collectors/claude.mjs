import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CLAUDE_DESKTOP_DIR = path.join(os.homedir(), 'Library/Application Support/Claude');
const CLAUDE_PROJECTS_DIR = path.join(os.homedir(), '.claude/projects');

export class ClaudeCollector {
  constructor(onChange) {
    this.onChange = onChange;
    this.threads = new Map();
    this.pendingHooks = new Map(); // requestId -> { resolve, timer, threadId }
    this.timer = null;
    this.watchers = [];
  }

  start() {
    this.scanSessions();
    this.timer = setInterval(() => this.scanSessions(), 2500);

    for (const dir of [
      path.join(CLAUDE_DESKTOP_DIR, 'claude-code-sessions'),
      path.join(CLAUDE_DESKTOP_DIR, 'local-agent-mode-sessions'),
      CLAUDE_PROJECTS_DIR,
    ]) {
      if (fs.existsSync(dir)) {
        try {
          this.watchers.push(fs.watch(dir, { recursive: true }, (_, f) => this.onFsEvent(f)));
        } catch {
          try {
            this.watchers.push(fs.watch(dir, (_, f) => this.onFsEvent(f)));
          } catch {}
        }
      }
    }
  }

  // Every transcript write (streaming, tool results, subagents) fires a watch event; scanning on
  // each one re-read ~8 big tails several times a second. Debounce and cap to ~4 scans/second.
  onFsEvent(filename) {
    if (filename && !/\.jsonl?$/.test(String(filename))) return;
    if (this.scanTimer) return;
    const wait = Math.max(120, 250 - (Date.now() - (this.lastScanAt || 0)));
    this.scanTimer = setTimeout(() => {
      this.scanTimer = null;
      this.lastScanAt = Date.now();
      try { this.scanSessions(); } catch (err) { console.warn(`[claude] scan failed: ${err.message}`); }
    }, wait);
  }

  stop() {
    if (this.scanTimer) clearTimeout(this.scanTimer);
    if (this.timer) clearInterval(this.timer);
    for (const w of this.watchers) {
      try { w.close(); } catch {}
    }
  }

  // Called by server.mjs when ~/.claude hook posts an event (PreToolUse / Notification / Stop)
  handleHookEvent(body, waitForDecision = false) {
    const sessionId = body.session_id || body.sessionId || 'claude-active';
    const id = `claude:${sessionId}`;
    const now = Date.now();
    const isDesktop = String(body.source || '').toLowerCase().includes('desktop') ||
      String(body.entrypoint || '').toLowerCase().includes('desktop');

    const existing = this.threads.get(id) || {
      id,
      rawId: sessionId,
      provider: 'claude',
      providerLabel: 'Claude',
      source: isDesktop ? 'desktop' : 'cli',
      title: body.cwd ? path.basename(body.cwd) : 'Claude Session',
      status: 'running',
      step: 'Working…',
      prompt: null,
      options: [],
      startedAtMs: now,
      updatedAtMs: now,
      color: '#81c995',
      canReply: true,
    };

    const eventName = body.hook_event_name || body.event || '';

    if (eventName === 'PreToolUse' && waitForDecision) {
      const requestId = `req_${now}_${Math.random().toString(36).slice(2, 7)}`;
      const toolName = body.tool_name || 'Tool';
      const cmd = body.tool_input?.command || body.tool_input?.file_path || JSON.stringify(body.tool_input || {}).slice(0, 60);
      existing.status = 'waiting_approval';
      existing.prompt = `Approve ${toolName}: ${String(cmd).slice(0, 160)}`;
      existing.step = existing.prompt;
      existing.options = ['Proceed', 'Deny'];
      existing.requestId = requestId;
      existing.color = '#fdd663';
      existing.updatedAtMs = now;
      this.threads.set(id, existing);
      this.onChange();

      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          this.pendingHooks.delete(requestId);
          resolve({ decision: 'ask' }); // Fall back to normal Claude prompt after 5 min
        }, 5 * 60_000);
        this.pendingHooks.set(requestId, { resolve, timer, threadId: id });
      });
    }

    if (eventName === 'Notification') {
      existing.status = 'waiting_input';
      existing.prompt = body.message || 'Claude needs your input';
      existing.step = String(existing.prompt).slice(0, 240);
      existing.options = ['Proceed'];
      existing.color = '#fdd663';
    } else if (eventName === 'Stop') {
      existing.status = 'idle';
      existing.step = 'Finished turn';
      existing.prompt = null;
      existing.options = [];
      existing.color = '#81c995';
    } else {
      existing.status = 'running';
      existing.step = body.tool_name ? `Running ${body.tool_name}` : 'Working…';
      existing.prompt = null;
      existing.options = [];
      existing.color = '#81c995';
    }

    existing.updatedAtMs = now;
    this.threads.set(id, existing);
    this.onChange();
    return Promise.resolve({ ok: true });
  }

  resolveHookDecision(threadId, decision) {
    for (const [reqId, entry] of this.pendingHooks.entries()) {
      if (entry.threadId === threadId) {
        clearTimeout(entry.timer);
        this.pendingHooks.delete(reqId);
        const allow = decision === 'approve' || decision === 'proceed' || decision === 'allow';
        entry.resolve({ decision: allow ? 'allow' : 'deny' });
        const t = this.threads.get(threadId);
        if (t) {
          t.status = allow ? 'running' : 'idle';
          t.step = allow ? 'Approved from watch' : 'Denied from watch';
          t.prompt = null;
          t.options = [];
          t.color = '#81c995';
          t.updatedAtMs = Date.now();
          this.onChange();
        }
        return true;
      }
    }
    return false;
  }

  scanSessions() {
    const now = Date.now();
    const recentFiles = [];

    // 1. Scan Desktop Claude Code & Cowork session metadata
    for (const sub of ['claude-code-sessions', 'local-agent-mode-sessions']) {
      const root = path.join(CLAUDE_DESKTOP_DIR, sub);
      if (!fs.existsSync(root)) continue;
      this.walkRecentJson(root, now, 6 * 3600_000, 'desktop', recentFiles, 3);
    }

    // 2. Scan Terminal ~/.claude/projects/*/*.jsonl
    if (fs.existsSync(CLAUDE_PROJECTS_DIR)) {
      this.walkRecentJson(CLAUDE_PROJECTS_DIR, now, 6 * 3600_000, 'cli', recentFiles, 2);
    }

    recentFiles.sort((a, b) => b.mtimeMs - a.mtimeMs);
    let changed = false;
    const setThread = (id, t) => {
      const existing = this.threads.get(id);
      if (!existing || JSON.stringify(existing) !== JSON.stringify(t)) {
        this.threads.set(id, t);
        changed = true;
      }
    };
    const dropThread = (id) => {
      if (this.threads.delete(id)) changed = true;
    };

    // Pass 1: Desktop session records. A Code-tab session's record points at the CLI session
    // that does the work (cliSessionId) — fold it into that thread instead of listing it twice.
    const desktopLinks = new Map(); // cliSessionId -> desktop meta
    for (const f of recentFiles) {
      if (f.source !== 'desktop' || !f.filePath.endsWith('.json')) continue;
      const meta = this.readDesktopMeta(f.filePath, f.mtimeMs);
      if (!meta) continue;
      const desktopId = `claude:${meta.sessionId}`;
      if (meta.isArchived) {
        dropThread(desktopId);
        if (meta.cliSessionId) dropThread(`claude:${meta.cliSessionId}`);
        continue;
      }
      if (meta.cliSessionId) {
        desktopLinks.set(meta.cliSessionId, meta);
        dropThread(desktopId);
        continue;
      }
      // Desktop-only session (e.g. Cowork): status from the app's own activity clock.
      const existing = this.threads.get(desktopId);
      if (existing && existing.requestId && this.pendingHooks.has(existing.requestId)) continue;
      const running = now - meta.lastActivityAt < 45_000;
      setThread(desktopId, {
        id: desktopId,
        rawId: meta.sessionId,
        provider: 'claude',
        providerLabel: 'Claude Desktop',
        source: 'desktop',
        title: meta.title.slice(0, 48),
        status: running ? 'running' : 'idle',
        step: running ? 'Working' : 'Idle',
        prompt: null,
        options: [],
        startedAtMs: existing && existing.status === (running ? 'running' : 'idle') ? existing.startedAtMs : meta.lastActivityAt,
        updatedAtMs: meta.lastActivityAt,
        color: '#81c995',
        canReply: true,
      });
    }

    // Pass 2: session transcripts (.jsonl) carry the live step.
    // Only .jsonl transcripts; sidecars like <session>/custom-title.json are not sessions.
    const transcripts = recentFiles.filter(f => f.filePath.endsWith('.jsonl'));
    for (const f of transcripts.slice(0, 8)) {
      const base = path.basename(f.filePath);
      if (['rpm.json', 'settings.json', 'config.json', 'manifest.json', 'state.json'].includes(base.toLowerCase())) continue;
      const sessionId = base.replace(/\.(jsonl|json)$/, '');
      const id = `claude:${sessionId}`;
      const existing = this.threads.get(id);
      // Do not overwrite an actively held PreToolUse hook decision
      if (existing && existing.requestId && this.pendingHooks.has(existing.requestId)) continue;

      const link = desktopLinks.get(sessionId);
      const parsed = this.readTailSummary(f.filePath, link ? 'desktop' : f.source, f.mtimeMs, sessionId);
      if (!parsed) continue;
      parsed.filePath = f.filePath;
      if (link) {
        // The Desktop record's title wins when it has one. When it does not, its "title" is just the
        // folder name, and that must not hide the title Claude Code wrote into the transcript.
        if (link.titleIsReal || !parsed._titleIsReal) parsed.title = link.title.slice(0, 48);
        parsed.providerLabel = 'Claude Code';
      }
      delete parsed._titleIsReal;
      if (existing) {
        // The tail often ends in tool results with no assistant line; keep the last real step
        // instead of flapping back to the placeholder.
        if (parsed.step === 'Active session' && existing.step) parsed.step = existing.step;
        // Elapsed time measures the current run, not the last file write.
        if (parsed.status === existing.status && existing.startedAtMs) parsed.startedAtMs = existing.startedAtMs;
      }
      setThread(id, parsed);
    }

    // A transcript that no longer exists (deleted session) must not linger as a ghost thread.
    for (const [id, t] of this.threads) {
      if (t.filePath && !fs.existsSync(t.filePath)) dropThread(id);
    }

    if (changed) this.onChange();
  }

  walkRecentJson(dir, now, maxAgeMs, source, acc, depth) {
    if (depth < 0) return;
    try {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) {
          // The Claude app keeps sidecar state (waiting-input/) and tombstones (deleted_*) next
          // to real session records; neither is a session.
          if (e.name === 'waiting-input' || e.name.startsWith('deleted_')) continue;
          this.walkRecentJson(full, now, maxAgeMs, source, acc, depth - 1);
        } else if (e.name.endsWith('.jsonl') || e.name.endsWith('.json')) {
          try {
            const st = fs.statSync(full);
            if (now - st.mtimeMs <= maxAgeMs && st.size > 20) {
              acc.push({ filePath: full, mtimeMs: st.mtimeMs, source });
            }
          } catch {}
        }
      }
    } catch {}
  }

  // Desktop session records are large single JSON objects; parse whole (cached by mtime)
  // because a tail slice never parses and left every one titled "Claude Desktop".
  readDesktopMeta(filePath, mtimeMs) {
    this.metaCache ??= new Map();
    const cached = this.metaCache.get(filePath);
    if (cached && cached.mtimeMs === mtimeMs) return cached.meta;
    let meta = null;
    try {
      const d = JSON.parse(fs.readFileSync(filePath, 'utf8'));
      // A real session record has a cwd/title/createdAt; tiny sidecars ({v, sessionId, savedAt})
      // don't, and were showing up as an untitled "Claude Desktop" session.
      if (d && typeof d === 'object' && (d.sessionId || d.cliSessionId) && (d.cwd || d.title || d.createdAt)) {
        meta = {
          sessionId: String(d.sessionId || path.basename(filePath, '.json')),
          cliSessionId: d.cliSessionId ? String(d.cliSessionId) : null,
          title: String(d.title || (d.cwd ? path.basename(d.cwd) : 'Claude Desktop')),
          titleIsReal: Boolean(d.title), // false: the title above is only the folder name
          isArchived: Boolean(d.isArchived),
          lastActivityAt: Number(d.lastActivityAt || d.lastTurnEdgeAt || mtimeMs),
        };
      }
    } catch {}
    this.metaCache.set(filePath, { mtimeMs, meta });
    return meta;
  }

  readTailSummary(filePath, source, mtimeMs, sessionId) {
    // Reuse the last parse while the file is unchanged, unless that result depended on how long
    // ago the file was written (those few cases are cheap to recompute and must age correctly).
    this.tailCache ??= new Map();
    try {
      const st = fs.statSync(filePath);
      const hit = this.tailCache.get(filePath);
      if (hit && hit.size === st.size && hit.mtimeMs === st.mtimeMs && !hit.timeSensitive) {
        return { ...hit.result };
      }
      const result = this.readTailSummaryUncached(filePath, source, mtimeMs, sessionId, st);
      if (result) {
        this.tailCache.set(filePath, { size: st.size, mtimeMs: st.mtimeMs, timeSensitive: result._timeSensitive, result });
        if (this.tailCache.size > 64) this.tailCache.delete(this.tailCache.keys().next().value);
        const out = { ...result };
        delete out._timeSensitive;
        return out;
      }
      return result;
    } catch {
      return null;
    }
  }

  readTailSummaryUncached(filePath, source, mtimeMs, sessionId, stat) {
    try {
      // Large tool results can be hundreds of KB; read enough tail to reach the last turn line.
      const len = Math.min(stat.size, 512 * 1024);
      const fd = fs.openSync(filePath, 'r');
      const buf = Buffer.alloc(len);
      fs.readSync(fd, buf, 0, len, stat.size - len);
      fs.closeSync(fd);

      const text = buf.toString('utf8');
      const lines = filePath.endsWith('.jsonl') ? text.split('\n').filter(Boolean) : [text];
      const projectName = path.basename(path.dirname(filePath)).replace(/^-Users-[^-]+-Documents-Projects-/, '').replace(/-/g, ' ');
      const looksLikeUuid = /^[0-9a-f]{8}\s+[0-9a-f]{4}/i.test(projectName);
      let title = (projectName && !looksLikeUuid && projectName.length < 40) ? projectName : (source === 'desktop' ? 'Claude Desktop' : 'Claude Code');
      // Claude Code writes the conversation's title into the transcript as `custom-title` (a name
      // the person or the app set) or `ai-title` (generated) records, repeated as the session goes on.
      let customTitle = '';
      let aiTitle = '';
      let step = 'Active session';
      let status = 'idle';
      let prompt = null;
      let choices = []; // option labels of a pending AskUserQuestion
      let multiSelect = false;
      let lastTurnLine = null;
      let doneAtMs = 0; // set only when the last turn genuinely ended (end_turn) // last user/assistant line; decides whether a turn is in progress

      // The first line is cut mid-record only when we started reading partway into the file.
      for (const line of (stat.size > len ? lines.slice(1) : lines)) {
        try {
          const obj = JSON.parse(line);
          if (obj.title) title = String(obj.title).slice(0, 44);
          if (obj.type === 'custom-title' && typeof obj.customTitle === 'string' && obj.customTitle.trim()) customTitle = obj.customTitle.trim();
          else if (obj.type === 'ai-title' && typeof obj.aiTitle === 'string' && obj.aiTitle.trim()) aiTitle = obj.aiTitle.trim();
          if ((obj.type === 'assistant' || obj.type === 'user') && !obj.isSidechain) lastTurnLine = obj;
          if (obj.type === 'assistant' && obj.message?.content) {
            const blocks = Array.isArray(obj.message.content) ? obj.message.content : [];
            const toolUse = blocks.find(b => b.type === 'tool_use');
            if (toolUse) {
              step = `Using ${toolUse.name}`;
              if (toolUse.name === 'AskUserQuestion') {
                step = String(toolUse.input?.questions?.[0]?.question || 'Claude asked a question').slice(0, 240);
              }
            } else {
              const txt = blocks.find(b => b.type === 'text')?.text;
              if (txt) step = String(txt).replace(/\s+/g, ' ').slice(0, 240);
            }
          }
        } catch {}
      }

      // Running means a turn is in progress: the last conversation line is a user prompt or
      // tool result (Claude is about to respond) or an assistant tool call. A turn that ended
      // (stop_reason end_turn) or was interrupted is idle, however recently the file was written.
      const lastTurnAt = (lastTurnLine && Date.parse(lastTurnLine.timestamp)) || 0;
      const silentMs = Date.now() - mtimeMs;
      let timeSensitive = false;
      if (!lastTurnLine) {
        timeSensitive = true;
        status = silentMs < 45_000 ? 'running' : 'idle';
      } else if (lastTurnLine.type === 'assistant') {
        const blocks = Array.isArray(lastTurnLine.message?.content) ? lastTurnLine.message.content : [];
        const stop = lastTurnLine.message?.stop_reason;
        const ask = blocks.find(b => b.type === 'tool_use' && b.name === 'AskUserQuestion');
        if (ask) {
          status = 'waiting_input';
          const q = ask.input?.questions?.[0];
          prompt = q?.question || 'Claude asked a question';
          choices = (Array.isArray(q?.options) ? q.options : [])
            .map(o => String(o?.label || '').trim().slice(0, 40))
            .filter(Boolean)
            .slice(0, 4);
          multiSelect = Boolean(q?.multiSelect);
        } else if (stop === 'tool_use' || blocks.some(b => b.type === 'tool_use')) {
          status = 'running';
        } else if (stop == null) {
          timeSensitive = true;
          status = silentMs < 45_000 ? 'running' : 'idle'; // still streaming
        } else {
          status = 'idle';
          doneAtMs = lastTurnAt || mtimeMs;
        }
      } else {
        const c = lastTurnLine.message?.content;
        const txt = typeof c === 'string' ? c : Array.isArray(c) ? c.map(b => b.text || '').join(' ') : '';
        status = /\[Request interrupted by user/.test(txt) ? 'idle' : 'running';
      }
      // A "running" session silent for 15 min almost certainly died mid-turn.
      if (status === 'running') timeSensitive = true;
      if (status === 'running' && silentMs > 15 * 60_000) status = 'idle';

      const realTitle = customTitle || aiTitle; // a name someone chose beats a generated one
      if (realTitle) title = realTitle;

      return {
        id: `claude:${sessionId}`,
        rawId: sessionId,
        provider: 'claude',
        providerLabel: source === 'desktop' ? 'Claude Desktop' : 'Claude Code',
        source,
        title: title.slice(0, 48),
        _titleIsReal: Boolean(realTitle),
        status,
        step,
        prompt,
        // A multiple-choice question carries its real choices so the watch can answer it;
        // anything else that waits on the user is a plain Proceed.
        options: status.startsWith('waiting') ? (choices.length ? choices : ['Proceed']) : [],
        kind: choices.length ? 'choice' : (status.startsWith('waiting') ? 'input' : ''),
        multiSelect,
        doneAtMs,
        _timeSensitive: timeSensitive,
        startedAtMs: lastTurnAt || mtimeMs,
        // "Last interacted" is the last real message, not the file's mtime: the Claude app
        // appends housekeeping lines (bridge-session, cost-state) to every transcript when it
        // relaunches, which made untouched sessions look recently active.
        updatedAtMs: lastTurnAt || mtimeMs,
        color: status.startsWith('waiting') ? '#fdd663' : '#81c995',
        canReply: true,
      };
    } catch {
      return null;
    }
  }

  getThreads() {
    return [...this.threads.values()];
  }
}
