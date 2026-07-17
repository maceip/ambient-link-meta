<script>
  import {
    app, wsConnected, dictateToggle, redoDictate,
    sendPrompt, firstQuickReply, waitingWakeStack, nextWakeHint, switchToNextWake,
  } from '../lib/store.svelte.js';
  import * as CS from '../lib/chipset.js';
  import { displayLabel, chatAgentLabel, chatStatusLabel } from '../lib/format.js';
  import { tap, rbtnGroup } from '../lib/tap.js';

  const t = $derived(app.activeThread ? app.threads[app.activeThread] : null);
  const thinking = $derived(!!(t && t.busy && !t.ended));
  const messages = $derived(t && t.chatLog ? t.chatLog : []);
  const listening = $derived(app.dictate.phase === 'listening');
  const chip = $derived(firstQuickReply());
  const dictateOn = $derived(app.companion.showDictate !== false);
  const dictateDisabled = $derived(!!(t && t.ended) || !dictateOn);
  const connState = $derived(app.conn === 'connecting' ? 'warn' : app.conn);
  // Slot 1: FIFO Switch to oldest waiting session, or Back → list when empty.
  // Hardware Back (Neural Band / temple → Escape) always returns to the list.
  const wakeWaiting = $derived(waitingWakeStack());
  const wakeNext = $derived(nextWakeHint());
  const wakeNextLabel = $derived.by(() => {
    if (!wakeNext) return '';
    const row = app.threads[wakeNext.thread];
    return row ? displayLabel(row) : wakeNext.thread;
  });
  const wakeBtnLabel = $derived.by(() => {
    if (!wakeNext) return 'Back';
    const n = wakeWaiting.length;
    return n > 1 ? `Switch · ${n}` : 'Switch';
  });
  const wakeBtnAria = $derived(
    wakeNext
      ? ('switch to waiting session' + (wakeNextLabel ? ': ' + wakeNextLabel : ''))
      : 'back to sessions',
  );
  const wakeBtnTitle = $derived.by(() => {
    if (!wakeNext) return 'Back to sessions';
    const n = wakeWaiting.length;
    return n > 1
      ? `Next waiting: ${wakeNextLabel} (${n} waiting)`
      : `Next waiting: ${wakeNextLabel}`;
  });

  /* The meta strip earns its row only when it says something the chat bubbles
     don't: a broken relay or an agent waiting on the human. */
  const metaText = $derived.by(() => {
    if (!t) return '';
    if (t.ended) return displayLabel(t) + ' · ended';
    if (!wsConnected()) return 'Not connected — messages will not send until relay reconnects';
    if (!thinking && t.yank &&
        (t.yank.awaiting === CS.Awaiting.PERMISSION || t.yank.awaiting === CS.Awaiting.QUESTION)) {
      return CS.metaLine({ ...t.yank, label: displayLabel(t) });
    }
    return '';
  });

  let wChat = $state(null);

  /** Always glued to the newest message — user or agent. Second pass on the
      next frame catches late layout growing the log. */
  $effect(() => {
    void messages.length;
    void thinking;
    void t;
    if (!wChat) return;
    wChat.scrollTop = wChat.scrollHeight;
    requestAnimationFrame(() => {
      if (wChat) wChat.scrollTop = wChat.scrollHeight;
    });
  });

  function showLabelFor(i) {
    return i === 0 || messages[i - 1].role !== messages[i].role;
  }

  function bubbleTime(at) {
    if (!at) return '';
    return new Date(at).toLocaleTimeString([], {
      hour: 'numeric', minute: '2-digit', second: '2-digit',
    });
  }

  function sendChip() {
    if (!app.activeThread || !chip || !chip.text) return;
    sendPrompt(app.activeThread, chip.text);
  }
</script>

