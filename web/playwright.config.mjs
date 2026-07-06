// Playwright config for the glasses web app E2E suite.
// The suite is hermetic: it boots its own relay + fake agent (see
// test/e2e-live.spec.mjs); nothing here points at prod or the LAN relay.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './test',
  timeout: 60_000,
  expect: { timeout: 20_000 },
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    headless: true,
    viewport: { width: 640, height: 400 }, // glasses-ish letterbox
    trace: 'retain-on-failure',
  },
});
