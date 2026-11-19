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

export async function transcribeWavBuffer(wavBuffer) {
  const tmpFile = path.join(os.tmpdir(), `agenthud_voice_${Date.now()}.wav`);
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
