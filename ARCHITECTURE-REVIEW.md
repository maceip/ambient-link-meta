# Ambient Link — Full Architecture Review (per-file, all repositories)

> Status: **living document, being assembled now.**
> Scope: every surface, interface, network connection, persistence location, and
> capability across all five repositories — `ambient-link-core`, `ambient-link-meta`,
> `ambient-link-google`, `ambient-link-snapchat`, `ambient-link-apple`.
> Method: files were opened and read, not inferred from names. The relay/core
> section below is a first-hand read. The web, native, shared-library, and vendor
> sections are produced by dedicated per-file review passes and folded in as they
> complete.
>
> This document is the **diagnosis**. The companion document
> [`IDEAL-ARCHITECTURE.md`](./IDEAL-ARCHITECTURE.md) is the **prescription** — the
> system we should build, deliberately separate from what exists today.

---

## 0. How to read this document

Each repository gets:

1. **Per-file table** — every source file: purpose, what it exposes, network
   endpoints, persistence, external deps, status (`working` / `half-migrated` /
   `dead` / `duplicate`), and surprises/risks.
2. **Area rollups** — deduplicated lists of network endpoints, persistence,
   capabilities, dependencies, cross-repo couplings, and a ranked risk list.

At the end:

- **§A — System capability matrix** (who can do what, over which transport).
- **§B — Every network connection in the system** (one table).
- **§C — Every persistence location** (one table).
- **§D — Root-cause analysis of the recurring "surprises"** (why trust keeps
  breaking).
- **§E — Keep / Rebuild / Delete verdict per component.**

---

## 1. Repository inventory (source files, build/dependency noise excluded)

| Repo | Meaningful source | Notes |
|---|---|---|
| `ambient-link-core` | 42 `.go`, 7 `.kt`, 7 `.swift`, 4 `.mjs`, 1 `.ts` | Go relay daemon (`host/`), shared Kotlin lib (`core-android/`), shared Swift package (`core-apple/`), contracts, protocol spec |
| `ambient-link-meta` | 63 `.kt`, 10 `.swift`, 8 `.js`, 4 `.css`, 9 `.html`, 18 `.glsl`, 22 `.sh` | Glasses web app (`web/`), native Meta app (`relay-android/`, `relay-ios/`), plus a large **decompiled DAT SDK tree** (~698 `.xml`, ~489 `.png`, `.dex`, `.so`) — vendor artifact, not our authored code |
| `ambient-link-google` | 6 `.kt`, 4 `.kts` | Android XR / Wear OS vendor app |
| `ambient-link-snapchat` | 4 `.ts` | Spectacles / Lens Studio vendor scaffold |
| `ambient-link-apple` | 10 `.swift` | visionOS / Siri / App Intents vendor scaffold |

The asymmetry is the first finding: **`ambient-link-meta` is ~1600 files** (mostly
a decompiled/vendored Meta DAT SDK + Android resource and asset trees), while the
three other vendor repos are **5–10 source files each** — they are scaffolds, not
implementations. The "four platforms" framing is aspirational; only Meta is real.

---

## 2. `ambient-link-core` — the Go relay daemon (`host/`)  [first-hand review]

The relay is the spine. It runs on a laptop next to the agents, observes their
activity from three independent producers, holds canonical session state in a
mux, persists to SQLite, serves the web app + a WebSocket, discovers itself over
mDNS, optionally bridges to a cloud endpoint, and routes human replies back into
the agents' terminals.

### 2.1 Per-file — `host/internal/proto`

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `proto/events.go` | The wire vocabulary. `EventType` (session_start, user_prompt, assistant_message, tool_use, permission_prompt, stop, session_end), `SessionState` (STARTING/BUSY/IDLE/AWAITING_PERMISSION/DEAD), `ProducerName`, the internal `Event`, and the outbound `Broadcast` envelope (thread_started/busy/idle/ended, hud_yank). | — | — | working | **No `PROTOCOL_VERSION` constant in Go.** The version (`1`) lives only in `protocol/PROTOCOL.md`. Nothing enforces wire-compat between relay and clients; a mismatch fails silently. `Broadcast` carries `lastAssistant`/`lastUserInput`/`awaiting`/`permissionPrompt` inline — content and control on one envelope. |

### 2.2 Per-file — `host/internal/mux` (canonical state machine)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `mux/mux.go` | `Mux` — the canonical per-session state. `Ingest` (single producer entry point, dedupes by (session,type) within 2.5s), `SweepStale(maxIdle, isLive)`, `MarkDead`, `Snapshot`, `ThreadsHello`, `YankForThread`, `SessionForThread`, `IngestUserInput`. Pure session aggregate `absorb()` maps events→state. `ThreadIDFor(agent,cwd)=agent-sha256(agent::cwd)[:10]`. | — (drives a `Sink`) | none directly — emits to sink | working, but **this is the de-facto source of truth**, which contradicts the stated "phone is source of truth" direction | `IdleDebounce` doc-comment says "Default 2s" but `WithDefaults` sets **4s** (and `PROTOCOL.md` says 2000ms) — three numbers, pick one. `IngestUserInput` records a human prompt into mux state; callers must gate it on real delivery or it re-introduces the "false sent" bug. `bestSessionLocked` collapses multiple sessions on one thread to "most recent," so two agents in the same cwd with the same tool fight over one thread id. |
| `mux/mux_test.go` | Unit tests incl. `TestSweepStaleSkipsLiveSession` (guards the "don't reap a live waiting agent" fix). | — | — | working | Good regression coverage for the G3 bug specifically. |

