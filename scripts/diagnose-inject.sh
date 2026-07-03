#!/usr/bin/env bash
# Shows why HUD chip taps may not reach live agents.
set -euo pipefail
export AMBIENT_HOST="${AMBIENT_HOST:-http://127.0.0.1:5181}"

python3 <<'PY'
import json, os, subprocess, urllib.request

host = os.environ.get("AMBIENT_HOST", "http://127.0.0.1:5181")
try:
    with urllib.request.urlopen(host + "/ambient-link/status", timeout=5) as r:
        d = json.load(r)
except Exception as e:
    print(f"✗ cannot reach relay: {e}")
    raise SystemExit(1)

delivery = {x["SessionID"]: x for x in d.get("delivery") or []}
outbox = d.get("outbox") or []
sessions = d.get("sessions") or []

print("Live delivery endpoints (PID/TTY — inject targets these):")
if not delivery:
    print("  (none — start claude/codex/cursor in a terminal on this Mac)")
else:
    for sid, ep in delivery.items():
        print(f"  {ep.get('Agent','?'):6} session={sid[:8]}… pid={ep.get('PID')} tty={ep.get('TTY')}")

print("\nSessions in mux:")
for s in sessions[:8]:
    sid = s.get("session_id") or "?"
    agent = s.get("agent") or "?"
    state = s.get("state") or "?"
    thread = s.get("thread_id") or "?"
    mapped = "✓ endpoint" if sid in delivery else "✗ no PID"
    print(f"  {agent:6} {state:6} {mapped}  thread={thread}")

if outbox:
    print("\nStuck outbox (queued — agent had no live endpoint when tap sent):")
    for q in outbox:
        for m in q.get("messages") or []:
            print(f"  thread={m.get('thread')} session={str(m.get('session_id','?'))[:8]}… attempts={m.get('attempts',0)}")
    print("  Fix: restart host (purges >7d) + ensure agent process running on Mac.")
else:
    print("\nOutbox empty ✓")

ps = subprocess.run(["ps", "-A", "-o", "pid=,comm="], capture_output=True, text=True)
hits = [ln.strip() for ln in ps.stdout.splitlines() if any(k in ln.lower() for k in ("claude", "codex", "cursor-agent"))]
print("\nAgent processes on Mac:")
print("  " + ("\n  ".join(hits) if hits else "(none visible)"))
PY
