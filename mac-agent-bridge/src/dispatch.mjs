import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CODEX_MONITOR = path.join(os.homedir(), '.gemini/config/skills/codex-monitor/scripts/codex_monitor.py');

// GUI-launched processes get a minimal PATH; make Homebrew tools (python3, codex) findable.
const EXEC_ENV = { ...process.env, PATH: `/opt/homebrew/bin:/usr/local/bin:${process.env.PATH || '/usr/bin:/bin'}` };

function runExec(cmd, args, timeout = 10_000) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout, env: EXEC_ENV }, (err, stdout, stderr) => {
      if (err) return reject(new Error(stderr?.trim() || err.message));
      resolve(stdout.trim());
    });
  });
}

// JXA script run as `osascript -l JavaScript - <app> <mode> <text>`. Arguments arrive via argv,
// so reply text is never spliced into script source (no quoting/injection issues).
// mode "paste": save every clipboard item (all types), paste the text + Return, then restore.
// mode "proceed": click a Proceed/Approve/Allow/Continue button, else press Return.
// mode "choose": click the button labelled exactly <text> (an answer to an open question); if the
// app isn't showing that question, nothing is clicked and it fails.
const DESKTOP_SCRIPT = `
ObjC.import('AppKit');
function run(argv) {
  const appName = argv[0], mode = argv[1], text = argv[2] || '';
  const se = Application('System Events');
  const app = Application(appName);
  if (!app.running()) throw new Error(appName + ' is not running');
  if (mode === 'compose') {
    // Types into the message box of the conversation the front window shows, only if that is the
    // expected one (argv[3] = exact window title) and the box holds no draft of the person's.
    const expect = argv[3] || '';
    const proc = se.processes.byName(appName);
    const win = proc.windows[0];
    let title = '';
    try { title = win.name() || ''; } catch (e) {}
    if (!expect || title !== expect) throw new Error('THREAD_NOT_SHOWN');
    const all = win.entireContents();
    let box = null;
    for (let i = 0; i < all.length; i++) {
      let role = '';
      try { role = all[i].role(); } catch (e) { continue; }
      if (role !== 'AXTextArea' && role !== 'AXTextField') continue;
      let d = '';
      try { d = all[i].description() || ''; } catch (e) {}
      if (/^Message\b/.test(d)) { box = all[i]; break; }
    }
    if (!box) throw new Error('THREAD_NOT_SHOWN');
    let draft = '';
    try { draft = String(box.value() || ''); } catch (e) {}
    if (draft.trim()) throw new Error('DRAFT_IN_BOX');
    app.activate(); delay(0.25);
    try { box.focused = true; } catch (e) {}
    delay(0.15);
    const pb0 = $.NSPasteboard.generalPasteboard;
    const keep = [];
    const its = pb0.pasteboardItems;
    for (let i = 0; i < its.count; i++) {
      const it = its.objectAtIndex(i);
      const copy = $.NSPasteboardItem.alloc.init;
      const types = it.types;
      for (let j = 0; j < types.count; j++) {
        const t = types.objectAtIndex(j);
        const dd = it.dataForType(t);
        if (dd && !dd.isNil()) copy.setDataForType(dd, t);
      }
      keep.push(copy);
    }
    pb0.clearContents;
    pb0.setStringForType($(text), $.NSPasteboardTypeString);
    se.keystroke('v', { using: 'command down' });
    delay(0.2);
    let typed = '';
    try { typed = String(box.value() || ''); } catch (e) {}
    if (typed.trim() !== text.trim()) {
      // Didn't land in that box: clear what we added and let the caller fall back.
      se.keystroke('a', { using: 'command down' }); se.keyCode(51);
      pb0.clearContents; if (keep.length) pb0.writeObjects($(keep));
      throw new Error('THREAD_NOT_SHOWN');
    }
    se.keyCode(36);
    delay(0.3);
    pb0.clearContents;
    if (keep.length) pb0.writeObjects($(keep));
    return 'typed';
  }
  if (mode === 'choose') {
    app.activate(); delay(0.3);
    const want = text.trim().toLowerCase();
    const proc = se.processes.byName(appName);
    const wins = proc.windows();
    for (let w = 0; w < wins.length; w++) {
      const all = wins[w].entireContents();
      for (let i = 0; i < all.length; i++) {
        const el = all[i];
        let role = '';
        try { role = el.role(); } catch (e) { continue; }
        if (role !== 'AXButton' && role !== 'AXRadioButton' && role !== 'AXCheckBox') continue;
        let names = [];
        try { names.push(el.name() || ''); } catch (e) {}
        try { names.push(el.description() || ''); } catch (e) {}
        try { names.push(el.title() || ''); } catch (e) {}
        if (names.some(n => String(n).trim().toLowerCase() === want ||
            String(n).trim().toLowerCase().startsWith(want + ' '))) {
          el.click();
          return 'chose';
        }
      }
    }
    throw new Error('QUESTION_NOT_SHOWN');
  }
  if (mode === 'proceed') {
    app.activate(); delay(0.25);
    const proc = se.processes.byName(appName);
    try {
      const btn = proc.windows[0].buttons.whose({ _or: [
        { name: { _contains: 'Proceed' } }, { name: { _contains: 'Approve' } },
        { name: { _contains: 'Allow' } }, { name: { _contains: 'Continue' } } ] })[0];
      btn.click();
      return 'clicked';
    } catch (e) {
      se.keyCode(36);
      return 'return';
    }
  }
  const pb = $.NSPasteboard.generalPasteboard;
  const saved = [];
  const items = pb.pasteboardItems;
  for (let i = 0; i < items.count; i++) {
    const it = items.objectAtIndex(i);
    const copy = $.NSPasteboardItem.alloc.init;
    const types = it.types;
    for (let j = 0; j < types.count; j++) {
      const t = types.objectAtIndex(j);
      const d = it.dataForType(t);
      if (d && !d.isNil()) copy.setDataForType(d, t);
    }
    saved.push(copy);
  }
  pb.clearContents;
  pb.setStringForType($(text), $.NSPasteboardTypeString);
  app.activate(); delay(0.3);
  se.keystroke('v', { using: 'command down' });
  delay(0.15);
  se.keyCode(36);
  delay(0.4);
  pb.clearContents;
  if (saved.length) pb.writeObjects($(saved));
  return 'pasted';
}
`;

