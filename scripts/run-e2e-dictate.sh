#!/usr/bin/env bash
# Interactive dictate e2e — deployed web app + phone daemon (adb).
# Speak during the test window; Playwright captures partials + screenshots.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="${AMBIENT_LOCAL_RELAY:-http://127.0.0.1:5181}"
REMOTE="${AMBIENT_RELAY_HOST:-https://public.computer}"

export AMBIENT_LOCAL_RELAY="$LOCAL"
export AMBIENT_WEB_ORIGIN="${AMBIENT_WEB_ORIGIN:-https://public.computer}"
export AMBIENT_WEB_PATH="${AMBIENT_WEB_PATH:-/ambient-link/}"
export AMBIENT_RELAY_HOST="$REMOTE"
export AMBIENT_DICTATE_WAIT_MS="${AMBIENT_DICTATE_WAIT_MS:-90000}"

curl -sf "${LOCAL}/healthz" >/dev/null || {
  echo "Laptop relay down at $LOCAL — run: bash scripts/start-host.sh" >&2
  exit 1
}

PEER=$(curl -sf "${REMOTE}/ambient-link/status" | python3 -c "import json,sys; print(json.load(sys.stdin).get('cloud_peer', False))" 2>/dev/null || echo False)
if [[ "$PEER" != "True" ]]; then
  echo "cloud_peer not connected on $REMOTE" >&2
  exit 1
fi

if ! adb get-state 2>/dev/null | grep -qx device; then
  echo "No adb device — plug in the phone and enable USB debugging." >&2
  exit 1
fi

echo "[dictate e2e] building + installing APK (Bluetooth SCO default on)…"
(cd "$ROOT/relay-android" && ./gradlew :app:installDebug -q)

echo "[dictate e2e] waking phone daemon…"
adb shell am start -n com.lowkey.ambientlink/.MainActivity >/dev/null
sleep 2

cd "$ROOT/web"
HEADED="${AMBIENT_DICTATE_HEADED:-1}"
if [[ "$HEADED" == "1" ]]; then
  npx playwright test dictate.spec.mjs --headed
else
  npm run test:e2e -- dictate.spec.mjs
fi

OUT="$ROOT/web/test/output"
for f in dictate-01-session-open.png dictate-02-listening.png dictate-03-result.png; do
  [[ -s "$OUT/$f" ]] || { echo "FAIL: missing $OUT/$f" >&2; exit 1; }
done
echo "OK — dictate e2e artifacts in $OUT/"
ls -la "$OUT"/dictate-*.png "$OUT"/dictate-logcat.txt 2>/dev/null || true
