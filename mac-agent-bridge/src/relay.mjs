// Outbound link from the bridge to the Agent Threads relay (relay/ in this repo), so the wrist app
// can reach the Mac from any network. The Mac dials out; nothing is opened on the home network.
//
// Relay address: AGENT_HUD_RELAY_URL, or the first line of ~/.agenthud-relay-url
// (e.g. https://agent-threads-relay.<you>.workers.dev). No address: the relay is simply off.
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';

const CHUNK = 512 * 1024;
const URL_FILE = path.join(os.homedir(), '.agenthud-relay-url');

/** The relay's public base URL (https://…), or '' when none is configured. */
export function relayBaseUrl() {
  let raw = process.env.AGENT_HUD_RELAY_URL || '';
  if (!raw) {
    try { raw = fs.readFileSync(URL_FILE, 'utf8').split('\n')[0]; } catch {}
  }
  raw = raw.trim().replace(/\/+$/, '');
  return /^https?:\/\/[A-Za-z0-9.-]+(:\d+)?$/.test(raw) ? raw : '';
}

export class RelayLink {
  constructor({ port, token }) {
    this.port = port;
    this.token = token;
    this.ws = null;
    this.connected = false;
    this.backoff = 1000;
    this.incoming = new Map();
    this.lastError = '';
  }

  start() {
    this.base = relayBaseUrl();
    if (!this.base) return;
    this.connect();
  }

  status() {
    return { configured: Boolean(this.base), url: this.base, connected: this.connected, lastError: this.lastError };
  }

  connect() {
    const wsUrl = `${this.base.replace(/^http/, 'ws')}/mac`;
    const ws = new WebSocket(wsUrl, { headers: { Authorization: `Bearer ${this.token}` }, handshakeTimeout: 10_000 });
    this.ws = ws;
    ws.on('open', () => {
      this.connected = true;
      this.backoff = 1000;
      this.lastError = '';
      console.log(`[relay] connected to ${this.base}`);
      clearInterval(this.pinger);
      // Keeps NAT/proxies from dropping the idle connection; the relay answers without waking up.
      this.pinger = setInterval(() => { try { ws.send('ping'); } catch {} }, 25_000);
    });
    ws.on('message', (data, isBinary) => {
      if (isBinary) return;
      const text = data.toString();
      if (text === 'pong') return;
      let msg;
      try { msg = JSON.parse(text); } catch { return; }
      this.onMessage(msg);
    });
    ws.on('unexpected-response', (_req, res) => { this.lastError = `Relay refused the Mac (${res.statusCode})`; });
    ws.on('error', (err) => { this.lastError = err.message; });
    ws.on('close', (code) => {
      clearInterval(this.pinger);
      const was = this.connected;
      this.connected = false;
      if (this.ws !== ws) return;
      if (was) console.log(`[relay] disconnected (${code}); reconnecting`);
      // 4000: replaced by a newer connection from this Mac (e.g. a restart); still reconnect later.
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 2, 30_000);
    });
  }

  onMessage(msg) {
    if (msg.t === 'req') {
      const entry = { ...msg, chunks: new Array(msg.parts || 0), got: 0 };
      if (!msg.parts) return this.handle(entry);
      this.incoming.set(msg.id, entry);
    } else if (msg.t === 'part') {
      const entry = this.incoming.get(msg.id);
      if (!entry || entry.chunks[msg.i] !== undefined) return;
      entry.chunks[msg.i] = msg.data;
      if (++entry.got === entry.chunks.length) {
        this.incoming.delete(msg.id);
        this.handle(entry);
      }
    }
  }

  /**
   * Runs one relayed request against this bridge. It's marked relayed, so the bridge gives it no
   * local trust: it needs the watch's token and may only use the wrist app's endpoints.
   */
  handle(req) {
    const body = req.chunks && req.chunks.length ? Buffer.from(req.chunks.join(''), 'base64') : null;
    const headers = { 'X-HUD-Relayed': '1', 'X-HUD-Token': String(req.token || '') };
    if (req.contentType) headers['Content-Type'] = req.contentType;
    if (req.contentEncoding) headers['Content-Encoding'] = req.contentEncoding;
    if (req.ifNoneMatch) headers['If-None-Match'] = req.ifNoneMatch;
    if (body) headers['Content-Length'] = body.length;
    const local = http.request({
      host: '127.0.0.1', port: this.port, method: req.method,
      path: `${req.path}${req.query || ''}`, headers, timeout: 40_000,
    }, (res) => {
      const parts = [];
      res.on('data', (c) => parts.push(c));
      res.on('end', () => this.reply(req.id, res.statusCode, res.headers['content-type'] || '', Buffer.concat(parts), res.headers['etag'] || ''));
    });
    local.on('timeout', () => local.destroy(new Error('timeout')));
    local.on('error', () => this.reply(req.id, 502, 'application/json', Buffer.from(JSON.stringify({ error: 'Bridge error' })), ''));
    if (body) local.end(body); else local.end();
  }

  reply(id, status, contentType, buf, etag = '') {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const b64 = buf.length ? buf.toString('base64') : '';
    const chunks = [];
    for (let i = 0; i < b64.length; i += CHUNK) chunks.push(b64.slice(i, i + CHUNK));
    try {
      ws.send(JSON.stringify({ t: 'res', id, status, contentType, etag, parts: chunks.length }));
      chunks.forEach((data, i) => ws.send(JSON.stringify({ t: 'part', id, i, data })));
    } catch {}
  }
}
