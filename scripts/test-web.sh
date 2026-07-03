#!/usr/bin/env bash
# Web companion smoke checks against a running Mac relay (Playwright lives in npm run test:e2e).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
LOG=/tmp/ambient-web-test.log
: >"$LOG"

fail() { echo "FAIL: $1" | tee -a "$LOG" >&2; exit 1; }
ok() { echo "ok: $1" >>"$LOG"; }

curl -sf "$HOST/healthz" >/dev/null || fail "host down — start with scripts/start-host.sh"

html="$(curl -sf "$HOST/ambient-link/")"
echo "$html" | grep -q 'id="host-panel"' || fail "missing host panel"
echo "$html" | grep -q 'id="list-scroll"' || fail "missing session list scroll"
echo "$html" | grep -q 'id="new-session-reveal"' || fail "missing new session reveal"
ok "index.html structure"

echo "web smoke OK — $LOG"
