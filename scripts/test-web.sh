#!/usr/bin/env bash
# Web companion tests: unit (chipset/static) + host protocol integration + smoke curl.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
WS="${AMBIENT_WS:-ws://127.0.0.1:5181/face-chat/ws}"
LOG=/tmp/ambient-web-test.log
: >"$LOG"

fail() { echo "FAIL: $1" | tee -a "$LOG" >&2; exit 1; }
ok() { echo "ok: $1" >>"$LOG"; }

export AMBIENT_HOST="$HOST" AMBIENT_WS="$WS"

curl -sf "$HOST/healthz" >/dev/null || fail "host down — start with scripts/start-host.sh"

echo "== node unit + protocol tests ==" >>"$LOG"
(cd "$ROOT/web" && node --test --test-timeout=20000 --test-concurrency=1 test/*.test.mjs) >>"$LOG" 2>&1 || fail "node web tests"

ok "node test suite"

html="$(curl -sf "$HOST/")"
echo "$html" | grep -q 'id="host-panel"' || fail "missing host panel"
echo "$html" | grep -q 'id="btn-pull"' || fail "missing pull card button"
ok "index.html structure"

echo "web OK — $LOG"
