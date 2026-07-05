# To delete or not to delete

**Written:** 2026-07-03  
**Last updated:** 2026-07-03 (after ADB phone session + LAN bugfix)  
**Purpose:** Honest assessment so you can decide — not to talk you into keeping broken work.  
**Quality bar:** *Would I copy this code into a new project and build on it?* Not “is there something salvageable somewhere.”

**Your goal (unchanged for three weeks):** On Meta Display glasses, see agent sessions, send a message back, receive updates — simply, reliably.

**Current reality:** That loop is **not** working as a product. Agent text sometimes appears (especially via cloud). User messages, chat history, dictate, touch, and first-run pairing are broken or unproven. The UI *looks* good; almost nothing underneath behaves like something you would ship.

---

## Executive verdict

| Question | Answer |
|----------|--------|
| Is the integrated product good code? | **No.** |
| Would I copy this stack as-is? | **No** — except isolated pieces (see below). |
| Is a fourth full rewrite justified? | **Yes for meta (web + Android shell).** **No for core Go relay** — that part is the only serious engineering in the system. |
| Did tests protect you? | **No.** Tests were shallow, not run on deploy, and removed entirely this session. They never asserted send → bubble → history. |
| Is this a Mac-only product? | **No — but the app copy and debug UX assume it is.** That mismatch is a product-quality failure, not a small wording fix. |
| Are you missing “simple fixes” because of noise? | **A few real ones** (LAN startup bugs — now partially fixed). **Most failures are architectural** — wrong chat data model, too many clients, cloud deployed wrong, no landed proof. |

**If you do nothing else:** Stop iterating on `web/app.js` and `MainActivity.kt` feature surface. Either archive and restart those, or pick **one** native-only demo path (HUD chip → inject → agent reply on glasses).

---

## What we learned today (ADB on your phone — facts)

Session on device `2506BPN68G`, package `com.lowkey.ambientlink`, Mac LAN `192.168.1.33:5181`.

### Network is fine; the app lied

| Check | Result |
|-------|--------|
| Phone → Mac ping `192.168.1.33` | **OK** (~11 ms) |
| Phone → Mac HTTP `GET /healthz` (via `nc`) | **OK** (`200 ok`) |
| Mac relay listening | **OK** (`*:5181`) |
| Mac `POST /ambient-link/config` with `~/ambient-link-meta` | **OK** (`exists: true`) |
| Phone mDNS `_ambientlink._tcp` | **Not observed** in quick browse (common on consumer Wi‑Fi) |

The phone **can** reach the relay. “No Mac relay found” was **app logic**, not Wi‑Fi.

### Root bugs found (and one fix shipped tonight)

1. **LAN health probe on the main thread** — `RelayConfig.isReachableWs()` did synchronous HTTP from the UI/main coroutine. Android throws `NetworkOnMainThreadException`; the catch returned `false`. Cached LAN URLs were always rejected as unreachable.

2. **Saved `relay_url` ignored on cold start** — `MainActivity` called `RelayService.start(null)` on every `onStart`. The service never read prefs; it waited 10s for mDNS, ran the broken probe, then gave up with “LAN-only — no Mac relay found.”

3. **Manual IP only worked via Debug → “Connect to Mac IP”** — That path passes an explicit URL and skips discovery. It **did** connect in testing (`RelayClient: connected ws://192.168.1.33:5181/...`). But reopening the app undid it until tonight’s fix.

4. **After fix (installed on your phone):** Cold start logcat shows `relay: using saved LAN ws://192.168.1.33:5181/ambient-link/ws` and `connected` in ~50 ms — **no manual IP, no Discover wait**.

### What is still bad about LAN (even after the fix)

