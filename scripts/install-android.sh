#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/relay-android"

if ! adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
  echo "No Android device — plug in phone and enable USB debugging." >&2
  exit 1
fi

./gradlew installDebug
echo "Installed. Open ambient link on phone; grant BT + mic if prompted."
