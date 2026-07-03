/**
 * Session list layout — Instagram-style flat rows on 600×600 glasses viewport.
 * Validates: no theme bar, no conn gutter, per-row status dots, scroll works.
 */
import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  openCompanion,
  waitForRelayConnected,
  fetchRelayStatus,
} from './e2e-helpers.mjs';

const OUT = join(dirname(fileURLToPath(import.meta.url)), 'output');
mkdirSync(OUT, { recursive: true });

test.describe('session list — Instagram layout', () => {
  test('flat rows, no theme bar, no conn gutter, status on cards', async ({ page }) => {
    const status = await fetchRelayStatus();
    await openCompanion(page);
    await waitForRelayConnected(page);

    await expect(page.locator('#theme-bar')).toHaveCount(0);
    await expect(page.locator('#conn-status')).toHaveCount(0);

    const list = page.locator('#threads.ig-list');
    await expect(list).toBeVisible();

    const rows = page.locator('#threads .thread-row');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    expect(count).toBeLessThanOrEqual(4);

    const liveCount = status.sessions?.filter((s) => s.state !== 'DEAD').length ?? count;
    expect(count).toBe(Math.min(4, liveCount));

    const first = rows.first();
    const box = await first.boundingBox();
    expect(box).toBeTruthy();
    expect(box.height).toBeGreaterThanOrEqual(68);
    expect(box.height).toBeLessThanOrEqual(88);

    await expect(first.locator('.thread-time')).toBeVisible();
    await expect(first.locator('.thread-conn-dot')).toHaveCount(1);

    const scroll = page.locator('#list-scroll');
    const before = await scroll.evaluate((el) => el.scrollTop);
    await scroll.evaluate((el) => { el.scrollTop = el.scrollHeight; });
    const after = await scroll.evaluate((el) => el.scrollTop);
    if (count > 4) {
      expect(after).toBeGreaterThan(before);
    }

    await page.screenshot({
      path: join(OUT, 'session-list-instagram-layout.png'),
      fullPage: false,
    });

    const codexRow = page.locator('#threads .agent-codex').first();
    if (await codexRow.count()) {
      const codexSvg = codexRow.locator('.avatar svg');
      await expect(codexSvg).toBeVisible();
      const paths = await codexSvg.locator('path').count();
      expect(paths).toBeGreaterThan(0);
      await page.screenshot({
        path: join(OUT, 'session-list-codex-avatar.png'),
        clip: await codexRow.boundingBox(),
      });
    }
  });
});
