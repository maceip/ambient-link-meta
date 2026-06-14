#!/usr/bin/env bash
# Install ambient link on device. Uses adb install -r so MIUI remembers the signing key.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/relay-android/app/build/outputs/apk/debug/app-debug.apk"
PKG=com.lowkey.ambientlink
PACK="$ROOT/relay-android/app/src/main/assets/soda/lp_cpu.zip"

if [[ ! -f "$PACK" ]]; then
  echo "SODA pack missing — provisioning from ~/neural"
  "$ROOT/scripts/provision-soda-from-neural.sh"
fi

(cd "$ROOT/relay-android" && ./gradlew :app:assembleDebug -q)
"$ROOT/scripts/verify-relay-apk.sh" "$APK"

adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}' || { echo "no device" >&2; exit 1; }

echo "signing cert:" $(apksigner verify --print-certs "$APK" 2>&1 | grep 'SHA-256 digest' | awk '{print $NF}')
adb install -r -g "$APK"

adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set "$PKG" RECORD_AUDIO allow 2>/dev/null || true
adb shell am start -n "$PKG/.MainActivity"
echo "installed $PKG"
