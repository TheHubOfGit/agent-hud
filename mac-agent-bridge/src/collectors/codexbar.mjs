import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CODEXBAR_CLI = '/Applications/CodexBar.app/Contents/Helpers/CodexBarCLI';
const USAGE_HISTORY = path.join(os.homedir(), 'Library/Application Support/CodexBar/usage-history.jsonl');

const DISPLAY_NAMES = {
  claude: 'Claude',
  codex: 'Codex',
  antigravity: 'Antigravity',
  gemini: 'Gemini',
  t3chat: 'T3 Chat',
  openai: 'OpenAI',
  cursor: 'Cursor',
};

const PROVIDER_COLORS = {
  claude: '#81c995',
  codex: '#fdd663',
  antigravity: '#8ab4f8',
  gemini: '#78d9ec',
  t3chat: '#c58af9',
};

function toMs(iso) {
  const t = iso ? new Date(iso).getTime() : 0;
  return Number.isFinite(t) ? t : 0;
}

function formatResetShort(resetsAtIso) {
  if (!resetsAtIso) return '';
  const ms = new Date(resetsAtIso).getTime() - Date.now();
  if (!Number.isFinite(ms) || ms <= 0) return 'now';
  const mins = Math.round(ms / 60000);
  if (mins < 60) return `${mins}m`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 48) return `${hrs}h`;
  return `${Math.round(hrs / 24)}d`;
}

