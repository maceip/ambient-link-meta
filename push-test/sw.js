// ambient-push-test service worker. Three jobs:
//   1) handle `push` events from a Web Push delivery → call showNotification on HUD
//   2) handle messages from the page (so we can fire a notification WITHOUT a real push,
//      isolating the "does Stella surface showNotification?" question from push transport)
//   3) handle notification clicks → focus/open the test page
self.addEventListener('install',  (e) => { self.skipWaiting(); });
self.addEventListener('activate', (e) => { e.waitUntil(self.clients.claim()); });

self.addEventListener('push', (event) => {
  let data = { title: 'ambient push', body: '(no payload)' };
  try { if (event.data) data = Object.assign(data, event.data.json()); }
  catch (e) { try { data.body = event.data?.text() || data.body; } catch (_) {} }
  event.waitUntil(
    self.registration.showNotification(data.title, {
      body: data.body,
      tag: data.tag || 'ambient-push',
      requireInteraction: !!data.requireInteraction,
      data: data,
    })
  );
});

self.addEventListener('message', (event) => {
  const m = event.data || {};
  if (m.type === 'show') {
    self.registration.showNotification(m.title || 'ambient test', {
      body: m.body || '(empty)',
      tag: m.tag || 'ambient-sw-test',
      requireInteraction: false,
    }).then(() => event.source?.postMessage({ type: 'shown', ok: true }))
      .catch((e) => event.source?.postMessage({ type: 'shown', ok: false, err: String(e) }));
  }
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (all[0]) { all[0].focus(); return; }
    await self.clients.openWindow('./');
  })());
});
