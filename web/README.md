# Glasses web app

Static SPA served to Meta Display glasses. No build step; Caddy on
`public.computer` serves these files directly at the installed origin
`https://relay.public.computer/`, the legacy installed-app alias
`https://agent.public.computer/`, and the compatibility path `/ambient-link/`.
All data comes from the relay over relative
`/ambient-link/*` paths — there are no mocks and no fixtures in this app.

- Session list, chat, compose: `app.js` (WS `/ambient-link/ws` + polling
  `/ambient-link/status`)
- Dictation: web only signals `dictate_*`; speech-to-text runs on the phone
  (relay-android SODA) and partials stream back over the relay
- Cache busting: bump `BUILD` in `index.html` **and** `CACHE` in `sw.js`
  together (see DEPLOYMENT.md §5)

## Tests

Hermetic end-to-end suite against the real relay binary and a fake `claude`
process that honors Claude Code's observable contract:

```bash
../scripts/e2e-web.sh          # builds relay if needed, runs Playwright
```

Runs in CI on every web change (`.github/workflows/web-e2e.yml`); status
badge on the repo README.
