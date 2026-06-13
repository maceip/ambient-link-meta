#!/usr/bin/env bash
# Generate a single 16 kHz mono PCM WAV for on-device SODA dictate tests.
# Same format as ~/neural/scripts/test-fixtures/generate-audio-fixtures.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/scripts/test-fixtures/audio}"
SLUG="${2:-define_polymorphism}"
PHRASE="${3:-Define polymorphism.}"
VOICE="${4:-Samantha}"

mkdir -p "$OUT_DIR"
if ! command -v say >/dev/null 2>&1 || ! command -v afconvert >/dev/null 2>&1; then
  echo "say + afconvert required (macOS)" >&2
  exit 64
fi

aiff="$OUT_DIR/$SLUG.aiff"
wav="$OUT_DIR/$SLUG.wav"
say -v "$VOICE" -o "$aiff" -- "$PHRASE"
afconvert -f WAVE -d LEI16@16000 -c 1 "$aiff" "$wav"
rm -f "$aiff"
echo "Wrote $wav ($(wc -c <"$wav" | tr -d ' ') bytes): $PHRASE"
