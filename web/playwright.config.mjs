import { defineConfig } from '@playwright/test';

/** Origin only — app is at /ambient-link/ (Playwright goto('/') would hit site root otherwise). */
const DEPLOYED_ORIGIN = 'https://public.computer';

const relayHost = (process.env.AMBIENT_RELAY_HOST || DEPLOYED_ORIGIN).replace(/\/$/, '');
const baseURL = (process.env.AMBIENT_WEB_ORIGIN || DEPLOYED_ORIGIN).replace(/\/$/, '');

export default defineConfig({
  testDir: './test',
  testMatch: '**/*.spec.mjs',
  globalSetup: './test/global-setup.mjs',
  timeout: 600_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL,
    viewport: { width: 600, height: 600 },
    locale: 'en-US',
    colorScheme: 'dark',
    trace: 'retain-on-failure',
  },
});
