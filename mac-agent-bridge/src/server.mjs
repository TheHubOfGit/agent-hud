import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { WebSocketServer } from 'ws';

import { AntigravityCollector } from './collectors/antigravity.mjs';
import { ClaudeCollector } from './collectors/claude.mjs';
import { CodexCollector } from './collectors/codex.mjs';
import { CodexBarCollector } from './collectors/codexbar.mjs';
import { T3CodeCollector } from './collectors/t3code.mjs';
import { dispatchWatchAction } from './dispatch.mjs';
import { LiveController } from './live.mjs';
import { transcribeWavBuffer } from './stt.mjs';

const PORT = Number(process.env.AGENT_HUD_PORT || 8765);
const BRIDGE_VERSION = '1.1.0';
const BRIDGE_STARTED_AT = Date.now();
const TOKEN_PATH = path.join(os.homedir(), '.agenthud-bridge-token');

function loadOrCreateToken() {
  if (process.env.AGENT_HUD_TOKEN) return process.env.AGENT_HUD_TOKEN.trim();
  if (fs.existsSync(TOKEN_PATH)) {
    const t = fs.readFileSync(TOKEN_PATH, 'utf8').trim();
    if (t.length >= 8) return t;
  }
  const token = crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(TOKEN_PATH, `${token}\n`, { mode: 0o600 });
  return token;
}

const AUTH_TOKEN = loadOrCreateToken();

// Webhook/custom terminal threads pushed via POST /event
const customThreads = new Map();
// Strict FIFO order for thread IDs currently awaiting approval/input
const fifoApprovalOrder = [];
let seq = 0;
let broadcastTimer = null;

const codexBar = new CodexBarCollector(() => scheduleBroadcast());
const antigravity = new AntigravityCollector(() => scheduleBroadcast());
const codex = new CodexCollector(() => scheduleBroadcast());
const claude = new ClaudeCollector(() => scheduleBroadcast());
const t3code = new T3CodeCollector(() => scheduleBroadcast());
const live = new LiveController((state) => broadcastLiveState(state));

function broadcastLiveState(state) {
  if (!wss || wss.clients.size === 0) return;
  const payload = JSON.stringify({
    type: 'live_state',
    serverTimeMs: Date.now(),
    ...state,
    confirmed: false,
    changed: false,
    boundary: false,
  });
  for (const client of wss.clients) {
    if (client.readyState === 1 && client.liveMode) {
      client.send(payload);
    }
  }
}

const TRIGGER_PATTERNS = {
  send: /(?:[\s,;:\-—]+|^)(send\s+it|send)[\s.!?,]*$/i,
  go: /(?:[\s,;:\-—]+|^)(go)[\s.!?,]*$/i,
  okay: /(?:[\s,;:\-—]+|^)(okay|ok)[\s.!?,]*$/i,
  all: /(?:[\s,;:\-—]+|^)(send\s+it|send|go|okay|ok)[\s.!?,]*$/i,
};

function matchTriggerWord(text, mode) {
  const clean = String(text || '').trim();
  const re = TRIGGER_PATTERNS[String(mode || '').toLowerCase()];
  if (!clean || !re) return { matched: false, word: '', stripped: clean };
  const m = clean.match(re);
  if (!m) return { matched: false, word: '', stripped: clean };
  const word = m[1].toLowerCase();
  const stripped = clean.slice(0, m.index).replace(/[\s,;:\-—]+$/, '').trim();
  return { matched: true, word, stripped };
}

function getLocalLanIp() {
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name] || []) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address;
    }
  }
  return '127.0.0.1';
}

