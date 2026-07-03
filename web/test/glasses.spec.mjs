/**
 * Glasses-path E2E — deployed web app only, no laptop bridge, no SW unregister.
 *
 * Exercises the same cold-open path as Meta Display: public.computer → WS → /status → UI.
 */
import { test, expect } from '@playwright/test';
import {
  RELAY,
  fetchRelayStatus,
  liveRelaySessions,
  openCompanion,
  waitForRelayConnected,
  assertNoPageErrors,
  expectSessionListMatchesRelay,
  postSession,
  waitForToastHidden,
  clickNewStart,
} from './e2e-helpers.mjs';

const FRESH_CWD = `/tmp/al-playwright-${Date.now().toString(36)}`;

test.describe('deployed companion — glasses path', () => {
  test('cold open: no JS errors, relay WS connects', async ({ page }) => {
    const { errors } = await openCompanion(page, { serviceWorker: 'enabled' });
    await waitForRelayConnected(page);
    assertNoPageErrors(errors);
  });

  test('session list matches relay live session count', async ({ page }) => {
    const status = await fetchRelayStatus();
    const { errors } = await openCompanion(page);
    await waitForRelayConnected(page);
    await expectSessionListMatchesRelay(page, status);
    assertNoPageErrors(errors);
  });

  test('create with fresh cwd surfaces relay POST capability in UI', async ({ page }) => {
    const api = await postSession(RELAY, {
      agent: 'cursor',
      cwd: FRESH_CWD,
      prompt: 'playwright create probe',
    });

    const { errors } = await openCompanion(page);
    await waitForRelayConnected(page);

    await page.locator('[data-agent="cursor"]').click();
    await expect(page.locator('#view-new')).toBeVisible();
    await page.fill('#new-cwd', FRESH_CWD);
    await page.fill('#new-prompt', 'playwright create probe');
    const outcome = await clickNewStart(page);

    if (api.status === 501) {
      expect(outcome).toBe('create_failed');
      await expect(page.locator('#toast')).toContainText(/terminal first/i);
      await expect(page.locator('#view-thread')).toBeHidden();
    } else {
      expect(outcome).toBe('opened');
      await expect(page.locator('#view-thread')).toBeVisible();
      await expect(page.locator('#t-title')).not.toBeEmpty();
    }

    assertNoPageErrors(errors);
    await waitForToastHidden(page);
  });

  test('service worker reload keeps relay connected and list coherent', async ({ page }) => {
    const status = await fetchRelayStatus();
    const { errors } = await openCompanion(page, { serviceWorker: 'enabled', cacheBust: true });
    await waitForRelayConnected(page);
    await expectSessionListMatchesRelay(page, status);

    await page.reload();
    await waitForRelayConnected(page);
    await expectSessionListMatchesRelay(page, status);
    assertNoPageErrors(errors);
  });

  test('open live session compose shell when relay has sessions', async ({ page }) => {
    const status = await fetchRelayStatus();
    const live = liveRelaySessions(status);

    const { errors } = await openCompanion(page);
    await waitForRelayConnected(page);
    await expectSessionListMatchesRelay(page, status);

    if (live.length === 0) {
      await expect(page.locator('#empty-hint')).toBeVisible();
      assertNoPageErrors(errors);
      return;
    }

    await page.locator('.thread-row').first().click();
    await expect(page.locator('#view-thread')).toBeVisible();
    await expect(page.locator('#prompt')).toBeEditable();
    await expect(page.locator('#send')).toBeVisible();
    await expect(page.locator('#w-chat')).toBeAttached();
    assertNoPageErrors(errors);
  });

  test('round action buttons meet Meta Display minimum touch target', async ({ page }) => {
    const status = await fetchRelayStatus();
    const live = liveRelaySessions(status);

    const { errors } = await openCompanion(page);
    await waitForRelayConnected(page);

    if (live.length === 0) {
      await page.locator('[data-agent="cursor"]').click();
      await expect(page.locator('#new-start')).toBeVisible();
      const startBox = await page.locator('#new-start').boundingBox();
      expect(startBox).toBeTruthy();
      expect(startBox.width).toBeGreaterThanOrEqual(64);
      expect(startBox.height).toBeGreaterThanOrEqual(64);
      assertNoPageErrors(errors);
      return;
    }

    await page.locator('.thread-row').first().click();
    await expect(page.locator('#view-thread')).toBeVisible();

    const sendBox = await page.locator('#send').boundingBox();
    expect(sendBox).toBeTruthy();
    expect(sendBox.width).toBeGreaterThanOrEqual(64);
    expect(sendBox.height).toBeGreaterThanOrEqual(64);

    const backBox = await page.locator('#back').boundingBox();
    expect(backBox).toBeTruthy();
    expect(backBox.width).toBeGreaterThanOrEqual(64);
    expect(backBox.height).toBeGreaterThanOrEqual(64);

    assertNoPageErrors(errors);
  });
});
