# Ambient Link — Final Report

**Date:** 2026-07-03  
**Purpose:** Single honest assessment. Decide what to keep, what to rebuild, what to use off the shelf.  
**Rule for substitutes:** Open source or paid is fine **only if** (a) it ships **inside our app/binary**, or (b) we **run the infrastructure ourselves** (our Mac, our VPS/VPC, our `public.computer` — no third-party control plane the user must sign up for).

---

## 1. The one primitive (everything else is decoration)

You were always clear: **the local Mac service must reliably track agent sessions and delivery.**

That means:

1. **Track** — Which agents are running, on what project, busy / idle / waiting for you, last agent message, last human message.
2. **Deliver** — When you reply from phone or glasses, the message **reaches the agent**; the system **reports honestly** whether it did (confirmed in the agent’s own transcript, not “we wrote bytes somewhere”).

If that primitive is wrong or missing, **nothing else matters** — not web chat polish, not theme pills, not cloud, not Meta Display touch hacks, not LAN Debug UI.

**What happened:** That primitive was **lost in the noise**. Four rewrites added layers (web monolith, Android god-screen, cloud role confusion, phone-as-truth plans, mock agents, jargon) while **end-to-end session truth + delivery was never held as the only gate.**

---

## 2. What went wrong (corrected)

| Failure | What it is | Severity |
|---------|------------|----------|
| **Local Mac service did not reliably track sessions and delivery** | Wrong/missing sessions, false “sent,” inject not landing, state not matching live agents | **Fatal** — you shouted about this from the start |
| **Never gated work on one 60-second test** | “Can I see agent state on a client and send a reply that lands in the agent?” | **Fatal** — allowed infinite surface area |
| **Web app built on wrong data** | Chat/history on `last_user_input` / `last_assistant` snapshots, not a turn log; 1913-line `app.js` | **High** — annoying but fixable *if* Mac service tells truth |
| **Android app unusable** | ~1,200-line `MainActivity`, Debug as onboarding, Mac-centric copy, LAN bugs (main-thread probe, ignored saved URL) | **High** — product failure, not just bugs |
| **Screenshot compare never done** | You asked for visual regression against working UI; agents skipped it | **High** — process failure; glasses/web drift unverified |
| **Phone-as-source-of-truth never shipped** | Documented requirement (`IDEAL-ARCHITECTURE.md`, `phone-source-of-truth-plan.md`, `rebuild/BUILD-SPEC` substrate); **no full session log on phone was implemented** | **High** — requirement abandoned in code, not replaced with working Mac-truth either |
| **Invented jargon** | “Yank,” mux, substrate in user-facing talk; wrong verbs (not “pull,” **push** to glasses) | **Medium** — obscured the product and your feedback |
| **Tests removed / never meaningful** | Playwright deleted; never tested send → bubble → history; `rebuild/` claims tests that aren’t in tree | **Medium** |
| **Cloud / LAN as headline bugs** | Often blamed for “wrong world”; **your correction:** core pain was **local session management**, not cloud connect | **Misattributed** — connect often worked; **truth** didn’t |

**Not the root cause (symptoms or side quests):** Meta Display touch, service worker versions, theme on phone settings, snooze, dictate paths, mDNS UX — all **downstream** of missing session/delivery primitive.

---

## 3. What this system is (plain English)

You run **coding agents** (Claude Code, Codex, Cursor) on a **computer**. You want to **see when they need you** and **reply** from **Meta glasses** (card on the display, quick buttons), usually via a **phone app** connected to the computer.

**Six jobs:**

| # | Job | Plain name |
|---|-----|------------|
| 1 | Watch agents on the computer | **Session tracking** |
| 2 | Remember state in one place | **Session store** |
| 3 | Send replies into the agent | **Delivery / inject** |
| 4 | Expose state to clients | **Host API** (HTTP + WebSocket) |
| 5 | Show card on glasses, send taps | **Phone + Meta SDK** |
| 6 | Optional browser UI on glasses | **Web companion** |

