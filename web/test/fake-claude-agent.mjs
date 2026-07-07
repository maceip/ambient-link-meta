#!/usr/bin/env node
// Fake "claude" agent for hermetic E2E tests.
//
// Emulates the full observable contract of a real Claude Code session so the
// production relay treats it as live — everything except the LLM is real:
//   1. process command line contains "claude" (this file's name), so the
//      relay's proc watcher (`ps -A`) classifies it as an agent process;
//   2. holds the transcript .jsonl open for append, so `lsof -p` correlates
//      this PID to the session uuid (delivery endpoint registration);
//   3. appends claude-format records ({sessionId, cwd, type, message}) in
//      real time, which the relay's JSONL tailer ingests;
//   4. reads stdin — the relay delivers human replies via `tmux send-keys`,
//      which land here — and echoes each line back into the transcript
//      verbatim as a user record (this is what upgrades delivery status to
//      "landed"), followed by an "ack:" assistant record (agent→human path).
//
// Usage:
//   fake-claude-agent.mjs <home-dir> <agent-cwd> <session-uuid>   (harness mode)
//   fake-claude-agent.mjs [initial prompt...]                     (spawn mode:
//     HOME/cwd from the environment, uuid generated — matches how the relay's
//     create-session spawns a real agent via AMBIENT_LINK_SPAWN_* overrides)
// Must run inside a tmux pane on the DEFAULT tmux server (the relay's tmux
// delivery adapter resolves panes by pid on the default server only).
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { randomUUID } from 'node:crypto';

const argv = process.argv.slice(2);
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
let homeDir, agentCwd, sessionId, initialPrompt = '';
if (argv.length === 3 && UUID_RE.test(argv[2])) {
  [homeDir, agentCwd, sessionId] = argv;
} else {
  homeDir = process.env.HOME;
  agentCwd = process.cwd();
  sessionId = randomUUID();
  initialPrompt = argv.join(' ');
}
if (!homeDir || !agentCwd || !sessionId) {
  console.error('usage: fake-claude-agent.mjs [<home-dir> <agent-cwd> <session-uuid> | prompt...]');
  process.exit(2);
}

const projectDir = path.join(
  homeDir, '.claude', 'projects',
  agentCwd.replace(/[^A-Za-z0-9]/g, '-'),
);
fs.mkdirSync(projectDir, { recursive: true });
const transcript = path.join(projectDir, `${sessionId}.jsonl`);
const out = fs.createWriteStream(transcript, { flags: 'a' });

function emit(type, role, text) {
  out.write(JSON.stringify({
    sessionId,
    cwd: agentCwd,
    type,
    timestamp: new Date().toISOString(),
    message: { role, content: [{ type: 'text', text }] },
  }) + '\n');
}

emit('user', 'user', initialPrompt || 'begin e2e session');
emit('assistant', 'assistant', `fake-agent ready in ${agentCwd}`);
console.log(`fake-claude-agent up: session=${sessionId} transcript=${transcript}`);

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  const text = line.trim();
  if (!text) return;
  emit('user', 'user', text);
  setTimeout(() => emit('assistant', 'assistant', `ack: ${text}`), 300);
});
// The pane's pty never closes stdin in normal operation; if it does, stay
// alive so the session doesn't flap DEAD mid-test.
rl.on('close', () => setInterval(() => {}, 2 ** 30));
