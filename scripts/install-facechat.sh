#!/usr/bin/env bash
# Install face·chat on MIUI/Xiaomi — auto-taps the "Install via USB" dialog.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/relay-android/app/build/outputs/apk/debug/app-debug.apk"
PKG=com.lowkey.facechat
REMOTE=/data/local/tmp/facechat.apk

if [[ ! -f "$APK" ]]; then
  (cd "$ROOT/relay-android" && ./gradlew :app:assembleDebug -q)
fi

adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}' || { echo "no device" >&2; exit 1; }

adb push "$APK" "$REMOTE" >/dev/null

# MIUI shows AdbInstallActivity — tap "Remember" + "Install" while pm install runs.
adb shell pm install -r -g -t "$REMOTE" >/tmp/facechat-install.log 2>&1 &
pid=$!
for _ in $(seq 1 12); do
  sleep 0.35
  adb shell input tap 640 2375 2>/dev/null || true  # Remember my choice
  adb shell input tap 375 2525 2>/dev/null || true  # Install
done
wait "$pid" || true
cat /tmp/facechat-install.log

if ! adb shell pm path "$PKG" >/dev/null 2>&1; then
  echo "install failed — enable Developer options → Install via USB on phone" >&2
  exit 1
fi

adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
echo "installed $PKG"
