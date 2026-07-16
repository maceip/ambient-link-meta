import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { generateServiceWorker } from './sw-plugin.js';

// Deploy contract: output is committed to ../web/ (the directory Caddy
// file_servers at https://agent.public.computer/) so the git-pull production
// deploy ships it with zero server changes. base './' keeps asset URLs
// relative so the same build also works under /ambient-link/ (the relay's
// static path used by the e2e suite) and any other prefix.
export default defineConfig({
  plugins: [svelte(), generateServiceWorker()],
  base: './',
  // Vitest must compile runes with the CLIENT runtime (jsdom), not SSR.
  resolve: process.env.VITEST ? { conditions: ['browser'] } : undefined,
  server: {
    // Local dev against a locally running relay.
    proxy: {
      '/ambient-link': {
        target: 'http://127.0.0.1:5181',
        ws: true,
      },
    },
  },
  build: {
    // web/ also holds the Playwright harness (test/, package.json, configs),
    // so Vite must NOT empty it; `npm run build` pre-cleans assets/ instead
    // (hashed filenames would otherwise accumulate forever).
    outDir: '../web',
    emptyOutDir: false,
    // The glasses browser burned us before; don't assume bleeding-edge JS.
    target: 'es2018',
    // Small app — one JS + one CSS file keeps the SW shell list trivial.
    cssCodeSplit: false,
  },
  test: {
    environment: 'jsdom',
    include: ['test/**/*.test.js'],
  },
});
