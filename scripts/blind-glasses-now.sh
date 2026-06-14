#!/usr/bin/env bash
# Blind / hands-free: push a test card to glasses. No laptop speech unless AMBIENT_SAY=1.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
PROMPT="${1:-Cursor is waiting. Tap dictate on your glasses, speak, then tap send.}"

say_step() {
  [[ "${AMBIENT_SAY:-}" == "1" ]] || return 0
  say -v Samantha "$1" 2>/dev/null || true
}

say_step "Pushing a test card to your glasses. Wake the display with two quick taps using two fingers on the right temple."
# Do not open MainActivity — Meta pairing is one-time; reinstall does not require reconnect.
"$ROOT/scripts/yank-glasses.sh" "$PROMPT" >/dev/null || true
adb shell am broadcast -a com.lowkey.facechat.DEBUG_HUD_YANK \
  --es prompt "$PROMPT" --es thread cursor >/dev/null 2>&1 || true
sleep 3

if adb logcat -d | rg -q 'sendContent SUCCESS|renderPeek|prepareDisplay'; then
  say_step "Card sent. Look at your glasses, not the phone. Swipe with one finger. Tap dictate to speak."
else
  say_step "Glasses not paired yet in face chat. The phone may need one Meta confirm screen. After that, run this script again."
fi
