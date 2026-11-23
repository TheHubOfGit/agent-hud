import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const BRAIN_DIR = path.join(os.homedir(), '.gemini/antigravity/brain');
// Antigravity writes a turn to its transcript only when the reply is finished (the user message
// and the reply land together), so the transcript can't show "working". Its per-conversation
// database is written the moment a message is sent, so that is the live "started" signal.
const CONV_DIR = path.join(os.homedir(), '.gemini/antigravity/conversations');
const ACTIVITY_MAX_MS = 10 * 60_000; // a working state with no progress at all clears after this
// steps table (conversations/<id>.db): step_type 14 = user message, 15 = agent reply,
// other types are tool steps; status 3 = done, 6 = cancelled/failed, anything else = in progress.
const STEP_USER_INPUT = 14, STEP_PLANNER = 15, STATUS_DONE = 3, STATUS_CANCELLED = 6;

/** ask_question stores its questions under `args` (sometimes `arguments`), often as a JSON string. */
function parseAskQuestions(call) {
  let qs = (call.args ?? call.arguments)?.questions;
  if (typeof qs === 'string') {
    try { qs = JSON.parse(qs); } catch { qs = []; }
  }
  return Array.isArray(qs) ? qs : [];
}

// ---- Antigravity's own conversation titles ---------------------------------------------------------
// The app keeps one summary per conversation in agyhub_summaries_proto.pb, an undocumented protobuf:
// repeated field 1 = { 1: conversation id, 2: summary }. In a summary, field 1 is the title the app
// shows (15.1 repeats it); a sub-agent run has no title of its own and carries its task name at 17.8.2.
// Decoded defensively: anything unexpected just means "no title", and the first-prompt fallback applies.
const HUB_SUMMARIES = path.join(os.homedir(), '.gemini/antigravity/agyhub_summaries_proto.pb');

function* pbFields(buf) {
  let i = 0;
  const varint = () => {
    let v = 0, shift = 0, b;
    do {
      b = buf[i++];
      if (b === undefined) return null;
      v += (b & 0x7f) * 2 ** shift;
      shift += 7;
    } while (b & 0x80);
    return v;
  };
  while (i < buf.length) {
    const key = varint();
    if (key === null) return;
    const field = Math.floor(key / 8), wire = key % 8;
    if (field === 0) return;
    if (wire === 0) {
      const v = varint();
      if (v === null) return;
      yield [field, wire, v];
    } else if (wire === 2) {
      const n = varint();
      if (n === null || i + n > buf.length) return;
      yield [field, wire, buf.subarray(i, i + n)];
      i += n;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else return;
  }
}

function pbFirst(buf, field) {
  if (!buf) return null;
  for (const [f, w, v] of pbFields(buf)) if (f === field && w === 2) return v;
  return null;
}

// A field is a title only if it decodes as clean text. A nested message would show control bytes (or
// start with a tag byte such as a newline), so those are refused; line breaks inside real text, as in a
// multi-line first prompt that Antigravity uses as a title, are folded into spaces.
function pbText(v) {
  if (!v) return '';
  const s = v.toString('utf8');
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\ufffd]/.test(s) || /^\s/.test(s)) return '';
  return s.replace(/\s+/g, ' ').trim();
}

function titleOfSummary(summary) {
  return pbText(pbFirst(summary, 1)) ||
    pbText(pbFirst(pbFirst(summary, 15), 1)) ||
    pbText(pbFirst(pbFirst(pbFirst(summary, 17), 8), 2));
}

let hubCache = { mtimeMs: 0, size: -1, titles: new Map() };

/** conversation id -> the title Antigravity shows for it. Re-read only when the file changes. */
export function readHubTitles(file = HUB_SUMMARIES) {
  try {
    const st = fs.statSync(file);
    if (st.mtimeMs === hubCache.mtimeMs && st.size === hubCache.size) return hubCache.titles;
    const data = fs.readFileSync(file);
    const titles = new Map();
    for (const [f, w, entry] of pbFields(data)) {
      if (f !== 1 || w !== 2) continue;
      const id = pbText(pbFirst(entry, 1));
      const summary = pbFirst(entry, 2);
      if (!/^[0-9a-f-]{36}$/.test(id) || !summary) continue;
      const title = titleOfSummary(summary);
      if (title) titles.set(id, title);
    }
    hubCache = { mtimeMs: st.mtimeMs, size: st.size, titles };
    return titles;
  } catch {
    return hubCache.titles; // unreadable right now (mid-write, or absent): keep what we had
  }
}

export class AntigravityCollector {
  constructor(onChange) {
    this.onChange = onChange;
    this.threads = [];
    this.watchers = new Map();
    this.offsets = new Map();
    this.states = new Map();
    this.timer = null;
  }

