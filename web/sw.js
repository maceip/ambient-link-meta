// Minimal service worker — required for Meta Display web-app install.
// Network-first for the shell (so a new deploy is never masked by a stale cache),
// cache as offline fallback. Relay paths are never intercepted.
const CACHE = 'ambient-link-meta-v68';
const SHELL = [
  './',
  './index.html',
  './app.js',
  './chipset.js',
  './content-pipeline.js',
  './blocks/blocks.js',
  './blocks/blocks.css',
  './styles.css',
  './companion.css',
  './themes.css',
  './manifest.json',
  './icon.svg',
];

self.addEventListener('install', (e) => {
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
  // Network-first: always try the live shell/assets, fall back to cache offline.
  e.respondWith(
    fetch(e.request).then(resp => {
      if (resp && resp.ok) {
        const copy = resp.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
      }
      return resp;
    }).catch(() => caches.match(e.request).then(r => r || caches.match('./index.html')))
  );
});