function buildUnifiedSnapshot() {
  const merged = new Map();

  // 1. T3 Code first (owns underlying provider sessions it spawns)
  for (const t of t3code.getThreads()) merged.set(t.id, t);

  // 2. Antigravity, Codex, Claude — deduplicating any thread owned by T3 Code
  for (const list of [antigravity.getThreads(), codex.getThreads(), claude.getThreads()]) {
    for (const t of list) {
      if (t.rawId && t3code.ownsProviderSession(t.rawId)) continue;
      if (!merged.has(t.id)) merged.set(t.id, t);
    }
  }

  // 3. Custom terminal / webhook threads (expire idle ones after 2h)
  const now = Date.now();
  for (const [id, t] of customThreads.entries()) {
    if (t.status === 'idle' && now - t.updatedAtMs > 2 * 3600_000) {
      customThreads.delete(id);
      continue;
    }
    merged.set(id, t);
  }

  // Only sessions touched in the last 30 minutes are shown, capped at 6. Anything waiting on
  // the user or erroring is always kept; the rest are ranked by most recent interaction, and the
  // least recently used drop off.
  const RECENT_WINDOW_MS = 30 * 60_000;
  const MAX_THREADS = 6;
  // A session that crashed or was closed mid-turn can report "running"/"waiting" forever.
  // Demote states nothing has refreshed in a long time so they can't pin the watch.
  const STALE_AFTER = { running: 30 * 60_000, error: 2 * 3600_000, waiting: 6 * 3600_000 };
  for (const [id, t] of merged) {
    const age = now - (t.updatedAtMs || 0);
    const kind = String(t.status).startsWith('waiting') ? 'waiting' : t.status;
    if (STALE_AFTER[kind] && age > STALE_AFTER[kind]) {
      merged.set(id, { ...t, status: 'idle', prompt: null, options: [], kind: '' });
    }
  }
  const mustKeep = (t) => t.status === 'error' || String(t.status).startsWith('waiting');
  const all = [...merged.values()]
    .filter(t => mustKeep(t) || t.status === 'running' || now - (t.updatedAtMs || 0) < RECENT_WINDOW_MS)
    .sort((a, b) => (mustKeep(b) - mustKeep(a)) || ((b.updatedAtMs || 0) - (a.updatedAtMs || 0)))
    .slice(0, MAX_THREADS);

  // Maintain strict FIFO approval queue
  const currentlyWaitingIds = new Set(
    all.filter(t => t.status === 'waiting_approval' || t.status === 'waiting_input').map(t => t.id)
  );
  // Remove resolved IDs from FIFO queue
  for (let i = fifoApprovalOrder.length - 1; i >= 0; i--) {
    if (!currentlyWaitingIds.has(fifoApprovalOrder[i])) {
      fifoApprovalOrder.splice(i, 1);
    }
  }
  // Append newly arrived waiting IDs (sorted by startedAtMs ascending so earliest arrives first)
  const newlyWaiting = all
    .filter(t => currentlyWaitingIds.has(t.id) && !fifoApprovalOrder.includes(t.id))
    .sort((a, b) => (a.startedAtMs || 0) - (b.startedAtMs || 0));
  for (const t of newlyWaiting) fifoApprovalOrder.push(t.id);

  // Sort unified threads:
  // 1) FIFO waiting approvals/questions
  // 2) Errors
  // 3) Running Desktop threads
  // 4) Running CLI threads
  // 5) Idle / recent (Desktop prioritized)
  const rank = (t) => {
    const fifoIdx = fifoApprovalOrder.indexOf(t.id);
    if (fifoIdx !== -1) return fifoIdx;
    if (t.status === 'error') return 100;
    if (t.status === 'running' && t.source === 'desktop') return 200;
    if (t.status === 'running') return 300;
    if (t.source === 'desktop') return 400;
    return 500;
  };

  all.sort((a, b) => {
    const diff = rank(a) - rank(b);
    if (diff !== 0) return diff;
    // Stable within a rank: ordering by updatedAtMs made busy agents swap places on every
    // write, flipping whatever page the watch was showing.
    const started = (a.startedAtMs || 0) - (b.startedAtMs || 0);
    if (started !== 0) return started;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });

  const activeCount = all.filter(t => t.status === 'running' || t.status.startsWith('waiting')).length;
  const errorCount = all.filter(t => t.status === 'error').length;

  return {
    type: 'snapshot',
    seq: ++seq,
    serverTimeMs: now,
    macHost: os.hostname().replace(/\.local$/, ''),
    activeCount,
    errorCount,
    approvalQueue: [...fifoApprovalOrder],
    threads: all.slice(0, 12),
    quotas: codexBar.getQuotas(),
  };
}

