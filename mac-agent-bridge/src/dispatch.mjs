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
const DESKTOP_SCRIPT = `
ObjC.import('AppKit');
function run(argv) {
  const appName = argv[0], mode = argv[1], text = argv[2] || '';
  const se = Application('System Events');
  const app = Application(appName);
  if (!app.running()) throw new Error(appName + ' is not running');
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

async function sendToDesktopApp(appName, text, clickProceed = false) {
  const mode = clickProceed ? 'proceed' : 'paste';
  try {
    return await runExecStdin('osascript', ['-l', 'JavaScript', '-', appName, mode, String(text || '')], DESKTOP_SCRIPT, 10_000);
  } catch (err) {
    throw new Error(explainOsascriptError(err.message, appName));
  }
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

export async function dispatchWatchAction({ thread, action, text, claudeCollector }) {
  const act = String(action || 'reply').toLowerCase();
  if (!thread) return { ok: false, message: 'That session is no longer active' };
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
  const appMap = {
    antigravity: 'Antigravity',
    claude: 'Claude',
    codex: 'Codex',
    t3code: 'T3 Code',
  };
  const targetApp = appMap[provider];
  if (!targetApp) return { ok: false, message: `No desktop app to reply to for ${provider}` };

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
