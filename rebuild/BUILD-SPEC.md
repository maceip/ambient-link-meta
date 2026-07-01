# Ambient Link — Build Spec (fresh repo, single milestone)

This is the package to build the thing from zero. It assumes a new, empty repo
and no dependency on the old code. The only artifacts that may be carried over
are a handful of named pure functions (listed in §8), and even those can be
retyped — nothing structural crosses over.

The single wire contract lives next to this file: [`ambient.proto`](./ambient.proto).
Read it first. It is code, not prose, and it is the source of truth; every
client is generated from it.

---

## 1. What this is (one paragraph, plain)

A reliable, vendor-neutral, **bidirectional channel between a human and his
coding agents, wherever those agents run** — plus the convenience primitives that
make talking over that channel fast. An agent's activity is observed as an
append-only, timestamped log. A session is reachable by a stable **handle** that
does not encode where it runs, so the same session stitches together across
machine → cloud → machine. **Status** (working / waiting-on-me / done / ended) is
a pure projection of the log, so every device computes the same answer.

## 2. Vocabulary (use these words; ban the others)

- **substrate** — the durable, always-on node that holds the union of all logs.
  In the target world this is the phone. (Also fine: "runtime".)
- **agent node** — a per-machine process that observes local coding agents and
  injects replies into them.
- **forward** — a dumb cloud byte-relay used only when a consumer can't reach the
  substrate on the LAN. Holds nothing.
- **consumer** — the UI surface (glasses web app, native, etc).
- **session** — an append-only, timestamped, bidirectional log.
- **handle** — a stable, location-independent reference to a session. NOT a
  credential, carries no permission. **The word "identity" is banned.**
- **event** — one immutable entry in a session's log.
- **status** — a projection of a session's log; never asserted on its own.
- **channel** — the session log read and written from both ends.
- **convenience primitives** — the human-facing affordances over the channel
  (addressing one agent or broadcasting, quick-intent chips, dictation).

## 3. Principles

1. **The log is the only truth.** Status, previews, history are projections.
2. **One contract, generated clients.** `ambient.proto` is the source of truth;
   no surface hand-writes the wire shape.
3. **Single-owner sessions.** A session is owned by exactly one producer at a
   time; the substrate **unions** per-producer logs. No CRDT, no clocks, no merge
   engine — replication is plain resumable log sync.
4. **Status is a pure function of the log.** Same log in → same status out, on
   any node.
5. **Roles are physically separated.** `forward` is a different entrypoint that
   cannot import the store. A forwarder accruing state must be impossible, not
   discouraged.
6. **Audio-ready, audio-deferred.** The media frame and a datagram channel seam
   exist in the contract from day one; no audio is implemented in v1.
7. **Halt, don't improvise.** Anything not covered by a numbered requirement is a
   STOP-and-ask, never a guess.

## 4. Non-goals (anti-sprawl — do NOT build these in v1)

- No gRPC. (Protobuf the schema, yes; protobuf messages over a plain socket. No
  HTTP/2 RPC stack, no gRPC-web proxy.)
- No CRDT / HLC / vector clocks (single-owner sessions remove the need).
- No per-session auth / credentials / pairing tokens in v1 (LAN-trusted; pairing
  is a later, separate milestone).
- No second client per platform. One consumer client, generated.
- No vendor repos (google / snapchat / apple). Frozen.
- No installer, no service-unit generation, no debug endpoints in the shipped
  binary.
- No interface added "for the future". If only one impl exists, it's a function,
  not an interface.

## 5. Components (the whole system)

| Component | Runs on | Responsibility | Must NOT |
|---|---|---|---|
| **agent node** (`role=AGENT`) | each machine with agents | observe local agents → append events; inject HUMAN_MESSAGE into the agent; mark `consumed` from the transcript; push its log to the substrate | serve the UI directly in the target design |
| **substrate** (`role=SUBSTRATE`) | phone (always-on) | hold the durable union of all per-producer logs; serve `SessionState` + history to consumers; route replies to the owning agent node | invent sessions; resolve conflicts (there are none) |
| **forward** (`role=FORWARD`) | cloud | relay opaque `Frame`s between consumer and substrate when off-LAN | hold a store; run producers; show anything when no substrate is connected |
| **consumer** (`role=CONSUMER`) | glasses web / native | render session list + status; send HUMAN_MESSAGE; convenience primitives | keep authoritative state |

