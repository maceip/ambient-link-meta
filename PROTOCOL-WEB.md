# PROTOCOL-WEB — the glasses web app's wire contract

Extracted from the shipped `web/app.js` (v86) message handler. This is the
behavioral contract the Svelte rewrite (`web-next/`) implements and tests
against. The relay side lives in `ambient-link-core/host/internal/sink/ws.go`.

Transport: one WebSocket at `/{origin}/ambient-link/ws` (`?token=` appended
when a bearer token is present). All frames are single JSON objects with a
`type` field. The web client sets `source: "web"` on frames it originates.

## Addressing

`session_id` is the primary address; `thread` is the legacy fallback the relay
still accepts. Every session row carries both (`thread` = relay-assigned row
id, `session_id` = agent transcript uuid).

## Input delivery lifecycle (the core seam)

```
sending ──(ws down)──> offline ──(flush)──> sending
sending ──> accepted ──> queued|delivered ──> landed | failed
```

- The client mints `client_id` (`web-<ms>-<rand>`) per input; every
  `input_status` frame echoes it as `id`.
- `sending`/`offline` are client-local states. Only the relay's `accepted`
  confirms custody; only `landed` (transcript echo observed by the relay)
  proves the agent saw it. `delivered` != `landed`.
- Offline inputs queue in `localStorage` (`ambient-link:pending-inputs`,
  capped 20) and flush on the next `hello`.
- `failed` carries `error` and renders honestly — never a fake sent bubble.

## Inbound frames (relay → web), 12 types

| type | fields used | effect |
|---|---|---|
| `hello` | `threads[] {id,label,agent,session_id}`, `cursor`, `relay_debug` | Reconcile restored rows, upsert live rows, send `subscribe` from cursor, flush offline queue |
| `thread_started` | `thread,label,agent,cwd,session_id,at` | Upsert row, `busy=true`; if a create was pending, open the thread |
| `thread_ended` | `thread,at` | Mark ended, clear yank, reap dead rows |
| `thread_busy` | `thread,at` | `busy=true` (agent thinking) |
| `thread_idle` | yank shape (below) | Same handler as `hud_yank` |
| `hud_yank` | `thread,label,agent,lastAssistant,lastUserInput,awaiting,permissionPrompt,at` | Update row card + merge agent turn into chat log (`chipset.js parseYank`) |
| `companion_config` | `quick_replies[],snooze_until,show_continue,show_dictate,default_agent,dictate_mic,wake_hint?` | Update chip config; soft-handoff wake hint |
| `dictate_active` | `thread,source` | Enter listening UI (phone-initiated if `source != "web"`) |
| `dictate_partial` | `thread,text` | Stream partial to listening line (and hidden draft input) |
| `dictate_end` | `thread,text,ok,error` | Commit result: ok → delivered user bubble; !ok → keep draft, toast error |
| `input_status` | `id,thread,session_id,status,error,pending_count,at` | Advance the per-message lifecycle above |
| `create_status` | `ok,agent,error` | Toast create progress; `ok:false` clears pending create |

Plus `pong` (heartbeat echo) — no handler; any inbound frame refreshes the
liveness clock.

## Outbound frames (web → relay), 9 types

| type | fields | when |
|---|---|---|
| `subscribe` | `since` (cursor object from `hello`) | On every `hello` |
| `input` | `session_id,thread,text,client_id` | Quick-reply chip tap (composer/Send removed in v81); flushes of the offline queue |
| `companion_ui` | `screen: list\|create\|session\|idle`, `source` | On view change, ws open, page hide; heartbeat ~15s while visible (Android lease ~45s) |
| `session_focus` / `session_blur` | `thread,source` | Thread open/close, app foreground (warms the phone mic) |
| `hud_yank` | `thread` | On thread open + manual card refresh (asks relay to re-yank) |
| `dictate_begin` | `thread,source` | Dictate tap (single button) |
| `dictate_commit` | `thread,text,source` | Manual end ("Done") with accumulated partial |
| `dictate_abort` | `thread,source` | Cancel with empty transcript / leaving the view |
| `ping` | — | Heartbeat: sent when idle >25s; socket closed when idle >45s |

## Dictation (two initiators, one arbiter)

Web action row is **Switch/Back · Dictate · configurable chip** (≤3 buttons —
glasses nav budget). Slot 1 is **Switch** (FIFO oldest waiting session) when
the wake stack is non-empty, otherwise **Back** → session list. Hardware Back
→ `Escape` (Neural Band middle→thumb, temple two-finger tap) always returns
to the list. Mic path is **not** a web choice: the Android app setting
“Dictate with glasses mic” selects phone mic vs glasses SCO, and
`companion_config.dictate_mic` (`phone`|`glasses`) tells the web which path is
active for listening chrome copy.

