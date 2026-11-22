import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BRIDGE_ROOT = path.resolve(__dirname, '..');

function resolveBinary() {
  const candidates = [
    path.join(BRIDGE_ROOT, 'bin', 'live-ctrl'),
    path.join(BRIDGE_ROOT, 'swift', 'live-ctrl'),
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

/**
 * Supervises the compiled native Swift helper `live-ctrl watch` (which inherits
 * Agent HUD.app's Accessibility permission). Provides zero-polling frontmost-app
 * tracking, per-app thinking-level stepping, and fast clipboard-preserving paste+send.
 */
export class LiveController {
  constructor(onFocusChange) {
    this.onFocusChange = onFocusChange;
    this.proc = null;
    this.rl = null;
    this.stopped = false;
    this.nextReqId = 1;
    this.pending = new Map();
    this.lastState = {
      ok: true,
      appName: 'Mac',
      bundleId: '',
      provider: '',
      dialSupported: false,
      level: '',
      levels: [],
      index: -1,
    };
    this.restartTimer = null;
    this.restartDelay = 2000;
  }

  start() {
    this.stopped = false;
    this._spawn();
  }

  stop() {
    this.stopped = true;
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer);
      reject(new Error('LiveController stopped'));
    }
    this.pending.clear();
    if (this.proc) {
      try { this.proc.kill(); } catch {}
      this.proc = null;
    }
  }

  _spawn() {
    if (this.stopped || this.proc) return;
    const bin = resolveBinary();
    if (!bin) {
      console.warn('[live-ctrl] binary not found (run mac-app/build.sh or compile swift/live-ctrl.swift)');
      return;
    }
    const child = spawn(bin, ['watch'], { stdio: ['pipe', 'pipe', 'pipe'] });
    this.proc = child;
    // A helper that cannot launch (bad permissions, wrong architecture) emits 'error', and so does a
    // pipe closed under a write. Unhandled, either one would take the whole bridge down with it.
    child.on('error', (err) => {
      console.warn(`[live-ctrl] helper error: ${err.message}`);
      this._handleGone(child, `failed (${err.code || err.message})`);
    });
    child.stdin.on('error', () => {});
    child.stderr.on('data', (d) => {
      const s = d.toString().trim();
      if (s) console.warn(`[live-ctrl] ${s}`);
    });

    const rl = readline.createInterface({ input: child.stdout });
    this.rl = rl;
    rl.on('line', (line) => {
      let msg;
      try { msg = JSON.parse(line); } catch { return; }
      this.restartDelay = 2000; // the helper is talking: forget any earlier failures

      if (msg.reqId !== undefined && this.pending.has(msg.reqId)) {
        const entry = this.pending.get(msg.reqId);
        this.pending.delete(msg.reqId);
        clearTimeout(entry.timer);
        if (msg.appName && msg.dialSupported !== undefined) {
          this.lastState = {
            ok: Boolean(msg.ok),
            appName: msg.appName,
            bundleId: msg.bundleId || '',
            provider: msg.provider || '',
            dialSupported: Boolean(msg.dialSupported),
            level: msg.level || '',
            levels: Array.isArray(msg.levels) ? msg.levels : [],
            index: typeof msg.index === 'number' ? msg.index : -1,
          };
        }
        entry.resolve(msg);
        return;
      }

      if (msg.event === 'focus' || msg.event === 'ready') {
        this.lastState = {
          ok: Boolean(msg.ok),
          appName: msg.appName || 'Mac',
          bundleId: msg.bundleId || '',
          provider: msg.provider || '',
          dialSupported: Boolean(msg.dialSupported),
          level: msg.level || '',
          levels: Array.isArray(msg.levels) ? msg.levels : [],
          index: typeof msg.index === 'number' ? msg.index : -1,
        };
        if (msg.event === 'focus' && typeof this.onFocusChange === 'function') {
          this.onFocusChange(this.lastState);
        }
      }
    });

    child.on('exit', (code) => this._handleGone(child, `exited (${code})`));
  }

  // The helper is gone ('error' and 'exit' can both fire for one failure, so this runs once per child).
  _handleGone(child, reason) {
    if (this.proc === child) this.proc = null;
    if (child.gone) return;
    child.gone = true;
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer);
      reject(new Error(`live-ctrl ${reason}`));
    }
    this.pending.clear();
    if (!this.stopped && !this.restartTimer) {
      // Back off (2 s, 4 s ... 30 s) so a helper that keeps failing does not spin.
      const delay = this.restartDelay;
      this.restartDelay = Math.min(30_000, this.restartDelay * 2);
      this.restartTimer = setTimeout(() => {
        this.restartTimer = null;
        this._spawn();
      }, delay);
      this.restartTimer.unref();
    }
  }

  _send(cmdObj, timeoutMs = 4500) {
    if (!this.proc || !this.proc.stdin || this.proc.stdin.destroyed) {
      this._spawn();
      if (!this.proc || !this.proc.stdin) {
        return Promise.reject(new Error('live-ctrl helper not available'));
      }
    }
    const reqId = this.nextReqId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(reqId);
        reject(new Error(`live-ctrl ${cmdObj.cmd} timed out`));
      }, timeoutMs);
      this.pending.set(reqId, { resolve, reject, timer });
      try {
        // deadlineMs lets the helper drop a command that waited in line past our timeout, so a late
        // paste or step can never run after the caller was already told it failed (and retried).
        this.proc.stdin.write(`${JSON.stringify({ ...cmdObj, reqId, deadlineMs: Date.now() + timeoutMs })}\n`);
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(reqId);
        reject(err);
      }
    });
  }

  getLastState() {
    return this.lastState;
  }

  inspect() {
    return this._send({ cmd: 'inspect' }, 4000);
  }

  step(delta, targetLevel = null, targetIndex = null) {
    const payload = { cmd: 'step', delta: delta >= 0 ? 1 : -1 };
    if (typeof targetLevel === 'string' && targetLevel) payload.targetLevel = targetLevel;
    if (typeof targetIndex === 'number' && targetIndex >= 0) payload.targetIndex = targetIndex;
    return this._send(payload, 5000);
  }

  // expectBundleId: the app the watch was showing. The helper refuses if a different app is in front.
  pasteSend(text, pressReturn = true, expectBundleId = '') {
    const payload = { cmd: 'paste_send', text: String(text || ''), pressReturn: Boolean(pressReturn) };
    if (expectBundleId) payload.expectBundleId = String(expectBundleId);
    return this._send(payload, 5000);
  }

  debugAx(appName = '') {
    return this._send({ cmd: 'debug_ax', app: appName }, 6000);
  }

  debugMenu(appName = '') {
    return this._send({ cmd: 'debug_menu', app: appName }, 6000);
  }
}
