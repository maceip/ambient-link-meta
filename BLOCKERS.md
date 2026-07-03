# Known blockers & fix attempts

Living tracker for demo recovery. **Crush one row at a time** — when something moves, update Status + add a line under Fix attempts.

Last updated: 2026-07-03 (HUD fixes: question yes/no, idle policy, dictate race — build ok, not installed)

---

## How to use this doc

| Column | Meaning |
|--------|---------|
| **P0** | End-to-end demo cannot proceed |
| **P1** | Broken UX; workaround may exist |
| **P2** | Architecture / future; not required for desk demo |
| **Status** | 🔴 open · 🟡 partial · 🟢 fixed · ⚪ external (Meta/hardware/core repo) |

**Verify** = the smallest test that proves the fix (device log, curl, Playwright, one glasses tap).

---

## P0 — Demo path blockers

### B-001 · Meta SDK link lost after reinstall / USB churn

| | |
|---|---|
| **Symptom** | face·chat shows `RegistrationState.UNAVAILABLE`, `devices=0`; HUD cards never render even though Meta AI app shows glasses connected |
| **Cause** | Meta Wearables SDK registration is separate from Meta AI pairing; reinstalling APK or heavy USB debugging can drop SDK registration while BT to glasses still works in Meta AI |
| **Fix attempts** | Documented “tap glasses icon → connect Meta AI”; avoided auto-connect-on-launch spam; status rail shows Meta vs Relay separately |
| **Status** | 🔴 open (environmental) |
| **Next** | On phone: grant BT permissions → tap header glasses icon → complete Meta AI OAuth. Confirm `RegistrationState.REGISTERED` + display device `LinkState.CONNECTED` before any HUD test |
| **Verify** | Debug → “Fire test widget on glasses” → card appears, OK dismisses |

---

### B-002 · Chip tap / dictate does not reach live agent

| | |
|---|---|
| **Symptom** | Tap yes/continue on glasses → nothing happens in real Claude/Cursor terminal; or host returns `ok` but agent never sees text |
| **Cause** | **Observe path works; deliver path mismatched.** Host `inject.SendInput` routes cursor → inbox, claude/codex → tmux session `fc-{agent}`. Real agents often run in normal terminals, not those tmux sessions. “Delivered” = written to channel, not confirmed in transcript (`MarkLanded` never wired — see ARCHITECTURE-REVIEW §2.5) |
| **Fix attempts** | Fixed fake ack loop on host; yes/no on peek for questions; inbox path verified for cursor test threads; `push-chat.sh` + debug inject tested locally |
| **Status** | 🟡 partial — proc registry + PTY path works when session mapped (`test-protocol.sh` → `delivered`); claude messages can still queue in outbox if no live endpoint |
| **Next** | In **ambient-link-core**: bind inject to proc registry PID/TTY for live sessions, or document “run agents in fc-claude / fc-codex tmux”. Add inject trace logging visible in `/ambient-link/status` |
| **Verify** | Chip tap → new line in **your** active agent terminal within 2s; host log shows `Delivered` + transcript shows user turn |

---

### B-003 · `thread_idle` vs `hud_yank` — cards don’t pop for real agent pauses (Android)

| | |
|---|---|
| **Symptom** | Manual debug yank / `push-chat.sh` shows cards; real agent going idle often does not |
| **Cause** | Android `HudPresenter.onIdle()` suppresses most `thread_idle` events (snooze, quiet period, occupied display, web companion focus, post-reply suppress). iOS `onIdle()` calls `yank()` much more eagerly |
| **Fix attempts** | Post-reply suppress to avoid re-open loop; web display queue when companion has session focused; permission-only refresh while card open |
| **Status** | 🟡 partial — actionable idles (question/permission) now yank immediately; DONE idles still guarded |
| **Next** | Decide policy: pop on `thread_idle` when `awaiting != done` OR always pop `hud_yank` from host. Align Android with iOS for `permission`/`question`. Add host flag `force_hud_yank` on idle |
| **Verify** | Pause live Claude session → peek card on glasses without manual debug yank |

---

### B-004 · Glasses web: stale service worker / old UI

| | |
|---|---|
| **Symptom** | Theme bar focus trap (v45), missing D-pad, can’t reach session list |
| **Cause** | Meta Display caches web app; SW served old `app.js` until force-quit Meta AI |
| **Fix attempts** | v49 removed `#theme-bar` / `#conn-status`; v50+ SW `controllerchange` auto-reload; cache bump per deploy (v53); Playwright 11/11 on prod |
| **Status** | 🟢 fixed on prod — 🔴 if user still on cached build |
| **Next** | Force-quit Meta AI after deploy; confirm no `#theme-bar` in running app |
| **Verify** | `https://public.computer/ambient-link/` → D-pad through list → Enter opens session (Playwright `core-session-flow.spec.mjs`) |

