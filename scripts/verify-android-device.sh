#!/usr/bin/env bash
# B-007 / B-103 device verification gates (requires USB adb + debug build).
set -euo pipefail
PKG=com.lowkey.ambientlink
ACTIVITY=${PKG}/.MainActivity

adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}' || {
  echo "FAIL: no adb device"
  exit 2
}

echo "== install =="
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/relay-android" && ./gradlew installDebug -q)
VER=$(adb shell dumpsys package "$PKG" | awk -F= '/versionName/{print $2; exit}')
echo "installed versionName=$VER"

echo "== launch + theme pills =="
adb shell am start -S -n "$ACTIVITY" >/dev/null
sleep 2
adb shell uiautomator dump /sdcard/verify-ui.xml >/dev/null 2>&1 || true
adb pull /sdcard/verify-ui.xml /tmp/verify-ui.xml >/dev/null 2>&1 || true
for pill in Meta Dracula Tokyo Catppuccin Nord; do
  if ! grep -q "text=\"$pill\"" /tmp/verify-ui.xml 2>/dev/null; then
    echo "FAIL B-007 theme: missing pill '$pill' in UI dump"
    exit 2
  fi
done
echo "PASS B-007 theme pills present"

echo "== snooze toggle (tap 15 min twice) =="
# Scroll to Snooze section
adb shell input swipe 540 1800 540 600 350
sleep 1
adb shell uiautomator dump /sdcard/verify-ui2.xml >/dev/null 2>&1 || true
adb pull /sdcard/verify-ui2.xml /tmp/verify-ui2.xml >/dev/null 2>&1 || true
COORD=$(python3 <<'PY'
import re, xml.etree.ElementTree as ET
try:
  root = ET.parse('/tmp/verify-ui2.xml').getroot()
except Exception:
  print(''); raise SystemExit(0)
for n in root.iter('node'):
  t = (n.get('text') or '').strip()
  if t in ('15m', '15 min', '15 min.'):
    b = n.get('bounds','')
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    if m:
      x = (int(m.group(1))+int(m.group(3)))//2
      y = (int(m.group(2))+int(m.group(4)))//2
      print(f'{x} {y}')
      break
PY
)
if [[ -z "${COORD:-}" ]]; then
  echo "FAIL B-103 snooze: could not find 15 min pill (scroll/UI)"
  exit 2
fi
read -r SX SY <<<"$COORD"
adb shell input tap "$SX" "$SY"
sleep 0.5
UNTIL1=$(adb shell run-as "$PKG" cat shared_prefs/ambient-link-meta.xml 2>/dev/null | sed -n 's/.*snooze_until_ms" value="\([^"]*\)".*/\1/p' | head -1)
if [[ -z "$UNTIL1" || "$UNTIL1" == "0" ]]; then
  echo "FAIL B-103 snooze: first tap did not activate snooze (until_ms=$UNTIL1)"
  exit 2
fi
echo "PASS B-103 snooze activated until_ms=$UNTIL1"
adb shell input tap "$SX" "$SY"
sleep 0.5
UNTIL2=$(adb shell run-as "$PKG" cat shared_prefs/ambient-link-meta.xml 2>/dev/null | sed -n 's/.*snooze_until_ms" value="\([^"]*\)".*/\1/p' | head -1)
if [[ -n "$UNTIL2" && "$UNTIL2" != "0" ]]; then
  echo "FAIL B-103 snooze: second tap did not clear snooze (until_ms=$UNTIL2)"
  exit 2
fi
echo "PASS B-103 snooze toggle-off (until_ms=0)"

echo "OK verify-android-device"
