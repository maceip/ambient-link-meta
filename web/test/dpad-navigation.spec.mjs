/**
 * D-pad navigation — list rows only, no theme chrome; matches Meta Display input model.
 */
import { test, expect } from '@playwright/test';
import {
  openCompanion,
  waitForRelayConnected,
  fetchRelayStatus,
  liveRelaySessions,
} from './e2e-helpers.mjs';

test.describe('D-pad navigation', () => {
  test('arrow keys cycle session rows on list view', async ({ page }) => {
    const status = await fetchRelayStatus();
    const live = liveRelaySessions(status);
    test.skip(live.length < 2, 'need at least 2 live sessions');

    await openCompanion(page);
    await waitForRelayConnected(page);

    await expect(page.locator('#theme-bar')).toHaveCount(0);
    const rows = page.locator('#threads .thread-row');
    await expect(rows.first()).toBeFocused();

    await page.keyboard.press('ArrowDown');
    await expect(rows.nth(1)).toBeFocused();

    await page.keyboard.press('ArrowUp');
    await expect(rows.first()).toBeFocused();
  });

  test('Enter opens focused session', async ({ page }) => {
    const status = await fetchRelayStatus();
    const live = liveRelaySessions(status);
    test.skip(live.length === 0, 'no live sessions');

    await openCompanion(page);
    await waitForRelayConnected(page);

    await page.keyboard.press('Enter');
    await expect(page.locator('#view-thread')).toBeVisible();
    await expect(page.locator('#view-threads')).toBeHidden();
  });
});