### 2.3 Per-file — `host/internal/producers` (observation)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `producers/hooks.go` | HTTP ingest. `NewHooks` serves `POST /ambient-link/hooks/claude` and `/ambient-link/hooks/codex`; parses Claude/Codex hook payloads → `proto.Event`; optional bearer auth (constant-time). On hook events, drains the outbox via `delivery.HookResponse` (the **bidirectional reply path**). | listens (mounted under main mux) | — | working | **Auth is optional** (`BearerToken==""` ⇒ accept all). The bidirectional reply path only fires when an agent happens to emit a hook — so HUD→agent delivery via hooks is opportunistic, not on-demand. |
| `producers/ingest.go` | Direct HTTP ingest endpoint (manual event push). | listens | — | (to confirm in detail) | A third way to create sessions; another spoofing surface if unauthenticated. |
| `producers/jsonl.go` | Transcript tailer. fsnotify-watches `~/.claude/projects`, `~/.codex/sessions`, `~/.cursor/.../agent-transcripts`; parses three on-disk formats; emits events. Recovers recent sessions on restart via bounded replay. | — | **reads** agent transcript files | working | **`cursorCWDFromPath` reconstructs cwd as a macOS `/Users/<name>/...` path on every OS.** This is the literal origin of the `cursor: /Users/mac/ambient-link-meta` "phantom" label observed on the Windows-fed cloud box. Tailer ingests *any* transcript on the box, so a relay on a shared/cloud host surfaces unrelated users' sessions. |
| `producers/proc.go` | Process watcher (Unix). Enumerates `claude`/`codex`/`cursor-agent` via `ps`, correlates PID→session via `lsof` open files, registers a delivery `Endpoint{PID,TTY}`, and `MarkDead`s vanished PIDs. | spawns `ps`/`lsof` | — | working | Windows path has **heuristic correlation** (`sessionsForUniqueLiveAgent` — "if exactly one live agent of this type, assume it's this PID"), which mis-binds when two same-type agents run. Death is process-driven (good), but correlation confidence is uneven across OSes. |
| `producers/proc_windows.go` | Windows process listing + cwd-based correlation (`sessionsForWindowsCWD`). | Windows APIs | — | working | The `useWindowsProcSupport` branch is materially different logic from Unix — two correlation strategies to keep in sync. |
| `producers/proc_other.go` | Non-Windows stub of the platform flag. | — | — | working | — |
| `producers/hooks.go`/`jsonl.go`/`proc.go` tests | `proc_test.go`, `jsonl_cursor_test.go` | — | — | working | Cursor cwd decode is tested only for the posix shape. |

### 2.4 Per-file — `host/internal/delivery` (reply routing — the trust-critical path)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `delivery/deliver.go` | `Deliver` / `DeliverWithResult` (always types **text+Enter** — `enter` flag removed, per DECISIONS §4), `TryImmediate` (process→tmux→tty adapters by PID), `TrySpecial` (single raw key, no newline), `RetryPending`, `FlushPending`. Web agent ⇒ outbox. Preserves order: if pending exists, new replies queue. | — | via outbox | **half-honest** | Only two status constants exist: `StatusDelivered`, `StatusQueued`. **`DECISIONS.md` claims statuses `written/landed/queued/failed`; those do not exist.** "delivered" means "the adapter returned no error" — i.e. *written to a channel*, the exact conflation the user flagged. There is no confirmation that bytes became an agent turn. |
| `delivery/registry.go` | `Registry`: session_id→`Endpoint{PID,TTY,Agent}`. Set by proc watcher, read at inject time. Also the `isLive` source for the stale reaper. | — | in-memory | working | Endpoints expire only on proc sweep; a missed sweep leaves a stale endpoint that delivery will try. |
| `delivery/outbox.go` | Durable-ish pending queue per session: `Message`/`PendingMessage`, `Enqueue`/`Peek`/`Dequeue`/`HasPending`/`Count`/`ListPendingIDs`. | — | (confirm: file or memory) | working | The `enter` field was removed here too. |
| `delivery/pty.go` | Relay-owned PTY writers keyed by **threadID**: `RegisterPTY`, `PTYByThread`, `PTYWriter` interface. Inject prefers these. | — | in-memory | working | Two parallel delivery worlds now exist — PID/console (proc) and threadID/PTY (`host run`) — resolved in different layers (deliver.go vs inject.go). Cohesion risk. |
| `delivery/process_input_windows.go` | `SendProcessInput(pid,text,enter)` via `AttachConsole(pid)` + `WriteConsoleInputW`. | Win32 syscalls | — | working but fragile | **Injects synthetic keystrokes into another process's console.** Frees/attaches the daemon's own console as a side effect; only one console attach at a time; brittle under service contexts. Powerful capability with real blast radius. |
| `delivery/process_input_other.go` | Non-Windows stub. | — | — | working | — |
| `delivery/tty.go` / `tty_windows.go` | `WriteTTY` adapter (Unix writes to controlling tty device; Windows variant). | OS tty | — | working | TTY write requires the relay to have rights to the target tty. |
| `delivery/tmux.go` / `tmux_windows.go` | `SendTmuxPID` — `tmux send-keys` to the pane owning the PID. | spawns `tmux` | — | working | Only works if the agent runs inside tmux; silent no-op otherwise. |
| `delivery/hooks.go` | `HookResponse` — builds the JSON reply that piggybacks queued outbox messages onto a hook HTTP response (bidirectional path). | — | drains outbox | working | Delivery latency = time until the agent next fires a hook. Not real-time. |
| `delivery/delivery_test.go` | Tests for ordering/queue/result. | — | — | working | Tests assert "delivered" on adapter success — codifying the written≠landed conflation. |

