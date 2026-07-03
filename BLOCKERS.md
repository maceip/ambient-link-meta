# Blockers tracker

Single source of truth. Update here — not in chat.

**Last updated:** 2026-07-03

---

## Priority (impact ↓, then complexity ↑)

**Impact** (demo): 5 = loop dead · 4 = major path broken · 3 = degraded · 2 = polish · 1 = edge · 0 = done  
**Complexity**: 1 = minutes · 2 = hours · 3 = day · 4 = multi-day · 5 = architecture / external

Within the same impact band, **lower complexity first** (quick wins).

| Rank | ID | Impact | Cx | Status | Next action |
|------|-----|--------|-----|--------|-------------|
| 1 | B-007 | 5 | 1 | 🔴 | `scripts/install-android.sh` |
| 2 | B-001 | 5 | 2 | 🔴 | Meta SDK: BT perms → glasses icon → REGISTERED |
| 3 | B-002 | 5 | 3 | 🟡 | Claude running + chip tap → terminal; `landed` UI |
| 4 | B-004 | 4 | 1 | 🟡 | Deploy web v54 |
| 5 | B-301 | 4 | 1 | 🟡 | Phone curl Mac `healthz` on Wi‑Fi |
| 6 | B-003 | 4 | 2 | 🟡 | DONE idle policy / host `force_hud_yank` |
| 7 | B-107 | 4 | 5 | 🔴 | Pair-once + auto-start (post-demo) |
| 8 | B-102 | 3 | 2 | 🟡 | Discover on device; mDNS / manual LAN URL |
| 9 | B-204 | 3 | 2 | 🟡 | Create session on glasses + Playwright |
| 10 | B-006 | 3 | 3 | 🟡 | Dictate E2E on device |
| 11 | B-105 | 3 | 3 | 🟡 | Reconnect loop logcat repro |
| 12 | B-106 | 3 | 4 | 🟡 | SODA smoke on device |
| 13 | B-101 | 2 | 2 | 🟡 | Cwd save from phone on cloud WS |
| 14 | B-203 | 2 | 2 | 🟡 | Web UI shows pending until `landed` |
| 15 | B-005 | 3 | 5 | ⚪ | Demo script: HUD chips not web keyboard |
| 16 | B-103 | 1 | 1 | 🟢 | Install APK to verify snooze toggle |
| 17 | B-104 | 2 | 1 | 🟢 | Install APK to verify peek chips |
| 18 | B-303 | 2 | 1 | 🔴 | Unify idle debounce 2s vs 4s |
| 19 | B-302 | 2 | 4 | 🔴 | Cloud JSONL cwd / scope |
| 20 | B-304 | 1 | 3 | P2 | LAN auth token |
| 21 | B-201 | 0 | — | 🟢 | — |
| 22 | B-202 | 0 | — | 🟢 | — |
| 23 | B-401 | 1 | 5 | 🔴 | phone-as-SoT spike |
| 24 | B-402 | 1 | 5 | 🔴 | WebRTC W→N |
| 25 | B-403 | 0 | — | — | ignore for demo |
| 26 | B-404 | 1 | 2 | 🔴 | PROTOCOL_VERSION in hello |

**Crush order** = ranks 1–14 (stop when demo loop works).

---

## Verify scripts

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

## Failure modes

| Mode | Repo | Status |
|------|------|--------|
| 1 Environment | meta + device | 🟡 scripts ready; phone + v54 deploy pending |
| 2 Meta fixes | meta `12188c5`, `3189520`, `342d326` | 🟡 smoke green; device pending |
| 3 Core delivery | core `4552e76` | 🟡 MarkLanded live; claude needs process |

---

## Seams → blocker ID

| Seam | ID |
|------|-----|
| Meta SDK ≠ Meta AI BT | B-001 |
| Observe ≠ deliver | B-002 |
| `thread_idle` ≠ `hud_yank` | B-003 |
| `delivered` ≠ `landed` | B-002, B-203 |
| Cloud WS ≠ Mac HTTP | B-101 |
| SW cache ≠ BUILD | B-004 |
| Glasses web ≠ phone HUD | B-005 |
| No agent PID | B-002 |
| Playwright ≠ Meta Display | B-20x |

---

## Commits & infra

| Repo | Commit | Summary |
|------|--------|---------|
| meta | `342d326` | Android settings, LAN store |
| meta | `3189520` | HUD yes/no, idle policy, dictate race |
| meta | `12188c5` | verify scripts, v54 SW, delivery UI |
| meta | `3fc7562` | this tracker |
| core | `4552e76` | MarkLanded, outbox purge |

Host: `0.0.0.0:5181` · LAN `ws://192.168.1.33:5181/ambient-link/ws` · prod web v53 (v54 in git)

---

## Blocker detail (priority order)

### B-007 · APK not on phone — I5 C1 🔴

| Field | Value |
|-------|-------|
| Symptom | Phone runs old build; fixes not testable |
| Attempts | `install-android.sh`; commits `3189520`+ |
| Verify | Theme pills; snooze tap-twice clears |

### B-001 · Meta SDK registration — I5 C2 🔴

