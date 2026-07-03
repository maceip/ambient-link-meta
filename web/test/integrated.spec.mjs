/**
 * Full-stack E2E — deployed UI + laptop relay + cloud bridge + real agent turns.
 *
 * Requires laptop relay, cloud_peer, and the e2e workspace session bootstrapped by
 * scripts/run-e2e-new-session.sh (or equivalent).
 */
import { test, expect } from '@playwright/test';
import { mkdir, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bridgeOnce } from './bridge-helper.mjs';
import {
  RELAY,
  LOCAL,
  APP_PATH,
  fetchRelayStatus,
  liveRelaySessions,
  openCompanion,
  waitForRelayConnected,
  waitForToastHidden,
  probeRelay,
} from './e2e-helpers.mjs';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), 'output');
const TEST_CWD = process.env.AMBIENT_TEST_CWD || join(dirname(fileURLToPath(import.meta.url)), 'e2e-workspace');
const RUN_ID = process.env.AMBIENT_TEST_RUN_ID || Date.now().toString(36);
const PROMPT_1 =
  process.env.AMBIENT_TEST_PROMPT_1 ||
  `PLAYWRIGHT-TURN-1-${RUN_ID}: reply with one sentence acknowledging this exact id.`;
const PROMPT_2 =
  process.env.AMBIENT_TEST_PROMPT_2 ||
  `PLAYWRIGHT-TURN-2-${RUN_ID}: reply with one sentence acknowledging this second id.`;

const SHOTS = {
  thread: join(OUT_DIR, 'integrated-01-thread-open.png'),
  turn1: join(OUT_DIR, 'integrated-02-first-turn.png'),
  turn2: join(OUT_DIR, 'integrated-03-second-turn.png'),
};

/** @type {{ thread_id: string; label: string; cwd: string; agent: string } | null} */
let targetSession = null;

async function requireIntegratedStack() {
  if (!(await probeRelay(LOCAL))) {
    throw new Error(`Laptop relay down at ${LOCAL}. Run: bash scripts/start-host.sh`);
  }
  await bridgeOnce();
  const remote = await fetchRelayStatus(RELAY);
  if (!remote.cloud_peer) {
    throw new Error(
      `cloud_peer=false on ${RELAY}. Set AMBIENT_LINK_CLOUD=wss://public.computer/ambient-link/relay on laptop host.`,
    );
  }
  const live = liveRelaySessions(remote);
  if (!live.length) {
    throw new Error(`No live sessions on ${RELAY} after bridge.`);
  }

  const wantThread = process.env.AMBIENT_TEST_THREAD;
  const wantCwd = TEST_CWD.replace(/\/$/, '');
  const pick = (list) =>
    (wantThread && list.find((s) => s.thread_id === wantThread)) ||
    list.find((s) => (s.cwd || '').replace(/\/$/, '') === wantCwd) ||
    list.find((s) => (s.label || '').includes('e2e-workspace')) ||
    list[0];

  const hit = pick(live);
  if (!hit) throw new Error('No suitable live session for integrated e2e.');
  return hit;
}

async function sessionRow(threadId) {
  const data = await fetchRelayStatus(RELAY);
  return (data.sessions || []).find((s) => s.thread_id === threadId) || null;
}

async function laptopSession(threadId) {
  const res = await fetch(`${LOCAL}/ambient-link/status`, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) return null;
  const data = await res.json();
  return (data.sessions || []).find((s) => s.thread_id === threadId) || null;
}

async function waitForSent(page) {
  await expect(page.locator('#toast')).toContainText(/sent/i, { timeout: 30_000 });
  await waitForToastHidden(page);
}

async function pullCard(page, threadId) {
  for (let attempt = 0; attempt < 8; attempt++) {
    await waitForRelayConnected(page, 20_000);
    await page.evaluate(function (thread) {
      document.dispatchEvent(new CustomEvent('ambient-pull-card', { detail: { thread: thread } }));
    }, threadId);
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
  throw new Error('Pull failed: WebSocket not connected');
}

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
      laptopSession(threadId),
    ]);

    const assistant = (laptop && laptop.last_assistant) || (deployed && deployed.last_assistant) || '';
    const userInput =
      (laptop && laptop.last_user_input) || (deployed && deployed.last_user_input) || '';
    if (userInput.includes(fullMarker) || userInput.includes(shortMarker)) sawPrompt = true;

    if (await page.locator('#view-thread').isVisible()) {
      await pullCard(page, threadId).catch(() => {});
    }

    const card = (await page.locator('#w-chat').textContent().catch(() => '')) || '';
    const assistantHasMarker = assistant.includes(fullMarker) || assistant.includes(shortMarker);
    const cardHasMarker = card.includes(fullMarker) || card.includes(shortMarker);

    if (sawPrompt && assistantHasMarker) return { assistant, card: card || assistant };
    if (sawPrompt && assistant && assistant !== baselineAssistant && cardHasMarker) {
      return { assistant, card };
    }

    await page.waitForTimeout(2500);
  }

  throw new Error(
    `Timed out waiting for agent reply to "${userPrompt.slice(0, 60)}…". ` +
      `Check laptop relay, cloud bridge, scripts/e2e-agent-responder.mjs.`,
  );
}

async function openTargetThread(page, labelBit) {
  await waitForRelayConnected(page);
  const row = page.locator('.thread-row').filter({ hasText: labelBit }).first();
  await expect(row).toBeVisible({ timeout: 60_000 });
  await row.click();
  await expect(page.locator('#view-thread')).toBeVisible();
}

test.beforeAll(async () => {
  await mkdir(OUT_DIR, { recursive: true });
  targetSession = await requireIntegratedStack();
  console.log('[integrated] session:', targetSession.thread_id, targetSession.label);
});

test('real multi-turn conversation through cloud bridge', async ({ page }) => {
  const threadId = targetSession.thread_id;
  const labelBit =
    (targetSession.label || '').split(':').pop().trim() ||
    (targetSession.cwd || TEST_CWD).split('/').pop() ||
    'e2e-workspace';

  await openCompanion(page, { serviceWorker: 'enabled' });
  await openTargetThread(page, labelBit);
  await pullCard(page, threadId);
  await page.screenshot({ path: SHOTS.thread, fullPage: true });

  const baseline0 =
    (await laptopSession(threadId))?.last_assistant ||
    (await sessionRow(threadId))?.last_assistant ||
    '';

  await page.fill('#prompt', PROMPT_1);
  await page.click('#send');
  await waitForSent(page);

  const turn1 = await waitForRealAgentTurn(page, threadId, PROMPT_1, baseline0, {
    promptAlreadySent: true,
  });
  expect(turn1.assistant + turn1.card).toMatch(new RegExp(`PLAYWRIGHT-TURN-1-${RUN_ID}|TURN-1-${RUN_ID}`));
  await page.screenshot({ path: SHOTS.turn1, fullPage: true });

  await page.fill('#prompt', PROMPT_2);
  await page.click('#send');
  await waitForSent(page);

  const turn2 = await waitForRealAgentTurn(page, threadId, PROMPT_2, turn1.assistant, {
    promptAlreadySent: true,
  });
  expect(turn2.assistant + turn2.card).toMatch(new RegExp(`PLAYWRIGHT-TURN-2-${RUN_ID}|TURN-2-${RUN_ID}`));
  expect(turn2.assistant + turn2.card).toMatch(/TURN-1-|PLAYWRIGHT-TURN-1/);
  await page.screenshot({ path: SHOTS.turn2, fullPage: true });

  for (const path of Object.values(SHOTS)) {
    const info = await stat(path);
    expect(info.size).toBeGreaterThan(3000);
  }
});
