#!/usr/bin/env bash
# Copy on-device SODA runtime artifacts from ~/neural into relay-android.
# Run once after clone (or when neural updates packs / libsoda).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEURAL="${NEURAL_ROOT:-$HOME/neural}"
RELAY="$ROOT/relay-android"

JAR_SRC="$NEURAL/app/libs/recovered-soda.jar"
JNI_SRC="$NEURAL/app/src/main/jniLibs/arm64-v8a/libsoda_dev_jni.so"
PACK_SRC="$NEURAL/app/src/main/assets/soda/lp_cpu.zip"

JAR_DST="$RELAY/app/libs/recovered-soda.jar"
JNI_DST="$RELAY/app/src/main/jniLibs/arm64-v8a/libsoda_dev_jni.so"
PACK_DST="$RELAY/app/src/main/assets/soda/lp_cpu.zip"

copy_one() {
  local src="$1" dst="$2"
  if [[ ! -f "$src" ]]; then
    echo "✗ missing: $src" >&2
    echo "  provision neural first: cd $NEURAL && scripts/provision-from-recovery.sh" >&2
    exit 2
  fi
  mkdir -p "$(dirname "$dst")"
  cp -f "$src" "$dst"
  echo "✓ $(basename "$dst")"
}

echo "provisioning SODA from $NEURAL → relay-android"
copy_one "$JAR_SRC" "$JAR_DST"
copy_one "$JNI_SRC" "$JNI_DST"
copy_one "$PACK_SRC" "$PACK_DST"
echo "done — rebuild relay-android"
