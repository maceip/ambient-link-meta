# Deploying to public.computer

The canonical, repeatable steps to update the **glasses web app** and the
**relay daemon** running on `public.computer`. No secrets live in this file — it
references paths, service names, and commands only. (SSH keys, tokens, and TLS
material are managed outside the repo.)

> TL;DR — to ship a web app change: **commit + push locally, then `git pull` on
> the server.** Caddy serves the files directly, so the change is live the moment
> the pull finishes (modulo the service-worker cache — see §5).

---

## 1. What runs where (the scaffolding)

| Piece | Location on `public.computer` | Owner | Notes |
|---|---|---|---|
| Web app (static) | `/home/devuser/ambient-link-meta/web` | `devuser` | git checkout of this repo; **served directly by Caddy** |
| Relay daemon | `/home/devuser/ambient-link-core/host/bin/ambient-link-host` | `devuser` | systemd service, binds `127.0.0.1:5181` |
| Relay source | `/home/devuser/ambient-link-core` | `devuser` | git checkout; server has Go (`/usr/bin/go`) |
| Reverse proxy / TLS | Caddy (`/etc/caddy/Caddyfile`) | root | terminates HTTPS for `public.computer` |
| systemd unit | `/etc/systemd/system/ambient-link-host.service` | root | `Type=simple`, runs as `devuser`, `HOME=/home/devuser` |

**Request routing** (from the `public.computer` Caddy block):

- `https://public.computer/ambient-link/` and all `*.css`/`*.js`/`index.html`/
  `manifest.json`/`sw.js` → **static files** from `…/ambient-link-meta/web`
  (Caddy `file_server`, prefix stripped). The relay is *not* involved in serving
  the web app here.
- API + WebSocket paths → **reverse-proxied to the relay** on `127.0.0.1:5181`:
  `/ambient-link/ws`, `/ambient-link/status`, `/ambient-link/sessions`,
  `/ambient-link/pair`, `/ambient-link/ingest`, `/ambient-link/hooks/*`,
  `/ambient-link/debug/*`.

> Consequence: **web-app-only changes never require touching the relay**, and
> relay changes never require redeploying the web app. Treat them as two
> independent deploys.

Access is via `ssh root@public.computer`. The relay files are owned by
`devuser`, so run git/build steps **as devuser** (`sudo -u devuser -H …`) to keep
ownership clean and avoid git "dubious ownership" errors.

---

## 2. Update the WEB APP (the common case)

**Automatic via GitHub Actions.** Every push to `main` runs
`.github/workflows/deploy-web-prod.yml`, which SSHes to the server and runs
`git fetch && git reset --hard origin/main` in `/home/devuser/ambient-link-meta`.
Caddy serves those files at `https://public.computer/ambient-link/`.

Local workflow:

```bash
git add -A && git commit -m "web: <what changed>"
git push origin main
# CI deploys — watch Actions tab on GitHub
```

Do **not** rsync, scp, or edit `web/` on the server. Do **not** use a local deploy
script. Disk must always match `origin/main`.

### GitHub repository secrets (Settings → Secrets and variables → Actions)

| Secret | Example | Purpose |
|---|---|---|
| `PROD_SSH_PRIVATE_KEY` | `-----BEGIN OPENSSH PRIVATE KEY-----…` | Deploy key; public half in server `authorized_keys` |
| `PROD_SSH_HOST` | `public.computer` | Production SSH host |
| `PROD_SSH_USER` | `root` | SSH user (must be able to `sudo -u devuser`) |
| `PROD_SSH_PORT` | `22` | Optional; omit to use 22 |

Generate a deploy-only key on your laptop:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ambient-link-prod-deploy -C "github-actions-ambient-link-meta"
ssh root@public.computer "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys" < ~/.ssh/ambient-link-prod-deploy.pub
```

Put the **private** key contents into `PROD_SSH_PRIVATE_KEY`. Restrict the public
key to `from="github-actions-ip-range"` if you add GitHub's meta IPs later; ed25519
deploy key with no passphrase is the minimum to get running.

Go to §4 to verify manually and §5 for service-worker cache busting.

> **train.public.computer** uses `/opt/train` — a different vhost. Ambient Link
> is only `…/ambient-link-meta/web` on `public.computer`.

---

## 3. Update the RELAY daemon

Only when relay (Go) code changes. Two options:

### Path A — build on the server (simplest; server has Go)

```bash
ssh root@public.computer '
  sudo -u devuser -H git -C /home/devuser/ambient-link-core pull --ff-only &&
  sudo -u devuser -H bash -lc "cd /home/devuser/ambient-link-core/host && go build -o bin/ambient-link-host ./cmd/host" &&
  systemctl restart ambient-link-host &&
  sleep 1 && systemctl --no-pager status ambient-link-host | head -5
'
```

### Path B — cross-compile on the laptop, ship the binary

```bash
# laptop, in ambient-link-core/host
GOOS=linux GOARCH=amd64 go build -o ambient-link-host.linux ./cmd/host
scp ambient-link-host.linux root@public.computer:/tmp/ambient-link-host.new
ssh root@public.computer '
  install -o devuser -g devuser -m 0755 /tmp/ambient-link-host.new /home/devuser/ambient-link-core/host/bin/ambient-link-host &&
  systemctl restart ambient-link-host &&
  sleep 1 && systemctl --no-pager status ambient-link-host | head -5
