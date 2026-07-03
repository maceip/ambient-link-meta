#!/usr/bin/env bash
# Deploy web companion to public.computer — git only. Never rsync/scp to prod.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO=/home/devuser/ambient-link-meta
HOST=root@public.computer

cd "$ROOT"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: commit and push local changes first (prod deploy is git-only)" >&2
  exit 1
fi

echo "== push origin/main =="
git push origin main

echo "== pull on server =="
ssh "$HOST" "sudo -u devuser -H git -C $REPO pull --ff-only"

echo "== verify =="
curl -sfI https://public.computer/ambient-link/ | head -3
curl -sf https://public.computer/ambient-link/sw.js | grep 'const CACHE'
echo "deploy ok"
