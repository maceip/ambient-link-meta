# Blockers tracker

Single source of truth. Update here — not in chat. Crush one row; log in **Fix log**; run **Verify scripts** before claiming fixed.

**Last updated:** 2026-07-03

---

## Verify scripts (run in order)

| Script | Gates | Last run |
|--------|-------|----------|
| `scripts/verify-demo-env.sh` | Host, adb/APK, prod BUILD/SW, protocol, inject diag | 2026-07-03 pass (yellow: no phone, prod v53) |
| `scripts/diagnose-inject.sh` | PID/TTY endpoints, outbox, agent processes | 2026-07-03 outbox empty ✓ |
| `scripts/test-protocol.sh` | debug yank + inject delivered | 2026-07-03 pass |
| `scripts/test-chip-contract.sh` | yes/no questions, quick-reply order | 2026-07-03 pass |
| `scripts/verify-lan-config.sh` | POST `/ambient-link/config` | 2026-07-03 pass |
| `scripts/install-android.sh` | APK on device | blocked: no adb |
| `web/test/*.spec.mjs` | glasses web layout, D-pad, session flow | prod v53 green (pre-v54 deploy) |

---

## Three failure modes

| Mode | Scope | Repo | Status |
|------|-------|------|--------|
| **1 Environment** | Meta SDK, APK install, stale SW, host LAN | meta + device | 🟡 scripts ready; phone + v54 deploy pending |
| **2 Meta partial fixes** | Snooze, chips, web list, LAN cwd, HUD policy | meta `12188c5`, `3189520`, `342d326` | 🟡 automated smoke green; device proof pending |
| **3 Core delivery** | Inject → terminal; delivered vs landed | core `4552e76` | 🟡 MarkLanded + outbox purge live; claude needs running process |

---

## Seams & wrinkles (track, don’t re-litigate in chat)

| Seam | What goes wrong | Where it lives | Track as |
|------|-----------------|----------------|----------|
| Meta SDK ≠ Meta AI BT | Glasses connected in Meta AI but HUD dead | `WearablesRepository` registration | B-001 |
| Observe ≠ deliver | Cards/WS work; terminal silent | proc registry, inject, outbox | B-002 |
| `thread_idle` ≠ `hud_yank` | Debug yank pops; real idle often doesn’t | Android `HudPresenter.onIdle` | B-003 |
| `delivered` ≠ `landed` | UI says sent before transcript confirms | core store + web `applyOutboxStatus` | B-002, B-203 |
| Cloud WS ≠ Mac HTTP | cwd save hits wrong host | `RelayLanStore`, `RelayConfig` | B-101 |
| SW cache ≠ deployed BUILD | Old theme bar / focus trap | `sw.js`, `index.html` BUILD | B-004 |
| Glasses web ≠ phone HUD | Web can’t type; DAT path differs | platform | B-005 |
| No claude PID | Inject queues forever | proc watcher, outbox | B-002 |
| Playwright ≠ Meta Display | Browser passes; glasses differ | test scope | all web B-20x |

---

## Commits (reference)

| Repo | Commit | Summary |
|------|--------|---------|
| meta | `342d326` | Android settings, LAN store, snooze/quick-reply |
| meta | `3189520` | HUD yes/no, idle policy, dictate race |
| meta | `12188c5` | verify scripts, v54 SW, honest delivery UI |
| core | `4552e76` | MarkLanded, FanoutInputStatus landed, outbox purge |

**Host:** rebuilt `/tmp/ambient-link-host`, restarted 2026-07-03, listen `0.0.0.0:5181`, LAN `ws://192.168.1.33:5181/ambient-link/ws`

**Prod web:** v53 live; v54 in git, deploy pending

---

## Crush order

```
1. B-007   install-android.sh
2. B-001   Meta SDK registered
3. B-004   deploy web v54 + force-quit Meta AI
4. B-301   phone hits Mac healthz (same Wi‑Fi)
5. B-101   cwd save from phone on cloud WS
6. B-007   fire test widget
7. B-002   chip tap → terminal (claude running)
8. B-003   real idle → card
9. B-006   dictate E2E
10. B-105  reconnect loop if still present
```

---

## P0 — Demo path

### B-001 · Meta SDK registration

| Field | Value |
|-------|-------|
| Symptom | `UNAVAILABLE`, `devices=0`; no HUD |
| Cause | SDK registration separate from Meta AI pairing |
| Attempts | No launch spam; status rail Meta/Relay; glasses icon OAuth |
| Status | 🔴 open |
| Next | BT perms → glasses icon → REGISTERED + CONNECTED |
| Verify | Debug → fire test widget |

### B-002 · Inject to live agent