Transport: `Frame` (protobuf) over a reliable ordered channel (WebSocket is fine
for v1; QUIC stream later). The media plane (audio) will use a datagram channel —
reserved, not built.

## 6. Data model + the Status projection rule (the core, pin it exactly)

A session's state is computed from its log, ordered by `ts_unix_ms`. Pseudocode —
implement this exactly; it is the thing to red-pen first:

```
function project(events) -> SessionState:
    if last lifecycle event is SESSION_CLOSE:        status = ENDED
    elif latest PERMISSION_REQUEST has no newer
         HUMAN_MESSAGE answering it:                  status = WAITING; awaiting_permission = true
    elif latest event is AGENT_MESSAGE that asks a
         question (ends with '?' or matches cues) and
         no newer HUMAN_MESSAGE/TOOL_ACTIVITY:        status = WAITING
    elif any TOOL_ACTIVITY or AGENT_MESSAGE within
         the last IDLE_WINDOW (default 4000 ms):      status = WORKING
    else:                                             status = DONE

    preview  = permission_text if WAITING-on-permission
               else last AGENT_MESSAGE text
               else last HUMAN_MESSAGE text
    location = producer of the most recent event
```

- A `FROM_HUMAN` event's `consumed` flips true when the agent's own transcript
  shows the message as a user turn (the "landed" property — one boolean, derived,
  never optimistic).
- `handle` is computed from agent + a stable session key the agent tool already
  assigns (e.g. the agent's own session UUID), **never** from an OS path.
  `workdir` travels as a data field for display only.

## 7. Requirements (numbered, each with an acceptance check)

Build in this order. R1–R6 are the working tracer (one machine — you see live
status and watch a reply land). R7–R10 thicken the same skeleton. R11–R12 are the
reserved/convenience edges. This is one milestone, not phases: the contract is
frozen at R1 and never re-aligned.

**Tracer (one machine, end to end):**

- **R1 — Contract compiles, version enforced.** `ambient.proto` generates clients
  for the languages used; `Hello.protocol_version` mismatch is rejected.
  *AC:* codegen runs clean; a unit test asserts a mismatched version is refused.
- **R2 — Agent node observes.** A `role=AGENT` process detects running coding
  agents (claude/codex/cursor) and appends `SESSION_OPEN` + `AGENT_MESSAGE`/
  `TOOL_ACTIVITY`/`PERMISSION_REQUEST` to a local append-only log.
  *AC:* start a real session; within 5 s the log contains `SESSION_OPEN` with a
  handle and the agent's last message text. (Port: transcript parsers, §8.)
- **R3 — Handle is location-independent.** Same logical session → same handle;
  handle contains no path/drive/machine.
  *AC:* test asserts handle stability and that it contains no path; `workdir`
  present as data only.
- **R4 — Status is a pure projection.** Implement §6 exactly.
  *AC:* a table of (event sequence → expected status/preview) passes exactly; the
  same input yields the same output on any node.
- **R5 — Consumer sees live status.** A `role=CONSUMER` connects (Frame over WS),
  receives `SessionState` for live sessions, and updates as the log grows.
  *AC:* the consumer lists the live session and flips WORKING→DONE/WAITING in
  agreement with §6.
- **R6 — Bidirectional reply that lands.** Consumer sends a `HUMAN_MESSAGE` for a
  handle; the agent node injects it into the agent; `consumed` flips true from the
  transcript.
  *AC:* type a unique marker on the consumer; it appears as a user turn in the
  agent's own transcript; `SessionState`/event shows `consumed=true`. (Port:
  console/PTY injection, §8.)

**Thicken the same skeleton (still one milestone):**

