# Blockers tracker

Single source of truth. Update here — not in chat.

**Last updated:** 2026-07-03 (verification pass)

---

## Priority (impact ↓, then complexity ↑)

**Impact** (demo): 5 = loop dead · 4 = major path broken · 3 = degraded · 2 = polish · 1 = edge · 0 = done  
**Complexity**: 1 = minutes · 2 = hours · 3 = day · 4 = multi-day · 5 = architecture / external

Within the same impact band, **lower complexity first** (quick wins).

| Rank | ID | Impact | Cx | Status | Verify gate (proof) |
|------|-----|--------|-----|--------|---------------------|
| 1 | B-007 | 5 | 1 | 🟢 | `verify-android-device.sh` 2026-07-03: install 0.1.1-hud + theme pills + snooze toggle |
| 2 | B-001 | 5 | 2 | 🟢 | `ambient.debug` prepareDisplay + display ready; UI "Test card sent — tap OK on glasses" 2026-07-03 |
| 3 | B-002 | 5 | 3 | 🟡 | `verify-inject-landed.mjs`: delivered 1ms ✓; landed not in 15s |
| 4 | B-004 | 4 | 1 | 🟡 | local v54 SW + Playwright core-flow ✓; **prod still v53** (5 commits unpushed) |
| 5 | B-301 | 4 | 1 | 🟡 | phone `RelayClient` WS live (thread_idle/hud_yank); healthz curl N/A on device shell |
| 6 | B-003 | 4 | 2 | 🟡 | debug yank → `HudPresenter update … PEEKING` ✓; live agent pause unproven |
| 7 | B-107 | 4 | 5 | 🔴 | Pair-once + auto-start (post-demo) |
| 8 | B-102 | 3 | 2 | 🟡 | Discover on device; mDNS / manual LAN URL |
| 9 | B-204 | 3 | 2 | 🟡 | Create session on glasses + Playwright |
| 10 | B-006 | 3 | 3 | 🟡 | Dictate E2E on device |
| 11 | B-105 | 3 | 3 | 🟡 | Reconnect loop logcat repro |
| 12 | B-106 | 3 | 4 | 🟡 | SODA smoke on device |
| 13 | B-101 | 2 | 2 | 🟡 | `verify-lan-config.sh` ✓; device save on cloud WS unproven |
| 14 | B-203 | 2 | 2 | 🟡 | Web UI pending until `landed` — code in git; live WS unproven on prod |
| 15 | B-005 | 3 | 5 | ⚪ | Demo script: HUD chips not web keyboard |
| 16 | B-103 | 1 | 1 | 🟢 | `verify-android-device.sh` snooze tap-twice ✓ |
| 17 | B-104 | 2 | 1 | 🟡 | logcat `chip tapped: explain more` on peek; full peek chip order unproven |
| 18 | B-303 | 2 | 1 | 🟡 | core mux IdleDebounce 4s→2s; host rebuilt; go test mux ✓ |
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
| `scripts/verify-demo-env.sh` | Host, adb/APK, prod BUILD/SW, protocol, inject diag | 2026-07-03 pass |
| `scripts/verify-android-device.sh` | B-007 theme pills; B-103 snooze toggle | 2026-07-03 **pass** |
| `scripts/verify-inject-landed.mjs` | B-002 delivered ≤2s; landed fan-out | 2026-07-03 delivered ✓ landed — |
| `scripts/diagnose-inject.sh` | PID/TTY endpoints, outbox, agent processes | 2026-07-03 outbox empty ✓ |
| `scripts/test-protocol.sh` | debug yank + inject delivered | 2026-07-03 pass (+ phone logcat) |
| `scripts/test-chip-contract.sh` | yes/no questions, quick-reply order | 2026-07-03 pass |
| `scripts/verify-lan-config.sh` | POST `/ambient-link/config` | 2026-07-03 pass |
| `scripts/install-android.sh` | APK on device | 2026-07-03 pass (2506BPN68G) |
| `web/test/core-session-flow.spec.mjs` | v54 layout, session open, prompt | local 2026-07-03 **pass**; prod blocked v53 |

---

## Failure modes

| Mode | Repo | Status |
|------|------|--------|
| 1 Environment | meta + device | 🟡 phone+APK ✓; prod v54 deploy pending (push main) |
| 2 Meta fixes | meta `12188c5`+ | 🟢 device settings + Meta debug widget verified |
| 3 Core delivery | core `4552e76` + mux 2s | 🟡 delivered live; landed fan-out not confirmed in 15s |

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

Host: `0.0.0.0:5181` · LAN `ws://192.168.1.33:5181/ambient-link/ws` · prod web **v53** · local/git **v54** · main **ahead 5** (unpushed)

---

## Blocker detail (priority order)

### B-007 · APK not on phone — I5 C1 🟢

| Field | Value |
|-------|-------|
| Symptom | Phone runs old build; fixes not testable |
| Attempts | `install-android.sh`; `verify-android-device.sh` |
| Verify | **2026-07-03 pass** — `com.lowkey.ambientlink` 0.1.1-hud; theme pills; snooze tap-twice |

### B-001 · Meta SDK registration — I5 C2 🟢

| Field | Value |
|-------|-------|
| Symptom | `UNAVAILABLE`, `devices=0`; no HUD |
| Attempts | BT perms; glasses icon; debug fire widget |
| Verify | **2026-07-03 pass** — device `98103fe9…`; log `display ready — sending debug card`; UI success line |

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
| 2026-07-03 | B-001 | debug fire test widget on device | 🟢 |
| 2026-07-03 | B-007,B-103 | `verify-android-device.sh`; install 0.1.1-hud | 🟢 |
| 2026-07-03 | B-002 | `verify-inject-landed.mjs` | 🟡 delivered ✓ landed — |
| 2026-07-03 | B-004 | local Playwright core-flow + v54 SW | 🟡 prod deploy pending |
| 2026-07-03 | B-303 | core mux IdleDebounce 2s; host rebuilt | 🟡 uncommitted core |
| 2026-07-03 | B-301,B-003 | phone WS + debug yank PEEKING | 🟡 |
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
scripts/verify-android-device.sh   # B-007 + B-103 when adb connected
node scripts/verify-inject-landed.mjs  # B-002 delivered/landed
scripts/install-android.sh
scripts/diagnose-inject.sh
scripts/start-host.sh
scripts/push-chat.sh "msg"
scripts/glasses-question.sh "?"
cd web && AMBIENT_WEB_ORIGIN=http://127.0.0.1:5181 AMBIENT_RELAY_HOST=http://127.0.0.1:5181 npx playwright test core-session-flow.spec.mjs --project=core-flow
```
