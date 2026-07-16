# Glasses web app (built output)

Static files served to Meta Display glasses. **Everything here except `test/`
and the npm/Playwright config is generated** — the source is the Svelte 5 app
in [`../web-next/`](../web-next/); edit there and run `npm run build`, never
edit `index.html`, `sw.js`, or `assets/*` by hand.

Caddy on `public.computer` serves this directory directly at the installed
origin `https://agent.public.computer/` and the compatibility path
`/ambient-link/`. All data comes from the relay over relative
`/ambient-link/*` paths — no mocks, no fixtures.

- Protocol contract: [`../PROTOCOL-WEB.md`](../PROTOCOL-WEB.md)
- Dictation: web only signals `dictate_*`; speech-to-text runs on the phone
  (relay-android SODA) and partials stream back over the relay
- Cache busting is automatic: assets are content-hashed and `sw.js` is
  generated with the hashed shell list (see `../web-next/sw-plugin.js` and
  DEPLOYMENT.md §5)

## Tests

Hermetic end-to-end suite against the real relay binary and a fake `claude`
process that honors Claude Code's observable contract:

```bash
../scripts/e2e-web.sh          # builds relay if needed, runs Playwright
```

Unit tests for the store/protocol/delivery logic live in `../web-next/test/`
(`cd ../web-next && npm test`).

CI (`.github/workflows/web-e2e.yml`) runs the unit tests, rebuilds, fails if
the committed output here is stale, then runs the Playwright suite on every
web or web-next change.