- **First pairing** still has no good UX: Discover often fails; fallback is typing an IP like it’s 2005.
- **Copy is wrong for the product:** “Connect to **Mac** IP”, “LAN-only: not connected to **Mac**”, “Can't reach your **Mac** on Wi‑Fi” — scattered across `MainActivity.kt`, `RelayConfig.kt`, `RelayService.kt`. The relay runs on **a host on the LAN** (Mac today, Linux/Windows tomorrow). This reads as a hacky debug panel, not a product.
- **`debug_lan_only=true` in your prefs** — LAN-only is a debug flag that became the default failure mode. Cloud fallback (the path that sometimes showed agent text) was disabled.
- **mDNS** — Still unreliable; saved URL is a band-aid, not pairing.
- **Working directory Save** — Mac API works; phone UI path was not cleanly verified end-to-end in UI after connect (Save tap / feedback ambiguous in automation).

### Verification run (Mac + phone, reproducible)

| Check | Result |
|-------|--------|
| Local relay `http://127.0.0.1:5181/healthz` | **ok** |
| Local `/ambient-link/status` | Live sessions (count varies) |
| `scripts/verify-lan-config.sh` | **pass** |
| Cloud `https://public.computer/ambient-link/status` | Sessions present, **no laptop peer** (phantom/stale world) |
| `ambient-link-core/host`: `go test ./...` | **pass** |
| Web Playwright tests | **Deleted** (`web/test/` gone) |
| Phone cold start → LAN WS (after fix) | **connected** (logcat) |
| Phone cold start → LAN WS (before fix) | **failed** after 10s mDNS |
| Meta Display glasses manual loop | **You report broken** (chat, dictate, touch) |

---

## Product-quality failures (not just bugs)

These are why the experience feels “everything is bad” even when one cable connects.

### 1. Mac-centric language in a cross-platform system

The Go relay is a **host relay** — it runs beside agents on whatever OS. The Android app talks as if the user owns a Mac on the same Wi‑Fi:

- Button: **“Connect to Mac IP”**
- Field: **“Mac IP (if Discover fails)”**
- Errors: **“Can't reach your Mac on Wi‑Fi”**

That is unacceptable in a settings surface. It should be **“Relay host”**, **“Connect to host”**, or automatic pairing — never “type your Mac’s IP in Debug.”

### 2. Debug panel as production onboarding

LAN pairing lives under **Debug**, next to daemon start/stop, relay URL monospace field, and Discover. Normal users (and you, tired after three weeks) should never see this. There is no first-run flow: no QR, no link code, no “open this URL on your laptop,” no cloud assist fallback that actually works.

### 3. Four sources of truth

| Layer | What it believes |
|-------|------------------|
| Mac mux + SQLite | Session state, last user/assistant, delivery |
| Cloud `public.computer` | Its own journal + phantoms when full relay runs |
| Web `app.js` | Hand-rolled chat log, polls status every 15s |
| Android HUD | Peek/engage cards, companion config push |

Nothing reconciles them. “Connected” on the phone does not mean the glasses web app is on the same origin, same session, or same chat model.

### 4. Chat UI on a protocol that cannot support it

The relay exposes **latest** `last_user_input` and `last_assistant`, not turn history. `web/app.js` (~1913 lines) repeatedly tried to build WhatsApp-style history anyway — replace, upsert, coalesce, sync from poll. **Wrong layer.** Any chat UI without a turn log from the relay will keep breaking.

### 5. Features before the one loop

Theme pills (on **phone** settings, not HUD bubbles), snooze, companion log, dictate bridges, LAN-only toggle, scroll/focus hacks — all landed before **“tap chip → message lands in agent → reply on glasses”** was stable and tested.

### 6. Tests that did not test the product

Playwright checked load, WS connect, row count, prompt editable. **Never:** send message → user bubble in DOM → second send → both bubbles → yank → agent append. Meta Display is not an excuse for zero web smoke tests.

### 7. Optimistic docs vs your experience

`BLOCKERS.md` green checks and version gates did not match glasses usage. Trust eroded faster than the tracker grew.

---

## Repo 1: `ambient-link-core` (Go relay + shared libs)

### Laptop relay (`host/`)

**What it does:** Watches Cursor/Claude/Codex on the host (hooks + JSONL tail + process watcher), holds session state in mux + SQLite, fans out over WebSocket, injects user input into agent terminals, optional cloud bridge outbound.

**Would I copy it?** **Mostly yes** for mux, store, producers, and the overall shape. This is the only part of the system that looks like engineered software with tests and a decision log (`DECISIONS.md`).