function readJsonBody(req, limitBytes = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limitBytes) {
        req.destroy();
        reject(new Error('Body too large'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function readRawBody(req, limitBytes = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limitBytes) {
        req.destroy();
        reject(new Error('Audio too large'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function tokenMatches(candidate) {
  const a = Buffer.from(String(candidate || ''));
  const b = Buffer.from(AUTH_TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function isAuthorized(req, urlObj) {
  // Browsers always send Origin on cross-site fetches and WebSocket upgrades; the watch, hooks
  // and the Mac app never do. Refusing any Origin stops a web page (or DNS rebinding) from
  // reading snapshots or typing into apps through the loopback trust below.
  if (req.headers.origin) return false;
  // Loopback requests (local CLI hooks, the Mac app, curl tests) are trusted.
  const ip = req.socket.remoteAddress || '';
  if (ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1') return true;
  const headerToken = req.headers['x-hud-token'] || (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const queryToken = urlObj.searchParams.get('token') || '';
  return tokenMatches(headerToken) || tokenMatches(queryToken);
}

// The Live debug endpoints click and read UI in the front Mac app, so they are for local use only:
// a watch (or anything else on the LAN) holding the token cannot call them.
function isLoopbackReq(req) {
  const ip = req.socket.remoteAddress || '';
  return ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1';
}

const server = http.createServer(async (req, res) => {
  const urlObj = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  res.setHeader('Content-Type', 'application/json');

  if (!isAuthorized(req, urlObj)) {
    res.writeHead(401);
    res.end(JSON.stringify({ error: 'Unauthorized' }));
    return;
  }

  try {
    // Liveness/health for the Mac app and scripts (small, no thread contents).
    if (req.method === 'GET' && urlObj.pathname === '/health') {
      const snap = buildUnifiedSnapshot();
      res.writeHead(200);
      res.end(JSON.stringify({
        ok: true,
        version: BRIDGE_VERSION,
        pid: process.pid,
        port: PORT,
        startedAtMs: BRIDGE_STARTED_AT,
        uptimeSec: Math.round((Date.now() - BRIDGE_STARTED_AT) / 1000),
        mdns: Boolean(dnsSdProc && dnsSdProc.exitCode === null),
        clients: [...wss.clients].map(c => ({ ip: c.remoteIp || '', connectedAtMs: c.connectedAtMs || 0 })),
        threads: snap.threads.length,
        waiting: snap.approvalQueue.length,
        quotas: (snap.quotas || []).length,
      }));
      return;
    }

    if (req.method === 'GET' && urlObj.pathname === '/status') {
      res.writeHead(200);
      res.end(JSON.stringify(buildUnifiedSnapshot(), null, 2));
      return;
    }

    if (req.method === 'POST' && urlObj.pathname === '/refresh-quotas') {
      codexBar.refreshViaCli();
      res.writeHead(200);
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    // Custom webhook for terminal scripts, builds, or manual testing
    if (req.method === 'POST' && urlObj.pathname === '/event') {
      const body = await readJsonBody(req);
      const id = body.id || `terminal:${body.name || 'task'}`;
      const now = Date.now();
      if (body.clear) {
        customThreads.delete(id);
      } else {
        const status = body.status || 'running';
        customThreads.set(id, {
          id,
          rawId: id,
          provider: body.provider || 'terminal',
          providerLabel: body.providerLabel || 'Terminal',
          source: body.source || 'cli',
          title: String(body.title || body.name || 'Terminal Task').slice(0, 48),
          status,
          step: String(body.step || body.prompt || 'Running…').slice(0, 240),
          prompt: body.prompt || null,
          options: Array.isArray(body.options) ? body.options : (status.startsWith('waiting') ? ['Proceed', 'Deny'] : []),
          startedAtMs: body.startedAtMs || now,
          updatedAtMs: now,
          color: status.startsWith('waiting') ? '#fdd663' : status === 'error' ? '#f28b82' : '#a78bfa',
          canReply: Boolean(body.canReply),
          kind: body.kind || '',
          doneAtMs: body.done ? now : 0,
          multiSelect: Boolean(body.multiSelect),
        });
      }
      scheduleBroadcast();
      res.writeHead(200);
      res.end(JSON.stringify({ ok: true, id }));
      return;
    }

    // Native Claude Code hook receiver (supports synchronous PreToolUse Allow/Deny!)
    if (req.method === 'POST' && urlObj.pathname === '/hook/claude') {
      const body = await readJsonBody(req);
      const waitForDecision = urlObj.searchParams.get('sync') === '1';
      const result = await claude.handleHookEvent(body, waitForDecision);
      res.writeHead(200);
      res.end(JSON.stringify(result));
      return;
    }

    // Watch -> Mac two-way action (Proceed / Approve / Deny / Text Reply)
    if (req.method === 'POST' && urlObj.pathname === '/action') {
      const body = await readJsonBody(req);
      const snapshot = buildUnifiedSnapshot();
      const thread = snapshot.threads.find(t => t.id === body.threadId);  // never guess: a stale id must not reach another session
      // Webhook/test threads have no app behind them: resolve them here, never type anywhere.
      let result;
      try {
        result = thread && customThreads.has(thread.id)
          ? { ok: true, method: 'webhook', message: 'Recorded' }
          : await dispatchWatchAction({
            thread,
            action: body.action,
            text: body.text,
            claudeCollector: claude,
          });
      } catch (err) {
        result = { ok: false, message: err.message || 'Action failed' };
      }
      // Log the outcome (never the reply text itself) so failed sends are diagnosable.
      console.log(`[action] ${body.action || '?'} -> ${thread?.id || body.threadId || '?'}: ${result.ok ? 'ok' : 'FAILED'}${result.method ? ` via ${result.method}` : ''}${result.message ? ` (${result.message})` : ''}${body.text ? ` [${String(body.text).length} chars]` : ''}`);
      // If it was a custom webhook waiting item, mark resolved cleanly
      if (thread && customThreads.has(thread.id)) {
        const cur = customThreads.get(thread.id);
        cur.status = body.action === 'deny' ? 'idle' : 'running';
        cur.step = body.text ? `Reply: ${body.text}` : `${body.action} sent`;
        cur.prompt = null;
        cur.options = [];
        cur.updatedAtMs = Date.now();
        scheduleBroadcast();
      }
      res.writeHead(result.ok ? 200 : 400);
      res.end(JSON.stringify(result));
      return;
    }

    // Watch -> Mac raw WAV audio transcription (returns text for quick preview before Send)
    if (req.method === 'POST' && urlObj.pathname === '/voice-transcribe') {
      const wavBuf = await readRawBody(req);
      const transcript = await transcribeWavBuffer(wavBuf);
      res.writeHead(200);
      res.end(JSON.stringify({ ok: true, text: transcript }));
      return;
    }

    // Fast tail check for optional Live Mode end-of-dictation trigger word ("send", "go", "okay")
    if (req.method === 'POST' && urlObj.pathname === '/live-voice-check') {
      const mode = (urlObj.searchParams.get('mode') || 'off').toLowerCase();
      if (mode === 'off') {
        res.writeHead(200);
        res.end(JSON.stringify({ ok: true, triggered: false, word: '' }));
        return;
      }
      const wavBuf = await readRawBody(req);
      let tailText = '';
      try {
        tailText = (await transcribeWavBuffer(wavBuf)).trim();
      } catch {
        // Nothing audible in the tail (the usual case between phrases): not an error, just no trigger.
      }
      const match = matchTriggerWord(tailText, mode);
      res.writeHead(200);
      res.end(JSON.stringify({ ok: true, triggered: match.matched, word: match.word }));
      return;
    }

    // Live Mode: transcribe watch mic audio & immediately paste+send to the focused Mac app
    if (req.method === 'POST' && urlObj.pathname === '/live-voice') {
      const wavBuf = await readRawBody(req);
      let transcript = (await transcribeWavBuffer(wavBuf)).trim();
      const stripMode = (urlObj.searchParams.get('stripTrigger') || '').toLowerCase();
      if (stripMode && stripMode !== 'off') {
        const m = matchTriggerWord(transcript, stripMode);
        if (m.matched) {
          if (m.stripped) {
            transcript = m.stripped;
          } else if (m.word === 'send' || m.word === 'send it') {
            transcript = '';
          }
          // Standalone "go" or "okay" is kept as-is when nothing preceded it.
        }
      }
      if (!transcript) {
        res.writeHead(400);
        res.end(JSON.stringify({ ok: false, error: 'No speech detected' }));
        return;
      }
      const pressReturn = urlObj.searchParams.get('send') !== '0';
      // The app the watch was showing: the Mac refuses to paste if a different one is in front.
      const expectBundleId = urlObj.searchParams.get('expect') || '';
      const sent = await live.pasteSend(transcript, pressReturn, expectBundleId);
      console.log(`[live-voice] -> ${sent.appName || '?'}: ${sent.ok ? (sent.pastedOnly ? 'pasted, Return left to you' : 'ok') : 'FAILED'} [${transcript.length} chars]${stripMode ? ` (trigger:${stripMode})` : ''}${sent.error ? ` (${sent.error})` : ''}`);
      res.writeHead(sent.ok ? 200 : 400);
      res.end(JSON.stringify({ ...sent, text: transcript }));
      return;
    }

    if (req.method === 'GET' && urlObj.pathname === '/live/status') {
      const state = await live.inspect();
      res.writeHead(200);
      res.end(JSON.stringify(state));
      return;
    }

    if (req.method === 'POST' && urlObj.pathname === '/live/step') {
      const body = await readJsonBody(req);
      const delta = Number(body.delta) >= 0 ? 1 : -1;
      const targetLevel = typeof body.targetLevel === 'string' ? body.targetLevel : null;
      const targetIndex = typeof body.targetIndex === 'number' ? body.targetIndex : null;
      const result = await live.step(delta, targetLevel, targetIndex);
      console.log(`[live-step] ${targetLevel || (delta > 0 ? '+1' : '-1')} -> ${result.appName || '?'}: level=${result.level || '?'} confirmed=${result.confirmed} changed=${result.changed}${result.error ? ` (${result.error})` : ''}`);
      res.writeHead(result.ok ? 200 : 400);
      res.end(JSON.stringify(result));
      return;
    }

    if (req.method === 'GET' && urlObj.pathname === '/live/debug-ax' && isLoopbackReq(req)) {
      const targetApp = urlObj.searchParams.get('app') || '';
      const info = await live.debugAx(targetApp);
      res.writeHead(200);
      res.end(JSON.stringify(info, null, 2));
      return;
    }

    if (req.method === 'GET' && urlObj.pathname === '/live/debug-menu' && isLoopbackReq(req)) {
      const targetApp = urlObj.searchParams.get('app') || '';
      const info = await live.debugMenu(targetApp);
      res.writeHead(200);
      res.end(JSON.stringify(info, null, 2));
      return;
    }

    // Loopback-only preview helper (like POST /event) to test Live Mode dial visuals on the watch
    if (req.method === 'POST' && urlObj.pathname === '/live/preview' && isLoopbackReq(req)) {
      const body = await readJsonBody(req);
      broadcastLiveState({
        ok: true,
        appName: body.appName || 'Antigravity',
        bundleId: body.bundleId || '',
        provider: body.provider || 'antigravity',
        dialSupported: body.dialSupported !== false,
        level: body.level || 'Medium',
        levels: Array.isArray(body.levels) ? body.levels : ['Low', 'Medium', 'High'],
        index: typeof body.index === 'number' ? body.index : 1,
      });
      res.writeHead(200);
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    res.writeHead(404);
    res.end(JSON.stringify({ error: 'Not found' }));
  } catch (err) {
    res.writeHead(500);
    res.end(JSON.stringify({ error: err.message }));
  }
});

// WebSocket server with 5-second Ping/Pong dead-link detection
const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

server.on('upgrade', (req, socket, head) => {
  const urlObj = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (urlObj.pathname !== '/ws' || !isAuthorized(req, urlObj)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.connectedAtMs = Date.now();
  ws.remoteIp = String(req?.socket?.remoteAddress || '').replace(/^::ffff:/, '');
  // Without an 'error' listener a single bad socket would throw and crash the whole bridge.
  ws.on('error', (err) => console.warn(`[mac-agent-bridge] watch socket error: ${err.message}`));
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', async (raw) => {
    try {
      const msg = JSON.parse(raw.toString('utf8'));
      if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong', serverTimeMs: Date.now() }));
      } else if (msg.type === 'refresh_quotas') {
        codexBar.refreshViaCli();
      } else if (msg.type === 'live_mode') {
        ws.liveMode = Boolean(msg.enabled);
        if (ws.liveMode) {
          try {
            const state = await live.inspect();
            if (ws.readyState === 1) {
              ws.send(JSON.stringify({ type: 'live_state', serverTimeMs: Date.now(), ...state, confirmed: false, changed: false, boundary: false }));
            }
          } catch (err) {
            if (ws.readyState === 1) {
              ws.send(JSON.stringify({ type: 'live_state', serverTimeMs: Date.now(), ...live.getLastState(), confirmed: false, changed: false, boundary: false, error: err.message }));
            }
          }
        }
      } else if (msg.type === 'live_thinking_step') {
        const delta = Number(msg.delta) >= 0 ? 1 : -1;
        const targetLevel = typeof msg.targetLevel === 'string' ? msg.targetLevel : null;
        const targetIndex = typeof msg.targetIndex === 'number' ? msg.targetIndex : null;
        let result;
        try {
          result = await live.step(delta, targetLevel, targetIndex);
        } catch (err) {
          result = { ok: false, ...live.getLastState(), confirmed: false, changed: false, boundary: false, error: err.message };
        }
        console.log(`[live-step] ${targetLevel || (delta > 0 ? '+1' : '-1')} -> ${result.appName || '?'}: level=${result.level || '?'} confirmed=${result.confirmed} changed=${result.changed}${result.error ? ` (${result.error})` : ''}`);
        if (ws.readyState === 1) {
          ws.send(JSON.stringify({ type: 'live_state', serverTimeMs: Date.now(), ...result, reqId: msg.reqId || 0 }));
        }
      } else if (msg.type === 'action') {
        const snapshot = buildUnifiedSnapshot();
        const thread = snapshot.threads.find(t => t.id === msg.threadId);  // never guess: a stale id must not reach another session
        let result;
        try {
          result = thread && customThreads.has(thread.id)
          ? { ok: true, method: 'webhook', message: 'Recorded' }
          : await dispatchWatchAction({
          thread,
          action: msg.action,
          text: msg.text,
          claudeCollector: claude,
        });
        } catch (err) {
          // e.g. no Accessibility permission, Mac locked, osascript timeout: tell the watch.
          result = { ok: false, message: err.message || 'Action failed' };
        }
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'action_result', ...result }));
      }
    } catch {
      // Ignore malformed client messages
    }
  });

  // Immediately push authoritative snapshot on connect
  ws.send(JSON.stringify(buildUnifiedSnapshot()));
});

// Coalesced high-frequency broadcast (max ~8 Hz = 120ms coalescing window)
// Status transitions, logged once each, so "the watch showed X" can be traced to the bridge.
const lastLoggedStatus = new Map();
function logTransitions(snap) {
  const seen = new Set();
  for (const t of snap.threads) {
    seen.add(t.id);
    const prev = lastLoggedStatus.get(t.id);
    if (prev !== t.status) {
      console.log(`[state] ${t.id.slice(0, 24)} ${prev || 'new'} -> ${t.status} (${String(t.title || '').slice(0, 30)})`);
      lastLoggedStatus.set(t.id, t.status);
    }
  }
  for (const id of [...lastLoggedStatus.keys()]) {
    if (!seen.has(id)) { console.log(`[state] ${id.slice(0, 24)} dropped`); lastLoggedStatus.delete(id); }
  }
}

function scheduleBroadcast() {
  if (broadcastTimer) return;
  broadcastTimer = setTimeout(() => {
    broadcastTimer = null;
    const snap = buildUnifiedSnapshot();
    logTransitions(snap);
    if (wss.clients.size === 0) return;
    const payload = JSON.stringify(snap);
    for (const client of wss.clients) {
      if (client.readyState === 1) {
        client.send(payload);
      }
    }
  }, 120);
}

// 5-second Ping/Pong heartbeat
const heartbeatInterval = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 5000);

// Advertise via macOS Bonjour mDNS (_agenthud._tcp). dns-sd is a long-lived child; if it dies
// the watch can't rediscover the Mac after an IP change, so it is respawned with backoff.
// AGENT_HUD_NO_MDNS=1 disables advertising (useful for a second, test instance).
let dnsSdProc = null;
let mdnsStopped = false;
let mdnsBackoffMs = 2000;
let mdnsCleaned = false;
function startMdns() {
  if (process.env.AGENT_HUD_NO_MDNS === '1' || mdnsStopped) return;
  if (!mdnsCleaned) {
    // A bridge killed with SIGKILL leaves its dns-sd child behind, advertising a dead service.
    mdnsCleaned = true;
    try { spawnSync('pkill', ['-f', `^dns-sd -R Mac Agent HUD _agenthud._tcp local ${PORT}$`]); } catch {}
  }
  try {
    dnsSdProc = spawn('dns-sd', ['-R', 'Mac Agent HUD', '_agenthud._tcp', 'local', String(PORT)], {
      stdio: 'ignore',
    });
    const startedAt = Date.now();
    dnsSdProc.on('error', () => {});
    dnsSdProc.on('exit', () => {
      if (mdnsStopped) return;
      if (Date.now() - startedAt > 60_000) mdnsBackoffMs = 2000;
      setTimeout(startMdns, mdnsBackoffMs).unref();
      mdnsBackoffMs = Math.min(mdnsBackoffMs * 2, 60_000);
    });
  } catch {
    // Non-fatal if dns-sd unavailable
  }
}

// Listen first: if the port is taken (another bridge is already running) exit cleanly with a
// clear message instead of crashing after the collectors and mDNS have already started.
server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`[mac-agent-bridge] Port ${PORT} is already in use; another bridge is probably running. Exiting.`);
    process.exit(3);
  }
  console.error(`[mac-agent-bridge] HTTP server error: ${err.stack || err.message}`);
});

server.listen(PORT, '0.0.0.0', () => {
  codexBar.start();
  antigravity.start();
  codex.start();
  claude.start();
  t3code.start();
  live.start();
  startMdns();
  const lanIp = getLocalLanIp();
  console.log(`[mac-agent-bridge] v${BRIDGE_VERSION} pid ${process.pid} listening on http://${lanIp}:${PORT} (ws://${lanIp}:${PORT}/ws)`);
  console.log(`[mac-agent-bridge] mDNS ${process.env.AGENT_HUD_NO_MDNS === '1' ? 'disabled' : 'advertising _agenthud._tcp.local.'}`);
  console.log(`[mac-agent-bridge] Watch auth token is in ${TOKEN_PATH}`);
});

// Log, don't die, on stray async errors: one bad file read or child process shouldn't take
// the watch offline. A truly broken state still exits so the manager can restart it.
process.on('unhandledRejection', (reason) => {
  console.error(`[mac-agent-bridge] Unhandled rejection: ${reason?.stack || reason}`);
});
process.on('uncaughtException', (err) => {
  console.error(`[mac-agent-bridge] Uncaught exception: ${err.stack || err.message}`);
  if (err.code === 'EADDRINUSE') process.exit(3);
});

function shutdown() {
  clearInterval(heartbeatInterval);
  codexBar.stop();
  antigravity.stop();
  codex.stop();
  claude.stop();
  t3code.stop();
  live.stop();
  mdnsStopped = true;
  if (dnsSdProc) try { dnsSdProc.kill(); } catch {}
  // server.close() waits for open sockets, so a connected watch would keep this stale process
  // alive (and keep feeding it old state). Drop every client, then exit regardless.
  for (const client of wss.clients) { try { client.terminate(); } catch {} }
  server.closeAllConnections?.();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
