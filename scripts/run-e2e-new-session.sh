#!/usr/bin/env bash
# E2E on DEPLOYED web app (public.computer) + laptop sessions via relay-bridge.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="${AMBIENT_LOCAL_RELAY:-http://127.0.0.1:5181}"
REMOTE="${AMBIENT_RELAY_HOST:-https://public.computer}"
CORE="${AMBIENT_LINK_CORE:-$HOME/ambient-link-core}"
E2E_CWD="${AMBIENT_TEST_CWD:-$ROOT/web/test/e2e-workspace}"
mkdir -p "$E2E_CWD"

export AMBIENT_TEST_CWD="$E2E_CWD"
export AMBIENT_TEST_THREAD="${AMBIENT_TEST_THREAD:-$(python3 - <<PY
import hashlib, os
agent = "cursor"
cwd = os.path.realpath(os.environ["AMBIENT_TEST_CWD"])
print(f"cursor-{hashlib.sha256(f'{agent}::{cwd}'.encode()).hexdigest()[:10]}")
PY
)}"

HOST_BIN="${AMBIENT_LINK_HOST:-/tmp/ambient-link-host}"

curl -sf "${LOCAL}/healthz" >/dev/null || {
  echo "Laptop relay down at $LOCAL — run: bash scripts/start-host.sh" >&2
  exit 1
}

PEER=$(curl -sf "${REMOTE}/ambient-link/status" | python3 -c "import json,sys; print(json.load(sys.stdin).get('cloud_peer', False))" 2>/dev/null || echo False)
if [[ "$PEER" != "True" ]]; then
  echo "cloud_peer not connected on $REMOTE — check AMBIENT_LINK_CLOUD on laptop host" >&2
  exit 1
fi

export AMBIENT_LOCAL_RELAY="$LOCAL"
export AMBIENT_WEB_ORIGIN="${AMBIENT_WEB_ORIGIN:-https://public.computer}"
export AMBIENT_WEB_PATH="${AMBIENT_WEB_PATH:-/ambient-link/}"
export AMBIENT_RELAY_HOST="$REMOTE"
export AMBIENT_TEST_RUN_ID="${AMBIENT_TEST_RUN_ID:-e2e$(date +%s | tail -c 8)}"

BRIDGE_PID=""
RESPONDER_PID=""
BOOT_PID=""
cleanup() {
  [[ -n "$BRIDGE_PID" ]] && kill "$BRIDGE_PID" 2>/dev/null || true
  [[ -n "$RESPONDER_PID" ]] && kill "$RESPONDER_PID" 2>/dev/null || true
  [[ -n "$BOOT_PID" ]] && kill "$BOOT_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "[e2e] isolated cwd=$AMBIENT_TEST_CWD thread=$AMBIENT_TEST_THREAD"
if [[ ! -x "$HOST_BIN" ]]; then
  echo "host binary missing at $HOST_BIN — run: bash scripts/start-host.sh" >&2
  exit 1
fi
echo "[e2e] bootstrapping PTY agent in e2e workspace…"
( cd "$AMBIENT_TEST_CWD" && exec "$HOST_BIN" run ./e2e-cursor-agent ) &
BOOT_PID=$!
sleep 3
curl -sf -X POST "${LOCAL}/ambient-link/ingest" \
  -H 'Content-Type: application/json' \
  -d "$(python3 - <<PY
import json, os, time
print(json.dumps({
  'source': 'virtual',
  'session_id': f"e2e-playwright-{os.environ['AMBIENT_TEST_RUN_ID']}",
  'agent': 'cursor',
  'cwd': os.environ['AMBIENT_TEST_CWD'],
  'event_type': 'session_start',
  'payload': {},
  'observed_at': int(time.time() * 1000),
}))
PY
)" >/dev/null
BOOT_OK=0
for i in $(seq 1 20); do
  if curl -sf "${LOCAL}/ambient-link/status" | AMBIENT_TEST_THREAD="$AMBIENT_TEST_THREAD" python3 -c "
import json,sys,os
tid=os.environ.get('AMBIENT_TEST_THREAD','')
data=json.load(sys.stdin)
live=[s for s in data.get('sessions',[]) if s.get('state')!='DEAD' and s.get('thread_id')==tid]
sys.exit(0 if live else 1)
" 2>/dev/null; then
    echo "[e2e] e2e session live on laptop"
    BOOT_OK=1
    break
  fi
  sleep 1
done
if [[ "$BOOT_OK" != "1" ]]; then
  echo "FAIL: e2e session $AMBIENT_TEST_THREAD never appeared — check $HOST_BIN run agent" >&2
  exit 1
fi

if [[ -f "$CORE/tools/relay-bridge.mjs" ]]; then
  LOCAL_HTTP="$LOCAL" REMOTE="$REMOTE" POLL_MS=4000 node "$CORE/tools/relay-bridge.mjs" &
  BRIDGE_PID=$!
  echo "[e2e] relay-bridge pid=$BRIDGE_PID"
fi

node "$ROOT/scripts/e2e-agent-responder.mjs" &
RESPONDER_PID=$!
echo "[e2e] agent-responder pid=$RESPONDER_PID RUN_ID=$AMBIENT_TEST_RUN_ID"

cd "$ROOT/web"
npm run test:e2e:integrated

OUT="$ROOT/web/test/output"
for f in integrated-01-thread-open.png integrated-02-first-turn.png integrated-03-second-turn.png; do
  [[ -s "$OUT/$f" ]] || { echo "FAIL: missing $OUT/$f" >&2; exit 1; }
done
echo "OK — integrated e2e screenshots in $OUT/"
ls -la "$OUT"/*.png
