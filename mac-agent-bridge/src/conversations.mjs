// Conversation history for the wrist app (Agent Threads): every recent conversation across
// providers, the messages in one, and replies sent to that exact conversation.
//
// Unlike the HUD snapshot (live sessions only, capped at 6), this reads each provider's own
// records directly, so a conversation from this morning is still there to pick up again.
// Replies go to the conversation by id wherever the provider allows it, never into whatever
// chat happens to be open on the Mac:
//   T3 Code      `t3 thread send <id>` (queued as the next turn if the thread is busy)
//   Codex        `codex queue --thread <id> --resume`
//   Claude Code  `claude -p --resume <id>` in the session's folder (a headless turn)
//   Antigravity  `agy acp --app-data-dir antigravity` (resumes any conversation by id headlessly)
import { execFile, spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { DatabaseSync } from 'node:sqlite';

import { antigravityDbTurnState, readHubTitles } from './collectors/antigravity.mjs';
import { imageFromBase64, imageFromFile } from './images.mjs';
import { desktopAppName, typeIntoShownThread } from './dispatch.mjs';

const HOME = os.homedir();
const T3_DB = path.join(HOME, '.t3/userdata/statev2.sqlite');
const CODEX_STATE_DB = path.join(HOME, '.codex/state_5.sqlite');
const CODEX_HISTORY_DB = path.join(HOME, '.codex/thread_history_1.sqlite');
const CLAUDE_PROJECTS_DIR = path.join(HOME, '.claude/projects');
const AG_BRAIN_DIR = path.join(HOME, '.gemini/antigravity/brain');
const AG_CONV_DIR = path.join(HOME, '.gemini/antigravity/conversations');
const AG_SUMMARIES_DB = path.join(HOME, '.gemini/antigravity/conversation_summaries.db');

const MAX_AGE_MS = 7 * 24 * 3600_000;
const PER_PROVIDER = 15;
const MAX_TEXT = 6000; // per message; the watch shows a preview and the rest on demand
const EXEC_ENV = { ...process.env, PATH: `/opt/homebrew/bin:/usr/local/bin:${HOME}/.local/bin:${process.env.PATH || '/usr/bin:/bin'}` };
// Ids are opaque provider ids; no leading dash (option-like) and no ".." (path-like).
const ID_RE = /^(?!.*\.\.)[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

const PROVIDER_LABEL = { t3code: 'T3 Code', codex: 'Codex', claude: 'Claude Code', antigravity: 'Antigravity' };

// ---- helpers --------------------------------------------------------------------------------------

function findBinary(name, extra = []) {
  for (const p of extra) if (p && fs.existsSync(p)) return p;
  try {
    const r = spawnSync('/usr/bin/which', [name], { env: EXEC_ENV, encoding: 'utf8', timeout: 3000 });
    const out = (r.stdout || '').trim();
    if (r.status === 0 && out) return out;
  } catch {}
  return null;
}

let binCache = null;
function bins() {
  if (!binCache || Date.now() - binCache.at > 5 * 60_000) {
    binCache = {
      at: Date.now(),
      t3: findBinary('t3', [path.join(HOME, '.t3/bin/t3')]),
      codex: findBinary('codex', ['/opt/homebrew/bin/codex']),
      claude: findBinary('claude', ['/opt/homebrew/bin/claude', path.join(HOME, '.local/bin/claude'), path.join(HOME, '.claude/local/claude')]),
      agy: findBinary('agy', [path.join(HOME, '.local/bin/agy'), '/opt/homebrew/bin/agy', '/usr/local/bin/agy']),
    };
  }
  return binCache;
}

function toMs(v) {
  if (v == null) return 0;
  if (typeof v === 'number') return v > 1e12 ? v : v * 1000;
  const n = Date.parse(v);
  return Number.isFinite(n) ? n : 0;
}

function clip(text) {
  const s = String(text || '').trim();
  return s.length > MAX_TEXT ? { text: s.slice(0, MAX_TEXT).trimEnd() + '…', truncated: true } : { text: s, truncated: false };
}

function oneLine(text, n = 140) {
  return String(text || '').replace(/[`*_#>]+/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
}

/** Folds runs of assistant text and runs of tool activity into single items, then keeps the last [limit]. */
/** [sourceHasMore]: the read stopped before the start of the conversation (a full batch, or a partial file). */
function finalize(raw, limit, sourceHasMore = false) {
  const out = [];
  for (const m of raw) {
    if (!m || (!m.text && !m.images?.length)) continue;
    const prev = out[out.length - 1];
    const addImages = (into) => {
      for (const t of m.images || []) if (!into.images.includes(t) && into.images.length < 8) into.images.push(t);
    };
    if (prev && prev.role === m.role && m.role === 'assistant') {
      prev.text = [prev.text, m.text].filter(Boolean).join('\n\n');
      addImages(prev);
      prev.atMs = m.atMs || prev.atMs;
      continue;
    }
    if (prev && prev.role === 'activity' && m.role === 'activity') {
      prev.count += m.count || 1;
      for (const t of m.tools || []) if (!prev.tools.includes(t)) prev.tools.push(t);
      addImages(prev);
      prev.atMs = m.atMs || prev.atMs;
      continue;
    }
    out.push(m.role === 'activity'
      ? { ...m, count: m.count || 1, tools: [...(m.tools || [])], images: [...(m.images || [])] }
      : { ...m, images: [...(m.images || [])] });
  }
  // Merging turns can leave fewer bubbles than [limit] even when older records exist, so a cut-off
  // read also counts as "more" (it used to hide the Earlier messages button on long threads).
  const hasMore = out.length > limit || sourceHasMore;
  const seen = new Set();
  const uniq = (id, i) => { let k = id || `m${i}`; while (seen.has(k)) k = `${k}~`; seen.add(k); return k; };
  const page = out.slice(-limit).map((m, i) => {
    if (m.role === 'activity') {
      const names = m.tools.slice(0, 3).join(', ');
      return { id: uniq(m.id || `a${i}`, i), role: 'activity', text: m.count > 1 ? `${m.count} steps · ${names}` : (m.text || names), atMs: m.atMs || 0, truncated: false, images: m.images };
    }
    const c = clip(m.text);
    return { id: uniq(m.id, i), role: m.role, text: c.text, truncated: c.truncated, atMs: m.atMs || 0, images: m.images };
  });
  return { messages: page, hasMore };
}

/** Reads up to [maxBytes] from the end of a file, dropping a partial first line. */
function readTailLines(file, maxBytes) {
  const st = fs.statSync(file);
  const len = Math.min(st.size, maxBytes);
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, st.size - len);
    const lines = buf.toString('utf8').split('\n');
    if (st.size > len) lines.shift();
    return lines.filter(Boolean);
  } finally {
    fs.closeSync(fd);
  }
}

function readHeadLines(file, maxBytes) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(maxBytes);
    const n = fs.readSync(fd, buf, 0, maxBytes, 0);
    const lines = buf.subarray(0, n).toString('utf8').split('\n');
    if (n === maxBytes) lines.pop();
    return lines.filter(Boolean);
  } finally {
    fs.closeSync(fd);
  }
}

/** Thrown when a provider's database can't be read right now (e.g. locked mid-write). */
export class SourceBusyError extends Error {}

/**
 * Runs [fn] on a read-only connection. Waits up to a second for a writer (T3 and Codex write
 * constantly). [strict]: a failure throws SourceBusyError instead of returning null, so a reader of
 * one conversation can tell "busy" from "empty" (it used to blank the watch's chat on a lock).
 */
function withDb(file, fn, strict = false) {
  if (!fs.existsSync(file)) return null;
  let db;
  try {
    db = new DatabaseSync(file, { readOnly: true, timeout: 1000 });
    return fn(db);
  } catch (err) {
    if (strict) throw new SourceBusyError(err.message);
    return null;
  } finally {
    try { db?.close(); } catch {}
  }
}

// ---- T3 Code ---------------------------------------------------------------------------------------

const T3_ATTACHMENTS = path.join(HOME, '.t3/userdata/attachments');

/** A T3 image attachment (stored as <id>.<ext> next to the database) -> image token. */
function t3Attachment(a) {
  if (a?.type !== 'image' || !/^[A-Za-z0-9-]{8,200}$/.test(String(a.id || ''))) return null;
  const ext = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp', 'image/heic': 'heic' }[a.mimeType] || 'png';
  for (const e of [ext, 'png', 'jpg', 'jpeg', 'webp', 'gif', 'heic']) {
    const token = imageFromFile(path.join(T3_ATTACHMENTS, `${a.id}.${e}`));
    if (token) return token;
  }
  return null;
}

function t3MessageText(payloadJson) {
  try {
    const p = JSON.parse(payloadJson);
    const images = Array.isArray(p.attachments) ? p.attachments.map(t3Attachment).filter(Boolean) : [];
    return { text: typeof p.text === 'string' ? p.text : '', createdBy: p.createdBy || '', images };
  } catch {
    return { text: '', createdBy: '', images: [] };
  }
}

/** Preview line for a message: its words, or "Image" when it's only a picture. */
function previewOf(text, images) {
  return oneLine(text) || (images?.length ? (images.length > 1 ? `${images.length} images` : 'Image') : '');
}

function t3OwnedSessions() {
  return withDb(T3_DB, (db) => {
    const owned = new Set();
    try {
      for (const r of db.prepare('SELECT provider_session_id AS id FROM orchestration_v2_projection_provider_sessions').all()) if (r.id) owned.add(r.id);
    } catch {}
    try {
      for (const r of db.prepare('SELECT provider_thread_id AS id FROM orchestration_v2_projection_runs WHERE provider_thread_id IS NOT NULL').all()) if (r.id) owned.add(r.id);
    } catch {}
    // The provider's own session id (e.g. the Claude Code transcript a T3 thread drives).
    try {
      for (const r of db.prepare(`SELECT json_extract(payload_json, '$.nativeThreadRef.nativeId') AS id FROM orchestration_v2_projection_provider_threads`).all()) if (r.id) owned.add(String(r.id));
    } catch {}
    return owned;
  }) || new Set();
}

function listT3() {
  return withDb(T3_DB, (db) => {
    const rows = db.prepare(`
      SELECT thread_id, title, updated_at, payload_json FROM orchestration_v2_projection_threads
      WHERE deleted_at IS NULL AND archived_at IS NULL
      ORDER BY updated_at DESC LIMIT ?`).all(PER_PROVIDER * 2);
    const last = db.prepare(`
      SELECT role, payload_json, created_at FROM orchestration_v2_projection_messages
      WHERE thread_id = ? AND streaming = 0 ORDER BY created_at DESC LIMIT 4`);
    const projectNames = new Map();
    for (const sql of [
      `SELECT project_id, title AS name FROM orchestration_v2_projection_projects`,
      `SELECT project_id, title AS name FROM projection_projects`,
      `SELECT project_id, json_extract(payload_json, '$.title') AS name FROM orchestration_v2_projection_projects`,
    ]) {
      try {
        for (const p of db.prepare(sql).all()) if (p.project_id && p.name) projectNames.set(p.project_id, p.name);
        if (projectNames.size > 0) break;
      } catch {}
    }
    const out = [];
    for (const r of rows) {
      let payload = {};
      try { payload = JSON.parse(r.payload_json || '{}'); } catch {}
      if (payload?.lineage?.relationshipToParent === 'subagent') continue;
      const updatedAtMs = toMs(r.updated_at);
      if (Date.now() - updatedAtMs > MAX_AGE_MS) continue;
      let preview = '', previewRole = '';
      for (const m of last.all(r.thread_id)) {
        const { text, images } = t3MessageText(m.payload_json);
        if (text.trim() || images.length) { preview = previewOf(text, images); previewRole = m.role === 'user' ? 'user' : 'assistant'; break; }
      }
      const project = (payload.projectId && projectNames.get(payload.projectId)) || '';
      out.push({ provider: 't3code', rawId: r.thread_id, title: r.title || 'T3 Code thread', project, source: 'desktop', updatedAtMs, preview, previewRole });
      if (out.length >= PER_PROVIDER) break;
    }
    return out;
  }) || [];
}

function messagesT3(rawId, limit) {
  return withDb(T3_DB, (db) => {
    const rows = db.prepare(`
      SELECT message_id, role, created_at, payload_json FROM orchestration_v2_projection_messages
      WHERE thread_id = ? ORDER BY created_at DESC, message_id DESC LIMIT ?`).all(rawId, limit * 4 + 20).reverse();
    const raw = rows.map(r => {
      const { text, images } = t3MessageText(r.payload_json);
      return { id: r.message_id, role: r.role === 'user' ? 'user' : 'assistant', text, images, atMs: toMs(r.created_at) };
    });
    return finalize(raw, limit, rows.length >= limit * 4 + 20);
  }, true) || { messages: [], hasMore: false };
}

/**
 * T3's window title when it shows this thread ("<project> / <thread title>"), or null when the title
 * isn't unique enough to tell threads apart.
 */
export function t3WindowTitle(threadId) {
  return withDb(T3_DB, (db) => {
    const t = db.prepare(`SELECT title, payload_json FROM orchestration_v2_projection_threads WHERE thread_id = ?`).get(threadId);
    if (!t?.title) return null;
    let projectId = '';
    try { projectId = JSON.parse(t.payload_json || '{}').projectId || ''; } catch {}
    const twins = db.prepare(`
      SELECT COUNT(*) AS n FROM orchestration_v2_projection_threads
      WHERE title = ? AND deleted_at IS NULL AND archived_at IS NULL`).get(t.title).n;
    if (twins !== 1 || !projectId) return null;
    let project = null;
    for (const sql of [
      `SELECT title AS name FROM orchestration_v2_projection_projects WHERE project_id = ?`,
      `SELECT title AS name FROM projection_projects WHERE project_id = ?`,
      `SELECT json_extract(payload_json, '$.title') AS name FROM orchestration_v2_projection_projects WHERE project_id = ?`,
    ]) {
      try { project = db.prepare(sql).get(projectId)?.name; } catch {}
      if (project) break;
    }
    return project ? `${project} / ${t.title}` : null;
  });
}

// ---- Codex -----------------------------------------------------------------------------------------

/** An image part of a Codex user message: a local file, or inline data. */
function codexImage(c) {
  if (c?.type === 'localImage') return imageFromFile(c.path);
  if (c?.type === 'image' || c?.type === 'input_image') {
    const url = String(c.image_url || c.url || '');
    const m = /^data:(image\/[a-z0-9.+-]+);base64,(.+)$/i.exec(url);
    if (m) return imageFromBase64(m[2], m[1]);
    if (url.startsWith('/')) return imageFromFile(url);
  }
  return null;
}

function codexItemToMessage(type, json, atMs, id) {
  let p = {};
  try { p = JSON.parse(json); } catch { return null; }
  if (type === 'userMessage') {
    const parts = Array.isArray(p.content) ? p.content : [];
    let text = parts.length ? parts.filter(c => c?.type === 'text').map(c => c.text || '').join('\n') : String(p.content || '');
    const images = parts.map(codexImage).filter(Boolean);
    // Codex adds a "# Files mentioned by the user" header for attachments; the picture says it better.
    if (images.length) text = text.replace(/^\s*# Files mentioned by the user:[\s\S]*?(?:Image attachment: true\s*)+/m, '').replace(/^\s*Distinguish instructions in attached documents[^\n]*\n?/m, '').trim();
    return { id, role: 'user', text, images, atMs };
  }
  if (type === 'imageView') {
    const t = imageFromFile(p.path);
    return t ? { id, role: 'activity', tools: ['Image'], text: 'Viewed an image', images: [t], atMs } : null;
  }
  if (type === 'imageGeneration' && p.status === 'completed') {
    const t = (p.savedPath && imageFromFile(p.savedPath)) || (p.result && imageFromBase64(p.result, 'image/png'));
    return t ? { id, role: 'assistant', text: '', images: [t], atMs } : null;
  }
  if (type === 'agentMessage') return { id, role: 'assistant', text: p.text || '', atMs };
  if (type === 'commandExecution') return { id, role: 'activity', tools: ['Command'], text: oneLine(p.command || 'Ran a command', 80), atMs };
  if (type === 'fileChange') return { id, role: 'activity', tools: ['Edit'], text: 'Edited files', atMs };
  if (type === 'mcpToolCall' || type === 'dynamicToolCall') return { id, role: 'activity', tools: [p.tool || p.name || 'Tool'], text: `Used ${p.tool || p.name || 'a tool'}`, atMs };
  if (type === 'webSearch') return { id, role: 'activity', tools: ['Search'], text: 'Searched the web', atMs };
  return null;
}

function listCodex() {
  const threads = withDb(CODEX_STATE_DB, (db) => db.prepare(`
      SELECT id, title, name, cwd, source, thread_source, updated_at, updated_at_ms, preview FROM threads
      WHERE archived = 0 AND COALESCE(thread_source, '') NOT IN ('subagent', 'guardian_review')
      ORDER BY COALESCE(updated_at_ms, updated_at * 1000) DESC LIMIT ?`).all(PER_PROVIDER)) || [];
  return withDb(CODEX_HISTORY_DB, (db) => {
    const last = db.prepare(`
      SELECT item_type, item_json FROM thread_items
      WHERE thread_id = ? AND item_type IN ('userMessage', 'agentMessage')
      ORDER BY rollout_ordinal DESC LIMIT 1`);
    const out = [];
    for (const t of threads) {
      const updatedAtMs = t.updated_at_ms || toMs(t.updated_at);
      if (Date.now() - updatedAtMs > MAX_AGE_MS) continue;
      const row = last.get(t.id);
      const m = row ? codexItemToMessage(row.item_type, row.item_json, 0) : null;
      if (m && !m.text && m.images?.length) m.text = previewOf('', m.images);
      const src = String(t.source || '').toLowerCase();
      out.push({
        provider: 'codex', rawId: t.id,
        title: t.name || t.title || path.basename(t.cwd || 'Codex'),
        project: t.cwd ? path.basename(t.cwd) : '',
        source: src === 'cli' || src === 'exec' ? 'cli' : 'desktop',
        updatedAtMs,
        preview: oneLine(m?.text || t.preview || ''),
        previewRole: m?.role || '',
      });
    }
    return out;
  }) || [];
}

function messagesCodex(rawId, limit) {
  return withDb(CODEX_HISTORY_DB, (db) => {
    const rows = db.prepare(`
      SELECT item_id, item_type, item_json, created_at_ms FROM thread_items
      WHERE thread_id = ? AND item_type IN ('userMessage', 'agentMessage', 'commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'webSearch', 'imageView', 'imageGeneration')
      ORDER BY rollout_ordinal DESC LIMIT ?`).all(rawId, limit * 6 + 30).reverse();
    return finalize(rows.map(r => codexItemToMessage(r.item_type, r.item_json, r.created_at_ms, r.item_id)), limit, rows.length >= limit * 6 + 30);
  }, true) || { messages: [], hasMore: false };
}

// ---- Claude Code -----------------------------------------------------------------------------------

const CLAUDE_NOISE = /^(<command-|<local-command-|<system-reminder>|<bash-|Caveat: The messages below)/;

function claudeUserText(obj) {
  if (obj.isMeta || obj.isCompactSummary || obj.isSidechain) return '';
  const c = obj.message?.content;
  let text = '';
  if (typeof c === 'string') text = c;
  else if (Array.isArray(c)) {
    if (c.some(b => b?.type === 'tool_result')) return '';
    text = c.filter(b => b?.type === 'text').map(b => b.text || '').join('\n');
  }
  text = text.trim();
  if (!text || CLAUDE_NOISE.test(text)) return '';
  if (/^\[Request interrupted by user/.test(text)) return '';
  return text;
}

function claudeImage(b) {
  const src = b?.source;
  if (src?.type === 'base64') return imageFromBase64(src.data, src.media_type || 'image/png');
  return null;
}

function claudeLineToMessage(obj) {
  if (obj.isSidechain) return null;
  const at = toMs(obj.timestamp);
  if (obj.type === 'user') {
    const blocks = Array.isArray(obj.message?.content) ? obj.message.content : [];
    // Images a tool returned (a screenshot Claude looked at) show as a tool step with the picture.
    const viewed = blocks.filter(b => b?.type === 'tool_result' && Array.isArray(b.content))
      .flatMap(b => b.content.filter(x => x?.type === 'image').map(claudeImage)).filter(Boolean);
    if (viewed.length) return { id: obj.uuid, role: 'activity', tools: ['Image'], text: viewed.length > 1 ? `Viewed ${viewed.length} images` : 'Viewed an image', images: viewed, atMs: at };
    const text = claudeUserText(obj);
    const images = obj.isMeta || obj.isCompactSummary ? [] : blocks.filter(b => b?.type === 'image').map(claudeImage).filter(Boolean);
    return text || images.length ? { id: obj.uuid, role: 'user', text, images, atMs: at } : null;
  }
  if (obj.type === 'assistant') {
    const blocks = Array.isArray(obj.message?.content) ? obj.message.content : [];
    const text = blocks.filter(b => b?.type === 'text').map(b => b.text || '').join('\n').trim();
    if (text) return { id: obj.uuid, role: 'assistant', text, atMs: at };
    const tools = blocks.filter(b => b?.type === 'tool_use').map(b => b.name || 'Tool');
    if (tools.length) return { id: obj.uuid, role: 'activity', tools, count: tools.length, text: `Used ${tools[0]}`, atMs: at };
  }
  return null;
}

/** sessionId -> file path, refreshed by listClaude(). */
const claudeFiles = new Map();
const claudeSummaryCache = new Map();

function findClaudeFile(sessionId) {
  const known = claudeFiles.get(sessionId);
  if (known && fs.existsSync(known)) return known;
  try {
    for (const dir of fs.readdirSync(CLAUDE_PROJECTS_DIR)) {
      const f = path.join(CLAUDE_PROJECTS_DIR, dir, `${sessionId}.jsonl`);
      if (fs.existsSync(f)) { claudeFiles.set(sessionId, f); return f; }
    }
  } catch {}
  return null;
}

/** Title, last message, folder and permission mode of a transcript; cached by size + mtime. */
function claudeSummary(file, st) {
  const hit = claudeSummaryCache.get(file);
  if (hit && hit.size === st.size && hit.mtimeMs === st.mtimeMs) return hit.summary;
  let title = '', aiTitle = '', firstUser = '', cwd = '', permissionMode = '', last = null, lastAt = 0;
  const scan = (lines) => {
    for (const line of lines) {
      let o;
      try { o = JSON.parse(line); } catch { continue; }
      if (o.type === 'custom-title' && o.customTitle) title = String(o.customTitle);
      else if (o.type === 'ai-title' && o.aiTitle) aiTitle = String(o.aiTitle);
      if (o.cwd) cwd = o.cwd;
      if (o.type === 'user' && o.permissionMode) permissionMode = o.permissionMode;
      const m = claudeLineToMessage(o);
      if (m && m.role !== 'activity') {
        last = m;
        if (!firstUser && m.role === 'user') firstUser = m.text;
      }
      if (m) lastAt = m.atMs || lastAt;
    }
  };
  let tail = readTailLines(file, 384 * 1024);
  // One huge record (a big tool result) can fill the whole tail; read further back once.
  if (st.size > 384 * 1024 && tail.length < 3) tail = readTailLines(file, 4 * 1024 * 1024);
  scan(tail);
  if (!title && !aiTitle && st.size > 384 * 1024) {
    const headTitle = { title: '', aiTitle: '' };
    for (const line of readHeadLines(file, 64 * 1024)) {
      try {
        const o = JSON.parse(line);
        if (o.type === 'custom-title' && o.customTitle) headTitle.title = String(o.customTitle);
        else if (o.type === 'ai-title' && o.aiTitle) headTitle.aiTitle = String(o.aiTitle);
        if (!firstUser && o.type === 'user') firstUser = claudeUserText(o);
      } catch {}
    }
    title = headTitle.title; aiTitle = headTitle.aiTitle;
  }
  const summary = last ? {
    title: title || aiTitle || oneLine(firstUser, 60) || path.basename(cwd || 'Claude Code'),
    cwd, permissionMode,
    preview: previewOf(last.text, last.images), previewRole: last.role,
    updatedAtMs: lastAt || st.mtimeMs,
  } : null;
  claudeSummaryCache.set(file, { size: st.size, mtimeMs: st.mtimeMs, summary });
  if (claudeSummaryCache.size > 200) claudeSummaryCache.delete(claudeSummaryCache.keys().next().value);
  return summary;
}

function listClaude(ownedByT3) {
  const files = [];
  const now = Date.now();
  try {
    for (const dir of fs.readdirSync(CLAUDE_PROJECTS_DIR, { withFileTypes: true })) {
      if (!dir.isDirectory()) continue;
      const full = path.join(CLAUDE_PROJECTS_DIR, dir.name);
      let entries = [];
      try { entries = fs.readdirSync(full); } catch { continue; }
      for (const name of entries) {
        if (!name.endsWith('.jsonl')) continue;
        const sessionId = name.slice(0, -6);
        if (ownedByT3.has(sessionId)) continue;
        const file = path.join(full, name);
        try {
          const st = fs.statSync(file);
          if (now - st.mtimeMs < MAX_AGE_MS && st.size > 200) files.push({ sessionId, file, st });
        } catch {}
      }
    }
  } catch {}
  files.sort((a, b) => b.st.mtimeMs - a.st.mtimeMs);
  const out = [];
  for (const f of files.slice(0, PER_PROVIDER * 2)) {
    claudeFiles.set(f.sessionId, f.file);
    let s = null;
    try { s = claudeSummary(f.file, f.st); } catch {}
    if (!s) continue;
    out.push({ provider: 'claude', rawId: f.sessionId, title: s.title, project: s.cwd ? path.basename(s.cwd) : '', source: 'cli', updatedAtMs: s.updatedAtMs, preview: s.preview, previewRole: s.previewRole, cwd: s.cwd });
    if (out.length >= PER_PROVIDER) break;
  }
  return out;
}

function messagesClaude(rawId, limit) {
  const file = findClaudeFile(rawId);
  if (!file) return { messages: [], hasMore: false };
  // Transcripts hold big tool results; read more of the tail when asked for more history.
  const budget = Math.min(16, Math.max(2, Math.ceil(limit / 20))) * 1024 * 1024;
  const raw = [];
  for (const line of readTailLines(file, budget)) {
    try { raw.push(claudeLineToMessage(JSON.parse(line))); } catch {}
  }
  return finalize(raw, limit, fs.statSync(file).size > budget);
}

// ---- Antigravity -----------------------------------------------------------------------------------

function agTranscript(convId) {
  return path.join(AG_BRAIN_DIR, convId, '.system_generated/logs/transcript.jsonl');
}

function agUserText(content) {
  let s = String(content || '');
  const req = s.match(/<USER_REQUEST>([\s\S]*?)(<\/USER_REQUEST>|$)/i);
  if (req) s = req[1];
  return s
    .replace(/<ADDITIONAL_METADATA>[\s\S]*$/gi, '')
    .replace(/The current local time is:[\s\S]*$/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .trim();
}

function agLineToMessage(ev, i) {
  const at = toMs(ev.created_at);
  const id = `s${ev.step_index ?? i}`;
  if (ev.type === 'USER_INPUT') {
    const text = agUserText(ev.content);
    // Uploads are listed in the metadata as paths under the conversation's .user_uploaded folder.
    const images = [...new Set(String(ev.content || '').match(/\/[^\s"'<>]*\/\.user_uploaded\/[^\s"'<>]+\.(?:png|jpe?g|gif|webp|heic)/gi) || [])]
      .map(imageFromFile).filter(Boolean);
    return text || images.length ? { id, role: 'user', text, images, atMs: at } : null;
  }
  if (ev.type === 'PLANNER_RESPONSE') {
    const calls = Array.isArray(ev.tool_calls) ? ev.tool_calls : [];
    const text = String(ev.content || '').trim();
    if (text) return { id, role: 'assistant', text, atMs: at };
    if (calls.length) {
      const tools = calls.map(c => c.name || 'tool');
      return { id, role: 'activity', tools, count: calls.length, text: oneLine(calls[0].arguments?.toolSummary || `Used ${tools[0]}`, 80), atMs: at };
    }
  }
  return null;
}

const agSummaryCache = new Map();
const agIsSubagent = new Map(); // conversation id -> true when a parent agent started it

/** Sub-agent runs (swarm workers, image helpers) open with a system message, not the person's words. */
function agStartedByAgent(convId, file) {
  if (agIsSubagent.has(convId)) return agIsSubagent.get(convId);
  let sub = false;
  try {
    const first = readHeadLines(file, 4096)[0];
    sub = first ? JSON.parse(first).type !== 'USER_INPUT' : false;
  } catch {}
  agIsSubagent.set(convId, sub);
  return sub;
}

function listAntigravity() {
  const now = Date.now();
  const cands = [];
  try {
    for (const e of fs.readdirSync(AG_BRAIN_DIR, { withFileTypes: true })) {
      if (!e.isDirectory() || !ID_RE.test(e.name)) continue;
      const f = agTranscript(e.name);
      try {
        const st = fs.statSync(f);
        if (now - st.mtimeMs < MAX_AGE_MS && !agStartedByAgent(e.name, f)) cands.push({ id: e.name, f, st });
      } catch {}
    }
  } catch {}
  cands.sort((a, b) => b.st.mtimeMs - a.st.mtimeMs);
  const titles = readHubTitles();
  const out = [];
  for (const c of cands.slice(0, PER_PROVIDER)) {
    const hit = agSummaryCache.get(c.f);
    let s = hit && hit.size === c.st.size && hit.mtimeMs === c.st.mtimeMs ? hit.s : null;
    if (!s) {
      let last = null, firstUser = '', lastAt = 0;
      try {
        readTailLines(c.f, 256 * 1024).forEach((line, i) => {
          try {
            const m = agLineToMessage(JSON.parse(line), i);
            if (m && m.role !== 'activity') { last = m; if (!firstUser && m.role === 'user') firstUser = m.text; }
            if (m?.atMs) lastAt = m.atMs;
          } catch {}
        });
      } catch {}
      s = { last, firstUser, lastAt };
      agSummaryCache.set(c.f, { size: c.st.size, mtimeMs: c.st.mtimeMs, s });
      if (agSummaryCache.size > 200) agSummaryCache.delete(agSummaryCache.keys().next().value);
    }
    const wsDir = agWorkspaceDir(c.id);
    out.push({
      provider: 'antigravity', rawId: c.id,
      title: titles.get(c.id) || oneLine(s.firstUser, 60) || 'Antigravity conversation',
      project: wsDir && wsDir !== HOME ? path.basename(wsDir) : '',
      source: 'desktop',
      updatedAtMs: s.lastAt || c.st.mtimeMs,
      preview: previewOf(s.last?.text || '', s.last?.images), previewRole: s.last?.role || '',
    });
  }
  return out;
}

function agHasDb(convId) {
  return fs.existsSync(path.join(AG_CONV_DIR, `${convId}.db`));
}

/** First existing workspace directory recorded for an Antigravity conversation, else HOME. */
function agWorkspaceDir(convId) {
  const raw = withDb(AG_SUMMARIES_DB, (db) => {
    try {
      return db.prepare('SELECT workspace_uris FROM conversation_summaries WHERE conversation_id = ?').get(convId)?.workspace_uris || '';
    } catch {
      return '';
    }
  });
  if (raw) {
    try {
      const uris = JSON.parse(raw);
      if (Array.isArray(uris)) {
        for (const u of uris) {
          if (typeof u !== 'string' || !u.startsWith('file://')) continue;
          const p = decodeURIComponent(new URL(u).pathname);
          if (fs.existsSync(p) && fs.statSync(p).isDirectory()) return p;
        }
      }
    } catch {}
  }
  return HOME;
}

function messagesAntigravity(rawId, limit) {
  const f = agTranscript(rawId);
  if (!fs.existsSync(f)) return { messages: [], hasMore: false };
  const budget = Math.min(16, Math.max(2, Math.ceil(limit / 20))) * 1024 * 1024;
  const raw = readTailLines(f, budget).map((line, i) => {
    try { return agLineToMessage(JSON.parse(line), i); } catch { return null; }
  });
  return finalize(raw, limit, fs.statSync(f).size > budget);
}

// ---- store -----------------------------------------------------------------------------------------

export class ConversationStore {
  /** [getLiveThreads]: every thread the live collectors know, for status, questions and options. */
  constructor({ getLiveThreads, getTestThreads, onTestReply }) {
    this.getLiveThreads = getLiveThreads;
    // Test threads posted to /event (the same ones the desk HUD shows). They appear as conversations
    // so the whole question/reply flow can be tried without a real agent; replies are only recorded.
    this.getTestThreads = getTestThreads || (() => []);
    this.onTestReply = onTestReply || (() => {});
    this.inflight = new Map(); // conversation id -> { since, until, method, proc, error }
    this.listCache = null;
  }

  parseId(id) {
    const m = /^(t3code|codex|claude|antigravity):(.+)$/.exec(String(id || ''));
    if (!m || !ID_RE.test(m[2])) return null;
    return { provider: m[1], rawId: m[2] };
  }

  liveById() {
    const map = new Map();
    for (const t of this.getLiveThreads()) map.set(t.id, t);
    return map;
  }

  /** Merged list across providers, waiting-on-you first, then most recent. Cached for 1.5s. */
  list(limit = 40) {
    const now = Date.now();
    if (!this.listCache || now - this.listCache.at > 1500) {
      const owned = t3OwnedSessions();
      const rows = [];
      for (const fn of [listT3, listCodex, () => listClaude(owned), listAntigravity]) {
        try { rows.push(...fn()); } catch (err) { console.warn(`[conversations] list failed: ${err.message}`); }
      }
      this.listCache = { at: now, rows };
    }
    const live = this.liveById();
    const latestAg = this.listCache.rows.filter(r => r.provider === 'antigravity').sort((a, b) => b.updatedAtMs - a.updatedAtMs)[0]?.rawId;
    const out = this.listCache.rows.map(r => this.decorate(r, live.get(`${r.provider}:${r.rawId}`), latestAg));
    out.push(...this.getTestThreads().map(t => this.testConversation(t)));
    const waiting = (c) => c.status.startsWith('waiting') ? 1 : 0;
    out.sort((a, b) => (waiting(b) - waiting(a)) || (b.updatedAtMs - a.updatedAtMs));
    return out.slice(0, Math.max(1, Math.min(100, limit)));
  }

  decorate(row, live, latestAg) {
    const id = `${row.provider}:${row.rawId}`;
    const pending = this.pendingFor(id);
    let status = live?.status || 'idle';
    const perm = pending?.permission || null;
    if (perm) status = 'waiting_approval';
    else if (pending?.active && !status.startsWith('waiting')) status = 'running';
    const reply = this.replyRoute(row, live, latestAg);
    return {
      id,
      provider: row.provider,
      providerLabel: live?.providerLabel || PROVIDER_LABEL[row.provider] || row.provider,
      source: live?.source || row.source,
      title: oneLine(row.title || live?.title, 80) || 'Conversation',
      project: oneLine(row.project || '', 40),
      preview: row.preview || '',
      previewRole: row.previewRole || '',
      updatedAtMs: Math.max(row.updatedAtMs || 0, live?.updatedAtMs || 0),
      status,
      // T3 reports "<driver> working…" (e.g. "claudeAgent working…"); the watch just says Working.
      step: perm
        ? perm.prompt
        : pending?.active
          ? (pending.step || 'Working on your reply')
          : String(live?.step || '').replace(/^\w+ working…$/, 'Working…'),
      prompt: perm ? perm.prompt : (live?.prompt || null),
      options: perm ? perm.options : (Array.isArray(live?.options) ? live.options : []),
      kind: perm ? 'choice' : (live?.kind || ''),
      multiSelect: perm ? false : Boolean(live?.multiSelect),
      canAct: Boolean(perm) || (Boolean(live) && String(live.status || '').startsWith('waiting')),
      canReply: reply.ok,
      replyNote: reply.note,
      replyError: pending?.error || '',
    };
  }

  /** Whether (and how) a reply can reach exactly this conversation. */
  replyRoute(row, live, latestAg) {
    const b = bins();
    switch (row.provider) {
      case 't3code':
        return b.t3 ? { ok: true, method: 't3', note: '' } : { ok: false, note: 'T3 Code command line not found on the Mac' };
      case 'codex':
        return b.codex ? { ok: true, method: 'codex', note: '' } : { ok: false, note: 'Codex command line not found on the Mac' };
      case 'claude': {
        if (!b.claude) return { ok: false, note: 'Claude Code not found on the Mac' };
        const cwd = row.cwd || claudeSummaryFor(row.rawId)?.cwd;
        if (!cwd || !fs.existsSync(cwd)) return { ok: false, note: "This session's folder is gone" };
        return { ok: true, method: 'claude', note: '' };
      }
      case 'antigravity':
        if (b.agy && agHasDb(row.rawId)) return { ok: true, method: 'agy-acp', note: '' };
        return row.rawId === latestAg
          ? { ok: true, method: 'typed', note: 'Types into Antigravity (its newest chat)' }
          : { ok: false, note: b.agy ? 'This conversation has no local database to resume' : 'Install the Antigravity CLI (agy) to reply to older chats' };
      default:
        return { ok: false, note: '' };
    }
  }

  pendingFor(id) {
    const p = this.inflight.get(id);
    if (!p) return null;
    const now = Date.now();
    const active = p.proc ? p.proc.exitCode === null && !p.proc.killed : now < p.until;
    if (!active && !p.error && now - p.since > 10 * 60_000) { this.inflight.delete(id); return null; }
    return { active, error: p.error || '', step: p.step || '', permission: active ? (p.permission || null) : null };
  }

  testThread(id) {
    return this.getTestThreads().find(t => t.id === id) || null;
  }

  testConversation(t) {
    const log = Array.isArray(t.log) ? t.log : [];
    const last = log[log.length - 1];
    const waiting = String(t.status || '').startsWith('waiting');
    return {
      id: t.id,
      provider: t.provider || 'terminal',
      providerLabel: t.providerLabel || 'Test',
      source: t.source || 'cli',
      title: t.title || 'Test thread',
      preview: oneLine(waiting ? (t.prompt || t.step) : (last?.text || t.step || '')),
      previewRole: last?.role || 'assistant',
      updatedAtMs: t.updatedAtMs || 0,
      status: t.status || 'idle',
      step: t.step || '',
      prompt: t.prompt || null,
      options: Array.isArray(t.options) ? t.options : [],
      kind: t.kind || '',
      multiSelect: Boolean(t.multiSelect),
      canAct: waiting,
      canReply: true,
      replyNote: 'Test thread: replies are recorded, not sent anywhere',
      replyError: '',
    };
  }

  get(id, limit = 40) {
    const test = this.testThread(id);
    if (test) {
      const raw = (Array.isArray(test.log) ? test.log : []).map((m, i) => ({ id: `t${i}`, ...m }));
      return { conversation: this.testConversation(test), ...finalize(raw, Math.max(5, Math.min(300, Number(limit) || 40))) };
    }
    const parsed = this.parseId(id);
    if (!parsed) return null;
    const lim = Math.max(5, Math.min(300, Number(limit) || 40));
    const { provider, rawId } = parsed;
    let page;
    if (provider === 't3code') page = messagesT3(rawId, lim);
    else if (provider === 'codex') page = messagesCodex(rawId, lim);
    else if (provider === 'claude') page = messagesClaude(rawId, lim);
    else page = messagesAntigravity(rawId, lim);

    let row = this.list(100).find(c => c.id === id);
    if (!row) {
      // Older than the list window: still readable, described from the messages alone.
      const last = [...page.messages].reverse().find(m => m.role !== 'activity');
      const base = { provider, rawId, title: PROVIDER_LABEL[provider], source: 'desktop', updatedAtMs: last?.atMs || 0, preview: oneLine(last?.text), previewRole: last?.role || '' };
      if (provider === 'claude') base.cwd = claudeSummaryFor(rawId)?.cwd;
      row = this.decorate(base, this.liveById().get(id), null);
    }
    return { conversation: row, messages: page.messages, hasMore: page.hasMore };
  }

  /**
   * [clientId]: one id per reply composed on the watch. A retry of the same reply (a timed-out
   * request that actually landed, a double tap) gets the first result back instead of sending again.
   */
  async reply(id, text, clientId = '') {
    const key = clientId ? `${id}\u0000${clientId}` : '';
    if (key) {
      this.sentByClient ??= new Map();
      const prior = this.sentByClient.get(key);
      if (prior) return { ...(await prior), duplicate: true };
      const work = this.replyOnce(id, text);
      this.sentByClient.set(key, work);
      setTimeout(() => this.sentByClient.delete(key), 10 * 60_000).unref?.();
      const result = await work;
      if (!result.ok) this.sentByClient.delete(key); // a failed send may be retried for real
      return result;
    }
    return this.replyOnce(id, text);
  }

  async replyOnce(id, text) {
    const body = String(text || '').trim();
    if (this.testThread(id)) {
      if (!body) return { ok: false, message: 'Nothing to send' };
      this.onTestReply(id, body);
      return { ok: true, method: 'webhook', message: 'Recorded (test thread)' };
    }
    const parsed = this.parseId(id);
    if (!parsed) return { ok: false, message: 'Unknown conversation' };
    if (!body) return { ok: false, message: 'Nothing to send' };
    if (body.length > 8000) return { ok: false, message: 'Reply is too long' };
    let conv = this.list(100).find(c => c.id === id);
    if (!conv) {
      const g = this.get(id, 5);
      conv = g && g.messages.length ? g.conversation : null;
    }
    if (!conv) return { ok: false, message: 'That conversation is gone' };
    if (!conv.canReply) return { ok: false, message: conv.replyNote || "Replies can't reach this conversation" };
    const { provider, rawId } = parsed;
    const b = bins();
    try {
      if (provider === 't3code') {
        // Typed into T3 when it's showing this thread, so it reads as the person's own message
        // (`t3 thread send` labels it as coming from an agent, and the agent is told so).
        const shown = conv.status === 'idle' || conv.status === 'error' ? t3WindowTitle(rawId) : null;
        // Only a clear "not showing that thread" falls back to the CLI. Any other failure (a timeout,
        // possibly after Return was pressed) must not send the same text a second time.
        if (shown && await typeIntoShownThread(desktopAppName('t3code'), shown, body)) {
          this.markSent(id, 20_000);
          return { ok: true, method: 't3-typed', message: 'Sent to T3 Code' };
        }
        await run(b.t3, ['thread', 'send', rawId, '-'], body, 20_000);
        this.markSent(id, 20_000);
        return { ok: true, method: 't3', message: conv.status === 'running' ? 'Queued as its next turn' : 'Sent to T3 Code' };
      }
      if (provider === 'codex') {
        await run(b.codex, ['queue', '--thread', rawId, `--message=${body}`, '--resume'], null, 30_000);
        this.markSent(id, 20_000);
        return { ok: true, method: 'codex', message: 'Sent to Codex' };
      }
      if (provider === 'claude') {
        // The live collector only tracks the newest few sessions; also check this transcript itself.
        const f = findClaudeFile(rawId);
        const recentlyWritten = f && Date.now() - fs.statSync(f).mtimeMs < 20_000;
        if (conv.status === 'running' || recentlyWritten) return { ok: false, message: 'Claude is still working. Try again when it finishes.' };
        return this.resumeClaude(id, rawId, body);
      }
      if (provider === 'antigravity') {
        if (b.agy && agHasDb(rawId)) {
          if (conv.status === 'running' || antigravityDbTurnState(rawId) === 'working') {
            return { ok: false, message: 'Antigravity is still working. Try again when it finishes.' };
          }
          return await this.resumeAntigravity(id, rawId, body);
        }
        // Fallback when agy isn't installed: type into Antigravity's open empty chat box.
        const typed = await typeIntoShownThread('Antigravity', '', body, { anyBox: true });
        if (!typed) return { ok: false, message: "Couldn't find Antigravity's empty chat box. Open the chat on the Mac." };
        this.markSent(id, 15_000);
        return { ok: true, method: 'typed', message: 'Typed into Antigravity' };
      }
    } catch (err) {
      return { ok: false, message: String(err.message || 'Send failed').split('\n')[0].slice(0, 160) };
    }
    return { ok: false, message: 'Unsupported provider' };
  }

  markSent(id, ms) {
    const now = Date.now();
    this.inflight.set(id, { since: now, until: now + ms, method: 'queue' });
  }

  resumeClaude(id, sessionId, text) {
    const file = findClaudeFile(sessionId);
    const s = file ? claudeSummary(file, fs.statSync(file)) : null;
    if (!s?.cwd || !fs.existsSync(s.cwd)) return { ok: false, message: "This session's folder is gone" };
    const cur = this.inflight.get(id);
    if (cur?.proc && cur.proc.exitCode === null) return { ok: false, message: 'Still working on your last reply' };
    const args = ['-p', '--resume', sessionId, '--output-format', 'json'];
    // Keep the session's own permission mode: a session that could edit files on the Mac still can.
    if (['acceptEdits', 'bypassPermissions', 'plan'].includes(s.permissionMode)) args.push('--permission-mode', s.permissionMode);
    const proc = spawn(bins().claude, args, { cwd: s.cwd, env: EXEC_ENV, stdio: ['pipe', 'pipe', 'pipe'] });
    let stderr = '', stdout = '';
    proc.stdout.on('data', d => { if (stdout.length < 64_000) stdout += d; });
    proc.stderr.on('data', d => { if (stderr.length < 8000) stderr += d; });
    const entry = { since: Date.now(), until: 0, method: 'claude', proc, error: '' };
    this.inflight.set(id, entry);
    const killer = setTimeout(() => { try { proc.kill('SIGTERM'); } catch {} }, 30 * 60_000);
    proc.on('error', (err) => { entry.error = `Couldn't start Claude: ${err.message}`; });
    proc.on('exit', (code) => {
      clearTimeout(killer);
      let isError = code !== 0;
      let resultText = '';
      try {
        const out = JSON.parse(stdout);
        isError = isError || out?.is_error === true;
        resultText = typeof out?.result === 'string' ? out.result : '';
      } catch {}
      if (isError) entry.error = oneLine(stderr || resultText || 'Claude stopped with an error', 160);
      console.log(`[conversations] claude resume ${sessionId.slice(0, 8)} exited ${code}${entry.error ? ` (${entry.error})` : ''}`);
    });
    proc.stdin.end(text);
    return { ok: true, method: 'claude', message: 'Claude is working on it' };
  }

  /**
   * Resumes any Antigravity desktop conversation by id via `agy acp --app-data-dir antigravity`.
   * Writes directly to the desktop app's store (works for every chat, even when the Mac is locked).
   * Resolves once `session/resume` succeeds and `session/prompt` has been dispatched; the turn then
   * runs in the background while live progress and any permission requests are exposed to the watch.
   */
  resumeAntigravity(id, convId, text) {
    const cur = this.inflight.get(id);
    if (cur?.proc && cur.proc.exitCode === null) {
      return Promise.resolve({ ok: false, message: 'Still working on your last reply' });
    }
    const cwd = agWorkspaceDir(convId);
    const proc = spawn(bins().agy, ['acp', '--app-data-dir', 'antigravity'], {
      cwd,
      env: EXEC_ENV,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    const entry = { since: Date.now(), until: 0, method: 'agy-acp', proc, error: '', step: 'Starting Antigravity…', permission: null };
    this.inflight.set(id, entry);
    let stderr = '';
    proc.stderr.on('data', d => { if (stderr.length < 8000) stderr += d; });
    const sendRpc = (obj) => {
      try { if (proc.stdin && !proc.stdin.destroyed) proc.stdin.write(`${JSON.stringify(obj)}\n`); } catch {}
    };
    const stopProc = () => {
      try { proc.stdin?.end(); } catch {}
      setTimeout(() => { try { if (proc.exitCode === null) proc.kill('SIGTERM'); } catch {} }, 400).unref?.();
    };

    const runKiller = setTimeout(() => {
      entry.error = 'Antigravity timed out after 30 minutes';
      try { proc.kill('SIGTERM'); } catch {}
    }, 30 * 60_000);
    runKiller.unref?.();

    return new Promise((resolve) => {
      let settled = false;
      const finishInit = (result) => {
        if (settled) return;
        settled = true;
        clearTimeout(initTimer);
        resolve(result);
      };
      const initTimer = setTimeout(() => {
        if (!settled) {
          this.inflight.delete(id);
          try { proc.kill('SIGTERM'); } catch {}
          finishInit({ ok: false, message: 'Antigravity took too long to open this conversation' });
        }
      }, 25_000);

      const rl = readline.createInterface({ input: proc.stdout });
      rl.on('line', (line) => {
        let msg;
        try { msg = JSON.parse(line); } catch { return; }

        if (msg.id === 1 && !msg.method) {
          if (msg.error) {
            this.inflight.delete(id);
            stopProc();
            finishInit({ ok: false, message: oneLine(msg.error.message || 'Antigravity failed to start', 160) });
            return;
          }
          sendRpc({ jsonrpc: '2.0', id: 2, method: 'session/resume', params: { sessionId: convId, cwd, mcpServers: [] } });
          return;
        }

        if (msg.id === 2 && !msg.method) {
          if (msg.error) {
            this.inflight.delete(id);
            stopProc();
            finishInit({ ok: false, message: oneLine(msg.error.message || "Couldn't open that Antigravity conversation", 160) });
            return;
          }
          entry.step = 'Thinking…';
          sendRpc({ jsonrpc: '2.0', id: 3, method: 'session/prompt', params: { sessionId: convId, prompt: [{ type: 'text', text }] } });
          finishInit({ ok: true, method: 'agy-acp', message: 'Antigravity is working on it' });
          return;
        }

        if (msg.id === 3 && !msg.method) {
          entry.permission = null;
          if (msg.error) entry.error = oneLine(msg.error.message || 'Antigravity stopped with an error', 160);
          stopProc();
          return;
        }

        if (msg.method === 'session/update') {
          const u = msg.params?.update;
          if (!u) return;
          if (u.sessionUpdate === 'tool_call') {
            entry.step = oneLine(u.args?.toolSummary || u.title || `Running ${u.name || 'tool'}`, 80);
          } else if (u.sessionUpdate === 'agent_message_chunk') {
            entry.step = 'Writing reply…';
          } else if (u.sessionUpdate === 'agent_thought_chunk') {
            entry.step = 'Thinking…';
          }
          return;
        }

        if (msg.id !== undefined && msg.method === 'session/request_permission') {
          const params = msg.params || {};
          const rawOpts = Array.isArray(params.options) ? params.options : [];
          const optionList = rawOpts.slice(0, 4).map(o => ({
            optionId: String(o.optionId || ''),
            label: oneLine(o.name || o.optionId || 'Allow', 40),
            kind: String(o.kind || '').toLowerCase(),
          })).filter(o => o.optionId);
          const tc = params.toolCall || {};
          const prompt = oneLine(tc.title || tc.rawInput?.CommandLine || tc.kind || 'Approve action in Antigravity?', 200);
          entry.permission = {
            rpcId: msg.id,
            prompt,
            options: optionList.length ? optionList.map(o => o.label) : ['Allow', 'Deny'],
            optionList,
          };
        }
      });

      proc.on('error', (err) => {
        entry.error = `Couldn't start Antigravity: ${err.message}`;
        if (!settled) {
          this.inflight.delete(id);
          finishInit({ ok: false, message: entry.error });
        }
      });

      proc.on('exit', (code) => {
        clearTimeout(runKiller);
        entry.permission = null;
        if (!settled) {
          this.inflight.delete(id);
          const msg = oneLine(stderr || `Antigravity exited (${code})`, 160);
          finishInit({ ok: false, message: msg });
        } else if (code && code !== 0 && !entry.error) {
          entry.error = oneLine(stderr || `Antigravity stopped (${code})`, 160);
        }
        console.log(`[conversations] antigravity acp ${convId.slice(0, 8)} exited ${code}${entry.error ? ` (${entry.error})` : ''}`);
      });

      sendRpc({
        jsonrpc: '2.0',
        id: 1,
        method: 'initialize',
        params: { protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: 'agent-hud', version: '1.1.0' } },
      });
    });
  }

  /** Answers a pending ACP permission request for a headless Antigravity turn, or returns null. */
  resolvePermission(id, action, text = '') {
    const entry = this.inflight.get(id);
    const perm = entry?.permission;
    if (!perm || !entry.proc || entry.proc.exitCode !== null) return null;
    const act = String(action || 'reply').toLowerCase();
    const want = String(text || '').trim().toLowerCase();
    const list = perm.optionList || [];
    let chosen = (want && list.find(o => o.label.toLowerCase() === want))
      || (act === 'deny'
        ? list.find(o => o.kind.includes('reject') || /deny|reject|cancel/i.test(o.label))
        : list.find(o => o.kind.includes('allow') || /allow|approve|proceed/i.test(o.label)))
      || list[0];
    const outcome = chosen
      ? { outcome: 'selected', optionId: chosen.optionId }
      : { outcome: 'cancelled' };
    try {
      entry.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: perm.rpcId, result: { outcome } })}\n`);
    } catch {
      return { ok: false, method: 'agy-acp', message: 'Antigravity session already closed' };
    }
    entry.permission = null;
    entry.step = 'Continuing…';
    return { ok: true, method: 'agy-acp', message: `${chosen?.label || act} sent to Antigravity` };
  }
}

function claudeSummaryFor(sessionId) {
  const f = findClaudeFile(sessionId);
  if (!f) return null;
  try { return claudeSummary(f, fs.statSync(f)); } catch { return null; }
}

function run(bin, args, stdin, timeout) {
  if (!bin) return Promise.reject(new Error('Command not found on the Mac'));
  return new Promise((resolve, reject) => {
    const child = execFile(bin, args, { timeout, env: EXEC_ENV }, (err, stdout, stderr) => {
      if (err) return reject(new Error((stderr || '').trim() || err.message));
      resolve(String(stdout || '').trim());
    });
    if (stdin != null) child.stdin.end(stdin);
    else child.stdin.end();
  });
}
