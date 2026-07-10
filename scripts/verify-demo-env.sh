#!/usr/bin/env bash
# Pre-demo environment gate — run before any glasses loop.
# Exit 0 only when every check that CAN pass locally does pass.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
WEB="${AMBIENT_WEB:-https://relay.public.computer}"
EXPECTED_BUILD="${AMBIENT_EXPECTED_BUILD:-v77}"
FAIL=0

red() { printf '\033[31m✗ %s\033[0m\n' "$1"; FAIL=1; }
grn() { printf '\033[32m✓ %s\033[0m\n' "$1"; }
ylw() { printf '\033[33m! %s\033[0m\n' "$1"; }

echo "== 1. Environment (Mac relay) =="
if curl -sf "${HOST}/healthz" >/dev/null; then
  grn "Mac relay healthz OK (${HOST})"
else
  red "Mac relay down — run: scripts/start-host.sh"
fi

if curl -sf "${HOST}/ambient-link/status" | python3 -c "
import json,sys
d=json.load(sys.stdin)
listen= d.get('listen','')
if listen and '127.0.0.1' in listen and '0.0.0.0' not in listen:
    print('WARN: relay may not be LAN-reachable (127.0.0.1 only)')
" 2>/dev/null; then :; fi

echo
echo "== 2. Environment (phone USB + APK) =="
if adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  grn "Android device connected"
  PKG=com.lowkey.ambientlink
  if adb shell pm path "$PKG" >/dev/null 2>&1; then
    grn "ambient link APK installed ($PKG)"
  else
    red "APK not installed — run: scripts/install-android.sh"
  fi
else
  ylw "No Android device on USB — skip install; plug phone for HUD tests"
fi

echo
echo "== 3. Environment (glasses web / SW) =="
BUILD=$(curl -sf "${WEB}/index.html" 2>/dev/null | sed -n "s/.*var BUILD = '\([^']*\)'.*/\1/p" | head -1 || true)
SW=$(curl -sf "${WEB}/sw.js" 2>/dev/null | sed -n "s/.*CACHE = '\([^']*\)'.*/\1/p" | head -1 || true)
if [[ -n "$BUILD" ]]; then
  grn "Prod web BUILD=${BUILD} SW=${SW:-unknown}"
  if [[ "$BUILD" != "$EXPECTED_BUILD" ]]; then
    ylw "Prod not on expected ${EXPECTED_BUILD} yet — deploy web or force-quit Meta AI after deploy"
  fi
else
  ylw "Could not fetch prod web (offline?) — check ${WEB} manually"
fi

echo
echo "== 4. Meta repo (automated smoke) =="
if bash "$ROOT/scripts/test-protocol.sh" >/dev/null 2>&1; then
  grn "test-protocol.sh (yank + inject)"
else
  red "test-protocol.sh failed"
fi

echo
echo "== 5. Core inject diagnostics =="
bash "$ROOT/scripts/diagnose-inject.sh" || true

echo
echo "== 6. Device gates (when adb connected) =="
if adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  if bash "$ROOT/scripts/verify-android-device.sh" >/dev/null 2>&1; then
    grn "verify-android-device.sh (theme + snooze)"
  else
    red "verify-android-device.sh failed"
  fi
  if node "$ROOT/scripts/verify-inject-landed.mjs" >/dev/null 2>&1; then
    grn "verify-inject-landed.mjs (delivered gate)"
  else
    ylw "verify-inject-landed.mjs — delivered or endpoint missing"
  fi
fi

echo
if [[ "$FAIL" -ne 0 ]]; then
  echo "Fix red items above before demo."
  exit 1
fi
echo "Environment checks passed (yellow items may still need device/Meta AI)."
