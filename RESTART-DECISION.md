# Restart vs. surgical-fix — decision brief

Status: **nothing deleted, no server changes made.** This is a prep doc so the
decision and (if chosen) the restart are fast next time. Read top to bottom; it's
short.

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
