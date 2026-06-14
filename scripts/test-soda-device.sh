#!/usr/bin/env bash
# On-device SODA smoke — requires adb + debug APK with provisioned assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/relay-android/app/build/outputs/apk/debug/app-debug.apk"

adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}' || { echo "no adb device" >&2; exit 1; }

if [[ ! -f "$ROOT/relay-android/app/libs/recovered-soda.jar" ]]; then
  "$ROOT/scripts/provision-soda-from-neural.sh"
fi

(cd "$ROOT/relay-android" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q)
"$ROOT/scripts/verify-relay-apk.sh" "$APK"

adb install -r -g "$APK" >/dev/null
TEST_APK="$ROOT/relay-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb install -r -g -t "$TEST_APK" >/dev/null
adb shell pm grant com.lowkey.ambientlink android.permission.RECORD_AUDIO 2>/dev/null || true

adb shell am instrument -w -r -e class com.lowkey.ambientlink.soda.SodaSmokeTest \
  com.lowkey.ambientlink.test/androidx.test.runner.AndroidJUnitRunner