  start() {
    this.discoverActive();
    this.timer = setInterval(() => this.discoverActive(), 2500);
    // React to a brand-new conversation (its transcript appearing) immediately instead of
    // waiting for the next 2.5s poll. Only transcript paths matter; everything else is noise.
    this.activityTimers = new Map();
    this.lastDbReadAt = new Map();
    if (fs.existsSync(CONV_DIR)) {
      try {
        this.convWatcher = fs.watch(CONV_DIR, (_, f) => {
          const m = /^([0-9a-f-]{36})\.db(-wal)?$/.exec(String(f || ''));
          if (m) this.onConversationActivity(m[1]);
        });
        this.convWatcher.on('error', () => {});
      } catch {}
    }
    if (fs.existsSync(BRAIN_DIR)) {
      try {
        this.brainWatcher = fs.watch(BRAIN_DIR, { recursive: true }, (_, f) => {
          if (!f || !String(f).endsWith('transcript.jsonl')) return;
          if (this.discoverTimer) return;
          this.discoverTimer = setTimeout(() => { this.discoverTimer = null; this.discoverActive(); }, 150);
        });
        this.brainWatcher.on('error', () => {});
      } catch {}
    }
  }

  stop() {
    if (this.convWatcher) try { this.convWatcher.close(); } catch {}
    for (const t of (this.activityTimers || new Map()).values()) clearTimeout(t);
    if (this.brainWatcher) try { this.brainWatcher.close(); } catch {}
    if (this.discoverTimer) clearTimeout(this.discoverTimer);
    if (this.timer) clearInterval(this.timer);
    for (const w of this.watchers.values()) {
      try { w.close(); } catch {}
    }
    this.watchers.clear();
  }

  discoverActive() {
    if (!fs.existsSync(BRAIN_DIR)) return;
    try {
      const now = Date.now();
      const entries = fs.readdirSync(BRAIN_DIR, { withFileTypes: true });
      const candidates = [];

      for (const e of entries) {
        if (!e.isDirectory() || e.name === 'tempmediaStorage') continue;
        const transcript = path.join(BRAIN_DIR, e.name, '.system_generated/logs/transcript.jsonl');
        if (!fs.existsSync(transcript)) continue;
        try {
          const st = fs.statSync(transcript);
          if (now - st.mtimeMs < 6 * 3600_000) {
            candidates.push({ id: e.name, transcript, mtimeMs: st.mtimeMs });
          }
        } catch {}
      }

      candidates.sort((a, b) => b.mtimeMs - a.mtimeMs);
      const top = candidates.slice(0, 6);
      const activeIds = new Set(top.map(c => c.id));

      for (const [id, w] of this.watchers.entries()) {
        if (!activeIds.has(id)) {
          try { w.close(); } catch {}
          this.watchers.delete(id);
          this.states.delete(id);
          this.offsets.delete(id);
        }
      }

      for (const c of top) {
        this.tailTranscript(c.id, c.transcript);
        if (!this.watchers.has(c.id)) {
          try {
            const w = fs.watch(c.transcript, () => this.tailTranscript(c.id, c.transcript));
            this.watchers.set(c.id, w);
          } catch {}
        }
      }

      // Backstop: re-read each visible conversation's live state from its database.
      for (const c of top) this.reconcileFromDb(c.id);
      // A database-activity "working" with no transcript progress for too long clears itself.
      for (const cur of this.states.values()) {
        if (cur.activityAt && cur.status === 'running' && now - cur.activityAt > ACTIVITY_MAX_MS) {
          cur.status = 'idle'; cur.step = 'Waiting for prompt'; delete cur.activityAt;
        }
      }
      this.publish();
    } catch {
      // Ignore transient FS errors
    }
  }

  transcriptPath(convId) {
    return path.join(BRAIN_DIR, convId, '.system_generated/logs/transcript.jsonl');
  }

  /**
   * Whether a conversation is mid-turn, read from the newest row of its live steps table:
   * a step still in progress, or the user's message with no reply yet, means working; a finished
   * reply means idle; a finished tool step means the agent is about to continue (working).
   * Returns 'working', 'idle', or null if the database can't be read.
   */
  dbTurnState(convId) {
    const file = path.join(CONV_DIR, `${convId}.db`);
    if (!fs.existsSync(file)) return null;
    let db;
    try {
      this.lastDbReadAt.set(convId, Date.now());
      db = new DatabaseSync(file, { readOnly: true });
      const row = db.prepare('SELECT step_type, status FROM steps ORDER BY idx DESC LIMIT 1').get();
      if (!row) return null;
      if (row.status !== STATUS_DONE && row.status !== STATUS_CANCELLED) return 'working';
      if (row.step_type === STEP_USER_INPUT) return 'working';
      if (row.step_type === STEP_PLANNER) return 'idle';
      return row.status === STATUS_CANCELLED ? 'idle' : 'working';
    } catch {
      return null;
    } finally {
      try { db?.close(); } catch {}
    }
  }