**Jargon ban (use these in specs and UI):**

| Never say | Say |
|-----------|-----|
| yank, hud_yank, thread_idle | **push snapshot to glasses** |
| mux | **session state** |
| land / landed | **confirmed in agent log** |
| relay / host (to users) | **computer service** or **agent service** |
| chip | **quick reply button** |
| daemon | **background connection** |

---

## 4. Infrastructure rule (substitutes)

**Allowed:**

- Libraries **linked into** Go binary or Android APK (Go modules, Maven, Meta SDK).
- Services **we run**: Mac host, **our** VPS/VPC (`public.computer`), **our** Caddy, **our** forwarder process.
- **Self-hosted** open source on **our** metal: e.g. proxy, DB, message broker, **Headscale** (if we run control plane + embed WireGuard — heavy, optional later).

**Not allowed for v1 path:**

- User installs **separate app** + **vendor account** (Tailscale consumer, ngrok, Cloudflare Zero Trust SaaS).
- **Third-party control plane** we don’t operate.

**Paid software:** OK if **embedded** (Meta SDK, Apple/Google store policies) or **license for software we self-host** on our VPC — not “pay Tailscale Inc. for mesh.”

---

## 5. Full substitution matrix (every problematic area)

**Legend:**  
**EMBED** = in app/binary · **SELF** = we run on Mac/VPC · **CUSTOM** = we must own code · **CUT** = delete/skip v1 · **FIX** = our code, wrong today

| Area | Problem today | Use instead | Type |
|------|---------------|-------------|------|
| **Session tracking on Mac** | Mux/state wrong vs live agents | **CUSTOM** — simplify to append-only events → SQLite → projections; keep `producers` parsers | FIX + CUSTOM |
| **Delivery / inject** | False sent, too many paths | **tmux** (SELF, user machine) + **Claude/Codex official hooks** (EMBED config) + **one outbox** in SQLite | EMBED + SELF + CUSTOM |
| **Session database** | Journal/phantom confusion, overbuilt | **`modernc.org/sqlite`** in Go host (EMBED) | EMBED |
| **SQL access** | Hand-rolled SQL | **`sqlc`** (EMBED, codegen) — optional | EMBED |
| **File watch agents** | Custom tailer | **`github.com/fsnotify/fsnotify`** (EMBED) + custom JSONL parsers | EMBED + CUSTOM |
| **HTTP + WebSocket server** | Custom hub | **`net/http`** + **`nhooyr.io/websocket`** (EMBED) | EMBED |
| **CLI** | Flag soup | **`spf13/cobra`** — `serve` only (EMBED) | EMBED |
| **LAN discovery** | mDNS failed UX; IP Debug | **`hashicorp/mdns`** in host (EMBED); save URL in Android **DataStore** (EMBED) | EMBED |
| **Remote access (off Wi‑Fi)** | Wrong full relay on VPS | **SELF** — stateless **WS forwarder** on **our VPC**; Mac relay **dials out** (`RESTART-DECISION.md` Option A) | SELF + CUSTOM |
| **TLS / reverse proxy** | — | **Caddy** on **our VPC** (SELF) | SELF |
| **Optional wrapper `host run`** | Forbid required wrapper | **CUSTOM** + **`go-pty`** (EMBED) — **optional** for provable inject; not required for product | EMBED optional |
| **Android connection** | Duplicate clients, bugs | **OkHttp WebSocket** (EMBED); drop **core-android poll client** | EMBED |
| **Android config** | SharedPreferences + Debug | **Jetpack DataStore** (EMBED) | EMBED |
| **Android UI** | 1200-line MainActivity | **Jetpack Compose**, one Activity (EMBED) | EMBED |
| **Android JSON** | `org.json` soup | **kotlinx.serialization** or **Moshi** (EMBED) | EMBED |
| **Android local cache** | None / prefs strings | **Room** (EMBED) — mirror of host API, not second truth unless you later choose phone-SOT | EMBED |
| **Android background** | FGS | **AndroidX Foreground Service** (EMBED) | EMBED |
| **Glasses display** | — | **Meta `mwdat-core` / `mwdat-display`** (EMBED, vendor — unavoidable) | EMBED |
| **Push card to glasses** | Wrong name “yank” | **CUSTOM** — slim DAT glue (~`DatDisplaySession` size) | CUSTOM |
| **Dictate v1** | SODA recovered jars, 3 paths | **Android `SpeechRecognizer`** (EMBED, OS) | EMBED |
| **Web companion** | 1913-line `app.js` | **CUT** or **Vite** + native **WebSocket** + **marked** + **DOMPurify** (EMBED); host exposes **status/events only** | EMBED or CUT |
| **Web cache wars** | Service worker v54–v65 | **CUT** SW v1; Vite hashed assets | CUT |
| **Chat history UI** | Impossible on snapshot fields | **CUT** v1 or host exposes **turn list** (CUSTOM API) first | FIX |
| **Cloud full relay + journal on VPS** | Phantoms when misdeployed | **CUT** — proxy only (SELF) | CUT |
| **core-android / core-apple** | Duplication, wrong WS model | **CUT** v1 | CUT |
| **iOS relay-ios** | Scaffold | **CUT** | CUT |
| **gRPC / ambient.proto stack** | Fourth architecture | **CUT** v1; JSON WS | CUT |
| **Mock demo agents as product** | False progress | **CUSTOM** — CI-only demo producer (`rebuild` idea), not user path | CUSTOM test only |
| **Multi-DB phone sync / CRDT** | Never finished phone-SOT | **CUT** unless explicitly re-spec’d; Mac SQLite + API simpler | CUT for now |
| **Screenshot / visual QA** | Never done | **Playwright** + screenshot compare (EMBED, CI on **SELF** runner) | EMBED + SELF |
| **Integration tests** | Shallow / deleted | **Go test** inject→transcript (EMBED); **5 Playwright** smokes if web survives | EMBED |
| **Notifications** | — | **FGS** v1; later **self-hosted ntfy** on **our VPC** (SELF) if needed | EMBED / SELF |
| **Pairing auth v1** | — | **Bearer token** env on host (EMBED); QR to our URL (CUSTOM) | EMBED |

