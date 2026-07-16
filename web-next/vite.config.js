import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Staging contract: output is committed to ../web/next/ and served by the
// existing Caddy file_server at https://agent.public.computer/next/ (the git
// pull deploy ships it — no server changes). base './' keeps asset URLs
// relative so the same build works at /next/ and, later, at the root.
export default defineConfig({
  plugins: [svelte()],
  base: './',
  build: {
    outDir: '../web/next',
    emptyOutDir: true,
    // The glasses browser burned us before; don't assume bleeding-edge JS.
    target: 'es2018',
    // Small app — one JS + one CSS file keeps the SW shell list trivial.
    cssCodeSplit: false,
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.js'],
  },
});
