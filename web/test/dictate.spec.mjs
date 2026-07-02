/**
 * Interactive phone-bridge dictate test on the deployed web app.
 *
 * Flow: browser (no SpeechRecognition) → dictate_begin → phone SODA (phone mic) →
 * dictate_partial / dictate_commit back to the web composer.
 *
 * YOU SPEAK during the wait window. Requires phone daemon via USB adb.
 */
import { test, expect } from '@playwright/test';
import { mkdir, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bridgeOnce } from './bridge-helper.mjs';
import {
  ensurePhoneDaemon,
  startDictateLogcat,
  logcatShowsSco,
} from './phone-dictate-helper.mjs';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), 'output');
const RELAY = (process.env.AMBIENT_RELAY_HOST || 'https://public.computer').replace(/\/$/, '');
const LOCAL = (process.env.AMBIENT_LOCAL_RELAY || 'http://127.0.0.1:5181').replace(/\/$/, '');
const APP_PATH = (process.env.AMBIENT_WEB_PATH || '/ambient-link/').replace(/\/?$/, '/');
const TEST_CWD = process.env.AMBIENT_TEST_CWD || join(dirname(fileURLToPath(import.meta.url)), 'e2e-workspace');
const SPEAK_WAIT_MS = Number(process.env.AMBIENT_DICTATE_WAIT_MS || 90_000);

const SHOTS = {
  ready: join(OUT_DIR, 'dictate-01-session-open.png'),
  listening: join(OUT_DIR, 'dictate-02-listening.png'),
  result: join(OUT_DIR, 'dictate-03-result.png'),
};

/** @type {import('./new-session.spec.mjs') extends never ? any : never} */
let targetSession = null;

async function loadLiveSession() {
  await bridgeOnce().catch(() => {});
  const [laptop, remote] = await Promise.all([
    fetch(`${LOCAL}/ambient-link/sessions`, { signal: AbortSignal.timeout(15_000) })
      .then((r) => r.json())
      .catch(() => ({ sessions: [] })),
    fetch(`${RELAY}/ambient-link/status`, { signal: AbortSignal.timeout(15_000) })
      .then((r) => r.json()),
  ]);
  const laptopLive = (laptop.sessions || []).filter((s) => s.state !== 'DEAD');
  const remoteLive = (remote.sessions || []).filter((s) => s.state !== 'DEAD');
  const wantThread = process.env.AMBIENT_TEST_THREAD;
  if (wantThread) {
    const hit =
      remoteLive.find((s) => s.thread_id === wantThread) ||
      laptopLive.find((s) => s.thread_id === wantThread);
    if (!hit) throw new Error(`AMBIENT_TEST_THREAD=${wantThread} not live`);
    return hit;
  }
  const wantCwd = TEST_CWD.replace(/\/$/, '');
  const pick = (list) =>
    list.find((s) => (s.cwd || '').replace(/\/$/, '') === wantCwd) ||
    list.find((s) => (s.agent || '').toLowerCase() === 'cursor') ||
    list[0];
  return pick(remoteLive) || pick(laptopLive);
}

test.beforeAll(async () => {
  await mkdir(OUT_DIR, { recursive: true });
  await ensurePhoneDaemon();
  targetSession = await loadLiveSession();
  console.log('[dictate e2e] session:', targetSession?.thread_id, targetSession?.label);
});

test.beforeEach(async ({ context }) => {
  await context.route('**/sw.js', (route) => route.abort());
  await context.addInitScript(() => {
    if (navigator.serviceWorker?.getRegistrations) {
      navigator.serviceWorker.getRegistrations().then((regs) => regs.forEach((r) => r.unregister()));
    }
    // Force phone-bridge path (same as Meta Display glasses browser).
    delete window.SpeechRecognition;
    delete window.webkitSpeechRecognition;
  });
});

test('phone dictate: speak into glasses mic, partials land in composer', async ({ page }) => {
  test.skip(!targetSession, 'no live session');
  test.setTimeout(SPEAK_WAIT_MS + 120_000);

  const threadId = targetSession.thread_id;
  const labelBit =
    (targetSession.label || '').split(':').pop().trim() ||
    (targetSession.cwd || '').split('/').pop() ||
    'e2e';

  const logcat = startDictateLogcat();

  await page.goto(`${APP_PATH}?session=${encodeURIComponent(threadId)}`);
  await expect(page.locator('#conn-dot')).toHaveClass(/on/, { timeout: 60_000 });
  await expect(page.locator('#view-thread')).toBeVisible({ timeout: 30_000 });
  await page.screenshot({ path: SHOTS.ready, fullPage: true });

  const dictate = page.locator('#dictate');
  await dictate.scrollIntoViewIfNeeded();
  await dictate.click();

  await expect(dictate).toHaveClass(/recording/, { timeout: 15_000 });
  await expect(page.locator('#prompt')).toHaveAttribute('placeholder', /listening/i);
  await page.screenshot({ path: SHOTS.listening, fullPage: true });

  console.log('\n════════════════════════════════════════════════════════');
  console.log(`  SPEAK NOW — up to ${Math.round(SPEAK_WAIT_MS / 1000)}s`);
  console.log('  Use your glasses mic (Bluetooth HFP). Say a short sentence.');
  console.log('  Stop talking; SODA commits after a brief pause.');
  console.log('════════════════════════════════════════════════════════\n');

  let heardText = '';
  const deadline = Date.now() + SPEAK_WAIT_MS;
  while (Date.now() < deadline) {
    const promptVal = ((await page.locator('#prompt').inputValue()) || '').trim();
    const cardText = ((await page.locator('#w-card').textContent()) || '').trim();
    const toast = ((await page.locator('#toast').textContent()) || '').trim();

    if (promptVal.length >= 3 && !/^listening/i.test(promptVal)) heardText = promptVal;
    if (/sent/i.test(toast) && heardText.length >= 3) break;
    if (cardText.includes('You:')) {
      const m = cardText.match(/You:\s*(.+)/s);
      const live = m && m[1].trim();
      if (live && live.length >= 3 && !/^listening/i.test(live)) heardText = live;
    }
    if (/sent/i.test(toast) && heardText.length >= 3) break;

    await page.waitForTimeout(400);
  }

  await page.screenshot({ path: SHOTS.result, fullPage: true });
  const { path: logPath, lines } = await logcat.stop();

  console.log(`[dictate e2e] logcat → ${logPath} (${lines.length} lines)`);
  console.log(`[dictate e2e] composer text: "${heardText}"`);

  expect(
    heardText.length >= 3,
    `No speech detected in ${SPEAK_WAIT_MS / 1000}s — speak into glasses mic; check daemon, SODA pack, BT link. logcat: ${logPath}`,
  ).toBeTruthy();

  if (logcatShowsSco(lines)) {
    console.log('[dictate e2e] adb log shows Bluetooth SCO / sco=true');
  }

  for (const path of Object.values(SHOTS)) {
    const info = await stat(path);
    expect(info.size).toBeGreaterThan(3000);
  }
});