### 2.5 Per-file — `host/internal/store` (durable local DB)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `store/store.go` | SQLite (`modernc.org/sqlite`, pure-Go) at `~/.ambient-link/relay.db` (or `$AMBIENT_LINK_HOME`). Tables: `broadcasts` (monotonic `seq` — the WS replay log), `sessions`, `interactions`. `Append` projects broadcasts into sessions + assistant interactions. `RecordHuman(status)`, `MarkLanded`, `History`. WAL, single writer. | — | **writes** `relay.db` | working storage, **unfinished honesty** | **`MarkLanded` has no caller anywhere in the tree** — the "confirm the message became a user turn by reading the agent's transcript" keystone (G4) is documented but **not wired**. So `interactions.delivery_status` is only ever `delivered`/`queued`, never `landed`. The DB faithfully persists an unverified claim. |
| `store/store_test.go` | Durability, append/replay parity, history, persistence-across-reopen tests. | — | — | working | Tests don't cover `MarkLanded` end-to-end because nothing calls it. |

### 2.6 Per-file — `host/internal/sink` (WS hub / fan-out)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `sink/ws.go` | The WebSocket hub. Serves `/ambient-link/ws`; sends `hello`; fans `Broadcast`s to all clients; handles inbound `subscribe`/`input`/`special`/`hud_yank`/`dictate_*`. Uses a `Journal` interface (now `*store.Store`) for replay; `CloudMirror` interface to mirror broadcasts upstream. Gates `mux.IngestUserInput` on `inject.Delivered(result)`. | listens `/ambient-link/ws` | via store | working | Inbound message structs still parse around removed `enter`; the web app still **sends** `enter` (ignored). Fan-out is all-or-nothing — every client sees every thread (no per-client scoping / no auth on the WS). |
| `sink/dictate_fanout_test.go` | Dictation fan-out test. | — | — | working | — |

### 2.7 Per-file — `host/internal/inject` (high-level reply API)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `inject/inject.go` | `SendInput`/`SendInputResult` (prefer `delivery.PTYByThread`, else `delivery.DeliverWithResult`), `SendSpecial`, `Delivered(result)`, `Init(recorder)`. Records human turns to the store with their status. | — | via store | working, **half-honest** | Status recorded is `delivered`/`queued` (see store gap). "Prefer PTY" only helps `host run`-launched agents; externally-run agents fall back to the console/tty heuristics. This is the single chokepoint where "did it really land?" should be answered and currently isn't. |

### 2.8 Per-file — `host/internal/pty` (relay-owned terminal)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `pty/pty.go` | Client side of `host run <agent>`: spawns the agent under a relay-owned PTY (`go-pty` + `x/term`), proxies local stdio, dials the daemon control WS, answers terminal capability queries (XTVERSION/DA1/DA2/DSR) so TUIs don't stall headless, defaults PTY size 120x30. | dials `/ambient-link/pty` | — | working (interactive); **unproven headless** | Per `DECISIONS.md`, driving a full TUI agent (Claude) headlessly through a synthetic ConPTY was **not reliably achievable**; `--print`/stream-json rejects TTY stdin. So the "most reliable" delivery channel is reliable only when a human launched the agent via `host run` in a real terminal. |
| `pty/control.go` | Daemon side: `ControlHandler` at `/ambient-link/pty?thread=&token=`; registers a `wsWriter` as the thread's `delivery.PTYWriter`; relays `{text,submit}` frames to the `host run` client. | listens `/ambient-link/pty` | — | working | Token optional. Thread identity comes from the client's query param (trust-on-connect). |

### 2.9 Per-file — `host/internal/cloud` (reverse channel)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `cloud/cloud.go` | Optional outbound bridge. `Bridge` dials `$AMBIENT_LINK_CLOUD` (wss), `Mirror`s local broadcasts upstream, and on `handleDownstream` accepts `input`/`special` from the cloud and applies them via registered `Deliver`/`Special` callbacks. Reconnect w/ backoff. | **dials** `$AMBIENT_LINK_CLOUD` | — | working | This is the *correct* shape for "cloud assist" — laptop dials out, cloud forwards. **But the deployed `public.computer` box was running the full relay binary (with its own producers + journal), not a forwarder** — so it manufactured its own sessions. The proxy/forwarder server side is not a distinct artifact; the same binary plays both roles. |

### 2.10 Per-file — `host/internal/discovery`, `pair`, `dictate`, `backpressure`, `webapp`

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `discovery/mdns.go` | Advertises `_ambientlink._tcp` on the LAN for phone/web discovery. | mDNS multicast | — | working | Advertises presence on any network the laptop joins (incl. hotel/coffee-shop LANs). No pairing gate on discovery. |
| `pair/pair.go` | Pairing flow (codes/tokens between phone and relay). | listens | (confirm) | (confirm in detail) | Pairing exists but the WS/REST data planes don't appear to *require* a paired token — discovery + open WS may sidestep it. To verify against `sink/ws.go` auth. |
| `dictate/dictate.go` (+ throttle test) | Dictation session state + fan-out of `dictate_*`; commits inject transcript via the same path as `input`. | — | — | working | STT capture is on phone/web; on-device SODA is "future v2" per protocol. So dictation today depends on platform speech APIs, not the vendored SODA. |
| `backpressure/throttle.go`, `buffer.go` (+tests) | `Throttle` (rate-gate) and `EphemeralBuffer` (TTL/max ring) used for `dictate_partial` fan-out and frame gating. | — | — | working | Clean, well-tested primitive. Mirrored in Kotlin/Swift (see core libs) — three copies to keep in sync. |
| `webapp/webapp.go` | Serves the static web app from `$AMBIENT_LINK_WEB_ROOT` (hardcoded mac path removed). | listens (static) | reads web root | working | If web root unset/missing, serving silently degrades; needs loud failure. |

