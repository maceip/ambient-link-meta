// Minimal service worker — required for Meta Display web-app install.
//
// Startup speed contract (glasses browse over a slow phone-relayed link, and
// the user is literally staring at a blank waveguide until first paint):
//   - EVERYTHING is CACHE-FIRST. Navigations serve the cached shell instantly
//     and revalidate in the background; assets carry a ?v= build tag so a new
//     deploy changes the URL and can never be masked by a stale cache.
//   - Freshness comes from the SW update flow: Caddy serves sw.js no-cache,
//     the page calls reg.update() on load, a byte-changed sw.js installs the
//     new cache atomically and reloads the page once via controllerchange.
// Relay API paths are never intercepted.
const V = '84'; // keep in lockstep with ?v= in index.html
const CACHE = 'ambient-link-meta-v' + V;
// Versioned entries must match index.html's ?v= URLs exactly: cache-first
// below uses exact-URL matching so a hit is always the right build.
const SHELL = [
  './',
  './index.html',
  './log.js?v=' + V,
  './app.js?v=' + V,
  './chipset.js?v=' + V,
  './content-pipeline.js?v=' + V,
  './blocks/blocks.js?v=' + V,
  './blocks/blocks.css?v=' + V,
  './styles.css?v=' + V,
  './companion.css?v=' + V,
  './themes.css?v=' + V,
  './beam.css?v=' + V,
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
  if (/^\/ambient-link\/(ws|status|pair|sessions|ingest|hooks\/|debug\/|history)/.test(url.pathname)) return;
  if (e.request.method !== 'GET') return;

  if (e.request.mode === 'navigate') {
    e.respondWith(
      caches.match(e.request, { ignoreSearch: true })
        .then(r => r || caches.match('./index.html'))
        .then(hit => {
          // Background revalidate keeps the cached shell current even between
          // sw.js version bumps; the response is NOT awaited — paint now.
          const refresh = fetch(e.request).then(resp => {
            if (resp && resp.ok) {
              const copy = resp.clone();
              caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
            }
            return resp;
          });
          if (hit) {
            e.waitUntil(refresh.catch(() => {}));
            return hit;
          }
          // Cold cache (first ever visit): we must go to the network, but
          // navigations must never resolve empty — that is exactly what makes
          // the glasses browser replace the app with its dead-end error page.
          return refresh.catch(() =>
            new Response(RETRY_PAGE, { headers: { 'Content-Type': 'text/html' } })
          );
        })
    );
    return;
  }

  // Static assets: cache-first with EXACT URL match — the ?v= tag is the
  // freshness key, so a cache hit is always the right build and startup does
  // not pay one network round-trip per asset.
  e.respondWith(
    caches.match(e.request).then(hit => {
      if (hit) return hit;
      return fetch(e.request).then(resp => {
        if (resp && resp.ok) {
          const copy = resp.clone();
          caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
        }
        return resp;
      }).catch(() =>
        caches.match(e.request, { ignoreSearch: true }).then(r => r || Response.error())
      );
    })
  );
});
