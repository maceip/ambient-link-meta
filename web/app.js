// Ambient Link web companion — session list + compose. Glasses HUD is native relay only.
(function () {
  'use strict';

  var CS = window.AmbientChipSet;
  var WS_URL = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/face-chat/ws';

  var statusEl   = document.getElementById('status');
  var threadsUl  = document.getElementById('threads');
  var emptyHint  = document.getElementById('empty-hint');
  var viewList   = document.getElementById('view-threads');
  var viewThread = document.getElementById('view-thread');
  var viewNew    = document.getElementById('view-new');
  var backBtn    = document.getElementById('back');
  var titleEl    = document.getElementById('t-title');
  var wMeta      = document.getElementById('w-meta');
  var wCard      = document.getElementById('w-card');
  var composer   = document.getElementById('composer');
  var promptEl   = document.getElementById('prompt');
  var dictateBtn = document.getElementById('dictate');
  var btnNew     = document.getElementById('btn-new');
  var newBack    = document.getElementById('new-back');
  var agentPick  = document.getElementById('agent-pick');
  var newCwd     = document.getElementById('new-cwd');
  var newPrompt  = document.getElementById('new-prompt');
  var newStart   = document.getElementById('new-start');
  var toastEl    = document.getElementById('toast');

  var threads = {};
  var threadOrder = [];
  var activeThread = null;
  var pickedAgent = 'cursor';
  var ws = null;
  var backoff = 500;
  var toastTimer;
  var dictRec = null;
  var pendingDeepLink = null;

  function parseDeepLink() {
    var p = new URLSearchParams(location.search);
    var session = p.get('session');
    if (!session) return null;
    return { session: session, compose: p.get('compose') === '1' };
  }

  function setUrlForSession(id, compose) {
    var url = new URL(location.href);
    if (id) {
      url.searchParams.set('session', id);
      if (compose) url.searchParams.set('compose', '1');
      else url.searchParams.delete('compose');
    } else {
      url.searchParams.delete('session');
      url.searchParams.delete('compose');
    }
    history.replaceState({}, '', url.pathname + url.search);
  }

  function sendDictate(type, thread, text) {
    if (!ws || ws.readyState !== 1 || !thread) return;
    var o = { type: type, thread: thread, source: 'web' };
    if (text != null && text !== '') o.text = text;
    ws.send(JSON.stringify(o));
  }

  function stopDictRec(abortThread) {
    if (!dictRec) return;
    try { dictRec.stop(); } catch (e) {}
    dictRec = null;
    if (abortThread) sendDictate('dictate_abort', abortThread);
  }

  function threadRow(id) {
    if (!threads[id]) {
      threads[id] = { id: id, label: id, agent: 'generic', busy: false, ended: false, yank: null };
      threadOrder.push(id);
    }
    return threads[id];
  }

  function upsertHelloRow(t) {
    var row = threadRow(t.id);
    row.label = t.label || t.id;
    row.agent = t.agent || row.agent || 'generic';
    row.ended = false;
  }

  function setStatus(state) { statusEl.className = 'pill ' + state; }

  function showToast(msg, kind) {
    toastEl.textContent = msg;
    toastEl.className = 'toast visible' + (kind ? ' ' + kind : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.className = 'toast'; }, 2800);
  }

  function showView(which) {
    viewList.classList.toggle('hidden', which !== 'list');
    viewThread.classList.toggle('hidden', which !== 'thread');
    viewNew.classList.toggle('hidden', which !== 'new');
  }

  function liveThreads() {
    return threadOrder.map(function (id) { return threads[id]; }).filter(function (t) { return t && !t.ended; });
  }

  function previewText(t) {
    if (t.busy) return 'thinking…';
    if (t.yank) return truncate(CS.bodyText(t.yank), 100);
    return 'waiting for agent…';
  }

  function statusBadge(t) {
    if (t.busy) return 'busy';
    if (t.yank && t.yank.awaiting === CS.Awaiting.PERMISSION) return 'permission';
    if (t.yank && t.yank.awaiting === CS.Awaiting.QUESTION) return 'question';
    if (t.yank && t.yank.awaiting === CS.Awaiting.DONE) return 'done';
    if (t.yank) return 'idle';
    return 'online';
  }

  function truncate(s, n) {
    if (!s) return '';
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function renderThreadList() {
    var live = liveThreads();
    threadsUl.innerHTML = '';
    emptyHint.classList.toggle('hidden', live.length > 0);
    live.forEach(function (t) {
      var li = document.createElement('li');
      li.className = 'thread-row list-item focusable';
      li.tabIndex = 0;
      var av = document.createElement('div');
      av.className = 'avatar ' + statusBadge(t);
      av.textContent = (t.label || t.id || '?').charAt(0).toUpperCase();
      var body = document.createElement('div');
      body.className = 'thread-body';
      var name = document.createElement('div');
      name.className = 'name';
      name.textContent = t.label || t.id;
      var badge = document.createElement('span');
      badge.className = 'status-tag ' + statusBadge(t);
      badge.textContent = t.busy ? 'busy' : (t.yank ? (
        t.yank.awaiting === CS.Awaiting.PERMISSION ? 'approval' :
        t.yank.awaiting === CS.Awaiting.QUESTION ? 'question' : 'done'
      ) : 'live');
      name.appendChild(badge);
      var preview = document.createElement('div');
      preview.className = 'preview body-preview';
      preview.textContent = previewText(t);
      body.appendChild(name);
      body.appendChild(preview);
      li.appendChild(av);
      li.appendChild(body);
      li.addEventListener('click', function () { openThread(t.id, true); });
      threadsUl.appendChild(li);
    });
  }

  function renderCompose() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) return;
    titleEl.textContent = t.label || t.id;
    if (t.busy) {
      wMeta.textContent = 'thinking…';
      wCard.textContent = 'agent is working — you can still queue a message below.';
      return;
    }
    if (!t.yank) {
      wMeta.textContent = t.label + ' · online';
      wCard.textContent = 'no pause card yet — type a message to steer the agent.';
      return;
    }
    wMeta.textContent = CS.metaLine(t.yank);
    wCard.textContent = CS.bodyText(t.yank);
  }

  function startDictate() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) { showToast('open a session first', 'error'); return; }
    var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) {
      showToast('dictate needs Chrome/Safari — type instead', 'error');
      promptEl.focus();
      return;
    }
    stopDictRec(null);
    var rec = new SR();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = 'en-US';
    sendDictate('dictate_begin', t.id);
    showToast('listening…', 'success');
    promptEl.placeholder = 'listening…';
    rec.onresult = function (e) {
      var interim = '';
      var finalText = '';
      for (var i = e.resultIndex; i < e.results.length; i++) {
        var r = e.results[i];
        if (r.isFinal) finalText += r[0].transcript;
        else interim += r[0].transcript;
      }
      if (interim.trim()) {
        promptEl.value = interim.trim();
        sendDictate('dictate_partial', t.id, interim.trim());
      }
      if (finalText.trim()) {
        var text = finalText.trim();
        sendDictate('dictate_commit', t.id, text);
        if (t.yank) t.yank = Object.assign({}, t.yank, { lastUserInput: text });
        promptEl.value = '';
        promptEl.placeholder = 'type your message…';
        showToast('sent', 'success');
        renderCompose();
        renderThreadList();
        dictRec = null;
      }
    };
    rec.onerror = function () {
      sendDictate('dictate_abort', t.id);
      showToast('dictation failed', 'error');
      promptEl.placeholder = 'type your message…';
      dictRec = null;
    };
    rec.onend = function () { dictRec = null; };
    dictRec = rec;
    rec.start();
  }

  function sendPrompt(thread, text) {
    sendInput(thread, text, true);
    var row = threads[thread];
    if (row && row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: text });
    promptEl.value = '';
    showToast('sent', 'success');
    renderCompose();
    renderThreadList();
  }

  function openThread(id, compose) {
    activeThread = id;
    setUrlForSession(id, !!compose);
    showView('thread');
    renderCompose();
    if (compose) {
      setTimeout(function () { promptEl.focus(); }, 50);
    }
  }

  function closeThreadView() {
    stopDictRec(activeThread);
    activeThread = null;
    setUrlForSession(null, false);
    showView('list');
    renderThreadList();
  }

  function tryPendingDeepLink() {
    if (!pendingDeepLink || !ws || ws.readyState !== 1) return;
    var dl = pendingDeepLink;
    pendingDeepLink = null;
    threadRow(dl.session);
    openThread(dl.session, dl.compose);
  }

  function openNew() { showView('new'); newPrompt.focus(); }

  function pickAgent(agent) {
    pickedAgent = agent;
    var opts = agentPick.querySelectorAll('.agent-opt');
    for (var i = 0; i < opts.length; i++) {
      opts[i].classList.toggle('primary', opts[i].dataset.agent === agent);
    }
  }

  function findThreadForAgent(agent) {
    var live = liveThreads();
    for (var i = 0; i < live.length; i++) {
      if ((live[i].agent || '').toLowerCase() === agent.toLowerCase()) return live[i];
    }
    return null;
  }

  function threadIdFor(agent, cwd) {
    var payload = agent + '::' + (cwd || '');
    return crypto.subtle.digest('SHA-256', new TextEncoder().encode(payload)).then(function (buf) {
      var hex = Array.from(new Uint8Array(buf)).map(function (b) {
        return ('0' + b.toString(16)).slice(-2);
      }).join('');
      return agent + '-' + hex.slice(0, 10);
    });
  }

  function ingest(ev) {
    return fetch('/face-chat/ingest', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(ev),
    });
  }

  function startNewThread() {
    var text = (newPrompt.value || '').trim();
    if (!text) { showToast('enter a first message', 'error'); return; }
    if (!ws || ws.readyState !== 1) { showToast('not connected', 'error'); return; }
    var cwd = (newCwd.value || '').trim();
    var existing = findThreadForAgent(pickedAgent);
    if (existing) {
      sendPrompt(existing.id, text);
      newPrompt.value = '';
      openThread(existing.id, true);
      return;
    }
    if (pickedAgent === 'cursor') {
      threadIdFor('cursor', cwd).then(function (sessionId) {
        var base = { session_id: sessionId, agent: 'cursor', cwd: cwd || '.' };
        ingest(Object.assign({}, base, { event_type: 'session_start' }))
          .then(function () {
            return ingest(Object.assign({}, base, {
              event_type: 'user_prompt',
              payload: { message: text },
            }));
          })
          .then(function () {
            var row = threadRow(sessionId);
            row.label = cwd ? ('cursor: ' + cwd.split('/').pop()) : sessionId;
            row.agent = 'cursor';
            newPrompt.value = '';
            newCwd.value = '';
            renderThreadList();
            openThread(sessionId, true);
          })
          .catch(function () { showToast('ingest failed', 'error'); });
      });
      return;
    }
    ws.send(JSON.stringify({ type: 'new_thread', agent: pickedAgent, cwd: cwd || undefined, text: text }));
    newPrompt.value = '';
    newCwd.value = '';
    showView('list');
  }

  function sendInput(thread, text, enter) {
    if (!ws || ws.readyState !== 1) return;
    ws.send(JSON.stringify({ type: 'input', thread: thread, text: text, enter: enter !== false }));
  }

  function applyYank(msg) {
    var yank = CS.parseYank(msg);
    var row = threadRow(yank.thread);
    if (msg.label) row.label = msg.label;
    if (msg.agent) row.agent = msg.agent;
    row.busy = false;
    row.ended = false;
    row.yank = yank;
  }

  function connect() {
    setStatus('warn');
    try { ws = new WebSocket(WS_URL); }
    catch (e) { setStatus('off'); scheduleReconnect(); return; }

    ws.onopen = function () {
      backoff = 500;
      setStatus('on');
      ws.send(JSON.stringify({ type: 'subscribe', since: {} }));
      tryPendingDeepLink();
    };

    ws.onmessage = function (ev) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }

      if (msg.type === 'hello') {
        (msg.threads || []).forEach(upsertHelloRow);
        renderThreadList();
        tryPendingDeepLink();
        if (activeThread) renderCompose();
        return;
      }

      if (msg.type === 'thread_started') {
        var started = threadRow(msg.thread);
        if (msg.label) started.label = msg.label;
        if (msg.agent) started.agent = msg.agent;
        started.busy = true;
      } else if (msg.type === 'thread_ended') {
        var ended = threadRow(msg.thread);
        ended.ended = true;
        ended.busy = false;
        ended.yank = null;
      } else if (msg.type === 'thread_busy') {
        threadRow(msg.thread).busy = true;
      } else if (msg.type === 'thread_idle' || msg.type === 'hud_yank') {
        applyYank(msg);
      } else if (msg.type === 'dictate_partial' && activeThread === msg.thread && msg.text) {
        promptEl.value = msg.text;
      } else if (msg.type === 'dictate_end' && activeThread === msg.thread) {
        promptEl.placeholder = 'type your message…';
        if (msg.text) {
          var row = threads[msg.thread];
          if (row && row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: msg.text });
        }
      } else {
        return;
      }

      if (activeThread === msg.thread) renderCompose();
      else renderThreadList();
    };

    ws.onclose = function () { setStatus('off'); scheduleReconnect(); };
    ws.onerror = function () {};
  }

  function scheduleReconnect() {
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 10000);
  }

  backBtn.addEventListener('click', closeThreadView);
  btnNew.addEventListener('click', openNew);
  newBack.addEventListener('click', function () { showView('list'); });
  newStart.addEventListener('click', startNewThread);
  agentPick.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-agent]');
    if (btn) pickAgent(btn.dataset.agent);
  });
  composer.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = (promptEl.value || '').trim();
    if (!text || !activeThread) return;
    sendPrompt(activeThread, text);
  });
  dictateBtn.addEventListener('click', startDictate);

  pendingDeepLink = parseDeepLink();
  pickAgent('cursor');
  renderThreadList();
  setStatus('off');
  connect();
})();
