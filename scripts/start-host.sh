#!/usr/bin/env bash
# Start ambient-link-host and keep it alive via launchd (macOS) or nohup fallback.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${AMBIENT_LINK_CORE:-$HOME/ambient-link-core}"
BIN="${AMBIENT_LINK_HOST:-/tmp/ambient-link-host}"
LISTEN="${AMBIENT_LINK_LISTEN:-0.0.0.0:5181}"
PORT="${LISTEN##*:}"
LOG="${AMBIENT_LINK_LOG:-$HOME/Library/Logs/ambient-link-host.log}"
LABEL="com.maceip.ambient-link"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"

if [[ ! -x "$BIN" ]]; then
  echo "building host → $BIN"
  (cd "$CORE/host" && go build -o "$BIN" ./cmd/host)
fi

mkdir -p "$(dirname "$LOG")" "$HOME/Library/LaunchAgents"

RELAY_DEBUG_XML=""
if [[ "${AMBIENT_RELAY_DEBUG:-}" == "1" ]]; then
  RELAY_DEBUG_XML='    <string>-relay-debug</string>'
fi

cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${BIN}</string>
    <string>serve</string>
    <string>-listen</string>
    <string>${LISTEN}</string>
    <string>-web-root</string>
    <string>${ROOT}/web</string>
    <string>-log</string>
    <string>info</string>
${RELAY_DEBUG_XML}
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>${LOG}</string>
  <key>StandardErrorPath</key><string>${LOG}</string>
</dict>
</plist>
EOF

UID_NUM="$(id -u)"
launchctl bootout "gui/${UID_NUM}" "$PLIST" 2>/dev/null || launchctl unload "$PLIST" 2>/dev/null || true
pkill -f 'ambient-link-host serve' 2>/dev/null || true
sleep 0.5
if launchctl bootstrap "gui/${UID_NUM}" "$PLIST" 2>/dev/null || launchctl load "$PLIST"; then
  :
else
  echo "launchctl failed — falling back to nohup" >&2
  nohup "$BIN" serve -listen "$LISTEN" -web-root "$ROOT/web" -log info ${AMBIENT_RELAY_DEBUG:+ -relay-debug} >>"$LOG" 2>&1 &
fi

for _ in $(seq 1 20); do
  if curl -sf "http://127.0.0.1:${PORT}/healthz" >/dev/null; then
    IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo '?')"
    echo "host up on $LISTEN (LAN: ws://$IP:${PORT}/face-chat/ws)"
    echo "logs: $LOG"
    exit 0
  fi
  sleep 0.25
done

echo "host failed to start — tail $LOG" >&2
tail -10 "$LOG" >&2
exit 1