---

### B-005 · Glasses web: text input unsupported on device

| | |
|---|---|
| **Symptom** | Cannot type in composer on glasses; demo “list → open → type → send” fails on-device |
| **Cause** | Meta Display web runtime does not support text fields (documented); typing is phone/native path |
| **Fix attempts** | Phone relay + dictate chips; web composer works in browser/Playwright only |
| **Status** | ⚪ external (platform limit) |
| **Next** | Demo script: list/browse on glasses web; send via HUD chips or phone dictate — not web keyboard |
| **Verify** | N/A on glasses; web composer works at same URL in desktop Chrome |

---

### B-006 · Dictate loop / wrong chips on question cards

| | |
|---|---|
| **Symptom** | Question card shows yes + dictate; tap dictate → same modal reloads; confusing chip set |
| **Cause** | Mixed question detection; dictate re-renders peek shell; SODA/mic not ready shows error card |
| **Fix attempts** | Dictate state shows **cancel** only (`HudWidgets.sendDictating`); question → yes/no chips; removed fake host reply loop |
| **Status** | 🟡 partial — yes/no on question cards; single `contentJob` prevents peek overwriting dictate UI |
| **Next** | Device test: mic permission granted, SODA build variant; confirm `State.DICTATING` path. For `awaiting=question`, consider hiding dictate on first peek |
| **Verify** | Tap dictate → body shows “You: listening…” + cancel chip only; no yes/dictate row |

---

### B-007 · Phone APK not installed / not on USB

| | |
|---|---|
| **Symptom** | Settings/LAN fixes exist only in git; phone still runs old build |
| **Cause** | `adb devices` empty during last build |
| **Fix attempts** | `assembleDebug` succeeded; commit `342d326` |
| **Status** | 🟡 partial — built, not installed |
| **Next** | Plug phone → `cd relay-android && ./gradlew installDebug` |
| **Verify** | Settings shows Theme pills + collapsible Debug; snooze pill tap-twice clears |

---

## P1 — Phone companion (Android)

### B-101 · Cwd Save while relay on cloud WS

| | |
|---|---|
| **Symptom** | Save directory fails when Relay URL is `wss://public.computer/...` |
| **Cause** | POST `/ambient-link/config` must hit Mac HTTP, not cloud |
| **Fix attempts** | `RelayLanStore` remembers last LAN ws; `RelayConfig.resolveHostHttpBase()` mDNS + cached LAN + health probe |
| **Status** | 🟡 partial — not device-verified |
| **Next** | Phone on cloud WS + Mac on same Wi‑Fi → Save cwd → expect “Directory saved” |
| **Verify** | Mac `curl localhost:5181/ambient-link/config` reflects new default_cwd |

---

### B-102 · Discover → “No host on LAN”

| | |
|---|---|
| **Symptom** | Mac relay running but Discover fails |
| **Cause** | mDNS blocked (AP isolation), timeout, or host not advertising `_ambientlink._tcp` |
| **Fix attempts** | 10s discovery timeout; fallback to cached LAN URL if healthz OK; clearer error with last seen URL |
| **Status** | 🟡 partial |
| **Next** | Confirm Mac `host serve` advertises mDNS; try manual `ws://MAC_IP:5181/ambient-link/ws` in Debug |
| **Verify** | Discover → connects without typing IP |

---

### B-103 · Snooze pills don’t toggle off

| | |
|---|---|
| **Symptom** | Once snoozed, can’t deselect duration pill except “End snooze now” button |
| **Fix attempts** | `pickSnoozeMinutes()` — tap active pill again clears; restore label on reopen via `LaunchedEffect` |
| **Status** | 🟢 fixed in `342d326` (needs install) |
| **Verify** | Tap 1h pill twice → snooze cleared, pill deselected |

---

### B-104 · Quick reply chips missing on peek cards

| | |
|---|---|
| **Symptom** | Only continue/dictate show; custom quick replies never appear |
| **Cause** | `MAX_CHIPS=3` and continue+dictate filled row before user replies |
| **Fix attempts** | Reordered `ChipSet.buildActionRow`: quick replies first, then dictate, then continue; dedupe “continue” text |
| **Status** | 🟢 fixed in `342d326` (needs install) |
| **Verify** | Add “looks good” in Settings → agent idle card shows it as chip |

---

### B-105 · Relay reconnect loop on app open

