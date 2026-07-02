# Playwright e2e — deployed web app

Tests run against **https://public.computer/ambient-link/** (deployed UI + relay on same host).

## No mocks in the test

| What | Real? |
|------|--------|
| Web UI | `https://public.computer/ambient-link/` |
| WebSocket / API | `wss://public.computer/ambient-link/ws` |
| User prompts | Browser → deployed relay → **cloud peer** → laptop agent |
| Agent replies | Cursor jsonl on laptop → mirrored to cloud → UI card |
| `pushAssistant` / `debug/yank` / mock HTTP server | **Removed — not used** |
| Service worker blocked in test | Cache bust only; relay is not mocked |

`global-setup` runs **one relay-bridge cycle** (laptop `:5181` → `public.computer` via production `/ambient-link/ingest`) so the deployed index lists your live laptop sessions. That mirrors real session previews; it does **not** fabricate agent replies in the test body.

`global-setup` also requires `"cloud_peer": true` on deployed `/ambient-link/status` (laptop dialed `wss://public.computer/ambient-link/relay`).

## Prerequisites

1. Laptop relay: `bash scripts/start-host.sh` with `AMBIENT_LINK_CLOUD=wss://public.computer/ambient-link/relay`
2. Live cursor session (default: this repo)
3. Cloud peer connected: `curl -s https://public.computer/ambient-link/status | jq .cloud_peer` → `true`

## Run

```bash
bash scripts/run-e2e-new-session.sh
```

Playwright opens `https://public.computer/ambient-link/` (not localhost). Override only if needed:

```bash
AMBIENT_WEB_ORIGIN=https://public.computer \
AMBIENT_WEB_PATH=/ambient-link/ \
AMBIENT_RELAY_HOST=https://public.computer \
bash scripts/run-e2e-new-session.sh
```

## Screenshots

- `01-create-session-form.png`
- `02-session-list-active.png`
- `03-session-first-prompt.png`
- `04-session-two-turns.png`

## Phone dictate (interactive)

Tests web → phone SODA bridge with **Bluetooth SCO** (glasses mic). You speak during the run.

```bash
bash scripts/run-e2e-dictate.sh
```

- Opens the deployed app in a **headed** browser (override: `AMBIENT_DICTATE_HEADED=0`)
- Wakes the phone daemon over **adb** (USB required)
- Clicks **Dictate** and waits **90s** for you to speak (`AMBIENT_DICTATE_WAIT_MS`)
- Saves `web/test/output/dictate-*.png` and `dictate-logcat.txt` (checks `sco=true` in logcat)

Phone app toggle: **Glasses Bluetooth mic** (default on). Turning it off uses the phone mic and avoids the glasses call UI.