**What actually works (verified):**
- Go unit tests pass (`go test ./...`).
- Local relay serves status, WS, config API on `:5181`.
- Session observation from agents on this laptop.
- `POST /ambient-link/config` validates cwd on disk.
- MarkLanded exists in code + store tests.

**What does not work / not proven end-to-end:**
- **`delivered` ≠ `landed` in practice** — inject can succeed at the channel layer while the agent never takes the turn.
- **Cloud path** — bridge code exists; laptop ↔ cloud ↔ web round-trip never closed as a verified demo.
- **`POST /ambient-link/sessions`** returns 501 — create-from-glasses not wired.
- **Dictate commit path** can ingest to HUD even when inject fails.
- **No tests** for cloud, inject, discovery, main.

| Part | Verdict | Why |
|------|---------|-----|
| `internal/mux`, `store`, `producers` | **KEEP** | Best code in the project; tested. |
| `sink` (WS hub), `delivery`, `inject` | **KEEP, harden** | Right design; honesty bugs and missing integration tests. |
| `cloud` (Bridge + PeerServer) | **KEEP concept, REWRITE deploy** | Code OK; full relay on public.computer was a mistake. |
| `cmd/host` wiring | **KEEP** | Fine; trim 501 stubs or implement them. |

---

### Cloud assist (`public.computer`)

**What it should be:** Stateless forwarder — host relay dials out, web clients see **only** that relay’s sessions while connected.

**What it is:** Full relay binary + stale journal → sessions with **no host linked**. Wrong role, wrong data.

**Would I copy the deployment?** **No.** The idea in `RESTART-DECISION.md` is correct; what’s running is not.

**Verdict:** **DELETE the deployment model**, not necessarily the Go `cloud` package. Surgical server fix (Option A in `RESTART-DECISION.md`) is hours, not a repo rewrite.

---

### `core-android` / `core-apple`

**core-android:** Small, builds, zero unit tests, HTTP poll-only — not what meta’s relay app uses for WS.

**core-apple:** `swift test` fails; wrong ingest endpoint.

**Verdict:** **KEEP** as future extraction; **ignore** for current demo.

---

## Repo 2: `ambient-link-meta` (Android, iOS, web)

### Android app (`relay-android/`)

**What it does:** Phone relay daemon (FGS), WS to host/cloud, Meta DAT HUD cards, settings UI (theme, snooze, LAN, cwd), web dictation bridge, companion config push.

**Would I copy it?**

| File / area | Copy? | Notes |
|-------------|-------|-------|
| `RelayService.kt` | **Yes, with fixes** | Best module — but had LAN startup bugs; Mac-centric errors. |
| `RelayClient.kt` | **Yes** | Clean WS event model. |
| `DatDisplaySession.kt` | **Yes** | Essential Meta SDK glue. |
| `HudPresenter.kt` | **Ideas yes, whole file no** | Too much arbitration with web companion. |
| `HudWidgets.kt`, `ChipSet.kt` | **Yes** | Matches web chip contract. |
| `RelayDiscovery.kt`, `RelayLanStore.kt` | **Maybe** | Layered fallback is sound; mDNS + ops weak; needs IO-thread probes (fixed). |
| `RelayConfig.kt` | **Maybe** | cwd POST works when host reachable; error strings say “Mac”. |
| `MainActivity.kt` (~1180 lines) | **No** | God-screen; Debug-as-onboarding; “Connect to Mac IP”. |
| `WebDictationBridge.kt` | **No** | Part of broken dictate path. |
| Theme (`AmbientTheme.kt`) | **Wrong target** | Phone settings colors, not HUD on face. |
| Snooze | **Half product** | Not trustworthy with peek/web/relay. |

**What works (evidence):**
- Debug yank → card on glasses (prior demos).
- LAN WS connect when URL known (logcat, tonight).
- Cold start reconnect to saved LAN URL (after tonight’s fix).
- Theme pills / snooze change prefs on **phone** UI only.