- **R7 — Substrate replica.** A `role=SUBSTRATE` node subscribes and holds the
  durable union of logs; survives an agent node disappearing.
  *AC:* kill the agent node — substrate still serves full history and correct
  `ENDED`; on return, sync resumes by per-producer cursor with no duplicates.
- **R8 — Forward is a dumb pipe.** A separate `role=FORWARD` entrypoint relays
  opaque frames; cannot construct a store.
  *AC:* with no substrate connected, a consumer through `forward` sees zero
  sessions; a test/compile check shows `forward` cannot import the store package.
- **R9 — Two machines, one substrate.** Two agent nodes report into one
  substrate; consumer sees both, correctly attributed; a reply routes to the
  owning node.
  *AC:* two machines, an agent each; a reply lands on the right machine.
- **R10 — Location handoff stitches.** A session whose producer changes
  (node A → node B) appears as one continuous history by handle.
  *AC:* emit events for one handle from two producers; consumer shows one merged,
  ordered session with the current location.

**Reserved + convenience:**

- **R11 — Media plane reserved (no audio built).** `MediaFrame` exists; the
  transport has a datagram-send seam documented as the audio path.
  *AC:* contract includes `MediaFrame`; transport exposes a datagram seam (stub);
  documented. No audio implementation required.
- **R12 — Convenience primitives.** Address one session or broadcast to many;
  quick-intent chips; dictation feeds `HUMAN_MESSAGE`.
  *AC:* chips produce `HUMAN_MESSAGE` events; a broadcast reaches N sessions.

## 8. Functions worth porting (optional; pure leaves, no architecture attached)

These are tested OS-glue that cost real debugging; re-deriving them re-incurs the
same bugs. Copy as standalone functions, no old contracts. Old locations:

- **Transcript parsers** (Claude/Codex/Cursor JSONL → events):
  `ambient-link-core/host/internal/producers/jsonl.go`. **Carry the parsing,
  drop `cursorCWDFromPath`** (it reconstructs macOS `/Users/...` on every OS —
  that is the phantom-label bug; replace with `workdir` as data).
- **PID → session correlation** (`ps`/`lsof`, Windows variant):
  `ambient-link-core/host/internal/producers/proc.go` /
  `proc_windows.go`.
- **Windows console injection** (`AttachConsole` + `WriteConsoleInputW`):
  `ambient-link-core/host/internal/delivery/process_input_windows.go`.
- **PTY-owned agent + capability-query responder:**
  `ambient-link-core/host/internal/pty/pty.go`.
- **Meta DAT HUD lifecycle** (createSession → addDisplay → STARTED):
  `ambient-link-meta/relay-android/.../hud/DatDisplaySession.kt`.
- **Web design system** (the Meta-HUD CSS, session cards, button row, Lobe
  icons): `ambient-link-meta/web/styles.css`, `companion.css`, `icons/`.
- **Backpressure** (throttle / TTL buffer) — pick ONE language as the reference
  and keep a single copy: `ambient-link-core/host/internal/backpressure/`.

Everything else from the old repos is deleted, not ported.

## 9. The enforcement contract (how to keep the build on-spec)

This exists because "we have a spec" has failed before by drifting mid-build.

1. **Every change cites a requirement number (R#).** A change that cites nothing
   is out of scope and is reverted — no exceptions, no "helpful extras".
2. **"Done" = the acceptance check, shown.** Not an opinion, not a summary. If the
   AC isn't demonstrated, it isn't done.
3. **Ambiguity halts.** Anything the spec doesn't pin down → stop and ask. Filling
   a gap with a prior is the failure mode; halting is correct.
4. **The contract is frozen at R1.** It changes only by editing `ambient.proto`
   deliberately and bumping the version — never silently mid-build.

## 10. First thing to make real

R1–R6 on one machine: run the agent node here, open the consumer, see this very
session's live status, type a reply, and watch it land in the agent's transcript.
That single working slice uses only code that survives to the finished product,
and it proves the two things that have been missing — trustworthy status and a
reply that actually lands — before anything is built on top of it.
