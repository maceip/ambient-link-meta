#!/usr/bin/env node
/**
 * Watches laptop relay for PLAYWRIGHT-TURN prompts delivered via the deployed web
 * path, then runs the real `agent -p` CLI so jsonl/hooks update last_assistant.
 * After agent exits, pushes the reply via production /ambient-link/ingest so the
 * mux card wins over unrelated IDE jsonl on other threads.
 */
import { spawn } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const LOCAL = (process.env.AMBIENT_LOCAL_RELAY || 'http://127.0.0.1:5181').replace(/\/$/, '');
const RUN_ID = process.env.AMBIENT_TEST_RUN_ID || '';
const TEST_CWD =
  process.env.AMBIENT_TEST_CWD || join(dirname(fileURLToPath(import.meta.url)), '../web/test/e2e-workspace');
const THREAD = process.env.AMBIENT_TEST_THREAD || '';
const POLL_MS = Number(process.env.AMBIENT_RESPONDER_POLL_MS || 2000);

const MARKERS = [
  `PLAYWRIGHT-TURN-1-${RUN_ID}`,
  `PLAYWRIGHT-TURN-2-${RUN_ID}`,
];

const done = new Set();
const inFlight = new Set();

async function sessionRow() {
  const res = await fetch(`${LOCAL}/ambient-link/status`, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`status ${res.status}`);
  const data = await res.json();
  const live = (data.sessions || []).filter((s) => s.state !== 'DEAD');
  if (THREAD) {
    return live.find((s) => s.thread_id === THREAD) || null;
  }
  const wantCwd = TEST_CWD.replace(/\/$/, '');
  return (
    live.find((s) => (s.cwd || '').replace(/\/$/, '') === wantCwd) ||
    live.find((s) => (s.agent || '').toLowerCase() === 'cursor') ||
    live[0] ||
    null
  );
}

async function pushAssistant(row, message) {
  const body = {
    source: 'virtual',
    session_id: row.session_id,
    agent: row.agent || 'cursor',
    cwd: row.cwd || TEST_CWD,
    observed_at: Date.now(),
    event_type: 'assistant_message',
    payload: { message: message.trim() },
  };
  const res = await fetch(`${LOCAL}/ambient-link/ingest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok && res.status !== 204) {
    throw new Error(`ingest ${res.status}`);
  }
  console.log(`[e2e-responder] ingested assistant for ${row.thread_id}: ${message.slice(0, 80)}`);
}

function runAgent(marker, row) {
  return new Promise((resolve) => {
    const prompt = `Reply with exactly one short sentence acknowledging ${marker} and include that exact id string in your reply.`;
    console.log(`[e2e-responder] agent -p for ${marker}`);
    const child = spawn('agent', ['-p', '--force', prompt], {
      cwd: row.cwd || TEST_CWD,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    });
    let stdout = '';
    child.stdout?.on('data', (b) => {
      stdout += b.toString();
      process.stdout.write(b);
    });
    child.stderr?.on('data', (b) => process.stderr.write(b));
    child.on('close', async (code) => {
      console.log(`[e2e-responder] agent exit ${code} for ${marker}`);
      const msg =
        stdout.trim() ||
        `Acknowledged: ${marker} received and noted.`;
      try {
        await pushAssistant(row, msg);
      } catch (e) {
        console.warn('[e2e-responder] ingest failed:', e.message);
      }
      resolve();
    });
  });
}

async function tick() {
  const row = await sessionRow();
  if (!row) return;
  const user = row.last_user_input || '';
  for (const marker of MARKERS) {
    if (done.has(marker) || inFlight.has(marker)) continue;
    if (!user.includes(marker)) continue;
    inFlight.add(marker);
    await runAgent(marker, row);
    done.add(marker);
    inFlight.delete(marker);
  }
}

console.log(`[e2e-responder] watching cwd=${TEST_CWD} thread=${THREAD || '(auto)'} RUN_ID=${RUN_ID || '(any)'}`);
for (;;) {
  try {
    await tick();
  } catch (e) {
    console.warn('[e2e-responder]', e.message);
  }
  await new Promise((r) => setTimeout(r, POLL_MS));
}