**What does not work (your experience + review):**
- **First-run pairing** — no product flow; Debug + IP + mDNS.
- **Copy and mental model** — “Mac IP” everywhere; not Mac-only.
- **Dictate** — broken for days; competing paths.
- **Theme on glasses** — not implemented.
- **Settings surface** — grew faster than HUD reliability.

**Verdict:** **REWRITE the shell** (delete 70% of settings, kill Mac-centric strings, first-run pairing). **KEEP** RelayService + HUD + DatDisplaySession as kernel — after stripping debug flags from the default path.

**Tonight’s code changes (for the record):**
- `RelayConfig.isReachableWs` → IO dispatcher.
- `RelayDiscovery.discoverOrDirect` → IO dispatcher for probes.
- `RelayService.resolveRelayUrl` → read saved `relay_url` before mDNS.
- `MainActivity.savedLanRelayUrl()` → pass saved LAN URL on start.

These fix **reconnect**; they do **not** fix product-quality pairing or cross-platform copy.

---

### iOS (`relay-ios/`)

12 Swift files, manual Xcode drop-in, ChipSet diverged from Android/web.

**Would I copy?** **No.**

**Verdict:** **DELETE or freeze** until Android loop works.

---

### Web app (`web/`)

**Size:** `app.js` **1913 lines** — monolith. **Tests: none** (deleted).

**Would I copy it?**

| Part | Copy? |
|------|-------|
| `companion.css`, `blocks/blocks.css`, `blocks/blocks.js` | **Yes** — visual design you like. |
| `chipset.js` | **Yes** — small, clear chip rules. |
| `content-pipeline.js` | **Maybe** — glasses text filtering. |
| `app.js` | **No** — hand-rolled chat on snapshot fields; scroll/touch/SW churn. |

**What actually works:**
- Page loads; WS connects (often to cloud).
- Agent text can appear when yanks arrive.
- Debug ping button (meaningful recent progress).

**What does not work:**
- Chat history, user bubbles, ordering.
- Touch / double-tap on Meta Display.
- Service worker cache churn (v54…v65).
- Wrong origin (cloud phantoms vs LAN host).

**Verdict:** **DELETE `app.js` logic** (or entire web product surface). **KEEP CSS/blocks/chipset** as a design kit for ~300 LOC rewrite or native-only product.

**Tests that should have existed:**
1. Mock WS → send → `#w-chat` contains user text.
2. Second send → both user texts present.
3. Yank → agent bubble appended; user bubbles unchanged.
4. Script load smoke (`CS` / critical globals defined).

Zero glasses required.

---

## Cross-cutting: why it feels like “nothing works”

1. **Wrong cloud anchor** — Glasses web hits public.computer; phantoms and no laptop peer.
2. **LAN was broken in code** — Not Wi‑Fi; main-thread probe + ignored prefs (partially fixed).
3. **Pairing is a debug panel** — “Connect to Mac IP” is not a product.
4. **Chat impossible on current protocol** — Need turn log from relay.
5. **Feature velocity >> proof** — Polish and toggles before one stable loop.
6. **No effective tests** — Web smoke would have caught most pain.

---

## “Simple fixes” — honest list

| Fix | Effort | Daily driver? |
|-----|--------|---------------|
| ~~LAN startup bugs (IO probe, saved URL)~~ | Done tonight | **Reconnect only** — not first-run |
| Cloud: proxy-only + wipe journal (`RESTART-DECISION.md` A) | Hours (server) | Reduces phantoms; necessary |
| Rename “Mac IP” → “Host address”; hide Debug pairing | Hours | **Cosmetic** unless pairing flow added |
| Strip web to last message + send (no history) | 1 day | Ugly but might send/receive |
| Native-only: HUD chips + phone keyboard, pause web | Days | **Most likely “simple” path** |
| HUD theme tokens (not phone MaterialTheme) | Days | Polish |
| 5 Playwright chat smokes | Hours | Stops web regressions only |
| Relay protocol: append-only turn log | Days | **Required for real chat** |

**Not simple:** Dictate, Meta Display touch, trustworthy multi-client sync, cloud assist that works.

---

## Delete / keep / rewrite — decision matrix