'
```

> Toolchain caveat: the server has Go 1.22. If `go.mod` requires a newer Go,
> Path A will fail — use Path B (build with the laptop's Go) instead.

### If the service invocation needs to change

The unit is at `/etc/systemd/system/ambient-link-host.service`. Current
`ExecStart` passes flags (`serve -listen 127.0.0.1:5181 -web-root … -log info`).
After editing the unit:

```bash
ssh root@public.computer 'systemctl daemon-reload && systemctl restart ambient-link-host'
```

Keep `127.0.0.1:5181` (Caddy proxies to it) and `HOME=/home/devuser`.

---

## 4. Verify a deploy

```bash
# relay alive + which build is running
ssh root@public.computer 'curl -s http://127.0.0.1:5181/ambient-link/status | head -c 400; echo'
ssh root@public.computer 'systemctl is-active ambient-link-host'

# web app reachable through Caddy/TLS (public edge)
curl -sI https://public.computer/ambient-link/ | head -5
curl -s  https://public.computer/ambient-link/app.js | head -c 80; echo
```

To confirm the web change is actually live, grep the served asset for a known
string from your edit:

```bash
curl -s https://public.computer/ambient-link/companion.css | grep -c "<your-new-token>"
```

---

## 5. Cache busting (the usual "why isn't it updating")

The web app registers a **service worker** (`sw.js`) and Caddy sets
`Cache-Control` (assets `max-age=86400`, shell `max-age=30`). A pulled change can
look "not deployed" because the browser/glasses is serving the cached SW bundle.

When a change doesn't show:

1. **Bump the SW cache version** in `web/sw.js` (the cache name/version constant)
   as part of the change. This is the reliable fix — the new SW activates and
   evicts the old cache. Commit it with the web change.
2. Hard refresh / unregister: in DevTools → Application → Service Workers →
   *Unregister*, then reload. On glasses, reinstall the web app entry.
3. If `git pull` fails with "local changes would be overwritten", the checkout
   drifted (someone copied files outside git). As `devuser`:
   `git fetch origin && git reset --hard origin/main`. Then fix ownership if
   needed: `chown -R devuser:devuser /home/devuser/ambient-link-meta`. **Do not
   rsync to prod again** — commit locally and pull.

---

## 6. Caddy routes (reference)

To add a new relay endpoint to the public edge (e.g. a new WS path), add it to
the `@ambient_api` matcher in the `public.computer` block of `/etc/caddy/Caddyfile`:

```
@ambient_api path /ambient-link/ws /ambient-link/status /ambient-link/sessions \
  /ambient-link/pair /ambient-link/ingest /ambient-link/hooks/* /ambient-link/debug/*
handle @ambient_api { reverse_proxy 127.0.0.1:5181 }
```

Then:

```bash
ssh root@public.computer 'caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy'
```

Anything **not** in `@ambient_api` (and not a static `/ambient-link/*` file) will
not reach the relay — this is the #1 cause of `404` on a new API path.

---

## 7. Common failures → fix

| Symptom | Cause | Fix |
|---|---|---|
| Web change not visible | service-worker cache | bump `sw.js` version (§5) |
| `404` on a new `/ambient-link/...` API path | not in Caddy `@ambient_api` | add it + reload Caddy (§6) |
| git "dubious ownership" | running git as root on devuser repo | run as `sudo -u devuser -H git …` |
| `go build` fails on server | server Go too old for `go.mod` | cross-compile on laptop (§3 Path B) |
| relay up but no sessions | sessions run on the **laptop**, not the server | needs the cloud bridge (laptop → `wss://public.computer/ambient-link/relay`); separate feature |
| `git pull` blocked on server | disk changed outside git (rsync/scp/manual edit) | `git reset --hard origin/main` as devuser; deploy via git only (§2) |
| `502` on `/ambient-link/ws` | relay down | `systemctl restart ambient-link-host` + check `journalctl -u ambient-link-host` |

---

## 8. One-shot quick reference

```bash
# WEB: push to main — GitHub Actions deploys automatically
git push origin main

# WEB: manual recovery only (if CI is down)
ssh root@public.computer 'sudo -u devuser -H git -C /home/devuser/ambient-link-meta fetch origin && sudo -u devuser -H git -C /home/devuser/ambient-link-meta reset --hard origin/main'

# RELAY: pull, build, restart (server-side build)
ssh root@public.computer 'sudo -u devuser -H git -C /home/devuser/ambient-link-core pull --ff-only && \
  sudo -u devuser -H bash -lc "cd /home/devuser/ambient-link-core/host && go build -o bin/ambient-link-host ./cmd/host" && \
  systemctl restart ambient-link-host'

# VERIFY
curl -sI https://public.computer/ambient-link/ | head -3
ssh root@public.computer 'curl -s http://127.0.0.1:5181/ambient-link/status | head -c 200; echo'
```
