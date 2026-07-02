// Ambient Link UI blocks — reusable DOM builders + behavior (see BLOCKS.md).
(function (root) {
  'use strict';

  var AUTO_ADVANCE_SECS = 5;

  var MIC_SVG =
    '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">' +
    '<path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5-3c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>' +
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

  /** WhatsApp-style list card row. */
  function renderListItem(data) {
    data = data || {};
    var li = document.createElement('button');
    li.type = 'button';
    li.className = 'blk-list-item thread-row list-item focusable' +
      (data.className ? ' ' + data.className : '');
    li.setAttribute('role', 'listitem');
    if (data.ariaLabel) li.setAttribute('aria-label', data.ariaLabel);

    var av = document.createElement('div');
    av.className = 'blk-list-item__avatar avatar' + (data.avatarClass ? ' ' + data.avatarClass : '');
    if (data.avatarHtml) av.innerHTML = data.avatarHtml;
    else av.textContent = (data.label || '?').charAt(0).toUpperCase();

    var body = document.createElement('div');
    body.className = 'blk-list-item__body thread-body';

    var name = document.createElement('div');
    name.className = 'blk-list-item__name name';
    var label = document.createElement('span');
    label.className = 'blk-list-item__label thread-label';
    label.textContent = data.label || '';
    name.appendChild(label);
    if (data.badge) {
      var badge = document.createElement('span');
      badge.className = data.badgeClass || 'status-tag';
      badge.textContent = data.badge;
      name.appendChild(badge);
    }
    body.appendChild(name);

    if (data.preview) {
      var preview = document.createElement('div');
      preview.className = 'blk-list-item__preview preview body-preview';
      preview.textContent = data.preview;
      body.appendChild(preview);
    }

    var time = document.createElement('div');
    time.className = 'blk-list-item__time thread-time';
    time.textContent = data.time || '';

    li.appendChild(av);
    li.appendChild(body);
    li.appendChild(time);

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
    bodyWithListening: bodyWithListening,
    wireRbtnGroups: wireRbtnGroups,
    renderRbtn: renderRbtn,
    renderRbtnRow: renderRbtnRow,
    renderListItem: renderListItem,
    renderFormField: renderFormField,
    agentActionCard: agentActionCard,
    showListeningCard: showListeningCard,
    clearListeningCard: clearListeningCard,
    renderActionChip: renderActionChip,
    renderAgentActions: renderAgentActions,
    armPrimaryCountdown: armPrimaryCountdown,
  };
})(typeof window !== 'undefined' ? window : globalThis);
