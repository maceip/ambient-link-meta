#!/usr/bin/env bash
# Start ambient-link host + serve glasses web companion. Run on the Mac where agents live.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB="$ROOT/web"
HOST_BIN="${AMBIENT_LINK_HOST_BIN:-/tmp/ambient-link-host}"

if [[ ! -x "$HOST_BIN" ]]; then
  echo "building host…"
  (cd "$ROOT/../ambient-link-core/host" && go build -o "$HOST_BIN" ./cmd/host)
fi

exec "$HOST_BIN" serve -listen 0.0.0.0:5181 -web-root "$WEB" -log info
