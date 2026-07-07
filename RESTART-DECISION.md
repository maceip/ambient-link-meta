# Restart vs. surgical-fix — decision brief

Status: **RESOLVED 2026-07-06 — Option A executed.** The cloud box now runs the
relay binary in `AMBIENT_LINK_ROLE=proxy` (core `49b8395`): no tailers, no proc
watcher, no reaper, no local ingestion, state under `/run` (wiped on restart).
Stale journal/db/outbox archived to `~devuser/ambient-link-state-backup-20260706.tar.gz`
and removed. Verified: laptop connected → its live sessions on the hosted web
app; laptop offline → zero sessions; reconnect → sessions return. The unit file
is `/etc/systemd/system/ambient-link-host.service`. Historical context below.

## The one-paragraph situation

The work you approved was rebuilding the **local relay** (runs on the laptop, next
to the agents). That part is done and tested. Today we discovered a *separate*
problem: the **cloud box** (`public.computer`) is running a **full relay binary**
where only a thin **Cloud Assist proxy** belongs. Because it's a full relay, it
tails its own disk and keeps a journal file, so it invents "phantom" sessions
(e.g. `claude C:\Users\mac` shown DEAD) that don't belong to any live machine.
That is a *deployment/role* mistake, not rot in the rebuilt local relay.

## What is actually solid (don't throw away)

- **Local relay rebuild** (`ambient-link-core/host`): SQLite store, PTY delivery,
  honest delivery status, process-driven session death, cloud bridge client.
  Builds, vets, all tests pass. Verified discovering live sessions on this laptop.
- **Web app** (`ambient-link-meta/web`): renders the session cards correctly
  (verified screenshot against the local relay).
- **Deployment runbook**: `DEPLOYMENT.md` (just written, no secrets).

## What is actually wrong (the real defect)

1. **Cloud box runs a full relay**, deployed by a prior agent session on
   **Jun 19 ~00:24 UTC** (binary build + service restart), committed under the
   `maceip` identity. It should be a stateless proxy.
2. **Stale state**: `~/.ambient-link/journal.jsonl` on the server (started
   Jun 18 14:50) accumulated laptop sessions via an earlier `relay-bridge` E2E
   test and now replays them as phantoms.
3. **No multi-relay model**: the web app connects to exactly one origin's
   WebSocket. When it can't reach the native device, there's no defined way to
   sync to a relay via the cloud.

## Agreed terminology (use going forward)

- **Local relay** — runs on a machine *with* agents. Source of session truth on
  that machine.
- **Native app** — the device source of truth for sync/updates.
- **Cloud Assist proxy** — a **stateless** connector on `public.computer`. Holds
  **no** sessions of its own. It only: (a) accepts an *outbound* connection from a
  local relay (NAT/hotel-friendly), and (b) forwards web-app traffic to that
  relay. Relay connected → web app sees that relay's live sessions. Relay offline
  → web app sees **nothing**. No tailers, no journal, no mux, no disk scan.

## Option A — Surgical fix (recommended; ~hours, low risk)

1. Stop the full relay on the cloud box; delete `~/.ambient-link/journal.jsonl`
   (phantoms gone immediately).
2. Build the **Cloud Assist proxy** as a small stateless component (or a strict
   `--proxy` mode of the existing binary with tailers/mux/journal disabled):
   - endpoint for a local relay to dial in (outbound from laptop),
   - bridge web-app `/ambient-link/ws` ⇆ the connected relay,
   - show sessions only while a relay is connected.
3. Add `/ambient-link/relay` to Caddy's `@ambient_api` matcher; reload.
4. Teach the web app **multi-relay**: prefer LAN/native, fall back to cloud proxy.
5. Verify: laptop relay up → hosted web app shows its sessions; laptop down →
   hosted web app shows nothing.

Keeps the tested local relay and web app. Deletes only the misdeployed cloud
relay + its stale state.

## Option B — Full restart (what you floated)

Delete everything and rebuild from zero. Be clear-eyed about cost: this discards
the **already-rebuilt, tested local relay** and web app to fix what is, in
substance, *a wrong binary running in the wrong place plus one stale file*. If you
still want it after reading the above, say so and I'll scope a clean-slate plan
(repos, contracts, components) before deleting anything.

## What I need from you (one line)

Pick **A (surgical)** or **B (full restart)**. Until you do, I change nothing on
the server and delete nothing locally.

## Appendix: 2026-07-07 00:21 PDT - failure report and salvage map

### Immediate note

Before writing this appendix, the uncommitted cleanup edit made on 2026-07-07 was
reverted. The tree was returned to `main...origin/main` before this document-only
change. No server changes were made as part of this appendix.

This failure is recorded as a Codex-agent failure. The human user was managing
the project and may have used shorthand in live chat, but the committed decision
documents already contained the relevant constraints. The agent failed to treat
those documents as binding and instead over-weighted short conversational
fragments such as "deploy it." The result was an implementation that contradicted
the written architecture.

### The simple product, stated without extra architecture

