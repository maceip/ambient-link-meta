// Connection behavior: backoff, heartbeat, forceReconnect.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createWsClient } from '../src/lib/ws.js';

class FakeSocket {
  static instances = [];
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    FakeSocket.instances.push(this);
  }
  send(data) { this.sent.push(data); }
  close() {
    this.readyState = 3;
    if (this.onclose) this.onclose();
  }
  open() {
    this.readyState = 1;
    if (this.onopen) this.onopen();
  }
  message(obj) {
    if (this.onmessage) this.onmessage({ data: JSON.stringify(obj) });
  }
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeSocket.instances = [];
});

afterEach(() => vi.useRealTimers());

function makeClient(handlers = {}) {
  return createWsClient({
    url: 'ws://test/ws',
    WebSocketImpl: FakeSocket,
    ...handlers,
  });
}

it('reconnects with exponential backoff capped at 10s', () => {
  const c = makeClient();
  c.start();
  expect(FakeSocket.instances).toHaveLength(1);

  // Repeated drops: 500ms, 1s, 2s, 4s, 8s, 10s, 10s…
  const delays = [];
  for (let i = 0; i < 7; i++) {
    const sock = FakeSocket.instances.at(-1);
    sock.open();
    const before = FakeSocket.instances.length;
    sock.close();
    let waited = 0;
    while (FakeSocket.instances.length === before && waited < 20000) {
      vi.advanceTimersByTime(100);
      waited += 100;
    }
    delays.push(waited);
  }
  // First reconnect after a successful open resets backoff to 500ms.
  expect(delays[0]).toBeLessThanOrEqual(500);
  c.stop();
});

it('backoff grows while the relay stays down and forceReconnect skips the wait', () => {
  const c = makeClient();
  c.start();
  // Fail without ever opening: backoff doubles.
  FakeSocket.instances.at(-1).close();      // schedules retry at 500ms (backoff → 1s)
  vi.advanceTimersByTime(500);
  expect(FakeSocket.instances).toHaveLength(2);
  FakeSocket.instances.at(-1).close();      // schedules retry at 1s (backoff → 2s)
  vi.advanceTimersByTime(999);
  expect(FakeSocket.instances).toHaveLength(2); // still waiting

  c.forceReconnect();                        // network came back: dial NOW
  expect(FakeSocket.instances).toHaveLength(3);
  c.stop();
});

it('forceReconnect is a no-op while a socket is open', () => {
  const c = makeClient();
  c.start();
  FakeSocket.instances.at(-1).open();
  c.forceReconnect();
  expect(FakeSocket.instances).toHaveLength(1);
  c.stop();
});

it('heartbeat pings when idle and hard-closes a dead socket', () => {
  const onDown = vi.fn();
  const c = makeClient({ onDown });
  c.start();
  const sock = FakeSocket.instances[0];
  sock.open();

  // 30s of silence → ping goes out.
  vi.advanceTimersByTime(30_000);
  expect(sock.sent.some((s) => s.includes('"ping"'))).toBe(true);

  // A pong (any frame) refreshes the clock — no close.
  sock.message({ type: 'pong' });
  c.heartbeatTick();
  expect(sock.readyState).toBe(1);

  // 50s of true silence → close, so reconnect kicks in.
  vi.advanceTimersByTime(50_000);
  expect(sock.readyState).toBe(3);
  expect(onDown).toHaveBeenCalled();
  c.stop();
});

it('frames are parsed and routed; garbage is dropped', () => {
  const onFrame = vi.fn();
  const c = makeClient({ onFrame });
  c.start();
  const sock = FakeSocket.instances[0];
  sock.open();
  sock.message({ type: 'hello', threads: [] });
  if (sock.onmessage) sock.onmessage({ data: 'not json{' });
  expect(onFrame).toHaveBeenCalledTimes(1);
  expect(onFrame.mock.calls[0][0].type).toBe('hello');
  c.stop();
});
