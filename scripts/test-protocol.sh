#!/usr/bin/env bash
# Host ↔ phone wire checks without glasses. Run while ambient link relay is connected.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"

echo "== health =="
curl -sf "$HOST/healthz" >/dev/null

echo "== status =="
curl -sf "$HOST/ambient-link/status" | head -c 500
echo

MSG="protocol-test $(date +%H:%M:%S)"
THREAD="$(curl -sf "$HOST/ambient-link/status" | python3 -c "
import json,sys
d=json.load(sys.stdin)
s=(d.get('sessions') or [{}])[0]
print(s.get('thread_id') or 'protocol-test')
")"

echo "== yank (explicit) =="
curl -sf -X POST "$HOST/ambient-link/debug/yank" \
  -H 'Content-Type: application/json' \
  -d "{\"thread\":\"$THREAD\",\"label\":\"protocol\",\"agent\":\"cursor\",\"awaiting\":\"done\",\"lastAssistant\":\"$MSG\"}"

echo
echo "== input (Go relay inject) =="
curl -sf -X POST "$HOST/ambient-link/debug/input" \
  -H 'Content-Type: application/json' \
  -d "{\"thread\":\"$THREAD\",\"text\":\"protocol-reply-$MSG\",\"enter\":true}"
echo

if adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
  echo "== phone log (last 30s) =="
  adb logcat -d -t 200 | grep -E "RelayClient|HudPresenter|hud_yank|RelayService" | tail -20 || echo "(no matching log lines — open ambient link on phone)"
fi

echo "OK protocol"
