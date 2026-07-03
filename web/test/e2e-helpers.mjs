/** Shared Playwright helpers — deployed relay + glasses viewport, no mocks. */
import { expect } from '@playwright/test';

export const RELAY = (process.env.AMBIENT_RELAY_HOST || 'https://public.computer').replace(/\/$/, '');
export const LOCAL = (process.env.AMBIENT_LOCAL_RELAY || 'http://127.0.0.1:5181').replace(/\/$/, '');
export const APP_PATH = (process.env.AMBIENT_WEB_PATH || '/ambient-link/').replace(/\/?$/, '/');

export async function fetchRelayStatus(base = RELAY) {
  const res = await fetch(`${base}/ambient-link/status`, { signal: AbortSignal.timeout(15_000) });
  if (!res.ok) throw new Error(`${base}/ambient-link/status → ${res.status}`);
  return res.json();
}

export function liveRelaySessions(status) {
  return (status.sessions || []).filter((s) => s.state !== 'DEAD');
}

export async function probeRelay(base) {
  try {
    const h = await fetch(`${base}/healthz`, { signal: AbortSignal.timeout(8000) });
    if (h.ok) return true;
  } catch {
    /* cloud has no /healthz */
  }
  const s = await fetch(`${base}/ambient-link/status`, { signal: AbortSignal.timeout(12_000) });
  return s.ok;
}

/** Collect uncaught JS errors — fails the test if any fire after navigation. */
export function attachPageDiagnostics(page) {
  const errors = [];
  page.on('pageerror', (err) => errors.push(err.message || String(err)));
  return errors;
}

export function assertNoPageErrors(errors, label = 'page') {
  expect(errors, `${label} runtime errors:\n${errors.join('\n')}`).toEqual([]);
}

export async function disableServiceWorker(context) {
  await context.route('**/sw.js', (route) => route.abort());
  await context.addInitScript(() => {
    if (navigator.serviceWorker?.getRegistrations) {
      navigator.serviceWorker.getRegistrations().then((regs) => {
        regs.forEach((r) => r.unregister());
      });
    }
  });
}

/**
 * Open the deployed companion like glasses: square viewport, real WS/API.
 * @param {import('@playwright/test').Page} page
 * @param {{ cacheBust?: boolean, serviceWorker?: 'enabled' | 'disabled' }} opts
 */
export async function openCompanion(page, opts = {}) {
  const cacheBust = opts.cacheBust !== false;
  const sw = opts.serviceWorker ?? 'enabled';
  if (sw === 'disabled') await disableServiceWorker(page.context());

  const errors = attachPageDiagnostics(page);
  const url = APP_PATH + (cacheBust ? `?/_=${Date.now()}` : '');
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('#app')).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('#theme-bar')).toHaveCount(0);
  await expect(page.locator('#conn-status')).toHaveCount(0);
  return { errors };
}

export async function waitForRelayConnected(page, timeoutMs = 60_000) {
  await page.waitForFunction(
    () => document.body && document.body.dataset.relayState === 'on',
    { timeout: timeoutMs },
  );
}

/** Open new-session form (pull-reveal pill or direct hook). */
export async function openNewSessionForm(page) {
  await page.evaluate(() => {
    var reveal = document.getElementById('new-session-reveal');
    if (reveal) {
      reveal.classList.add('open');
      reveal.setAttribute('aria-hidden', 'false');
    }
    if (typeof window.__ambientOpenNew === 'function') window.__ambientOpenNew();
    else document.getElementById('new-session-pill')?.click();
  });
  await expect(page.locator('#view-threads')).toBeHidden({ timeout: 10_000 });
  await expect(page.locator('#view-new')).toBeVisible({ timeout: 10_000 });
}

/** Click #new-start; retries when WS reconnect races the create form. */
export async function clickNewStart(page) {
  await expect(page.locator('#view-new')).toBeVisible();
  const start = page.locator('#new-start');
  for (let attempt = 0; attempt < 8; attempt++) {
    await waitForRelayConnected(page, 20_000);
    await page.evaluate(() => document.getElementById('new-start')?.click());
    await page.waitForTimeout(400);
    const toastText = ((await page.locator('#toast').textContent()) || '').trim();
    if (await page.locator('#view-thread').isVisible()) return 'opened';
    if (/starting|sent/i.test(toastText)) {
      await expect(page.locator('#view-thread')).toBeVisible({ timeout: 10_000 });
      return 'opened';
    }
    if (/terminal first|session create|unavailable/i.test(toastText)) return 'create_failed';
    if (/not connected/i.test(toastText)) {
      await page.waitForTimeout(1500);
      continue;
    }
    if (toastText) return 'toast';
    await page.waitForTimeout(500);
  }
  throw new Error('Start failed: WebSocket not connected after retries');
}

export async function waitForToastHidden(page) {
  const toast = page.locator('#toast.visible');
  if (await toast.count()) {
    await expect(toast).toBeHidden({ timeout: 6000 });
  }
}

/** UI live rows should match relay /status (same filter: state !== DEAD). */
export async function expectSessionListMatchesRelay(page, status) {
  const expected = Math.min(4, liveRelaySessions(status).length);
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const rows = await page.locator('.thread-row').count();
    const hint = ((await page.locator('#empty-hint').textContent().catch(() => '')) || '').trim();
    const hintVisible = await page.locator('#empty-hint').isVisible().catch(() => false);
    if (expected === 0) {
      if (rows === 0 && hintVisible && hint.length > 0) return;
    } else if (rows === expected) {
      return;
    }
    await page.waitForTimeout(1000);
  }
  const rows = await page.locator('.thread-row').count();
  throw new Error(
    `session list mismatch: relay has ${expected} live session(s), UI shows ${rows} row(s)`,
  );
}

export async function postSession(base, body) {
  const res = await fetch(`${base}/ambient-link/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('application/json') ? await res.json().catch(() => ({})) : {};
  return { status: res.status, data };
}