| | |
|---|---|
| **Symptom** | App opens constantly “reconnecting” though already connected |
| **Cause** | `RelayService.start()` force-reconnect logic; duplicate foreground service restarts |
| **Fix attempts** | Skip restart when same URL + connected; `restartMutex`; don’t auto-discover on every launch (user must Start/Discover) |
| **Status** | 🟡 partial — reported again after pairing changes |
| **Next** | Log `skip restart` vs `doRestart` on cold open; reproduce with adb logcat |
| **Verify** | Force-stop → open app → no reconnect storm unless user taps Start |

---

### B-106 · SODA / speech not in all APK builds

| | |
|---|---|
| **Symptom** | Dictate fails with “speech pack missing” or “soda_unavailable” |
| **Cause** | SODA native libs + assets require specific build flavor / pre-load |
| **Fix attempts** | Soda smoke instrumented test; preload toggle in Debug; error strings in `HudPresenter.showDictateError` |
| **Status** | 🟡 partial |
| **Next** | Confirm release/debug variant includes SODA assets; run `SodaSmokeTest` on device |
| **Verify** | Dictate → partial transcript updates on card |

---

### B-107 · Auto-start daemon / pairing UX

| | |
|---|---|
| **Symptom** | User must manually enter relay URL and tap Start — not product-ready |
| **Cause** | No QR pairing, no “always-on” daemon policy, host defaults to 127.0.0.1 |
| **Fix attempts** | mDNS discovery; cloud fallback URL in `BuildConfig`; LAN→cloud failover after 8s |
| **Status** | 🔴 open (product) |
| **Next** | Pair-once flow (QR or deep link with token); auto-start service on boot when paired |
| **Verify** | Fresh install → one tap → connected to Mac or cloud without typing URL |

---

## P1 — Glasses web companion

### B-201 · Session list vs validation gate design

| | |
|---|---|
| **Symptom** | List didn’t match WhatsApp/Instagram reference (pills, mute, conn dot, max 4 rows) |
| **Fix attempts** | v49–v53: ig-list pills, green time, mute icon, per-row conn dot, removed sessions header/theme bar; overlay capture at 600×600 @ 50% |
| **Status** | 🟢 fixed on prod |
| **Verify** | Playwright `session-list-layout.spec.mjs` + `validation-overlay-50pct.png` |

---

### B-202 · D-pad navigation / focus trap

| | |
|---|---|
| **Symptom** | Couldn’t navigate list or open session with glasses D-pad |
| **Fix attempts** | v51 `wireDpadNavigation()`; focus scoped to active view; auto-focus first row |
| **Status** | 🟢 fixed on prod |
| **Verify** | Playwright `dpad-navigation.spec.mjs` |

---

### B-203 · “Delivered” UI lies (written ≠ landed)

| | |
|---|---|
| **Symptom** | Web shows message sent before agent actually received it |
| **Cause** | UI infers delivery from outbox dequeue; host never calls `MarkLanded` |
| **Fix attempts** | Documented in ARCHITECTURE-REVIEW; no code fix yet |
| **Status** | 🔴 open (core + web) |
| **Next** | Wire transcript confirmation → `input_status: landed` on WS; web shows pending until landed |
| **Verify** | Send message → UI stays pending until JSONL shows user turn |

---

### B-204 · New session from glasses

| | |
|---|---|
| **Symptom** | Create session flow flaky in E2E |
| **Fix attempts** | v52 `window.__ambientOpenNew`; split Playwright create test from POST probe |
| **Status** | 🟡 partial |
| **Next** | On-device create with D-pad; confirm `POST /ambient-link/sessions` + list refresh |
| **Verify** | Playwright create spec green + manual glasses create |

---

## P1 — Mac relay (ambient-link-core)

### B-301 · Host listen address / LAN reachability

| | |
|---|---|
| **Symptom** | Phone can’t reach Mac even with “right” IP |
| **Cause** | Host bound to 127.0.0.1 or firewall; wrong path |
| **Fix attempts** | Docs for `0.0.0.0:5181`; mDNS; phone LAN store |
| **Status** | 🟡 partial |
| **Next** | `AMBIENT_LINK_LISTEN=0.0.0.0:5181`; confirm phone curl `http://MAC:5181/healthz` |
| **Verify** | Phone Discover or manual ws URL connects |

---

### B-302 · Phantom / wrong session labels (cloud host)

| | |
|---|---|
| **Symptom** | Sessions show wrong cwd (e.g. `/Users/mac/...` on non-Mac cloud box) |
| **Cause** | JSONL tailer `cursorCWDFromPath` assumes macOS paths; cloud relay ingests unrelated transcripts |
| **Fix attempts** | Documented in ARCHITECTURE-REVIEW |
| **Status** | 🔴 open |
| **Next** | Scope JSONL watch to configured home; fix cwd decode per OS |
| **Verify** | Session labels match actual project folders |