  /** Applies the database's view of a conversation (working/idle) to its state. */
  reconcileFromDb(convId) {
    const turn = this.dbTurnState(convId);
    if (!turn) return false;
    const now = Date.now();
    let cur = this.states.get(convId);
    if (!cur) {
      if (turn !== 'working') return false;
      cur = {
        id: `antigravity:${convId}`, rawId: convId, provider: 'antigravity', providerLabel: 'Antigravity',
        source: 'desktop', title: 'Antigravity Session', status: 'idle', step: 'Ready', prompt: null,
        options: [], startedAtMs: now, updatedAtMs: now, color: '#8ab4f8', canReply: true,
      };
      this.states.set(convId, cur);
    }
    if (cur.status.startsWith('waiting')) return false; // the transcript owns questions/approvals
    if (turn === 'working' && cur.status !== 'running') {
      cur.status = 'running'; cur.step = 'Thinking…';
      cur.prompt = null; cur.options = []; cur.kind = '';
      cur.startedAtMs = now; cur.updatedAtMs = now; cur.activityAt = now; cur.color = '#8ab4f8';
      return true;
    }
    if (turn === 'idle' && cur.status === 'running') {
      cur.status = 'idle';
      cur.updatedAtMs = now;
      cur.doneAtMs = cur.doneAtMs && cur.doneAtMs > cur.startedAtMs ? cur.doneAtMs : now;
      delete cur.activityAt;
      return true;
    }
    if (turn === 'working') cur.activityAt = now;
    return false;
  }

  onConversationActivity(convId) {
    // Our own read can touch the database's side files; ignore events right after it.
    if (Date.now() - (this.lastDbReadAt.get(convId) || 0) < 400) return;
    clearTimeout(this.activityTimers.get(convId));
    this.activityTimers.set(convId, setTimeout(() => {
      this.activityTimers.delete(convId);
      if (this.reconcileFromDb(convId)) this.publish();
    }, 250));
  }

