#!/usr/bin/env bash
# POST a HUD reply to the host (delivers into cursor-agent / claude / codex via inject).
set -euo pipefail

HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
THREAD="${AMBIENT_THREAD:-cursor-ambient-link-meta}"
PORT="${BLIND_PORT:-5199}"

json_escape() {
  printf '%s' "$1" | awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if(NR>1) printf "\\n"; printf "%s",$0}'
}

send_one() {
  local text="$1"
  [[ -n "$text" ]] || return 0
  local tj bj
  tj=$(json_escape "$THREAD")
  bj=$(json_escape "$text")
  curl -sf -X POST "$HOST/face-chat/debug/input" \
    -H 'Content-Type: application/json' \
    -d "{\"thread\":\"$tj\",\"text\":\"$bj\",\"enter\":true}"
  [[ "${AMBIENT_SAY:-}" == "1" ]] && say -v Samantha "sent" 2>/dev/null || true
}

if [[ "${1:-}" == "--listen" ]]; then
  [[ "${AMBIENT_SAY:-}" == "1" ]] && say -v Samantha "Blind keyboard ready on port '"$PORT"'." 2>/dev/null || true
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
