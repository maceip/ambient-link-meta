/**
 * E2E on the DEPLOYED web app (https://public.computer/ambient-link).
 *
 * NO mocks:
 *   - No mock HTTP server, no fake WS hub, no debug/yank, no virtual ingest in-test.
 *   - Playwright opens the deployed UI; WS/API hit public.computer.
 *   - User prompts → real browser → real relay WS `input` → laptop agent (via cloud bridge).
 *   - Agent replies → real jsonl/hooks on laptop → mirrored to cloud → UI card (pull if relay_debug).
 *
 * global-setup runs one relay-bridge cycle (laptop → public.computer) so the deployed
 * index sees your live laptop sessions — same as glasses.
 */
import { test, expect } from '@playwright/test';
import { mkdir, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bridgeOnce } from './bridge-helper.mjs';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), 'output');
const RELAY = (process.env.AMBIENT_RELAY_HOST || 'https://public.computer').replace(/\/$/, '');
const LOCAL = (process.env.AMBIENT_LOCAL_RELAY || 'http://127.0.0.1:5181').replace(/\/$/, '');
/** Playwright baseURL is origin-only; app lives under /ambient-link/ */
const APP_PATH = (process.env.AMBIENT_WEB_PATH || '/ambient-link/').replace(/\/?$/, '/');
const TEST_CWD = process.env.AMBIENT_TEST_CWD || join(dirname(fileURLToPath(import.meta.url)), 'e2e-workspace');

const RUN_ID = process.env.AMBIENT_TEST_RUN_ID || Date.now().toString(36);
const PROMPT_1 =
  process.env.AMBIENT_TEST_PROMPT_1 ||
  `PLAYWRIGHT-TURN-1-${RUN_ID}: reply with one sentence acknowledging this exact id.`;
const PROMPT_2 =
  process.env.AMBIENT_TEST_PROMPT_2 ||
  `PLAYWRIGHT-TURN-2-${RUN_ID}: reply with one sentence acknowledging this second id.`;

const SHOTS = {
  form: join(OUT_DIR, '01-create-session-form.png'),
  list: join(OUT_DIR, '02-session-list-active.png'),
  first: join(OUT_DIR, '03-session-first-prompt.png'),
  twoTurns: join(OUT_DIR, '04-session-two-turns.png'),
};

/** @type {{ thread_id: string; session_id: string; label: string; cwd: string; agent: string } | null} */
let targetSession = null;

async function fetchStatus() {
  const res = await fetch(`${RELAY}/ambient-link/status`, { signal: AbortSignal.timeout(15_000) });
  if (!res.ok) throw new Error(`status ${res.status}`);
  return res.json();
}

async function sessionRow(threadId) {
  const data = await fetchStatus();
  return (data.sessions || []).find((s) => s.thread_id === threadId) || null;
}

async function laptopSession(threadId) {
  const res = await fetch(`${LOCAL}/ambient-link/status`, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) return null;
  const data = await res.json();
  return (data.sessions || []).find((s) => s.thread_id === threadId) || null;
}

async function loadLiveSession() {
  const laptop = await fetch(`${LOCAL}/ambient-link/sessions`, { signal: AbortSignal.timeout(15_000) })
    .then((r) => r.json())
    .catch(() => ({ sessions: [] }));
  const laptopLive = (laptop.sessions || []).filter((s) => s.state !== 'DEAD');

  await bridgeOnce();

  const data = await fetchStatus();
  const sessions = (data.sessions || []).filter((s) => s.state !== 'DEAD');
  if (!sessions.length && !laptopLive.length) {
    throw new Error(`No live sessions on laptop or deployed relay after bridge.`);
  }

  const wantThread = process.env.AMBIENT_TEST_THREAD;
  if (wantThread) {
    const hit =
      sessions.find((s) => s.thread_id === wantThread) ||
      laptopLive.find((s) => s.thread_id === wantThread);
    if (!hit) throw new Error(`AMBIENT_TEST_THREAD=${wantThread} not live`);
    return hit;
  }

  const wantCwd = TEST_CWD.replace(/\/$/, '');
  const pick = (list) =>
    list.find((s) => (s.cwd || '').replace(/\/$/, '') === wantCwd) ||
    list.find((s) => (s.label || '').includes('e2e-workspace')) ||
    list.find((s) => (s.agent || '').toLowerCase() === 'cursor') ||
    list[0];

  return pick(sessions) || pick(laptopLive);
}

async function waitForToastHidden(page) {
  const toast = page.locator('#toast.visible');
  if (await toast.count()) {
    await expect(toast).toBeHidden({ timeout: 6000 });
  }
}

async function waitForSent(page) {
  await expect(page.locator('#toast')).toContainText(/sent/i, { timeout: 30_000 });
  await waitForToastHidden(page);
}

