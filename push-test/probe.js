// Step-by-step probe. Each button corresponds to a stage; the log line tells you exactly
// what we observed so the next step's decision is data-driven, not guessed.
(function () {
  'use strict';
  var logEl = document.getElementById('log');
  function log(line, cls) {
    var el = document.createElement('div');
    if (cls) el.className = cls;
    var ts = new Date().toISOString().slice(11, 23);
    el.textContent = ts + '  ' + line;
    logEl.appendChild(el);
    logEl.scrollTop = logEl.scrollHeight;
  }
  function ok(s)   { log(s, 'ok'); }
  function bad(s)  { log(s, 'bad'); }
  function warn(s) { log(s, 'warn'); }
  function dim(s)  { log(s, 'dim'); }

  // Capture errors so silent SW/Notification failures surface in the log.
  window.addEventListener('error',              (e) => bad('window error: ' + (e.message || e.error)));
  window.addEventListener('unhandledrejection', (e) => bad('unhandled: ' + (e.reason?.message || e.reason)));

  // 1 — feature detection ─────────────────────────────────────────────
  document.getElementById('b-detect').onclick = function () {
    dim('--- detect ---');
    log('UA: ' + navigator.userAgent);
    log('display-mode standalone? ' + matchMedia('(display-mode: standalone)').matches);
    var fs = [
      ['serviceWorker',       'serviceWorker' in navigator],
      ['Notification',        typeof Notification !== 'undefined'],
      ['PushManager',         'PushManager' in window],
      ['Permissions API',     'permissions' in navigator],
      ['indexedDB',           'indexedDB' in window],
      ['BackgroundSync',      'SyncManager' in window],
      ['Periodic Sync',       'serviceWorker' in navigator && 'periodicSync' in ServiceWorkerRegistration.prototype],
    ];
    fs.forEach(function (f) { (f[1] ? ok : bad)((f[1] ? '✓ ' : '✗ ') + f[0]); });
    if (typeof Notification !== 'undefined') log('Notification.permission = ' + Notification.permission);
  };

  // 2 — request notification permission ────────────────────────────────
  document.getElementById('b-perm').onclick = async function () {
    dim('--- permission ---');
    if (typeof Notification === 'undefined') return bad('Notification API missing');
    try {
      var p = await Notification.requestPermission();
      (p === 'granted' ? ok : bad)('Notification.requestPermission → ' + p);
    } catch (e) { bad('requestPermission threw: ' + e.message); }
  };

  // 3 — register service worker ────────────────────────────────────────
  var reg = null;
  document.getElementById('b-reg').onclick = async function () {
    dim('--- register sw ---');
    if (!('serviceWorker' in navigator)) return bad('no serviceWorker');
    try {
      reg = await navigator.serviceWorker.register('./sw.js');
      ok('register ok, scope=' + reg.scope);
      await navigator.serviceWorker.ready;
      ok('serviceWorker.ready');
      log('active? ' + !!reg.active + ' installing? ' + !!reg.installing + ' waiting? ' + !!reg.waiting);
    } catch (e) { bad('register failed: ' + e.message); }
  };

  // 4a — fire notification directly from page (uses Notification constructor; some
  // runtimes only honor SW-side showNotification, so 4b is the real test)
  document.getElementById('b-notify-page').onclick = function () {
    dim('--- notify (page) ---');
    if (Notification.permission !== 'granted') return bad('permission=' + Notification.permission);
    try {
      var n = new Notification('fc test (page)', { body: 'fired from page Notification ctor' });
      n.onshow  = function () { ok('Notification.onshow'); };
      n.onerror = function (e) { bad('Notification.onerror'); };
      n.onclick = function () { ok('Notification.onclick'); };
      log('Notification ctor returned, waiting…');
    } catch (e) { bad('Notification ctor threw: ' + e.message); }
  };

  // 4b — fire notification from SW (the canonical PWA path; this is what Stella would
  // need to surface on the HUD when our PWA is NOT in the foreground)
  document.getElementById('b-notify-sw').onclick = function () {
    dim('--- notify (sw) ---');
    if (!navigator.serviceWorker.controller) return bad('no SW controller yet — register first');
    navigator.serviceWorker.addEventListener('message', function once(ev) {
      if (ev.data?.type !== 'shown') return;
      navigator.serviceWorker.removeEventListener('message', once);
      (ev.data.ok ? ok : bad)('sw showNotification → ok=' + ev.data.ok + (ev.data.err ? ' err=' + ev.data.err : ''));
    });
    navigator.serviceWorker.controller.postMessage({
      type: 'show',
      title: 'fc test (sw)',
      body: 'fired from sw.registration.showNotification',
    });
    log('postMessage(show) sent to SW');
  };

  // 5 — subscribe to push (no server yet; just tests if the subscribe call works at all
  // and what endpoint Stella's runtime supports — FCM? Mozilla? something else?)
  document.getElementById('b-sub').onclick = async function () {
    dim('--- subscribe push ---');
    if (!reg) return bad('register sw first');
    if (!('PushManager' in window)) return bad('no PushManager');
    try {
      var existing = await reg.pushManager.getSubscription();
      if (existing) { ok('already subscribed: ' + existing.endpoint); return; }
      // Pull VAPID pub from the relay if available; fall back to a placeholder so subscribe still
      // exercises whether Stella's runtime can reach a push service at all.
      var VAPID_PUB = 'BHWaJI9hY1ZqQYJOC0o5HpVxR4QnaWlcTzqrvvB-pAW5KQzPmtZBKqBxr1z5DT7XQCpO7VlhPe1RyrJV5n1aJxw';
      try {
        var v = await fetch('/face-chat/push/vapid');
        if (v.ok) { VAPID_PUB = (await v.json()).publicKey; log('vapid: live key from relay'); }
        else { warn('vapid: relay returned ' + v.status + ' — using placeholder'); }
      } catch (e) { warn('vapid: no relay (' + e.message + ') — using placeholder'); }
      function urlBase64ToUint8Array(b64) {
        var pad = '='.repeat((4 - b64.length % 4) % 4);
        var b   = (b64 + pad).replace(/-/g, '+').replace(/_/g, '/');
        var raw = atob(b);
        var arr = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; ++i) arr[i] = raw.charCodeAt(i);
        return arr;
      }
      var sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(VAPID_PUB),
      });
      ok('subscribed: ' + sub.endpoint);
      log('keys: ' + JSON.stringify(sub.toJSON().keys));
      // POST to relay so server can push to this endpoint later. /face-chat/push-test/sub
      // is fine to 404 right now — we only care about whether subscribe succeeded.
      try {
        var resp = await fetch('./sub', { method: 'POST', headers: {'content-type':'application/json'}, body: JSON.stringify(sub) });
        log('POST ./sub → ' + resp.status);
      } catch (e) { dim('POST ./sub failed (expected if no backend yet): ' + e.message); }
    } catch (e) { bad('subscribe threw: ' + e.message); }
  };

  // 6 — ask backend to send a real push (requires server with VAPID privkey + sub)
  document.getElementById('b-push').onclick = async function () {
    dim('--- request server push ---');
    try {
      var resp = await fetch('./push', { method: 'POST', headers: {'content-type':'application/json'},
        body: JSON.stringify({ title: 'fc push (server)', body: 'arrived via Web Push' }) });
      log('POST ./push → ' + resp.status + ' ' + await resp.text());
    } catch (e) { bad('./push request failed: ' + e.message); }
  };

  document.getElementById('b-clear').onclick = function () { logEl.innerHTML = ''; };

  dim('ready — start with detect.');
})();
