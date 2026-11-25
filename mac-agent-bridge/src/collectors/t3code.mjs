import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const T3_USERDATA = path.join(os.homedir(), '.t3/userdata');
const T3_DB = path.join(T3_USERDATA, 'statev2.sqlite');

export class T3CodeCollector {
  constructor(onChange) {
    this.onChange = onChange;
    this.threads = [];
    this.ownedProviderSessionIds = new Set();
    this.watcher = null;
    this.timer = null;
    this.debounceTimer = null;
  }

  start() {
    this.scan();
    if (fs.existsSync(T3_USERDATA)) {
      try {
        this.watcher = fs.watch(T3_USERDATA, (_, filename) => {
          if (filename && filename.includes('statev2.sqlite') && !filename.endsWith('-shm')) {
            this.scheduleScan();
          }
        });
      } catch {
        // Fallback to timer
      }
    }
    this.timer = setInterval(() => this.scan(), 2000);
  }

  stop() {
    if (this.watcher) this.watcher.close();
    if (this.timer) clearInterval(this.timer);
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
  }

  scheduleScan() {
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    const wait = Math.max(150, 1000 - (Date.now() - (this.lastScanAt || 0)));
    this.debounceTimer = setTimeout(() => this.scan(), wait);
  }

  scan() {
    this.lastScanAt = Date.now();
    if (!fs.existsSync(T3_DB)) return;
    let db;
    try {
      db = new DatabaseSync(T3_DB, { readOnly: true });

      // Provider (Claude/Codex) session ids that each T3 thread drives, for deduplication.
      const ownedByThread = new Map();
      const addOwned = (threadId, providerId) => {
        if (!threadId || !providerId) return;
        if (!ownedByThread.has(threadId)) ownedByThread.set(threadId, new Set());
        ownedByThread.get(threadId).add(providerId);
      };
      try {
        for (const s of db.prepare(`SELECT provider_session_id, thread_id FROM orchestration_v2_projection_provider_sessions`).all()) {
          addOwned(s.thread_id, s.provider_session_id);
        }
        for (const r of db.prepare(`SELECT thread_id, provider_thread_id FROM orchestration_v2_projection_runs WHERE provider_thread_id IS NOT NULL`).all()) {
          addOwned(r.thread_id, r.provider_thread_id);
        }
      } catch {
        // Optional tables
      }
      try {
        // The ids above are T3's wrapped refs ("provider-thread:…native-thread:pending%3Arun…"),
        // never the provider's own session id. The real Claude/Codex session id — the one their
        // transcripts are named by — lives in payload_json.nativeThreadRef.nativeId.
        for (const r of db.prepare(`
          SELECT thread_id, json_extract(payload_json, '$.nativeThreadRef.nativeId') AS native_id
          FROM orchestration_v2_projection_provider_threads
          WHERE json_extract(payload_json, '$.nativeThreadRef.nativeId') IS NOT NULL`).all()) {
          addOwned(r.thread_id, r.native_id);
        }
      } catch {
        // Older T3 schema without provider_threads
      }

      // T3 moved threads to orchestration_v2_projection_threads; the old projection_threads
      // table stopped updating, so read v2 and fall back only if it doesn't exist.
      let rows;
      try {
        rows = db.prepare(`
          SELECT thread_id, title, updated_at,
                 0 AS pending_approval_count, 0 AS pending_user_input_count, 0 AS has_actionable_proposed_plan
          FROM orchestration_v2_projection_threads
          WHERE deleted_at IS NULL AND archived_at IS NULL
          ORDER BY updated_at DESC
          LIMIT 10
        `).all();
      } catch {
        rows = db.prepare(`
          SELECT thread_id, title, updated_at, pending_approval_count, pending_user_input_count, has_actionable_proposed_plan
          FROM projection_threads
          WHERE deleted_at IS NULL AND archived_at IS NULL
          ORDER BY updated_at DESC
          LIMIT 10
        `).all();
      }

      const now = Date.now();
      const out = [];

      for (const r of rows) {
        const updatedMs = new Date(r.updated_at).getTime() || now;

        // Check active run
        let activeRun = null;
        try {
          activeRun = db.prepare(`
            SELECT run_id, provider, status, requested_at, completed_at
            FROM orchestration_v2_projection_runs
            WHERE thread_id = ?
            ORDER BY ordinal DESC
            LIMIT 1
          `).get(r.thread_id);
        } catch {}

        // Check unresolved runtime requests (approvals / user input questions)
        let pendingReq = null;
        try {
          pendingReq = db.prepare(`
            SELECT runtime_request_id, kind, status, created_at, payload_json
            FROM orchestration_v2_projection_runtime_requests
            WHERE thread_id = ? AND resolved_at IS NULL
            ORDER BY created_at ASC
            LIMIT 1
          `).get(r.thread_id);
        } catch {}

        let status = 'idle';
        let step = 'Standby';
        let prompt = null;
        let options = [];

        if (pendingReq || r.pending_approval_count > 0 || r.pending_user_input_count > 0) {
          status = r.pending_user_input_count > 0 || (pendingReq?.kind || '').toLowerCase().includes('input')
            ? 'waiting_input'
            : 'waiting_approval';
          try {
            const p = pendingReq ? JSON.parse(pendingReq.payload_json) : {};
            prompt = p.question || p.command || p.message || p.title || p.description || 'Action required in T3 Code';
            step = String(prompt).slice(0, 240);
            if (Array.isArray(p.options)) {
              options = p.options.map(o => typeof o === 'string' ? o : (o.label || o.title || '')).filter(Boolean).slice(0, 4);
            }
          } catch {
            prompt = 'Action required in T3 Code';
            step = prompt;
          }
          if (options.length === 0) options = ['Proceed', 'Deny'];
        } else if (r.has_actionable_proposed_plan > 0) {
          status = 'waiting_approval';
          prompt = 'Proposed plan ready to proceed';
          step = prompt;
          options = ['Proceed'];
        } else if (activeRun && ['running', 'in_progress', 'inProgress', 'pending', 'streaming'].includes(activeRun.status)) {
          status = 'running';
          step = `${activeRun.provider || 'Agent'} working…`;
        } else if (activeRun && ['error', 'failed'].includes(activeRun.status)) {
          status = 'error';
          step = 'Run failed in T3 Code';
        }

        if (status === 'idle' && now - updatedMs > 12 * 3600_000) continue;

        const startedMs = pendingReq?.created_at
          ? new Date(pendingReq.created_at).getTime()
          : (activeRun?.requested_at ? new Date(activeRun.requested_at).getTime() : updatedMs);

        out.push({
          id: `t3code:${r.thread_id}`,
          rawId: r.thread_id,
          provider: 't3code',
          providerLabel: 'T3 Code',
          source: 'desktop',
          title: (r.title || 'T3 Code Thread').slice(0, 48),
          status,
          step,
          prompt,
          options,
          startedAtMs: startedMs || updatedMs,
          updatedAtMs: updatedMs,
          color: status.startsWith('waiting') ? '#fdd663' : status === 'error' ? '#f28b82' : '#c58af9',
          canReply: true,
        });
      }

      const prevJson = JSON.stringify(this.threads);
      // Hide underlying provider sessions only for T3 threads actually shown.
      const owned = new Set();
      for (const t of out) for (const id of ownedByThread.get(t.rawId) || []) owned.add(id);
      this.ownedProviderSessionIds = owned;
      this.threads = out;
      if (JSON.stringify(this.threads) !== prevJson) {
        this.onChange();
      }
    } catch {
      // SQLite busy — retry next tick
    } finally {
      try { db?.close(); } catch {}
    }
  }

  getThreads() {
    return this.threads;
  }

  ownsProviderSession(rawId) {
    return this.ownedProviderSessionIds.has(rawId);
  }
}
