// WebSocket client for the relay — the battle-tested connection behavior from
// web/app.js as one module. It owns the socket only; every observable change
// goes through the callbacks (the store is the sole mutator of UI state).
//
// Ported invariants:
// - exponential backoff 500ms → 10s cap on reconnect
// - forceReconnect(): reset backoff and dial NOW (online / app foreground) —
//   no-op while a socket is open or connecting
// - heartbeat: any inbound frame refreshes the liveness clock; ping after
//   25s idle; hard-close after 45s (glasses↔phone links die silently)

const HEARTBEAT_IDLE_MS = 25000;
const HEARTBEAT_DEAD_MS = 45000;
const BACKOFF_MIN = 500;
const BACKOFF_MAX = 10000;

export function createWsClient(opts) {
  const { url, onFrame, onOpen, onDown, WebSocketImpl } = opts;
  const WS = WebSocketImpl || (typeof WebSocket !== 'undefined' ? WebSocket : null);

  let ws = null;
  let backoff = BACKOFF_MIN;
  let reconnectTimer = null;
  let heartbeatTimer = null;
  let lastMsgAt = Date.now();
  let stopped = false;

  function live() {
    return !!(ws && ws.readyState === 1);
  }

  function send(obj) {
    if (!live()) return false;
    try {
      ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj));
      return true;
    } catch {
      return false;
    }
  }

  function connect() {
    if (stopped) return;
    try {
      ws = new WS(typeof url === 'function' ? url() : url);
    } catch {
      if (onDown) onDown();
      scheduleReconnect();
      return;
    }
    ws.onopen = () => {
      backoff = BACKOFF_MIN;
      lastMsgAt = Date.now();
      if (onOpen) onOpen();
    };
    ws.onmessage = (ev) => {
      lastMsgAt = Date.now();
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (onFrame) onFrame(msg);
    };
    ws.onclose = () => {
      if (onDown) onDown();
      scheduleReconnect();
    };
    ws.onerror = () => {};
  }

  function scheduleReconnect() {
    if (stopped || reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, backoff);
    backoff = Math.min(backoff * 2, BACKOFF_MAX);
  }

  /** Network came back / app foregrounded: don't sit out the remaining
      backoff — reconnect NOW. No-op while a socket is open/opening. */
  function forceReconnect() {
    if (stopped) return;
    if (ws && (ws.readyState === 0 || ws.readyState === 1)) return;
    backoff = BACKOFF_MIN;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    connect();
  }

  function heartbeatTick() {
    if (!live()) return;
    const idle = Date.now() - lastMsgAt;
    if (idle > HEARTBEAT_DEAD_MS) {
      try { ws.close(); } catch { /* already closing */ }
      return;
    }
    if (idle > HEARTBEAT_IDLE_MS) {
      send({ type: 'ping' });
    }
  }

  function start() {
    stopped = false;
    heartbeatTimer = setInterval(heartbeatTick, 10000);
    connect();
  }

  function stop() {
    stopped = true;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
    if (ws) { try { ws.close(); } catch { /* ignore */ } ws = null; }
  }

  return { start, stop, send, live, forceReconnect, heartbeatTick };
}