### 2.11 Per-file — `host/cmd/host/main.go` (composition root)

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `cmd/host/main.go` | Wires everything: opens `store`, builds `mux`, starts producers (hooks, jsonl ×3, proc), serves `/ambient-link/ws`, `/status`, `/sessions`, `/healthz`, `/hooks/*`, `/pty`, debug `/yank` + `/input`, static web app; mDNS; optional cloud bridge (`$AMBIENT_LINK_CLOUD`); stale reaper with `isLive` predicate; `host run` subcommand. Config via env (`AMBIENT_LINK_LISTEN/TOKEN/WEB_ROOT/CLOUD/HOME`). | listens `:5181` (default), dials cloud | store | working | Single binary = LAN relay **and** cloud peer **and** CLI launcher. No "mode." Debug endpoints (`/debug/yank`, `/debug/input`) ship in the same binary; `/debug/input` previously did optimistic mux ingest (now gated). Default listen is broad; token optional ⇒ open control plane on the LAN by default. |

### 2.12 `host/go.mod` dependencies (capability surface)

`modernc.org/sqlite` (pure-Go DB), `nhooyr.io/websocket`, `github.com/fsnotify/fsnotify`,
`github.com/aymanbagabas/go-pty`, `golang.org/x/term`, `golang.org/x/sys/windows`,
an mDNS lib. Lean and cgo-free — good. The OS-injection capability (`x/sys/windows`
console APIs, `tmux`, tty) is the security-sensitive part.

### 2.13 Core/relay — area rollups

**Network endpoints (relay):**

| Direction | Endpoint | Auth | Purpose |
|---|---|---|---|
| listen | `:5181/ambient-link/ws` (WS) | none by default | broadcasts + inbound input/dictate/yank |
| listen | `:5181/ambient-link/status`, `/sessions`, `/healthz` (HTTP GET) | none | polling snapshot of sessions |
| listen | `:5181/ambient-link/hooks/{claude,codex}` (POST) | optional bearer | agent lifecycle ingest + outbox drain |
| listen | `:5181/ambient-link/ingest` (POST) | (confirm) | manual event ingest |
| listen | `:5181/ambient-link/pty?thread=&token=` (WS) | optional token | relay-owned PTY control |
| listen | `:5181/ambient-link/debug/{yank,input}` | (confirm) | debug injection |
| listen | static web root | none | serves the glasses web app |
| advertise | mDNS `_ambientlink._tcp` | none | LAN discovery |
| dial | `$AMBIENT_LINK_CLOUD` (wss) | (token in URL?) | cloud mirror + downstream input |

**Persistence (relay):** `~/.ambient-link/relay.db` (SQLite: broadcasts/sessions/interactions);
the outbox (pending replies); plus it **reads** agent transcripts under `~/.claude`,
`~/.codex`, `~/.cursor`. It also (via installer) **writes** `~/.claude/settings.json`,
`~/.codex/hooks.json`, and launchd/systemd unit files.

**Capabilities (relay):** observe agents (3 producers), hold canonical state,
persist history, serve web app, broadcast over WS, discover over mDNS, bridge to
cloud, and **inject input into agent terminals** (console keystrokes / tmux / tty /
relay-owned PTY).

**Top relay risks (ranked, first-hand):**

1. **Delivery still reports "delivered" for a mere channel write.** `MarkLanded`
   is never called; `written/landed/failed` don't exist. The core promise — "a
   human reply reliably became an agent turn" — is still unverified in code.
   (`delivery/deliver.go`, `store/store.go`, `inject/inject.go`)
2. **Source-of-truth inversion.** The Go mux *is* the source of truth; the
   "phone is source of truth" requirement is unimplemented. Every sync story is
   built on the wrong anchor. (`mux/mux.go`)
3. **Phantom sessions are structural, not a one-off.** The tailer ingests any
   transcript on the host and reconstructs cwd as `/Users/...`; the same binary
   runs as the cloud peer with its own DB. A "cloud assist" that is actually a
   full relay will always manufacture sessions. (`producers/jsonl.go`,
   `cloud/cloud.go`, `cmd/host/main.go`)
4. **Open control plane by default.** WS, status, pty, and (optionally) hooks
   accept unauthenticated connections; mDNS advertises on any LAN. (`sink/ws.go`,
   `discovery/mdns.go`, `cmd/host/main.go`)
5. **Two delivery worlds** (PID/console vs threadID/PTY) resolved in two layers;
   easy to regress one while testing the other. (`delivery/`, `inject/`)
6. **Windows correlation is heuristic** ("one live agent of this type") and
   mis-binds with two same-type agents. (`producers/proc.go`)
7. **Doc/code/wire drift**: `enter` (gone in Go, still in PROTOCOL.md + web app),
   idle debounce 2s vs 4s vs 2000ms, no `PROTOCOL_VERSION` in code.
