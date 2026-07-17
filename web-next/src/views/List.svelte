<script>
  import {
    app, listThreads, openThread, openNewSession, wsConnected, connectionCopy,
    statusBadge, listConnectionDot, listPreviewText, emptyHintText, isSnoozing,
    liveThreads, clockNow, waitingWakeStack, nextWakeHint, switchToNextWake,
  } from '../lib/store.svelte.js';
  import {
    folderTitle, agentClass, agentStatusLabel, listTimeLabel, relativeTime, zincIconFor,
    displayLabel,
  } from '../lib/format.js';
  import { agentIcon } from '../lib/icons.js';
  import { tap } from '../lib/tap.js';

  const rows = $derived(listThreads());
  const connState = $derived(app.conn === 'connecting' ? 'warn' : app.conn);
  const liveCount = $derived(app.host.liveSessionCount || liveThreads().length);
  const showCount = $derived(app.conn === 'on' && liveCount > 0);
  const wakeWaiting = $derived(waitingWakeStack());
  const wakeNext = $derived(nextWakeHint());
  const wakeSwitchText = $derived.by(() => {
    if (!wakeNext) return '';
    const row = app.threads[wakeNext.thread];
    const name = row ? displayLabel(row) : wakeNext.thread;
    const short = name.length > 18 ? name.slice(0, 16) + '…' : name;
    const n = wakeWaiting.length;
    return n > 1 ? `Switch · ${short} · ${n}` : `Switch · ${short}`;
  });

  function rowAria(t) {
    return folderTitle(t) + ', ' + (t.agent || 'agent') + ', ' + agentStatusLabel(statusBadge(t));
  }

  function rowTime(t) {
    return listTimeLabel(t.lastEventAt, clockNow()) || relativeTime(t.lastEventAt, clockNow());
  }

  function open(t) {
    app.listFocusedThreadId = t.id;
    openThread(t.id, true);
  }
</script>

<!-- THREAD LIST — Instagram/DMs-style shell; same BEM classes as legacy so
     companion.css / blocks.css apply unchanged. -->
<section id="view-threads" class="view blk-shelf-list-view" class:hidden={app.view !== 'list'}>
  <span id="relay-badge" class="relay-badge" class:hidden={!app.host.relayDebug}
        title="relay debug — auto cards suppressed">debug</span>
  <!-- Glasses vertical economy: chrome is ONE header line so ≥3 session cards
       stay visible. The banner only earns a row when something is wrong. -->
  <header class="list-hdr blk-shelf-list-view__hdr">
    <span class="list-title blk-shelf-list-view__title">sessions</span>
    <span class="list-hdr-right">
      <span id="conn-count" class="conn-count" class:hidden={!showCount} aria-hidden="true">
        {showCount ? liveCount + ' live' : ''}
      </span>
      <span id="conn-dot" class="conn-dot {connState}" aria-hidden="true"></span>
    </span>
  </header>
  <div id="conn-status" class="conn-status {connState}" class:hidden={connState === 'on'}
       role="status" aria-live="polite">
    <span id="conn-label" class="conn-label">{connectionCopy()}</span>
  </div>
  <!-- Waiting sessions that pinged while we were elsewhere (FIFO Switch). -->
  <div id="wake-switch-bar-list" class="wake-switch-bar" class:hidden={!wakeNext}>
    <button type="button" id="wake-switch-list" class="wake-switch-pill focusable"
            aria-label="switch to waiting session"
            use:tap onclick={() => switchToNextWake()}>
      <span class="wake-switch-pill__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none"><path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </span>
      <span class="wake-switch-pill__label">{wakeSwitchText}</span>
    </button>
  </div>
  <div id="list-scroll" class="list-scroll blk-shelf-list-view__scroll">
    <!-- Permanent first row: pull-to-reveal hid the only create affordance. -->
    <div id="new-session-reveal" class="new-session-reveal">
      <button type="button" id="new-session-pill" class="compose-pill focusable"
              aria-label="new session" use:tap onclick={openNewSession}>
        <span class="compose-pill__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none"><rect x="4" y="4" width="16" height="16" rx="4" stroke="currentColor" stroke-width="1.75"/><path d="M8 12h8M12 8v8" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"/></svg>
        </span>
        <span class="compose-pill__label">New session</span>
      </button>
    </div>
    <div id="list-body" class="list-body">
      <p id="empty-hint" class="empty-hint blk-shelf-list-view__empty"
         class:hidden={rows.length > 0}>{emptyHintText()}</p>
      <div id="threads" class="thread-list dm-list blk-shelf-list-view__list" role="list">
        {#each rows as t (t.id)}
          {@const ac = agentClass(t.agent)}
          {@const zinc = zincIconFor(t.agent)}
          <button type="button"
                  class="blk-list-item thread-row list-item focusable dm-row agent-{ac} {statusBadge(t)}"
                  class:session-offline={!wsConnected()}
                  data-thread-id={t.id}
                  role="listitem"
                  aria-label={rowAria(t)}
                  use:tap
                  onclick={() => open(t)}>
            <div class="blk-list-item__avatar avatar agent-{ac}">
              {#if zinc}
                <img class="avatar-zinc" src={zinc} alt="" draggable="false">
              {:else}
                {@html agentIcon(ac) || (folderTitle(t) || '?').charAt(0).toUpperCase()}
              {/if}
            </div>
            <div class="blk-list-item__body thread-body">
              <div class="blk-list-item__top thread-top">
                <span class="blk-list-item__label thread-label">{folderTitle(t)}</span>
                <span class="blk-list-item__time thread-time">{rowTime(t)}</span>
              </div>
              <div class="blk-list-item__bottom thread-bottom">
                <div class="blk-list-item__preview preview body-preview">
                  {listPreviewText(t) || 'Waiting for agent…'}
                </div>
                <div class="blk-list-item__meta thread-meta">
                  {#if isSnoozing()}
                    <span class="thread-mute-icon" aria-label="snoozed">
                      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 4.5c-4.1 0-7.5 3.4-7.5 7.5v3.8l-1.7 1.7a1 1 0 0 0 .7 1.7h17a1 1 0 0 0 .7-1.7L19.5 15.8V12c0-4.1-3.4-7.5-7.5-7.5Z" stroke="currentColor" stroke-width="1.5"/><path d="M10 20a2 2 0 0 0 4 0" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><path d="m4 4 16 16" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
                    </span>
                  {/if}
                  <span class="thread-conn-dot thread-conn-dot--{listConnectionDot(t)}"
                        aria-label={listConnectionDot(t)}></span>
                </div>
              </div>
            </div>
          </button>
        {/each}
      </div>
    </div>
  </div>
</section>
