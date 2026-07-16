<script>
  import { onMount } from 'svelte';
  import {
    app, init, saveListSnapshot, closeThreadView, closeNewSessionView,
  } from './lib/store.svelte.js';
  import {
    wireDpadNavigation, focusLastListRow, focusSessionPrimary, focusNewPrimary,
  } from './lib/focus.js';
  import { loadString } from './lib/persist.js';
  import { KEYS } from './lib/persist.js';
  import List from './views/List.svelte';
  import Thread from './views/Thread.svelte';
  import New from './views/New.svelte';

  const THEMES = ['meta', 'dracula', 'tokyo-night', 'catppuccin', 'nord'];

  let toastVisible = $state(false);
  let toastTimer = null;

  onMount(() => {
    // Contract: themes.css keys off data-theme on <html> (no picker chrome —
    // a previously saved theme still applies).
    const saved = loadString(KEYS.theme);
    document.documentElement.dataset.theme = THEMES.includes(saved) ? saved : 'meta';

    init();

    wireDpadNavigation(
      () => app.view,
      (view) => {
        if (view === 'thread') closeThreadView();
        else if (view === 'new') closeNewSessionView();
      },
    );
  });

  // Focus follows the view: list → last (or remembered) row; thread → Dictate
  // (respond is the #1 action; never the scrollback); new → Start.
  $effect(() => {
    const view = app.view;
    if (view === 'list') focusLastListRow(app.listFocusedThreadId);
    else if (view === 'thread') focusSessionPrimary();
    else if (view === 'new') focusNewPrimary();
  });

  // Offline resilience: snapshot the list while connected so the glasses open
  // to the last-known sessions instead of an empty screen.
  $effect(() => {
    void app.threadOrder.length;
    void app.conn;
    Object.values(app.threads).forEach((t) => { void t.lastEventAt; });
    saveListSnapshot();
  });

  $effect(() => {
    if (!app.toast.seq) return;
    toastVisible = true;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastVisible = false; }, 2800);
  });
</script>

<div id="app">
  <List />
  <Thread />
  <New />
  <div id="toast" class="toast" class:visible={toastVisible}
       class:success={app.toast.kind === 'success'} class:error={app.toast.kind === 'error'}
       role="status" aria-live="polite">{app.toast.text}</div>
</div>