8. **Headless delivery unproven** for full-TUI agents; "most reliable" channel
   needs a human-launched real terminal. (`pty/`, `DECISIONS.md`)
9. **Debug injection endpoints ship in the production binary.** (`cmd/host/main.go`)
10. **Backpressure primitive triplicated** across Go/Kotlin/Swift — drift risk.

---

## 3. `ambient-link-meta` — glasses web app (`web/`) + repo tooling  [first-hand]

The web app is the surface the user actually sees on the glasses. It is a vanilla
JS PWA served by the relay, talking to the relay over one WebSocket on the same
origin.

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `web/app.js` | The whole companion: thread list, thread view, composer, dictation, host panel, delivery tracking. Connects WS, handles `hello`/`thread_started`/`thread_busy`/`thread_idle`/`hud_yank`/`dictate_*`/`input_status`; sends `subscribe`/`input`/`special`/`hud_yank`/`dictate_*`; POSTs new sessions; polls `/status`. | `ws(s)://location.host/ambient-link/ws`; `GET /ambient-link/status` (15s); `POST /ambient-link/sessions` | `localStorage`: `ambient-link:pending-inputs`, `ambient-link:delivery-states` | working | **Single-relay by construction** — WS URL is `location.host`; the app can only ever see the one relay that served it. **Still sends `enter: item.enter !== false`** (relay ignores it). **"delivered" is inferred from "left the outbox queue"** (`applyOutboxStatus`), not from landing — the UI repeats the relay's written≠landed conflation, and has no `landed` state. Re-implements `ThreadIDFor` (sha256 agent::cwd) in JS — a third copy of that identity function. |
| `web/chipset.js` | Chip classification + card text (`forYank`, `metaLine`, `bodyText`, `Awaiting`). Mirrors `protocol/PROTOCOL.md` chip table. | — | — | working | The chip→text/`enter` mapping is duplicated from PROTOCOL.md; drift-prone. |
| `web/styles.css` | The Meta-HUD design system: tokens, session cards/list rows, status tags, avatars, chip/button row. | — | — | working | This is the design work the user fought for; it is correct and worth keeping. Tokens live only here (not shared with native). |
| `web/companion.css` | Compose/thread-view styling. | — | — | working | Second stylesheet; design tokens not centralized. |
| `web/index.html` | App shell; mounts views; registers `sw.js`. | — | — | working | — |
| `web/sw.js` | Service worker. **Network-first** for the shell, cache fallback; cache name `ambient-link-meta-v13`; never intercepts `/ambient-link/(ws|status|pair|sessions|ingest|hooks/|debug/)`. | — | Cache Storage `ambient-link-meta-v13` | working | **Cache-bust = manually bumping `v13`.** Network-first mitigates staleness, but a human must remember the version bump — a recurring source of "why is the old UI still there." |
| `web/manifest.json` | PWA manifest (standalone, black theme, single SVG icon). | — | — | working | Minimal but valid for Meta Display install. |
| `web/icons/*.svg` (`cursor`, `claude`, `openai`) | Lobe-style agent icons. | — | — | working | Same icons also inlined in `app.js` `AGENT_ICONS` — duplicated. |
| `web/probe.js`/`probe.html`/`harness.html` | Transport-probe + test harnesses (the "multi-mode local transport" experiment). | various | — | likely dead/experimental | Leftover from earlier transport experiments; not part of the shipping path. Candidate for deletion. |
| `web/chipset.js` tests (`test/chipset.test.mjs`, `load-chipset.mjs`), `test/static.test.mjs` | Node tests for chip logic + static shape. | — | — | working | Good that chip logic is tested; tie it to PROTOCOL.md as the source. |
| `web/package.json` | Test scripts only. | — | — | working | — |

**Web rollups:** discovers the relay only as "whoever served this page" (no
multi-relay, no phone target); WS consumes `hello/thread_*/hud_yank/dictate_*/input_status`
and produces `subscribe/input/special/hud_yank/dictate_*`; REST `GET /status` +
`POST /sessions`; SW is network-first with manual version cache-bust. **Top web
risks:** single-relay coupling; delivered≠landed mirrored in UI; `enter` drift;
triplicated identity/icon/chip logic; dead probe harnesses.

## 4. `ambient-link-meta` — native apps (`relay-android`, `relay-ios`) + SODA  [first-hand]

This is the real, substantial native app (~32 Kotlin files + 10 Swift), and it is
the **only** client that drives the Meta DAT glasses HUD directly.

**Android (`relay-android`), key files:**

