# Plan: Phone as source of truth + web↔phone sync, with local-first transports

## Context

Today the **laptop relay/host is the canonical source of truth** (`DELIVERY.md` "Sync layers
(host as source of truth)"; `mux.go` holds canonical state; phone + web are caches). Ryan's
authoritative direction inverts this: the **phone (native app) is the source of truth**, the
laptop is just one *producer*, agents will also run in cloud / on-phone, and laptop connectivity
must become optional. The web app must talk to the **phone**, not the laptop — primarily for
**latency**: an on-the-face HUD must feel instant on tap/dismiss/compose, and round-tripping to a
(possibly remote) laptop kills that. The relay must also keep a durable, **never-deleted** log of
all agent activity even when the phone is offline (separate "journal-of-record" truth).

This plan covers: (1) cited research on local link options + cloud-proxy protocol + fallback
strategy; (2) a re-architecture making the phone canonical via **reuse** of the existing Go core;
(3) a transport design that greedily prefers local links; (4) the one blocker that must be settled
by an on-device spike before the W→N transport can be built.

## Cited research (full sources in conversation; key points here)

**A. Glasses web-app runtime — the blocker for local W→N.**
- Web-app URL must be `https://`, sockets must be `wss://`, and the app document must be hosted on
  a **publicly reachable** HTTPS host; localhost/LAN hosting appears unsupported.
  ([Meta web-apps setup](https://wearables.developer.meta.com/docs/develop/webapps/setup/))
- Glasses **tether to the phone over Bluetooth, not a shared Wi-Fi subnet** → a phone-local server
  is very likely **not IP-reachable** from the glasses web app.
  ([review/hardware](https://www.tomsguide.com/computing/smart-glasses/meta-ray-ban-display-review))
- **WebRTC-on-glasses and service-worker/PWA support are UNDOCUMENTED** — must be tested on-device.
- Generic browser rule: an HTTPS page **cannot open `ws://` to a bare LAN IP** (mixed content, no
  upgrade); only **same-device loopback** is exempt — and glasses≠phone are different devices.
  ([MDN mixed content](https://developer.mozilla.org/en-US/docs/Web/Security/Mixed_content),
  [secure contexts](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts))
- The only *proven* low-latency local glasses↔phone channel today is the **DAT native SDK** (what
  the native HUD already uses) — it is **not** exposed to the on-glasses web app.

**B. Local web↔phone transport options (general).** Top candidates: (1) **WebSocket to an in-app
server** — lowest latency, universal, but only works browser→app on the **same device** via
loopback; bare-LAN-IP `ws://` from HTTPS is blocked. (2) **WebRTC data channel** — lowest
cross-device steady-state latency via local ICE host candidates, but Chrome obscures host IPs
behind mDNS `.local` candidates (peer must resolve mDNS or you get relayed). Web Bluetooth (no iOS
support, ~7.5ms floor), Nearby/Wi-Fi-Aware (native-only, no browser API), raw TCP (IWA-only) are
not viable for a browser↔nearby-app link.

**C. Cloud-assist proxy + fallback — clean answer.** Use **WebRTC DataChannel + ICE + self-hosted
TURN**, with a **thin WebSocket for signaling only**. Rationale: **ICE is itself the
greedy-local-then-relay engine** — RFC 8445 ranks host candidates at type-pref **126** and relay
at **0** ("used only as a last resort"), runs priority-ordered connectivity checks, picks the best
working pair, and recovers via `restartIce()`. So we **do not hand-roll** the local-first fallback
(a custom Happy-Eyeballs/RFC-8305 racer over WebSocket would re-implement ICE, worse).
([RFC 8445](https://www.rfc-editor.org/rfc/rfc8445.html),
[MDN WebRTC connectivity](https://developer.mozilla.org/en-US/docs/Web/API/WebRTC_API/Connectivity),
[restartIce](https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/restartIce))
Self-hosted TURN (coturn) is the concrete realization of Ryan's already-decided "self-hosted
rendezvous relay."

## The blocker (must resolve first, empirically)

**Can the glasses web app reach the phone over a *local* link at all?** Every local W→N path is
either blocked (LAN `ws://`, BT-not-IP tether) or undocumented (WebRTC-on-glasses) on this runtime.
The latency payoff for W→N depends entirely on this. **Decision: a feasibility spike comes before
any W→N transport work.** If the spike fails, the web app reaches the phone via the **cloud proxy**
even when physically co-located (correct but not local-latency); the native DAT HUD remains the
true instant interactive surface.

## Target architecture (proposed)

```
producers (each keeps its own never-delete journal)        canonical truth
  laptop core ──┐                                           ┌───────────────┐
  cloud agents ─┼── local-first (ICE) / TURN fallback ─────►│  PHONE: Go core│
  on-phone     ─┘                                           │  mux + journal │◄── DAT ──► glasses HUD
                                                            │  + WS hub      │
  glasses web app ◄── local (spike) / TURN fallback ───────►└───────────────┘
```

- **Phone = canonical state + durable never-delete journal**, by **running the existing Go core
  (`ambient-link-core/host`) on the phone via gomobile/gobind** inside the Android foreground
  service — reusing the tested `mux.go`, `journal.go`, `sink/ws.go`, `delivery/*` rather than
  re-porting them to Kotlin. (iOS later via the same gomobile bind.)
- **Laptop = producer**: keeps running `producers/*` (hooks/jsonl/proc) and its own local
  never-delete journal, but instead of being the hub it **syncs its events into the phone core**.
- **W→N**: glasses web app → phone Go-core WS hub (transport gated on spike; cloud-proxy fallback).
- **N→R**: phone core ↔ laptop producer over WebRTC/ICE (local-first) with TURN fallback.
- **Greedy-local everywhere via ICE**; TURN only as last resort.
- **Never-delete invariant** holds on both the phone journal and each producer's journal.

## Change surface (reuse-first; pattern over line-by-line)

**ambient-link-core (Go) — make the core relocatable + add producer mode:**
- Wrap the existing daemon wiring in `host/cmd/host/main.go:138` (`runServe`) into a library entry
  point that gomobile can bind, so the same `sink.NewHub` + `mux.New` + `journal.Open` graph runs
  on the phone unchanged.
- Add a **producer/uploader mode**: reuse `producers/*` and `mux` for local dedup, but replace the
  `sink.Hub` fan-out with a client that streams `proto.Broadcast`s into a remote phone core. New
  small module (e.g. `host/internal/uplink/`). Laptop keeps a local journal (`journal.go`,
  append-only, never delete).
- Reverse the reply path: `inject`/`delivery/*` (tmux/tty/outbox) stay on the laptop; the phone
  core sends `input` to the laptop producer, which injects locally. Reuse `delivery/registry.go`,
  `outbox.go` as-is.
- Wire WebRTC/ICE transport for the core↔producer link (Pion for Go); thin WS signaling endpoint.

**relay-android (Kotlin) — host the Go core + serve the web app:**
- `RelayService.kt:37` foreground service hosts the bound Go core (WS hub now runs *on the phone*).
- `RelayClient.kt` / `RelayService.kt:90` (`resolveRelayUrl`) flip from "dial the laptop" to
  "core runs in-process; laptop is a producer that connects in."
- `HudPresenter.kt`, `DatDisplaySession.kt` — unchanged (they consume core events as today).
- WebRTC peer (for web↔phone fallback + laptop↔phone) via a Kotlin WebRTC lib; ICE/TURN config.

**ambient-link-meta/web — point the web app at the phone, decouple transport:**
- `web/app.js:6` — replace hardcoded `WS_URL = location.host` with a resolver that targets the
  **phone** (WebRTC/ICE first; TURN-relayed `wss://` fallback), not the serving origin.
- `web/app.js` REST calls (`/ambient-link/status` ~`:735`, `/ambient-link/sessions` ~`:500`) — same
  redirection to the phone core.
- Keep `web/sw.js` cache-first shell (UI loads offline; only the data channel needs the network).

**Cloud (new, small):** self-hosted **coturn** (TURN) + a thin **WebSocket signaling** service to
broker SDP/ICE and rendezvous the NAT'd laptop. Control traffic only; no media/state transits it.

## Sequencing (phased — spike gates the W→N transport)

1. **Spike (do first, ~1–2 days, mostly on-device):** extend `push-test/` to probe, on real
   glasses: WebRTC `RTCPeerConnection` + data channel (do local/host ICE candidates appear?);
   reachability of a phone endpoint; `wss://` to a public host; service-worker persistence. Output
   decides whether W→N can ever be local, or is proxy-only.
2. **Phone-as-core (unblocked, build in parallel):** gomobile-bind the Go core; run it in
   `RelayService`; make the laptop a producer that syncs in; verify phone holds canonical state +
   never-delete journal with the laptop offline.
3. **N→R local-first transport:** WebRTC/ICE/TURN between laptop producer and phone core.
4. **W→N transport:** per spike result — local (WebRTC/ICE) if feasible, else cloud-proxy via TURN.
   Point `web/app.js` at the phone.
5. **Cloud proxy:** stand up coturn + signaling; confirm ICE falls back to relay when local fails.

## Decided vs proposed vs blocked

- **Decided by Ryan:** phone = source of truth; relay never deletes / owns full transcripts;
  self-hosted relay (now realized as self-hosted TURN); web app talks to the phone.
- **Proposed by Claude (needs approval):** reuse the Go core on-phone via gomobile (vs Kotlin
  rewrite); WebRTC/ICE/TURN as the unified local-first transport; the phased sequencing.
- **Blocked pending spike:** whether W→N can be a *local* link on the glasses at all.

## Verification

- **Spike:** on-device log from the `push-test` probe showing WebRTC/data-channel + reachability
  results (empirical, not inferred).
- **Phone canonical:** kill the laptop; confirm the phone core still serves state to the web app
  and HUD, and its journal keeps appending; restart phone, confirm journal reload + monotonic seq.
- **Never-delete:** assert no code path truncates/rotates/deletes either journal; large-run smoke
  test shows only growth.
- **Local-first:** with phone+laptop on the same LAN, confirm ICE selects a **host** candidate
  (not relay) for N→R; force them apart and confirm graceful TURN fallback via `restartIce`.
- **Existing tests:** run `host/...` Go tests (`mux_test.go`, `journal_test.go`, `delivery_test.go`)
  after the library/producer refactor; run `web/test/*.mjs`; run meta `scripts/check.sh`.
