/**
 * Ensure the Android relay daemon is up for phone-bridge dictate tests.
 * Requires USB adb + com.lowkey.ambientlink installed.
 */
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const PKG = 'com.lowkey.ambientlink';
const ACTIVITY = `${PKG}/.MainActivity`;
const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), 'output');

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], ...opts });
    let out = '';
    let err = '';
    child.stdout?.on('data', (d) => { out += d; });
    child.stderr?.on('data', (d) => { err += d; });
    child.on('close', (code) => {
      if (code === 0) resolve({ out, err });
      else reject(new Error(`${cmd} ${args.join(' ')} → ${code}\n${err || out}`));
    });
  });
}

export async function adbDevices() {
  const { out } = await run('adb', ['devices']);
  return out
    .split('\n')
    .slice(1)
    .map((l) => l.trim().split('\t')[0])
    .filter((id) => id && id !== 'List');
}

export async function ensurePhoneDaemon() {
  const devices = await adbDevices();
  if (!devices.length) {
    throw new Error('No adb device — plug in the phone and enable USB debugging.');
  }
  await run('adb', ['shell', 'am', 'start', '-n', ACTIVITY]);
  await new Promise((r) => setTimeout(r, 2500));
  return devices[0];
}

/** Tail logcat for dictate / SCO lines during the test window. */
export function startDictateLogcat() {
  const child = spawn('adb', [
    'logcat',
    '-v',
    'time',
    'DictationManager:I',
    'WebDictationBridge:I',
    'RelayClient:I',
    'SodaDictationEngine:I',
    '*:S',
  ]);
  const lines = [];
  child.stdout.on('data', (buf) => {
    for (const line of buf.toString().split('\n')) {
      if (!line.trim()) continue;
      if (/dictate|sco|Dictation|WebDictation|partial|final transcript/i.test(line)) {
        lines.push(line);
      }
    }
  });
  return {
    stop: async () => {
      child.kill('SIGTERM');
      await mkdir(OUT_DIR, { recursive: true });
      const path = join(OUT_DIR, 'dictate-logcat.txt');
      await writeFile(path, lines.join('\n') + '\n', 'utf8');
      return { path, lines };
    },
  };
}

export function logcatShowsSco(lines) {
  return lines.some((l) => /sco=true|BluetoothSco|VOICE_COMMUNICATION/i.test(l));
}

export function logcatShowsTranscript(lines) {
  return lines.some((l) =>
    /final transcript:|dictate partial:|sendDictatePartial|dictate live \(mic/i.test(l),
  );
}