Web taps Dictate → `dictate_begin` → relay fans `dictate_active` to the phone,
which captures on-device (SODA) on the configured path and streams
`dictate_partial`. EITHER the phone auto-commits on silence or the user taps
Done and the web commits. The relay injects and answers `dictate_end
{ok|error}` — the only frame that renders a bubble. Glasses SCO may flash the
in-call UI (intentional tradeoff).

## HTTP endpoints (same origin, `Authorization: Bearer` when token present)

- `GET /ambient-link/status` — poll every 15s: `sessions[]`, `delivery[]`,
  `outbox[]`, `laptop_peer_connected`, `now`, `default_cwd`, `journal`.
  Guard: when the laptop peer is up but the snapshot shows no live session,
  do NOT wipe rows already received over WS (cloud mux can lag).
- `GET /ambient-link/history?session_id=&limit=48` — hydrate chat scrollback
  once per session (`rows[] {role,text,at,id}` merged de-duped).
- `POST /ambient-link/sessions` `{agent,cwd,prompt}` — create; `200 {ok:
  "spawned", tmux}` or honest failure surfaced verbatim. Glasses New Session
  picks `cwd` from known folder chips (live sessions / host default /
  last-used) or **New here** on a list row — no free-text path field.

## Connection management (all client-side, port as-is)

- Reconnect: exponential backoff 500ms → 10s cap; `forceReconnect()` on
  `online` and `visibilitychange` resets backoff and reconnects immediately.
- Heartbeat: `ping` after 25s idle; hard-close after 45s idle (glasses links
  die silently; the close triggers reconnect).
- Displayed status lags real drops by a 2.5s grace window (no flicker);
  upgrades to connected render immediately. Action guards (send/dictate) use
  the REAL socket state, never the displayed one.

## localStorage keys

| key | contents |
|---|---|
| `ambient-link:token` | bearer token (captured from `#token=` fragment) |
| `ambient-link:theme` | theme name (`meta` default) |
| `ambient-link:list-snapshot` | last live session rows — instant list paint before ws |
| `ambient-link:chat-logs-v3` | per-thread chat logs (capped) |
| `ambient-link:pending-inputs` | offline input queue (capped 20) |
| `ambient-link:delivery-states` | message-id → last known lifecycle state |
| `al_default_cwd` | last used create-session cwd |

## Glasses copy (DAT + web session)

Waveguide and web chat show **decisions**, not transcripts:

| `awaiting` | Body shown |
|---|---|
| `permission` | `permissionPrompt` only (≤120 chars / 3 lines) |
| `question` | Last ask extract (`?` sentence), else clamped short paragraph |
| `done` | `ready` or `ready · last: <user>` — never assistant prose |

Diffs, code dumps, and tables are dropped for display. Chips remain the primary action UI. Full agent text stays on the Mac.

## Cross-surface roles (DAT ↔ web)

- **DAT (Android)** wakes the glasses when the web app is closed; quick chips
  act in place. It does **not** open the installed glasses web app.
- **Web** owns the launcher icon and deep work. While `companion_ui` screen ≠
  `idle` (with a ~45s lease refreshed by a ~15s web heartbeat), Android
  queues HUD peeks instead of pushing. Dead/backgrounded web stops
  heartbeating → lease expires → DAT may peek again.
- Soft handoff stack: phone publishes `companion_config.wake_hint =
  { thread, at, reason }` whenever a peek would have fired (including when
  web owns the display). Web keeps a FIFO stack (max 8, ~15 min TTL).
  - Soft-open: from the list only, and only if `at` is within ~2 minutes.
  - Switch: action-row slot 1 opens the oldest waiting session (count when
    >1). Empty stack → same as Back (session list). Opening a thread
    dismisses it from the stack. Re-ping of the same thread moves it to the
    end. Hardware Escape always → list regardless of stack.

## UI invariants (regression-tested in `web/test/e2e-live.spec.mjs`)

- No typed composer, no Send button. Session action row is exactly:
  Switch/Back · Dictate · first configured quick-reply chip.
- Chat history is inert (no focus stop, no touch scroll) and always snapped
  to the newest message.
- New session pill is a permanent first row of the list.
- Single tap activates any control (pointer AND touch synthesized clicks).
- List shows at most ~3.5 session cards; the half-card is the scroll cue.
- No fake sessions, no fabricated statuses: every rendered state is driven by
  a relay frame.
