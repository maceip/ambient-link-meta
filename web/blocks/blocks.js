// Ambient Link UI blocks — reusable DOM builders + behavior (see BLOCKS.md).
(function (root) {
  'use strict';

  var AUTO_ADVANCE_SECS = 5;

  var MIC_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<rect x="9" y="4" width="6" height="10" rx="3" stroke="currentColor" stroke-width="1.75"/>' +
    '<path d="M5 11a7 7 0 0 0 14 0" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"/>' +
    '<path d="M12 18v3M8 21h8" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"/>' +
    '</svg>';

  var WAVE_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<path d="M4 10v4M7 8v8M10 6v12M13 9v6M16 7v10M19 10v4" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"/>' +
    '</svg>';

  var CHEVRON_LEFT_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<path d="M15 5.5 8 12l7 6.5" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>';

  var SEND_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z" stroke="currentColor" stroke-width="1.75" stroke-linejoin="round"/>' +
    '</svg>';

  var REDO_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<path d="M4 12a8 8 0 0 1 13.7-5.7M20 4v5h-5" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>';

  var PAUSE_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<path d="M9 7v10M15 7v10" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"/>' +
    '</svg>';

  var PLAY_SVG =
    '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
    '<path d="M8 6v12l10-6-10-6Z" stroke="currentColor" stroke-width="1.75" stroke-linejoin="round"/>' +
    '</svg>';

  /** Append partial dictation under agent body — mirrors HudWidgets.dictateCardBody. */
  function bodyWithListening(baseBody, partial) {
    var base = (baseBody || '').trim();
    var userLine = (partial || '').trim() || 'listening…';
    if (!base) return 'You: ' + userLine;
    return base + '\n\nYou: ' + userLine;
  }

  function wireRbtnGroups(scope) {
    var rootEl = scope && scope.querySelectorAll ? scope : document;
    rootEl.querySelectorAll('.blk-rbtn-row, .rbtn-row').forEach(function (group) {
      var btns = group.querySelectorAll('.blk-rbtn, .rbtn');
      if (!btns.length) return;
      function setActive(active) {
        btns.forEach(function (b) {
          b.classList.toggle('blk-rbtn--active', b === active);
          b.classList.toggle('rbtn-active', b === active);
        });
      }
      btns.forEach(function (btn) {
        btn.addEventListener('focus', function () { setActive(btn); });
        btn.addEventListener('blur', function () {
          requestAnimationFrame(function () {
            if (!group.contains(document.activeElement)) setActive(null);
          });
        });
      });
    });
  }

  /** Glasses browsers focus on first tap and click on second, which reads as
      "I have to double-tap everything" plus click-delay lag. Synthesize the
      click on touchend instead — but ONLY for a true tap (finger didn't move,
      short press), so scrolling across a card never opens it and never loses
      momentum to preventDefault. */
  function wireImmediateTap(scope) {
    var rootEl = scope && scope.querySelectorAll ? scope : document;
    rootEl.querySelectorAll('.rbtn, .quick-reply-pill, .thread-row, .compose-pill, .theme-chip').forEach(function (el) {
      if (el.dataset.tapWired) return;
      el.dataset.tapWired = '1';
      var startX = 0, startY = 0, startedAt = 0;
      el.addEventListener('touchstart', function (e) {
        var t = e.touches[0];
        startX = t.clientX;
        startY = t.clientY;
        startedAt = Date.now();
      }, { passive: true });
      el.addEventListener('touchend', function (e) {
        if (el.disabled) return;
        var t = e.changedTouches && e.changedTouches[0];
        if (!t) return;
        var moved = Math.hypot(t.clientX - startX, t.clientY - startY);
        if (moved > 12 || Date.now() - startedAt > 700) return; // scroll or hold — not a tap
        e.preventDefault();
        el.click();
      }, { passive: false });
    });
  }

  /** Round HUD pill button (icon + expanding label on focus). */
  function renderRbtn(opts) {
    opts = opts || {};
    var btn = document.createElement('button');
    btn.type = 'button';
    var cls = ['blk-rbtn', 'rbtn', 'focusable'];
    if (opts.primary) cls.push('blk-rbtn--primary', 'rbtn-primary');
    if (opts.recording) cls.push('blk-rbtn--recording', 'recording');
    if (opts.className) cls.push(opts.className);
    btn.className = cls.join(' ');
    if (opts.id) btn.id = opts.id;
    if (opts.title) btn.title = opts.title;
    if (opts.ariaLabel) btn.setAttribute('aria-label', opts.ariaLabel);
    if (opts.onClick) btn.addEventListener('click', opts.onClick);

    var pill = document.createElement('span');
    pill.className = 'blk-rbtn__pill rbtn-pill';

    var icon = document.createElement('span');
    icon.className = 'blk-rbtn__icon rbtn-icon';
    icon.setAttribute('aria-hidden', 'true');
    if (opts.iconHtml) icon.innerHTML = opts.iconHtml;
    else if (opts.icon) icon.textContent = opts.icon;

    var label = document.createElement('span');
    label.className = 'blk-rbtn__label rbtn-label';
    label.textContent = opts.label || '';

    pill.appendChild(icon);
    pill.appendChild(label);
    btn.appendChild(pill);
    return btn;
  }

  function renderRbtnRow(buttons, opts) {
    opts = opts || {};
    var row = document.createElement('div');
    row.className = 'blk-rbtn-row rbtn-row';
    if (opts.className) row.className += ' ' + opts.className;
    (buttons || []).forEach(function (b) { row.appendChild(renderRbtn(b)); });
    return row;
  }

  /** Meta glasses DMs list card (WhatsApp / Instagram shared pattern). */
  function renderListItem(data) {
    data = data || {};
    var li = document.createElement('button');
    li.type = 'button';
    li.className = 'blk-list-item thread-row list-item focusable' +
      (data.className ? ' ' + data.className : '');
    li.setAttribute('role', 'listitem');
    if (data.threadId) li.dataset.threadId = data.threadId;
    if (data.ariaLabel) li.setAttribute('aria-label', data.ariaLabel);

    var av = document.createElement('div');
    av.className = 'blk-list-item__avatar avatar' + (data.avatarClass ? ' ' + data.avatarClass : '');
    if (data.avatarHtml) av.innerHTML = data.avatarHtml;
    else av.textContent = (data.label || '?').charAt(0).toUpperCase();

    var body = document.createElement('div');
    body.className = 'blk-list-item__body thread-body';

    var top = document.createElement('div');
    top.className = 'blk-list-item__top thread-top';

    var label = document.createElement('span');
    label.className = 'blk-list-item__label thread-label';
    label.textContent = data.label || '';
    top.appendChild(label);

    var time = document.createElement('span');
    time.className = 'blk-list-item__time thread-time';
    time.textContent = data.time || '';
    top.appendChild(time);
    body.appendChild(top);

    var bottom = document.createElement('div');
    bottom.className = 'blk-list-item__bottom thread-bottom';

    if (data.preview) {
      var preview = document.createElement('div');
      preview.className = 'blk-list-item__preview preview body-preview';
      preview.textContent = data.preview;
      bottom.appendChild(preview);
    }

    if (data.muted || data.connectionState) {
      var meta = document.createElement('div');
      meta.className = 'blk-list-item__meta thread-meta';
      if (data.muted) {
        var mute = document.createElement('span');
        mute.className = 'thread-mute-icon';
        mute.setAttribute('aria-label', 'snoozed');
        mute.innerHTML = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 4.5c-4.1 0-7.5 3.4-7.5 7.5v3.8l-1.7 1.7a1 1 0 0 0 .7 1.7h17a1 1 0 0 0 .7-1.7L19.5 15.8V12c0-4.1-3.4-7.5-7.5-7.5Z" stroke="currentColor" stroke-width="1.5"/><path d="M10 20a2 2 0 0 0 4 0" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><path d="m4 4 16 16" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
        meta.appendChild(mute);
      }
      if (data.connectionState) {
        var dot = document.createElement('span');
        dot.className = 'thread-conn-dot thread-conn-dot--' + data.connectionState;
        dot.setAttribute('aria-label', data.connectionState);
        meta.appendChild(dot);
      }
      bottom.appendChild(meta);
    }

    body.appendChild(bottom);

    li.appendChild(av);
    li.appendChild(body);

    if (data.onClick) li.addEventListener('click', data.onClick);
    if (data.onActivate) {
      li.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          data.onActivate(e);
        }
      });
    }
    return li;
  }

  function renderFormField(field) {
    field = field || {};
    var wrap = document.createElement('div');
    wrap.className = 'blk-form-field';

    var lbl = document.createElement('label');
    lbl.className = 'blk-form-field__label field-label';
    lbl.setAttribute('for', field.id || '');
    lbl.textContent = field.label || '';

    var input;
    if (field.type === 'textarea') {
      input = document.createElement('textarea');
      input.rows = field.rows || 5;
    } else {
      input = document.createElement('input');
      input.type = field.type || 'text';
    }
    input.id = field.id || '';
    input.className = 'blk-form-field__input text-input focusable';
    if (field.placeholder) input.placeholder = field.placeholder;
    if (field.value != null) input.value = field.value;

    wrap.appendChild(lbl);
    wrap.appendChild(input);
    wrap.input = input;
    return wrap;
  }

  /** Agent card shell: meta + body + action chips. Pass existing nodes or get new ones. */
  function agentActionCard(opts) {
    opts = opts || {};
    var meta = opts.metaEl || document.createElement('p');
    var body = opts.bodyEl || document.createElement('div');
    var actions = opts.actionsEl || document.createElement('div');

    if (!opts.metaEl) meta.className = 'blk-agent-card__meta widget-meta';
    if (!opts.bodyEl) {
      body.className = 'blk-agent-card__body widget-card hud-card';
    }
    if (!opts.actionsEl) {
      actions.className = 'blk-agent-card__actions widget-chips';
      actions.setAttribute('role', 'group');
      actions.setAttribute('aria-label', opts.actionsLabel || 'quick actions');
    }

    if (opts.meta != null) meta.textContent = opts.meta;
    if (opts.body != null) body.textContent = opts.body;

    return { meta: meta, body: body, actions: actions };
  }

  function showListeningCard(bodyEl, agentBody, partial) {
    if (!bodyEl) return;
    bodyEl.classList.add('blk-listening-card');
    bodyEl.textContent = bodyWithListening(agentBody, partial);
  }

  function clearListeningCard(bodyEl, agentBody) {
    if (!bodyEl) return;
    bodyEl.classList.remove('blk-listening-card');
    if (agentBody != null) bodyEl.textContent = agentBody;
  }

  /** WhatsApp-style chat bubble. role: user | agent */
  function renderChatBubble(opts) {
    opts = opts || {};
    var wrap = document.createElement('div');
    wrap.className = 'blk-chat-row blk-chat-row--' + (opts.role || 'agent');
    if (opts.thinking) wrap.classList.add('blk-chat-row--thinking');
    if (opts.listening) wrap.classList.add('blk-chat-row--listening');

    var stack = document.createElement('div');
    stack.className = 'blk-chat-stack';

    if (opts.showLabel !== false) {
      var label = document.createElement('div');
      label.className = 'blk-chat-bubble__label';
      label.textContent = opts.role === 'user'
        ? 'You'
        : (opts.agentLabel || 'Agent');
      stack.appendChild(label);
    }

    var bubble = document.createElement('div');
    bubble.className = 'blk-chat-bubble blk-chat-bubble--' + (opts.role || 'agent');
    if (opts.truncated) bubble.classList.add('blk-chat-bubble--truncated');
    if (opts.thinking) bubble.classList.add('blk-chat-bubble--thinking');
    if (opts.listening) bubble.classList.add('blk-chat-bubble--listening');

    var text = document.createElement('div');
    text.className = 'blk-chat-bubble__text';
    text.textContent = opts.text || '';
    bubble.appendChild(text);

    // No "collapsed for display" note rows: vertical space on the waveguide is
    // too scarce to spend on meta-commentary. Truncation shows as a plain "…".

    if (opts.at) {
      var timeEl = document.createElement('div');
      timeEl.className = 'blk-chat-bubble__time';
      var d = new Date(opts.at);
      timeEl.textContent = d.toLocaleTimeString([], {
        hour: 'numeric',
        minute: '2-digit',
        second: '2-digit',
      });
      bubble.appendChild(timeEl);
    }

    stack.appendChild(bubble);

    // Honest per-message lifecycle under user bubbles, driven only by relay
    // input_status frames: sending/offline → accepted/queued/delivered →
    // landed | failed. Landed is the only "seen by agent" proof.
    if (opts.role === 'user' && opts.status) {
      var statusEl = document.createElement('div');
      statusEl.className = 'blk-chat-status blk-chat-status--' + opts.status;
      statusEl.textContent = chatStatusLabel(opts.status, opts.error);
      stack.appendChild(statusEl);
    }

    wrap.appendChild(stack);
    return wrap;
  }

  function chatStatusLabel(status, error) {
    switch (status) {
      case 'sending': return 'sending…';
      case 'offline': return 'waiting for connection';
      case 'accepted': return 'relay accepted';
      case 'queued': return 'queued for agent';
      case 'delivered': return 'delivered';
      case 'landed': return '✓ landed';
      case 'failed': return 'failed' + (error ? ' — ' + error : '');
      default: return status;
    }
  }

  function renderChatThread(container, messages, opts) {
    if (!container) return;
    opts = opts || {};
    container.innerHTML = '';
    var agentLabel = opts.agentLabel || 'Agent';
    var prevRole = null;
    var list = messages || [];
    if (!list.length && !opts.thinking) {
      var empty = document.createElement('div');
      empty.className = 'blk-chat-empty';
      empty.textContent = opts.emptyText || 'No messages yet.';
      container.appendChild(empty);
    }
    list.forEach(function (m) {
      var row = renderChatBubble(Object.assign({}, m, {
        agentLabel: agentLabel,
        showLabel: prevRole !== m.role,
      }));
      if (prevRole === m.role) row.classList.add('blk-chat-row--follow');
      prevRole = m.role;
      container.appendChild(row);
    });
    if (opts.thinking) {
      container.appendChild(renderChatBubble({
        role: 'agent',
        text: 'thinking…',
        thinking: true,
        agentLabel: agentLabel,
        showLabel: prevRole !== 'agent',
      }));
    }
  }

  function renderActionChip(chip, handlers) {
    handlers = handlers || {};
    var btn = document.createElement('button');
    btn.type = 'button';
    var style = chip.chipStyle || chip.style || 'outline';
    btn.className = 'blk-action-chip chip focusable chip-' + style +
      (chip.countdown ? ' blk-action-chip--countdown chip-countdown' : '');
    btn.textContent = chip.label || chip.text || '';
    btn.addEventListener('click', function () {
      if (chip.kind === 'dictate' && handlers.onDictate) handlers.onDictate(chip);
      else if (chip.kind === 'modify' && handlers.onModify) handlers.onModify(chip);
      else if (handlers.onSend) handlers.onSend(chip);
      else if (handlers.onClick) handlers.onClick(chip);
    });
    return btn;
  }

  /** Render primary + dictate chips; optional 5s countdown on first send chip (done cards). */
  function renderAgentActions(container, chips, handlers, countdownOpts) {
    if (!container) return null;
    container.innerHTML = '';
    var cancelCountdown = null;
    var primaryBtn = null;

    (chips || []).forEach(function (c) {
      var btn = renderActionChip(c, handlers);
      if (c.kind === 'send' && !primaryBtn) primaryBtn = btn;
      container.appendChild(btn);
    });

    if (countdownOpts && countdownOpts.enabled && primaryBtn) {
      cancelCountdown = armPrimaryCountdown(primaryBtn, countdownOpts);
    }
    return cancelCountdown;
  }

  /** Mirrors HudPresenter.armAutoAdvance — ticks label "continue · Ns" then fires onComplete. */
  function armPrimaryCountdown(primaryBtn, opts) {
    opts = opts || {};
    var seconds = opts.seconds != null ? opts.seconds : AUTO_ADVANCE_SECS;
    var baseLabel = opts.baseLabel || primaryBtn.textContent.replace(/\s+·\s+\d+s$/, '').trim();
    var remaining = seconds;
    var timer = null;
    var cancelled = false;

    function tick() {
      if (cancelled) return;
      if (opts.shouldCancel && opts.shouldCancel()) {
        cancel();
        return;
      }
      primaryBtn.textContent = baseLabel + ' · ' + remaining + 's';
      if (remaining <= 0) {
        timer = null;
        if (opts.onComplete) opts.onComplete();
        return;
      }
      remaining--;
      timer = setTimeout(tick, 1000);
    }

    function cancel() {
      cancelled = true;
      if (timer) clearTimeout(timer);
      timer = null;
      primaryBtn.textContent = baseLabel;
    }

    primaryBtn.textContent = baseLabel + ' · ' + remaining + 's';
    timer = setTimeout(function () {
      remaining--;
      tick();
    }, 1000);
    return cancel;
  }

  root.AmbientBlocks = {
    AUTO_ADVANCE_SECS: AUTO_ADVANCE_SECS,
    MIC_SVG: MIC_SVG,
    WAVE_SVG: WAVE_SVG,
    CHEVRON_LEFT_SVG: CHEVRON_LEFT_SVG,
    SEND_SVG: SEND_SVG,
    REDO_SVG: REDO_SVG,
    PAUSE_SVG: PAUSE_SVG,
    PLAY_SVG: PLAY_SVG,
    bodyWithListening: bodyWithListening,
    wireRbtnGroups: wireRbtnGroups,
    wireImmediateTap: wireImmediateTap,
    renderRbtn: renderRbtn,
    renderRbtnRow: renderRbtnRow,
    renderListItem: renderListItem,
    renderFormField: renderFormField,
    agentActionCard: agentActionCard,
    showListeningCard: showListeningCard,
    clearListeningCard: clearListeningCard,
    renderChatBubble: renderChatBubble,
    renderChatThread: renderChatThread,
    renderActionChip: renderActionChip,
    renderAgentActions: renderAgentActions,
    armPrimaryCountdown: armPrimaryCountdown,
  };
})(typeof window !== 'undefined' ? window : globalThis);
