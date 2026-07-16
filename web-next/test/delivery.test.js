// The message-ID lifecycle and the offline queue — the core seam:
//   sending/offline → accepted → queued|delivered → landed | failed
import { describe, it, expect, beforeEach } from 'vitest';
import {
  app, resetForTest, setWsClientForTest, threadRow, sendPrompt,
  handleFrame, flushPendingInputs,
} from '../src/lib/store.svelte.js';
import { KEYS, loadJson } from '../src/lib/persist.js';

function fakeWs(liveState = true) {
  const sent = [];
  const client = {
    sent,
    liveState,
    live: () => client.liveState,
    send: (obj) => {
      if (!client.liveState) return false;
      sent.push(obj);
      return true;
    },
    forceReconnect: () => {},
  };
  return client;
}

let ws;

beforeEach(() => {
  localStorage.clear();
  resetForTest();
  ws = fakeWs();
  setWsClientForTest(ws);
});

it('sendPrompt sends session_id-addressed input and renders an honest sending bubble', () => {
  const row = threadRow('t1');
  row.sessionId = 'sess-1';

  sendPrompt('t1', 'do the thing');

  const frame = ws.sent.find((m) => m.type === 'input');
  expect(frame.session_id).toBe('sess-1');
  expect(frame.thread).toBe('t1');
  expect(frame.text).toBe('do the thing');
  expect(frame.client_id).toMatch(/^web-/);

  const bubble = row.chatLog.find((m) => m.role === 'user');
  expect(bubble.status).toBe('sending'); // local state; only the relay upgrades it
  expect(app.deliveryStates[frame.client_id].status).toBe('sending');
});

it('input_status advances the SAME bubble through accepted → landed', () => {
  const row = threadRow('t1');
  row.sessionId = 'sess-1';
  sendPrompt('t1', 'ping');
  const id = ws.sent.find((m) => m.type === 'input').client_id;

  for (const status of ['accepted', 'queued', 'delivered', 'landed']) {
    handleFrame({ type: 'input_status', id, thread: 't1', status });
    const bubble = row.chatLog.find((m) => m.msgId === id);
    expect(bubble.status).toBe(status);
  }
  expect(row.chatLog.filter((m) => m.role === 'user')).toHaveLength(1); // no duplicates
});

it('failed status marks the bubble and toasts — never a fake sent state', () => {
  const row = threadRow('t1');
  sendPrompt('t1', 'doomed');
  const id = ws.sent.find((m) => m.type === 'input').client_id;

  handleFrame({ type: 'input_status', id, thread: 't1', status: 'failed', error: 'no endpoint' });
  const bubble = row.chatLog.find((m) => m.msgId === id);
  expect(bubble.status).toBe('failed');
  expect(bubble.error).toBe('no endpoint');
  expect(app.toast.text).toContain('no endpoint');
});

it('offline sends queue in localStorage and flush on reconnect', () => {
  ws.liveState = false;
  threadRow('t1');
  sendPrompt('t1', 'queued while offline');

  expect(app.pendingInputs).toHaveLength(1);
  expect(app.toast.text).toContain('queued on this device');
  const persisted = loadJson(KEYS.pendingInputs, []);
  expect(persisted).toHaveLength(1);
  const bubble = app.threads.t1.chatLog.find((m) => m.role === 'user');
  expect(bubble.status).toBe('offline');

  // Reconnect: flush moves offline → sending over the wire.
  ws.liveState = true;
  flushPendingInputs();
  expect(app.pendingInputs).toHaveLength(0);
  const frame = ws.sent.find((m) => m.type === 'input');
  expect(frame.text).toBe('queued while offline');
  expect(app.threads.t1.chatLog.find((m) => m.role === 'user').status).toBe('sending');
});

it('input_status for a reloaded page materializes the bubble from cached text', () => {
  // Simulate: message was sent, page reloaded (no bubble), status arrives.
  threadRow('t1');
  app.deliveryStates['web-1-abc'] = { thread: 't1', text: 'from before reload', status: 'sending', updatedAt: 1 };

  handleFrame({ type: 'input_status', id: 'web-1-abc', thread: 't1', status: 'landed' });
  const bubble = app.threads.t1.chatLog.find((m) => m.msgId === 'web-1-abc');
  expect(bubble.text).toBe('from before reload');
  expect(bubble.status).toBe('landed');
});

it('sending to an ended session is refused honestly', () => {
  const row = threadRow('t1');
  row.ended = true;
  sendPrompt('t1', 'too late');
  expect(ws.sent.find((m) => m.type === 'input')).toBeUndefined();
  expect(app.toast.text).toBe('session ended');
});
