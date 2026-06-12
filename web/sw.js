// Minimal service worker — required for Meta Display web-app install. Cache-first
// for app shell, network for ws.
const CACHE = 'face-chat-final-v1';
const SHELL = ['./', './index.html', './app.js', './styles.css', './manifest.json'];

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
  if (url.pathname.includes('/face-chat/ws')) return;
  e.respondWith(caches.match(e.request).then(r => r || fetch(e.request).then(resp => {
    if (e.request.method === 'GET' && resp.ok) {
      const copy = resp.clone();
      caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
    }
    return resp;
  }).catch(() => caches.match('./index.html'))));
});
