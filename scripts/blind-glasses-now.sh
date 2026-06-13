#!/usr/bin/env bash
# Blind / hands-free: wake relay, pair glasses flow, push a test card, speak status.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
PROMPT="${1:-Cursor is waiting. Tap dictate on your glasses, speak, then tap send.}"

say_step() { say -v Samantha "$1" 2>/dev/null || true; }

say_step "Starting face chat on your phone. One-time Meta pairing may open on the phone. If you have sighted help, confirm it on the phone. Otherwise wait thirty seconds."
adb shell am start -n com.lowkey.facechat/.MainActivity >/dev/null 2>&1 || true
sleep 8

say_step "Pushing a test card to your glasses now. Wake the display with two quick taps using two fingers on the right temple strip."
"$ROOT/scripts/yank-glasses.sh" "$PROMPT" >/dev/null || true
adb shell am broadcast -a com.lowkey.facechat.DEBUG_HUD_YANK \
  --es prompt "$PROMPT" --es thread cursor >/dev/null 2>&1 || true
sleep 3

if adb logcat -d | rg -q 'sendContent SUCCESS|renderPeek|prepareDisplay'; then
  say_step "Card sent. Look at your glasses, not the phone. Swipe with one finger. Tap dictate to speak."
else
  say_step "Glasses not paired yet in face chat. The phone may need one Meta confirm screen. After that, run this script again. Meanwhile, type on your Bluetooth keyboard and I will hear you in Cursor."
fi
