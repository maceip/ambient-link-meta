#!/usr/bin/env bash
# DEPRECATED: optional tmux helper only. Host correlates live claude/codex PIDs via
# proc watcher — you do not need fc-claude/fc-codex for observation or delivery.
# Start tmux agent sessions (claude / codex).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${AMBIENT_CORE:-$ROOT/../ambient-link-core}"

tmux start-server 2>/dev/null || true

for script in start-claude.sh start-codex.sh; do
  if [[ -x "$CORE/agents/$script" ]]; then
    "$CORE/agents/$script"
  else
    echo "missing $CORE/agents/$script" >&2
    exit 1
  fi
done
