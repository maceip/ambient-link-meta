# Ambient Link UI blocks

Reusable templates for the glasses companion web app. Each block matches native HUD patterns (`HudWidgets.kt`, `HudPresenter.kt`) and `BUTTON_ANIMS.mov`.

**Files**

| File | Purpose |
|------|---------|
| `blocks.css` | Canonical `blk-*` styles + legacy aliases (`.thread-row`, `.rbtn`, …) |
| `blocks.js` | `window.AmbientBlocks` — DOM builders and behavior |
| `demo.html` | Visual reference for all three blocks |
| `BLOCKS.md` | This doc |

**Include**

```html
<link rel="stylesheet" href="styles.css">
<link rel="stylesheet" href="blocks/blocks.css">
<script src="blocks/blocks.js"></script>
```

---

## 1. `shelf_list_view`

WhatsApp-style session list: card/bubble rows, newest at bottom, shelf of round icon buttons at the bottom.

**Markup pattern**

```html
<section class="blk-shelf-list-view view">
  <header class="blk-shelf-list-view__hdr">
    <span class="blk-shelf-list-view__title">sessions</span>
  </header>
  <p class="blk-shelf-list-view__empty hidden">no sessions</p>
  <div class="blk-shelf-list-view__list" role="list"></div>
  <nav class="blk-shelf-list-view__shelf" role="toolbar">
    <div class="blk-rbtn-row"><!-- agent launchers --></div>
  </nav>
</section>
```

**JS**

```javascript
var AB = window.AmbientBlocks;
AB.renderListItem({
  label: 'my-project',
  preview: 'done — continue or dictate',
  time: '2m',
  avatarHtml: '◆',
  avatarClass: 'agent-cursor',
  badge: 'done',
  badgeClass: 'status-tag done',
  onClick: function () { openThread(id); },
  onActivate: function () { openThread(id); },
});
AB.wireRbtnGroups(document); // one expanded pill at a time
```

**Rules**

- List rows are **cards**, not bare text lines.
- Shelf buttons: **52px circles**, **8px gap**, centered cluster — label expands on `:focus-visible` only.
- Call `wireRbtnGroups()` after mount.

---

## 2. `form`

Generic new-session form: path fields, text/textarea, mic + submit grouped **8px apart, centered**.

**Markup pattern**

```html
<section class="blk-form-view view">
  <header class="blk-form-view__hdr">
    <div class="blk-form-view__title-wrap">
      <span class="blk-form-view__icon">…</span>
      <h1 class="blk-form-view__title">createCursor</h1>
    </div>
  </header>
  <div class="blk-form-view__body"><!-- fields --></div>
  <nav class="blk-form-view__actions" role="toolbar">
    <div class="blk-rbtn-row">
      <!-- Dictate (mic SVG) + Start (primary) -->
    </div>
  </nav>
</section>
```

**JS**

```javascript
var cwd = AB.renderFormField({ id: 'cwd', label: 'working directory', placeholder: '/path/to/project' });
var prompt = AB.renderFormField({ id: 'prompt', label: 'prompt', type: 'textarea', rows: 5 });
body.appendChild(cwd);
body.appendChild(prompt);

var actions = AB.renderRbtnRow([
  { id: 'dictate', label: 'Dictate', iconHtml: AB.MIC_SVG, onClick: startDictate },
  { id: 'start', label: 'Start', icon: '▶', primary: true, onClick: submit },
]);
```

**Rules**

- Mic + Start share one `blk-rbtn-row`; CSS forces `gap: 8px` on form actions.
- Use `AB.MIC_SVG` for consistent mic icon.
- Toggle `recording` class on dictate button while listening.

---

## 3. `agent_action_card` + `listening`

Incoming agent message: meta line, card body, action chips (continue + dictate). On done cards, primary chip gets **5s auto-countdown** (native parity). Dictate shows **listening** state in the same card shell.

**Markup pattern**

```html
<div class="blk-agent-view">
  <p class="blk-agent-card__meta">project · done</p>
  <div class="blk-agent-card__body">Agent message…</div>
  <div class="blk-agent-card__actions" role="group"></div>
</div>
```

**JS — render chips with countdown**

```javascript
var cancel = AB.renderAgentActions(actionsEl, chips, {
  onDictate: function () { startDictate(); if (cancel) cancel(); },
  onSend: function (c) { sendPrompt(c.text); if (cancel) cancel(); },
  onModify: function () { composer.focus(); if (cancel) cancel(); },
}, {
  enabled: yank.awaiting === 'done',
  baseLabel: 'continue',
  shouldCancel: function () { return !isStillPeeking(); },
  onComplete: function () { sendPrompt('continue'); },
});
```

**JS — listening widget (same card, `You: listening…`)**

```javascript
AB.showListeningCard(bodyEl, agentBodyText, partialTranscript);
// on commit / abort:
AB.clearListeningCard(bodyEl, agentBodyText);
```

**Rules**

- Countdown only on **done** cards (`awaiting === 'done'`), never permission/question.
- Any user action cancels countdown via returned `cancel()` function.
- Listening keeps the **same card** — appends `\n\nYou: listening…` (or partial text), matching `HudWidgets.dictateCardBody`.

---

## Companion wiring

`index.html` loads `blocks/blocks.css` + `blocks/blocks.js`. Existing class names still work via aliases in `blocks.css`. `app.js` uses `AmbientBlocks` for list rows, rbtn groups, agent chips, countdown, and listening card.

**Visual test:** open `blocks/demo.html` in a browser (or glasses relay) to verify layout without live WS.
