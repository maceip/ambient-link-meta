// Hermetic end-to-end test of the glasses web app against the REAL pipeline.
//
// What is real here: the production relay binary (ambient-link-core host),
// its JSONL tailer, proc watcher, delivery adapters (tmux send-keys), the
// dictation fan-out, the SQLite store, and the shipping web app served by the
// relay itself. The only substitution is the LLM: a fake "claude" process
// (test/fake-claude-agent.mjs) that honors Claude Code's observable contract
// (transcript JSONL + open file handle + stdin). No mocks inside the web app,
// no __AMBIENT_TEST__ hook, no injected fixtures.
//
// Covered loop (the product's core promise):
//   1. live agent session appears in the session list (agent → human)
//   2. opening a session shows its real transcript history
//   3. a reply typed on the web LANDS in the agent's stdin, is confirmed
//      "landed" via transcript echo, and the agent's answer flows back
//   4. dictation: glasses press Dictate → phone streams partials → commit is
//      injected into the agent (the phone is played by a plain WS client —
//      the exact frames relay-android sends)
//   5. session creation fails HONESTLY (relay returns 501; UI shows the
//      truth instead of a fake session)
//
// Run: cd web && npx playwright test        (or scripts/e2e-web.sh)
import { test, expect } from '@playwright/test';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

const PORT = Number(process.env.AMBIENT_E2E_PORT || 5188);
const ORIGIN = `http://127.0.0.1:${PORT}`;
const BASE = `${ORIGIN}/ambient-link/`;
const WEB_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const HOST_BIN = process.env.AMBIENT_HOST_BIN
  || path.join(os.homedir(), 'ambient-link-core/host/bin/ambient-link-host');
const TMUX_SESSION = `al-e2e-${process.pid}`;

let tmp;            // throwaway HOME for the relay + fake agent
let relay;          // relay child process
let sessionId;      // fake agent's session uuid
let threadId;       // relay-assigned thread id for that session
let transcript;     // fake agent's transcript path

async function waitFor(fn, { timeout = 30_000, step = 250, what = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  let lastErr;
  while (Date.now() < deadline) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) { lastErr = e; }
    await new Promise((r) => setTimeout(r, step));
  }
  throw new Error(`timed out waiting for ${what}${lastErr ? `: ${lastErr}` : ''}`);
}

async function relayStatus() {
  const r = await fetch(`${ORIGIN}/ambient-link/status`);
  if (!r.ok) throw new Error(`status ${r.status}`);
  return r.json();
}

function transcriptText() {
  try { return fs.readFileSync(transcript, 'utf8'); } catch { return ''; }
}

// A plain WS client used both as a frame observer and as the fake phone.
// It speaks the same frames relay-android's RelayClient sends.
function wsClient() {
  const frames = [];
  const sock = new WebSocket(`ws://127.0.0.1:${PORT}/ambient-link/ws`);
  sock.addEventListener('message', (e) => {
    try { frames.push(JSON.parse(e.data)); } catch { /* ignore */ }
  });
  const open = new Promise((resolve, reject) => {
    sock.addEventListener('open', resolve);
    sock.addEventListener('error', reject);
  });
  return {
    frames,
    open,
    send: (obj) => sock.send(JSON.stringify(obj)),
    close: () => { try { sock.close(); } catch { /* ignore */ } },
    seen: (pred) => frames.some(pred),
  };
}

async function openSession(page) {
  await page.goto(BASE);
  const row = page.locator('#threads .thread-row').first();
  await expect(row).toBeVisible();
  await row.click();
  await expect(page.locator('#view-thread')).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  // Disable the service worker in tests. Its controllerchange handler reloads
  // the page once shortly after first load, which wipes in-flight UI state
  // (filled inputs, opened views) mid-test.
  await page.addInitScript(() => {
    try { delete Navigator.prototype.serviceWorker; } catch { /* best effort */ }
  });
});

test.beforeAll(async () => {
  test.setTimeout(120_000);
  if (!fs.existsSync(HOST_BIN)) {
    throw new Error(
      `relay binary not found at ${HOST_BIN} — build it first:\n` +
      '  cd ~/ambient-link-core/host && go build -o bin/ambient-link-host ./cmd/host\n' +
      'or set AMBIENT_HOST_BIN.',
    );
  }

  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'al-e2e-'));
  const workdir = path.join(tmp, 'workdir');
  fs.mkdirSync(workdir);
  // The tailer goes permanently idle when its root is missing at boot
  // ("jsonl: root does not exist"), so make sure it exists up front.
  fs.mkdirSync(path.join(tmp, '.claude', 'projects'), { recursive: true });
  sessionId = randomUUID();

  const logFd = fs.openSync(path.join(tmp, 'relay.log'), 'a');
  relay = spawn(HOST_BIN, ['serve'], {
    env: {
      PATH: process.env.PATH,
      HOME: tmp,
      TMPDIR: process.env.TMPDIR || '/tmp',
      AMBIENT_LINK_HOME: path.join(tmp, '.ambient-link'),
      AMBIENT_LINK_LISTEN: `127.0.0.1:${PORT}`,
      AMBIENT_LINK_WEB_ROOT: WEB_ROOT,
      AMBIENT_LINK_LOG: 'debug',
      // deliberately NO AMBIENT_LINK_CLOUD: hermetic, never touches prod
    },
    stdio: ['ignore', logFd, logFd],
  });
  await waitFor(async () => (await fetch(`${ORIGIN}/healthz`)).ok, { what: 'relay /healthz' });

  // Fake agent inside a tmux pane on the DEFAULT server — that is where the
  // relay's tmux delivery adapter looks up panes by pid.
  const agentScript = path.join(WEB_ROOT, 'test', 'fake-claude-agent.mjs');
  execFileSync('tmux', [
    'new-session', '-d', '-s', TMUX_SESSION,
    `exec ${process.execPath} ${agentScript} ${tmp} ${workdir} ${sessionId}`,
  ]);
  transcript = path.join(
    tmp, '.claude', 'projects',
    workdir.replace(/[^A-Za-z0-9]/g, '-'),
    `${sessionId}.jsonl`,
  );

  // Relay must (a) ingest the session from the transcript and (b) register a
  // live delivery endpoint for it via proc correlation.
  const status = await waitFor(async () => {
    const s = await relayStatus();
    return (s.sessions || []).some((x) => x.session_id === sessionId) ? s : null;
  }, { what: 'session visible in /ambient-link/status' });
  threadId = status.sessions.find((x) => x.session_id === sessionId).thread_id;

  await waitFor(async () => {
    const s = await relayStatus();
    return JSON.stringify(s.delivery || []).includes(sessionId) ? true : null;
  }, { what: 'delivery endpoint registered for the fake agent' });
});