**No shelf exists for:** parsing Claude/Codex/Cursor JSONL, mapping “waiting for you” → card + buttons, Meta DAT integration. **Must own**, but **small** if Mac API is honest.

---

## 6. History (four rewrites — why nothing compounded)

| Phase | What happened |
|-------|----------------|
| 1 | Go host + JSON WS + journal |
| 2 | Core rebuild: SQLite, honest delivery, `DECISIONS.md` — **best engineering** |
| 3 | Parallel north stars: phone-SOT docs, `rebuild/BUILD-SPEC`, cloud bridge — **never one shipped loop** |
| 4 | Meta repo: web + Android explosion, features before session/delivery gate |

**Phone-as-source-of-truth:** Required in docs; **never implemented** in code. You may reconsider that direction; **this report does not prescribe it** — insufficient evidence here for why it failed in implementation, only that **neither phone-SOT nor reliable Mac-SOT was finished.**

---

## 7. What actually worked (evidence)

| Piece | Evidence |
|-------|----------|
| Go `producers` + `store` + `mux` unit tests | `go test ./...` in `ambient-link-core/host` |
| Mac `/ambient-link/status`, `/healthz`, config POST | curl / verify scripts |
| WS connect phone → Mac (after LAN fix) | adb logcat `RelayClient: connected` |
| Push snapshot → glasses card | Prior debug demos, `DatDisplaySession` |
| Web visual design | `companion.css`, `blocks/` — **look** only |

**Never proven:** You send from glasses → **confirmed in live agent transcript** → update on client **three days in a row**.

---