| Field | Value |
|-------|-------|
| Symptom | Chip tap silent in terminal |
| Cause | No PID/TTY for session → outbox queue; or agent not running |
| Attempts | Proc registry + PTY inject; outbox purge >7d; MarkLanded on JSONL user_prompt; web stopped fake delivered; diagnose-inject.sh |
| Status | 🟡 partial |
| Next | Start claude in terminal; confirm endpoint in diagnose-inject; web UI for `landed` status |
| Verify | Tap chip → line in terminal ≤2s; `input_status: landed` on WS |

### B-003 · Real idle → HUD card (Android)

| Field | Value |
|-------|-------|
| Symptom | push-chat/debug yank works; agent pause doesn’t |
| Cause | `onIdle` suppresses DONE idles; iOS more eager |
| Attempts | question/permission bypass quiet suppress (`3189520`) |
| Status | 🟡 partial |
| Next | Policy for DONE idles; optional host `force_hud_yank` |
| Verify | Live agent pause → peek without debug yank |

### B-004 · Stale glasses web / SW

| Field | Value |
|-------|-------|
| Symptom | Theme bar focus trap, no D-pad |
| Cause | Cached SW |
| Attempts | v49–54: no theme bar; network-first SW; BUILD purge; controllerchange reload |
| Status | 🟡 v54 in git; prod v53 |
| Next | Deploy v54 |
| Verify | prod BUILD=v54; Playwright core-session-flow |

### B-005 · Glasses web text input

| Field | Value |
|-------|-------|
| Symptom | Can’t type on glasses |
| Cause | Meta Display platform limit |
| Status | ⚪ external |
| Demo path | List on web; send via HUD chips / phone dictate |

### B-006 · Dictate / question chips

| Field | Value |
|-------|-------|
| Symptom | yes+dictate confusion; dictate reloads peek |
| Attempts | yes/no on question; `contentJob` serializes peek/dictate; cancel-only dictate UI |
| Status | 🟡 code; device unproven |
| Verify | Dictate → listening + cancel only |

### B-007 · APK not on phone

| Field | Value |
|-------|-------|
| Attempts | `install-android.sh`; builds `3189520`+ |
| Status | 🔴 blocked (no adb) |
| Verify | Theme pills; snooze tap-twice clears |

---

## P1 — Android

### B-101 · Cwd save on cloud WS — 🟡 automated ✓, device pending
### B-102 · Discover LAN — 🟡 partial (mDNS + cached LAN)
### B-103 · Snooze toggle — 🟢 code (`342d326`), needs install
### B-104 · Quick replies on peek — 🟢 code (`342d326`), needs install
### B-105 · Reconnect loop — 🟡 partial
### B-106 · SODA / dictate — 🟡 partial
### B-107 · Auto-start / pairing UX — 🔴 open

---

## P1 — Glasses web

### B-201 · Session list design — 🟢 prod
### B-202 · D-pad / focus — 🟢 prod
### B-203 · Delivered UI honesty — 🟡 partial (web fixed dequeue lie; landed fan-out wired; UI pending)
### B-204 · New session from glasses — 🟡 partial

---

## P1 — Mac relay (core)

### B-301 · LAN reachability — 🟢 host on 0.0.0.0:5181; phone proof pending
### B-302 · Phantom session labels (cloud) — 🔴 open
### B-303 · Idle debounce 2s vs 4s — 🔴 open
### B-304 · Open LAN auth — P2

---

## P2 — Architecture

B-401 phone-as-SoT · B-402 web↔phone latency · B-403 vendor scaffolds · B-404 protocol version

See `phone-source-of-truth-plan.md`, `ARCHITECTURE-REVIEW.md`.

---

## Fix log

| Date | ID | Change | Result |
|------|-----|--------|--------|
| 2026-07-03 | B-002,B-203 | core MarkLanded + FanoutInputStatus; web no fake delivered; host restart; outbox purged | 🟡 |
| 2026-07-03 | env | verify-demo-env, install-android, diagnose-inject, verify-lan-config, test-chip-contract | 🟡 |
| 2026-07-03 | B-004 | web v54 SW/BUILD | 🟡 deploy pending |
| 2026-07-03 | B-003,B-006 | idle policy; yes/no; contentJob | 🟡 device |
| 2026-07-03 | B-103,B-104,B-101 | Android settings + LAN store | 🟡 device |
| earlier | B-201,B-202 | web v49–53 prod | 🟢 |

---

## Commands

```bash
scripts/verify-demo-env.sh      # gate everything automatable
scripts/install-android.sh      # when adb connected
scripts/diagnose-inject.sh      # PID/outbox truth
scripts/start-host.sh           # Mac relay
scripts/push-chat.sh "msg"      # test yank content
scripts/glasses-question.sh "?" # test question card
cd web/test && npx playwright test
```