| Component | Delete | Keep | Rewrite |
|-----------|--------|------|---------|
| **core Go mux/store/producers** | | ✓ | |
| **core Go cloud package** | | ✓ | deploy role only |
| **Cloud server (full relay deploy)** | ✓ | | proxy mode |
| **web/app.js** | ✓ | | from scratch (~300 LOC) OR drop web |
| **web CSS + blocks + chipset** | | ✓ | |
| **Android RelayService/Client/HUD/DAT** | | ✓ | strip Mac copy, default path |
| **Android MainActivity / Debug LAN UI** | ✓ (most of it) | | first-run pairing OR delete |
| **Android theme (current)** | ✓ | | HUD-scoped tokens |
| **iOS relay-ios** | ✓ (freeze) | | later |
| **BLOCKERS.md (as truth)** | ✓ | | manual demo checklist |
| **Playwright / test scripts** | deleted | | 5-test chat smoke if web survives |
| **RESTART-DECISION, IDEAL-ARCHITECTURE** | | ✓ | docs are honest |

---

## Recommended paths (pick one)

### Path 1 — Surgical (minimum throwaway)

- Fix cloud deployment (proxy + wipe phantoms).
- Phone: rely on saved LAN URL (done); **turn off `debug_lan_only` for daily use** unless you are actively testing LAN isolation.
- **Strip web to:** session list + last message + send. No chat log.
- **One demo gate:** HUD question card → tap yes → agent moves on host.
- **Delete or hide:** “Connect to Mac IP”, manual relay URL field, daemon start/stop from default UI.
- **Time:** days.

### Path 2 — Meta-native product (no web) — **best fit for “simple”**

- Archive `web/` except CSS/blocks reference.
- Android = relay daemon + HUD only; phone types replies.
- Host relay unchanged; cloud proxy fixed.
- First-run: one screen (“Link to host”) — not Debug.
- **Time:** ~1 week if scope frozen to one loop.

### Path 3 — Full restart (meta repo)

- New repo; copy: CSS/blocks, ChipSet rules, DatDisplaySession pattern, RelayService design notes, Go relay unchanged.
- Rewrite web from zero or skip web.
- **Time:** 2–3 weeks **if** scope is one loop only.

### Path 4 — Stop project

- Valid if the bar is “daily driver on glasses” and rewrite #4 still does not close the loop.
- Archive repos; extract design assets.

---

## What to carry into any restart

1. **Visual design** — `companion.css`, `blocks/*`, Meta DM patterns.
2. **Chip/yank model** — permission / question / done + quick replies.
3. **Go mux + observation pipeline** — tested, in core.
4. **Meta DAT knowledge** — `DatDisplaySession`, peek vs engaged.
5. **Architecture docs** — `RESTART-DECISION.md`, `IDEAL-ARCHITECTURE.md`.
6. **Tonight’s LAN lesson** — never probe network on main thread; never ignore saved host URL; never ship Debug as onboarding.
7. **What not to carry** — 1913-line `app.js`, god MainActivity, “Connect to Mac IP”, hand-rolled chat on snapshot fields, BLOCKERS green checks, phone theme mistaken for HUD theme.

---

## Bottom line

- **The user-facing loop is not good enough to call working.** Your instinct is correct.
- **Something underneath is good:** local Go relay (core) and Android relay/HUD kernel are reusable engineering.
- **The web app as integrated product should be treated as failed** — keep the paint, delete the engine.
- **Cloud as deployed today is actively harmful** — phantom sessions, wrong mental model.
- **LAN felt broken because the app was broken** — not because the phone couldn’t reach the host. Reconnect is fixed; **pairing and copy are still unacceptable**.
- **This is not presented as a Mac-only product** — but the UI says Mac everywhere. That alone signals hack quality.
- **Tests didn’t fail you — the right tests were never written.**

When you review: choose Path 1–4. Until then, **no more feature work** — only the one loop you care about, or archive.

---

*This document supersedes optimistic status in BLOCKERS.md for product-truth purposes. Verification commands from 2026-07-03 are reproducible on your Mac and phone via adb logcat + curl.*
