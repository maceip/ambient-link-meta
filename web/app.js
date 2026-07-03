// Ambient Link web companion — session list + compose. Glasses HUD is native relay only.
(function () {
  'use strict';

  var CS = window.AmbientChipSet;
  var BLK = window.AmbientBlocks;
  var PIPE = window.AmbientContentPipeline;
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
  var toastEl    = document.getElementById('toast');
  var hostPanel  = document.getElementById('host-panel');
  var relayBadge = document.getElementById('relay-badge');
  var newDictate = document.getElementById('new-dictate');
  var listScroll = document.getElementById('list-scroll');
  var newSessionReveal = document.getElementById('new-session-reveal');
  var newSessionPill = document.getElementById('new-session-pill');

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
  var pendingDeepLink = null;
  var hostInfo = { relayDebug: false, journal: 0, now: 0, delivery: {}, defaultCwd: '', relayConnected: null };
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
    var cfg = chipConfig();
    if (t && t.yank) return CS.forYank(t.yank, cfg);
    return (cfg.quickReplies || []).map(function (text) {
      return CS.quickReplyChip(text);
    });
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

  function speechAvailable() {
    return !!(window.SpeechRecognition || window.webkitSpeechRecognition);
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
    var agentPrev = lastAgentPreview(t);
    if (agentPrev) return previewFromText(agentPrev);
    if (t.yank) return previewFromText(CS.bodyText(t.yank));
    var pending = lastPendingText(t.id);
    if (pending) return previewFromText('You: ' + pending);
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

  function upsertChatTurn(row, role, rawText) {
    if (!rawText || !String(rawText).trim()) return;
    var filtered = filterText(rawText);
    var log = ensureChatLog(row);
    var entry = {
      role: role,
      text: filtered.display,
      kind: filtered.kind,
      truncated: filtered.truncated,
      at: clockNow(),
    };
    var last = log[log.length - 1];
    if (last && last.role === role && last.text === entry.text) return;
    if (last && last.role === role) {
      log[log.length - 1] = entry;
      return;
    }
    log.push(entry);
    if (log.length > 48) row.chatLog = log.slice(-48);
  }

  function syncChatFromYank(row) {
    if (!row || !row.yank) return;
    if (row.yank.lastUserInput) upsertChatTurn(row, 'user', row.yank.lastUserInput);
    var agentText = agentTextFromYank(row.yank);
    if (agentText) upsertChatTurn(row, 'agent', agentText);
  }

  function syncChatFromSessionFields(row, session) {
    if (!row || !session) return;
    if (session.last_user_input) upsertChatTurn(row, 'user', session.last_user_input);
    if (session.last_assistant) upsertChatTurn(row, 'agent', session.last_assistant);
  }

  function chatMessagesForRender(row) {
    if (!row) return [];
    if (!row.chatLog || !row.chatLog.length) syncChatFromYank(row);
    return row.chatLog || [];
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
    codex: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M9.205 8.658v-2.26c0-.19.072-.333.238-.428l4.543-2.616c.619-.357 1.356-.523 2.117-.523 2.854 0 4.662 2.212 4.662 4.566 0 .167 0 .357-.024.547l-4.71-2.759a.797.797 0 00-.856 0l-5.97 3.473zm10.609 8.8V12.06c0-.333-.143-.57-.429-.737l-5.97-3.473 1.95-1.118a.433.433 0 01.476 0l4.543 2.617c1.309.76 2.189 2.378 2.189 3.948 0 1.808-1.07 3.473-2.76 4.163zM7.802 12.703l-1.95-1.142c-.167-.095-.239-.238-.239-.428V5.899c0-2.545 1.95-4.472 4.591-4.472 1 0 1.927.333 2.712.928L8.23 5.067c-.285.166-.428.404-.428.737v6.898zM12 15.128l-2.795-1.57v-3.33L12 8.658l2.795 1.57v3.33L12 15.128zm1.796 7.23c-1 0-1.927-.332-2.712-.927l4.686-2.712c.285-.166.428-.404.428-.737v-6.898l1.974 1.142c.167.095.238.238.238.428v5.233c0 2.545-1.974 4.472-4.614 4.472zm-5.637-5.303l-4.544-2.617c-1.308-.761-2.188-2.378-2.188-3.948A4.482 4.482 0 014.21 6.327v5.423c0 .333.143.571.428.738l5.947 3.449-1.95 1.118a.432.432 0 01-.476 0zm-.262 3.9c-2.688 0-4.662-2.021-4.662-4.519 0-.19.024-.38.047-.57l4.686 2.71c.286.167.571.167.856 0l5.97-3.448v2.26c0 .19-.07.333-.237.428l-4.543 2.616c-.619.357-1.356.523-2.117.523zm5.899 2.83a5.947 5.947 0 005.827-4.756C22.287 18.339 24 15.84 24 13.296c0-1.665-.713-3.282-1.998-4.448.119-.5.19-.999.19-1.498 0-3.401-2.759-5.947-5.946-5.947-.642 0-1.26.095-1.88.31A5.962 5.962 0 0010.205 0a5.947 5.947 0 00-5.827 4.757C1.713 5.447 0 7.945 0 10.49c0 1.666.713 3.283 1.998 4.448-.119.5-.19 1-.19 1.499 0 3.401 2.759 5.946 5.946 5.946.642 0 1.26-.095 1.88-.309a5.96 5.96 0 004.162 1.713z"/></svg>'
  };

  var PULL_REVEAL_THRESHOLD = 56;
  var pullRevealPx = 0;
  var pullTouchStartY = 0;

  function setPullReveal(px, sticky) {
    if (!newSessionReveal) return;
    pullRevealPx = Math.max(0, Math.min(px, 88));
    var open = sticky || pullRevealPx >= PULL_REVEAL_THRESHOLD;
    newSessionReveal.classList.toggle('open', open);
    newSessionReveal.setAttribute('aria-hidden', open ? 'false' : 'true');
    newSessionReveal.style.setProperty('--pull', pullRevealPx + 'px');
  }

  function wireListPullReveal() {
    if (!listScroll || !newSessionPill) return;

    listScroll.addEventListener('touchstart', function (e) {
      pullTouchStartY = e.touches[0].clientY;
    }, { passive: true });

    listScroll.addEventListener('touchmove', function (e) {
      if (listScroll.scrollTop > 2) return;
      var dy = e.touches[0].clientY - pullTouchStartY;
      if (dy > 0) setPullReveal(dy, false);
    }, { passive: true });

    listScroll.addEventListener('touchend', function () {
      if (pullRevealPx < PULL_REVEAL_THRESHOLD) setPullReveal(0, false);
      else setPullReveal(PULL_REVEAL_THRESHOLD, true);
    });

    listScroll.addEventListener('wheel', function (e) {
      if (listScroll.scrollTop > 2) return;
      if (e.deltaY >= 0) return;
      var next = pullRevealPx + Math.abs(e.deltaY);
      setPullReveal(next, next >= PULL_REVEAL_THRESHOLD);
      e.preventDefault();
    }, { passive: false });

    listScroll.addEventListener('scroll', function () {
      if (listScroll.scrollTop > 2 && pullRevealPx > 0) setPullReveal(0, false);
    });

    newSessionPill.addEventListener('click', function () {
      setPullReveal(0, false);
      openNewSession();
    });
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

  function renderThreadList() {
    var live = liveThreads();
    // Reverse order: newest at the bottom (WhatsApp/chat-style).
    live.sort(function (a, b) { return (a.lastEventAt || 0) - (b.lastEventAt || 0); });
    threadsUl.innerHTML = '';
    if (live.length === 0) {
      emptyHint.classList.remove('hidden');
      emptyHint.textContent = hostInfo.relayConnected === false
        ? 'relay offline — open this app from your Mac relay or reconnect cloud bridge'
        : 'no live sessions — start an agent on your Mac (relay must be running)';
      return;
    }
    emptyHint.classList.add('hidden');
    live.forEach(function (t) {
      var ac = agentClass(t.agent);
      var preview = previewText(t);
      var li = BLK.renderListItem({
        className: 'ig-row agent-' + ac + ' ' + statusBadge(t),
        ariaLabel: displayLabel(t) + ', ' + (t.agent || 'agent') + ', ' + statusBadge(t),
        label: displayLabel(t),
        preview: preview || undefined,
        time: relativeTime(t.lastEventAt),
        avatarHtml: agentIcon(t.agent) || undefined,
        avatarClass: 'agent-' + ac + ' ' + statusBadge(t),
        onClick: function () { openThread(t.id, true); },
        onActivate: function () { openThread(t.id, true); },
      });
      threadsUl.appendChild(li);
    });
    scrollListToBottom();
  }

  function renderCompose() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t || !wChat) return;
    titleEl.textContent = displayLabel(t);
    setComposerEnabled(!t.ended);

    var listening = phoneDictateThread === t.id || dictRec ? listeningPartial : '';
    var thinking = t.busy && !t.ended;

    if (t.ended) {
      wMeta.textContent = displayLabel(t) + ' · ended';
      wMeta.classList.remove('hidden');
      if (!t.chatLog || !t.chatLog.length) {
        upsertChatTurn(t, 'agent', t.yank ? agentTextFromYank(t.yank) : 'session ended');
      }
      BLK.renderChatThread(wChat, chatMessagesForRender(t));
      return;
    }

    syncChatFromYank(t);

    if (thinking) {
      wMeta.textContent = 'thinking…';
      wMeta.classList.remove('hidden');
    } else if (t.yank) {
      wMeta.textContent = CS.metaLine(Object.assign({}, t.yank, { label: displayLabel(t) }));
      wMeta.classList.remove('hidden');
    } else {
      wMeta.textContent = displayLabel(t) + ' · online';
      wMeta.classList.add('hidden');
    }

    BLK.renderChatThread(wChat, chatMessagesForRender(t), {
      thinking: thinking,
      listening: listening,
    });
    renderQuickReplies();
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
    sendDictate('dictate_begin', t.id);
    listeningPartial = '';
    setDictatePhase('listening');
    promptEl.placeholder = 'listening…';
  }

  function finishPhoneDictate(thread, text, enterReview) {
    if (phoneDictateThread !== thread && phoneDictateThread != null) return;
    var wasPhone = phoneDictateThread === thread;
    phoneDictateThread = null;
    listeningPartial = '';
    if (dictateBtn) dictateBtn.classList.remove('recording');
    promptEl.placeholder = 'type your message…';
    if (text) {
      dictateDraft = text.trim();
      promptEl.value = dictateDraft;
      var row = threads[thread];
      if (row) {
        upsertChatTurn(row, 'user', dictateDraft);
        if (row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: dictateDraft });
      }
      if (enterReview !== false) {
        setDictatePhase('review');
        renderCompose();
        renderThreadList();
        return;
      }
      showToast('sent', 'success');
    }
    if (wasPhone && !text) resetDictateUi();
    renderCompose();
    renderThreadList();
  }

  function pauseDictate() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) return;
    stopDictRec(t.id);
    if (phoneDictateThread) {
      sendDictate('dictate_commit', t.id, dictateDraft || listeningPartial || promptEl.value.trim());
      finishPhoneDictate(t.id, dictateDraft || listeningPartial || promptEl.value.trim(), true);
      return;
    }
    var text = (dictateDraft || listeningPartial || promptEl.value || '').trim();
    sendDictate('dictate_commit', t.id, text);
    if (text) {
      dictateDraft = text;
      promptEl.value = text;
      var row = threads[t.id];
      if (row) {
        upsertChatTurn(row, 'user', text);
        if (row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: text });
      }
      setDictatePhase('review');
    } else {
      resetDictateUi();
    }
    renderCompose();
    renderThreadList();
  }

  function startDictate() {
    var t = activeThread ? threads[activeThread] : null;
    if (!t) { showToast('open a session first', 'error'); return; }
    if (!speechAvailable()) {
      startPhoneDictate(t);
      showToast('listening — speak toward the phone', 'success');
      renderCompose();
      return;
    }
    var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    stopDictRec(null);
    phoneDictateThread = null;
    dictateDraft = '';
    var rec = new SR();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = 'en-US';
    sendDictate('dictate_begin', t.id);
    listeningPartial = '';
    setDictatePhase('listening');
    promptEl.placeholder = 'listening…';
    renderCompose();
    rec.onresult = function (e) {
      var interim = '';
      var finalText = '';
      for (var i = e.resultIndex; i < e.results.length; i++) {
        var r = e.results[i];
        if (r.isFinal) finalText += r[0].transcript;
        else interim += r[0].transcript;
      }
      if (finalText.trim()) {
        dictateDraft = (dictateDraft + ' ' + finalText.trim()).trim();
      }
      var live = (dictateDraft + (interim ? ' ' + interim : '')).trim();
      if (live) {
        promptEl.value = live;
        listeningPartial = live;
        sendDictate('dictate_partial', t.id, live);
        renderCompose();
      }
    };
    rec.onerror = function () {
      sendDictate('dictate_abort', t.id);
      resetDictateUi();
      showToast('dictation failed — trying phone mic', 'error');
      startPhoneDictate(t);
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
    if (row) {
      upsertChatTurn(row, 'user', text);
      if (row.yank) row.yank = Object.assign({}, row.yank, { lastUserInput: text });
      row.lastEventAt = clockNow();
      row.busy = true;
    }
    promptEl.value = '';
    resetDictateUi();
    showToast('sent', 'success');
    renderCompose();
    renderThreadList();
  }

  function openThread(id, compose) {
    if (activeThread && activeThread !== id) sendSessionSignal('session_blur', activeThread);
    activeThread = id;
    setUrlForSession(id, !!compose);
    showView('thread');
    sendSessionSignal('session_focus', id);
    renderCompose();
    if (compose) {
      setTimeout(function () { promptEl.focus(); }, 50);
    }
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

  function dictateIntoField(field, btn) {
    if (!speechAvailable()) {
      showToast('speak toward your phone — Ambient Link app must be running', 'error');
      field.focus();
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
    setTimeout(function () { if (newPrompt) newPrompt.focus(); }, 50);
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
    if (isSnoozing()) return;
    var yank = CS.parseYank(msg);
    var row = threadRow(yank.thread);
    if (msg.label) row.label = msg.label;
    if (msg.agent) row.agent = msg.agent;
    row.busy = false;
    row.ended = false;
    row.yank = yank;
    row.lastEventAt = msg.at || clockNow();
    syncChatFromYank(row);
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
        var cloudPeer = !!data.cloud_peer;
        var liveOnHost = data.sessions.some(function (s) { return s.state !== 'DEAD'; });
        // Cloud relay mux can lag behind the laptop peer — don't let stale DEAD
        // snapshots wipe rows we already have from live WS broadcasts.
        if (cloudPeer && !liveOnHost) {
          hostInfo.relayConnected = true;
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
          if (cloudPeer && s.state === 'DEAD' && row.lastEventAt && !row.ended) {
            return;
          }
          if (s.label) row.label = s.label;
          else if (s.agent && s.cwd) row.label = s.agent + ': ' + (s.cwd.split('/').pop() || s.cwd);
          if (s.agent) row.agent = s.agent;
          row.cwd = s.cwd || row.cwd || '';
          row.sessionId = s.session_id || row.sessionId;
          row.deliverable = sessionDeliverable(s.session_id);
          row.busy = s.state === 'BUSY' || s.state === 'STARTING';
          row.ended = s.state === 'DEAD';
          row.lastEventAt = s.last_event_at || row.lastEventAt || clockNow();
          syncChatFromSessionFields(row, s);
        });
        reapDeadThreads();
        hostInfo.relayConnected = true;
        renderThreadList();
        if (activeThread) renderCompose();
      })
      .catch(function () {
        hostInfo.relayConnected = false;
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
    try { ws = new WebSocket(WS_URL); }
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
        started.busy = true;
        started.ended = false;
        started.lastEventAt = msg.at || clockNow();
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
      } else if (msg.type === 'dictate_partial' && activeThread === msg.thread && msg.text) {
        promptEl.value = msg.text;
        if (phoneDictateThread === msg.thread) {
          listeningPartial = msg.text;
          renderCompose();
        }
      } else if (msg.type === 'dictate_end' && activeThread === msg.thread) {
        finishPhoneDictate(msg.thread, msg.text || '', true);
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

  window.addEventListener('pagehide', function () {
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ type: 'companion_ui', screen: 'idle', source: 'web' }));
    }
  });

  // E2E can refresh the agent card without a visible pull button.
  document.addEventListener('ambient-pull-card', function (e) {
    var thread = e.detail && e.detail.thread;
    if (thread) pullCard(thread);
  });

  backBtn.addEventListener('click', closeThreadView);
  newStart.addEventListener('click', startNewThread);
  wireListPullReveal();
  sendBtn.addEventListener('click', function () {
    var text = (dictatePhase === 'review' ? dictateDraft : (promptEl.value || '')).trim();
    if (!text || !activeThread) return;
    sendPrompt(activeThread, text);
  });
  promptEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') {
      e.preventDefault();
      var text = (dictatePhase === 'review' ? dictateDraft : (promptEl.value || '')).trim();
      if (!text || !activeThread) return;
      sendPrompt(activeThread, text);
    }
  });
  dictateBtn.addEventListener('click', function () {
    if (dictatePhase === 'listening') {
      pauseDictate();
      return;
    }
    if (dictatePhase === 'review') return;
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
      }
      renderCompose();
    }
    startDictate();
  });
  if (newDictate) newDictate.addEventListener('click', function () { dictateIntoField(newPrompt, newDictate); });

  pendingDeepLink = parseDeepLink();
  document.querySelectorAll('[data-agent-icon]').forEach(function (n) {
    n.innerHTML = agentIcon(n.getAttribute('data-agent-icon'));
  });
  pickAgent(pickedAgent);
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