  tailTranscript(convId, filePath) {
    try {
      const stat = fs.statSync(filePath);
      const prevOffset = this.offsets.get(convId) ?? Math.max(0, stat.size - 65536);
      const start = stat.size < prevOffset ? 0 : prevOffset;
      if (stat.size === start && this.states.has(convId)) return;

      const len = stat.size - start;
      let chunk = '';
      if (len > 0) {
        const fd = fs.openSync(filePath, 'r');
        const buf = Buffer.alloc(len);
        fs.readSync(fd, buf, 0, len, start);
        fs.closeSync(fd);
        // Only consume complete lines; a half-written last line is re-read next time instead of
        // being dropped (that lost events such as ask_question).
        const lastNl = buf.lastIndexOf(0x0a);
        if (lastNl === -1) {
          chunk = '';
        } else {
          chunk = buf.subarray(0, lastNl + 1).toString('utf8');
          this.offsets.set(convId, start + lastNl + 1);
        }
      }

      const cur = this.states.get(convId) || {
        id: `antigravity:${convId}`,
        rawId: convId,
        provider: 'antigravity',
        providerLabel: 'Antigravity',
        source: 'desktop',
        title: 'Antigravity Session',
        status: 'idle',
        step: 'Ready',
        prompt: null,
        options: [],
        startedAtMs: stat.mtimeMs,
        updatedAtMs: stat.mtimeMs,
        color: '#8ab4f8',
        canReply: true,
      };

      const lines = chunk.split('\n').filter(Boolean);
      for (const line of lines) {
        try {
          const ev = JSON.parse(line);
          const evTime = ev.created_at ? new Date(ev.created_at).getTime() : Date.now();
          cur.updatedAtMs = evTime || Date.now();

          if (ev.type === 'USER_INPUT' && ev.content) {
            const clean = String(ev.content)
              .replace(/<ADDITIONAL_METADATA>[\s\S]*$/gi, '')
              .replace(/The current local time is:[\s\S]*$/gi, '')
              .replace(/Comments on artifact URI:[\s\S]*?approved this document\./gi, '')
              .replace(/<[^>]+>/g, ' ')
              .replace(/\s+/g, ' ')
              .trim();
            if (clean && (cur.title === 'Antigravity Session' || cur.title.startsWith('Comments on artifact'))) {
              cur.title = clean.slice(0, 44);
            }
            cur.status = 'running';
            cur.step = 'Thinking…';
            cur.prompt = null;
            cur.options = [];
            cur.kind = '';
            cur.startedAtMs = cur.updatedAtMs;
          } else if (ev.type === 'PLANNER_RESPONSE') {
            const calls = Array.isArray(ev.tool_calls) ? ev.tool_calls : [];
            const askCall = calls.find(c => c.name === 'ask_question');
            const feedbackCall = calls.find(c =>
              (c.name === 'write_file' || c.name === 'edit_file') &&
              c.arguments?.ArtifactMetadata?.RequestFeedback === true
            );

            if (askCall) {
              const q0 = parseAskQuestions(askCall)[0];
              const choices = (Array.isArray(q0?.options) ? q0.options : [])
                .map(o => String(typeof o === 'string' ? o : o?.label || '').trim().slice(0, 40))
                .filter(Boolean)
                .slice(0, 4);
              cur.status = 'waiting_input';
              cur.prompt = q0?.question || 'Question from Antigravity';
              cur.step = String(cur.prompt).slice(0, 240);
              cur.options = choices.length ? choices : ['Proceed'];
              cur.kind = choices.length ? 'choice' : 'input';
              cur.multiSelect = Boolean(q0?.is_multi_select ?? q0?.multiSelect);
            } else if (feedbackCall) {
              cur.status = 'waiting_approval';
              cur.prompt = `Review plan: ${path.basename(feedbackCall.arguments?.Path || 'artifact')}`;
              cur.step = cur.prompt;
              cur.options = ['Proceed'];
            } else if (calls.length > 0) {
              const lastCall = calls[calls.length - 1];
              cur.status = 'running';
              cur.step = lastCall.arguments?.toolSummary || `Running ${lastCall.name}`;
              cur.prompt = null;
              cur.options = [];
            } else if (ev.status === 'DONE') {
              cur.status = 'idle';
              cur.doneAtMs = evTime || Date.now();
              const snippet = String(ev.content || '').replace(/\s+/g, ' ').trim();
              cur.step = snippet ? snippet.slice(0, 240) : 'Waiting for prompt';
              cur.prompt = null;
              cur.options = [];
            }
          } else if (cur.status === 'waiting_input' && ev.type === 'GENERIC' && /(^|\n)A\d+:\s*\S/.test(String(ev.content || ''))) {
            // The answer to an ask_question is logged as a GENERIC step ("A1: <answer>").
            const answer = String(ev.content).match(/(?:^|\n)A\d+:\s*([^\n]*)/)?.[1] || '';
            cur.status = 'running';
            cur.step = answer ? `Answered: ${answer}`.slice(0, 240) : 'Answered';
            cur.prompt = null;
            cur.options = [];
            cur.kind = '';
          } else if (ev.status === 'ERROR') {
            cur.status = 'error';
            cur.step = String(ev.content || 'Step error').slice(0, 240);
          }
        } catch {
          // Ignore partial line
        }
      }

      // Antigravity writes a step only when it completes, so a long tool run (build, test suite)
      // is silent for minutes. Only fall back to idle after 10 quiet minutes; the watch already
      // shows "Quiet for X" from 2 minutes on.
      // Any transcript progress supersedes the database-activity guess.
      if (lines.length > 0) delete cur.activityAt;
      const lastProgress = Math.max(stat.mtimeMs, cur.activityAt || 0);
      if (cur.status === 'running' && Date.now() - lastProgress > ACTIVITY_MAX_MS) {
        cur.status = 'idle';
      }

      cur.color = cur.status.startsWith('waiting')
        ? '#fdd663'
        : cur.status === 'error'
          ? '#f28b82'
          : '#8ab4f8';

      this.states.set(convId, cur);
      this.publish();
    } catch {
      // Ignore read errors
    }
  }

  publish() {
    // The title Antigravity itself shows, once it has one. Until then the first prompt stands in
    // (a brand-new conversation has no summary yet).
    const hubTitles = readHubTitles();
    for (const cur of this.states.values()) {
      const real = hubTitles.get(cur.rawId);
      if (real) cur.title = real.slice(0, 48);
    }
    // States are updated in place, so comparing against the previous array would compare an
    // object with itself and miss changes. Compare serialized snapshots instead.
    const next = [...this.states.values()].sort((a, b) => b.updatedAtMs - a.updatedAtMs);
    const json = JSON.stringify(next);
    this.threads = next;
    if (json !== this.lastPublishedJson) {
      this.lastPublishedJson = json;
      this.onChange();
    }
  }

  getThreads() {
    return this.threads;
  }
}