export async function sendToDesktopApp(appName, text, clickProceed = false) {
  const mode = clickProceed ? 'proceed' : 'paste';
  try {
    return await runExecStdin('osascript', ['-l', 'JavaScript', '-', appName, mode, String(text || '')], DESKTOP_SCRIPT, 10_000);
  } catch (err) {
    throw new Error(explainOsascriptError(err.message, appName));
  }
}

// The Swift Accessibility helper (live-ctrl) walks an app's window in well under a second. The
// AppleScript fallback below scans every element and timed out on long T3 conversations.
let axHelper = null;
export function setAxHelper(helper) { axHelper = helper; }

// Returns the exact window title an app shows for a thread (T3: "<project> / <title>"), or ''.
let windowTitleFor = null;
export function setWindowTitleResolver(fn) { windowTitleFor = fn; }

/**
 * Types [text] into the conversation shown in [appName]'s front window, if its title is exactly
 * [windowTitle] and its message box is empty. Resolves false (nothing typed) otherwise.
 */
export async function typeIntoShownThread(appName, windowTitle, text, opts = {}) {
  if (axHelper) {
    const r = await axHelper.composeIn(appName, windowTitle, text, opts);
    if (r?.ok) return true;
    if (r?.notShown) return false;
    throw new Error(r?.error || 'Typing into the app failed');
  }
  try {
    await runExecStdin('osascript', ['-l', 'JavaScript', '-', appName, 'compose', String(text || ''), String(windowTitle || '')], DESKTOP_SCRIPT, 20_000);
    return true;
  } catch (err) {
    if (/THREAD_NOT_SHOWN|DRAFT_IN_BOX|is not running/.test(err.message)) return false;
    throw new Error(explainOsascriptError(err.message, appName));
  }
}

/**
 * Clicks the answer labelled [label] in the app's open question. [windowTitle], when known, must be
 * the front window's exact title (the conversation it shows), so a choice with the same label in a
 * different conversation is never pressed.
 */
export async function chooseInDesktopApp(appName, label, windowTitle = '') {
  if (axHelper) {
    const r = await axHelper.pressChoice(appName, label, windowTitle);
    if (r?.ok) return 'chose';
    if (r?.notShown) throw new Error(`Open this conversation in ${appName} on the Mac to answer it`);
    throw new Error(r?.error || 'Answering in the app failed');
  }
  try {
    return await runExecStdin('osascript', ['-l', 'JavaScript', '-', appName, 'choose', String(label || '')], DESKTOP_SCRIPT, 25_000);
  } catch (err) {
    if (/QUESTION_NOT_SHOWN/.test(err.message)) {
      throw new Error(`Open this conversation in ${appName} on the Mac to answer it`);
    }
    throw new Error(explainOsascriptError(err.message, appName));
  }
}

