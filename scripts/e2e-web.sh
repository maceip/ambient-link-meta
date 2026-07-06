#!/bin/bash
# One-command E2E for the glasses web app. Hermetic: boots its own relay on
# 127.0.0.1 with a throwaway HOME plus a fake "claude" agent in tmux; never
# touches prod, the LAN relay on :5181, or your real ~/.claude.
#
# Usage: scripts/e2e-web.sh            (builds the relay if missing, runs suite)
#        AMBIENT_HOST_BIN=/path/to/host scripts/e2e-web.sh
set -euo pipefail

META_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${AMBIENT_LINK_CORE:-$HOME/ambient-link-core}"
BIN="${AMBIENT_HOST_BIN:-$CORE/host/bin/ambient-link-host}"

command -v tmux >/dev/null || { echo "tmux is required (brew install tmux)"; exit 1; }
command -v node >/dev/null || { echo "node is required"; exit 1; }

if [ ! -x "$BIN" ]; then
  echo "building relay: $BIN"
  (cd "$CORE/host" && go build -o bin/ambient-link-host ./cmd/host)
fi

cd "$META_ROOT/web"
AMBIENT_HOST_BIN="$BIN" npx playwright test "$@"
