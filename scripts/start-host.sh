#!/usr/bin/env bash
# Start ambient-link-host and keep it alive via launchd (macOS) or nohup fallback.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${AMBIENT_LINK_CORE:-$HOME/ambient-link-core}"
BIN="${AMBIENT_LINK_HOST:-/tmp/ambient-link-host}"
LISTEN="${AMBIENT_LINK_LISTEN:-0.0.0.0:5181}"
PORT="${LISTEN##*:}"
LOG="${AMBIENT_LINK_LOG:-$HOME/Library/Logs/ambient-link-host.log}"
WEB_ROOT="${AMBIENT_LINK_WEB_ROOT:-$ROOT/web}"
CLOUD="${AMBIENT_LINK_CLOUD:-}"
RELAY_DEBUG="${AMBIENT_RELAY_DEBUG:-}"
LABEL="com.maceip.ambient-link"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"

if [[ ! -x "$BIN" ]]; then
  echo "building host → $BIN"
  (cd "$CORE/host" && go build -o "$BIN" ./cmd/host)
fi

mkdir -p "$(dirname "$LOG")" "$HOME/Library/LaunchAgents"

ENV_XML="  <key>EnvironmentVariables</key>
  <dict>
    <key>AMBIENT_LINK_LISTEN</key><string>${LISTEN}</string>
    <key>AMBIENT_LINK_WEB_ROOT</key><string>${WEB_ROOT}</string>
    <key>AMBIENT_LINK_LOG</key><string>info</string>"
if [[ -n "$CLOUD" ]]; then
  ENV_XML="${ENV_XML}
    <key>AMBIENT_LINK_CLOUD</key><string>${CLOUD}</string>"
fi
if [[ "$RELAY_DEBUG" == "1" ]]; then
  ENV_XML="${ENV_XML}
    <key>AMBIENT_LINK_RELAY_DEBUG</key><string>1</string>"
fi
ENV_XML="${ENV_XML}
  </dict>"

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
  </array>
${ENV_XML}
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
  nohup env \
    AMBIENT_LINK_LISTEN="$LISTEN" \
    AMBIENT_LINK_WEB_ROOT="$WEB_ROOT" \
    AMBIENT_LINK_LOG=info \
    ${CLOUD:+AMBIENT_LINK_CLOUD="$CLOUD"} \
    ${RELAY_DEBUG:+AMBIENT_LINK_RELAY_DEBUG=1} \
    "$BIN" serve >>"$LOG" 2>&1 &
fi

for _ in $(seq 1 20); do
  if curl -sf "http://127.0.0.1:${PORT}/healthz" >/dev/null; then
    IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo '?')"
    echo "host up on $LISTEN (LAN: ws://$IP:${PORT}/ambient-link/ws)"
    [[ -n "$CLOUD" ]] && echo "cloud bridge: $CLOUD"
    echo "logs: $LOG"
    exit 0
  fi
  sleep 0.25
done

echo "host failed to start — tail $LOG" >&2
tail -10 "$LOG" >&2
exit 1
