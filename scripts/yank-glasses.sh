#!/usr/bin/env bash
# Pop a question card on glasses via the running host. Message is required.
set -euo pipefail

HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
THREAD="${AMBIENT_THREAD:-cursor-ambient-link-meta}"

if [[ $# -lt 1 ]] || [[ -z "${1:-}" ]]; then
  echo "usage: $0 \"exact question text\"" >&2
  exit 1
fi
PROMPT="$1"

curl -sf -X POST "$HOST/face-chat/debug/yank" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"thread":"%s","label":"cursor","agent":"cursor","awaiting":"question","lastAssistant":%s}' \
    "$THREAD" \
    "$(printf '%s' "$PROMPT" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')")"
echo
echo "sent — glasses should show: dictate | dismiss"
