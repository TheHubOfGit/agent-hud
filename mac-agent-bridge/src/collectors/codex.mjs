import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const CODEX_DIR = path.join(os.homedir(), '.codex');
const STATE_DB = path.join(CODEX_DIR, 'state_5.sqlite');
const HISTORY_DB = path.join(CODEX_DIR, 'thread_history_1.sqlite');
const LOCKS_DIR = path.join(CODEX_DIR, 'thread-writer-locks');

export class CodexCollector {
  constructor(onChange) {
    this.onChange = onChange;
    this.threads = [];
    this.watcher = null;
    this.timer = null;
    this.debounceTimer = null;
  }

  start() {
    this.scan();
    if (fs.existsSync(CODEX_DIR)) {
      try {
        this.watcher = fs.watch(CODEX_DIR, (_, filename) => {
          if (!filename) return;
          // Opening the DB read-only rebuilds its -shm file when Codex isn't running; reacting to
          // that re-triggered a scan ~11x/second forever. Only real data/WAL/lock changes count.
          if (filename.endsWith('-shm')) return;
          if (filename.includes('state_5.sqlite') || filename.includes('thread_history_1.sqlite') || filename === 'thread-writer-locks') {
            this.scheduleScan();
          }
        });
      } catch {
        // Fallback to interval
      }
    }
    this.timer = setInterval(() => this.scan(), 2500);
  }

  stop() {
    if (this.watcher) this.watcher.close();
    if (this.timer) clearInterval(this.timer);
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
  }

  scheduleScan() {
    // Debounce bursts, and never scan more than ~once a second (each scan opens an ~850 MB DB).
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    const wait = Math.max(150, 1000 - (Date.now() - (this.lastScanAt || 0)));
    this.debounceTimer = setTimeout(() => this.scan(), wait);
  }

  getActiveLockSet() {
    const locked = new Set();
    if (!fs.existsSync(LOCKS_DIR)) return locked;
    try {
      for (const f of fs.readdirSync(LOCKS_DIR)) {
        if (f.endsWith('.lock')) {
          locked.add(f.slice(0, -5));
        }
      }
    } catch {
      // Ignore
    }
    return locked;
  }

  scan() {
    this.lastScanAt = Date.now();
    if (!fs.existsSync(STATE_DB) || !fs.existsSync(HISTORY_DB)) return;
    let stateDb;
    let histDb;
    try {
      stateDb = new DatabaseSync(STATE_DB, { readOnly: true });
      histDb = new DatabaseSync(HISTORY_DB, { readOnly: true });
      const locked = this.getActiveLockSet();

      const recentThreads = stateDb.prepare(`
        SELECT id, title, name, cwd, model, source, thread_source, updated_at, updated_at_ms, preview
        FROM threads
        WHERE archived = 0
        ORDER BY COALESCE(updated_at_ms, updated_at * 1000) DESC
        LIMIT 8
      `).all();

      const now = Date.now();
      const out = [];

      for (const t of recentThreads) {
        const updatedMs = t.updated_at_ms || (t.updated_at > 1e12 ? t.updated_at : t.updated_at * 1000);
        // Ignore stale threads older than 12 hours unless actively inProgress
        const latestTurn = histDb.prepare(`
          SELECT turn_id, status, started_at, completed_at, error_json
          FROM thread_turns
          WHERE thread_id = ?
          ORDER BY rollout_ordinal DESC
          LIMIT 1
        `).get(t.id);

        const isRunning = latestTurn && (latestTurn.status === 'inProgress' || latestTurn.status === 'running');
        if (!isRunning && now - updatedMs > 12 * 3600_000) continue;

        let latestStep = t.preview || 'Standby';
        let status = isRunning ? 'running' : 'idle';
        let pendingPrompt = null;

        if (latestTurn) {
          if (latestTurn.status === 'error' || latestTurn.status === 'failed' || latestTurn.error_json) {
            status = 'error';
          }
          const latestItem = histDb.prepare(`
            SELECT item_type, item_json, created_at_ms
            FROM thread_items
            WHERE thread_id = ? AND turn_id = ?
            ORDER BY rollout_ordinal DESC, created_at_ms DESC
            LIMIT 1
          `).get(t.id, latestTurn.turn_id);

          if (latestItem) {
            try {
              const payload = JSON.parse(latestItem.item_json);
              const type = (latestItem.item_type || payload.type || '').toLowerCase();
              if (type.includes('approval') || payload.status === 'pendingApproval' || payload.awaiting_approval) {
                status = 'waiting_approval';
                pendingPrompt = payload.command || payload.reason || payload.message || 'Approve action in Codex';
                latestStep = pendingPrompt;
              } else if (type.includes('command')) {
                const cmd = payload.command || payload.cmd || 'Running command';
                latestStep = String(cmd).slice(0, 240);
                if (payload.status === 'failed' || (typeof payload.exitCode === 'number' && payload.exitCode !== 0 && isRunning)) {
                  status = 'error';
                }
              } else if (type.includes('reasoning')) {
                latestStep = String(payload.summary || payload.text || 'Reasoning…').slice(0, 240);
              } else if (type.includes('agentmessage') || type.includes('message')) {
                const txt = payload.text || payload.content || '';
                if (txt) latestStep = String(txt).replace(/\s+/g, ' ').slice(0, 240);
              }
            } catch {
              // Keep fallback step
            }
          }
        }

        const isDesktop = String(t.source || t.thread_source || '').toLowerCase().includes('desktop') ||
          String(t.source || '').toLowerCase().includes('app') ||
          locked.has(t.id);

        const startedMs = latestTurn?.started_at
          ? (latestTurn.started_at > 1e12 ? latestTurn.started_at : latestTurn.started_at * 1000)
          : updatedMs;

        // A turn that completed cleanly is a real "done" (not just quiet).
        const completed = latestTurn?.completed_at;
        const doneAtMs = !isRunning && status === 'idle' && completed
          ? (completed > 1e12 ? completed : completed * 1000)
          : 0;

        out.push({
          doneAtMs,
          id: `codex:${t.id}`,
          rawId: t.id,
          provider: 'codex',
          providerLabel: 'Codex',
          source: isDesktop ? 'desktop' : 'cli',
          title: (t.name || t.title || path.basename(t.cwd || 'Codex')).slice(0, 48),
          status,
          step: latestStep,
          prompt: pendingPrompt,
          options: status === 'waiting_approval' ? ['Proceed', 'Deny'] : [],
          startedAtMs: startedMs,
          updatedAtMs: updatedMs,
          color: status === 'waiting_approval' ? '#fdd663' : status === 'error' ? '#f28b82' : '#78d9ec',
          canReply: true,
        });
      }

      const prevJson = JSON.stringify(this.threads);
      this.threads = out;
      if (JSON.stringify(this.threads) !== prevJson) {
        this.onChange();
      }
    } catch {
      // SQLite busy/unavailable — retry next tick
    } finally {
      try { stateDb?.close(); } catch {}
      try { histDb?.close(); } catch {}
    }
  }

  getThreads() {
    return this.threads;
  }
}