| Field | Value |
|-------|-------|
| Symptom | `UNAVAILABLE`, `devices=0`; no HUD |
| Cause | SDK registration ≠ Meta AI BT pairing |
| Attempts | No launch spam; status rail; glasses icon OAuth |
| Verify | Debug → fire test widget |

### B-002 · Inject to live agent — I5 C3 🟡

| Field | Value |
|-------|-------|
| Symptom | Chip tap silent in terminal |
| Cause | No PID/TTY → outbox; agent not running |
| Attempts | Proc registry; MarkLanded; outbox purge; diagnose-inject.sh |
| Verify | Terminal line ≤2s; `input_status: landed` |

### B-004 · Stale glasses web / SW — I4 C1 🟡

| Field | Value |
|-------|-------|
| Symptom | Theme bar trap, no D-pad |
| Attempts | v54 network-first SW + BUILD purge |
| Verify | prod BUILD=v54; Playwright core-session-flow |

### B-301 · LAN reachability — I4 C1 🟡

| Field | Value |
|-------|-------|
| Symptom | Phone can't reach Mac |
| Attempts | `0.0.0.0:5181`; mDNS; LAN store |
| Verify | Phone Discover or `ws://MAC_IP:5181/ambient-link/ws` |

### B-003 · Real idle → HUD card — I4 C2 🟡

| Field | Value |
|-------|-------|
| Symptom | Debug yank works; agent pause doesn't |
| Attempts | question/permission bypass quiet suppress (`3189520`) |
| Verify | Live pause → peek without debug yank |

### B-107 · Auto-start / pairing — I4 C5 🔴

| Field | Value |
|-------|-------|
| Symptom | Manual URL + Start every time |
| Verify | Fresh install → one tap connected |

### B-102 · Discover LAN — I3 C2 🟡

| Field | Value |
|-------|-------|
| Attempts | 10s mDNS; cached LAN fallback |
| Verify | Discover connects without typing IP |

### B-204 · New session from glasses — I3 C2 🟡

| Field | Value |
|-------|-------|
| Attempts | `__ambientOpenNew`; split Playwright create test |
| Verify | Playwright + on-device create |

### B-006 · Dictate / question chips — I3 C3 🟡

| Field | Value |
|-------|-------|
| Attempts | yes/no; `contentJob`; cancel-only dictate UI |
| Verify | Dictate → listening + cancel |

### B-105 · Reconnect loop — I3 C3 🟡

| Field | Value |
|-------|-------|
| Attempts | skip restart when same URL + connected |
| Verify | Cold open → no reconnect storm |

### B-106 · SODA / speech — I3 C4 🟡

| Field | Value |
|-------|-------|
| Verify | Dictate partial on card; SodaSmokeTest |

### B-101 · Cwd save on cloud WS — I2 C2 🟡

| Field | Value |
|-------|-------|
| Attempts | RelayLanStore; resolveHostHttpBase |
| Verify | `verify-lan-config.sh`; device save on cloud WS |

### B-203 · Delivered UI honesty — I2 C2 🟡

| Field | Value |
|-------|-------|
| Attempts | No fake delivered on outbox dequeue; landed fan-out wired |
| Verify | UI pending until `landed` |

### B-005 · Glasses web text input — I3 C5 ⚪

| Field | Value |
|-------|-------|
| Demo path | List on web; send via HUD / phone dictate |

### B-103 · Snooze toggle — I1 C1 🟢

| Field | Value |
|-------|-------|
| Code | `342d326` · Verify after install |

### B-104 · Quick replies on peek — I2 C1 🟢

| Field | Value |
|-------|-------|
| Code | `342d326` · Verify after install |

### B-303 · Idle debounce mismatch — I2 C1 🔴

| Field | Value |
|-------|-------|
| Cause | mux 4s vs PROTOCOL 2s |

### B-302 · Phantom session labels — I2 C4 🔴

| Field | Value |
|-------|-------|
| Cause | JSONL cwd decode; cloud scope |

### B-304 · Open LAN auth — I1 C3 P2

### B-201 · Session list — I0 🟢 prod

### B-202 · D-pad / focus — I0 🟢 prod

### B-401 · Phone as SoT — I1 C5 · `phone-source-of-truth-plan.md`

### B-402 · Web↔phone latency — I1 C5

### B-403 · Vendor scaffolds — I0 ignore

### B-404 · Protocol version — I1 C2

---

## Fix log

| Date | ID | Change | Result |
|------|-----|--------|--------|
| 2026-07-03 | — | Priority matrix (impact × complexity) | doc |
| 2026-07-03 | B-002,B-203 | MarkLanded; honest delivery UI; host restart | 🟡 |
| 2026-07-03 | env | verify scripts | 🟡 |
| 2026-07-03 | B-004 | v54 SW | 🟡 deploy |
| 2026-07-03 | B-003,B-006 | HUD fixes `3189520` | 🟡 |
| 2026-07-03 | B-103,B-104,B-101 | Android `342d326` | 🟡 |
| earlier | B-201,B-202 | web v49–53 | 🟢 |

---

## Commands

```bash
scripts/verify-demo-env.sh
scripts/install-android.sh
scripts/diagnose-inject.sh
scripts/start-host.sh
scripts/push-chat.sh "msg"
scripts/glasses-question.sh "?"
cd web/test && npx playwright test
```
