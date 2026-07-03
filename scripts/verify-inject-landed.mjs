#!/usr/bin/env node
/**
 * B-002 verification gate: inject to live cursor thread → delivered ≤2s;
 * optionally landed when agent transcript confirms user turn.
 * Exit 0 = delivered gate pass; exit 2 = delivered fail; exit 1 = usage/env.
 */
const HOST = process.env.AMBIENT_HOST || 'http://127.0.0.1:5181';
const WS = process.env.AMBIENT_WS || 'ws://127.0.0.1:5181/ambient-link/ws';
const LANDED_WAIT_MS = Number(process.env.LANDED_WAIT_MS || 15000);

async function get(path) {
  const res = await fetch(HOST + path);
  if (!res.ok) throw new Error(`${path} ${res.status}`);
  return res.json();
}

function waitFor(ws, pred, ms) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + ms;
    function onMsg(ev) {
      try {
        const msg = JSON.parse(ev.data);
        if (pred(msg)) {
          cleanup();
          resolve(msg);
        }
      } catch (_) { /* ignore */ }
    }
    function onTick() {
      if (Date.now() > deadline) {
        cleanup();
        reject(new Error(`timeout after ${ms}ms`));
      }
    }
    function cleanup() {
      ws.removeEventListener('message', onMsg);
      clearInterval(timer);
    }
    ws.addEventListener('message', onMsg);
    const timer = setInterval(onTick, 100);
  });
}

const status = await get('/ambient-link/status');
const delivery = status.delivery || [];
const sessions = status.sessions || [];
const live = sessions.find(
  (s) => s.agent === 'cursor' && s.state !== 'DEAD' && delivery.some((d) => d.SessionID === s.session_id),
);
if (!live) {
  console.error('FAIL B-002: no cursor session with PID/TTY endpoint — start Cursor agent in a terminal');
  process.exit(2);
}

const thread = live.thread_id;
const marker = `ambient-verify-${Date.now()}`;
const clientId = `verify-${Date.now()}`;

const ws = new WebSocket(WS);
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', rej, { once: true });
});
ws.send(JSON.stringify({ type: 'subscribe', since: { journal: status.journal || 0 } }));
await waitFor(ws, (m) => m.type === 'hello', 8000);

const t0 = Date.now();
const deliveredP = waitFor(
  ws,
  (m) => m.type === 'input_status' && m.id === clientId && m.status === 'delivered',
  2000,
);
ws.send(JSON.stringify({ type: 'input', thread, text: marker, client_id: clientId }));
let delivered;
try {
  delivered = await deliveredP;
} catch (e) {
  console.error(`FAIL B-002 delivered gate: ${e.message} thread=${thread}`);
  ws.close();
  process.exit(2);
}
const deliveredMs = Date.now() - t0;
console.log(`PASS B-002 delivered in ${deliveredMs}ms thread=${thread} session=${delivered.session_id || live.session_id}`);

let landed = null;
try {
  landed = await waitFor(
    ws,
    (m) => m.type === 'input_status' && m.status === 'landed' && m.thread === thread,
    LANDED_WAIT_MS,
  );
  console.log(`PASS B-002 landed after ${Date.now() - t0}ms session=${landed.session_id}`);
} catch {
  console.log(`WARN B-002 landed not observed within ${LANDED_WAIT_MS}ms (agent may not echo user turn yet)`);
}

ws.close();
process.exit(0);