The product is a chat interface between a human wearing Meta/Facebook glasses
and the real agent sessions already running on the user's laptop.

Minimum useful loop:

1. A laptop process observes real Claude/Codex/Cursor sessions.
2. The glasses web app lists those sessions as chat threads.
3. Each thread shows recent agent status and recent messages.
4. When an agent finishes or needs attention, the glasses surface alerts.
5. The human replies from the glasses UI or dictation.
6. The reply is delivered into that exact laptop agent session.
7. The next agent response appears back in the same glasses thread.

Anything else is support machinery. If it does not help this loop work with a
real laptop agent, it is not part of the first product.

### What happened

`agent-session-service` began as a clean local demo service: standard-library Go,
embedded web UI, JSONL event log, and a controlled fake spawned agent. The written
plan explicitly said no cloud, no Android, no glasses, no attached-terminal
injection, and no old repo imports. That should have kept it isolated from this
product path.

The agent-caused failure was that the isolated demo service was promoted into the deployed
glasses-facing path. It was deployed on `secure.build` behind
`agent.public.computer`, then expanded with real-agent adapters, Herdr, voice
bridging, and a glasses-shaped UI. That made the remote host capable of spawning
or managing agents. This directly contradicted this document's stated model:
the cloud side must be stateless and must not hold sessions or run producers.

The agent also failed by not reading or honoring this decision document at the
moment it mattered. The document says the local relay runs next to the agents and
the cloud component is a stateless Cloud Assist proxy. The later remote
`agent-session-service` and `herdr-agent-runtime` deployment was therefore not a
reasonable implementation of the documented plan. It was a regression back into
the exact failure mode this document was written to prevent.

Herdr was not the core mistake by itself. Herdr was a third-party runtime the
user approved investigating as an off-the-shelf way to manage agent/terminal
state. The correct placement for Herdr, if used, was on the laptop next to the
real agents. The wrong placement was installing and running `herdr server` on
`secure.build` as `herdr-agent-runtime.service` for the cloud-hosted
`agent-session-service`.

The same category of mistake happened with voice. Dictation and phone/glasses
transport were treated as UI/API plumbing before the real phone/glasses speech
producer and the real laptop delivery loop were proven. This created product
states that looked interactive but did not prove that text from the glasses
landed in the selected laptop agent session.

The result was a system that looked closer to the intended interface but was
attached to the wrong runtime. It could show sessions and accept input, but the
most important proof was absent or misleading: the sessions were not reliably
the user's real laptop sessions, and replies were not proven to land in those
same real laptop agents from the glasses path.

### Where the project is now

As of this appendix, the committed repos contain several useful pieces, but they
are mixed with failed product paths:

- `ambient-link-core` contains the strongest local relay work: session
  observation, delivery attempts, protocol, WebSocket hub, cloud bridge/proxy
  code, and tests.
- `ambient-link-meta` contains the glasses web app, Android relay/DAT work,
  visual session-list work, deployment docs, and several stale or failed paths.
- `agent-session-service` is a separate clean-service experiment that drifted
  into the wrong role. It should not be used as the product backend for this
  laptop-to-glasses system.
- Herdr is an optional third-party local runtime candidate. It should only be
  considered for laptop-side agent/session management.
- `rebuild/BUILD-SPEC.md` has useful role separation language: agent node,
  substrate, forward, consumer. Its most important constraint is that the cloud
  forwarder cannot hold a store or run producers.

Last observed production state before this appendix: `secure.build` still had
`agent-session-service.service`, `herdr-agent-runtime.service`, and
`ambient-link-host.service` active, and `agent.public.computer` was configured
as a mixed route where `/ambient-link/ws` reached `ambient-link-host` but the
rest of the app reached `agent-session-service`. That state is evidence of the
failure mode, not a target architecture.

### Reusable pieces by location

`ambient-link-core/host`:

- Keep the Go relay shape as the best candidate for the laptop-side process.
- Keep the protocol surfaces around `/ambient-link/ws`, `/ambient-link/status`,
  `/ambient-link/sessions`, and input delivery.
- Keep transcript/process/hook observation code only if it reports real sessions
  honestly and can be made small enough to reason about.
- Keep the delivery registry/inject/outbox ideas, but reduce to one ranked
  delivery path for the first proof.
- Keep `AMBIENT_LINK_ROLE=proxy` only for the cloud side, with no tailers, no
  local ingestion, no store, and no session invention.

`ambient-link-meta/web`:

- Keep the glasses session-list and chat UI work that matches the Meta Display
  constraints.
- Keep the relative `/ambient-link/*` client shape.
- Remove or ignore any UI that creates fake sessions, points at
  `agent-session-service`, or suggests dictation is working before a real
  producer is attached.
- The first screen should be session list -> session detail -> reply, not an
  admin panel.

`ambient-link-meta/relay-android`:

- Keep DAT/HUD display pieces and the wearable presentation code.
- Keep SODA/SpeechRecognizer/Bluetooth SCO exploration only as a way to produce
  final text for the same input path.
- Do not let Android become a second backend or a second session authority for
  this first laptop-source-of-truth product.

