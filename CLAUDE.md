# Ambient Link — hard rules (read before doing ANYTHING)

The product: a chat interface between a human wearing Meta glasses and the
**real agent sessions already running on the user's laptop**. Laptop sessions
appear on the glasses; replies from the glasses land in those same laptop
sessions. Everything else is support machinery.

## Per-machine process table (binding)

| Machine | May run | Must NEVER run |
|---|---|---|
| **Laptop** | session observers, reply delivery, relay API (`ambient-link-core/host`), optionally Herdr | — |
| **Cloud** (`public.computer` / `secure.build`) | the relay binary in `AMBIENT_LINK_ROLE=proxy` ONLY (stateless forwarder) + Caddy static hosting of `web/` | anything that spawns/manages agents, holds session state, tails disks, or invents sessions |
| **Glasses** | the static web client | — |
| **Phone** | display / voice-input helpers | a second backend or second session authority |

## Hard rules

1. **The cloud is a dumb pipe.** Acceptance test: with no laptop relay
   connected, the hosted web app shows **zero** sessions. If a cloud change
   fails this, it is wrong regardless of what the chat transcript says.
2. **No fake/demo data on any glasses-facing route.** Placeholder sessions,
   mocked dictation, and fake agents are contamination, not progress.
3. **Before implementing any new component, state which machine it runs on.**
   If the answer is "cloud" and it isn't the stateless proxy, stop.
4. **Read `RESTART-DECISION.md` (especially the 2026-07-07 appendix) before
   interpreting shorthand like "deploy it".** Committed decision docs override
   conversational fragments.
5. **Adjacent checkouts in `~` are NOT part of this product by default**
   (`agent-session-service`, `agent-city`, `rebuild/`, old demo scaffolds are
   lessons-only). Cross a repo boundary only when this repo imports it, a
   current decision doc names it, or the user explicitly asks.
6. **Never claim the product works without passing the manual gate** in
   `RESTART-DECISION.md` § "The only acceptance gate that matters": a real
   laptop agent session visible on the hosted web app, a unique reply sent
   from that UI, and that exact text confirmed inside the same laptop session.
   HTTP 200s, unit tests, and fake agents do not count.

## Layout

- `web/` — the glasses web app (static; deployed to
  `https://public.computer/ambient-link/` on push to `main`).
- `relay-android/` — phone/glasses helper app (display + voice input only).
- The laptop relay lives in the sibling repo `~/ambient-link-core` (`host/`).
- E2E: `scripts/e2e-web.sh`.
