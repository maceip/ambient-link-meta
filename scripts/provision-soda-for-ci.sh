#!/usr/bin/env bash
# Hydrate gitignored SODA binaries for relay-android CI (and local builds without ~/neural).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="${SODA_MANIFEST:-$ROOT/config/soda-runtime-binaries.json}"

if [[ ! -f "$MANIFEST" ]]; then
  echo "missing manifest: $MANIFEST" >&2
  exit 65
fi

need_aws=0
while IFS= read -r line; do
  [[ "$line" == *'"s3Uri"'* ]] && need_aws=1 && break
done < "$MANIFEST"

if [[ "$need_aws" -eq 1 ]] && ! command -v aws >/dev/null 2>&1; then
  echo "aws CLI required for S3-backed SODA artifacts" >&2
  exit 69
fi

rows="$(mktemp)"
trap 'rm -f "$rows"' EXIT

python3 - "$MANIFEST" >"$rows" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    manifest = json.load(handle)

for index, artifact in enumerate(manifest.get("artifacts", [])):
    artifact_id = artifact["id"]
    path = artifact["path"]
    sha256 = artifact["sha256"]
    size_bytes = artifact["sizeBytes"]
    http_url = artifact.get("httpUrl", "")
    s3_uri = artifact.get("s3Uri", "")
    if bool(http_url) == bool(s3_uri):
        raise SystemExit(f"artifact {artifact_id} must have exactly one of httpUrl or s3Uri")
    source = http_url or s3_uri
    print("\t".join([artifact_id, path, source, sha256, str(size_bytes)]))
PY

sha_file() { shasum -a 256 "$1" | awk '{print $1}'; }
size_file() {
  if size="$(stat -f '%z' "$1" 2>/dev/null)"; then
    printf '%s\n' "$size"
  else
    stat -c '%s' "$1"
  fi
}

verify_one() {
  local path="$1" expected_sha="$2" expected_size="$3"
  [[ -f "$path" ]] || return 1
  [[ "$(size_file "$path")" == "$expected_size" ]] || return 1
  [[ "$(sha_file "$path")" == "$expected_sha" ]] || return 1
}

count=0
while IFS=$'\t' read -r artifact_id rel_path source sha256 size_bytes; do
  [[ -z "${artifact_id:-}" ]] && continue
  target="$ROOT/$rel_path"
  if verify_one "$target" "$sha256" "$size_bytes"; then
    echo "ok: $rel_path"
    count=$((count + 1))
    continue
  fi

  mkdir -p "$(dirname "$target")"
  tmp="${target}.download"
  echo "fetch: $artifact_id -> $rel_path"
  if [[ "$source" == http://* || "$source" == https://* ]]; then
    curl -fsSL --retry 3 "$source" -o "$tmp"
  else
    aws s3 cp --only-show-errors "$source" "$tmp"
  fi
  mv "$tmp" "$target"
  verify_one "$target" "$sha256" "$size_bytes" || {
    echo "checksum failed after download: $rel_path" >&2
    exit 66
  }
  count=$((count + 1))
done <"$rows"

echo "provisioned $count SODA artifact(s)"