async function pullCard(page) {
  const sessionId = new URL(page.url()).searchParams.get('session');
  for (let attempt = 0; attempt < 8; attempt++) {
    await expect(page.locator('#conn-dot')).toHaveClass(/on/, { timeout: 20_000 });
    await page.evaluate(function (thread) {
      document.dispatchEvent(new CustomEvent('ambient-pull-card', { detail: { thread: thread } }));
    }, sessionId);
    const toastText = ((await page.locator('#toast').textContent()) || '').trim();
    if (/refresh/i.test(toastText)) {
      await waitForToastHidden(page);
      return;
    }
    if (/not connected/i.test(toastText)) {
      await page.waitForTimeout(1500);
      continue;
    }
    await expect(page.locator('#toast')).toContainText(/refresh/i, { timeout: 10_000 });
    await waitForToastHidden(page);
    return;
  }
  throw new Error('Pull failed: deployed relay WebSocket not connected after retries');
}

/**
 * Wait for a REAL agent turn: deployed /status last_assistant changes AND card updates.
 * Polls laptop status too — source of truth for jsonl agent output.
 */
async function waitForRealAgentTurn(page, threadId, userPrompt, baselineAssistant, opts = {}) {
  const fullMarker = userPrompt.includes('TURN-1')
    ? `PLAYWRIGHT-TURN-1-${RUN_ID}`
    : `PLAYWRIGHT-TURN-2-${RUN_ID}`;
  const shortMarker = userPrompt.includes('TURN-1') ? `TURN-1-${RUN_ID}` : `TURN-2-${RUN_ID}`;
  const deadline = Date.now() + 300_000;
  let sawPrompt = opts.promptAlreadySent === true;

  while (Date.now() < deadline) {
    await bridgeOnce().catch(() => {});

    const [deployed, laptop] = await Promise.all([
      sessionRow(threadId),
      fetch(`${LOCAL}/ambient-link/status`, { signal: AbortSignal.timeout(10_000) })
        .then((r) => r.json())
        .then((d) => (d.sessions || []).find((s) => s.thread_id === threadId))
        .catch(() => null),
    ]);

    const assistant =
      (laptop && laptop.last_assistant) ||
      (deployed && deployed.last_assistant) ||
      '';
    const userInput =
      (laptop && laptop.last_user_input) || (deployed && deployed.last_user_input) || '';
    if (userInput.includes(fullMarker) || userInput.includes(shortMarker)) {
      sawPrompt = true;
    }
    const assistantChanged = assistant && assistant !== baselineAssistant;
    const assistantHasMarker =
      assistant.includes(fullMarker) || assistant.includes(shortMarker);

    if (await page.locator('#view-thread').isVisible()) {
      await pullCard(page).catch(() => {});
    }

    const card = (await page.locator('#w-card').textContent().catch(() => '')) || '';
    const cardHasMarker =
      card.includes(fullMarker) || card.includes(shortMarker);

    if (sawPrompt && assistantHasMarker) {
      return { assistant, card: card || assistant };
    }
    if (sawPrompt && assistantChanged && cardHasMarker) {
      return { assistant, card };
    }

    await page.waitForTimeout(2500);
  }

  throw new Error(
    `Timed out waiting for real agent reply to "${userPrompt.slice(0, 60)}…"\n` +
      `Check laptop relay ${LOCAL}, cloud bridge, and scripts/e2e-agent-responder.mjs.`,
  );
}

async function waitForRelayReady(page, labelBit) {
  await expect(page.locator('#conn-dot')).toHaveClass(/on/, { timeout: 60_000 });
  await expect(page.locator('.thread-row').filter({ hasText: labelBit }).first()).toBeVisible({
    timeout: 60_000,
  });
}

/** Start can race WS reconnect on the deployed edge — retry until real send succeeds. */
async function clickStartSession(page) {
  const start = page.locator('#new-start');
  for (let attempt = 0; attempt < 8; attempt++) {
    await expect(page.locator('#conn-dot')).toHaveClass(/on/, { timeout: 20_000 });
    await start.scrollIntoViewIfNeeded();
    await start.click();
    if (await page.locator('#view-thread').isVisible()) return;
    const toastText = ((await page.locator('#toast').textContent()) || '').trim();
    if (/sent/i.test(toastText)) {
      await expect(page.locator('#view-thread')).toBeVisible({ timeout: 10_000 });
      return;
    }
    if (/not connected/i.test(toastText)) {
      await page.waitForTimeout(1500);
      continue;
    }
    throw new Error(`Start failed: ${toastText || '(no toast)'}`);
  }
  throw new Error('Start failed: deployed relay WebSocket not connected after retries');
}

async function fullPageShot(page, path) {
  await waitForToastHidden(page);
  await page.screenshot({ path, fullPage: true });
}

