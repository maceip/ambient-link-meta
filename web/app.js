// Ambient Link web companion — session list + compose. Glasses HUD is native relay only.
(function () {
  'use strict';

  var CS = window.AmbientChipSet;
  var WS_URL = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ambient-link/ws';

  var connDot    = document.getElementById('conn-dot');
  var threadsUl  = document.getElementById('threads');
  var emptyHint  = document.getElementById('empty-hint');
  var viewList   = document.getElementById('view-threads');
  var viewThread = document.getElementById('view-thread');
  var viewNew    = document.getElementById('view-new');
  var backBtn    = document.getElementById('back');
  var titleEl    = document.getElementById('t-title');
  var wMeta      = document.getElementById('w-meta');
  var wCard      = document.getElementById('w-card');
  var wChips     = document.getElementById('w-chips');
  var composer   = document.getElementById('composer');
  var promptEl   = document.getElementById('prompt');
  var dictateBtn = document.getElementById('dictate');
  var sendBtn     = document.getElementById('send');
  var shelf      = document.getElementById('shelf');
  var newTitle   = document.getElementById('new-title');
  var newTitleIcon = document.getElementById('new-title-icon');
  var newCwd     = document.getElementById('new-cwd');
  var newPrompt  = document.getElementById('new-prompt');
  var newStart   = document.getElementById('new-start');
  var toastEl    = document.getElementById('toast');
  var hostPanel  = document.getElementById('host-panel');
  var relayBadge = document.getElementById('relay-badge');
  var btnPull    = document.getElementById('btn-pull');
  var newDictate = document.getElementById('new-dictate');

  var threads = {};
  var threadOrder = [];
  var activeThread = null;
  var pickedAgent = 'cursor';
  var ws = null;
  var backoff = 500;
  var toastTimer;
  var dictRec = null;
  var pendingDeepLink = null;
  var hostInfo = { relayDebug: false, journal: 0, now: 0, delivery: {}, defaultCwd: '' };
  var hostPanelOpen = false;
  var pendingInputs = loadPendingInputs();
  var deliveryStates = loadDeliveryStates();

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
      threads[id] = {
        id: id,
        label: id,
        agent: 'generic',
        busy: false,
        ended: false,
        yank: null,
        lastEventAt: 0,
        deliverable: false,
      };
      threadOrder.push(id);
    }
    return threads[id];
  }

  function upsertHelloRow(t) {
    var row = threadRow(t.id);
    row.label = t.label || t.id;
    row.agent = t.agent || row.agent || 'generic';
    row.ended = false;
    row.lastEventAt = row.lastEventAt || clockNow();
  }

  function setStatus(state) {
    if (!connDot) return;
    connDot.classList.remove('on', 'off', 'warn');
    connDot.classList.add(state);
    connDot.setAttribute('title', 'relay ' + state);
    connDot.setAttribute('aria-label', 'relay ' + state);
  }

  function sessionDeliverable(sessionId) {
    return !!(sessionId && hostInfo.delivery[sessionId]);
  }

  function renderHostPanel() {
    if (!hostPanelOpen) {
      hostPanel.classList.add('hidden');
      return;
    }
    var lines = [
      'relay_debug: ' + (hostInfo.relayDebug ? 'on' : 'off'),
      'journal: ' + (hostInfo.journal || 0),
      'live threads: ' + liveThreadCount(),
    ];
    hostPanel.textContent = lines.join('\n');
    hostPanel.classList.remove('hidden');
  }

  function toggleHostPanel() {
    hostPanelOpen = !hostPanelOpen;
    renderHostPanel();
  }

  function pullCard(thread) {
    if (!thread) return;
    if (!ws || ws.readyState !== 1) {
      showToast('not connected', 'error');
      return;
    }
    ws.send(JSON.stringify({ type: 'hud_yank', thread: thread }));
    showToast('pulling card…', 'success');
  }

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

  function visibleThreads() {
    return threadOrder
      .map(function (id) { return threads[id]; })
      .filter(function (t) { return !!t; })
      .sort(function (a, b) { return (b.lastEventAt || 0) - (a.lastEventAt || 0); });
  }

  function liveThreadCount() {
    return visibleThreads().filter(function (t) { return !t.ended; }).length;
  }

  function displayLabel(t) {
    var label = ((t && t.label) || '').trim();
    if (!label || /:\s*$/.test(label)) label = ((t && t.agent) || 'session').trim();
    return label || 'session';
  }

  function relativeTime(ms) {
    if (!ms) return '';
    var now = clockNow();
    var delta = Math.max(0, now - ms);
    if (delta > 30 * 24 * 60 * 60 * 1000) return '';
    var sec = Math.floor(delta / 1000);
    if (sec < 45) return 'now';
    var min = Math.floor(sec / 60);
    if (min < 60) return min + 'm';
    var hr = Math.floor(min / 60);
    if (hr < 24) return hr + 'h';
    return Math.floor(hr / 24) + 'd';
  }

  function clockNow() {
    return hostInfo.now || Date.now();
  }

  function previewText(t) {
    if (t.ended) return 'ended';
    if (t.busy) return 'thinking…';
    if (t.yank) return truncate(CS.bodyText(t.yank), 100);
    return '';
  }

  function statusBadge(t) {
    if (t.ended) return 'ended';
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

  var AGENT_ICONS = {
    cursor: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M22.106 5.68L12.5.135a.998.998 0 00-.998 0L1.893 5.68a.84.84 0 00-.419.726v11.186c0 .3.16.577.42.727l9.607 5.547a.999.999 0 00.998 0l9.608-5.547a.84.84 0 00.42-.727V6.407a.84.84 0 00-.42-.726zm-.603 1.176L12.228 22.92c-.063.108-.228.064-.228-.061V12.34a.59.59 0 00-.295-.51l-9.11-5.26c-.107-.062-.063-.228.062-.228h18.55c.264 0 .428.286.296.514z"/></svg>',
    claude: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M4.709 15.955l4.72-2.647.08-.23-.08-.128H9.2l-.79-.048-2.698-.073-2.339-.097-2.266-.122-.571-.121L0 11.784l.055-.352.48-.321.686.06 1.52.103 2.278.158 1.652.097 2.449.255h.389l.055-.157-.134-.098-.103-.097-2.358-1.596-2.552-1.688-1.336-.972-.724-.491-.364-.462-.158-1.008.656-.722.881.06.225.061.893.686 1.908 1.476 2.491 1.833.365.304.145-.103.019-.073-.164-.274-1.355-2.446-1.446-2.49-.644-1.032-.17-.619a2.97 2.97 0 01-.104-.729L6.283.134 6.696 0l.996.134.42.364.62 1.414 1.002 2.229 1.555 3.03.456.898.243.832.091.255h.158V9.01l.128-1.706.237-2.095.23-2.695.08-.76.376-.91.747-.492.584.28.48.685-.067.444-.286 1.851-.559 2.903-.364 1.942h.212l.243-.242.985-1.306 1.652-2.064.73-.82.85-.904.547-.431h1.033l.76 1.129-.34 1.166-1.064 1.347-.881 1.142-1.264 1.7-.79 1.36.073.11.188-.02 2.856-.606 1.543-.28 1.841-.315.833.388.091.395-.328.807-1.969.486-2.309.462-3.439.813-.042.03.049.061 1.549.146.662.036h1.622l3.02.225.79.522.474.638-.079.485-1.215.62-1.64-.389-3.829-.91-1.312-.329h-.182v.11l1.093 1.068 2.006 1.81 2.509 2.33.127.578-.322.455-.34-.049-2.205-1.657-.851-.747-1.926-1.62h-.128v.17l.444.649 2.345 3.521.122 1.08-.17.353-.608.213-.668-.122-1.374-1.925-1.415-2.167-1.143-1.943-.14.08-.674 7.254-.316.37-.729.28-.607-.461-.322-.747.322-1.476.389-1.924.315-1.53.286-1.9.17-.632-.012-.042-.14.018-1.434 1.967-2.18 2.945-1.726 1.845-.414.164-.717-.37.067-.662.401-.589 2.388-3.036 1.44-1.882.93-1.086-.006-.158h-.055L4.132 18.56l-1.13.146-.487-.456.061-.746.231-.243 1.908-1.312-.006.006z"/></svg>',
    codex: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M9.205 8.658v-2.26c0-.19.072-.333.238-.428l4.543-2.616c.619-.357 1.356-.523 2.117-.523 2.854 0 4.662 2.212 4.662 4.566 0 .167 0 .357-.024.547l-4.71-2.759a.797.797 0 00-.856 0l-5.97 3.473zm10.609 8.8V12.06c0-.333-.143-.57-.429-.737l-5.97-3.473 1.95-1.118a.433.433 0 01.476 0l4.543 2.617c1.309.76 2.189 2.378 2.189 3.948 0 1.808-1.07 3.473-2.76 4.163zM7.802 12.703l-1.95-1.142c-.167-.095-.239-.238-.239-.428V5.899c0-2.545 1.95-4.472 4.591-4.472 1 0 1.927.333 2.712.928L8.23 5.067c-.285.166-.428.404-.428.737v6.898zM12 15.128l-2.795-1.57v-3.33L12 8.658l2.795 1.57v3.33L12 15.128zm1.796 7.23c-1 0-1.927-.332-2.712-.927l4.686-2.712c.285-.166.428-.404.428-.737v-6.898l1.974 1.142c.167.095.238.238.238.428v5.233c0 2.545-1.974 4.472-4.614 4.472zm-5.637-5.303l-4.544-2.617c-1.308-.761-2.188-2.378-2.188-3.948A4.482 4.482 0 014.21 6.327v5.423c0 .333.143.571.428.738l5.947 3.449-1.95 1.118a.432.432 0 01-.476 0zm-.262 3.9c-2.688 0-4.662-2.021-4.662-4.519 0-.19.024-.38.047-.57l4.686 2.71c.286.167.571.167.856 0l5.97-3.448v2.26c0 .19-.07.333-.237.428l-4.543 2.616c-.619.357-1.356.523-2.117.523zm5.899 2.83a5.947 5.947 0 005.827-4.756C22.287 18.339 24 15.84 24 13.296c0-1.665-.713-3.282-1.998-4.448.119-.5.19-.999.19-1.498 0-3.401-2.759-5.947-5.946-5.947-.642 0-1.26.095-1.88.31A5.962 5.962 0 0010.205 0a5.947 5.947 0 00-5.827 4.757C1.713 5.447 0 7.945 0 10.49c0 1.666.713 3.283 1.998 4.448-.119.5-.19 1-.19 1.499 0 3.401 2.759 5.946 5.946 5.946.642 0 1.26-.095 1.88-.309a5.96 5.96 0 004.162 1.713z"/></svg>'
  };

  function agentClass(agent) {
    var a = (agent || '').toLowerCase();
    if (a === 'cursor') return 'cursor';
    if (a === 'claude') return 'claude';
    if (a === 'codex' || a === 'openai') return 'codex';
    return 'generic';
  }

  function agentIcon(agent) {
    return AGENT_ICONS[agentClass(agent)] || '';
  }

  function renderThreadList() {
    var live = visibleThreads();
    // Reverse order: newest at the bottom (WhatsApp/chat-style).
    live.sort(function (a, b) { return (a.lastEventAt || 0) - (b.lastEventAt || 0); });
    threadsUl.innerHTML = '';
    emptyHint.classList.toggle('hidden', live.length > 0);
    live.forEach(function (t) {
      var li = document.createElement('button');
      li.type = 'button';
      var ac = agentClass(t.agent);
      li.className = 'thread-row list-item focusable agent-' + ac + ' ' + statusBadge(t);
      li.setAttribute('role', 'listitem');
      li.setAttribute('aria-label', displayLabel(t) + ', ' + (t.agent || 'agent') + ', ' + statusBadge(t));
      var av = document.createElement('div');
      av.className = 'avatar agent-' + ac + ' ' + statusBadge(t);
      var icon = agentIcon(t.agent);
      if (icon) av.innerHTML = icon;
      else av.textContent = displayLabel(t).charAt(0).toUpperCase();
      var body = document.createElement('div');
      body.className = 'thread-body';
      var name = document.createElement('div');
      name.className = 'name';
      var label = document.createElement('span');
      label.className = 'thread-label';
      label.textContent = displayLabel(t);
      name.appendChild(label);
      var badge = document.createElement('span');
      badge.className = 'status-tag ' + statusBadge(t);
      badge.textContent = t.ended ? 'ended' : (t.busy ? 'busy' : (t.yank ? (
        t.yank.awaiting === CS.Awaiting.PERMISSION ? 'approval' :
        t.yank.awaiting === CS.Awaiting.QUESTION ? 'question' : 'done'
      ) : 'live'));
      name.appendChild(badge);
      var meta = document.createElement('div');
      meta.className = 'thread-meta';
      meta.textContent = t.agent || 'agent';
      var preview = document.createElement('div');
      preview.className = 'preview body-preview';
      preview.textContent = previewText(t);
      body.appendChild(name);
      body.appendChild(meta);
      if (preview.textContent) body.appendChild(preview);
      var time = document.createElement('div');
      time.className = 'thread-time';
      time.textContent = relativeTime(t.lastEventAt);
      li.appendChild(av);
      li.appendChild(body);
      li.appendChild(time);
      li.addEventListener('click', function () { openThread(t.id, true); });
      li.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          openThread(t.id, true);
        }
      });
      threadsUl.appendChild(li);
    });
    threadsUl.scrollTop = threadsUl.scrollHeight; // keep newest (bottom) in view
  }

  function renderChips(yank, agent) {
    wChips.innerHTML = '';
    if (!yank) return;
    CS.forYank(yank).forEach(function (c) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'chip focusable chip-' + CS.chipStyle(c.kind);
      btn.textContent = c.label;
      btn.addEventListener('click', function () {
        if (!activeThread) return;
        if (c.kind === 'dictate') { startDictate(); return; }
        if (c.kind === 'modify') {
          showToast('use composer below to modify', 'success');
          promptEl.focus();
          return;
        }
        if (c.text) sendPrompt(activeThread, c.text);
      });
      wChips.appendChild(btn);
    });
    if (yank.awaiting === CS.Awaiting.DONE) {
      var extras = CS.followUpChips(agent);
      if (extras.length) {
        var wrap = document.createElement('div');
        wrap.className = 'followup-chips';
        var lbl = document.createElement('div');
        lbl.className = 'followup-label';
        lbl.textContent = 'follow up';
        wrap.appendChild(lbl);
        extras.forEach(function (c) {
          var btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'chip focusable chip-outline';
          btn.textContent = c.label;
          btn.addEventListener('click', function () {
            if (c.text && activeThread) sendPrompt(activeThread, c.text);
          });
          wrap.appendChild(btn);
        });
        wChips.appendChild(wrap);
      }
    }
  }

  function renderCompose() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) return;
    titleEl.textContent = displayLabel(t);
    setComposerEnabled(!t.ended);
    if (t.ended) {
      wMeta.textContent = displayLabel(t) + ' · ended';
      wCard.textContent = t.yank ? CS.bodyText(t.yank) : 'session ended';
      renderChips(null, t.agent);
      return;
    }
    if (t.busy) {
      wMeta.textContent = 'thinking…';
      wCard.textContent = 'agent is working';
      renderChips(null, t.agent);
      return;
    }
    if (!t.yank) {
      wMeta.textContent = displayLabel(t) + ' · online';
      wCard.textContent = 'ready for a message';
      renderChips(null, t.agent);
      return;
    }
    wMeta.textContent = CS.metaLine(Object.assign({}, t.yank, { label: displayLabel(t) }));
    wCard.textContent = CS.bodyText(t.yank);
    renderChips(t.yank, t.agent);
  }

  function setComposerEnabled(on) {
    promptEl.disabled = !on;
    dictateBtn.disabled = !on;
    sendBtn.disabled = !on;
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
    var row = threads[thread];
    if (row && row.ended) {
      showToast('session ended', 'error');
      return;
    }
    var item = buildInput(thread, text, true);
    var sent = sendInputItem(item);
    if (!sent) {
      queueInput(item);
    }
    if (row && row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: text });
    if (row) {
      row.lastEventAt = clockNow();
      row.busy = true;
    }
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

  function openNew() {
    pickAgent(pickedAgent);
    if (!newCwd.value) newCwd.value = defaultCwd();
    showView('new');
    newPrompt.focus();
  }

  // Prefill the working directory: host-configured default (set from the Android
  // app) wins, else the most-recent session's cwd, else the last one used here.
  function defaultCwd() {
    if (hostInfo.defaultCwd) return hostInfo.defaultCwd;
    var recent = visibleThreads().filter(function (t) { return t && t.cwd; })[0];
    if (recent && recent.cwd) return recent.cwd;
    try { return localStorage.getItem('al_default_cwd') || ''; } catch (e) { return ''; }
  }

  // Dictate straight into a text field (new-session first message). No thread yet,
  // so this is local speech-to-text only; tapping again stops it.
  function dictateIntoField(field, btn) {
    var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) { showToast('dictate needs Chrome/Safari — type instead', 'error'); field.focus(); return; }
    if (dictRec) { stopDictRec(null); if (btn) btn.classList.remove('recording'); return; }
    var rec = new SR();
    rec.continuous = true; rec.interimResults = true; rec.lang = 'en-US';
    var base = field.value ? field.value.trim() + ' ' : '';
    if (btn) btn.classList.add('recording');
    showToast('listening…', 'success');
    rec.onresult = function (e) {
      var interim = '', finalText = '';
      for (var i = e.resultIndex; i < e.results.length; i++) {
        var r = e.results[i];
        if (r.isFinal) finalText += r[0].transcript; else interim += r[0].transcript;
      }
      field.value = (base + finalText + interim).replace(/\s+/g, ' ').trim();
    };
    rec.onerror = function () { showToast('dictation failed', 'error'); if (btn) btn.classList.remove('recording'); dictRec = null; };
    rec.onend = function () { if (btn) btn.classList.remove('recording'); dictRec = null; };
    dictRec = rec;
    rec.start();
  }

  function startAgentSession(agent) {
    pickAgent(agent);
    if (newCwd) newCwd.value = defaultCwd();
    if (newPrompt) newPrompt.value = '';
    showView('new');
    setTimeout(function () { if (newPrompt) newPrompt.focus(); }, 50);
  }

  // Meta HUD button rows: one expanded pill at a time; icon anchored, label grows right.
  function wireRbtnGroups() {
    document.querySelectorAll('.shelf, .new-actions, .button-row').forEach(function (group) {
      var btns = group.querySelectorAll('.rbtn');
      if (!btns.length) return;
      btns.forEach(function (btn) {
        btn.addEventListener('pointerdown', function () {
          btns.forEach(function (b) { b.classList.toggle('selected', b === btn); });
        });
        btn.addEventListener('focus', function () {
          btns.forEach(function (b) { b.classList.toggle('selected', b === btn); });
        });
        btn.addEventListener('blur', function () {
          setTimeout(function () {
            var active = document.activeElement;
            if (active && group.contains(active)) return;
            btns.forEach(function (b) { b.classList.remove('selected'); });
          }, 0);
        });
      });
    });
  }

  function pickAgent(agent) {
    pickedAgent = agent;
    var ac = agentClass(agent);
    if (newTitle) {
      var key = (agent || 'session').toLowerCase();
      newTitle.textContent = 'create' + key.charAt(0).toUpperCase() + key.slice(1);
    }
    if (newTitleIcon) {
      newTitleIcon.className = 'new-title-icon agent-' + ac;
      newTitleIcon.setAttribute('data-agent-icon', ac);
      newTitleIcon.innerHTML = agentIcon(ac);
    }
  }

  function findThreadForAgent(agent, cwd) {
    var live = liveThreads();
    var wantCwd = (cwd || '').trim();
    for (var i = 0; i < live.length; i++) {
      if ((live[i].agent || '').toLowerCase() !== agent.toLowerCase()) continue;
      if (wantCwd && (live[i].cwd || '') !== wantCwd) continue;
      return live[i];
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

  function startNewThread() {
    var text = (newPrompt.value || '').trim();
    var cwd = (newCwd.value || '').trim();
    if (cwd) { try { localStorage.setItem('al_default_cwd', cwd); } catch (e) {} }
    if (!text) { showToast('enter a first message', 'error'); return; }
    if (!ws || ws.readyState !== 1) { showToast('not connected', 'error'); return; }
    var existing = findThreadForAgent(pickedAgent, cwd);
    if (existing) {
      sendPrompt(existing.id, text);
      newPrompt.value = '';
      openThread(existing.id, true);
      return;
    }
    createHostSession(pickedAgent, cwd, text);
  }

  function createHostSession(agent, cwd, text) {
    fetch('/ambient-link/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agent: agent, cwd: cwd, prompt: text }),
    })
      .then(function (r) {
        var ct = r.headers.get('content-type') || '';
        if (!r.ok || ct.indexOf('application/json') < 0) {
          return r.text().then(function (body) {
            throw new Error(body || 'session create unavailable');
          });
        }
        return r.json();
      })
      .then(function (data) {
        var id = data.thread_id || data.thread || data.session_id;
        if (!id) return threadIdFor(agent, cwd || '.').then(function (fallback) {
          return Object.assign({}, data, { thread_id: fallback });
        });
        return data;
      })
      .then(function (data) {
        var id = data.thread_id || data.thread || data.session_id;
        var row = threadRow(id);
        row.label = data.label || (cwd ? (agent + ': ' + shortName(cwd)) : agent);
        row.agent = agent;
        row.busy = true;
        row.ended = false;
        row.lastEventAt = clockNow();
        newPrompt.value = '';
        newCwd.value = '';
        showToast('starting ' + agent, 'success');
        renderThreadList();
        openThread(id, true);
      })
      .catch(function () {
        showToast('start ' + agent + ' in a terminal first', 'error');
      });
  }

  function shortName(path) {
    var trimmed = (path || '').replace(/[\\\/]+$/, '');
    if (!trimmed) return '';
    var parts = trimmed.split(/[\\\/]/);
    return parts[parts.length - 1] || trimmed;
  }

  function sendInput(thread, text, enter, clientId) {
    return sendInputItem(buildInput(thread, text, enter, clientId));
  }

  function sendInputItem(item) {
    if (!ws || ws.readyState !== 1 || !item || !item.thread || !item.text) return false;
    try {
      ws.send(JSON.stringify({
        type: 'input',
        thread: item.thread,
        text: item.text,
        enter: item.enter !== false,
        client_id: item.id,
      }));
      trackDelivery(item.id, {
        thread: item.thread,
        status: 'sent',
        at: item.at || clockNow(),
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  function buildInput(thread, text, enter, clientId) {
    return {
      id: clientId || newInputId(),
      thread: thread,
      text: text,
      enter: enter !== false,
      at: clockNow(),
    };
  }

  function newInputId() {
    return 'web-' + String(clockNow()) + '-' + Math.random().toString(36).slice(2);
  }

  function pendingKey() {
    return 'ambient-link:pending-inputs';
  }

  function loadPendingInputs() {
    try {
      var raw = localStorage.getItem(pendingKey());
      var rows = raw ? JSON.parse(raw) : [];
      return Array.isArray(rows) ? rows.filter(function (x) {
        return x && x.thread && x.text;
      }) : [];
    } catch (e) {
      return [];
    }
  }

  function savePendingInputs() {
    try {
      localStorage.setItem(pendingKey(), JSON.stringify(pendingInputs.slice(-20)));
    } catch (e) {}
  }

  function queueInput(item) {
    pendingInputs.push(item);
    trackDelivery(item.id, {
      thread: item.thread,
      status: 'local_pending',
      at: item.at,
    });
    savePendingInputs();
    renderHostPanel();
  }

  function flushPendingInputs() {
    if (!pendingInputs.length || !ws || ws.readyState !== 1) return;
    var remaining = [];
    var sent = 0;
    pendingInputs.forEach(function (item) {
      if (sendInputItem(item)) {
        sent++;
      } else {
        remaining.push(item);
      }
    });
    if (sent) {
      pendingInputs = remaining;
      savePendingInputs();
      renderThreadList();
      if (activeThread) renderCompose();
      renderHostPanel();
    }
  }

  function pendingCountForThread(thread) {
    var n = 0;
    pendingInputs.forEach(function (item) {
      if (item.thread === thread) n++;
    });
    return n;
  }

  function lastPendingText(thread) {
    for (var i = pendingInputs.length - 1; i >= 0; i--) {
      if (pendingInputs[i].thread === thread) return truncate(pendingInputs[i].text, 80);
    }
    return '';
  }

  function deliveryKey() {
    return 'ambient-link:delivery-states';
  }

  function loadDeliveryStates() {
    try {
      var raw = localStorage.getItem(deliveryKey());
      var rows = raw ? JSON.parse(raw) : {};
      return rows && typeof rows === 'object' ? rows : {};
    } catch (e) {
      return {};
    }
  }

  function saveDeliveryStates() {
    try {
      var keys = Object.keys(deliveryStates).sort(function (a, b) {
        return (deliveryStates[b].updatedAt || 0) - (deliveryStates[a].updatedAt || 0);
      });
      var compact = {};
      keys.slice(0, 100).forEach(function (k) { compact[k] = deliveryStates[k]; });
      deliveryStates = compact;
      localStorage.setItem(deliveryKey(), JSON.stringify(deliveryStates));
    } catch (e) {}
  }

  function trackDelivery(id, fields) {
    if (!id) return;
    deliveryStates[id] = Object.assign({}, deliveryStates[id] || {}, fields || {}, {
      updatedAt: clockNow(),
    });
    saveDeliveryStates();
  }

  function applyInputStatus(msg) {
    if (!msg || !msg.id) return;
    trackDelivery(msg.id, {
      thread: msg.thread,
      sessionId: msg.session_id,
      status: msg.status || 'unknown',
      error: msg.error || '',
      pendingCount: msg.pending_count || 0,
      relayAt: msg.at || 0,
    });
  }

  function applyOutboxStatus(outbox) {
    var pending = {};
    (outbox || []).forEach(function (session) {
      (session.messages || []).forEach(function (msg) {
        if (!msg.id) return;
        pending[msg.id] = true;
        trackDelivery(msg.id, {
          thread: msg.thread,
          sessionId: msg.session_id,
          status: 'queued',
          attempts: msg.attempts || 0,
          error: msg.last_error || '',
          relayAt: msg.at || 0,
        });
      });
    });
    Object.keys(deliveryStates).forEach(function (id) {
      var row = deliveryStates[id];
      if (row && row.status === 'queued' && !pending[id]) {
        trackDelivery(id, { status: 'delivered', error: '' });
      }
    });
  }

  function applyYank(msg) {
    var yank = CS.parseYank(msg);
    var row = threadRow(yank.thread);
    if (msg.label) row.label = msg.label;
    if (msg.agent) row.agent = msg.agent;
    row.busy = false;
    row.ended = false;
    row.yank = yank;
    row.lastEventAt = msg.at || clockNow();
  }

  function syncFromHost() {
    fetch('/ambient-link/status')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data) return;
        hostInfo.relayDebug = !!data.relay_debug;
        hostInfo.journal = data.journal || 0;
        hostInfo.now = data.now || Date.now();
        if (typeof data.default_cwd === 'string' && data.default_cwd) hostInfo.defaultCwd = data.default_cwd;
        hostInfo.delivery = {};
        (data.delivery || []).forEach(function (d) {
          if (d.SessionID) hostInfo.delivery[d.SessionID] = d;
        });
        applyOutboxStatus(data.outbox || []);
        relayBadge.classList.toggle('hidden', !hostInfo.relayDebug);
        renderHostPanel();
        if (!data.sessions) return;
        data.sessions.forEach(function (s) {
          var id = s.thread_id || s.session_id;
          if (!id) return;
          var row = threadRow(id);
          if (s.label) row.label = s.label;
          else if (s.agent && s.cwd) row.label = s.agent + ': ' + (s.cwd.split('/').pop() || s.cwd);
          if (s.agent) row.agent = s.agent;
          row.cwd = s.cwd || row.cwd || '';
          row.sessionId = s.session_id || row.sessionId;
          row.deliverable = sessionDeliverable(s.session_id);
          row.busy = s.state === 'BUSY' || s.state === 'STARTING';
          row.ended = s.state === 'DEAD';
          row.lastEventAt = s.last_event_at || row.lastEventAt || clockNow();
        });
        renderThreadList();
        if (activeThread) renderCompose();
      })
      .catch(function () {});
  }

  function subscribeFromCursor(cursor) {
    if (!ws || ws.readyState !== 1) return;
    var since = cursor && typeof cursor === 'object' ? cursor : {};
    ws.send(JSON.stringify({ type: 'subscribe', since: since }));
  }

  function connect() {
    setStatus('warn');
    try { ws = new WebSocket(WS_URL); }
    catch (e) { setStatus('off'); scheduleReconnect(); return; }

    ws.onopen = function () {
      backoff = 500;
      setStatus('on');
    };

    ws.onmessage = function (ev) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }

      if (msg.type === 'hello') {
        subscribeFromCursor(msg.cursor);
        (msg.threads || []).forEach(upsertHelloRow);
        if (msg.relay_debug) {
          hostInfo.relayDebug = true;
          relayBadge.classList.remove('hidden');
          showToast('relay debug — explicit cards only', 'success');
        }
        syncFromHost();
        flushPendingInputs();
        renderThreadList();
        tryPendingDeepLink();
        if (activeThread) renderCompose();
        return;
      }

      if (msg.type === 'thread_started') {
        var started = threadRow(msg.thread);
        if (msg.label) started.label = msg.label;
        if (msg.agent) started.agent = msg.agent;
        if (msg.cwd) started.cwd = msg.cwd;
        started.busy = true;
        started.ended = false;
        started.lastEventAt = msg.at || clockNow();
      } else if (msg.type === 'thread_ended') {
        var ended = threadRow(msg.thread);
        ended.ended = true;
        ended.busy = false;
        ended.yank = null;
        ended.lastEventAt = msg.at || clockNow();
      } else if (msg.type === 'thread_busy') {
        var busy = threadRow(msg.thread);
        busy.busy = true;
        busy.ended = false;
        busy.lastEventAt = msg.at || clockNow();
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
      } else if (msg.type === 'input_status') {
        applyInputStatus(msg);
        return;
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

  setInterval(syncFromHost, 15000);

  backBtn.addEventListener('click', closeThreadView);
  btnPull.addEventListener('click', function () {
    if (activeThread) pullCard(activeThread);
  });
  newStart.addEventListener('click', startNewThread);
  if (shelf) shelf.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-agent]');
    if (btn) startAgentSession(btn.dataset.agent);
  });
  composer.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = (promptEl.value || '').trim();
    if (!text || !activeThread) return;
    sendPrompt(activeThread, text);
  });
  dictateBtn.addEventListener('click', startDictate);
  if (newDictate) newDictate.addEventListener('click', function () { dictateIntoField(newPrompt, newDictate); });

  pendingDeepLink = parseDeepLink();
  document.querySelectorAll('[data-agent-icon]').forEach(function (n) {
    n.innerHTML = agentIcon(n.getAttribute('data-agent-icon'));
  });
  pickAgent('cursor');
  wireRbtnGroups();
  renderThreadList();
  setStatus('off');
  if (window.__AMBIENT_TEST__) {
    window.__AmbientWebTest = {
      threadRow: threadRow,
      sendPrompt: sendPrompt,
      sendInput: sendInput,
      flushPendingInputs: flushPendingInputs,
      pendingInputs: function () { return pendingInputs.slice(); },
      deliveryStates: function () { return Object.assign({}, deliveryStates); },
      applyInputStatus: applyInputStatus,
      setSocket: function (fake) { ws = fake; },
      setHostNow: function (ms) { hostInfo.now = ms; },
      state: function () {
        return {
          activeThread: activeThread,
          threads: threads,
          threadOrder: threadOrder.slice(),
        };
      },
    };
  } else {
    syncFromHost();
    connect();
  }
})();