// Each app's name as installed. T3 Code ships as "T3 Code (Beta)", and an app named "T3 Code"
// doesn't exist, so AppleScript failed with "Application can't be found (-2700)".
const APP_NAMES = {
  antigravity: ['Antigravity'],
  claude: ['Claude'],
  codex: ['Codex'],
  t3code: ['T3 Code (Beta)', 'T3 Code'],
};

export function desktopAppName(provider) {
  const names = APP_NAMES[provider];
  if (!names) return null;
  for (const n of names) {
    if (fs.existsSync(`/Applications/${n}.app`) || fs.existsSync(path.join(os.homedir(), 'Applications', `${n}.app`))) return n;
  }
  return names[0];
}

/** Turns macOS privacy errors into something the watch can show and the user can act on. */
function explainOsascriptError(msg, appName) {
  const m = String(msg || '');
  if (/not allowed to send keystrokes|assistive access|1002/i.test(m)) {
    return 'Mac needs Accessibility permission for Agent HUD (System Settings > Privacy & Security > Accessibility)';
  }
  if (/Not authorized to send Apple events|-1743|-10004/i.test(m)) {
    return 'Mac needs Automation permission for Agent HUD to control System Events';
  }
  if (/is not running/i.test(m)) return `${appName} isn't open on the Mac`;
  if (/timed out|ETIMEDOUT|SIGTERM/i.test(m)) return `${appName} didn't respond (is the Mac locked?)`;
  return m.split('\n')[0].slice(0, 160) || 'Typing into the app failed';
}

function runExecStdin(cmd, args, stdin, timeout) {
  return new Promise((resolve, reject) => {
    const child = execFile(cmd, args, { timeout }, (err, stdout, stderr) => {
      if (err) return reject(new Error(stderr?.trim() || err.message));
      resolve(stdout.trim());
    });
    child.stdin.end(stdin);
  });
}

// Questions answered recently, per thread. The desk HUD and the wrist app can both show the same
// question; once one answers it, the thread's choices are gone, and the other watch's tap (sent as
// "reply" + the choice's label) would otherwise be typed into the chat as a message.
const recentAnswers = new Map(); // thread id -> { options: Set<string>, at }
const ANSWER_MEMORY_MS = 10 * 60_000;

/** Identifies one question: the same prompt with the same choices. */
function questionKey(thread) {
  return `${thread.prompt || ''}\u0000${(thread.options || []).join('\u0001')}`;
}

function rememberAnswered(thread) {
  recentAnswers.set(thread.id, { key: questionKey(thread), options: new Set(thread.options || []), at: Date.now() });
}

/**
 * Called with every thread the collectors report. Once an answered thread is seen *not* waiting, its
 * question was resolved: forget it, so the same question asked again later ("Approve npm test?"
 * twice) is a new question, not a late answer.
 */
export function observeThreads(threads) {
  if (recentAnswers.size === 0) return;
  for (const t of threads) {
    const prev = recentAnswers.get(t.id);
    if (prev && !String(t.status || '').startsWith('waiting')) prev.resolved = true;
  }
}

/** A thread that no longer exists (a cleared test thread) takes its answered question with it. */
export function forgetThread(id) {
  recentAnswers.delete(id);
}

function isLateAnswer(thread, act, text) {
  const prev = recentAnswers.get(thread.id);
  if (!prev || Date.now() - prev.at > ANSWER_MEMORY_MS) return false;
  const waiting = String(thread.status || '').startsWith('waiting');
  if (waiting) {
    // Same question, never seen resolved: the collectors are lagging (they poll every ~2s); refuse.
    // Same question after it was resolved: it was asked again; that's a new question.
    if (questionKey(thread) === prev.key && !prev.resolved) return true;
    return false;
  }
  // Not waiting: a tap on the old question from the other watch. Refuse it; never let its label
  // turn into a typed chat message.
  if (act === 'proceed' || act === 'approve' || act === 'deny') return true;
  return act === 'reply' && Boolean(text) && prev.options.has(text);
}

// Questions with an answer in flight right now (thread id), so two watches tapping at the same
// moment can't both answer: the second is refused while the first is still being delivered.
const answering = new Set();

