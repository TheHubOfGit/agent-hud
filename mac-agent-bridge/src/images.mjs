// Image previews for the wrist app. Conversations reference images in many forms (a file on disk, an
// attachment id, base64 inside a transcript). The watch never sees any of that: each image gets an
// opaque token, and GET /image?t=<token>&w=<px> returns a small JPEG made from it.
//
// Only images that appeared in a conversation the watch has read can be fetched (tokens are minted
// while reading), so the endpoint can't be used to read arbitrary files.
import { execFile } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CACHE_DIR = path.join(os.homedir(), 'Library/Caches/AgentHUD/thumbs');
const IMAGE_EXT = /\.(png|jpe?g|gif|webp|heic|heif|bmp|tiff?)$/i;
const MAX_SOURCE_BYTES = 40 * 1024 * 1024;
const MAX_ENTRIES = 3000;
const WIDTHS = [96, 160, 240, 320, 480, 640]; // thumbnails are made at one of these, rounded up

/** token -> { kind: 'file', path } */
const registry = new Map();

function remember(token, source) {
  if (registry.has(token)) registry.delete(token); // refresh LRU position
  registry.set(token, source);
  while (registry.size > MAX_ENTRIES) registry.delete(registry.keys().next().value);
  return token;
}

/** A token for an image file on disk, or null if it isn't a usable image file. */
export function imageFromFile(filePath) {
  const p = String(filePath || '');
  if (!p || !path.isAbsolute(p) || !IMAGE_EXT.test(p)) return null;
  try {
    const st = fs.statSync(p);
    if (!st.isFile() || st.size === 0 || st.size > MAX_SOURCE_BYTES) return null;
  } catch {
    return null;
  }
  const token = crypto.createHash('sha256').update(`file\0${p}`).digest('hex').slice(0, 32);
  return remember(token, { kind: 'file', path: p });
}

const SRC_DIR = path.join(CACHE_DIR, 'src');

/**
 * A token for base64 image data (an image pasted into a transcript, a generated image). The image is
 * written to the cache once (transcripts can hold hundreds of screenshots; keeping them in memory
 * would grow without bound) and served like any other file.
 */
export function imageFromBase64(data, mime = 'image/png') {
  const d = String(data || '');
  if (d.length < 64 || d.length > MAX_SOURCE_BYTES * 1.4) return null;
  if (!/^image\//.test(mime)) return null;
  const token = crypto.createHash('sha256').update(`b64\0${d.slice(0, 4096)}\0${d.slice(-512)}\0${d.length}`).digest('hex').slice(0, 32);
  const ext = (mime.split('/')[1] || 'png').replace(/[^a-z0-9]/gi, '').slice(0, 5) || 'png';
  const file = path.join(SRC_DIR, `${token}.${ext === 'jpeg' ? 'jpg' : ext}`);
  try {
    if (!fs.existsSync(file)) {
      fs.mkdirSync(SRC_DIR, { recursive: true });
      fs.writeFileSync(file, Buffer.from(d, 'base64'));
    }
  } catch {
    return null;
  }
  return remember(token, { kind: 'file', path: file });
}

function run(cmd, args, timeout = 15_000) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout }, (err, _out, stderr) => (err ? reject(new Error(stderr?.trim() || err.message)) : resolve()));
  });
}

const inflight = new Map(); // cache path -> Promise, so a burst of requests makes one thumbnail

/**
 * Path of a JPEG thumbnail of [token] at least [width] px wide (never upscaled), made on first use and
 * cached on disk. Throws if the token is unknown or the image can't be read.
 */
export async function thumbnail(token, width) {
  const src = registry.get(String(token || ''));
  if (!src) throw Object.assign(new Error('Unknown image'), { status: 404 });
  const w = WIDTHS.find(x => x >= Number(width)) || WIDTHS[WIDTHS.length - 1];
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const out = path.join(CACHE_DIR, `${token}-${w}.jpg`);
  if (fs.existsSync(out)) return out;
  if (inflight.has(out)) return inflight.get(out);
  const work = (async () => {
    const input = src.path;
    if (!input || !fs.existsSync(input)) throw Object.assign(new Error('Image is gone'), { status: 410 });
    const tmpOut = `${out}.${process.pid}.tmp.jpg`;
    try {
      // sips ships with macOS: -Z fits the longest side, so tall screenshots stay readable.
      await run('/usr/bin/sips', ['-Z', String(w), '-s', 'format', 'jpeg', '-s', 'formatOptions', '72', input, '--out', tmpOut]);
      fs.renameSync(tmpOut, out);
    } finally {
      try { fs.unlinkSync(tmpOut); } catch {}
    }
    return out;
  })();
  inflight.set(out, work);
  try {
    return await work;
  } finally {
    inflight.delete(out);
  }
}

/** Keeps the newest [keep] files in [dir]. */
function prune(dir, keep) {
  try {
    const files = fs.readdirSync(dir).filter(f => !f.startsWith('.'))
      .map(f => ({ f, st: fs.statSync(path.join(dir, f)) })).filter(x => x.st.isFile());
    files.sort((a, b) => b.st.mtimeMs - a.st.mtimeMs);
    for (const { f } of files.slice(keep)) fs.unlinkSync(path.join(dir, f));
  } catch {}
}

/** Trims the caches (thumbnails, and images copied out of transcripts). Called now and then. */
export function pruneThumbnails() {
  prune(CACHE_DIR, 500);
  prune(SRC_DIR, 800);
}