| File | Purpose / surface | Network | Persistence | Status | Surprises / risks |
|---|---|---|---|---|---|
| `relay/RelayClient.kt` | The real phone daemon transport: okhttp **WebSocket** to `/ambient-link/ws`; emits `Hello/ThreadIdle/HudYank/ThreadBusy`; sends `input`(+`enter`)/`special`/`dictate_*`. | WS to relay | in-memory | working | **This is a second, separate RelayClient from `core-android`'s** (which REST-polls `/status`). The native app does **not** use the shared contract — "one contract home" is violated. Still sends `enter`. |
| `relay/RelayDiscovery.kt` | Finds the relay (mDNS `_ambientlink._tcp` / saved URL). | mDNS | prefs | working | Discovery → single relay URL; no multi-relay merge. |
| `relay/RelayService.kt` | Foreground service hosting the daemon loop (yank → HUD). | — | — | working | Long-lived foreground service; correct pattern for a daemon. |
| `relay/CompanionUrls.kt` | Builds `http://host:5181/ambient-link/?session=…&compose=1` from the WS URL. | — | — | working | Confirms relay default port **5181** and that the web companion is served by the relay host. |
| `hud/DatDisplaySession.kt` | **Real Meta DAT integration**: `com.meta.wearable.dat.*` — `Wearables.createSession` → `addDisplay` → `DisplayState.STARTED`; one session per device; retry on "already exists"; surfaces "DAT app update required". | DAT/BT to glasses | — | working | The genuine glasses HUD push path. Tightly coupled to the Meta DAT SDK (vendored/decompiled — see below). Single global `GlassesDisplay` session. |
| `hud/HudPresenter.kt`, `HudWidgets.kt`, `HudDiffCard.kt`, `AgentYank.kt`, `ChipSet.kt` | The six-state HUD presenter + DAT card rendering + chip sets (mirrors PROTOCOL.md). | — | — | working | Chip/awaiting logic duplicated from PROTOCOL.md and from the web app's chipset.js. |
| `dictation/Dictation*.kt` (`Manager`, `Activity`, `Launcher`, `Callback`) | Android `SpeechRecognizer`-based dictation → `dictate_*`. | — | mic | working | Two dictation engines coexist (platform SpeechRecognizer **and** SODA). |
| `soda/SodaDictationEngine.kt`, `SodaRuntime.kt`, `MicCapture.kt`, `WavPcmReader.kt`, `SodaFixtureRunner.kt` | On-device SODA STT: mic capture → SODA runtime → transcript. Reference impl of `core-android` `SttEngine`. | — | mic, reads model pack | working-ish | Depends on a **recovered Google native blob**. |
| `soda/.../com.google.android.libraries.assistant.soda/*`, `com.google.research.air.cosmo.lib.soda/*` (`SodaJniLibraryLoader`, `SodaUtils`, `SodaTypes`, `SodaSession`, `SodaConfigBuilder`) | **Decompiled/recovered Google SODA** Kotlin + `System.loadLibrary("soda_dev_jni")`. | — | loads `libsoda_dev_jni.so` | **provenance risk** | Google-owned, decompiled code committed under `com.google.*` package names, plus a native `.so` and (per `SttEngine` doc) a non-redistributable `.jar`. Licensing/provenance liability; ties STT to an unsupported recovered binary. |
| `probe/ClaudeNotificationListener.kt` | Notification-listener probe (reads Claude notifications). | — | — | experimental | A `NotificationListenerService` is a sensitive permission; appears to be a probe. |
| `MainActivity.kt`, `App.kt` | App entry + DI/bootstrap. | — | prefs | working | — |
| `wearables/WearablesRepository.kt`, `WearablesRuntime.kt` | DAT device discovery/state. | DAT/BT | — | working | — |
| `debug/DictateDebugReceiver.kt` | adb broadcast → trigger dictation (debug). | — | — | debug | Debug receiver shipping in app; gate behind build type. |

**iOS (`relay-ios`):** `RelayClient.swift` (URLSessionWebSocketTask WS — mirrors the
Android native WS client), `HudPresenter.swift`, `DaemonState.swift`, `MainView.swift`,
`WearablesViewModel.swift`, `DictationManager.swift`, `ChipSet.swift`, `AgentYank.swift`,
`DebugFireWidget.swift`, `AmbientLinkApp.swift`. Same divergence as Android: the iOS
native app has its **own** WS RelayClient, separate from `core-apple`'s REST-poll
`RelayClient`. Chip/yank/state logic is re-implemented again here (4th+ copy).

**Non-source trees in `ambient-link-meta` (grouped):** ~698 `.xml` + ~489 `.png` +
`.dex`/`.so`/`.kotlin_builtins`/`.arsc` are overwhelmingly a **decompiled/vendored
Meta DAT SDK** plus standard Android resource/asset trees and build intermediates.
Plus committed binaries to flag: an `.apk`, a `.keystore`, a `.wav`/`.ogg` fixture,
and an `.mp4` (the reference UI clip). **A committed `.keystore` and `.apk` are
security/footprint red flags** and should not live in git.

**Native rollups:** the native app is the *only* path that (a) uses WebSocket and
(b) drives the real DAT HUD. It depends on a vendored Meta DAT SDK and a recovered
Google SODA blob. It re-implements the relay client and chip logic instead of using
`core-*`. Permissions to enumerate from the manifest include foreground service
type(s), mic (RECORD_AUDIO), notification listener, and BT/companion for DAT.

## 5. `ambient-link-core` — shared libraries (`core-android`, `core-apple`)  [first-hand]

These are meant to be the "vendor-neutral core." In practice they are consumed by
the *vendor scaffolds*, not by the real Meta native app.

