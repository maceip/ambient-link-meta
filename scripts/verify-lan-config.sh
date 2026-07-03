#!/usr/bin/env bash
# Simulates phone cwd-save HTTP path (Mac config API).
set -euo pipefail
HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"
TEST_DIR="${AMBIENT_TEST_CWD:-/tmp/ambient-link-cwd-verify}"

curl -sf "${HOST}/healthz" >/dev/null || { echo "host down"; exit 1; }

mkdir -p "$TEST_DIR"
payload=$(printf '{"default_cwd":"%s","create":false}' "$TEST_DIR")
resp=$(curl -sf -X POST "${HOST}/ambient-link/config" \
  -H 'Content-Type: application/json' \
  -d "$payload")

echo "$resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
if d.get('ok'):
    print('✓ POST /ambient-link/config OK', d.get('resolved_path',''))
    sys.exit(0)
print('✗ config failed:', d)
sys.exit(1)
"
