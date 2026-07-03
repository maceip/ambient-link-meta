/**
 * Core glasses path: list → open session → type in compose field.
 * No theme bar, no Android-only chrome — just relay + sessions + input.
 */
import { test, expect } from '@playwright/test';
import {
  openCompanion,
  waitForRelayConnected,
  fetchRelayStatus,
  liveRelaySessions,
} from './e2e-helpers.mjs';

test.describe('core session flow', () => {
  test('open session from list and reach editable prompt', async ({ page }) => {
    const status = await fetchRelayStatus();
    const live = liveRelaySessions(status);

    const { errors } = await openCompanion(page);
    await waitForRelayConnected(page);

    await expect(page.locator('#theme-bar')).toHaveCount(0);
    await expect(page.locator('#view-threads')).toBeVisible();

    if (live.length === 0) {
      test.skip(true, 'no live relay sessions — start an agent on Mac first');
    }

    const row = page.locator('#threads .thread-row').first();
    await expect(row).toBeVisible();
    await row.click();

    await expect(page.locator('#view-thread')).toBeVisible();
    await expect(page.locator('#view-threads')).toBeHidden();
    await expect(page.locator('#prompt')).toBeEditable();

    await page.fill('#prompt', 'playwright core flow probe');
    await expect(page.locator('#prompt')).toHaveValue('playwright core flow probe');

    await page.locator('#back').click();
    await expect(page.locator('#view-threads')).toBeVisible();

    expect(errors).toEqual([]);
  });
});
