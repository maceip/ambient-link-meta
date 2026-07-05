#!/usr/bin/env bash
# Point the phone relay at the Mac LAN relay (debug APK, USB adb).
set -euo pipefail
MAC_IP="${1:-192.168.1.33}"
WS="ws://${MAC_IP}:5181/ambient-link/ws"
PKG=com.lowkey.ambientlink

adb devices | grep -q device || { echo "No adb device"; exit 1; }

adb shell run-as "$PKG" sh <<EOF
PREFS=shared_prefs/ambient-link-meta.xml
mkdir -p shared_prefs
cat > "\$PREFS" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="relay_url">${WS}</string>
  <string name="last_lan_relay_ws">${WS}</string>
  <string name="last_lan_relay_ip">${MAC_IP}</string>
  <boolean name="debug_lan_only" value="true" />
</map>
XML
EOF

adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2
# Tap through to start relay — user may need one Start tap; prefs are set.
echo "OK — prefs set to ${WS}. Open Ambient Link on phone; relay should use LAN."
