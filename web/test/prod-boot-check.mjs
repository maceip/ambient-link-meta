// One-shot read-only boot check of the PRODUCTION origin after a deploy:
// loads the app, requires the session list view to render and the relay WS
// to reach "connected" (conn dot green). Sends nothing to any agent.
// Usage: node test/prod-boot-check.mjs
import { chromium } from '@playwright/test';

const ORIGIN = process.env.AMBIENT_PROD_ORIGIN || 'https://agent.public.computer/';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 640, height: 400 } });
try {
  await page.goto(ORIGIN, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.waitForSelector('#view-threads:not(.hidden)', { timeout: 15_000 });
  await page.waitForSelector('#conn-dot.on', { timeout: 20_000 });
  const pill = await page.$('#new-session-pill');
  if (!pill) throw new Error('#new-session-pill missing');
  const rows = await page.$$eval('#threads .thread-row', (r) => r.length);
  console.log(`prod boot OK: list rendered, WS connected, ${rows} session row(s)`);
} finally {
  await browser.close();
}
