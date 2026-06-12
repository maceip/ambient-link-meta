// face-chat-final glasses client. Renders the relay's threads as a WhatsApp-style chat list +
// per-thread message view. All transport is WS; reconnects with backoff and resumes by per-thread
// cursor so a flaky link doesn't lose history.
//
// Lives at /face-chat/ws of whatever host serves this SPA (front the relay with Caddy/nginx).
(function () {
  'use strict';

  var WS_URL = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/face-chat/ws';
  var LS_BUFFERS = 'fcf.buffers.v1';   // { threadId: string }
  var LS_CURSORS = 'fcf.cursors.v1';   // { threadId: int }
  var BUFFER_MAX = 65536;

  // ── DOM ─────────────────────────────────────────────────────────────
  var statusEl   = document.getElementById('status');
  var metaEl     = document.getElementById('meta');
  var threadsUl  = document.getElementById('threads');
  var viewList   = document.getElementById('view-threads');
  var viewChat   = document.getElementById('view-thread');
  var backBtn    = document.getElementById('back');
  var titleEl    = document.getElementById('t-title');
  var subEl      = document.getElementById('t-sub');
  var avatarEl   = document.getElementById('t-avatar');
  var messagesEl = document.getElementById('messages');
  var promptEl   = document.getElementById('prompt');
  var composer   = document.getElementById('composer');

  // ── state ───────────────────────────────────────────────────────────
  // threadsMeta: [{id, label, agent}]. Order preserved server-side.
  // buffers[id]: raw accumulated post-strip text for that thread.
  // cursors[id]: byte cursor for resume.
  // busy[id]:    true while last `thread_busy` is more recent than last `thread_idle`.
  var threadsMeta = [];
  var buffers = loadJSON(LS_BUFFERS) || {};
  var cursors = loadJSON(LS_CURSORS) || {};
  var busy = {};
  var activeThread = null;

  function loadJSON(k){ try { return JSON.parse(localStorage.getItem(k) || 'null'); } catch (e) { return null; } }
  var saveDeb;
  function persist() {
    clearTimeout(saveDeb);
    saveDeb = setTimeout(function () {
      try { localStorage.setItem(LS_BUFFERS, JSON.stringify(buffers)); } catch (e) {}
      try { localStorage.setItem(LS_CURSORS, JSON.stringify(cursors)); } catch (e) {}
    }, 400);
  }

  // ── ANSI strip + bubble split (heuristic for CLI agents like claude/codex) ──
  var ANSI = /\x1b\[[0-9;?]*[ -\/]*[@-~]|\x1b\][^\x07]*\x07|\x1b[=>]|\x1b\([AB012]/g;
  function strip(s) { return (s || '').replace(ANSI, '').replace(/\r/g, ''); }
  function isBox(s)    { return /^[\s│║╭╮╯╰─━╌╍┄┅═╔╗╚╝]+$/.test(s); }
  function isPromptOnly(s) { return /^(>|❯|\$|#)\s*$/.test(s.trim()); }

  function bubbles(text) {
    var lines = strip(text).split('\n');
    var out = [];
    var cur = null;
    function flush() {
      if (!cur) return;
      var t = cur.text.replace(/^\s+|\s+$/g, '');
      if (t) out.push({ role: cur.role, text: t });
      cur = null;
    }
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (/^(>|❯)\s/.test(l)) {
        flush();
        cur = { role: 'user', text: l.replace(/^(>|❯)\s/, '') };
        flush();
        continue;
      }
      if (isBox(l) || isPromptOnly(l)) continue;
      if (!cur || cur.role !== 'assistant') { flush(); cur = { role: 'assistant', text: '' }; }
      cur.text += (cur.text ? '\n' : '') + l;
    }
    flush();
    return out;
  }
  function tailPreview(text, max) {
    var bs = bubbles(text);
    if (!bs.length) return '';
    var last = bs[bs.length - 1];
    var s = (last.role === 'user' ? '↗ ' : '') + last.text;
    return s.length > max ? s.slice(0, max - 1) + '…' : s;
  }

  // ── status pill ─────────────────────────────────────────────────────
  function setStatus(state, label) {
    statusEl.className = 'pill ' + state;
    metaEl.textContent = (state === 'on' ? '' : (label || '')).trim();
  }

  // ── thread list render ──────────────────────────────────────────────
  function renderThreadList() {
    threadsUl.innerHTML = '';
    threadsMeta.forEach(function (t) {
      var li = document.createElement('li');
      // Adopt Meta's `list-item` + `focusable` primitives so the spec's focus animations
      // apply automatically. tabindex=0 makes the LI keyboard/D-pad reachable.
      li.className = 'thread-row list-item focusable';
      li.tabIndex = 0;
      li.dataset.thread = t.id;

      var av = document.createElement('div');
      av.className = 'avatar ' + (busy[t.id] ? 'busy' : 'idle');
      av.textContent = (t.label || t.id || '?').charAt(0).toUpperCase();

      var body = document.createElement('div');
      body.className = 'thread-body';
      var name = document.createElement('div');
      name.className = 'name';
      name.appendChild(document.createTextNode(t.label || t.id));
      var dot = document.createElement('span');
      dot.className = 'dot ' + (busy[t.id] ? 'busy' : '');
      name.appendChild(dot);
      var preview = document.createElement('div');
      preview.className = 'preview';
      preview.textContent = tailPreview(buffers[t.id] || '', 120) || '(no messages yet)';
      body.appendChild(name); body.appendChild(preview);

      li.appendChild(av); li.appendChild(body);
      li.addEventListener('click', function () { openThread(t.id); });
      threadsUl.appendChild(li);
    });
  }

  // ── thread view render ──────────────────────────────────────────────
  function renderThread(id) {
    var bs = bubbles(buffers[id] || '');
    messagesEl.innerHTML = '';
    bs.forEach(function (m) {
      var el = document.createElement('div');
      el.className = 'msg ' + m.role;
      el.textContent = m.text;
      messagesEl.appendChild(el);
    });
    messagesEl.scrollTop = messagesEl.scrollHeight;
    var meta = threadsMeta.find(function (x) { return x.id === id; });
    titleEl.textContent  = meta ? (meta.label || id) : id;
    avatarEl.textContent = (meta && (meta.label || meta.id) || '?').charAt(0).toUpperCase();
    avatarEl.className = 'avatar ' + (busy[id] ? 'busy' : 'idle');
    subEl.textContent  = busy[id] ? 'thinking…' : 'online';
  }
  function openThread(id) {
    activeThread = id;
    viewList.classList.add('hidden');
    viewChat.classList.remove('hidden');
    renderThread(id);
    promptEl.focus();
  }
  function closeThread() {
    activeThread = null;
    viewChat.classList.add('hidden');
    viewList.classList.remove('hidden');
    renderThreadList();
  }
  backBtn.addEventListener('click', closeThread);

  composer.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = (promptEl.value || '').trim();
    if (!text || !activeThread || !ws || ws.readyState !== 1) return;
    ws.send(JSON.stringify({ type: 'input', thread: activeThread, text: text, enter: true }));
    promptEl.value = '';
  });

  // ── transport ───────────────────────────────────────────────────────
  var ws = null, backoff = 500;
  function connect() {
    setStatus('warn', 'connecting…');
    try { ws = new WebSocket(WS_URL); }
    catch (e) { setStatus('off', 'WS ctor failed'); scheduleReconnect(); return; }

    ws.onopen = function () {
      backoff = 500;
      setStatus('on', '');
      ws.send(JSON.stringify({ type: 'subscribe', since: cursors }));
    };
    ws.onmessage = function (ev) {
      var msg; try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (msg.type === 'hello') {
        threadsMeta = msg.threads || [];
        threadsMeta.forEach(function (t) {
          if (!(t.id in buffers)) buffers[t.id] = '';
        });
        renderThreadList();
      } else if (msg.type === 'snapshot') {
        buffers[msg.thread] = msg.content || '';
        cursors[msg.thread] = msg.cursor || 0;
        if (activeThread === msg.thread) renderThread(msg.thread); else renderThreadList();
        persist();
      } else if (msg.type === 'append') {
        buffers[msg.thread] = (buffers[msg.thread] || '') + (msg.content || '');
        if (buffers[msg.thread].length > BUFFER_MAX * 2) {
          buffers[msg.thread] = buffers[msg.thread].slice(-BUFFER_MAX);
        }
        cursors[msg.thread] = msg.cursor || 0;
        if (activeThread === msg.thread) renderThread(msg.thread); else renderThreadList();
        persist();
      } else if (msg.type === 'thread_busy') {
        busy[msg.thread] = true;
        if (activeThread === msg.thread) renderThread(msg.thread); else renderThreadList();
      } else if (msg.type === 'thread_idle') {
        busy[msg.thread] = false;
        if (activeThread === msg.thread) renderThread(msg.thread); else renderThreadList();
      }
    };
    ws.onclose = function () { setStatus('off', 'offline'); scheduleReconnect(); };
    ws.onerror = function () {};
  }
  function scheduleReconnect() { setTimeout(connect, backoff); backoff = Math.min(backoff * 2, 10000); }

  renderThreadList();
  setStatus('off', 'starting');
  connect();
})();
