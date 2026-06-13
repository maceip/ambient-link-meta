#!/usr/bin/env bash
# Pop a question card on glasses via the running host (curl from Mac keyboard — no screen needed).
set -euo pipefail

HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
PROMPT="${1:-Tap dictate and speak your reply. Tap send on glasses when done.}"

curl -sf -X POST "$HOST/face-chat/debug/yank" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"thread":"cursor","awaiting":"question","lastAssistant":%s}' \
    "$(printf '%s' "$PROMPT" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')")"
echo
echo "Yank sent — check glasses for yes | dictate"