function formatResetClock(resetsAtIso) {
  if (!resetsAtIso) return '';
  const d = new Date(resetsAtIso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

export class CodexBarCollector {
  constructor(onChange) {
    this.onChange = onChange;
    this.quotas = [];
    this.refreshing = false;
    this.timer = null;
    this.watcher = null;
  }

  start() {
    // Immediate seed from JSONL history (zero latency) then refresh via CLI
    this.readHistoryFallback();
    this.refreshViaCli();

    // Gentle 90-second CLI refresh so CodexBar stays fresh without CPU burn
    this.timer = setInterval(() => this.refreshViaCli(), 90_000);

    // Watch CodexBar's live usage-history.jsonl for immediate updates
    const dir = path.dirname(USAGE_HISTORY);
    if (fs.existsSync(dir)) {
      try {
        this.watcher = fs.watch(dir, (_, filename) => {
          if (filename === 'usage-history.jsonl') {
            this.readHistoryFallback();
          }
        });
      } catch {
        // Ignore watch errors
      }
    }
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    if (this.watcher) this.watcher.close();
  }

  readHistoryFallback() {
    if (!fs.existsSync(USAGE_HISTORY)) return;
    try {
      const stat = fs.statSync(USAGE_HISTORY);
      const readSize = Math.min(stat.size, 65536);
      const fd = fs.openSync(USAGE_HISTORY, 'r');
      const buf = Buffer.alloc(readSize);
      fs.readSync(fd, buf, 0, readSize, stat.size - readSize);
      fs.closeSync(fd);

      const lines = buf.toString('utf8').split('\n').filter(Boolean);
      const latest = new Map();
      for (const line of lines) {
        try {
          const row = JSON.parse(line);
          if (!row.provider || typeof row.usedPercent !== 'number') continue;
          const key = `${row.provider}:${row.windowKind || 'primary'}`;
          latest.set(key, row);
        } catch {
          // Skip partial line
        }
      }

      if (this.quotas.length === 0 && latest.size > 0) {
        const byProv = new Map();
        // Shorter window first, whichever one the provider calls primary.
        const pairs = new Map();
        for (const row of latest.values()) {
          const p = row.provider.toLowerCase();
          pairs.set(p, [...(pairs.get(p) || []), row]);
        }
        for (const rows of pairs.values()) {
          if (rows.length === 2 && rows.every(r => r.windowMinutes)) {
            rows.sort((a, b) => a.windowMinutes - b.windowMinutes);
            rows[0] = { ...rows[0], windowKind: 'primary' };
            rows[1] = { ...rows[1], windowKind: 'secondary' };
          }
        }
        for (const row of [...pairs.values()].flat()) {
          const p = row.provider.toLowerCase();
          const cur = byProv.get(p) || { id: p, name: DISPLAY_NAMES[p] || p, color: PROVIDER_COLORS[p] || '#8ab4f8' };
          const rem = Math.max(0, Math.min(100, Math.round(100 - row.usedPercent)));
          if (row.windowKind === 'secondary') {
            cur.secondaryRemainingPct = rem;
            cur.secondaryResetShort = formatResetShort(row.resetsAt);
            cur.secondaryWindowMinutes = row.windowMinutes || 0;
            cur.secondaryResetsAtMs = toMs(row.resetsAt);
          } else {
            cur.remainingPct = rem;
            cur.resetShort = formatResetShort(row.resetsAt);
            cur.resetClock = formatResetClock(row.resetsAt);
            cur.windowMinutes = row.windowMinutes || 0;
            cur.resetsAtMs = toMs(row.resetsAt);
            cur.windowLabel = row.windowMinutes === 300 ? '5h window' : row.windowMinutes ? `${Math.round(row.windowMinutes / 60)}h window` : 'Window';
          }
          byProv.set(p, cur);
        }
        this.quotas = [...byProv.values()].map(q => {
          if (typeof q.remainingPct !== 'number' && typeof q.secondaryRemainingPct === 'number') {
            q.remainingPct = q.secondaryRemainingPct;
            q.resetShort = q.secondaryResetShort || '';
            q.windowLabel = 'Weekly';
          }
          return q;
        }).filter(q => typeof q.remainingPct === 'number');
        this.onChange();
      }
    } catch {
      // Ignore read errors
    }
  }

  refreshViaCli() {
    if (this.refreshing || !fs.existsSync(CODEXBAR_CLI)) return;
    this.refreshing = true;
    // execFile's callback waits for stdout to close; a hung CLI (or a child holding the pipe)
    // would leave refreshing=true forever. The watchdog guarantees the next refresh can run.
    const watchdog = setTimeout(() => {
      this.refreshing = false;
      try { cp.kill('SIGKILL'); } catch {}
    }, 30_000);
    const cp = execFile(CODEXBAR_CLI, ['usage', '--json'], { timeout: 25_000, killSignal: 'SIGKILL', maxBuffer: 4 * 1024 * 1024 }, (err, stdout) => {
      clearTimeout(watchdog);
      this.refreshing = false;
      // CodexBarCLI exits 1 if ANY enabled provider (e.g. cursor/museai) has an auth error,
      // while still printing the complete valid JSON array for all working providers to stdout.
      if (!stdout || !stdout.trim().startsWith('[')) return;
      try {
        const list = JSON.parse(stdout);
        if (!Array.isArray(list)) return;

        const parsed = [];
        for (const entry of list) {
          if (entry.error || !entry.usage || !entry.usage.primary) continue;
          const p = String(entry.provider || '').toLowerCase();
          let prim = entry.usage.primary;
          let sec = entry.usage.secondary;
          if (typeof prim.usedPercent !== 'number') continue;
          // Some providers (Antigravity) report the weekly window as primary. The watch always
          // shows the shorter window first, so swap them when primary is the longer one.
          if (sec && typeof sec.usedPercent === 'number' && prim.windowMinutes && sec.windowMinutes &&
              sec.windowMinutes < prim.windowMinutes) [prim, sec] = [sec, prim];

          const rem = Math.max(0, Math.min(100, Math.round(100 - prim.usedPercent)));
          const secRem = sec && typeof sec.usedPercent === 'number'
            ? Math.max(0, Math.min(100, Math.round(100 - sec.usedPercent)))
            : null;

          parsed.push({
            id: p,
            name: DISPLAY_NAMES[p] || p.charAt(0).toUpperCase() + p.slice(1),
            color: rem <= 15 ? '#f28b82' : rem <= 35 ? '#fdd663' : (PROVIDER_COLORS[p] || '#8ab4f8'),
            remainingPct: rem,
            resetShort: formatResetShort(prim.resetsAt),
            resetClock: formatResetClock(prim.resetsAt),
            windowLabel: prim.windowMinutes === 300 ? '5h window' : prim.windowMinutes ? `${Math.round(prim.windowMinutes / 60)}h` : 'Primary',
            secondaryRemainingPct: secRem,
            secondaryResetShort: sec ? formatResetShort(sec.resetsAt) : null,
            // Raw window timing so the watch can draw a live pace marker.
            windowMinutes: prim.windowMinutes || 0,
            resetsAtMs: toMs(prim.resetsAt),
            secondaryWindowMinutes: sec?.windowMinutes || 0,
            secondaryResetsAtMs: sec ? toMs(sec.resetsAt) : 0,
            plan: entry.usage.loginMethod || '',
            updatedAt: Date.now(),
          });
        }

        if (parsed.length > 0) {
          // Preferred display order: Claude, Codex, Antigravity, Gemini, others
          const order = ['claude', 'codex', 'antigravity', 'gemini'];
          parsed.sort((a, b) => {
            const ia = order.indexOf(a.id);
            const ib = order.indexOf(b.id);
            return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
          });
          this.quotas = parsed;
          this.onChange();
        }
      } catch {
        // Ignore parse error
      }
    });
  }

  getQuotas() {
    return this.quotas;
  }
}
