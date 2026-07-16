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
//   3. a reply sent from the web UI (quick-reply chip — the composer input
//      and Send button were removed; glasses have no keyboard) LANDS in the
//      agent's stdin, is confirmed "landed" via transcript echo, and the
//      agent's answer flows back
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
let spawnedTmux;    // tmux session created by the create-session test

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
      // Create-session spawns run the fake agent instead of real CLIs. The
      // UI's default agent is cursor; both point at the same stub. HOME is
      // inlined because tmux panes inherit the tmux SERVER's env, not ours.
      AMBIENT_LINK_SPAWN_CURSOR: `HOME=${tmp} ${process.execPath} ${path.join(WEB_ROOT, 'test', 'fake-claude-agent.mjs')}`,
      AMBIENT_LINK_SPAWN_CLAUDE: `HOME=${tmp} ${process.execPath} ${path.join(WEB_ROOT, 'test', 'fake-claude-agent.mjs')}`,
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
  if (spawnedTmux) {
    try { execFileSync('tmux', ['kill-session', '-t', spawnedTmux]); } catch { /* already gone */ }
  }
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
  // The typed composer is gone (v81): the web's send paths are dictation and
  // the user-configured quick-reply chip in the action row. Configure the chip
  // over the same companion_config fan-out relay-android uses, then click it.
  observer.send({ type: 'companion_config', quick_replies: [text], source: 'phone' });
  const chip = page.locator('#quick-replies .quick-reply-pill');
  await expect(chip).toBeVisible();
  await chip.click();

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
  // Partial streamed to the glasses' visible listening line (the composer
  // input is a hidden state holder since v81 — not a UI surface).
  await expect(page.locator('#dictate-status-text')).toHaveText('hello from');
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

test('glasses economy: ~3.5 session cards visible at most (scroll for more)', async ({ page }) => {
  // Product rule (WhatsApp/IG/Messenger on-glasses precedent): the list never
  // shows more than ~3.5 cards; the half-card peek is the scroll affordance.
  await page.goto(BASE);
  const row = page.locator('#threads .thread-row').first();
  await expect(row).toBeVisible();
  const m = await page.evaluate(() => {
    const list = document.getElementById('threads');
    const r = list.querySelector('.thread-row').getBoundingClientRect();
    const cs = getComputedStyle(list);
    return {
      listTop: list.getBoundingClientRect().top,
      rowH: r.height,
      gap: parseFloat(cs.rowGap) || 0,
      vh: window.innerHeight,
    };
  });
  const fit = (m.vh - m.listTop) / (m.rowH + m.gap);
  const detail = `fit=${fit.toFixed(2)} cards (row ${m.rowH.toFixed(0)}px + gap ${m.gap}px, list top ${m.listTop.toFixed(0)}px, viewport ${m.vh}px)`;
  expect(fit, detail).toBeLessThanOrEqual(3.6);
  expect(fit, detail).toBeGreaterThanOrEqual(2.8);
});

test('New session pill is always visible above the list and opens the form', async ({ page }) => {
  // v83 killed pull-to-reveal: the pill is a permanent first row of the list
  // (the gesture was undiscoverable — nobody found the only create affordance).
  await page.goto(BASE);
  await expect(page.locator('#threads .thread-row').first()).toBeVisible();
  const pill = page.locator('#new-session-pill');
  await expect(pill).toBeVisible();
  // "Visible" is not enough: v68 shipped with the pill translated up under
  // the header — partially in-viewport (so toBeVisible passed) but the tap
  // landed on the header. The pill must sit fully below the header AND a
  // real click must reach it and open the create form.
  const hdrBox = await page.locator('.list-hdr').boundingBox();
  await expect
    .poll(async () => (await pill.boundingBox()).y, { timeout: 3000 })
    .toBeGreaterThanOrEqual(hdrBox.y + hdrBox.height);
  await pill.click({ timeout: 3000 });
  await expect(page.locator('#view-new')).toBeVisible();
});

test.describe('touch', () => {
  test.use({ hasTouch: true });
  test('a single tap opens a session — no double-tap dance', async ({ page }) => {
    // The glasses browser focuses on first tap and clicks on second;
    // wireImmediateTap synthesizes the click on a true tap (finger didn't
    // move). One physical tap must navigate.
    await page.goto(BASE);
    const row = page.locator('#threads .thread-row').first();
    await expect(row).toBeVisible();
    await row.tap();
    await expect(page.locator('#view-thread')).toBeVisible();
  });
});

test('creating a session actually spawns an agent the relay can see', async ({ page }) => {
  const spawnDir = path.join(tmp, 'spawned-project');
  fs.mkdirSync(spawnDir, { recursive: true });

  await page.goto(BASE);
  await page.waitForFunction(() => typeof window.__ambientOpenNew === 'function');
  await page.evaluate(() => window.__ambientOpenNew());
  await expect(page.locator('#view-new')).toBeVisible();
  await page.fill('#new-cwd', spawnDir);
  await page.fill('#new-prompt', 'e2e created session');

  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/ambient-link/sessions') && r.request().method() === 'POST'),
    page.click('#new-start'),
  ]);
  expect(resp.status()).toBe(200);
  const body = await resp.json();
  expect(body.ok).toBe('spawned');
  spawnedTmux = body.tmux; // killed in afterAll

  // The spawned agent writes a real transcript; the relay ingests it and the
  // new session surfaces with the creating prompt.
  await waitFor(async () => {
    const s = await relayStatus();
    // endsWith: macOS reports the pane cwd via /private/var while the test
    // built the path via /var (same dir through the tmpdir symlink).
    return (s.sessions || []).some(
      (x) => (x.cwd || '').endsWith('spawned-project') && x.state !== 'DEAD',
    ) ? true : null;
  }, { what: 'spawned session visible in relay status' });
  await page.goto(BASE);
  await expect(page.locator('#threads')).toContainText('spawned-project');
});
