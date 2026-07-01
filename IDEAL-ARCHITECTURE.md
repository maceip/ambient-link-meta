# Ambient Link — Ideal Architecture (the system we should build)

> This is the **prescription**. The diagnosis is in
> [`ARCHITECTURE-REVIEW.md`](./ARCHITECTURE-REVIEW.md). This document describes a
> system designed to be built **fresh, alongside** the current one — not a patch
> list for the existing relay. It exists so that every recurring failure mode in
> the current system (phantom sessions, "delivered but never landed," stale
> relays, source-of-truth confusion, scope creep across five repos) is **designed
> out**, not papered over.
>
> Design stance: **what would a team that ships this for a living do?** Fewer
> moving parts, one source of truth, honest state, offline-first, least
> privilege, and a delivery path that can *prove* a human reply became an agent
> turn.

---

## 1. The one-paragraph model

A coding agent runs on a **laptop**. The laptop runs a small **edge producer**
that observes the agent and emits an **append-only event log**. The **phone is
the source of truth**: it owns the canonical, durable, replicated store of all
sessions and all human↔agent interactions, merged from one or more laptops. The
**glasses** (web app today; native DAT later) are a **thin consumer** that
renders peek cards and sends replies; they talk to the phone. When the glasses
can't reach the phone directly, a **stateless cloud assist** forwards bytes
between them — it **never stores state and never invents sessions**. Replies
flow back to the laptop and are injected into the agent, and are only marked
**landed** once the agent's own transcript confirms the reply became a user turn.

```
  ┌─────────┐   append-only      ┌──────────────────┐    render/reply   ┌──────────┐
  │ laptop  │  event log + recv  │  PHONE            │  scoped stream    │ glasses  │
  │ edge    │ ─────────────────▶ │  source of truth  │ ◀───────────────▶ │ web/DAT  │
  │ producer│ ◀───────────────── │  (durable log +   │                   └──────────┘
  └─────────┘   reply + receipt  │   session view)   │        ▲
       ▲  (multiple laptops)     └──────────────────┘         │ when LAN
       │                                  ▲                    │ unavailable
       │ inject + confirm landed          │ sync              │
       ▼                                  ▼                    ▼
  ┌─────────┐                    ┌──────────────────────────────────┐
  │  AGENT  │                    │  CLOUD ASSIST (stateless forward) │
  │ (CLI)   │                    │  signaling + TURN + byte relay    │
  └─────────┘                    │  NO database, NO producers        │
                                 └──────────────────────────────────┘
```

---

## 2. Non-negotiable principles

1. **One source of truth: the phone.** Everything else is a producer or a
   consumer. The phone holds the durable log and the materialized session view.
   Laptops do **not** hold canonical state; they hold their own *local* log
   segment and forward it. The cloud holds **nothing**.
2. **One append-only log, many materialized views.** All state is derived from an
   immutable event log. Session lists, peek cards, history — all are projections.
   No component "invents" a session; a session exists iff there are events for it.
3. **Honest delivery with confirmation.** A reply has an explicit lifecycle:
   `accepted → written → landed | failed`. `landed` is set **only** when the
   agent's own transcript shows the reply as a user turn. "Written to a channel"
   is never reported as success. Every reply has a receipt the UI can show.
4. **One artifact, explicit roles.** A single binary may exist, but it runs in
   exactly one declared **mode** (`edge`, `hub`, `forward`) and refuses to do
   another mode's job. A forwarder physically cannot open a database or a
   producer. This makes "the cloud box grew a journal" impossible by construction.
5. **Least-privilege, paired control plane.** No unauthenticated control surface.
   Discovery advertises capability, not access; data/inject planes require a
   pairing token. Fan-out is **scoped** — a consumer sees only the sessions it is
   authorized for, not every thread on the host.
6. **Offline-first, reconcilable sync.** Laptop↔phone and phone↔glasses tolerate
   disconnection. Each producer has a monotonic sequence; the phone merges by
   `(producer_id, seq)`; conflicts resolve by last-writer-per-field with explicit
   timestamps. No data loss when a peer is away.
7. **No host-specific truth leakage.** cwd, labels, and identity are carried as
   data from the producer, never reconstructed by string-munging a path on a
   different OS. A session created on a Windows laptop says `C:\...`, full stop.
8. **One contract, version-checked on the wire.** A single protocol module is the
   source of truth for message shapes, with a `PROTOCOL_VERSION` that is sent on
   connect and **enforced** (refuse/upgrade on mismatch), shared by every client.

---

## 3. Components and responsibilities

