#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ROOT
node -e "
const fs = require('fs');
const path = require('path');
const src = fs.readFileSync(path.join(process.env.ROOT, 'web/chipset.js'), 'utf8');
const ctx = { window: {}, console };
ctx.globalThis = ctx.window;
require('vm').runInNewContext(src, ctx);
const CS = ctx.window.AmbientChipSet;
function assert(cond, msg) { if (!cond) { console.error('✗', msg); process.exit(1); } }
const q = CS.forYank(
  { thread: 't', label: 'test', awaiting: CS.Awaiting.QUESTION, lastAssistant: 'ready?' },
  { showDictate: true, showContinue: true, quickReplies: ['continue'] },
);
assert(q[0].label === 'yes' && q[1].label === 'no', 'question chips should be yes/no');
const done = CS.forYank(
  { thread: 't', label: 'test', awaiting: CS.Awaiting.DONE, lastAssistant: 'done' },
  { showDictate: true, showContinue: true, quickReplies: ['looks good'] },
);
assert(done[0].label === 'looks good', 'done should prioritize quick replies');
console.log('✓ chip contract');
"
