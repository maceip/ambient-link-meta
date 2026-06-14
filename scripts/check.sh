#!/usr/bin/env bash
# One-shot integration gate — logs detail to /tmp/ambient-check.log, prints summary only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG=/tmp/ambient-check.log
: >"$LOG"

fail() { echo "FAIL: $1" >>"$LOG"; echo "FAIL: $1" >&2; exit 1; }
ok() { echo "$1" >>"$LOG"; }

{
  echo "=== $(date) ==="
  curl -sf http://127.0.0.1:5181/healthz >/dev/null || fail "host healthz"
  ok "host up"
  [[ "$(lsof -iTCP:5181 -sTCP:LISTEN -n -P 2>/dev/null | awk 'NR>1' | wc -l | tr -d ' ')" == "1" ]] || fail "expected 1 listener on 5181"
  ok "single host listener"

  STATUS="$(curl -sf http://127.0.0.1:5181/face-chat/status)"
  JHEAD="$(echo "$STATUS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("journal",0))')"
  [[ -n "$JHEAD" ]] || fail "status missing journal head"
  ok "journal head=$JHEAD"

  RELAY_DEBUG="$(echo "$STATUS" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("relay_debug", False))')"
  ok "relay_debug=$RELAY_DEBUG"

  bash "$ROOT/scripts/verify-relay-apk.sh" "$ROOT/relay-android/app/build/outputs/apk/debug/app-debug.apk" || fail "APK missing SODA"
  ok "APK SODA ok"

  if adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
    adb shell am start -n com.lowkey.facechat/.MainActivity >/dev/null 2>&1 || true
    sleep 2
    PID="$(adb shell pidof com.lowkey.facechat 2>/dev/null | tr -d '\r')"
    adb logcat -c >/dev/null 2>&1 || true
    curl -sf -X POST http://127.0.0.1:5181/face-chat/debug/yank \
      -H 'Content-Type: application/json' \
      -d '{"thread":"check-yank","label":"check","agent":"cursor","awaiting":"done","lastAssistant":"check.sh yank — tap dismiss on glasses"}' >/dev/null
    sleep 3
    if ! adb logcat -d ${PID:+--pid=$PID} 2>/dev/null | grep -q "RelayClient:"; then
      fail "phone relay not logging (open face·chat, check relay URL)"
    fi
    ok "phone relay logcat"
  else
    ok "phone skip (no adb)"
  fi

  bash "$ROOT/scripts/test-agents.sh" >>"$LOG" 2>&1 || fail "agents delivery test"
  ok "agents delivery"

  bash "$ROOT/scripts/test-web.sh" >>"$LOG" 2>&1 || fail "web test"
  ok "web test"
} >>"$LOG" 2>&1

echo "check OK — detail: $LOG"
