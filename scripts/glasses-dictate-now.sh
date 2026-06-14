#!/usr/bin/env bash
# Hands-free glasses dictate — grant perms, install, pop test card on HUD.
set -euo pipefail

PKG=com.lowkey.facechat
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

log() { printf '==> %s\n' "$*"; }

if ! adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
  echo "no adb device" >&2
  exit 1
fi

log "granting mic + notification (no phone tap needed)"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

log "building + installing debug APK"
(cd "$ROOT/relay-android" && ./gradlew :app:assembleDebug -q)
if ! adb install -r "$ROOT/relay-android/app/build/outputs/apk/debug/app-debug.apk"; then
  echo "Install blocked — on phone approve USB install, then re-run." >&2
  exit 1
fi

log "pushing question to glasses HUD (daemon must already be running)"
PROMPT="${1:?usage: $0 \"exact question text\"}"
adb shell am broadcast -a com.lowkey.facechat.DEBUG_HUD_YANK \
  --es prompt "$PROMPT" \
  --es thread cursor \
  -p "$PKG"

echo
echo "ON YOUR GLASSES:"
echo "  1. Card shows dictate | dismiss (not yes)"
echo "  2. Tap dictate, speak, tap send when done"
echo
echo "Phone stays in pocket. Host must be running at ws://YOUR_LAN_IP:5181/face-chat/ws"
