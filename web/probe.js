// Ambient Link — multi-transport spike. Runs every candidate web->phone/relay
// transport in a single pass and reports PASS / FAIL / BLOCKED for each, so the
// "can the glasses reach a local link?" question is answered with data, not guesses.
(function () {
  'use strict';

  var logEl = document.getElementById('log');
  var sumEl = document.getElementById('summary');
  var q = new URLSearchParams(location.search);

  // ── config (all overridable via query string) ────────────────────────────
  var secure   = (location.protocol === 'https:');
  var RELAY_WS = (secure ? 'wss://' : 'ws://') + location.host + '/ambient-link/ws';
  var LAN      = q.get('lan');      // e.g. 192.168.1.50:5181  -> plaintext ws:// to a LAN box
  var PHONE    = q.get('phone');    // e.g. phone.local or a hostname -> wss:// + fetch reachability
  var STUN     = q.get('stun') || 'stun:stun.l.google.com:19302';
  var TURN     = q.get('turn');     // e.g. turn:1.2.3.4:3478?transport=udp
  var TURNUSER = q.get('turnuser');
  var TURNPASS = q.get('turnpass');
  var TIMEOUT  = parseInt(q.get('timeout') || '8000', 10);

  // ── logging ───────────────────────────────────────────────────────────────
  function log(line, cls) {
    var el = document.createElement('div');
    if (cls) el.className = cls;
    el.textContent = new Date().toISOString().slice(11, 23) + '  ' + line;
    logEl.appendChild(el);
    logEl.scrollTop = logEl.scrollHeight;
  }
  var ok = function (s){ log(s,'ok'); }, bad = function (s){ log(s,'bad'); };
  var warn = function (s){ log(s,'warn'); }, dim = function (s){ log(s,'dim'); };
  var info = function (s){ log(s,'info'); };

  var rows = {};
  function row(key, label) {
    var k = document.createElement('div'); k.className = 'k'; k.textContent = label;
    var v = document.createElement('div'); v.id = 'sum-' + key; v.textContent = '…';
    sumEl.appendChild(k); sumEl.appendChild(v); rows[key] = v;
  }
  function set(key, state, detail) {
    var v = rows[key]; if (!v) return;
    v.className = state;
    v.textContent = state.toUpperCase() + (detail ? ' — ' + detail : '');
  }

  window.addEventListener('error', function (e){ bad('window error: ' + (e.message || e.error)); });
  window.addEventListener('unhandledrejection', function (e){ bad('unhandled: ' + (e.reason && e.reason.message || e.reason)); });

  function withTimeout(promise, ms, onTimeoutLabel) {
    return new Promise(function (resolve) {
      var done = false;
      var t = setTimeout(function () { if (!done) { done = true; resolve({ timeout: true, label: onTimeoutLabel }); } }, ms);
      promise.then(function (r) { if (!done) { done = true; clearTimeout(t); resolve(r); } },
                   function (e) { if (!done) { done = true; clearTimeout(t); resolve({ error: e }); } });
    });
  }

  // ── 0 — environment ─────────────────────────────────────────────────────
  function detect() {
    dim('--- environment ---');
    log('UA: ' + navigator.userAgent);
    log('origin: ' + location.origin + '   secureContext=' + window.isSecureContext);
    log('standalone: ' + matchMedia('(display-mode: standalone)').matches);
    var feats = [
      ['WebSocket',         'WebSocket' in window],
      ['fetch',             'fetch' in window],
      ['RTCPeerConnection', 'RTCPeerConnection' in window],
      ['RTCDataChannel',    'RTCDataChannel' in window || 'RTCPeerConnection' in window],
      ['WebTransport',      'WebTransport' in window],
    ];
    feats.forEach(function (f){ (f[1] ? ok : bad)((f[1] ? '\u2713 ' : '\u2717 ') + f[0]); });
    set('env', window.isSecureContext ? 'pass' : 'warn', (secure ? 'https' : 'http') + ', secureCtx=' + window.isSecureContext);
  }

  // ── 1 — control: wss:// to the serving relay (baseline; must pass) ────────
  function testRelayWs() {
    dim('--- relay control: ' + RELAY_WS + ' ---');
    set('relay', 'run');
    return withTimeout(new Promise(function (resolve, reject) {
      var t0 = performance.now(), ws;
      try { ws = new WebSocket(RELAY_WS); }
      catch (e) { return reject(e); }
      ws.onopen = function () {
        var ms = Math.round(performance.now() - t0);
        ok('relay open in ' + ms + 'ms'); try { ws.close(); } catch (e) {}
        resolve({ ms: ms });
      };
      ws.onerror = function () { reject(new Error('ws error')); };
      ws.onclose = function (ev) { if (ev.code !== 1000) reject(new Error('closed code=' + ev.code)); };
    }), TIMEOUT, 'relay').then(function (r) {
      if (r.ms != null) set('relay', 'pass', r.ms + 'ms');
      else if (r.timeout) { warn('relay timeout'); set('relay', 'fail', 'timeout'); }
      else { bad('relay failed: ' + (r.error && r.error.message)); set('relay', 'fail', r.error && r.error.message); }
    });
  }

  // ── 2 — plaintext ws:// to a LAN IP (expected: BLOCKED by mixed content) ──
  function testLanWs() {
    if (!LAN) { dim('--- lan ws:// skipped (pass ?lan=IP:PORT) ---'); set('lan', 'skip', 'no ?lan'); return Promise.resolve(); }
    var url = 'ws://' + LAN + '/ambient-link/ws';
    dim('--- lan plaintext: ' + url + ' ---');
    set('lan', 'run');
    return withTimeout(new Promise(function (resolve, reject) {
      var t0 = performance.now(), ws;
      try { ws = new WebSocket(url); }                       // https page -> ws:// usually throws here
      catch (e) { return reject({ blocked: true, e: e }); }
      ws.onopen = function () { var ms = Math.round(performance.now() - t0); try { ws.close(); } catch (e) {} resolve({ ms: ms }); };
      ws.onerror = function () { reject(new Error('ws error (mixed-content or unreachable)')); };
      ws.onclose = function (ev) { if (ev.code !== 1000) reject(new Error('closed code=' + ev.code)); };
    }), TIMEOUT, 'lan').then(function (r) {
      if (r.ms != null) { ok('LAN ws:// OPEN in ' + r.ms + 'ms — runtime allowed it!'); set('lan', 'pass', r.ms + 'ms (allowed!)'); }
      else if (r.error && r.error.blocked) { warn('LAN ws:// blocked synchronously (mixed content)'); set('lan', 'blocked', 'mixed-content'); }
      else if (r.timeout) { warn('LAN ws:// timeout (unreachable or silently blocked)'); set('lan', 'blocked', 'timeout/unreachable'); }
      else { warn('LAN ws:// rejected: ' + (r.error && r.error.message)); set('lan', 'blocked', r.error && r.error.message); }
    });
  }

  // ── 3 — wss:// to a phone host (expected: cert failure for private IP) ────
  function testPhoneWs() {
    if (!PHONE) { dim('--- phone wss:// skipped (pass ?phone=HOST) ---'); set('phonews', 'skip', 'no ?phone'); return Promise.resolve(); }
    var url = 'wss://' + PHONE + '/ambient-link/ws';
    dim('--- phone wss: ' + url + ' ---');
    set('phonews', 'run');
    return withTimeout(new Promise(function (resolve, reject) {
      var t0 = performance.now(), ws;
      try { ws = new WebSocket(url); } catch (e) { return reject(e); }
      ws.onopen = function () { var ms = Math.round(performance.now() - t0); try { ws.close(); } catch (e) {} resolve({ ms: ms }); };
      ws.onerror = function () { reject(new Error('ws error (TLS/cert or unreachable)')); };
      ws.onclose = function (ev) { if (ev.code !== 1000) reject(new Error('closed code=' + ev.code)); };
    }), TIMEOUT, 'phonews').then(function (r) {
      if (r.ms != null) { ok('phone wss:// OPEN in ' + r.ms + 'ms'); set('phonews', 'pass', r.ms + 'ms'); }
      else if (r.timeout) { warn('phone wss:// timeout'); set('phonews', 'fail', 'timeout'); }
      else { warn('phone wss:// failed: ' + (r.error && r.error.message)); set('phonews', 'fail', r.error && r.error.message); }
    });
  }

  // ── 4 — fetch() reachability to the phone (opaque, just connection test) ──
  function testPhoneFetch() {
    if (!PHONE) { set('phonefetch', 'skip', 'no ?phone'); return Promise.resolve(); }
    var url = 'https://' + PHONE + '/healthz';
    dim('--- phone fetch: ' + url + ' (no-cors) ---');
    set('phonefetch', 'run');
    return withTimeout(fetch(url, { mode: 'no-cors', cache: 'no-store' }).then(function () { return { reached: true }; }),
      TIMEOUT, 'phonefetch').then(function (r) {
      if (r.reached) { ok('phone fetch resolved (opaque) — host reachable'); set('phonefetch', 'pass', 'reachable'); }
      else if (r.timeout) { warn('phone fetch timeout'); set('phonefetch', 'fail', 'timeout'); }
      else { warn('phone fetch failed: ' + (r.error && r.error.message)); set('phonefetch', 'fail', r.error && r.error.message); }
    });
  }

  // ── 5 — WebRTC: does it exist, and what ICE candidate types appear? ───────
  // This is THE open question: host candidates => raw local IP path possible;
  // srflx => STUN egress works; relay => TURN works; mdns => Chrome-obscured host.
  function testWebRTC() {
    set('webrtc', 'run');
    if (!('RTCPeerConnection' in window)) { bad('RTCPeerConnection unsupported'); set('webrtc', 'fail', 'no RTCPeerConnection'); return Promise.resolve(); }
    var iceServers = [{ urls: STUN }];
    if (TURN) iceServers.push({ urls: TURN, username: TURNUSER || '', credential: TURNPASS || '' });
    dim('--- webrtc: iceServers=' + JSON.stringify(iceServers.map(function (s){ return s.urls; })) + ' ---');

    var counts = { host: 0, srflx: 0, prflx: 0, relay: 0, mdns: 0, total: 0 };
    var pc;
    try { pc = new RTCPeerConnection({ iceServers: iceServers }); }
    catch (e) { bad('RTCPeerConnection ctor threw: ' + e.message); set('webrtc', 'fail', e.message); return Promise.resolve(); }

    return withTimeout(new Promise(function (resolve) {
      pc.onicegatheringstatechange = function () { dim('iceGatheringState=' + pc.iceGatheringState); if (pc.iceGatheringState === 'complete') resolve(counts); };
      pc.onicecandidate = function (e) {
        if (!e.candidate) { resolve(counts); return; }
        var c = e.candidate.candidate || '';
        var m = /(?:^| )typ (host|srflx|prflx|relay)/.exec(c);
        var typ = m ? m[1] : '?';
        counts.total++;
        if (counts[typ] != null) counts[typ]++;
        var addr = e.candidate.address || (c.split(' ')[4] || '');
        if (addr && /\.local$/i.test(addr)) counts.mdns++;
        info('cand typ=' + typ + ' addr=' + addr + (e.candidate.protocol ? ' ' + e.candidate.protocol : ''));
      };
      try {
        var dc = pc.createDataChannel('probe');
        dc.onopen = function () { ok('data channel opened (loopback offer)'); };
        pc.createOffer().then(function (o) { return pc.setLocalDescription(o); })
          .then(function () { dim('local description set; gathering…'); })
          .catch(function (e) { bad('createOffer/setLocalDescription failed: ' + e.message); resolve(counts); });
      } catch (e) { bad('createDataChannel failed: ' + e.message); resolve(counts); }
    }), TIMEOUT, 'webrtc').then(function (r) {
      try { pc.close(); } catch (e) {}
      var c = (r && r.host != null) ? r : counts;
      var detail = 'host=' + c.host + ' srflx=' + c.srflx + ' relay=' + c.relay + ' mdns=' + c.mdns + (counts.total === 0 ? ' (none)' : '');
      if (counts.total === 0) { warn('WebRTC present but gathered NO candidates'); set('webrtc', 'fail', 'no candidates'); }
      else {
        ok('WebRTC candidates: ' + detail);
        // host (non-mdns) or relay means a usable peer path exists.
        var localPath = (c.host - c.mdns) > 0;
        var state = (c.srflx > 0 || c.host > 0 || c.relay > 0) ? 'pass' : 'warn';
        set('webrtc', state, detail + (localPath ? '  [local host IP exposed]' : (c.mdns > 0 ? '  [host behind mdns]' : '')));
      }
    });
  }

  // ── runner ────────────────────────────────────────────────────────────────
  async function run() {
    logEl.innerHTML = '';
    dim('=== ambient transport probe — one pass @ ' + new Date().toISOString() + ' ===');
    detect();
    await testRelayWs();
    await testLanWs();
    await testPhoneWs();
    await testPhoneFetch();
    await testWebRTC();
    dim('=== done — read the summary above ===');
  }

  // summary rows in display order
  ['env','relay','lan','phonews','phonefetch','webrtc'].forEach(function (k) {
    row(k, ({ env:'environment', relay:'relay wss (control)', lan:'LAN ws:// (local)',
      phonews:'phone wss:// (local)', phonefetch:'phone fetch', webrtc:'WebRTC / ICE' })[k]);
  });

  document.getElementById('run').onclick = run;
  document.getElementById('clear').onclick = function () { logEl.innerHTML = ''; };
  document.getElementById('copy').onclick = function () {
    var txt = logEl.textContent;
    if (navigator.clipboard) navigator.clipboard.writeText(txt).then(function () { dim('log copied'); }, function () { dim('copy failed'); });
  };

  run(); // auto-run one pass on load
})();
