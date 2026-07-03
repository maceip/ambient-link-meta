# ambient-link-meta

> Ambient Link target for **Meta Ray-Ban Display glasses** (codename
> Hypernova). Phone relays (iOS + Android) drive HUD peek cards in
> response to events from [ambient-link-core](https://github.com/maceip/ambient-link-core);
> a glasses-side web app provides the in-HUD launcher entry for browsing.

This repo is the *display half* of Ambient Link for the Meta Display
platform. It deliberately knows nothing about agent observation — it
takes already-normalized session events off the wire (per
[ambient-link-core/protocol/PROTOCOL.md](https://github.com/maceip/ambient-link-core/blob/main/protocol/PROTOCOL.md))
and renders them.

## Companion-link contract

Capture (camera/audio) follows the vendor-neutral `GlassLink` contract, now shared
as the **`com.ambientlink:core-android`** library
([ambient-link-core/core-android](https://github.com/maceip/ambient-link-core/tree/main/core-android))
alongside `EphemeralBuffer`, `Throttle`, `Session`, `RelayClient`, and `WearPaths` —
see the routing/perf plan in
[ambient-link-core/ROUTING.md](https://github.com/maceip/ambient-link-core/blob/main/ROUTING.md),
extracted from the recovered Cosmo teardown. `relay-android` consumes the library
via a **Gradle composite build** (`includeBuild("../../ambient-link-core/core-android")`,
the same mechanism as `ambient-link-google`; aligned on AGP 8.7 / Kotlin 2.1.20 /
Gradle 8.14.1, so no publish step). `SodaDictationEngine` implements the shared
`com.ambientlink.core.SttEngine`; a DAT `GlassLink` impl would use
`com.ambientlink.core.GlassLink` with the display side in `hud/`. Perf rules to
honor: idempotent bind, 1-frame/10s throttle, TTL ephemeral buffer, capture in a
typed foreground service.

## The UX, in one diagram

```
ambient (glasses on)
   │
   │ thread_idle event off the wire
   ▼
HUD CARD            thread label · last message · chips by awaiting:
                    done:       [ continue ] [ dictate ] [ dismiss ]
                    question:   [ dictate ] [ dismiss ]
                    permission: [ approve ] [ deny ]
   │
   │ user picks a chip (or hardware back = dismiss)
   ▼
reply posted back through the relay → host inject → agent continues
```

Phone relay discovers the Mac host on LAN via mDNS (`_ambientlink._tcp`) when
no relay URL is configured. HUD replies go through the host inject path into
live CLI agents (cursor-agent, claude, codex) via tmux or TTY.

Integration gate: `scripts/check.sh` (host + protocol + APK SODA + phone logcat).

## What's in here

| Path | Purpose |
|---|---|
| [`relay-android/`](relay-android) | Kotlin app — headless DAT companion. Foreground service holds DAT session, subscribes to host WS, on `thread_idle` pushes the peek card and routes chip taps back. No on-phone chat UI. |
| [`relay-ios/`](relay-ios) | Swift app — same job as `relay-android`, idiomatically native. Built against meta-wearables-dat-ios SDK. |
| [`web/`](web) | Static PWA registered as a Meta Display web app — gets the in-HUD launcher icon. WhatsApp-style multi-thread chat view, used for browsing / cold-open / long-form composition. |
| [`push-test/`](push-test) | Diagnostic page used to verify what the Stella web-app runtime does and doesn't support. Research artifact, not part of the production trigger path. |

## Why two native codebases (no cross-platform abstraction)

The phone companion is the foundation for the live-audio path coming
later (bidirectional voice between agent and user via the glasses mic +
speakers). Real-time audio over the DAT transport is latency-sensitive
and any cross-platform wrapper (React Native, Flutter, KMP) adds an
extra hop on every chunk, a wrapper that leaks when the DAT SDK evolves,
and toolchain overhead disproportionate to the savings.

Both relays implement the same state machine
(`Ambient → Peeking → Engaged → Snoozed`) defined in
[ambient-link-core/protocol/PROTOCOL.md](https://github.com/maceip/ambient-link-core/blob/main/protocol/PROTOCOL.md).

## Why the web app AND a native relay (not one or the other)

Meta confirms in their own developer docs that **third-party PWAs cannot
post to the HUD notification surface** — `showNotification`, Web Push, and
background SW delivery are listed in the "unavailable" column. So the
web app alone can't deliver the proactive peek-card UX; only the native
DAT SDK path can.

But native apps can't get a launcher icon in the on-glasses launcher —
that's a web-only affordance. So:

- **Web app** = in-HUD launcher icon + browse/cold-open + long-form
  composition.
- **Native relay** = the proactive peek-card path (DAT createSession +
  sendContent on host event).

If you don't care about proactive HUD push and just want to chat when you
open the app cold, the web app stands alone.

## Setup

1. Install [ambient-link-core](https://github.com/maceip/ambient-link-core)
   on the machine(s) where your agents run.
2. Install the native relay on your phone (see
   [`relay-android/`](relay-android) or [`relay-ios/`](relay-ios)).
3. Add the web app to your glasses launcher (see [`web/`](web)).
4. Configure both phone relay and web app to point at your host's WS URL.

## Production deploy

Web app deploys automatically via GitHub Actions on every push to `main`
(`.github/workflows/deploy-web-prod.yml` → `git reset --hard origin/main` on
`public.computer`). CI verify: deploy-trigger-4.
