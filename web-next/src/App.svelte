<script>
  // Device gate: proves runes reactivity, single-tap handling, and the WS
  // path to the relay all work in the glasses browser before we port the app.
  const BUILD = 'next-gate-1';

  let taps = $state(0);
  let ws = $state('connecting…');
  let sessions = $state(-1);

  const doubled = $derived(taps * 2);

  $effect(() => {
    const sock = new WebSocket(
      (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ambient-link/ws'
    );
    sock.onopen = () => { ws = 'open'; };
    sock.onerror = () => { ws = 'error'; };
    sock.onclose = () => { ws = 'closed'; };
    sock.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data);
        if (m.type === 'hello' && Array.isArray(m.threads)) sessions = m.threads.length;
      } catch { /* ignore */ }
    };
    return () => sock.close();
  });
</script>

<main>
  <h1>web-next boot OK</h1>
  <p class="tag">{BUILD} · svelte 5 runes</p>
  <p>relay ws: <b class={ws}>{ws}</b>{#if sessions >= 0} · {sessions} session(s){/if}</p>
  <button onclick={() => { taps += 1; }}>tap me — {taps} taps, doubled {doubled}</button>
  <p class="hint">If the count rises on a SINGLE tap and ws says open, the gate passes.</p>
</main>

<style>
  :global(body) { margin: 0; background: #000; color: #fff; font: 16px/1.5 system-ui, sans-serif; }
  main { padding: 28px 20px; max-width: 560px; margin: 0 auto; }
  h1 { font-size: 22px; color: #1c84ff; margin: 0 0 4px; }
  .tag { opacity: 0.6; margin: 0 0 16px; }
  b.open { color: #25d366; }
  b.error, b.closed { color: #ff5c5c; }
  button {
    font: inherit; color: #fff; background: rgba(28, 132, 255, 0.2);
    border: 1px solid rgba(28, 132, 255, 0.5); border-radius: 999px;
    padding: 12px 22px; margin: 10px 0; cursor: pointer;
  }
  .hint { opacity: 0.55; font-size: 14px; }
</style>
