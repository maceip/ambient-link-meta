// Ambient Link web companion — session list + compose. Glasses HUD is native relay only.
(function () {
  'use strict';

  var LOG = window.AmbientLog || { log: function () {} };
  var CS = window.AmbientChipSet;
  var BLK = window.AmbientBlocks;
  var PIPE = window.AmbientContentPipeline;

  /* Bearer token, provisioned by the pair flow (QR/link carries #token=…
     in the fragment so it never appears in server logs). Persisted so a
     reload keeps the pairing; sent on the WS upgrade and on fetches. */
  var TOKEN = loadToken();

  function loadToken() {
    try {
      var m = (location.hash || '').match(/[#&]token=([^&]+)/);
      if (m) {
        var tok = decodeURIComponent(m[1]);
        localStorage.setItem('ambient-link:token', tok);
        history.replaceState({}, '', location.pathname + location.search);
        return tok;
      }
      return localStorage.getItem('ambient-link:token') || '';
    } catch (e) { return ''; }
  }

  function authHeaders(extra) {
    var h = extra || {};
    if (TOKEN) h['Authorization'] = 'Bearer ' + TOKEN;
    return h;
  }

  function wsUrl() {
    var base = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ambient-link/ws';
    return TOKEN ? base + '?token=' + encodeURIComponent(TOKEN) : base;
  }

  var threadsUl  = document.getElementById('threads');
  var emptyHint  = document.getElementById('empty-hint');
  var viewList   = document.getElementById('view-threads');
  var viewThread = document.getElementById('view-thread');
  var viewNew    = document.getElementById('view-new');
  var backBtn    = document.getElementById('back');
  var titleEl    = document.getElementById('t-title');
  var wMeta      = document.getElementById('w-meta');
  var wChat      = document.getElementById('w-chat');
  var quickRepliesEl = document.getElementById('quick-replies');
  var promptEl   = document.getElementById('prompt');
  var dictateBtn = document.getElementById('dictate');
  var sendBtn     = document.getElementById('send');
  var threadActions = document.getElementById('thread-actions');
  var composeField = document.getElementById('compose-field');
  var dictateChrome = document.getElementById('dictate-chrome');
  var dictateStatus = document.getElementById('dictate-status');
  var dictateStatusText = document.getElementById('dictate-status-text');
  var dictateRedo = document.getElementById('dictate-redo');
  var dictatePause = document.getElementById('dictate-pause');
  var newTitle   = document.getElementById('new-title');
  var newTitleIcon = document.getElementById('new-title-icon');
  var newCwd     = document.getElementById('new-cwd');
  var newPrompt  = document.getElementById('new-prompt');
  var newStart   = document.getElementById('new-start');
  var newBack    = document.getElementById('new-back');
  var toastEl    = document.getElementById('toast');
  var hostPanel  = document.getElementById('host-panel');
  var relayBadge = document.getElementById('relay-badge');
  var newDictate = document.getElementById('new-dictate');
  var listScroll = document.getElementById('list-scroll');
  var listBody   = document.getElementById('list-body');
  var newSessionReveal = document.getElementById('new-session-reveal');
  var newSessionPill = document.getElementById('new-session-pill');

  /** Glasses list view: show at most this many sessions (newest by last activity). */
  var MAX_LIST_ITEMS = 4;

  var threads = {};
  var threadOrder = [];
  var activeView = 'list';
  var activeThread = null;
  var pickedAgent = 'cursor';
  var ws = null;
  var backoff = 500;
  var toastTimer;
  var dictRec = null;
  var phoneDictateThread = null;
  var listeningPartial = '';
  var dictatePhase = 'idle'; // idle | listening | review
  var dictateDraft = '';
  /* DMs convention: start pinned to the newest message; scrolling up unpins,
     returning near the bottom re-pins. */
  var chatPinBottom = true;
  var chatForceScrollOnce = false;
  var chatScrollToUser = false;
  var lastChatRenderSig = '';
  var dictateWatchdog = null;
  var listPinBottom = false;
  var listFocusedThreadId = null;
  var lastListSig = '';
  var pendingDeepLink = null;
  var hostInfo = {
    relayDebug: false,
    journal: 0,
    now: 0,
    delivery: {},
    defaultCwd: '',
    relayConnected: null,
    laptopPeerConnected: false,
    liveSessionCount: 0,
  };
  var hostPanelOpen = false;
  var pendingInputs = loadPendingInputs();
  var deliveryStates = loadDeliveryStates();
  var companionConfig = {
    quickReplies: [],
    snoozeUntil: 0,
    showContinue: true,
    showDictate: true,
  };

  function isSnoozing() {
    return companionConfig.snoozeUntil && clockNow() < companionConfig.snoozeUntil;
  }

  function chipConfig() {
    return {
      quickReplies: companionConfig.quickReplies || [],
      showContinue: companionConfig.showContinue !== false,
      showDictate: companionConfig.showDictate !== false,
    };
  }

  function sessionActionChips(t) {
    // Active session compose: custom quick-reply pills only — no continue/dictate
    // (dictate lives in the action bar; continue is for peek cards, not live chat).
    return CS.sessionQuickReplies(chipConfig());
  }

  function applyCompanionConfig(msg) {
    if (msg.quick_replies && Array.isArray(msg.quick_replies)) {
      companionConfig.quickReplies = msg.quick_replies.filter(function (s) { return s && String(s).trim(); });
    } else if (msg.quick_replies) {
      companionConfig.quickReplies = [];
    }
    if (typeof msg.snooze_until === 'number') companionConfig.snoozeUntil = msg.snooze_until;
    if (typeof msg.show_continue === 'boolean') companionConfig.showContinue = msg.show_continue;
    if (typeof msg.show_dictate === 'boolean') companionConfig.showDictate = msg.show_dictate;
    if (typeof msg.default_agent === 'string') {
      var da = msg.default_agent.toLowerCase();
      if (da === 'cursor' || da === 'claude' || da === 'codex') {
        pickedAgent = da;
        pickAgent(pickedAgent);
      }
    }
    renderQuickReplies();
    if (activeThread) renderCompose();
  }

  function renderQuickReplies() {
    if (!quickRepliesEl) return;
    var t = activeThread ? threads[activeThread] : null;
    var chips = sessionActionChips(t);
    quickRepliesEl.innerHTML = '';
    if (!chips.length || activeView !== 'thread') {
      quickRepliesEl.classList.add('hidden');
      return;
    }
    quickRepliesEl.classList.remove('hidden');
    chips.forEach(function (chip) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'quick-reply-pill focusable' +
        (chip.primary || chip.kind === 'dictate' ? ' quick-reply-pill--primary' : '');
      btn.textContent = chip.label || chip.text || '';
      btn.addEventListener('click', function () {
        if (!activeThread) return;
        if (chip.kind === 'dictate') {
          if (dictRec || phoneDictateThread) stopDictRec(activeThread);
          else startDictate();
          return;
        }
        if (chip.text) sendPrompt(activeThread, chip.text);
      });
      quickRepliesEl.appendChild(btn);
    });
    wireTaps();
  }

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

  function setDictatePhase(phase) {
    dictatePhase = phase || 'idle';
    if (threadActions) {
      threadActions.classList.toggle('dictate-listening', dictatePhase === 'listening');
      threadActions.classList.toggle('dictate-review', dictatePhase === 'review');
    }
    if (composeField) {
      composeField.classList.toggle('dictate-listening', dictatePhase === 'listening');
      composeField.classList.toggle('dictate-review', dictatePhase === 'review');
    }
    if (dictateChrome) dictateChrome.classList.toggle('hidden', dictatePhase === 'idle');
    if (dictateStatus) dictateStatus.classList.toggle('hidden', dictatePhase !== 'listening');
    if (dictateBtn) {
      dictateBtn.classList.toggle('recording', dictatePhase === 'listening');
      dictateBtn.classList.toggle('rbtn-active', dictatePhase === 'listening');
    }
    if (sendBtn) {
      sendBtn.classList.toggle('rbtn-active', dictatePhase === 'review');
    }
    if (dictatePhase === 'listening' && dictateStatusText) {
      dictateStatusText.textContent = 'Listening…';
    }
  }

  function resetDictateUi() {
    dictateDraft = '';
    listeningPartial = '';
    setDictatePhase('idle');
    if (dictateBtn) dictateBtn.classList.remove('recording');
    if (sendBtn) sendBtn.classList.remove('rbtn-active');
    promptEl.placeholder = 'type your message…';
  }

  function sendDictate(type, thread, text) {
    if (!ws || ws.readyState !== 1 || !thread) return;
    var o = { type: type, thread: thread, source: 'web' };
    if (text != null && text !== '') o.text = text;
    ws.send(JSON.stringify(o));
  }

  function sendSessionSignal(type, thread) {
    if (!ws || ws.readyState !== 1 || !thread) return;
    ws.send(JSON.stringify({ type: type, thread: thread, source: 'web' }));
  }

  function companionScreenForView(which) {
    if (which === 'list') return 'list';
    if (which === 'new') return 'create';
    if (which === 'thread') return 'session';
    return 'idle';
  }

  /** Tell the phone relay when the web companion owns the glasses display. */
  function sendCompanionUi(which) {
    if (!ws || ws.readyState !== 1) return;
    ws.send(JSON.stringify({
      type: 'companion_ui',
      screen: companionScreenForView(which),
      source: 'web',
    }));
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
        chatLog: [],
        lastEventAt: 0,
        deliverable: false,
        sessionState: 'IDLE',
      };
      threadOrder.push(id);
    }
    return threads[id];
  }

  function upsertHelloRow(t) {
    var row = threadRow(t.id);
    row.label = t.label || t.id;
    row.agent = t.agent || row.agent || 'generic';
    if (t.session_id) row.sessionId = t.session_id;
    row.ended = false;
    row.lastEventAt = row.lastEventAt || clockNow();
  }

  var wsConnState = 'connecting';

  function wsConnected() {
    return wsConnState === 'on';
  }

  function connectionState() {
    return wsConnState;
  }

  function connectionCopy(state) {
    var live = hostInfo.liveSessionCount || liveThreadCount();
    if (state === 'warn') return 'Connecting to relay…';
    if (state === 'off') {
      return 'Not connected — open from your Mac relay or check network';
    }
    if (hostInfo.relayConnected === false) {
      return 'Relay unreachable — reconnecting…';
    }
    if (live > 0) {
      return 'Connected · ' + live + ' live session' + (live === 1 ? '' : 's');
    }
    if (hostInfo.laptopPeerConnected) {
      return 'Connected · Mac linked, no active agents';
    }
    return 'Connected · no Mac agents running';
  }

  function renderConnStatus() {
    // Instagram/DMs-era chrome (shell restored from c100fd9): banner on the
    // list view + strip on the thread view, driven by the same wsConnState
    // the rest of the app already maintains.
    var state = connectionState();
    var connStatus = document.getElementById('conn-status');
    var connDot = document.getElementById('conn-dot');
    var connLabel = document.getElementById('conn-label');
    var threadConn = document.getElementById('thread-conn');
    var threadConnLabel = document.getElementById('thread-conn-label');
    if (connDot) {
      connDot.classList.remove('on', 'off', 'warn');
      connDot.classList.add(state);
    }
    // c100fd9-era "Connected · N live sessions" liveliness, folded into the
    // one-line chrome so it costs no card space.
    var connCount = document.getElementById('conn-count');
    if (connCount) {
      var liveNow = hostInfo.liveSessionCount || liveThreadCount();
      var showCount = state === 'on' && liveNow > 0;
      connCount.textContent = showCount ? liveNow + ' live' : '';
      connCount.classList.toggle('hidden', !showCount);
    }
    if (connStatus) {
      connStatus.classList.remove('on', 'off', 'warn');
      connStatus.classList.add(state);
    }
    if (connLabel) connLabel.textContent = connectionCopy(state);
    // Vertical economy: the banner earns a row only when something is wrong;
    // connected state is the header dot.
    if (connStatus) connStatus.classList.toggle('hidden', state === 'on');
    if (threadConn) {
      threadConn.classList.remove('on', 'off', 'warn');
      threadConn.classList.add(state);
    }
    if (threadConnLabel) {
      threadConnLabel.textContent = state === 'on'
        ? 'Connected'
        : (state === 'warn' || state === 'connecting' ? 'Connecting…' : 'Not connected');
    }
  }

  function setStatus(state) {
    wsConnState = state || 'off';
    if (document.body) document.body.dataset.relayState = wsConnState;
    renderConnStatus();
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
      'mac linked: ' + (hostInfo.laptopPeerConnected ? 'yes' : 'no'),
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
      showToast('not connected to relay', 'error');
      return;
    }
    ws.send(JSON.stringify({ type: 'hud_yank', thread: thread }));
    showToast('refreshing card…', 'success');
  }

  function showToast(msg, kind) {
    toastEl.textContent = msg;
    toastEl.className = 'toast visible' + (kind ? ' ' + kind : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.className = 'toast'; }, 2800);
  }

  function showView(which) {
    activeView = which;
    viewList.classList.toggle('hidden', which !== 'list');
    viewThread.classList.toggle('hidden', which !== 'thread');
    viewNew.classList.toggle('hidden', which !== 'new');
    sendCompanionUi(which);
    renderQuickReplies();
    renderConnStatus();
  }

  function viewRootFor(name) {
    if (name === 'list') return viewList;
    if (name === 'thread') return viewThread;
    if (name === 'new') return viewNew;
    return null;
  }

  function isTextEntry(el) {
    if (!el) return false;
    var tag = el.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable;
  }

  function focusablesInView(name) {
    var root = viewRootFor(name);
    if (!root || root.classList.contains('hidden')) return [];
    return Array.prototype.slice.call(
      root.querySelectorAll('.focusable:not([disabled])')
    ).filter(function (el) {
      if (name === 'thread' && el.id === 'prompt') return false;
      if (name === 'new' && (el.id === 'new-prompt' || el.id === 'new-cwd')) return false;
      return !el.classList.contains('hidden') && el.offsetParent !== null;
    });
  }

  function focusLastListRow() {
    if (!threadsUl) return;
    var rows = threadsUl.querySelectorAll('.thread-row');
    if (!rows.length) return;
    var pick = listFocusedThreadId
      ? threadsUl.querySelector('.thread-row[data-thread-id="' + listFocusedThreadId + '"]')
      : null;
    var target = pick || rows[rows.length - 1];
    if (target) target.focus({ preventScroll: true });
  }

  /** Glasses can't type — land on Dictate (mic), expanded and ready. */
  function focusSessionPrimary() {
    if (!dictateBtn || activeView !== 'thread') return;
    setTimeout(function () {
      if (dictateBtn.disabled) {
        var items = focusablesInView('thread');
        if (items.length) items[0].focus();
        return;
      }
      dictateBtn.focus();
    }, 60);
  }

  function focusNewPrimary() {
    if (activeView !== 'new') return;
    setTimeout(function () {
      if (newStart && !newStart.disabled) newStart.focus();
      else if (newBack) newBack.focus();
    }, 60);
  }

  function focusInitialInView(which) {
    if (which === 'list') {
      focusLastListRow();
      return;
    }
    if (which === 'thread') {
      focusSessionPrimary();
      return;
    }
    if (which === 'new') {
      focusNewPrimary();
    }
  }

  function moveFocus(direction) {
    var items = focusablesInView(activeView);
    if (!items.length) return;
    var idx = items.indexOf(document.activeElement);
    if (idx === -1) {
      items[0].focus();
      return;
    }
    var next = idx;
    if (direction === 'up' || direction === 'left') {
      next = idx > 0 ? idx - 1 : items.length - 1;
    } else {
      next = idx < items.length - 1 ? idx + 1 : 0;
    }
    items[next].focus();
  }

  function wireDpadNavigation() {
    document.addEventListener('keydown', function (e) {
      if (isTextEntry(document.activeElement)) return;
      var key = e.key;
      if (activeView === 'thread' && (key === 'ArrowUp' || key === 'ArrowDown')) {
        if (tryScrollChat(key === 'ArrowUp' ? 'up' : 'down')) {
          e.preventDefault();
          return;
        }
      }
      if (key === 'ArrowUp') { e.preventDefault(); moveFocus('up'); return; }
      if (key === 'ArrowDown') { e.preventDefault(); moveFocus('down'); return; }
      if (key === 'ArrowLeft') { e.preventDefault(); moveFocus('left'); return; }
      if (key === 'ArrowRight') { e.preventDefault(); moveFocus('right'); return; }
      if (key === 'Enter' && document.activeElement.classList.contains('focusable')) {
        e.preventDefault();
        document.activeElement.click();
      }
      if (key === 'Escape' && activeView !== 'list') {
        e.preventDefault();
        if (activeView === 'thread') closeThreadView();
        else if (activeView === 'new') closeNewSessionView();
      }
    });
  }

  function liveThreads() {
    return threadOrder
      .map(function (id) { return threads[id]; })
      .filter(function (t) { return t && !t.ended; });
  }

  function visibleThreads() {
    return threadOrder
      .map(function (id) { return threads[id]; })
      .filter(function (t) { return !!t; })
      .sort(function (a, b) { return (b.lastEventAt || 0) - (a.lastEventAt || 0); });
  }

  /** Drop ended sessions from memory so the list can't fill with ghosts. */
  function reapDeadThreads() {
    threadOrder = threadOrder.filter(function (id) {
      var t = threads[id];
      return t && !t.ended;
    });
    Object.keys(threads).forEach(function (id) {
      if (threads[id] && threads[id].ended) delete threads[id];
    });
  }

  /** Glasses: browser STT is broken — always use phone mic relay path. */
  function usePhoneDictate() {
    return true;
  }

  function speechAvailable() {
    return !usePhoneDictate() && !!(window.SpeechRecognition || window.webkitSpeechRecognition);
  }

  function liveThreadCount() {
    return visibleThreads().filter(function (t) { return !t.ended; }).length;
  }

  function expandHomePath(path) {
    var p = (path || '').trim();
    if (!p || p === '~') return '';
    if (p.startsWith('~/')) return p.slice(2);
    if (p.charAt(0) === '~') return p.slice(1).replace(/^[\\/]+/, '');
    return p;
  }

  /** List card title — last folder name only, no ~ or full path. */
  function folderTitle(t) {
    var cwd = expandHomePath((t && t.cwd) || '');
    if (!cwd && t && t.label) {
      var bits = String(t.label).split(':');
      if (bits.length > 1) cwd = expandHomePath(bits.slice(1).join(':').trim());
    }
    var leaf = shortName(cwd);
    if (leaf) return leaf;
    var agent = ((t && t.agent) || '').trim();
    return agent || 'session';
  }

  function displayLabel(t) {
    var fromFolder = folderTitle(t);
    if (fromFolder && fromFolder !== 'session') return fromFolder;
    var label = ((t && t.label) || '').trim();
    if (!label || /:\s*$/.test(label)) label = ((t && t.agent) || 'session').trim();
    return label || 'session';
  }

  /** List card preview — one short line (validation gate). */
  function listPreviewPlain(raw) {
    var s = String(raw || '')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/https?:\/\/\S+/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    return truncate(s, 64);
  }

  function listPreviewText(t) {
    if (t.ended) return 'session ended';
    if (t.busy) return 'thinking…';
    var agentPrev = lastAgentPreview(t);
    if (agentPrev) return listPreviewPlain(agentPrev);
    if (t.lastAssistant) return listPreviewPlain(t.lastAssistant);
    if (t.yank && t.yank.lastAssistant) return listPreviewPlain(t.yank.lastAssistant);
    return '';
  }

  function listTimeLabel(ms) {
    if (!ms) return '';
    var now = new Date(clockNow());
    var d = new Date(ms);
    if (now.toDateString() === d.toDateString()) {
      return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    }
    var delta = Math.max(0, clockNow() - ms);
    if (delta < 7 * 24 * 60 * 60 * 1000) {
      return d.toLocaleDateString([], { weekday: 'short' });
    }
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
  }

  function listConnectionDot(t) {
    var badge = statusBadge(t);
    if (badge === 'offline' || badge === 'unreachable') return 'offline';
    if (badge === 'dead') return 'dead';
    if (badge === 'busy' || badge === 'permission') return 'busy';
    return 'live';
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
    var agentPrev = lastAgentPreview(t);
    if (agentPrev) return previewFromText(agentPrev);
    if (t.yank) return previewFromText(CS.bodyText(t.yank));
    var pending = lastPendingText(t.id);
    if (pending) return previewFromText('You: ' + pending);
    return '';
  }

  function statusBadge(t) {
    if (t.ended || t.sessionState === 'DEAD') return 'dead';
    if (!wsConnected() && !t.lastEventAt) return 'offline';
    if (t.sessionId && !sessionDeliverable(t.sessionId) && hostInfo.laptopPeerConnected) return 'unreachable';
    if (t.busy || t.sessionState === 'BUSY' || t.sessionState === 'STARTING') return 'busy';
    if (t.yank && t.yank.awaiting === CS.Awaiting.PERMISSION) return 'permission';
    if (t.yank && t.yank.awaiting === CS.Awaiting.QUESTION) return 'question';
    if (t.yank && t.yank.awaiting === CS.Awaiting.DONE) return 'done';
    if (t.yank) return 'idle';
    if (t.sessionState === 'IDLE' || t.sessionState === 'BUSY') return 'online';
    return 'online';
  }

  function agentStatusLabel(state) {
    switch (state) {
      case 'dead': return 'crashed';
      case 'unreachable': return 'unreachable';
      case 'offline': return 'offline';
      case 'busy': return 'working';
      case 'permission': return 'permission';
      case 'question': return 'question';
      case 'done': return 'ready';
      case 'idle': return 'idle';
      default: return state;
    }
  }

  function truncate(s, n) {
    if (!s) return '';
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function filterText(raw) {
    if (PIPE && PIPE.filterForDisplay) return PIPE.filterForDisplay(raw || '');
    return { kind: 'normal', truncated: false, display: (raw || '').trim() };
  }

  function previewFromText(raw) {
    if (PIPE && PIPE.preview) return PIPE.preview(raw || '', 180);
    return truncate((raw || '').trim(), 180);
  }

  function ensureChatLog(row) {
    if (!row.chatLog) row.chatLog = [];
    return row.chatLog;
  }

  function agentTextFromYank(yank) {
    if (!yank) return '';
    if (yank.awaiting === CS.Awaiting.PERMISSION) {
      return ((yank.permissionPrompt && yank.permissionPrompt.trim()) || yank.lastAssistant || '').trim();
    }
    return (yank.lastAssistant || '').trim();
  }

  function chatHasRoleText(row, role, rawText) {
    if (!rawText || !String(rawText).trim()) return false;
    var disp = filterText(rawText).display;
    var log = row && row.chatLog;
    if (!log || !log.length) return false;
    for (var i = 0; i < log.length; i++) {
      if (log[i].role === role && log[i].text === disp) return true;
    }
    return false;
  }

  function chatHasUserText(row, rawText) {
    return chatHasRoleText(row, 'user', rawText);
  }

  function chatLogKey() {
    return 'ambient-link:chat-logs-v3';
  }

  /* Offline resilience: the glasses should open to the last-known session
     list (greyed via the existing session-offline styling) instead of an
     empty screen. Snapshot is written only while connected, so a dead-relay
     render never overwrites the good one; restored rows are reconciled
     against the next hello. */
  var LIST_SNAPSHOT_KEY = 'ambient-link:list-snapshot';

  function saveListSnapshot(live) {
    if (!wsConnected() || !live || !live.length) return;
    try {
      localStorage.setItem(LIST_SNAPSHOT_KEY, JSON.stringify(live.map(function (t) {
        return {
          id: t.id, label: t.label, agent: t.agent,
          lastAssistant: listPreviewPlain(listPreviewText(t)) || t.lastAssistant || '',
          lastEventAt: t.lastEventAt || 0,
        };
      })));
    } catch (e) {}
  }

  function restoreListSnapshot() {
    try {
      var raw = localStorage.getItem(LIST_SNAPSHOT_KEY);
      if (!raw) return;
      JSON.parse(raw).forEach(function (s) {
        if (!s || !s.id || threads[s.id]) return;
        var row = threadRow(s.id);
        row.label = s.label || s.id;
        row.agent = s.agent || 'generic';
        row.lastAssistant = s.lastAssistant || '';
        row.lastEventAt = s.lastEventAt || 0;
        row.restored = true; // dropped on hello if the relay no longer has it
      });
    } catch (e) {}
  }

  function reconcileRestoredRows(helloThreads) {
    var seen = {};
    (helloThreads || []).forEach(function (t) { if (t && t.id) seen[t.id] = true; });
    Object.keys(threads).forEach(function (id) {
      if (threads[id].restored && !seen[id]) {
        delete threads[id];
        threadOrder = threadOrder.filter(function (x) { return x !== id; });
      } else {
        threads[id].restored = false;
      }
    });
  }

  function loadChatLogs() {
    try {
      var raw = localStorage.getItem(chatLogKey());
      if (!raw) return;
      var data = JSON.parse(raw);
      if (!data || typeof data !== 'object') return;
      Object.keys(data).forEach(function (id) {
        if (!Array.isArray(data[id]) || !data[id].length) return;
        var row = threadRow(id);
        if (!row.chatLog || !row.chatLog.length) row.chatLog = data[id];
      });
    } catch (e) {}
  }

  function saveChatLogs() {
    try {
      var data = {};
      Object.keys(threads).forEach(function (id) {
        var log = threads[id].chatLog;
        if (log && log.length) data[id] = log;
      });
      localStorage.setItem(chatLogKey(), JSON.stringify(data));
    } catch (e) {}
  }

  /** Append-only chat log. The only in-place mutation allowed is a user
      bubble's delivery status, updated by input_status frames keyed on the
      relay message ID (opts.id). */
  function appendChatMessage(row, role, rawText, opts) {
    if (!rawText || !String(rawText).trim()) return;
    opts = opts || {};
    if (opts.id && chatFindByMsgId(row, opts.id)) return;
    if (chatHasRoleText(row, role, rawText)) return;
    var filtered = filterText(rawText);
    var log = ensureChatLog(row);
    log.push({
      role: role,
      text: filtered.display,
      kind: filtered.kind,
      truncated: filtered.truncated,
      at: opts.at || clockNow(),
      msgId: opts.id || '',
      status: opts.status || '',
      error: opts.error || '',
    });
    if (log.length > 48) row.chatLog = log.slice(-48);
    saveChatLogs();
  }

  function chatFindByMsgId(row, msgId) {
    var log = row && row.chatLog;
    if (!log || !msgId) return null;
    for (var i = log.length - 1; i >= 0; i--) {
      if (log[i].msgId === msgId) return log[i];
    }
    return null;
  }

  /** Update a user bubble's lifecycle status by message ID. Returns true if
      a bubble was found. Statuses are honest relay states only. */
  function updateMessageStatus(threadId, msgId, status, error) {
    var row = threads[threadId];
    var entry = chatFindByMsgId(row, msgId);
    if (!entry) return false;
    entry.status = status || entry.status;
    entry.error = error || '';
    saveChatLogs();
    return true;
  }

  function appendUserMessage(row, text, opts) {
    if (!row || !text || !String(text).trim()) return;
    appendChatMessage(row, 'user', text, opts);
    LOG.log('chat', 'user', { thread: row.id, n: (row.chatLog || []).length });
  }

  function recordAgentReply(row, rawText) {
    if (!row || row.busy) return;
    appendChatMessage(row, 'agent', rawText);
  }

  function mergeAgentFromYank(row) {
    if (!row || !row.yank) return;
    recordAgentReply(row, agentTextFromYank(row.yank));
  }

  function syncChatFromSessionFields(row, session) {
    if (!row || !session) return;
    if (session.last_user_input) row.lastUserInput = session.last_user_input;
    if (session.last_assistant) row.lastAssistant = session.last_assistant;
  }

  function replayDeliveredUserMessages(row) {
    if (!row) return;
    Object.keys(deliveryStates).forEach(function (id) {
      var st = deliveryStates[id];
      if (!st || st.thread !== row.id) return;
      if (st.status !== 'delivered' && st.status !== 'landed') return;
      var text = (st.text || '').trim();
      if (text) appendUserMessage(row, text, { id: id, status: st.status });
    });
    pendingInputs.forEach(function (item) {
      if (item.thread !== row.id) return;
      var text = (item.text || '').trim();
      if (text) appendUserMessage(row, text, { id: item.id, status: 'offline' });
    });
  }

  function hydrateChatIfEmpty(row) {
    if (!row || (row.chatLog && row.chatLog.length)) return;
    replayDeliveredUserMessages(row);
    if (!row.busy) {
      mergeAgentFromYank(row);
      if ((!row.chatLog || !row.chatLog.length) && row.lastAssistant) {
        recordAgentReply(row, row.lastAssistant);
      }
    }
  }

  /* Relay history is the authoritative record (store.interactions on the
     laptop, proxied when hosted); localStorage is only a display cache.
     Merge strategy: relay rows first (they carry real message IDs and final
     delivery statuses), then keep local entries the relay doesn't know —
     in-flight sends and offline-queued drafts. */
  function hydrateFromRelayHistory(row) {
    if (!row || !row.sessionId || row.historyLoading || row.historyLoaded) return;
    row.historyLoading = true;
    fetch('/ambient-link/history?session_id=' + encodeURIComponent(row.sessionId) + '&limit=48', {
      headers: authHeaders(),
    })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        row.historyLoading = false;
        if (!data || !Array.isArray(data.rows)) return;
        row.historyLoaded = true;
        if (!data.rows.length) return;
        mergeHistoryRows(row, data.rows);
        if (activeThread === row.id) renderCompose();
      })
      .catch(function () { row.historyLoading = false; });
  }

  function mergeHistoryRows(row, rows) {
    var merged = [];
    var seenIds = {};
    rows.forEach(function (r) {
      if (!r || !r.text || !String(r.text).trim()) return;
      var filtered = filterText(r.text);
      var entry = {
        role: r.role === 'human' ? 'user' : 'agent',
        text: filtered.display,
        kind: filtered.kind,
        truncated: filtered.truncated,
        at: r.at || 0,
        msgId: r.message_id || '',
        status: r.role === 'human' ? (r.delivery_status || '') : '',
        error: '',
      };
      if (entry.msgId) seenIds[entry.msgId] = true;
      merged.push(entry);
    });
    var textSeen = {};
    merged.forEach(function (m) { textSeen[m.role + '\u0000' + m.text] = true; });
    (row.chatLog || []).forEach(function (m) {
      if (m.msgId && seenIds[m.msgId]) return;
      if (textSeen[m.role + '\u0000' + m.text]) return;
      merged.push(m);
    });
    merged.sort(function (a, b) { return (a.at || 0) - (b.at || 0); });
    row.chatLog = merged.slice(-48);
    saveChatLogs();
  }

  function chatAgentLabel(row) {
    var agent = (row && row.agent) || 'agent';
    var a = agent.toLowerCase();
    if (a === 'cursor') return 'Cursor';
    if (a === 'claude') return 'Claude';
    if (a === 'codex' || a === 'openai') return 'Codex';
    return agent.charAt(0).toUpperCase() + agent.slice(1);
  }

  function chatMessagesForRender(row) {
    return (row && row.chatLog) ? row.chatLog.slice() : [];
  }

  function lastAgentPreview(row) {
    var log = row && row.chatLog;
    if (log && log.length) {
      for (var i = log.length - 1; i >= 0; i--) {
        if (log[i].role === 'agent' && log[i].text) return log[i].text;
      }
    }
    if (row && row.yank) return agentTextFromYank(row.yank);
    return '';
  }

  function threadDetail(t) {
    var parts = [];
    if (t.agent) parts.push(t.agent);
    if (t.cwd) parts.push(shortName(t.cwd));
    return parts.join(' · ');
  }

  var AGENT_ICONS = {
    cursor: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M22.106 5.68L12.5.135a.998.998 0 00-.998 0L1.893 5.68a.84.84 0 00-.419.726v11.186c0 .3.16.577.42.727l9.607 5.547a.999.999 0 00.998 0l9.608-5.547a.84.84 0 00.42-.727V6.407a.84.84 0 00-.42-.726zm-.603 1.176L12.228 22.92c-.063.108-.228.064-.228-.061V12.34a.59.59 0 00-.295-.51l-9.11-5.26c-.107-.062-.063-.228.062-.228h18.55c.264 0 .428.286.296.514z"/></svg>',
    claude: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M4.709 15.955l4.72-2.647.08-.23-.08-.128H9.2l-.79-.048-2.698-.073-2.339-.097-2.266-.122-.571-.121L0 11.784l.055-.352.48-.321.686.06 1.52.103 2.278.158 1.652.097 2.449.255h.389l.055-.157-.134-.098-.103-.097-2.358-1.596-2.552-1.688-1.336-.972-.724-.491-.364-.462-.158-1.008.656-.722.881.06.225.061.893.686 1.908 1.476 2.491 1.833.365.304.145-.103.019-.073-.164-.274-1.355-2.446-1.446-2.49-.644-1.032-.17-.619a2.97 2.97 0 01-.104-.729L6.283.134 6.696 0l.996.134.42.364.62 1.414 1.002 2.229 1.555 3.03.456.898.243.832.091.255h.158V9.01l.128-1.706.237-2.095.23-2.695.08-.76.376-.91.747-.492.584.28.48.685-.067.444-.286 1.851-.559 2.903-.364 1.942h.212l.243-.242.985-1.306 1.652-2.064.73-.82.85-.904.547-.431h1.033l.76 1.129-.34 1.166-1.064 1.347-.881 1.142-1.264 1.7-.79 1.36.073.11.188-.02 2.856-.606 1.543-.28 1.841-.315.833.388.091.395-.328.807-1.969.486-2.309.462-3.439.813-.042.03.049.061 1.549.146.662.036h1.622l3.02.225.79.522.474.638-.079.485-1.215.62-1.64-.389-3.829-.91-1.312-.329h-.182v.11l1.093 1.068 2.006 1.81 2.509 2.33.127.578-.322.455-.34-.049-2.205-1.657-.851-.747-1.926-1.62h-.128v.17l.444.649 2.345 3.521.122 1.08-.17.353-.608.213-.668-.122-1.374-1.925-1.415-2.167-1.143-1.943-.14.08-.674 7.254-.316.37-.729.28-.607-.461-.322-.747.322-1.476.389-1.924.315-1.53.286-1.9.17-.632-.012-.042-.14.018-1.434 1.967-2.18 2.945-1.726 1.845-.414.164-.717-.37.067-.662.401-.589 2.388-3.036 1.44-1.882.93-1.086-.006-.158h-.055L4.132 18.56l-1.13.146-.487-.456.061-.746.231-.243 1.908-1.312-.006.006z"/></svg>',
    codex: '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" stroke-width="1.75"/><path d="M8 9l-2 3 2 3M16 9l2 3-2 3M13 8l-2 8" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>'
  };

  var PULL_REVEAL_THRESHOLD = 56;
  var pullRevealPx = 0;
  var pullTouchStartY = 0;
  var revealAutoOpened = false;

  function listAtTop() {
    if (!listScroll) return true;
    return listScroll.scrollTop <= 4;
  }

  function setPullReveal(px, sticky) {
    if (!newSessionReveal) return;
    pullRevealPx = Math.max(0, Math.min(px, 88));
    var open = sticky || pullRevealPx >= PULL_REVEAL_THRESHOLD;
    newSessionReveal.classList.toggle('open', open);
    newSessionReveal.setAttribute('aria-hidden', open ? 'false' : 'true');
    newSessionReveal.style.setProperty('--pull', pullRevealPx + 'px');
    // Once open the reveal takes real flow height and pushes the list itself;
    // keeping the shift too doubles the gap (and the pill can't fill it).
    if (listBody) listBody.style.setProperty('--list-shift', (open ? 0 : pullRevealPx) + 'px');
    if (listScroll) listScroll.style.setProperty('--pull', pullRevealPx + 'px');
  }

  function wireListPullReveal() {
    if (!listScroll || !newSessionPill) return;

    listScroll.addEventListener('touchstart', function (e) {
      pullTouchStartY = e.touches[0].clientY;
    }, { passive: true });

    listScroll.addEventListener('touchmove', function (e) {
      if (!listAtTop()) return;
      var dy = e.touches[0].clientY - pullTouchStartY;
      if (dy > 0) setPullReveal(dy, false);
    }, { passive: true });

    listScroll.addEventListener('touchend', function () {
      if (pullRevealPx < PULL_REVEAL_THRESHOLD) setPullReveal(0, false);
      else setPullReveal(PULL_REVEAL_THRESHOLD, true);
    });

    listScroll.addEventListener('wheel', function (e) {
      if (!listAtTop()) return;
      if (e.deltaY >= 0) return;
      var next = pullRevealPx + Math.abs(e.deltaY);
      setPullReveal(next, next >= PULL_REVEAL_THRESHOLD);
      e.preventDefault();
    }, { passive: false });

    listScroll.addEventListener('scroll', function () {
      if (!listAtTop() && pullRevealPx > 0) setPullReveal(0, false);
    });

    newSessionPill.addEventListener('click', function () {
      setPullReveal(0, false);
      openNewSession();
    });
  }

  var THEMES = ['meta', 'dracula', 'tokyo-night', 'catppuccin', 'nord'];

  function wireThemes() {
    // The header theme-cycle chip was removed (glasses chrome economy); a
    // previously saved theme still applies. Contract: themes.css keys off
    // data-theme on <html>.
    var saved = '';
    try { saved = localStorage.getItem('ambient-link:theme') || ''; } catch (e) {}
    document.documentElement.dataset.theme = THEMES.indexOf(saved) >= 0 ? saved : 'meta';
  }

  function wireListScroll() {
    if (!listScroll || listScroll.dataset.listScrollWired) return;
    listScroll.dataset.listScrollWired = '1';
    listScroll.addEventListener('scroll', function () {
      var dist = listScroll.scrollHeight - listScroll.scrollTop - listScroll.clientHeight;
      listPinBottom = dist <= 48;
    }, { passive: true });
    listScroll.addEventListener('touchmove', function () {
      listPinBottom = false;
    }, { passive: true });
  }

  function scrollListToBottom() {
    var el = listScroll || threadsUl;
    if (el) el.scrollTop = el.scrollHeight;
  }

  function openNewSession() {
    startAgentSession(pickedAgent);
  }

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

  /* Uniform round zinc avatars (reference_ui/LIST_ITEM_CONTENT.png: replace
     tile avatars with agent icons "rounded like these avatars are").
     Generated from ambient-link-core/agents/assets into icons/agents/zinc/. */
  var ZINC_ICONS = ['amp', 'apple', 'claude', 'claudecode', 'cline', 'codex', 'copilot',
    'cursor', 'deepseek', 'githubcopilot', 'goose', 'grok', 'hermesagent', 'huggingface',
    'hunyuan', 'kimi', 'longcat', 'manus', 'mcp', 'meta', 'metaai', 'microsoft',
    'midjourney', 'minimax', 'mistral', 'openclaw', 'openhands', 'poe', 'qwen',
    'replit', 'roocode', 'trae', 'venice'];

  function agentAvatarHtml(agent) {
    var key = agentClass(agent);
    if (key === 'generic') key = 'mcp';
    if (ZINC_ICONS.indexOf(key) < 0) return agentIcon(agent) || undefined;
    return '<img class="avatar-zinc" src="icons/agents/zinc/' + key + '.png" alt="" draggable="false">';
  }

  function threadListSignature(live) {
    return live.map(function (t) {
      return t.id + '|' + (t.lastEventAt || 0) + '|' + (t.busy ? 1 : 0) + '|' + (t.ended ? 1 : 0)
        + '|' + listPreviewPlain(listPreviewText(t));
    }).join(';');
  }

  function renderThreadList(force) {
    var live = liveThreads();
    var sig = threadListSignature(live);
    if (!force && sig === lastListSig && threadsUl && threadsUl.childElementCount === live.length && live.length > 0) {
      return;
    }
    lastListSig = sig;
    var scrollEl = listScroll || threadsUl;
    var scrollTopBefore = scrollEl ? scrollEl.scrollTop : 0;
    var activeRow = threadsUl && threadsUl.querySelector('.thread-row:focus');
    if (activeRow && activeRow.dataset.threadId) {
      listFocusedThreadId = activeRow.dataset.threadId;
    }
    // Newest at bottom (chat-style). Cap at MAX_LIST_ITEMS — keep the most recent.
    live.sort(function (a, b) { return (a.lastEventAt || 0) - (b.lastEventAt || 0); });
    if (live.length > MAX_LIST_ITEMS) {
      live = live.slice(live.length - MAX_LIST_ITEMS);
    }
    threadsUl.innerHTML = '';
    if (live.length === 0) {
      emptyHint.classList.remove('hidden');
      if (wsConnState === 'warn' || wsConnState === 'connecting') {
        emptyHint.textContent = 'Loading sessions…';
      } else if (!wsConnected()) {
        emptyHint.textContent = 'Relay offline — open this app from your Mac or wait for reconnect';
      } else if (hostInfo.liveSessionCount > 0) {
        emptyHint.textContent = 'Loading sessions…';
      } else if (hostInfo.laptopPeerConnected) {
        emptyHint.textContent = 'No active agents — tap New session, or start one on your Mac';
      } else {
        emptyHint.textContent = 'No sessions — tap New session';
      }
      // Empty list: the pull-to-reveal gesture is undiscoverable, so surface
      // the New session pill outright instead of hiding the only action.
      if (!revealAutoOpened) {
        revealAutoOpened = true;
        setPullReveal(PULL_REVEAL_THRESHOLD, true);
      }
      renderConnStatus();
      wireRbtnGroups();
      wireTaps();
      return;
    }
    emptyHint.classList.add('hidden');
    if (revealAutoOpened) {
      revealAutoOpened = false;
      setPullReveal(0, false);
    }
    live.forEach(function (t) {
      var ac = agentClass(t.agent);
      var preview = listPreviewText(t);
      var badgeState = statusBadge(t);
      var connState = listConnectionDot(t);
      var snoozed = isSnoozing();
      var li = BLK.renderListItem({
        threadId: t.id,
        className: 'dm-row agent-' + ac + ' ' + badgeState + (wsConnected() ? '' : ' session-offline'),
        ariaLabel: folderTitle(t) + ', ' + (t.agent || 'agent') + ', ' + agentStatusLabel(badgeState),
        label: folderTitle(t),
        preview: preview || 'Waiting for agent…',
        time: listTimeLabel(t.lastEventAt) || relativeTime(t.lastEventAt),
        avatarHtml: agentAvatarHtml(t.agent),
        avatarClass: 'agent-' + ac,
        muted: snoozed,
        connectionState: connState,
        onClick: function () {
          listFocusedThreadId = t.id;
          openThread(t.id, true);
        },
        onActivate: function () {
          listFocusedThreadId = t.id;
          openThread(t.id, true);
        },
      });
      threadsUl.appendChild(li);
    });
    saveListSnapshot(live);
    renderConnStatus();
    wireRbtnGroups();
    wireTaps();
  }


  /** Single-tap fix (see blocks.js wireImmediateTap): rewire after any render
      that creates focusable cards/pills. Per-element dataset guard makes
      repeated document-wide calls cheap. */
  function wireTaps() {
    if (BLK && BLK.wireImmediateTap) BLK.wireImmediateTap(document);
  }

  function wireChatScroll() {
    if (!wChat || wChat.dataset.scrollWired) return;
    wChat.dataset.scrollWired = '1';
    wChat.setAttribute('tabindex', '-1');
    var touchStartY = 0;
    wChat.addEventListener('scroll', function () {
      var dist = wChat.scrollHeight - wChat.scrollTop - wChat.clientHeight;
      chatPinBottom = dist <= 48;
    }, { passive: true });
    wChat.addEventListener('touchstart', function (e) {
      touchStartY = e.touches[0].clientY;
    }, { passive: true });
    wChat.addEventListener('touchmove', function (e) {
      if (Math.abs(e.touches[0].clientY - touchStartY) > 10) chatPinBottom = false;
    }, { passive: true });
    wChat.addEventListener('wheel', function () {
      chatPinBottom = false;
    }, { passive: true });
    wChat.addEventListener('keydown', function (e) {
      if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
      if (wChat.scrollHeight <= wChat.clientHeight + 2) return;
      e.preventDefault();
      e.stopPropagation();
      var step = 56;
      if (e.key === 'ArrowUp') wChat.scrollTop = Math.max(0, wChat.scrollTop - step);
      else wChat.scrollTop = Math.min(wChat.scrollHeight - wChat.clientHeight, wChat.scrollTop + step);
      var dist = wChat.scrollHeight - wChat.scrollTop - wChat.clientHeight;
      chatPinBottom = dist <= 48;
    });
  }

  function tryScrollChat(direction) {
    if (!wChat || activeView !== 'thread') return false;
    if (wChat.scrollHeight <= wChat.clientHeight + 2) return false;
    var step = 56;
    if (direction === 'up') {
      if (wChat.scrollTop <= 0) return false;
      wChat.scrollTop = Math.max(0, wChat.scrollTop - step);
    } else {
      var max = wChat.scrollHeight - wChat.clientHeight;
      if (wChat.scrollTop >= max - 1) return false;
      wChat.scrollTop = Math.min(max, wChat.scrollTop + step);
    }
    var dist = wChat.scrollHeight - wChat.scrollTop - wChat.clientHeight;
    chatPinBottom = dist <= 48;
    return true;
  }

  function renderCompose() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t || !wChat) return;
    titleEl.textContent = displayLabel(t);
    setComposerEnabled(!t.ended);

    var thinking = t.busy && !t.ended;

    if (t.ended) {
      wMeta.textContent = displayLabel(t) + ' · ended';
      wMeta.classList.remove('hidden');
      hydrateChatIfEmpty(t);
      BLK.renderChatThread(wChat, chatMessagesForRender(t), {
        agentLabel: chatAgentLabel(t),
        emptyText: 'Session ended with no messages.',
      });
      scrollChatToBottom();
      return;
    }

    /* The meta strip earns its row only when it says something the chat
       bubbles don't: a broken relay or an agent waiting on the human. The
       old "label · done" breadcrumb repeated the title and cramped the
       bubbles (c100fd9-era look had no such line). */
    if (!wsConnected()) {
      wMeta.textContent = 'Not connected — messages will not send until relay reconnects';
      wMeta.classList.remove('hidden');
    } else if (!thinking && t.yank &&
        (t.yank.awaiting === CS.Awaiting.PERMISSION || t.yank.awaiting === CS.Awaiting.QUESTION)) {
      wMeta.textContent = CS.metaLine(Object.assign({}, t.yank, { label: displayLabel(t) }));
      wMeta.classList.remove('hidden');
    } else {
      wMeta.classList.add('hidden');
    }

    BLK.renderChatThread(wChat, chatMessagesForRender(t), {
      thinking: thinking,
      agentLabel: chatAgentLabel(t),
      emptyText: 'No messages yet.',
    });
    renderQuickReplies();
    scrollChatToBottom();
  }

  function scrollChatToBottom() {
    if (!wChat || !chatPinBottom) return;
    wChat.scrollTop = wChat.scrollHeight;
  }

  function setComposerEnabled(on) {
    promptEl.disabled = !on;
    var dictateOn = companionConfig.showDictate !== false;
    if (dictateBtn) {
      dictateBtn.disabled = !on || !dictateOn;
      dictateBtn.style.display = dictateOn ? '' : 'none';
    }
    sendBtn.disabled = !on;
  }

  function startPhoneDictate(t) {
    stopDictRec(null);
    phoneDictateThread = t.id;
    dictateDraft = '';
    sendSessionSignal('session_focus', t.id);
    sendDictate('dictate_begin', t.id);
    listeningPartial = '';
    setDictatePhase('listening');
    promptEl.placeholder = 'listening…';
  }

  /* Honest dictation end: the relay's dictate_end carries ok/error. A failed
     inject renders as a failure — never a sent bubble (DECISIONS §4). */
  function applyDictateResult(thread, text, ok, errText) {
    if (phoneDictateThread === thread || phoneDictateThread != null) phoneDictateThread = null;
    listeningPartial = '';
    if (dictRec) {
      try { dictRec.stop(); } catch (e) {}
      dictRec = null;
    }
    if (dictateBtn) dictateBtn.classList.remove('recording');
    resetDictateUi();
    var trimmed = (text || '').trim();
    if (!trimmed) {
      renderCompose();
      return;
    }
    if (ok === false) {
      // Keep the transcript in the composer so the human can retry or edit.
      promptEl.value = trimmed;
      showToast('not delivered — ' + (errText || 'inject failed'), 'error');
      renderCompose();
      return;
    }
    var row = threads[thread];
    if (row) {
      appendUserMessage(row, trimmed, { status: 'delivered' });
      if (row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: trimmed });
      row.lastEventAt = clockNow();
    }
    promptEl.value = '';
    renderCompose();
    if (activeView === 'list') renderThreadList();
    setTimeout(syncFromHost, 400);
  }

  function pauseDictate() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) return;
    stopDictRec(t.id);
    var text = (dictateDraft || listeningPartial || promptEl.value || '').trim();
    if (!text) {
      sendDictate('dictate_abort', t.id);
      resetDictateUi();
      renderCompose();
      return;
    }
    sendDictate('dictate_commit', t.id, text);
    promptEl.placeholder = 'sending…';
    setDictatePhase('idle');
    renderCompose();
  }

  function startDictate() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) { showToast('open a session first', 'error'); return; }
    if (!wsConnected()) {
      showToast('relay not connected — wait for reconnect', 'error');
      return;
    }
    stopDictRec(null);
    startPhoneDictate(t);
    if (dictateStatusText) dictateStatusText.textContent = 'Listening on phone…';
    renderCompose();
  }

  function debugPingText() {
    var now = new Date(clockNow());
    var local = now.toLocaleTimeString([], {
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
    });
    return 'debug ping ' + local + ' · ' + now.toISOString();
  }

  /* Honest send: the bubble appears immediately but carries its real
     lifecycle state (sending/offline), advanced ONLY by input_status frames
     keyed on the message ID. No 'sent' toast, no fabricated busy state. */
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
    if (row) {
      appendUserMessage(row, text, { id: item.id, status: sent ? 'sending' : 'offline' });
      LOG.log('send', 'prompt', { thread: thread, ok: sent });
      if (row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: text });
      row.lastEventAt = clockNow();
    }
    promptEl.value = '';
    resetDictateUi();
    if (!sent) showToast('offline — queued on this device', 'error');
    renderCompose();
    if (activeView === 'list') renderThreadList();
  }

  function openThread(id, compose) {
    if (activeThread && activeThread !== id) sendSessionSignal('session_blur', activeThread);
    activeThread = id;
    chatPinBottom = true; // opening a thread always lands on the newest message
    listFocusedThreadId = id;
    setUrlForSession(id, !!compose);
    showView('thread');
    var cached = threads[id];
    titleEl.textContent = cached ? displayLabel(cached) : 'loading…';
    sendSessionSignal('session_focus', id);
    hydrateChatIfEmpty(threads[id]);
    hydrateFromRelayHistory(threads[id]);
    renderCompose();
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ type: 'hud_yank', thread: id }));
    }
    syncFromHost();
  }

  function closeThreadView() {
    stopDictRec(activeThread);
    if (phoneDictateThread && activeThread) sendDictate('dictate_abort', activeThread);
    phoneDictateThread = null;
    resetDictateUi();
    if (activeThread) sendSessionSignal('session_blur', activeThread);
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
    focusNewPrimary();
  }

  function closeNewSessionView() {
    showView('list');
    focusLastListRow();
  }

  function fillNewTestPrompt() {
    if (!newPrompt) return;
    newPrompt.value = debugPingText();
    showToast('test prompt filled — tap Start', 'success');
    focusNewPrimary();
  }

  // Prefill the working directory: host-configured default (set from the Android
  // app) wins, else the most-recent session's cwd, else the last one used here.
  function defaultCwd() {
    if (hostInfo.defaultCwd) return hostInfo.defaultCwd;
    var recent = visibleThreads().filter(function (t) { return t && t.cwd; })[0];
    if (recent && recent.cwd) return recent.cwd;
    try { return localStorage.getItem('al_default_cwd') || ''; } catch (e) { return ''; }
  }

  function dictateIntoField(field, btn) {
    if (usePhoneDictate()) {
      if (field === newPrompt) fillNewTestPrompt();
      else showToast('use phone mic in session', 'error');
      return;
    }
    var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
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
    rec.onerror = function (ev) {
      var why = (ev && ev.error) ? ev.error : 'failed';
      if (why === 'not-allowed') showToast('microphone blocked — allow mic or type', 'error');
      else if (why === 'service-not-allowed') showToast('voice not supported in this browser — type instead', 'error');
      else showToast('dictation failed (' + why + ') — type instead', 'error');
      if (btn) btn.classList.remove('recording');
      dictRec = null;
    };
    rec.onend = function () { if (btn) btn.classList.remove('recording'); dictRec = null; };
    try {
      dictRec = rec;
      rec.start();
    } catch (e) {
      showToast('dictation unavailable — type instead', 'error');
      if (btn) btn.classList.remove('recording');
      dictRec = null;
    }
  }

  function startAgentSession(agent) {
    pickAgent(agent);
    if (newCwd) newCwd.value = defaultCwd();
    if (newPrompt) newPrompt.value = '';
    showView('new');
    focusNewPrimary();
  }

  // Meta HUD button row: exactly one expanded pill — the focused control only.
  function wireRbtnGroups() {
    if (BLK) BLK.wireRbtnGroups(document);
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
    var chips = document.querySelectorAll('#agent-chips .agent-chip');
    for (var i = 0; i < chips.length; i++) {
      var on = chips[i].getAttribute('data-agent') === ac;
      chips[i].classList.toggle('selected', on);
      chips[i].setAttribute('aria-checked', on ? 'true' : 'false');
    }
  }

  function wireAgentChips() {
    var box = document.getElementById('agent-chips');
    if (!box) return;
    box.addEventListener('click', function (e) {
      var chip = e.target.closest('.agent-chip');
      if (!chip) return;
      pickAgent(chip.getAttribute('data-agent'));
    });
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

  /* Create honesty: the web never invents a thread ID. A session exists only
     when the relay broadcasts thread_started (with session_id). Until then
     the UI shows "starting…"; create failures arrive as create_status
     frames (or the HTTP error) and render as errors. */
  var pendingCreate = null;

  function createHostSession(agent, cwd, text) {
    fetch('/ambient-link/sessions', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ agent: agent, cwd: cwd, prompt: text }),
    })
      .then(function (r) {
        if (!r.ok) {
          return r.text().then(function (body) {
            var msg = body;
            try { msg = JSON.parse(body).error || body; } catch (e) {}
            throw new Error(msg || ('session create failed (' + r.status + ')'));
          });
        }
        pendingCreate = { agent: agent, cwd: cwd || '', at: clockNow() };
        newPrompt.value = '';
        newCwd.value = '';
        showToast('starting ' + agent + '…', 'success');
        showView('list');
        renderThreadList();
      })
      .catch(function (err) {
        showToast((err && err.message) || ('could not start ' + agent), 'error');
      });
  }

  function applyCreateStatus(msg) {
    if (!msg) return;
    if (msg.ok === false) {
      pendingCreate = null;
      showToast('agent failed to start — ' + (msg.error || 'unknown error'), 'error');
      return;
    }
    showToast((msg.agent || 'agent') + ' starting — session will appear shortly', 'success');
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

  /* Wire format: session_id is the address (thread is the legacy fallback the
     relay still accepts); client_id is the message-lifecycle ID echoed back on
     every input_status frame. There is no enter flag — delivery always
     submits (relay DECISIONS §4). 'sending' is a local state: only the
     relay's own 'accepted' confirms custody. */
  function sendInputItem(item) {
    if (!ws || ws.readyState !== 1 || !item || !item.thread || !item.text) return false;
    try {
      ws.send(JSON.stringify({
        type: 'input',
        session_id: item.sessionId || sessionIdForThread(item.thread),
        thread: item.thread,
        text: item.text,
        client_id: item.id,
      }));
      trackDelivery(item.id, {
        thread: item.thread,
        text: item.text,
        status: 'sending',
        at: item.at || clockNow(),
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  function sessionIdForThread(thread) {
    var row = thread ? threads[thread] : null;
    return (row && row.sessionId) || '';
  }

  function buildInput(thread, text, enter, clientId) {
    return {
      id: clientId || newInputId(),
      thread: thread,
      sessionId: sessionIdForThread(thread),
      text: text,
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
      text: item.text,
      status: 'offline',
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
        updateMessageStatus(item.thread, item.id, 'sending', '');
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

  /* The one honest source of per-message truth: input_status frames from the
     relay (accepted → queued/delivered → landed | failed), keyed by message
     ID. Updates the matching bubble in place; appends only when this device
     has the text but never rendered a bubble (e.g. restored after reload). */
  function applyInputStatus(msg) {
    if (!msg || !msg.id) return;
    var known = deliveryStates[msg.id];
    trackDelivery(msg.id, {
      thread: msg.thread || (known && known.thread) || '',
      sessionId: msg.session_id,
      status: msg.status || 'unknown',
      error: msg.error || '',
      pendingCount: msg.pending_count || 0,
      relayAt: msg.at || 0,
    });
    var status = msg.status || '';
    var threadId = msg.thread || (known && known.thread) || '';
    if (updateMessageStatus(threadId, msg.id, status, msg.error || '')) {
      if (status === 'failed') showToast('not delivered — ' + (msg.error || 'delivery failed'), 'error');
      return;
    }
    // No bubble yet (page reloaded mid-flight): materialize it from the
    // cached text once the relay confirms the message really exists.
    if (status !== 'accepted' && status !== 'queued' && status !== 'delivered' && status !== 'landed') return;
    var row = threadId ? threads[threadId] : null;
    if (!row) return;
    var text = '';
    var st = deliveryStates[msg.id];
    if (st && st.text) text = String(st.text).trim();
    if (!text) {
      for (var i = pendingInputs.length - 1; i >= 0; i--) {
        if (pendingInputs[i].id === msg.id) {
          text = String(pendingInputs[i].text || '').trim();
          break;
        }
      }
    }
    if (text) appendUserMessage(row, text, { id: msg.id, status: status });
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
    // Do NOT upgrade queued → delivered when a message leaves the outbox.
    // Only input_status (delivered / landed) from the relay is authoritative.
  }

  function applyYank(msg) {
    if (isSnoozing()) return;
    var yank = CS.parseYank(msg);
    var row = threadRow(yank.thread);
    if (msg.label) row.label = msg.label;
    if (msg.agent) row.agent = msg.agent;
    row.busy = false;
    row.ended = false;
    row.yank = yank;
    row.lastEventAt = msg.at || clockNow();
    mergeAgentFromYank(row);
  }

  function syncFromHost() {
    fetch('/ambient-link/status', { headers: authHeaders() })
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
        var laptopPeer = !!(data.laptop_peer_connected || data.cloud_peer);
        var liveOnHost = data.sessions.some(function (s) { return s.state !== 'DEAD'; });
        // Cloud relay mux can lag behind the laptop peer — don't let stale DEAD
        // snapshots wipe rows we already have from live WS broadcasts.
        if (laptopPeer && !liveOnHost) {
          hostInfo.relayConnected = true;
          hostInfo.laptopPeerConnected = true;
          hostInfo.liveSessionCount = 0;
          renderConnStatus();
          renderThreadList();
          return;
        }
        var bestByThread = {};
        data.sessions.forEach(function (s) {
          var id = s.thread_id || s.session_id;
          if (!id) return;
          var cur = bestByThread[id];
          var live = s.state !== 'DEAD';
          if (!cur) {
            bestByThread[id] = s;
            return;
          }
          var curLive = cur.state !== 'DEAD';
          if (live && !curLive) {
            bestByThread[id] = s;
            return;
          }
          if (live === curLive && (s.last_event_at || 0) >= (cur.last_event_at || 0)) {
            bestByThread[id] = s;
          }
        });
        Object.keys(bestByThread).forEach(function (id) {
          var s = bestByThread[id];
          var row = threadRow(id);
          if (laptopPeer && s.state === 'DEAD' && row.lastEventAt && !row.ended) {
            return;
          }
          if (s.label) row.label = s.label;
          else if (s.agent && s.cwd) row.label = s.agent + ': ' + (s.cwd.split('/').pop() || s.cwd);
          if (s.agent) row.agent = s.agent;
          row.cwd = s.cwd || row.cwd || '';
          row.sessionId = s.session_id || row.sessionId;
          row.sessionState = s.state || row.sessionState || 'IDLE';
          row.deliverable = sessionDeliverable(s.session_id);
          row.busy = s.state === 'BUSY' || s.state === 'STARTING';
          row.ended = s.state === 'DEAD';
          row.lastEventAt = s.last_event_at || row.lastEventAt || clockNow();
          syncChatFromSessionFields(row, s);
        });
        reapDeadThreads();
        hostInfo.relayConnected = true;
        hostInfo.laptopPeerConnected = laptopPeer;
        hostInfo.liveSessionCount = Object.keys(bestByThread).filter(function (id) {
          return bestByThread[id].state !== 'DEAD';
        }).length;
        renderConnStatus();
        renderThreadList();
        if (activeThread) {
          // The open thread may only now have learned its session_id.
          hydrateFromRelayHistory(threads[activeThread]);
          renderCompose();
        }
      })
      .catch(function () {
        hostInfo.relayConnected = false;
        renderConnStatus();
        renderThreadList();
      });
  }

  function subscribeFromCursor(cursor) {
    if (!ws || ws.readyState !== 1) return;
    var since = cursor && typeof cursor === 'object' ? cursor : {};
    ws.send(JSON.stringify({ type: 'subscribe', since: since }));
  }

  function connect() {
    setStatus('warn');
    try { ws = new WebSocket(wsUrl()); }
    catch (e) { setStatus('off'); scheduleReconnect(); return; }

    ws.onopen = function () {
      backoff = 500;
      setStatus('on');
      sendCompanionUi(activeView);
    };

    ws.onmessage = function (ev) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }

      if (msg.type === 'hello') {
        subscribeFromCursor(msg.cursor);
        reconcileRestoredRows(msg.threads);
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
        sendCompanionUi(activeView);
        return;
      }

      if (msg.type === 'thread_started') {
        var started = threadRow(msg.thread);
        if (msg.label) started.label = msg.label;
        if (msg.agent) started.agent = msg.agent;
        if (msg.cwd) started.cwd = msg.cwd;
        if (msg.session_id) started.sessionId = msg.session_id;
        started.busy = true;
        started.ended = false;
        started.lastEventAt = msg.at || clockNow();
        if (pendingCreate && (pendingCreate.agent === (msg.agent || '') || !msg.agent)) {
          pendingCreate = null;
          openThread(msg.thread, true);
        }
      } else if (msg.type === 'thread_ended') {
        var ended = threadRow(msg.thread);
        ended.ended = true;
        ended.busy = false;
        ended.yank = null;
        ended.lastEventAt = msg.at || clockNow();
        reapDeadThreads();
      } else if (msg.type === 'thread_busy') {
        var busy = threadRow(msg.thread);
        busy.busy = true;
        busy.ended = false;
        busy.lastEventAt = msg.at || clockNow();
      } else if (msg.type === 'thread_idle' || msg.type === 'hud_yank') {
        applyYank(msg);
      } else if (msg.type === 'companion_config') {
        applyCompanionConfig(msg);
        return;
      } else if (msg.type === 'dictate_active' && activeThread === msg.thread) {
        if (msg.source && msg.source !== 'web') phoneDictateThread = msg.thread;
        setDictatePhase('listening');
        if (dictateStatusText) {
          dictateStatusText.textContent = (msg.source === 'phone')
            ? 'Listening on phone — speak now'
            : 'Listening… speak now';
        }
        promptEl.placeholder = 'listening…';
      } else if (msg.type === 'dictate_partial' && activeThread === msg.thread && msg.text) {
        promptEl.value = msg.text;
        listeningPartial = msg.text;
        if (dictateStatusText) dictateStatusText.textContent = msg.text;
        renderCompose();
      } else if (msg.type === 'dictate_end' && activeThread === msg.thread) {
        applyDictateResult(msg.thread, msg.text || '', msg.ok !== false, msg.error || '');
      } else if (msg.type === 'input_status') {
        applyInputStatus(msg);
        if (activeThread === msg.thread) renderCompose();
        else renderThreadList();
        return;
      } else if (msg.type === 'create_status') {
        applyCreateStatus(msg);
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

  window.addEventListener('pagehide', function () {
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ type: 'companion_ui', screen: 'idle', source: 'web' }));
    }
  });

  function regainSessionFocus() {
    if (!activeThread || activeView !== 'thread') return;
    if (!ws || ws.readyState !== 1) return;
    sendSessionSignal('session_focus', activeThread);
    sendCompanionUi('thread');
  }

  window.addEventListener('pageshow', regainSessionFocus);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') regainSessionFocus();
  });

  // E2E can refresh the agent card without a visible pull button.
  document.addEventListener('ambient-pull-card', function (e) {
    var thread = e.detail && e.detail.thread;
    if (thread) pullCard(thread);
  });

  backBtn.addEventListener('click', closeThreadView);
  if (newBack) newBack.addEventListener('click', closeNewSessionView);
  if (newSessionPill) newSessionPill.addEventListener('click', openNewSession);
  newStart.addEventListener('click', startNewThread);
  wireThemes();
  wireDpadNavigation();
  sendBtn.addEventListener('click', function () {
    var text = (promptEl.value || '').trim();
    if (!text || !activeThread) return;
    sendPrompt(activeThread, text);
  });
  promptEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') {
      e.preventDefault();
      var text = (promptEl.value || '').trim();
      if (!text || !activeThread) return;
      sendPrompt(activeThread, text);
    }
  });
  dictateBtn.addEventListener('click', function () {
    if (dictatePhase === 'listening') {
      pauseDictate();
      return;
    }
    startDictate();
  });
  if (dictatePause) dictatePause.addEventListener('click', function () { pauseDictate(); });
  if (dictateRedo) dictateRedo.addEventListener('click', function () {
    resetDictateUi();
    promptEl.value = '';
    if (activeThread) {
      var row = threads[activeThread];
      if (row && row.chatLog && row.chatLog.length) {
        var last = row.chatLog[row.chatLog.length - 1];
        if (last && last.role === 'user') row.chatLog.pop();
        saveChatLogs();
      }
      renderCompose();
    }
    startDictate();
  });
  if (newDictate) newDictate.addEventListener('click', fillNewTestPrompt);

  pendingDeepLink = parseDeepLink();
  document.querySelectorAll('[data-agent-icon]').forEach(function (n) {
    n.innerHTML = agentIcon(n.getAttribute('data-agent-icon'));
  });
  wireAgentChips();
  pickAgent(pickedAgent);
  wireRbtnGroups();
  wireTaps();
  wireListPullReveal();
  restoreListSnapshot();
  loadChatLogs();
  renderThreadList(true);
  setStatus('connecting');
  window.__ambientOpenNew = openNewSession;
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