test.afterAll(async () => {
  try { execFileSync('tmux', ['kill-session', '-t', TMUX_SESSION]); } catch { /* already gone */ }
  if (relay) { try { relay.kill('SIGTERM'); } catch { /* already gone */ } }
  if (tmp && process.env.AMBIENT_E2E_KEEP !== '1') {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('session list shows the live agent — from the relay, not a mock', async ({ page }) => {
  await page.goto(BASE);
  const rows = page.locator('#threads .thread-row');
  await expect(rows.first()).toBeVisible();
  await expect(page.locator('#threads')).toContainText(/workdir/);
  await expect(rows.first()).toHaveAttribute('aria-label', /claude/i);
  await expect(page.locator('#empty-hint')).toBeHidden();
});

test('opening a session shows its real transcript history', async ({ page }) => {
  await openSession(page);
  await expect(page.locator('#w-chat')).toContainText('fake-agent ready');
});

test('reply from the web lands in the agent and the answer flows back', async ({ page }) => {
  const observer = wsClient();
  await observer.open;

  await openSession(page);
  const text = `e2e ping ${Date.now()}`;
  await page.fill('#prompt', text);
  await page.click('#send');

  // 1. Delivered for real: the text reaches the agent process's stdin and the
  //    agent writes it into its transcript as a user turn.
  await waitFor(
    () => transcriptText().includes(`"type":"user"`) && transcriptText().includes(text),
    { what: 'reply echoed into agent transcript (delivery landed)' },
  );

  // 2. The relay noticed the transcript echo and fanned out the honest
  //    "landed" status (delivered != landed is a core protocol seam).
  await waitFor(
    () => observer.seen((m) => m.type === 'input_status'
      && m.thread === threadId && String(m.status).includes('landed')),
    { what: 'input_status landed fan-out' },
  );

  // 3. Round trip: the agent's answer surfaces back on the glasses UI.
  await expect(page.locator('#w-chat')).toContainText(`ack: ${text}`, { timeout: 25_000 });

  observer.close();
});

test('dictation: begin on glasses, partials + commit from phone, lands in agent', async ({ page }) => {
  const phone = wsClient();
  await phone.open;

  await openSession(page);
  await page.click('#dictate');

  // The relay turns the web's dictate_begin into a dictate_active fan-out to
  // companion clients (plus a raw session_focus) — what wakes the phone mic.
  await waitFor(
    () => phone.seen((m) => m.type === 'dictate_active' && m.thread === threadId),
    { what: 'dictate_active fan-out to phone' },
  );
  expect(phone.seen((m) => m.type === 'session_focus' && m.thread === threadId)).toBe(true);

  const spoken = `hello from the phone ${Date.now()}`;
  phone.send({ type: 'dictate_partial', thread: threadId, text: 'hello from', source: 'phone' });
  await expect(page.locator('#prompt')).toHaveValue('hello from'); // partial streamed to glasses
  phone.send({ type: 'dictate_partial', thread: threadId, text: spoken, source: 'phone' });
  phone.send({ type: 'dictate_commit', thread: threadId, text: spoken, source: 'phone' });

  // The relay itself injects the committed text into the agent.
  await waitFor(
    () => transcriptText().includes(spoken),
    { what: 'dictated text landed in agent transcript' },
  );
  await expect(page.locator('#w-chat')).toContainText(spoken);
  await expect(page.locator('#w-chat')).toContainText(`ack: ${spoken}`, { timeout: 25_000 });

  phone.close();
});

test('creating a session fails honestly — no fake success, no phantom row', async ({ page }) => {
  await page.goto(BASE);
  // The pill sits in a scroll-reveal container that stays hidden until the
  // list is scrolled; use the app's own deep-link hook to open the view.
  await page.waitForFunction(() => typeof window.__ambientOpenNew === 'function');
  await page.evaluate(() => window.__ambientOpenNew());
  await expect(page.locator('#view-new')).toBeVisible();
  await page.fill('#new-prompt', 'do a thing');

  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/ambient-link/sessions') && r.request().method() === 'POST'),
    page.click('#new-start'),
  ]);
  expect(resp.status()).toBe(501); // relay is honest: remote spawn is not wired

  await expect(page.locator('#toast')).toContainText(/terminal/i); // UI is honest too
  await expect(page.locator('#view-new')).toBeVisible(); // and does not pretend to navigate
});
