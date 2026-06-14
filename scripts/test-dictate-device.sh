#!/usr/bin/env bash
# On-device SODA dictate test with real speech audio (not silence smoke).
#
# Audio: 16 kHz mono 16-bit PCM WAV from scripts/generate-dictate-fixture.sh
# (macOS `say` + `afconvert`), same fixtures as ~/neural gatekeeper tests.
#
# Requires: adb, debug APK installed, device allows app install from USB.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID="$ROOT/relay-android"
FIXTURE_DIR="$ROOT/scripts/test-fixtures/audio"
SLUG="${SLUG:-define_polymorphism}"
EXPECT="${EXPECT:-polymorphism}"
PHRASE="${PHRASE:-Define polymorphism.}"
PKG="com.lowkey.ambientlink"
FIXTURE_NAME="${SLUG}.wav"
DEVICE_PRIVATE_PATH="/data/data/$PKG/files/ambient-link-test/$FIXTURE_NAME"
WAV="$FIXTURE_DIR/${SLUG}.wav"
NEURAL_WAV="${NEURAL_ROOT:-$HOME/neural}/scripts/test-fixtures/audio/${SLUG}.wav"

log() { printf '==> %s\n' "$*"; }

if [[ ! -f "$WAV" ]]; then
  if [[ -f "$NEURAL_WAV" ]]; then
    log "using neural fixture $NEURAL_WAV"
    mkdir -p "$FIXTURE_DIR"
    cp "$NEURAL_WAV" "$WAV"
  else
    log "generating fixture ($PHRASE)"
    "$ROOT/scripts/generate-dictate-fixture.sh" "$FIXTURE_DIR" "$SLUG" "$PHRASE"
  fi
fi

if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "no adb device in 'device' state" >&2
  exit 1
fi

log "building debug APK"
(cd "$ANDROID" && ./gradlew :app:assembleDebug -q)
APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"

if [[ "${SKIP_INSTALL:-0}" != "1" ]]; then
  log "installing $APK (approve the prompt on MIUI/Xiaomi if shown)"
  if ! adb install -r "$APK"; then
    echo >&2
    echo "Install failed. On Xiaomi/MIUI: Settings → Additional settings → Developer options →" >&2
    echo "  enable 'USB debugging (Security settings)' and 'Install via USB', then re-run." >&2
    echo "Or install manually: adb install -r $APK" >&2
    echo "Then re-run with SKIP_INSTALL=1 $0" >&2
    exit 1
  fi
else
  log "SKIP_INSTALL=1 — assuming APK already on device"
  if ! adb shell pm path "$PKG" >/dev/null 2>&1; then
    echo "$PKG not installed; remove SKIP_INSTALL or install APK first" >&2
    exit 1
  fi
fi

log "pushing $WAV -> app-private $DEVICE_PRIVATE_PATH"
TMP="/data/local/tmp/ambient-link-$FIXTURE_NAME"
adb push "$WAV" "$TMP"
adb shell run-as "$PKG" mkdir -p files/ambient-link-test
adb shell "cat '$TMP' | run-as '$PKG' sh -c 'cat > files/ambient-link-test/$FIXTURE_NAME'"
adb shell rm -f "$TMP" 2>/dev/null || true

log "starting app process (wake receiver)"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2

log "injecting fixture via DEBUG_SODA_FIXTURE (expect substring: $EXPECT)"
adb logcat -c
adb shell am broadcast -a com.lowkey.ambientlink.DEBUG_SODA_FIXTURE \
  --es path "$DEVICE_PRIVATE_PATH" \
  --es expect "$EXPECT" \
  -p "$PKG"

log "waiting for SODA transcript (up to 25s)"
deadline=$((SECONDS + 25))
passed=0
while (( SECONDS < deadline )); do
  if adb logcat -d -s DictateDebug | grep -q 'audio_fixture_pass:'; then
    passed=1
    break
  fi
  if adb logcat -d -s DictateDebug | grep -q 'audio_fixture_fail:'; then
    passed=0
    break
  fi
  sleep 2
done

echo
echo "--- DictateDebug log ---"
adb logcat -d -s DictateDebug | tail -40
echo "------------------------"

if (( passed )); then
  log "PASS: SODA transcribed fixture containing '$EXPECT'"
  exit 0
fi

echo "FAIL: no passing transcript for '$EXPECT' in fixture '$SLUG'" >&2
exit 1
