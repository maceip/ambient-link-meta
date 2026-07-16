import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Staging contract: output is committed to ../web/next/ and served by the
// existing Caddy file_server at https://agent.public.computer/next/ (the git
// pull deploy ships it — no server changes). base './' keeps asset URLs
// relative so the same build works at /next/ and, later, at the root.
export default defineConfig({
  plugins: [svelte()],
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
    outDir: '../web/next',
    emptyOutDir: true,
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
