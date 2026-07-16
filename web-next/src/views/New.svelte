<script>
  import {
    app, pickAgent, startNewThread, closeNewSessionView, defaultCwd,
    showToast, clockNow,
  } from '../lib/store.svelte.js';
  import { agentClass } from '../lib/format.js';
  import { agentIcon } from '../lib/icons.js';
  import { tap, rbtnGroup } from '../lib/tap.js';

  const AGENTS = [
    { key: 'claude', label: 'Claude' },
    { key: 'codex', label: 'Codex' },
    { key: 'cursor', label: 'Cursor' },
  ];

  let cwd = $state('');
  let prompt = $state('');

  const ac = $derived(agentClass(app.pickedAgent));
  const title = $derived('create' + app.pickedAgent.charAt(0).toUpperCase() + app.pickedAgent.slice(1));

  // Prefill cwd each time the view opens (host default → recent → last used).
  $effect(() => {
    if (app.view === 'new' && !cwd) cwd = defaultCwd();
  });

  function debugPingText() {
    const now = new Date(clockNow());
    const local = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' });
    return 'debug ping ' + local + ' · ' + now.toISOString();
  }

  function fillTestPrompt() {
    prompt = debugPingText();
    showToast('test prompt filled — tap Start', 'success');
  }

  function start() {
    if (startNewThread(cwd, prompt)) prompt = '';
  }
</script>

<!-- NEW SESSION — blk-form; same BEM classes as legacy. -->
<section id="view-new" class="view blk-form-view" class:hidden={app.view !== 'new'}>
  <header class="hdr new-hdr blk-form-view__hdr">
    <div class="new-title-wrap blk-form-view__title-wrap">
      <span id="new-title-icon" class="new-title-icon blk-form-view__icon agent-{ac}"
            data-agent-icon={ac} aria-hidden="true">{@html agentIcon(ac)}</span>
      <h1 id="new-title" class="new-title-text blk-form-view__title">{title}</h1>
    </div>
  </header>
  <div class="new-body blk-form-view__body">
    <div id="agent-chips" class="agent-chips" role="radiogroup" aria-label="agent">
      {#each AGENTS as a (a.key)}
        <button type="button" class="agent-chip focusable"
                class:selected={ac === a.key}
                data-agent={a.key} role="radio"
                aria-checked={ac === a.key ? 'true' : 'false'}
                use:tap onclick={() => pickAgent(a.key)}>
          <img src="icons/agents/zinc/{a.key}.png" alt="" draggable="false"><span>{a.label}</span>
        </button>
      {/each}
    </div>
    <label class="field-label" for="new-cwd">working directory (on your Mac)</label>
    <input id="new-cwd" class="text-input focusable" type="text"
           placeholder="~/Projects/my-app" tabindex="-1" aria-hidden="true"
           bind:value={cwd}>
    <label class="field-label" for="new-prompt">prompt</label>
    <textarea id="new-prompt" class="text-input focusable" rows="3"
              placeholder="what should the agent do?" tabindex="-1" aria-hidden="true"
              bind:value={prompt}></textarea>
  </div>
  <nav class="new-actions blk-form-view__actions" role="toolbar" aria-label="create session">
    <div class="rbtn-row blk-rbtn-row" use:rbtnGroup>
      <button type="button" id="new-back" class="rbtn focusable rbtn-stroke" aria-label="back"
              use:tap onclick={closeNewSessionView}>
        <span class="rbtn-pill"><span class="rbtn-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M15 5.5 8 12l7 6.5" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="rbtn-label">Back</span></span>
      </button>
      <button type="button" id="new-dictate" class="rbtn focusable"
              title="fill test prompt" aria-label="fill test prompt"
              use:tap onclick={fillTestPrompt}>
        <span class="rbtn-pill">
          <span class="rbtn-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5-3c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/></svg>
          </span>
          <span class="rbtn-label">Test prompt</span>
        </span>
      </button>
      <button type="button" id="new-start" class="rbtn focusable rbtn-primary" aria-label="start session"
              use:tap onclick={start}>
        <span class="rbtn-pill">
          <span class="rbtn-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M7.5 5.2v13.6c0 .8.9 1.3 1.6.9l10.2-6.2c.7-.4.7-1.4 0-1.8L9.1 4.3c-.7-.4-1.6.1-1.6.9Z"/></svg></span>
          <span class="rbtn-label">Start</span>
        </span>
      </button>
    </div>
  </nav>
</section>