### 3.1 Edge producer (laptop) — `mode=edge`
- Observe local agents (hooks + transcript tail + process watch), exactly as
  today's producers do (these are the *proven leaves* — keep them).
- Emit a **local append-only log segment** with a stable `producer_id` and
  monotonic `seq`. Carry real cwd/agent/identity as data.
- Maintain delivery endpoints (PID/PTY/tty) and **inject replies**, then **read
  the agent transcript to confirm landing** and emit a `landed`/`failed` receipt.
- Sync its log segment to the phone (LAN first, cloud-assist fallback). Stateless
  toward the UI — it never serves the glasses directly in the target design.
- **Self-supersede:** on start, register with the phone with a build id; the phone
  tells an older edge instance for the same machine to stand down (kills the
  "stale relay" class of bug at the source-of-truth, not via local lock hacks).

### 3.2 Hub (phone) — `mode=hub`  ← source of truth
- Own the **durable, replicated event log** and the materialized **session view**.
- **Merge** segments from N laptops by `(producer_id, seq)`; dedupe; order by
  hybrid logical clock; resolve field conflicts deterministically.
- Serve a **scoped** stream/REST to the glasses (only authorized sessions).
- Hold the **delivery receipts** and surface honest status to the UI.
- Be the **pairing authority**: it issues and revokes tokens for laptops and
  glasses.
- Run the Go core via `gomobile/gobind` (reuse the proven mux/store as a library),
  so there is exactly one implementation of state logic, hosted on the phone.

### 3.3 Cloud assist — `mode=forward`  ← stateless
- **Signaling + TURN + byte relay** only. Pairs a glasses client to a phone (or a
  phone to a laptop) and forwards frames. **No database, no producers, no mux.**
- Physically separate build target or a hard runtime guard: in `forward` mode the
  store and producer packages are not even constructed.
- If the phone/laptop is offline, the forwarder returns "peer offline" and shows
  **nothing** — it has nothing to show, by design.

### 3.4 Consumer (glasses) — web app now, native DAT later
- Thin. Connect to the phone (direct LAN, else via cloud assist). Render the peek
  card / session list using the Meta DAT design language. Send replies and
  dictation. Display delivery receipts honestly ("sent ✓", "landed ✓✓",
  "couldn't reach agent ✗").
- One UI codebase, one design-token source shared with native.

---

## 4. Canonical data model

### 4.1 Event log (immutable)
```
Event {
  producer_id   string     // which laptop/edge emitted it
  seq           int64      // monotonic per producer
  hlc           string     // hybrid logical clock for cross-producer ordering
  session_id    string     // globally unique (producer_id + agent + cwd hash)
  agent, cwd    string     // carried as data, OS-correct, never reconstructed
  type          enum       // session_start | user_prompt | assistant_message
                           // | tool_use | permission_prompt | stop | session_end
  payload       json
  observed_at   int64
}
```

### 4.2 Materialized session view (derived, on the phone)
State machine identical to today's (STARTING/BUSY/IDLE/AWAITING_PERMISSION/DEAD),
but death is **only** process-driven or `session_end`; never a timer that can kill
a waiting agent. Idle is inferred, labeled `inferred:true`, and reversible.

### 4.3 Delivery receipt (the keystone)
```
Reply {
  id            string
  session_id    string
  text          string
  status        enum   // accepted → written → landed | failed
  channel       enum   // pty | console | tmux | tty | hook-outbox
  landed_proof  string // the transcript line/offset that confirms the user turn
  error         string
  at            int64
}
```
`landed` **requires** `landed_proof`. No proof ⇒ status stays `written`. The UI
shows the difference. This is the contract that the current system promises and
does not keep.

---

## 5. Transports & sync

| Hop | Primary | Fallback | Notes |
|---|---|---|---|
| laptop → phone | LAN (mDNS-discovered, paired, `wss`) | cloud-assist forward | log-segment sync, resumable by `(producer_id, seq)` |
| phone ↔ glasses | LAN `wss` if reachable | cloud-assist (WebRTC DataChannel + ICE + TURN) | scoped session stream + replies |
| phone → laptop | reply + "please inject" | cloud-assist | carries reply id; laptop returns receipt |

- **Resumable, idempotent sync.** Every link resumes from the last acked
  `(producer_id, seq)`; redelivery is safe (events are immutable, replies are
  idempotent by `id`).
- **Backpressure** uses the existing `Throttle`/`EphemeralBuffer` primitive —
  but defined **once** (in the Go core compiled to the phone via gomobile, and
  exposed to web via wasm or a tiny TS port that is generated/checked against the
  Go one), not hand-copied three times.

---

## 6. Trust & security