| File | Purpose / surface | Network | Contract or impl | Status | Surprises / risks |
|---|---|---|---|---|---|
| `core-android/RelayClient.kt` | Polls `GET /ambient-link/status` (5s glasses / 15s wear) → `List<Session>` via StateFlow. | REST poll | impl | working | **Diverges from the native app's WS client.** No reply/input path at all — read-only. So any consumer using *this* client cannot send replies. |
| `core-android/Session.kt` | Canonical `Session(sessionId, agent, cwd, state, preview, awaiting, permissionPrompt)` + `isLive/needsAttention/shortCwd/label`. | — | model | working | Good model; but the native app uses its own `AgentYank`/types instead. |
| `core-android/GlassLink.kt` | Vendor-neutral capture contract (bind/unbind, image+audio capture, throttled frames). Cosmo-derived. | — | contract | working | Real contract; only implemented (stubbed) by the google scaffold. The Meta app's DAT path does not implement this interface. |
| `core-android/SttEngine.kt` | Vendor-neutral streaming STT contract (start/stop/lastPartialText + Callbacks). | — | contract | working | SODA impl lives in meta (blob not redistributable). Clean boundary. |
| `core-android/WearPaths.kt` | Wear data-layer paths (`/ambientlink/sessions|status|reply|trigger|mic_stream`), `PhoneStatus`/`WatchStatus`/`TriggerType`. | Wear MessageClient/ChannelClient | contract | working | Only the google `wear` scaffold references these; no shipping watch app. |
| `core-android/Throttle.kt`, `EphemeralBuffer.kt` | Backpressure primitives (mirror the Go ones). | — | impl | working | **Third copy** of the same primitive (Go + Kotlin + Swift). |
| `core-apple/RelayClient.swift` | `RelayClient` polls `GET /status`; **`reply()` POSTs `/ambient-link/ingest`**; `SessionStore` `@Observable` poll loop. **`baseURL` defaults to `https://public.computer`.** | REST poll + POST ingest to **public.computer** | impl | **working but wrong default** | **Two problems:** (1) defaults to the cloud box, not a phone/laptop; (2) "reply" goes to `/ingest`, which only records a `user_reply` event in the mux — **it never injects into an agent terminal.** So the Apple reply path is a no-op toward the agent while reporting success. |
| `core-apple/GlassLink.swift` | Swift mirror of the GlassLink contract. | — | contract | working | Parallel definition to Kotlin; manual sync. |
| `core-apple/Session.swift` | Swift `Session` model + parsing. | — | model | working | Parallel to Kotlin `Session`; manual sync. |
| `core-apple/Throttle.swift`, `EphemeralBuffer.swift` | Backpressure mirrors. | — | impl | working | Third-copy problem again. |
| `core-apple/Package.swift`, `Tests/*` | SwiftPM package + tests. | — | — | working | — |

**Shared-lib rollups:** the "shared core" is real but **not used by the real Meta
app**; it's the dependency of the vendor scaffolds. The Apple client points at the
cloud and its reply path can't land. Backpressure is triplicated. `GlassLink`/
`Session` exist twice (Kotlin+Swift) with manual sync and no codegen.

## 6. Vendor repos — `ambient-link-google`, `ambient-link-snapchat`, `ambient-link-apple`  [first-hand]

| Vendor | Target / SDK | What it actually is | Relay transport | GlassLink | Maturity |
|---|---|---|---|---|---|
| **google** (`app/` + `wear/`) | Android XR `ProjectedGlass*` + Wear OS data layer | `ProjectedGlassLink.kt` is **all `TODO(xr)` stubs** (no real Jetpack XR calls); `WatchDataLayer.kt`/`SessionListScreen.kt`/`WatchActivity.kt`/`HomeScreen.kt`/`GlassesMainActivity.kt` are skeleton UI. Depends on `core-android`. | (via `core-android` REST poll) | declared, **stubbed** | **scaffold** |
| **snapchat** (`Assets/Scripts/*.ts`) | Spectacles Lens Studio + Fetch API | `RelayClient.ts` (Fetch to `public.computer/ambient-link/status`), `GlassLink.ts`, `AmbientLinkController.ts`, `SessionTypes.ts`. Read-only status fetch; TODOs against Lens Studio API versions. | REST poll to **public.computer** | declared, partial | **scaffold** |
| **apple** (`Sources/AmbientLinkKit`, `App/AmbientLinkVision`) | visionOS + FoundationModels + App Intents | `SiriAssistant.swift` (on-device model + `getSessions` tool), `Intents.swift` (`ShowSessionsIntent`, `ReplyToAgentIntent`), `SessionEntity.swift`, `ImmersiveSessionsView`/`SessionVolumeView`/`SessionListView`/`AmbientLinkApp`. Uses `AmbientLinkCore` (→ `public.computer`). | REST poll + `/ingest` reply to **public.computer** | n/a (no projected capture on visionOS) | **scaffold (richest of the three)** |

**Vendor rollups:** all three are scaffolds pointing at the **cloud box**; none
drives a real device end-to-end; google's GlassLink is stubbed; snap is a read-only
Fetch; apple is the most fleshed-out (FoundationModels + App Intents) but its reply
path is the no-op `/ingest`. They collectively imply "4-platform support" that does
not exist. **They should be explicitly frozen as scaffolds** until Meta is solid.

---

## §A. System capability matrix

| Capability | core relay (Go) | web app | native Meta (kt/swift) | core libs (kt/swift) | google | snapchat | apple |
|---|---|---|---|---|---|---|---|
| Observe agents | ✅ 3 producers | — | — | — | — | — | — |
| Canonical state | ✅ mux | — | — | — | — | — | — |
| Persist history | ✅ SQLite | localStorage only | — | — | — | — | — |
| Consume sessions | — | ✅ WS | ✅ WS | ✅ REST poll | REST poll | REST poll | REST poll |
| Reply that **lands** | ⚠️ written≠landed | ⚠️ via WS input | ⚠️ via WS input | ❌ (none / `/ingest`) | ❌ | ❌ | ❌ `/ingest` no-op |
| Drive glasses HUD | — | (web UI only) | ✅ Meta DAT | — | stub | stub | (vision UI) |
| Dictation/STT | fan-out only | Web SpeechRecognition | ✅ SpeechRecognizer + SODA blob | SttEngine contract | — | — | Apple Speech (planned) |
| Discover relay | mDNS advertise | same-origin only | mDNS | hardcoded/poll | — | hardcoded | hardcoded cloud |

