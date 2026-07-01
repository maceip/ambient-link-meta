# Ambient Link — working build

Fresh, dependency-free implementation of the tracer in `BUILD-SPEC.md`
(requirements R1–R6, plus live observation). One Go module, no external Go
modules, no `protoc`, no build step for the web client.

## Run

```
cd rebuild
go run ./cmd/agent
```

Open <http://localhost:8765>.

You'll see:

- **Claude · auth-service** and **Codex · data-pipeline** — replyable demo agents.
  They work, then ask a question (status flips to **WAITING**). Type a reply and
  Send: your exact message lands as a consumed turn (the **✓ landed** badge), the
  agent acknowledges it, runs, and falls to **DONE**. This is the bidirectional
  channel + trustworthy status, proven end to end.
- **Cursor · this machine (live)** — read-only. Tails the newest real Cursor
  transcript on this machine and shows it as a live session (R2 on real data).

Optional env: `AMBIENT_PORT` (default 8765), `AMBIENT_WEB` (default `./web`).

## Verify (no UI needed)

```
cd rebuild
go test ./...
```

- `internal/status` — R4: the status projection table.
- `internal/server` — R6: a human reply reaches the agent, lands
  (`consumed=true`) as the exact text, and is acknowledged.

## Map to the spec

| Requirement | Where |
|---|---|
| R1 contract + version refused | `internal/model`, `ambient.proto`, `server.ServeWS` |
| R2 observe | `internal/producers/{demo,cursor}.go` |
| R3 location-independent handle | `agent:<id>` in producers, no path |
| R4 pure status projection | `internal/status` (+ test) |
| R5 consumer sees live status | `internal/server`, `web/` |
| R6 reply that lands | `internal/producers/demo.go`, `server.handleHuman` (+ test) |

## What's deliberately not here yet

Substrate replica (R7), forward pipe (R8), multi-machine (R9), location handoff
(R10), media/audio plane (R11) and convenience chips (R12) build on this exact
skeleton without reshaping it. The contract is frozen; they are additive.
