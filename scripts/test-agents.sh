#!/usr/bin/env bash
# Verify Go relay delivery into a live terminal session (no shell-script inject path).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
LOG="${AMBIENT_LINK_LOG:-$HOME/Library/Logs/ambient-link-host.log}"
MARKER="agent-delivery-$(date +%s)"

fail() { echo "FAIL: $1" >&2; exit 1; }

curl -sf "$HOST/healthz" >/dev/null || fail "host down"

read -r THREAD SESSION <<<"$(curl -sf "$HOST/ambient-link/status" | python3 -c "
import json,sys
d=json.load(sys.stdin)
delivery = {e['SessionID']: e for e in d.get('delivery') or []}
for s in d.get('sessions') or []:
    sid = s.get('session_id','')
    if sid in delivery and s.get('state') != 'DEAD':
        print(s.get('thread_id',''), sid)
        break
")"
[[ -n "$THREAD" && -n "$SESSION" ]] || fail "no live session with delivery endpoint (start claude/codex/cursor-agent in a terminal)"

curl -sf -X POST "$HOST/ambient-link/debug/input" \
  -H 'Content-Type: application/json' \
  -d "{\"thread\":\"$THREAD\",\"text\":\"$MARKER\",\"enter\":true}" >/dev/null

sleep 1
if [[ -f "$HOME/.ambient-link/outbox/${SESSION}.pending.json" ]]; then
  fail "delivery pending for $SESSION (see $LOG)"
fi

if [[ -f "$LOG" ]] && ! grep -q "delivery:" "$LOG" 2>/dev/null; then
  fail "no delivery log lines in $LOG"
fi

echo "agents OK — thread=$THREAD session=$SESSION"
