#!/usr/bin/env bash
# Pop a question on glasses HUD (needs face·chat relay on phone) + speak instructions.
set -euo pipefail
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
MSG="${1:-What would you like me to do? Tap dictate on your glasses and speak.}"

json_escape() {
  printf '%s' "$1" | awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if(NR>1) printf "\\n"; printf "%s",$0}'
}
mj=$(json_escape "$MSG")

curl -sf -X POST "$HOST/face-chat/debug/yank" \
  -H 'Content-Type: application/json' \
  -d "{\"thread\":\"cursor-5df30d48c2\",\"awaiting\":\"question\",\"lastAssistant\":\"$mj\"}" \
  >/dev/null

say -v Samantha "Check your glasses. Tap dictate, speak, then tap send." 2>/dev/null || true
