// Opt-in production smoke against the installed glasses origin.
//
// This creates a real remote session through the public relay, sends a
// Playwright-generated timestamp into that session, and saves a screenshot of
// the opened thread. It is intentionally skipped unless AMBIENT_PROD_SMOKE=1
// so normal CI does not create real agent sessions on every push.
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const ORIGIN = process.env.AMBIENT_PROD_ORIGIN || 'https://agent.public.computer';
const AGENT = process.env.AMBIENT_PROD_AGENT || 'codex';
const ENABLED = process.env.AMBIENT_PROD_SMOKE === '1';

function safeStamp(s) {
  return s.replace(/[^0-9A-Za-z._-]/g, '-');
}

test.describe('production agent timestamp smoke', () => {
  test.skip(!ENABLED, 'Set AMBIENT_PROD_SMOKE=1 to create a real production agent session.');

  test('creates a session, posts exact Playwright time, and screenshots the thread', async ({ page }, testInfo) => {
    test.setTimeout(90_000);

    const exactTime = new Date().toISOString();
    const timestampText = `Playwright exact time: ${exactTime}`;
    const cwd = process.env.AMBIENT_PROD_CWD
      || fs.mkdtempSync(path.join(os.tmpdir(), 'ambient-link-prod-smoke-'));

    await page.goto(`${ORIGIN}/`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => typeof window.__ambientOpenNew === 'function');
    await page.evaluate(() => window.__ambientOpenNew());
    await expect(page.locator('#view-new')).toBeVisible();

    await page.locator(`#agent-chips [data-agent="${AGENT}"]`).click();
    await page.fill('#new-cwd', cwd);
    await page.fill('#new-prompt', `Create production smoke session for ${exactTime}`);

    const [createResp] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/ambient-link/sessions') && r.request().method() === 'POST'),
      page.click('#new-start'),
    ]);
    expect([200, 202], await createResp.text()).toContain(createResp.status());

    await expect(page.locator('#view-thread')).toBeVisible({ timeout: 30_000 });
    // No typed composer since v81 — configure a quick-reply chip carrying the
    // timestamp over the relay's companion_config fan-out (the same frames
    // relay-android sends) and click it in the action row.
    const wsUrl = ORIGIN.replace(/^http/, 'ws') + '/ambient-link/ws';
    const sock = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
      sock.addEventListener('open', resolve);
      sock.addEventListener('error', reject);
    });
    sock.send(JSON.stringify({ type: 'companion_config', quick_replies: [timestampText], source: 'phone' }));
    const chip = page.locator('#quick-replies .quick-reply-pill');
    await expect(chip).toBeVisible({ timeout: 15_000 });
    await chip.click();
    sock.close();

    await expect(page.locator('#w-chat')).toContainText(timestampText, { timeout: 30_000 });

    const screenshot = testInfo.outputPath(`agent-public-${safeStamp(exactTime)}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    await testInfo.attach('agent-public-thread', {
      path: screenshot,
      contentType: 'image/png',
    });
    console.log(`production screenshot: ${screenshot}`);
    console.log(`production timestamp: ${timestampText}`);
  });
});
