import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// The model lives next to the bridge in the repo, or (for the copy bundled inside the Agent HUD
// app, which has no models folder) in Application Support. AGENT_HUD_WHISPER_MODEL overrides.
const MODEL_CANDIDATES = [
  process.env.AGENT_HUD_WHISPER_MODEL,
  path.resolve(__dirname, '../models/ggml-base.en.bin'),
  path.join(os.homedir(), 'Library/Application Support/AgentHUD/models/ggml-base.en.bin'),
].filter(Boolean);
const WHISPER_MODEL = MODEL_CANDIDATES.find(p => fs.existsSync(p)) || MODEL_CANDIDATES[MODEL_CANDIDATES.length - 1];
const WHISPER_BINS = ['/opt/homebrew/bin/whisper-cli', '/usr/local/bin/whisper-cli', 'whisper-cli'];

function execPromise(cmd, args, timeout = 25_000) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout, maxBuffer: 2 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) return reject(new Error(stderr?.trim() || err.message));
      resolve(stdout.trim());
    });
  });
}

function findWhisperBin() {
  for (const b of WHISPER_BINS) {
    if (b.startsWith('/') ? fs.existsSync(b) : true) return b;
  }
  return 'whisper-cli';
}

// ITU-T G.711 µ-law -> 16-bit linear PCM lookup table (256 entries).
const MULAW_TABLE = new Int16Array(256);
for (let i = 0; i < 256; i++) {
  const u = (~i) & 0xff;
  const sign = u & 0x80;
  const exponent = (u >> 4) & 0x07;
  const mantissa = u & 0x0f;
  let sample = ((mantissa << 3) + 0x84) << exponent;
  sample -= 0x84;
  MULAW_TABLE[i] = sign ? -sample : sample;
}

/** Wraps raw 16kHz 8-bit µ-law bytes as a standard 16kHz 16-bit mono PCM WAV for whisper-cli. */
export function decodeMulawToWav(mulawBuf, sampleRate = 16000) {
  const n = mulawBuf.length;
  const out = Buffer.alloc(44 + n * 2);
  out.write('RIFF', 0);
  out.writeUInt32LE(36 + n * 2, 4);
  out.write('WAVEfmt ', 8);
  out.writeUInt32LE(16, 16);
  out.writeUInt16LE(1, 20);              // PCM
  out.writeUInt16LE(1, 22);              // mono
  out.writeUInt32LE(sampleRate, 24);
  out.writeUInt32LE(sampleRate * 2, 28); // byte rate
  out.writeUInt16LE(2, 32);              // block align
  out.writeUInt16LE(16, 34);             // bits per sample
  out.write('data', 36);
  out.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) out.writeInt16LE(MULAW_TABLE[mulawBuf[i]], 44 + i * 2);
  return out;
}

export async function transcribeWavBuffer(audioBuffer, contentType = 'audio/wav') {
  const wavBuffer = /mulaw/i.test(String(contentType || '')) ? decodeMulawToWav(audioBuffer) : audioBuffer;
  const tmpFile = path.join(os.tmpdir(), `agenthud_voice_${Date.now()}_${Math.random().toString(36).slice(2, 6)}.wav`);
  fs.writeFileSync(tmpFile, wavBuffer);

  try {
    if (!fs.existsSync(WHISPER_MODEL)) {
      throw new Error('Whisper model missing (ggml-base.en.bin)');
    }
    const bin = findWhisperBin();
    const raw = await execPromise(bin, [
      '-m', WHISPER_MODEL,
      '-f', tmpFile,
      '-nt',
      '--no-prints',
      '-l', 'en',
    ], 20_000);

    const cleaned = raw
      .replace(/\[BLANK_AUDIO\]/gi, '')
      .replace(/\(blank audio\)/gi, '')
      // Whisper labels non-speech (typing, music, a sigh) as [typing], (keyboard clicking), *sigh*
      // or a note glyph. Live Mode presses Return after pasting, so those must never reach an agent
      // chat as if they were words. Spoken words never come out in brackets, so dropping is safe.
      .replace(/\[[^\]]*\]/g, '')
      .replace(/(^|\s)\([^()]*\)(?=\s|$|[.,!?])/g, '$1')
      .replace(/(^|\s)\*[^*\n]+\*(?=\s|$|[.,!?])/g, '$1')
      .replace(/[\u266A\u266B]+/g, '')
      .replace(/\s+/g, ' ')
      .trim();

    if (!cleaned) {
      throw new Error('No speech detected — try speaking closer to the watch');
    }
    return cleaned;
  } finally {
    try { fs.unlinkSync(tmpFile); } catch {}
  }
}