- **Pairing required for data + inject.** Discovery (mDNS / cloud signaling)
  only announces existence. The first connection performs a pairing handshake
  (short code shown on phone), after which a scoped token is issued.
- **Scoped fan-out.** A glasses client subscribes to authorized sessions only;
  the hub filters. No "every client sees every thread."
- **Inject is a privileged capability** and is logged. The OS keystroke-injection
  path (Windows console / tty) is the highest-blast-radius capability and must be
  pairing-gated and audited.
- **No debug endpoints in release builds.** Debug injection is a build tag, not a
  route that ships.
- **Cloud assist sees ciphertext where possible.** End-to-end encrypt phone↔glasses
  payloads through the forwarder so the cloud can't read or forge content.

---

## 7. What to KEEP, REBUILD, DELETE (from today's code)

**Keep (proven leaves):**
- The three producers (hooks, JSONL tailer, process watcher) — observation works.
- The mux state machine logic and the SQLite store schema — solid; relocate them
  to run **on the phone** as the source of truth (gomobile), and on the laptop as
  a *local segment* only.
- `Throttle` / `EphemeralBuffer` backpressure primitive — but de-duplicate to one
  definition.
- The Meta DAT web UI work (cards, button row, Lobe icons) — the design is right.

**Rebuild (rotten spine):**
- **Delivery + receipts** around the `accepted→written→landed|failed` contract
  with mandatory transcript confirmation. Wire `MarkLanded` to a transcript
  watcher (it exists but is dead today).
- **Roles/modes.** Split `edge` / `hub` / `forward` so a forwarder cannot hold
  state. This alone prevents the cloud-phantom-session class.
- **Sync** as resumable per-producer log merge with the phone as authority.
- **cwd/identity** carried as data; delete `cursorCWDFromPath`-style path
  reconstruction.
- **One protocol module** with an enforced `PROTOCOL_VERSION`; regenerate the
  web/native clients from it.

**Delete (dead / misleading / scope creep):**
- The "same binary is also the cloud relay" behavior (replace with `forward`).
- Optimistic state recording on unconfirmed delivery (already mostly gated).
- `enter` flag remnants in PROTOCOL.md and the web app (the Go side already
  dropped it).
- Stale-relay lock-file/takeover experiments — superseded by hub-mediated
  self-supersede.
- The three near-empty vendor repos as *active scope*: freeze `ambient-link-google`,
  `ambient-link-snapchat`, `ambient-link-apple` as **explicitly labeled scaffolds**
  until Meta is fully solid. Do not let them imply working multi-platform support.

---

## 8. Phased, scope-bounded plan (each phase independently shippable)

**Phase 0 — Contract & honesty (no new transports).**
- Extract one protocol module + `PROTOCOL_VERSION`; enforce on connect.
- Implement the delivery receipt lifecycle + transcript-confirmed `landed` on the
  laptop. Ship the receipt in `/status`, WS, and the web UI. *Exit test:* inject a
  unique marker, see it become a user turn in the agent's transcript, and watch
  the UI flip to `landed ✓✓` — for cursor, claude, and codex.

**Phase 1 — Roles.**
- Introduce `mode=edge|hub|forward`. Make `forward` incapable of storing state.
- Redeploy `public.computer` as `forward`. *Exit test:* with no laptop connected,
  the cloud shows **zero** sessions; with a laptop connected, it forwards and
  shows the laptop's real sessions only.

**Phase 2 — Phone as source of truth.**
- Compile the Go core to the phone (`gomobile`); phone owns the durable log +
  session view; laptop becomes an `edge` that syncs a local segment. *Exit test:*
  kill a laptop mid-session; phone retains full history; laptop reconnects and
  resumes by `(producer_id, seq)` with no dupes.

**Phase 3 — Multi-laptop merge + scoped glasses stream.**
- Two laptops report into one phone; phone merges; glasses see a unified,
  authorized list. *Exit test:* two machines, two agents each; phone shows four
  correctly-attributed sessions; glasses reply lands on the right machine.

**Phase 4 — Hardening.**
- Pairing tokens everywhere, scoped fan-out, E2E encryption through the forwarder,
  debug endpoints behind build tags, audit log for inject.

Only after Phases 0–4 are solid for Meta do we **unfreeze** a single additional
vendor (the one with the most mature SDK per the vendor review) and implement the
*same* consumer contract there.

---

## 9. The single sentence to hold everyone accountable

> A human reply is only ever shown as delivered when we can point at the line in
> the agent's own transcript where it became a turn — and the phone, not any
> laptop or cloud box, is the one place that remembers it.
