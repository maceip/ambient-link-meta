/**
 * Capture glasses-viewport session list for validation overlay (600×600).
 * Run: AMBIENT_WEB_ORIGIN=http://127.0.0.1:5181 node test/capture-session-list.mjs
 */
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = join(dirname(fileURLToPath(import.meta.url)), 'output');
mkdirSync(OUT, { recursive: true });

const ORIGIN = (process.env.AMBIENT_WEB_ORIGIN || 'http://127.0.0.1:5181').replace(/\/$/, '');
const URL = `${ORIGIN}/ambient-link/?v=49&_=${Date.now()}`;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 600, height: 600 } });

await page.addInitScript(() => {
  if (navigator.serviceWorker?.getRegistrations) {
    navigator.serviceWorker.getRegistrations().then((regs) => regs.forEach((r) => r.unregister()));
  }
});

await page.goto(URL, { waitUntil: 'networkidle', timeout: 60_000 });

// Wait for WS + session rows (up to 4).
await page.waitForFunction(() => {
  const rows = document.querySelectorAll('#threads .thread-row');
  return rows.length > 0 && document.querySelector('#theme-bar') == null && document.querySelector('#conn-status') == null;
}, { timeout: 45_000 });

const rows = await page.locator('#threads .thread-row').count();
if (rows === 0) throw new Error('No session rows rendered');

await page.screenshot({
  path: join(OUT, 'session-list-v49-600.png'),
  fullPage: false,
});

console.log(JSON.stringify({
  ok: true,
  url: URL,
  rows,
  path: join(OUT, 'session-list-v49-600.png'),
}));

await browser.close();