## 8. What to extract vs rebuild

### Extract (copy or module — small)

- `ambient-link-core/host/internal/producers`, `store`, `mux` ( **after simplifying session model** )
- `host/DECISIONS.md`, `DELIVERY.md` — **rules**, not all code
- `DatDisplaySession.kt`, chip rules, web CSS/blocks
- OkHttp WS client pattern from meta `RelayClient.kt`

### Rebuild (do not extend)

- Android shell (one screen: connected, sessions, push to glasses, quick reply)
- Web logic (`app.js`) or cut web v1
- Host **inject path** — one ranked pipeline
- Host **read API** — DB-backed status + events (and turn list if chat ever returns)

### Cut

- `MainActivity` settings/Debug product surface
- Full relay on VPS
- SODA / `WebDictationBridge` v1
- `core-android` WS duplication, iOS, BLOCKERS-as-truth

---

## 9. Wrapper (`host run agent`) — one paragraph, no mandate

**Attached agents** (your terminal) = real workflow, hard inject. **Wrapper** = provable stdin, optional. Product can **forbid requiring wrapper**; cost is **harder delivery honesty** on attached sessions. v1 proof: attached + tmux + hooks + transcript confirmation — not a fourth inject layer.

---

## 10. vNext composition (minimal, your infrastructure rules)

```
┌─ YOUR Mac / VPC ─────────────────────────────────────────────┐
│  Go host (EMBED: sqlite, fsnotify, mdns, websocket)          │
│  CUSTOM: producers, simplified session log, one inject path │
│  SELF: tmux, agent hook configs on disk                     │
└───────────────────────────┬────────────────────────────────┘
                            │ WS + GET /status (LAN or our VPC forwarder)
┌───────────────────────────▼────────────────────────────────┐
│  Android APK (EMBED: OkHttp, Compose, DataStore, mwdat)     │
│  CUSTOM: thin card presenter, quick reply → WS input        │
│  Optional: Room cache of host events                        │
└───────────────────────────┬────────────────────────────────┘
                            │ Meta SDK
                       Meta glasses
```

**Optional:** static web on **our Caddy** — same API, no second truth.

**Gate before any feature:**  
*Live agent on Mac → status correct → reply from phone → **visible in agent transcript** → reflected in API.*

---

## 11. Agent / process failures (so it doesn’t repeat)

| Rule | Why |
|------|-----|
| **Mac session + delivery gate only** | You said it from day one; we ignored it |
| **Screenshot compare when UI claimed fixed** | You asked; agents didn’t |
| **Plain English in specs** | No yank/mux/substrate |
| **No 🟢 without your 60s manual script** | localhost curl ≠ product |
| **One subsystem per session** | No web+Android+cloud+docs in one agent pass |
| **Don’t document phone-SOT without building it** | Or don’t document Mac-SOT without building it — pick one, ship |

---

## 12. Bottom line

- **You needed one thing:** Mac service that **tracks sessions** and **delivers replies honestly**. That was **lost**. Everything else is irrelevant until that works.
- **Web annoyances, LAN Debug, cloud noise** — secondary. Fix **host truth** first; Android becomes a **view** on that data; web becomes easy or unnecessary.
- **Substitutes:** Use **embeddable libraries** + **self-hosted infra on our VPC**; no sidecar SaaS VPN accounts.
- **Four rewrites failed** because **no gate, no screenshots, jargon, phone-SOT undelivered, Mac-SOT unfinished**, and agents optimized for motion over the primitive.
- **Recoverable:** Go observation stack (simplified), Meta DAT glue, visual design, honesty rules in `DECISIONS.md`.
- **Not recoverable as product:** current Android app shell, current `app.js`, treating Debug as onboarding.

**If you build again:** one page spec, one gate, Mac SQLite as truth (unless you formally re-decide phone-SOT with a written spec and resources to finish it), thin Android client, our VPC forwarder only for off-LAN — **no new nouns.**

---

*End of report.*
