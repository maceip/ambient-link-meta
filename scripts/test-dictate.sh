#!/usr/bin/env bash
# Run dictate tests (Go only — no Python).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST_DIR="$ROOT/../ambient-link-core/host"
HOST_BIN="${AMBIENT_LINK_HOST_BIN:-/tmp/ambient-link-host}"

echo "== unit: dictate package =="
(cd "$HOST_DIR" && go test ./internal/dictate/... -count=1 -v)

echo "== integration: WS dictate fanout =="
(cd "$HOST_DIR" && go test ./internal/sink/... -run TestWebSocketDictateFanout -count=1 -v)

if [[ -x "$HOST_BIN" ]] || (cd "$HOST_DIR" && go build -o "$HOST_BIN" ./cmd/host); then
  echo "== host binary builds =="
fi

if adb devices 2>/dev/null | grep -qE '\tdevice$'; then
  echo "== device: SODA smoke (install main APK if needed) =="
  "$ROOT/scripts/provision-soda-from-neural.sh"
  (cd "$ROOT/relay-android" && ./gradlew :app:installDebug -q)
  if adb install -r -g "$ROOT/relay-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" 2>/dev/null; then
    adb shell am instrument -w -e class com.lowkey.facechat.soda.SodaSmokeTest \
      com.lowkey.facechat.test/androidx.test.runner.AndroidJUnitRunner
  else
    echo "skip instrumented test (approve test APK install on phone)"
    unzip -l "$ROOT/relay-android/app/build/outputs/apk/debug/app-debug.apk" | grep -q libsoda_dev_jni && \
      echo "APK contains libsoda_dev_jni.so ✓"
  fi
else
  echo "skip device tests — no adb device"
fi

echo "OK"
