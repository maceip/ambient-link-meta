#!/usr/bin/env bash
# Push a real agent question to glasses HUD. Message is required — no fake defaults.
set -euo pipefail
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
THREAD="${AMBIENT_THREAD:-cursor-ambient-link-meta}"

if [[ $# -lt 1 ]] || [[ -z "${1:-}" ]]; then
  echo "usage: $0 \"exact question text from the agent\"" >&2
  exit 1
fi
MSG="$1"

json_escape() {
  printf '%s' "$1" | awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); if(NR>1) printf "\\n"; printf "%s",$0}'
}
mj=$(json_escape "$MSG")

curl -sf -X POST "$HOST/face-chat/debug/yank" \
  -H 'Content-Type: application/json' \
  -d "{\"thread\":\"$THREAD\",\"label\":\"cursor\",\"agent\":\"cursor\",\"awaiting\":\"question\",\"lastAssistant\":\"$mj\"}" \
  >/dev/null
echo "sent — glasses should show: dictate | dismiss"