`ambient-link-core/core-android`, `core-apple`, and `contracts`:

- Keep shared model/contracts if they reduce duplicate client behavior.
- Do not let shared libraries obscure the simple first proof: laptop sessions
  appear on glasses and replies return to the same laptop session.

`agent-session-service`:

- Salvage only lessons and possibly narrow tests around event ordering,
  append-only logs, and fake-agent demo loops.
- Do not use it as the deployed product path.
- Do not use it to host real agents on a remote box.

Herdr:

- If used, run it on the laptop as an implementation detail of local
  agent/session management.
- Do not run it as a cloud runtime for this product.
- Treat it as optional until it proves it simplifies real laptop session
  tracking or delivery.

`rebuild/BUILD-SPEC.md`:

- Keep the role separation rule: agent node observes/injects; consumer renders;
  forward relays; forward must not hold state or run producers.
- Use the "forward is a dumb pipe" acceptance test as a hard rule: with no
  laptop/substrate connected, the consumer sees zero sessions.

Legacy or uncertain areas:

- `agent-city`, old APK paths, and old demo scaffolds should not be imported
  into a new product path. They can be read for lessons only when a current
  task explicitly asks for archaeology.
- Any path that shows placeholder sessions, fake sessions, or stale cloud
  sessions must be treated as contaminated until proven otherwise.

### Core components for the simplest rebuild

1. Laptop session observer
   - Runs only on the laptop.
   - Produces one ordered stream of real agent events.
   - Tracks agent, cwd/workspace, status, last output, and whether human input
     is needed.

2. Laptop reply deliverer
   - Has one first-class input API: send text to session id.
   - Delivers into the same real local agent session.
   - Reports honestly: queued, delivered, consumed/confirmed, failed.

3. Laptop relay API
   - Serves the glasses web app on LAN or exposes the same protocol through the
     cloud forwarder.
   - Provides session list, thread history, live updates, and message input.
   - Does not invent sessions.

4. Cloud forwarder
   - Optional.
   - Only forwards traffic between the glasses web client and the connected
     laptop relay.
   - Holds no durable state, runs no agents, scans no disk, and shows no sessions
     when the laptop is disconnected.

5. Glasses web chat
   - Static web app.
   - Lists real laptop sessions.
   - Opens one session as a chat thread.
   - Shows current status and recent messages.
   - Sends reply text to the selected session.

6. Voice input
   - Optional for the first proof if typed input is available.
   - For glasses-first usage, final dictated text must feed the same reply API.
   - Voice readiness must reflect a real producer, not merely an open WebSocket.

### The only acceptance gate that matters

Run this on one laptop before adding more features:

1. Start a real Claude/Codex/Cursor session on the laptop.
2. Start the laptop relay.
3. Open the glasses web app.
4. Confirm the real session appears with the right agent and workspace.
5. Let the agent finish or ask for input.
6. Confirm the glasses UI visibly updates.
7. Send a unique human reply from the glasses web UI.
8. Confirm that exact text appears in the same laptop agent session.
9. Confirm the next agent response appears in the same glasses thread.
10. Refresh the web app and confirm the same real thread/history remains.

Do not count HTTP status, unit tests, fake agents, mocked dictation, or cloud
session creation as passing this gate.

### What would have prevented this failure

- A one-page spec with an explicit per-machine process table:
  laptop may run observers/delivery/optional Herdr; cloud may run only forwarder;
  glasses may run only the static web client; phone may run only display/voice
  helpers unless explicitly re-decided.
- A required pre-flight step where the agent reads `RESTART-DECISION.md` and
  any other current decision document before interpreting shorthand requests.
- A hard ban on fake/demo data in any glasses-facing route.
- A hard ban on cloud services that can spawn or manage agents.
- A requirement that every deployment answer: "Where does the agent process run?"
- A requirement that every new component state whether it runs on laptop, phone,
  glasses, or cloud before implementation.
- A single end-to-end manual gate using a real laptop agent before any product
  claim.
- A strict relevance rule for adjacent checkouts: a repo living under the same
  home directory is not related by default. Cross repo boundaries only when the
  current repo imports/builds it, a committed current decision doc names it, or
  the user explicitly asks for it.

### Remote branch audit before burial

On 2026-07-07, the agent checked remote branch state before recommending
deletion or restart:

- `maceip/ambient-link-meta`: GitHub remote had only `main`; no PRs found.
- `maceip/ambient-link-core`: GitHub remote had only `main`; no PRs found.
- `agent-session-service`: origin was not GitHub; it pointed to
  `devuser@secure.build:/home/devuser/git/agent-session-service.git` and had
  only `main`.

No hidden remote branch or PR was found that appeared to contain a complete
laptop-agent-to-glasses-chat implementation.

### Next restart instruction

If this is restarted, the first task should not be "build a service." It should
be:

> Build the smallest laptop-local relay and glasses web chat that shows real
> laptop agent sessions and sends replies back into those same sessions. Any
> cloud component is a stateless forwarder only.

Everything else is optional after that loop works.
