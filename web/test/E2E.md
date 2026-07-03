# Playwright e2e — deployed web app

All specs hit **https://public.computer/ambient-link/** (real UI, WS, and relay API — no HTTP mocks).

## Projects

| Project | Spec | Needs laptop? | What it proves |
|---------|------|---------------|----------------|
| **glasses** | `glasses.spec.mjs` | No | Cold open, no JS errors, WS connects, list matches `/status`, create POST honesty, SW reload, touch targets |
| **integrated** | `integrated.spec.mjs` | Yes | Real prompts → cloud bridge → laptop agent → replies in UI |
| **dictate** | `dictate.spec.mjs` | Yes + phone | Web → phone SODA dictate (interactive) |

### glasses (same path as Meta Display)

- 600×600 viewport, **service worker enabled** (not unregistered)
- No `bridgeOnce`, no pre-seeded sessions
- Fails on `pageerror` (e.g. `threadOrder is not defined`)
- Compares live relay session count to `.thread-row` count
- Create form uses a **fresh cwd** and asserts UI matches relay `POST /sessions` (501 toast today, thread open when wired)

### integrated (full agent loop)

Requires:

1. Laptop relay: `bash scripts/start-host.sh` with `AMBIENT_LINK_CLOUD=wss://public.computer/ambient-link/relay`
2. `cloud_peer: true` on deployed `/ambient-link/status`
3. Live e2e session (bootstrapped by run script)

Opens an **existing** thread and runs two real agent turns through the cloud bridge.

## Run

```bash
# Glasses-path only — no laptop required
cd web && npx playwright test --project=glasses

# Full agent conversation (laptop + bridge + responder)
bash scripts/run-e2e-new-session.sh

# All projects
cd web && npm run test:e2e

# Interactive dictate (phone USB)
bash scripts/run-e2e-dictate.sh
```

## Screenshots

Integrated (`web/test/output/`):

- `integrated-01-thread-open.png`
- `integrated-02-first-turn.png`
- `integrated-03-second-turn.png`

Dictate: `dictate-*.png`, `dictate-logcat.txt`

## What we removed

- **global-setup** that blocked every spec unless laptop was up
- **SW unregister** in default path (was hiding cache bugs)
- **Fake “create session”** that reused an existing cwd and never hit `POST /sessions`
- **`test.skip`** when no sessions — glasses spec branches on empty vs populated relay instead