---

### B-303 · Idle debounce mismatch (2s vs 4s)

| | |
|---|---|
| **Symptom** | Cards fire too early or too late vs protocol doc |
| **Cause** | `mux.WithDefaults` 4s vs PROTOCOL.md 2s |
| **Fix attempts** | None unified |
| **Status** | 🔴 open |
| **Next** | Pick one value; update doc + code + tests |
| **Verify** | Agent pause → single `thread_idle` after N seconds |

---

### B-304 · Optional auth / open LAN control plane

| | |
|---|---|
| **Symptom** | Anyone on LAN can inject |
| **Cause** | Bearer token optional on hooks/WS |
| **Fix attempts** | None for demo |
| **Status** | P2 for demo |
| **Next** | Require token for prod LAN; phone stores token from pairing |
| **Verify** | WS without token rejected when configured |

---

## P2 — Architecture / future (not demo blockers)

### B-401 · Phone as source of truth

| | |
|---|---|
| **Doc** | `phone-source-of-truth-plan.md` |
| **Blocker** | Glasses web may not reach phone locally (HTTPS page → LAN ws blocked; BT tether) |
| **Status** | 🔴 planning — spike required before W→N transport |
| **Next** | On-device WebRTC / reachability spike per plan §Sequencing step 1 |

---

### B-402 · Web ↔ phone local latency

| | |
|---|---|
| **Symptom** | Round-trip to Mac/cloud too slow for instant HUD feel |
| **Cause** | Glasses web talks to relay origin, not phone DAT path |
| **Status** | 🔴 architectural |
| **Next** | Native DAT HUD remains instant path; web is browse/compose backup |

---

### B-403 · iOS / Google / Snapchat / Apple vendor repos

| | |
|---|---|
| **Status** | Scaffolds only (`ProjectedGlassLink.kt` all TODOs, Lens Studio read-only, etc.) |
| **Next** | Ignore for Meta Ray-Ban demo |

---

### B-404 · Protocol version enforcement

| | |
|---|---|
| **Symptom** | Client/server mismatch fails silently |
| **Fix attempts** | Version only in PROTOCOL.md |
| **Next** | Add `PROTOCOL_VERSION` to hello; clients refuse mismatch |

---

## Suggested crush order (demo-first)

Work top to bottom; don’t start a lower item if a P0 above it is still red for your setup.

```
1. B-007  Install APK (342d326)
2. B-001  Meta SDK registered + glasses connected
3. B-301  Mac reachable on LAN (healthz from phone)
4. B-101  Cwd save (confirms LAN HTTP path)
5. B-004  Glasses web on v53 (force-quit Meta AI)
6. B-007  Fire test widget (DAT path OK)
7. B-002  Inject reaches YOUR terminal (core fix)
8. B-003  Real agent idle pops card (Android policy)
9. B-006  Dictate end-to-end (mic + SODA)
10. B-105 Reconnect loop (if still happening)
```

---

## Fix log (append-only)

| Date | ID | What we tried | Result |
|------|-----|---------------|--------|
| 2026-07-03 | B-003 | Actionable `thread_idle` (question/permission) bypasses quiet suppress | 🟡 code, needs device |
| 2026-07-03 | B-006 | yes/no chips; HudWidgets `contentJob` serializes peek vs dictate | 🟡 code, needs device |
| 2026-07-03 | B-002 | Ran `test-protocol.sh` — inject `delivered` to live codex session | 🟡 host path works when mapped |
| 2026-07-03 | B-103 | Snooze pill re-tap clears | 🟢 in 342d326 |
| 2026-07-03 | B-104 | Quick replies prioritized in ChipSet | 🟢 in 342d326 |
| 2026-07-03 | B-101 | RelayLanStore + resolveHostHttpBase | 🟡 code landed, not device-tested |
| 2026-07-03 | B-102 | Discover 10s + cached LAN fallback | 🟡 code landed, not device-tested |
| 2026-07-03 | B-201–204 | Web v49–v53 deploy + Playwright | 🟢 prod |
| earlier | B-002 | Removed fake ack loop; yes/no on peek | 🟡 inject path still wrong for live tmux |
| earlier | B-004 | Removed theme bar; D-pad; SW reload | 🟢 prod |

---

## Commands cheat sheet

```bash
# Mac relay
scripts/start-host.sh
curl -sf http://127.0.0.1:5181/healthz

# Push test card content
scripts/push-chat.sh "peek card test"

# Android
cd relay-android && ./gradlew installDebug
adb logcat -s RelayClient HudPresenter RelayService RelayConfig

# Web tests (prod)
cd web/test && npx playwright test

# Protocol smoke
scripts/test-protocol.sh
```
