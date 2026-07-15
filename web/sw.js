// Minimal service worker — required for Meta Display web-app install.
// Network-first for the shell (so a new deploy is never masked by a stale cache),
// cache as offline fallback. Relay paths are never intercepted.
const CACHE = 'ambient-link-meta-v78';
const SHELL = [
  './',
  './index.html',
  './log.js',
  './app.js',
  './chipset.js',
  './content-pipeline.js',
  './blocks/blocks.js',
  './blocks/blocks.css',
  './styles.css',
  './companion.css',
  './themes.css',
  './beam.css',
  './manifest.json',
  './icon.svg',
];

// Shown only when a navigation fails AND the cache has no shell — the glasses
// browser's own "No Internet Connection" page is a dead end (no reload UI),
// so we must always resolve navigations with something that retries itself.
const RETRY_PAGE = `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta http-equiv="refresh" content="4">
<title>ambient link</title>
<style>body{background:#000;color:#eee;font:16px system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}</style>
</head><body><div>reconnecting to relay…</div></body></html>`;

self.addEventListener('install', (e) => {
  // addAll is atomic: if any shell asset fails (flaky glasses↔phone link),
  // the whole install fails and the previous SW + cache keep serving. A
  // partially-populated cache is worse than a stale one — it turns the next
  // offline navigation into the browser's unrecoverable error page.
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (/^\/ambient-link\/(ws|status|pair|sessions|ingest|hooks\/|debug\/)/.test(url.pathname)) return;
  if (e.request.method !== 'GET') return;
  const isNav = e.request.mode === 'navigate';
  // Network-first: always try the live shell/assets, fall back to cache offline.
  e.respondWith(
    fetch(e.request).then(resp => {
      if (resp && resp.ok) {
        const copy = resp.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
      }
      return resp;
    }).catch(() =>
      caches.match(e.request, { ignoreSearch: true }).then(r => {
        if (r) return r;
        if (!isNav) return Response.error();
        // Navigations must never resolve empty — that is exactly what makes
        // the glasses browser replace the app with its dead-end error page.
        return caches.match('./index.html').then(idx =>
          idx || new Response(RETRY_PAGE, { headers: { 'Content-Type': 'text/html' } })
        );
      })
    )
  );
});