## §B. Every network connection in the system

| From | To | Transport | Auth | Notes |
|---|---|---|---|---|
| web app | relay `:5181/ambient-link/ws` | WS | none | same origin only |
| web app | relay `/status`, `/sessions` | HTTP | none | poll + create |
| native Meta (and/or) | relay `/ambient-link/ws` | WS | none | mDNS-discovered |
| core-android | relay `/status` | HTTP poll | none | read-only |
| core-apple / apple | **public.computer** `/status`, `/ingest` | HTTP poll/POST | none | cloud default; `/ingest` ≠ delivery |
| snapchat | **public.computer** `/status` | HTTP (Fetch) | none | allow-listed host |
| agents (claude/codex) | relay `/hooks/*` | HTTP POST | optional bearer | lifecycle ingest |
| relay | `$AMBIENT_LINK_CLOUD` | WS dial | token-in-URL? | cloud mirror/downstream |
| relay | glasses (native) | Meta DAT / BT | DAT pairing | HUD push |
| phone↔watch (planned) | Wear data layer paths | MessageClient/ChannelClient | Wear pairing | google scaffold only |
| relay | LAN | mDNS `_ambientlink._tcp` | none | advertises on any network |

## §C. Every persistence location

| Where | What | Written by | Risk |
|---|---|---|---|
| `~/.ambient-link/relay.db` | broadcasts / sessions / interactions (SQLite) | relay `store` | `landed` never set; cloud box accrues phantom rows |
| relay outbox | pending replies | relay `delivery` | retried opportunistically |
| `~/.claude/settings.json`, `~/.codex/hooks.json` | hook wiring (+bearer token) | installer | mutates user config; token at rest |
| `~/Library/LaunchAgents/*.plist`, `~/.config/systemd/user/*.service` | service units | installer | autostart persistence |
| reads `~/.claude`,`~/.codex`,`~/.cursor` transcripts | observation source | jsonl tailer | reads *any* user's transcripts on host |
| web `localStorage` | pending-inputs, delivery-states | web app | client-only optimism |
| committed in git | `.keystore`, `.apk`, fixtures | (repo) | secrets/binaries in VCS |

## §D. Root-cause analysis — why trust keeps breaking

1. **No single source of truth.** The Go mux is de-facto authoritative, the phone
   is supposed to be, the web keeps its own optimistic copy, and the cloud box
   kept its own DB. Four "truths" ⇒ every reconciliation surfaces a "surprise."
2. **One binary, many roles, no mode.** The same relay is LAN relay, cloud peer,
   and CLI launcher. Deploying it to `public.computer` *necessarily* started
   producers + a journal ⇒ "phantom sessions." This was structural, not a slip.
3. **Identity by string-reconstruction.** `cursorCWDFromPath` rebuilds cwd as
   `/Users/...` on every OS; thread identity (sha256 agent::cwd) is re-coded in Go,
   JS, and implied elsewhere. Cross-OS hosts therefore mislabel and could collide.
4. **"Delivered" never meant "landed."** Every layer (Go `delivery`, the store,
   the web UI, the Apple `/ingest` path) reports success on *handing bytes to a
   channel*. `MarkLanded` exists but is never called. The product's core promise
   is unverified at every tier — exactly the repeated complaint.
5. **No one contract, many clients.** WS (web, native) vs REST-poll (core libs,
   vendors) vs `/ingest` (apple). Chip/yank/state logic re-implemented 4–5 times.
   `enter` removed in Go but alive in PROTOCOL.md and three clients. Drift is the
   default state, so behavior differs by surface and "works on one, not another."
6. **Open, unscoped control plane.** Unauthenticated WS/status/pty, optional hook
   auth, mDNS on any LAN, and OS keystroke injection ⇒ fragile, surprising
   behavior across environments (hotel VLANs, shared boxes) and a real security
   surface.
7. **Scaffolds presented as platforms.** Three near-empty vendor repos imply
   working multi-platform support; testing any of them "uncovers" that they were
   never wired — a recurring let-down.

## §E. Keep / Rebuild / Delete verdict

**Keep (works, well-shaped):**
- The three Go producers (hooks/JSONL/proc) — observation is solid.
- The Go mux state machine + the SQLite schema — relocate ownership to the phone.
- The Meta DAT HUD path (`DatDisplaySession` + presenter) and the web design system.
- The backpressure primitive (collapse to one definition).
- The Apple FoundationModels/App-Intents idea (re-point off the cloud, fix reply).

**Rebuild (the rotten spine):**
- Delivery + receipts with transcript-confirmed `landed` (wire the dead `MarkLanded`).
- Explicit `edge`/`hub`/`forward` roles so a forwarder cannot hold state.
- One protocol module + enforced `PROTOCOL_VERSION`; generate clients from it; one
  RelayClient per platform (delete the duplicates).
- Phone as source of truth; laptops as edges syncing a local segment.
- Identity carried as data; delete path-reconstruction.

**Delete:**
- "Same binary is also the cloud relay" behavior; debug endpoints in release;
  `enter` remnants; web probe/harness experiments; committed `.keystore`/`.apk`.
- Active scope on the three vendor scaffolds until Meta is solid (freeze + label).

> The full target design is in [`IDEAL-ARCHITECTURE.md`](./IDEAL-ARCHITECTURE.md).