<!-- SESSION COMPOSE — same BEM classes as legacy markup. -->
<section id="view-thread" class="view blk-agent-screen"
         class:hidden={app.view !== 'thread'}
         class:dictate-live={listening}>
  <!-- Full-viewport listening frame: state must be obvious beyond the red
       button ring. Soft salmon neon edge + pulse while SODA is capturing. -->
  <div class="dictate-frame" class:hidden={!listening} aria-hidden="true"></div>
  <div id="thread-conn" class="thread-conn {connState}" role="status" aria-live="polite">
    <span class="thread-conn-dot" aria-hidden="true"></span>
    <span id="thread-conn-label" class="thread-conn-label">
      {connState === 'on' ? 'Connected' : (connState === 'warn' ? 'Connecting…' : 'Not connected')}
    </span>
  </div>
  <header class="thread-hdr">
    <span id="t-title" class="title hdr-title">{t ? displayLabel(t) : 'loading…'}</span>
  </header>
  <div class="compose-wrap blk-agent-view">
    <p id="w-meta" class="widget-meta blk-agent-card__meta" class:hidden={!metaText}>{metaText}</p>
    <!-- Read-only log: pointer-events none in CSS; always snapped to newest. -->
    <div id="w-chat" class="blk-chat-thread compose-chat" role="log" aria-live="off" bind:this={wChat}>
      {#if !messages.length && !thinking}
        <div class="blk-chat-empty">
          {t && t.ended ? 'Session ended with no messages.' : 'No messages yet.'}
        </div>
      {/if}
      {#each messages as m, i}
        <div class="blk-chat-row blk-chat-row--{m.role}"
             class:blk-chat-row--follow={!showLabelFor(i)}>
          <div class="blk-chat-stack">
            {#if showLabelFor(i)}
              <div class="blk-chat-bubble__label">
                {m.role === 'user' ? 'You' : chatAgentLabel(t && t.agent)}
              </div>
            {/if}
            <div class="blk-chat-bubble blk-chat-bubble--{m.role}">
              <div class="blk-chat-bubble__text">{m.text}</div>
              {#if m.at}
                <div class="blk-chat-bubble__time">{bubbleTime(m.at)}</div>
              {/if}
            </div>
            <!-- Honest per-message lifecycle under user bubbles, driven only by
                 relay input_status frames. Landed is the only "seen by agent". -->
            {#if m.role === 'user' && m.status}
              <div class="blk-chat-status blk-chat-status--{m.status}">
                {chatStatusLabel(m.status, m.error)}
              </div>
            {/if}
          </div>
        </div>
      {/each}
      {#if thinking}
        <div class="blk-chat-row blk-chat-row--agent blk-chat-row--thinking"
             class:blk-chat-row--follow={messages.length > 0 && messages[messages.length - 1].role === 'agent'}>
          <div class="blk-chat-stack">
            {#if !messages.length || messages[messages.length - 1].role !== 'agent'}
              <div class="blk-chat-bubble__label">{chatAgentLabel(t && t.agent)}</div>
            {/if}
            <div class="blk-chat-bubble blk-chat-bubble--agent blk-chat-bubble--thinking">
              <div class="blk-chat-bubble__text">thinking…</div>
            </div>
          </div>
        </div>
      {/if}
    </div>
  </div>
  <div id="dictate-chrome" class="dictate-chrome" class:hidden={!listening} aria-live="polite">
    <div id="dictate-status" class="dictate-status" class:hidden={!listening}>
      <span class="dictate-cursor" aria-hidden="true"></span>
      <span id="dictate-status-text">{app.dictate.partial || ''}</span>
    </div>
  </div>
  <!-- Action row ≤3: Switch/Back · Dictate · chip.
       Hardware Escape always → list; this button Switch (FIFO) or Back → list. -->
  <nav id="thread-actions" class="thread-actions new-actions blk-form-view__actions"
       class:dictate-listening={listening}
       role="toolbar" aria-label="session actions">
    <div class="rbtn-row blk-rbtn-row" id="thread-rbtn-row" use:rbtnGroup>
      <button type="button" id="wake-switch" class="rbtn focusable rbtn-stroke"
              class:rbtn-wake={!!wakeNext}
              class:hidden={listening}
              aria-label={wakeBtnAria}
              title={wakeBtnTitle}
              use:tap onclick={() => switchToNextWake()}>
        <span class="rbtn-pill">
          <span class="rbtn-icon" aria-hidden="true">
            {#if wakeNext}
              <svg viewBox="0 0 24 24" fill="none"><path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"/></svg>
            {:else}
              <svg viewBox="0 0 24 24" fill="none"><path d="M15 5.5 8 12l7 6.5" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"/></svg>
            {/if}
          </span>
          <span class="rbtn-label">{wakeBtnLabel}</span>
        </span>
      </button>
      <button type="button" id="dictate-redo" class="rbtn focusable rbtn-stroke"
              class:hidden={!listening} aria-label="redo dictation"
              use:tap onclick={redoDictate}>
        <span class="rbtn-pill"><span class="rbtn-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M4 12a8 8 0 0 1 13.7-5.7M20 4v5h-5" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="rbtn-label">Redo</span></span>
      </button>
      <button type="button" id="dictate" class="rbtn focusable rbtn-stroke"
              class:recording={listening} class:rbtn-active={listening}
              disabled={dictateDisabled}
              style:display={dictateOn ? '' : 'none'}
              title={listening ? 'Listening — tap to send now' : 'Dictate'}
              aria-label={listening ? 'done — send' : 'dictate'}
              use:tap onclick={() => dictateToggle()}>
        <span class="rbtn-pill">
          <span class="rbtn-icon rbtn-icon--dictate" aria-hidden="true">
            <svg class="icon-mic" class:hidden={listening} viewBox="0 0 24 24" fill="none"><rect x="9" y="3.5" width="6" height="11" rx="3" stroke="currentColor" stroke-width="1.9"/><path d="M5 11.5a7 7 0 0 0 14 0" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/><path d="M12 18.5v3M8 21.5h8" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/></svg>
            <svg class="icon-wave" class:hidden={!listening} viewBox="0 0 24 24" fill="none"><path d="M4 10v4M7 8v8M10 6v12M13 9v6M16 7v10M19 10v4" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/></svg>
          </span>
          <span class="rbtn-label">{listening ? 'Done' : 'Dictate'}</span>
        </span>
      </button>
      <span id="quick-replies" class="thread-user-chips" role="presentation"
            class:hidden={!chip || app.view !== 'thread' || listening}>
        {#if chip}
          <button type="button"
                  class="quick-reply-pill focusable"
                  class:quick-reply-pill--primary={chip.primary}
                  use:tap onclick={sendChip}>{chip.label || chip.text}</button>
        {/if}
      </span>
    </div>
  </nav>
</section>
