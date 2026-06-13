#!/usr/bin/env bash
# Send text to Cursor without seeing the screen (Bluetooth keyboard on Mac).
set -euo pipefail

HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
THREAD="${AMBIENT_THREAD:-cursor-5df30d48c2}"
PORT="${BLIND_PORT:-5199}"

json_escape() {
  printf '%s' "$1" | awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if(NR>1) printf "\\n"; printf "%s",$0}'
}

send_one() {
  local text="$1"
  [[ -n "$text" ]] || return 0
  printf '%s' "$text" | pbcopy
  local tj bj
  tj=$(json_escape "$THREAD")
  bj=$(json_escape "$text")
  curl -sf -X POST "$HOST/face-chat/debug/input" \
    -H 'Content-Type: application/json' \
    -d "{\"thread\":\"$tj\",\"text\":\"$bj\",\"enter\":true}" \
    >/dev/null 2>&1 || true
  osascript >/dev/null 2>&1 <<'APPLESCRIPT' || true
tell application "Cursor" to activate
delay 0.35
tell application "System Events"
  keystroke "v" using command down
  delay 0.1
  key code 36
end tell
APPLESCRIPT
  say -v Samantha "sent" 2>/dev/null || true
}

if [[ "${1:-}" == "--listen" ]]; then
  say -v Samantha "Blind keyboard ready on port '"$PORT"'. Open terminal and type netcat localhost "$PORT" then your message." 2>/dev/null || true
  while true; do
    line="$(nc -l "$PORT" 2>/dev/null | tr -d '\r\n')"
    [[ -n "$line" ]] && send_one "$line"
  done
fi

if [[ $# -gt 0 ]]; then
  send_one "$*"
else
  IFS= read -r line || exit 0
  send_one "$line"
fi
