#!/usr/bin/env bash
# Fail fast if debug APK is missing on-device SODA (dictate will not work).
set -euo pipefail
APK="${1:?usage: $0 path/to/app-debug.apk}"
RELAY_APP="$(cd "$(dirname "$APK")/../../../../" && pwd)"
JAR="${2:-$RELAY_APP/libs/recovered-soda.jar}"

if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR — run scripts/provision-soda-from-neural.sh" >&2
  exit 1
fi

for path in assets/soda/lp_cpu.zip lib/arm64-v8a/libsoda_dev_jni.so; do
  if ! unzip -l "$APK" | awk '{print $4}' | grep -qx "$path"; then
    echo "APK missing $path — run scripts/provision-soda-from-neural.sh then rebuild" >&2
    exit 1
  fi
done

if ! command -v dexdump >/dev/null 2>&1; then
  echo "warn: dexdump not found — skipping class verification (install Android build-tools)" >&2
  echo "ok: SODA pack + native lib present in $(basename "$APK")"
  exit 0
fi

dex_index="$(mktemp)"
trap 'rm -f "$dex_index"' EXIT
dexdump -f "$APK" >"$dex_index" 2>&1 || true

missing=()
for needle in \
  'com/google/android/libraries/assistant/soda/Soda;' \
  'com/google/android/libraries/assistant/soda/SodaJniLoader;' \
  'com/google/speech/soda/SodaEventProto$SodaEvent;' \
  'com/google/android/libraries/assistant/soda/SodaUtils$DirectByteBufferMaker;' \
  'com/google/common/base/Optional;' \
  'com/google/research/air/cosmo/lib/soda/SodaSession;'
do
  if ! grep -qF "$needle" "$dex_index"; then
    missing+=("L$needle")
  fi
done

if ((${#missing[@]} > 0)); then
  echo "APK dex missing SODA classes (recovered-soda.jar not merged?):" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

echo "ok: SODA pack, native lib, and dex classes present in $(basename "$APK")"