test.beforeAll(async () => {
  await mkdir(OUT_DIR, { recursive: true });
  targetSession = await loadLiveSession();
  console.log('[e2e] target session on deployed relay:', targetSession.thread_id, targetSession.label);
});

test.beforeEach(async ({ context }) => {
  // Avoid stale SW cache of an older deployed bundle during the run — not a relay mock.
  await context.route('**/sw.js', (route) => route.abort());
  await context.addInitScript(() => {
    if (navigator.serviceWorker?.getRegistrations) {
      navigator.serviceWorker.getRegistrations().then((regs) => {
        regs.forEach((r) => r.unregister());
      });
    }
  });
});

test('deployed web app: four gates, real multi-turn session', async ({ page }) => {
  test.skip(!targetSession, 'no live session on deployed relay');

  const threadId = targetSession.thread_id;
  const cwd = targetSession.cwd || TEST_CWD;
  const labelBit =
    (targetSession.label || '').split(':').pop().trim() ||
    cwd.split('/').pop() ||
    'e2e-workspace';

  await page.goto(APP_PATH);
  await expect(page.locator('#conn-dot')).toBeAttached({ timeout: 10_000 });
  await waitForRelayReady(page, labelBit);
  await waitForToastHidden(page);

  const baseline0 =
    (await laptopSession(threadId))?.last_assistant ||
    (await sessionRow(threadId))?.last_assistant ||
    '';

  // Gate 1 — create-session form (deployed UI)
  await page.locator('[data-agent="cursor"]').click();
  await expect(page.locator('#view-new')).toBeVisible();
  await page.fill('#new-cwd', cwd);
  await page.fill('#new-prompt', PROMPT_1);
  await fullPageShot(page, SHOTS.form);

  // Real WS input via deployed relay (no ingest/yank mocks)
  await clickStartSession(page);
  await waitForSent(page);

  const turn1 = await waitForRealAgentTurn(page, threadId, PROMPT_1, baseline0, {
    promptAlreadySent: true,
  });
  expect(turn1.assistant + turn1.card).toMatch(new RegExp(`PLAYWRIGHT-TURN-1-${RUN_ID}|TURN-1-${RUN_ID}`));

  await page.reload();
  await expect(page.locator('#conn-dot')).toHaveClass(/on/, { timeout: 30_000 });
  if (await page.locator('#view-thread').isVisible()) await page.click('#back');
  await expect(page.locator('#view-threads')).toBeVisible();

  // Gate 2 — session index on deployed app
  const sessionRowEl = page.locator('.thread-row').filter({ hasText: labelBit });
  await expect(sessionRowEl.first()).toBeVisible({ timeout: 20_000 });
  await expect(sessionRowEl.first().locator('.status-tag')).not.toHaveText(/ended/i);
  await sessionRowEl.first().click();
  await expect(page.locator('#view-thread')).toBeVisible();
  await pullCard(page);
  await expect(page.locator('#w-card')).toContainText(new RegExp(`PLAYWRIGHT-TURN-1-${RUN_ID}|TURN-1-${RUN_ID}`), {
    timeout: 20_000,
  });
  await page.click('#back');
  await expect(sessionRowEl.first().locator('.preview')).toContainText(/PLAYWRIGHT-TURN-1|TURN-1-/i, {
    timeout: 15_000,
  });
  await fullPageShot(page, SHOTS.list);

  // Gate 3 — open session, first real turn visible
  await sessionRowEl.first().click();
  await expect(page.locator('#view-thread')).toBeVisible();
  await pullCard(page);
  await expect(page.locator('#w-card')).toContainText(new RegExp(`PLAYWRIGHT-TURN-1-${RUN_ID}|TURN-1-${RUN_ID}`));
  await expect(page.locator('#w-card')).not.toContainText('agent is working');
  await fullPageShot(page, SHOTS.first);

  const baseline1 = turn1.assistant;

  // Gate 4 — second prompt, real agent follow-up mentioning both ids
  await page.fill('#prompt', PROMPT_2);
  await page.click('#send');
  await waitForSent(page);

  const turn2 = await waitForRealAgentTurn(page, threadId, PROMPT_2, baseline1, {
    promptAlreadySent: true,
  });
  expect(turn2.assistant + turn2.card).toMatch(new RegExp(`PLAYWRIGHT-TURN-2-${RUN_ID}|TURN-2-${RUN_ID}`));
  expect(turn2.assistant + turn2.card).toMatch(/TURN-1-|PLAYWRIGHT-TURN-1/);
  await fullPageShot(page, SHOTS.twoTurns);

  for (const path of Object.values(SHOTS)) {
    const info = await stat(path);
    expect(info.size).toBeGreaterThan(5000);
  }
});
