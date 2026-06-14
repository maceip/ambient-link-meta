#!/usr/bin/env bash
# Push an assistant turn into ambient-link (virtual ingest) so glasses get thread_idle.
set -euo pipefail
HOST="${AMBIENT_LINK_HOST:-http://127.0.0.1:5181}"
SESSION="${AMBIENT_LINK_SESSION:-${AMBIENT_THREAD:-cursor-ambient-link-meta}}"
AGENT="${AMBIENT_LINK_AGENT:-cursor}"
CWD="${AMBIENT_LINK_CWD:-$(pwd)}"
MSG="${1:-}"

if [[ -z "$MSG" ]]; then
  echo "usage: push-chat.sh \"assistant message text\"" >&2
  exit 1
fi

export SESSION AGENT CWD MSG

curl -sf -X POST "$HOST/ambient-link/ingest" -H 'Content-Type: application/json' -d "$(python3 -c '
import json, os
print(json.dumps({
  "source": "virtual",
  "session_id": os.environ["SESSION"],
  "agent": os.environ["AGENT"],
  "cwd": os.environ["CWD"],
  "event_type": "session_start",
}))
')" || true

curl -sf -X POST "$HOST/ambient-link/ingest" -H 'Content-Type: application/json' -d "$(python3 -c '
import json, os
print(json.dumps({
  "source": "virtual",
  "session_id": os.environ["SESSION"],
  "agent": os.environ["AGENT"],
  "cwd": os.environ["CWD"],
  "event_type": "assistant_message",
  "payload": {"message": os.environ["MSG"]},
}))
')"
echo "pushed — peek should land on glasses in ~2s"