function isAnswer(thread, act, text) {
  return act === 'proceed' || act === 'approve' || act === 'deny' ||
    (act === 'reply' && Boolean(text) && (thread.options || []).includes(text));
}

/**
 * Runs [deliver] as the one answer to [thread]'s open question: refuses a late answer (already
 * answered) or a concurrent one (another watch is answering), and remembers a successful answer.
 * Used for real threads and for /event test threads alike.
 */
export async function guardAnswer(thread, action, text, deliver) {
  const act = String(action || 'reply').toLowerCase();
  if (isLateAnswer(thread, act, text)) {
    return { ok: false, method: 'dedupe', message: 'Already answered (maybe from your other watch)' };
  }
  const claim = isAnswer(thread, act, text) && String(thread.status || '').startsWith('waiting');
  if (claim && answering.has(thread.id)) {
    return { ok: false, method: 'dedupe', message: 'Already being answered from your other watch' };
  }
  if (claim) answering.add(thread.id);
  try {
    const result = await deliver();
    if (result?.ok && claim) rememberAnswered(thread);
    return result;
  } finally {
    if (claim) answering.delete(thread.id);
  }
}

export async function dispatchWatchAction({ thread, action, text, claudeCollector }) {
  const act = String(action || 'reply').toLowerCase();
  if (!thread) return { ok: false, message: 'That session is no longer active' };
  return guardAnswer(thread, act, text, () => dispatchInner({ thread, act, text, claudeCollector }));
}

async function dispatchInner({ thread, act, text, claudeCollector }) {
  const provider = thread.provider;
  const rawId = thread.rawId || '';

  // 1. Claude Code synchronous PreToolUse hook approval/denial (works even if Mac is locked!)
  if (provider === 'claude' && (act === 'proceed' || act === 'approve' || act === 'deny')) {
    const resolved = claudeCollector.resolveHookDecision(thread.id, act);
    if (resolved) {
      return { ok: true, method: 'claude-hook', message: `${act} sent to Claude hook` };
    }
  }

  // 2. Codex native SQLite queue (zero lock conflict with Codex Desktop, works even if locked!)
  if (provider === 'codex' && fs.existsSync(CODEX_MONITOR)) {
    const msg = text || (act === 'proceed' || act === 'approve' ? 'Proceed' : act === 'deny' ? 'Stop and wait for my review.' : '');
    if (msg) {
      const args = [CODEX_MONITOR, 'queue'];
      if (rawId) args.push('--thread', rawId);
      args.push('--', msg); // text starting with "-" must not be parsed as an option
      const out = await runExec('python3', args, 10_000);
      // The script exits 0 even when `codex queue` failed, so check what it printed.
      if (!/queued/i.test(out)) {
        return { ok: false, method: 'codex-queue', message: `Codex didn't accept the message${out ? `: ${out.slice(0, 120)}` : ''}` };
      }
      return { ok: true, method: 'codex-queue', message: 'Queued directly into Codex thread' };
    }
  }

  // 3. Desktop app Accessibility dispatch (Antigravity, Claude Desktop, T3 Code, Codex GUI).
  // A terminal (CLI) session has no app window to type into; typing into the desktop app instead
  // would land the reply in an unrelated chat, so refuse.
  if (thread.source === 'cli' && provider !== 'codex') {
    return { ok: false, message: 'Replies to terminal sessions can only be approved or denied from the watch' };
  }
  const targetApp = desktopAppName(provider);
  if (!targetApp) return { ok: false, message: `No desktop app to reply to for ${provider}` };

  // An answer to a multiple-choice question: click that exact choice, never type it into a chat.
  if (act === 'reply' && text && thread.kind === 'choice' && (thread.options || []).includes(text)) {
    await chooseInDesktopApp(targetApp, text, windowTitleFor ? (windowTitleFor(thread) || '') : '');
    return { ok: true, method: 'macos-accessibility', message: `Answered in ${targetApp}` };
  }

  if (act === 'proceed' || act === 'approve') {
    await sendToDesktopApp(targetApp, text || 'Proceed', !text);
    return { ok: true, method: 'macos-accessibility', message: `Triggered Proceed in ${targetApp}` };
  }

  if (text) {
    await sendToDesktopApp(targetApp, text, false);
    return { ok: true, method: 'macos-accessibility', message: `Sent reply to ${targetApp}` };
  }

  return { ok: false, message: 'No text or actionable command provided' };
}
