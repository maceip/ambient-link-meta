// The one store. Every piece of UI state lives in this $state tree and every
// mutation happens in this module — components only read it and call the
// exported actions. This is the declarative replacement for app.js's 49
// hand-sequenced render*() calls.
//
// Protocol contract: PROTOCOL-WEB.md (repo root). Message lifecycle:
//   sending/offline → accepted → queued|delivered → landed | failed
// Only relay frames advance a message past 'sending'. No fabricated statuses.

import * as CS from './chipset.js';
import * as PIPE from './content-pipeline.js';
import * as GC from './glasses-copy.js';
import { KEYS, loadJson, saveJson, loadString, saveString } from './persist.js';
import { createWsClient } from './ws.js';
import { listPreviewPlain, shortName, expandHomePath } from './format.js';

const MAX_LIST_ITEMS = 4;
const CONN_GRACE_MS = 2500;
const MAX_KNOWN_FOLDERS = 6;

export const app = $state({
  view: 'list', // list | thread | new
  activeThread: null,
  threads: {},
  threadOrder: [],
  conn: 'connecting', // displayed: connecting | warn | on | off
  // mic: which capture path the phone should use for this turn — 'phone' |
  // 'glasses' | null. Chosen by the two dictate buttons on the session row.
  dictate: { phase: 'idle', partial: '', draft: '', phoneThread: null, mic: null },
  // dictateMic mirrors the Android app setting (phone | glasses) so the
  // listening chrome can say which path is live — choice is NOT a web button.
  companion: { quickReplies: [], snoozeUntil: 0, showContinue: true, showDictate: true, dictateMic: 'phone' },
  // Sessions that would have DAT-peeked while web owned the display.
  // FIFO stack; Switch jumps to the oldest other-than-current entry.
  wakeStack: [],
  host: {
    relayDebug: false,
    journal: 0,
    now: 0,
    delivery: {},
    defaultCwd: '',
    relayConnected: null,
    laptopPeerConnected: false,
    liveSessionCount: 0,
  },
  pendingInputs: [],
  deliveryStates: {},
  pickedAgent: 'cursor',
  pendingCreate: null,
  listFocusedThreadId: null,
  // Prefill for New session (folder pick / New here). No free-text path.
  newDraft: { cwd: '', fromThread: null },
  toast: { text: '', kind: '', seq: 0 },
});

// ── environment (injectable for tests) ─────────────────────────────────────

let env = {
  fetch: (...a) => globalThis.fetch(...a),
  now: () => Date.now(),
  WebSocketImpl: undefined,
};

let TOKEN = '';
let wsc = null; // createWsClient instance
let pendingDeepLink = null;

export function clockNow() {
  return app.host.now || env.now();
}

export function isSnoozing() {
  return !!(app.companion.snoozeUntil && clockNow() < app.companion.snoozeUntil);
}

// ── auth / urls ─────────────────────────────────────────────────────────────

function loadToken() {
  try {
    const m = (location.hash || '').match(/[#&]token=([^&]+)/);
    if (m) {
      const tok = decodeURIComponent(m[1]);
      saveString(KEYS.token, tok);
      history.replaceState({}, '', location.pathname + location.search);
      return tok;
    }
    return loadString(KEYS.token);
  } catch {
    return '';
  }
}

function authHeaders(extra) {
  const h = extra || {};
  if (TOKEN) h['Authorization'] = 'Bearer ' + TOKEN;
  return h;
}

function wsUrl() {
  const base = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ambient-link/ws';
  return TOKEN ? base + '?token=' + encodeURIComponent(TOKEN) : base;
}

// ── toast ───────────────────────────────────────────────────────────────────

export function showToast(text, kind) {
  app.toast = { text, kind: kind || '', seq: app.toast.seq + 1 };
}

// ── connection state (displayed, with anti-thrash grace) ────────────────────

export function wsLive() {
  return !!(wsc && wsc.live());
}

export function wsConnected() {
  return app.conn === 'on';
}

let connGraceTimer = null;
let pendingConnState = null;

/* Anti-thrash: the glasses↔phone link blips constantly. Drops from a
   connected state are held back for a grace window; recovery ('on') always
   applies immediately. */
export function setStatus(state) {
  state = state || 'off';
  if (state === 'on') {
    if (connGraceTimer) { clearTimeout(connGraceTimer); connGraceTimer = null; }
    pendingConnState = null;
    app.conn = 'on';
    return;
  }
  if (app.conn !== 'on') {
    if (!connGraceTimer) app.conn = state;
    else pendingConnState = state;
    return;
  }
  pendingConnState = state;
  if (connGraceTimer) return;
  connGraceTimer = setTimeout(() => {
    connGraceTimer = null;
    if (pendingConnState) app.conn = pendingConnState;
    pendingConnState = null;
  }, CONN_GRACE_MS);
}

export function connectionCopy() {
  const state = app.conn;
  const live = app.host.liveSessionCount || liveThreads().length;
  if (state === 'warn' || state === 'connecting') return 'Connecting to relay…';
  if (state === 'off') return 'Not connected — open from your Mac relay or check network';
  if (app.host.relayConnected === false) return 'Relay unreachable — reconnecting…';
  if (live > 0) return 'Connected · ' + live + ' live session' + (live === 1 ? '' : 's');
  if (app.host.laptopPeerConnected) return 'Connected · Mac linked, no active agents';
  return 'Connected · no Mac agents running';
}

// ── threads ─────────────────────────────────────────────────────────────────

export function threadRow(id) {
  if (!app.threads[id]) {
    app.threads[id] = {
      id,
      label: id,
      agent: 'generic',
      busy: false,
      ended: false,
      yank: null,
      chatLog: [],
      lastEventAt: 0,
      deliverable: false,
      sessionState: 'IDLE',
      cwd: '',
      sessionId: '',
      restored: false,
      historyLoading: false,
      historyLoaded: false,
    };
    app.threadOrder.push(id);
  }
  return app.threads[id];
}

export function liveThreads() {
  return app.threadOrder
    .map((id) => app.threads[id])
    .filter((t) => t && !t.ended);
}

export function visibleThreads() {
  return app.threadOrder
    .map((id) => app.threads[id])
    .filter(Boolean)
    .sort((a, b) => (b.lastEventAt || 0) - (a.lastEventAt || 0));
}

/** Newest at bottom (chat-style), capped at MAX_LIST_ITEMS most recent. */
export function listThreads() {
  const live = liveThreads()
    .slice()
    .sort((a, b) => (a.lastEventAt || 0) - (b.lastEventAt || 0));
  return live.length > MAX_LIST_ITEMS ? live.slice(live.length - MAX_LIST_ITEMS) : live;
}

/** Drop ended sessions from memory so the list can't fill with ghosts. */
function reapDeadThreads() {
  app.threadOrder = app.threadOrder.filter((id) => {
    const t = app.threads[id];
    return t && !t.ended;
  });
  Object.keys(app.threads).forEach((id) => {
    if (app.threads[id] && app.threads[id].ended) delete app.threads[id];
  });
}

function sessionDeliverable(sessionId) {
  return !!(sessionId && app.host.delivery[sessionId]);
}

export function statusBadge(t) {
  if (t.ended || t.sessionState === 'DEAD') return 'dead';
  if (!wsConnected() && !t.lastEventAt) return 'offline';
  if (t.sessionId && !sessionDeliverable(t.sessionId) && app.host.laptopPeerConnected) return 'unreachable';
  if (t.busy || t.sessionState === 'BUSY' || t.sessionState === 'STARTING') return 'busy';
  if (t.yank && t.yank.awaiting === CS.Awaiting.PERMISSION) return 'permission';
  if (t.yank && t.yank.awaiting === CS.Awaiting.QUESTION) return 'question';
  if (t.yank && t.yank.awaiting === CS.Awaiting.DONE) return 'done';
  if (t.yank) return 'idle';
  return 'online';
}

export function listConnectionDot(t) {
  const badge = statusBadge(t);
  if (badge === 'offline' || badge === 'unreachable') return 'offline';
  if (badge === 'dead') return 'dead';
  if (badge === 'busy' || badge === 'permission') return 'busy';
  return 'live';
}

function agentTextFromYank(yank) {
  return GC.displayForYank(yank);
}

export function lastAgentPreview(row) {
  if (row && row.yank) return agentTextFromYank(row.yank);
  const log = row && row.chatLog;
  if (log && log.length) {
    for (let i = log.length - 1; i >= 0; i--) {
      if (log[i].role === 'agent' && log[i].text) return log[i].text;
    }
  }
  return '';
}

export function listPreviewText(t) {
  if (t.ended) return 'session ended';
  if (t.busy) return 'thinking…';
  if (t.yank) return listPreviewPlain(agentTextFromYank(t.yank));
  const agentPrev = lastAgentPreview(t);
  if (agentPrev) return listPreviewPlain(agentPrev);
  return '';
}

export function emptyHintText() {
  if (app.conn === 'warn' || app.conn === 'connecting') return 'Loading sessions…';
  if (!wsConnected()) return 'Relay offline — open this app from your Mac or wait for reconnect';
  if (app.host.liveSessionCount > 0) return 'Loading sessions…';
  if (app.host.laptopPeerConnected) return 'No active agents — tap New session, or start one on your Mac';
  return 'No sessions — tap New session';
}

// ── list snapshot (instant paint before ws) ─────────────────────────────────

/* Snapshot is written only while connected, so a dead-relay render never
   overwrites the good one; restored rows are reconciled against the next
   hello. */
export function saveListSnapshot() {
  const live = listThreads();
  if (!wsConnected() || !live.length) return;
  saveJson(KEYS.listSnapshot, live.map((t) => ({
    id: t.id,
    label: t.label,
    agent: t.agent,
    lastAssistant: listPreviewPlain(listPreviewText(t)) || t.lastAssistant || '',
    lastEventAt: t.lastEventAt || 0,
  })));
}

function restoreListSnapshot() {
  const rows = loadJson(KEYS.listSnapshot, []);
  if (!Array.isArray(rows)) return;
  rows.forEach((s) => {
    if (!s || !s.id || app.threads[s.id]) return;
    const row = threadRow(s.id);
    row.label = s.label || s.id;
    row.agent = s.agent || 'generic';
    row.lastAssistant = s.lastAssistant || '';
    row.lastEventAt = s.lastEventAt || 0;
    row.restored = true; // dropped on hello if the relay no longer has it
  });
}

function reconcileRestoredRows(helloThreads) {
  const seen = {};
  (helloThreads || []).forEach((t) => { if (t && t.id) seen[t.id] = true; });
  Object.keys(app.threads).forEach((id) => {
    if (app.threads[id].restored && !seen[id]) {
      delete app.threads[id];
      app.threadOrder = app.threadOrder.filter((x) => x !== id);
    } else {
      app.threads[id].restored = false;
    }
  });
}

// ── chat log (append-only; status is the only in-place mutation) ────────────

function loadChatLogs() {
  const data = loadJson(KEYS.chatLogs, null);
  if (!data || typeof data !== 'object') return;
  Object.keys(data).forEach((id) => {
    if (!Array.isArray(data[id]) || !data[id].length) return;
    const row = threadRow(id);
    if (!row.chatLog || !row.chatLog.length) row.chatLog = data[id];
  });
}

function saveChatLogs() {
  const data = {};
  Object.keys(app.threads).forEach((id) => {
    const log = app.threads[id].chatLog;
    if (log && log.length) data[id] = log;
  });
  saveJson(KEYS.chatLogs, data);
}

function filterText(raw) {
  return PIPE.filterForDisplay(raw || '');
}

function chatHasRoleText(row, role, rawText) {
  if (!rawText || !String(rawText).trim()) return false;
  const disp = filterText(rawText).display;
  const log = row && row.chatLog;
  if (!log || !log.length) return false;
  return log.some((m) => m.role === role && m.text === disp);
}

function chatFindByMsgId(row, msgId) {
  const log = row && row.chatLog;
  if (!log || !msgId) return null;
  for (let i = log.length - 1; i >= 0; i--) {
    if (log[i].msgId === msgId) return log[i];
  }
  return null;
}

function appendChatMessage(row, role, rawText, opts) {
  if (!rawText || !String(rawText).trim()) return;
  opts = opts || {};
  if (opts.id && chatFindByMsgId(row, opts.id)) return;
  // Agent copy is already glasses-shaped (ask/ready); user text still classified.
  let display;
  let kind;
  let truncated;
  if (role === 'agent') {
    display = String(rawText).trim();
    if (!display) return;
    kind = opts.kind || 'glasses';
    truncated = false;
  } else {
    if (chatHasRoleText(row, role, rawText)) return;
    const filtered = filterText(rawText);
    display = filtered.display;
    if (!display) return;
    kind = filtered.kind;
    truncated = filtered.truncated;
  }
  if (role === 'agent' && chatHasExact(row, role, display)) return;
  if (!row.chatLog) row.chatLog = [];
  row.chatLog.push({
    role,
    text: display,
    kind,
    truncated,
    at: opts.at || clockNow(),
    msgId: opts.id || '',
    status: opts.status || '',
    error: opts.error || '',
  });
  if (row.chatLog.length > 48) row.chatLog = row.chatLog.slice(-48);
  saveChatLogs();
}

function chatHasExact(row, role, text) {
  const log = row && row.chatLog;
  if (!log || !text) return false;
  return log.some((m) => m.role === role && m.text === text);
}

/** Update a user bubble's lifecycle status by message ID. Honest relay
    states only. Returns true if a bubble was found. */
function updateMessageStatus(threadId, msgId, status, error) {
  const row = app.threads[threadId];
  const entry = chatFindByMsgId(row, msgId);
  if (!entry) return false;
  entry.status = status || entry.status;
  entry.error = error || '';
  saveChatLogs();
  return true;
}

function appendUserMessage(row, text, opts) {
  if (!row || !text || !String(text).trim()) return;
  appendChatMessage(row, 'user', text, opts);
}

function recordAgentReply(row, rawText) {
  if (!row || row.busy) return;
  const display = GC.displayAgentHistory(rawText);
  if (!display) return;
  appendChatMessage(row, 'agent', display, { kind: 'glasses' });
}

function mergeAgentFromYank(row) {
  if (!row || !row.yank) return;
  const display = agentTextFromYank(row.yank);
  if (!display) return;
  appendChatMessage(row, 'agent', display, { kind: 'glasses' });
}

function replayDeliveredUserMessages(row) {
  if (!row) return;
  Object.keys(app.deliveryStates).forEach((id) => {
    const st = app.deliveryStates[id];
    if (!st || st.thread !== row.id) return;
    if (st.status !== 'delivered' && st.status !== 'landed') return;
    const text = (st.text || '').trim();
    if (text) appendUserMessage(row, text, { id, status: st.status });
  });
  app.pendingInputs.forEach((item) => {
    if (item.thread !== row.id) return;
    const text = (item.text || '').trim();
    if (text) appendUserMessage(row, text, { id: item.id, status: 'offline' });
  });
}

function hydrateChatIfEmpty(row) {
  if (!row || (row.chatLog && row.chatLog.length)) return;
  replayDeliveredUserMessages(row);
  if (!row.busy) {
    mergeAgentFromYank(row);
    if ((!row.chatLog || !row.chatLog.length) && row.lastAssistant) {
      const display = GC.displayAgentHistory(row.lastAssistant);
      if (display) appendChatMessage(row, 'agent', display, { kind: 'glasses' });
    }
  }
}

/* Relay history is the authoritative record; localStorage is only a display
   cache. Relay rows first (real message IDs and final delivery statuses),
   then local entries the relay doesn't know — in-flight and offline drafts. */
function hydrateFromRelayHistory(row) {
  if (!row || !row.sessionId || row.historyLoading || row.historyLoaded) return;
  row.historyLoading = true;
  env.fetch('/ambient-link/history?session_id=' + encodeURIComponent(row.sessionId) + '&limit=48', {
    headers: authHeaders(),
  })
    .then((r) => (r.ok ? r.json() : null))
    .then((data) => {
      row.historyLoading = false;
      if (!data || !Array.isArray(data.rows)) return;
      row.historyLoaded = true;
      if (!data.rows.length) return;
      mergeHistoryRows(row, data.rows);
    })
    .catch(() => { row.historyLoading = false; });
}

export function mergeHistoryRows(row, rows) {
  const merged = [];
  const seenIds = {};
  rows.forEach((r) => {
    if (!r || !r.text || !String(r.text).trim()) return;
    const isUser = r.role === 'human' || r.role === 'user';
    let display;
    let kind;
    let truncated;
    if (isUser) {
      const filtered = filterText(r.text);
      display = filtered.display;
      kind = filtered.kind;
      truncated = filtered.truncated;
    } else {
      display = GC.displayAgentHistory(r.text);
      kind = 'glasses';
      truncated = false;
    }
    if (!display) return;
    const entry = {
      role: isUser ? 'user' : 'agent',
      text: display,
      kind,
      truncated,
      at: r.at || 0,
      msgId: r.message_id || '',
      status: isUser ? (r.delivery_status || '') : '',
      error: '',
    };
    if (entry.msgId) seenIds[entry.msgId] = true;
    merged.push(entry);
  });
  const textSeen = {};
  merged.forEach((m) => { textSeen[m.role + '\u0000' + m.text] = true; });
  (row.chatLog || []).forEach((m) => {
    if (m.msgId && seenIds[m.msgId]) return;
    if (textSeen[m.role + '\u0000' + m.text]) return;
    merged.push(m);
  });
  merged.sort((a, b) => (a.at || 0) - (b.at || 0));
  row.chatLog = merged.slice(-48);
  saveChatLogs();
}

// ── delivery lifecycle ──────────────────────────────────────────────────────

function saveDeliveryStates() {
  const keys = Object.keys(app.deliveryStates).sort(
    (a, b) => (app.deliveryStates[b].updatedAt || 0) - (app.deliveryStates[a].updatedAt || 0),
  );
  const compact = {};
  keys.slice(0, 100).forEach((k) => { compact[k] = app.deliveryStates[k]; });
  app.deliveryStates = compact;
  saveJson(KEYS.deliveryStates, compact);
}

function trackDelivery(id, fields) {
  if (!id) return;
  app.deliveryStates[id] = {
    ...(app.deliveryStates[id] || {}),
    ...(fields || {}),
    updatedAt: clockNow(),
  };
  saveDeliveryStates();
}

function newInputId() {
  return 'web-' + String(clockNow()) + '-' + Math.random().toString(36).slice(2);
}

function sessionIdForThread(thread) {
  const row = thread ? app.threads[thread] : null;
  return (row && row.sessionId) || '';
}

function buildInput(thread, text, clientId) {
  return {
    id: clientId || newInputId(),
    thread,
    sessionId: sessionIdForThread(thread),
    text,
    at: clockNow(),
  };
}

/* Wire format: session_id is the address (thread is the legacy fallback);
   client_id is the message-lifecycle ID echoed back on every input_status
   frame. Delivery always submits. 'sending' is local: only the relay's own
   'accepted' confirms custody. */
function sendInputItem(item) {
  if (!wsLive() || !item || !item.thread || !item.text) return false;
  const ok = wsc.send({
    type: 'input',
    session_id: item.sessionId || sessionIdForThread(item.thread),
    thread: item.thread,
    text: item.text,
    client_id: item.id,
  });
  if (!ok) return false;
  trackDelivery(item.id, {
    thread: item.thread,
    text: item.text,
    status: 'sending',
    at: item.at || clockNow(),
  });
  return true;
}

function savePendingInputs() {
  saveJson(KEYS.pendingInputs, app.pendingInputs.slice(-20));
}

function queueInput(item) {
  app.pendingInputs.push(item);
  trackDelivery(item.id, {
    thread: item.thread,
    text: item.text,
    status: 'offline',
    at: item.at,
  });
  savePendingInputs();
}

export function flushPendingInputs() {
  if (!app.pendingInputs.length || !wsLive()) return;
  const remaining = [];
  let sent = 0;
  app.pendingInputs.forEach((item) => {
    if (sendInputItem(item)) {
      sent++;
      updateMessageStatus(item.thread, item.id, 'sending', '');
    } else {
      remaining.push(item);
    }
  });
  if (sent) {
    app.pendingInputs = remaining;
    savePendingInputs();
  }
}

/* Honest send: the bubble appears immediately but carries its real lifecycle
   state (sending/offline), advanced ONLY by input_status frames keyed on the
   message ID. No 'sent' toast, no fabricated busy state. */
export function sendPrompt(thread, text) {
  const row = app.threads[thread];
  if (row && row.ended) {
    showToast('session ended', 'error');
    return;
  }
  const item = buildInput(thread, text);
  const sent = sendInputItem(item);
  if (!sent) queueInput(item);
  if (row) {
    appendUserMessage(row, text, { id: item.id, status: sent ? 'sending' : 'offline' });
    if (row.yank) row.yank = { ...row.yank, lastUserInput: text };
    row.lastEventAt = clockNow();
  }
  resetDictateUi();
  if (!sent) showToast('offline — queued on this device', 'error');
}

/* The one honest source of per-message truth: input_status frames from the
   relay, keyed by message ID. Updates the matching bubble in place; appends
   only when this device has the text but never rendered a bubble. */
export function applyInputStatus(msg) {
  if (!msg || !msg.id) return;
  const known = app.deliveryStates[msg.id];
  trackDelivery(msg.id, {
    thread: msg.thread || (known && known.thread) || '',
    sessionId: msg.session_id,
    status: msg.status || 'unknown',
    error: msg.error || '',
    pendingCount: msg.pending_count || 0,
    relayAt: msg.at || 0,
  });
  const status = msg.status || '';
  const threadId = msg.thread || (known && known.thread) || '';
  if (updateMessageStatus(threadId, msg.id, status, msg.error || '')) {
    if (status === 'failed') showToast('not delivered — ' + (msg.error || 'delivery failed'), 'error');
    return;
  }
  // No bubble yet (page reloaded mid-flight): materialize it from the cached
  // text once the relay confirms the message really exists.
  if (status !== 'accepted' && status !== 'queued' && status !== 'delivered' && status !== 'landed') return;
  const row = threadId ? app.threads[threadId] : null;
  if (!row) return;
  let text = '';
  const st = app.deliveryStates[msg.id];
  if (st && st.text) text = String(st.text).trim();
  if (!text) {
    for (let i = app.pendingInputs.length - 1; i >= 0; i--) {
      if (app.pendingInputs[i].id === msg.id) {
        text = String(app.pendingInputs[i].text || '').trim();
        break;
      }
    }
  }
  if (text) appendUserMessage(row, text, { id: msg.id, status });
}

function applyOutboxStatus(outbox) {
  (outbox || []).forEach((session) => {
    (session.messages || []).forEach((msg) => {
      if (!msg.id) return;
      trackDelivery(msg.id, {
        thread: msg.thread,
        sessionId: msg.session_id,
        status: 'queued',
        attempts: msg.attempts || 0,
        error: msg.last_error || '',
        relayAt: msg.at || 0,
      });
    });
  });
  // Do NOT upgrade queued → delivered when a message leaves the outbox.
  // Only input_status (delivered / landed) from the relay is authoritative.
}

// ── companion config / quick replies ────────────────────────────────────────

const WAKE_STACK_MAX = 8;
/** Auto-open from list only if the ping is this fresh. */
const WAKE_SOFT_OPEN_MS = 2 * 60 * 1000;
/** Keep Switch targets around long enough to finish the current session. */
const WAKE_STACK_KEEP_MS = 15 * 60 * 1000;

/** Read-only TTL filter — safe during Svelte render (no $state writes). */
function wakeStackLive() {
  const now = clockNow();
  return (app.wakeStack || []).filter(
    (h) => h && h.thread && now - (h.at || 0) <= WAKE_STACK_KEEP_MS,
  );
}

/** Drop expired hints. Call only from event/write paths, never from render. */
function pruneWakeStack() {
  const live = wakeStackLive();
  if (live.length !== (app.wakeStack || []).length) app.wakeStack = live;
}

/** Push/refresh a wake hint. Same thread moves to the end (re-pinged). */
export function pushWakeHint(hint) {
  if (!hint || !hint.thread) return;
  const entry = {
    thread: String(hint.thread),
    at: typeof hint.at === 'number' ? hint.at : clockNow(),
    reason: hint.reason || 'done',
    sessionId: hint.session_id || hint.sessionId || '',
  };
  threadRow(entry.thread); // ensure list row exists for labels
  pruneWakeStack();
  app.wakeStack = app.wakeStack.filter((h) => h.thread !== entry.thread);
  app.wakeStack.push(entry);
  if (app.wakeStack.length > WAKE_STACK_MAX) {
    app.wakeStack = app.wakeStack.slice(-WAKE_STACK_MAX);
  }
  // Soft open: only from the list, and only for a fresh ping.
  if (app.view === 'list' && clockNow() - entry.at <= WAKE_SOFT_OPEN_MS) {
    openThread(entry.thread, false);
  }
}

/** Waiting sessions other than the one currently open (FIFO). */
export function waitingWakeStack() {
  const cur = app.activeThread;
  return wakeStackLive().filter((h) => h.thread && h.thread !== cur);
}

export function nextWakeHint() {
  const waiting = waitingWakeStack();
  return waiting.length ? waiting[0] : null;
}

/** FIFO: open the oldest waiting session. Empty stack → session list
 *  (same as hardware Back / Escape). */
export function switchToNextWake() {
  const next = nextWakeHint();
  if (next) {
    app.wakeStack = (app.wakeStack || []).filter((h) => h.thread !== next.thread);
    openThread(next.thread, false);
    return true;
  }
  if (app.view === 'thread') closeThreadView();
  return false;
}

function dismissWakeForThread(thread) {
  if (!thread) return;
  app.wakeStack = (app.wakeStack || []).filter((h) => h.thread !== thread);
}

export function applyCompanionConfig(msg) {
  if (msg.quick_replies && Array.isArray(msg.quick_replies)) {
    app.companion.quickReplies = msg.quick_replies.filter((s) => s && String(s).trim());
  } else if (msg.quick_replies) {
    app.companion.quickReplies = [];
  }
  if (typeof msg.snooze_until === 'number') app.companion.snoozeUntil = msg.snooze_until;
  if (typeof msg.show_continue === 'boolean') app.companion.showContinue = msg.show_continue;
  if (typeof msg.show_dictate === 'boolean') app.companion.showDictate = msg.show_dictate;
  if (typeof msg.dictate_mic === 'string') {
    app.companion.dictateMic = msg.dictate_mic === 'glasses' ? 'glasses' : 'phone';
  }
  if (typeof msg.default_agent === 'string') {
    const da = msg.default_agent.toLowerCase();
    if (da === 'cursor' || da === 'claude' || da === 'codex') app.pickedAgent = da;
  }
  if (msg.wake_hint && typeof msg.wake_hint === 'object') {
    pushWakeHint(msg.wake_hint);
  }
}

/** One action row on glasses: Switch/Back · Dictate · first chip (≤3). */
export function firstQuickReply() {
  const chips = CS.sessionQuickReplies({
    quickReplies: app.companion.quickReplies,
    showContinue: app.companion.showContinue !== false,
    showDictate: app.companion.showDictate !== false,
  });
  return chips.length ? chips[0] : null;
}

// ── yank / thread lifecycle frames ──────────────────────────────────────────

function applyYank(msg) {
  if (isSnoozing()) return;
  const yank = CS.parseYank(msg);
  const row = threadRow(yank.thread);
  if (msg.label) row.label = msg.label;
  if (msg.agent) row.agent = msg.agent;
  row.busy = false;
  row.ended = false;
  row.yank = yank;
  row.lastEventAt = msg.at || clockNow();
  mergeAgentFromYank(row);
}

function upsertHelloRow(t) {
  const row = threadRow(t.id);
  row.label = t.label || t.id;
  row.agent = t.agent || row.agent || 'generic';
  if (t.session_id) row.sessionId = t.session_id;
  row.ended = false;
  row.lastEventAt = row.lastEventAt || clockNow();
}

// ── dictation (phone SODA capture; mic path chosen per turn) ────────────────

function normalizeMic(mic) {
  return mic === 'glasses' ? 'glasses' : 'phone';
}

function sendDictate(type, thread, text) {
  if (!wsLive() || !thread) return;
  const o = { type, thread, source: 'web' };
  if (text != null && text !== '') o.text = text;
  // Mic path is an Android app setting. Web does not choose per tap.
  wsc.send(o);
}

function sendSessionSignal(type, thread) {
  if (!wsLive() || !thread) return;
  wsc.send({ type, thread, source: 'web' });
}

function resetDictateUi() {
  app.dictate.phase = 'idle';
  app.dictate.partial = '';
  app.dictate.draft = '';
  app.dictate.mic = null;
}

/** Start listening. Capture path comes from the Android dictate-mic setting. */
export function startDictate() {
  const t = app.activeThread ? app.threads[app.activeThread] : null;
  if (!t) { showToast('open a session first', 'error'); return; }
  if (!wsLive()) {
    showToast('relay not connected — wait for reconnect', 'error');
    return;
  }
  app.dictate.phoneThread = t.id;
  app.dictate.mic = normalizeMic(app.companion.dictateMic);
  app.dictate.draft = '';
  app.dictate.partial = '';
  sendSessionSignal('session_focus', t.id);
  sendDictate('dictate_begin', t.id);
  app.dictate.phase = 'listening';
}

/** Same Dictate button starts and finishes. Silence auto-commits; Done = send now. */
export function dictateToggle() {
  if (app.dictate.phase === 'listening') pauseDictate();
  else startDictate();
}

export function pauseDictate() {
  const t = app.activeThread ? app.threads[app.activeThread] : null;
  if (!t) return;
  const text = (app.dictate.draft || app.dictate.partial || '').trim();
  if (!text) {
    sendDictate('dictate_abort', t.id);
    resetDictateUi();
    return;
  }
  sendDictate('dictate_commit', t.id, text);
  resetDictateUi();
}

/** Redo: drop the last user bubble and start listening again. */
export function redoDictate() {
  resetDictateUi();
  if (app.activeThread) {
    const row = app.threads[app.activeThread];
    if (row && row.chatLog && row.chatLog.length) {
      const last = row.chatLog[row.chatLog.length - 1];
      if (last && last.role === 'user') row.chatLog.pop();
      saveChatLogs();
    }
  }
  startDictate();
}

/* Honest dictation end: the relay's dictate_end carries ok/error. A failed
   inject renders as a failure — never a sent bubble. */
export function applyDictateResult(thread, text, ok, errText) {
  app.dictate.phoneThread = null;
  app.dictate.partial = '';
  resetDictateUi();
  const trimmed = (text || '').trim();
  if (!trimmed) return;
  if (ok === false) {
    // Keep the transcript as a draft so the human can retry.
    app.dictate.draft = trimmed;
    showToast('not delivered — ' + (errText || 'inject failed'), 'error');
    return;
  }
  const row = app.threads[thread];
  if (row) {
    appendUserMessage(row, trimmed, { status: 'delivered' });
    if (row.yank) row.yank = { ...row.yank, lastUserInput: trimmed };
    row.lastEventAt = clockNow();
  }
  setTimeout(syncFromHost, 400);
}

// ── views / navigation ──────────────────────────────────────────────────────

function companionScreenForView(which) {
  if (which === 'list') return 'list';
  if (which === 'new') return 'create';
  if (which === 'thread') return 'session';
  return 'idle';
}

/** Tell the phone relay when the web companion owns the glasses display. */
function sendCompanionUi(which) {
  if (!wsLive()) return;
  wsc.send({ type: 'companion_ui', screen: companionScreenForView(which), source: 'web' });
}

/** Heartbeat so Android's ~45s lease does not expire while the web is open. */
const COMPANION_UI_HEARTBEAT_MS = 15_000;

function pulseCompanionUi() {
  try {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
  } catch { /* non-browser */ }
  if (!wsLive()) return;
  sendCompanionUi(app.view);
}

function setUrlForSession(id, compose) {
  try {
    const url = new URL(location.href);
    if (id) {
      url.searchParams.set('session', id);
      if (compose) url.searchParams.set('compose', '1');
      else url.searchParams.delete('compose');
    } else {
      url.searchParams.delete('session');
      url.searchParams.delete('compose');
    }
    history.replaceState({}, '', url.pathname + url.search);
  } catch { /* non-browser env */ }
}

function showView(which) {
  app.view = which;
  sendCompanionUi(which);
}

export function openThread(id, compose) {
  if (app.activeThread && app.activeThread !== id) sendSessionSignal('session_blur', app.activeThread);
  app.activeThread = id;
  app.listFocusedThreadId = id;
  dismissWakeForThread(id);
  setUrlForSession(id, !!compose);
  showView('thread');
  sendSessionSignal('session_focus', id);
  hydrateChatIfEmpty(app.threads[id]);
  hydrateFromRelayHistory(app.threads[id]);
  if (wsLive()) wsc.send({ type: 'hud_yank', thread: id });
  syncFromHost();
}

export function closeThreadView() {
  if (app.dictate.phoneThread && app.activeThread) sendDictate('dictate_abort', app.activeThread);
  app.dictate.phoneThread = null;
  resetDictateUi();
  if (app.activeThread) sendSessionSignal('session_blur', app.activeThread);
  app.activeThread = null;
  setUrlForSession(null, false);
  showView('list');
}

export function openNewSession() {
  app.newDraft = { cwd: defaultCwd(), fromThread: null };
  showView('new');
}

/** Case 3: another session in a folder already on the list. */
export function openNewHere(threadId) {
  const row = threadId ? app.threads[threadId] : null;
  if (!row) {
    openNewSession();
    return;
  }
  if (row.agent) {
    const a = String(row.agent).toLowerCase();
    if (a === 'cursor' || a === 'claude' || a === 'codex') app.pickedAgent = a;
  }
  app.newDraft = { cwd: (row.cwd || '').trim() || defaultCwd(), fromThread: threadId };
  showView('new');
}

export function closeNewSessionView() {
  app.newDraft = { cwd: '', fromThread: null };
  showView('list');
}

export function pickAgent(agent) {
  app.pickedAgent = agent;
}

export function pickFolder(path) {
  app.newDraft = { ...app.newDraft, cwd: (path || '').trim() };
}

function parseDeepLink() {
  try {
    const p = new URLSearchParams(location.search);
    const session = p.get('session');
    if (!session) return null;
    return { session, compose: p.get('compose') === '1' };
  } catch {
    return null;
  }
}

function tryPendingDeepLink() {
  if (!pendingDeepLink || !wsLive()) return;
  const dl = pendingDeepLink;
  pendingDeepLink = null;
  threadRow(dl.session);
  openThread(dl.session, dl.compose);
}

// ── create session ──────────────────────────────────────────────────────────

/* Prefill the working directory: host-configured default wins, else the
   most-recent session's cwd, else the last one used here. */
export function defaultCwd() {
  if (app.host.defaultCwd) return app.host.defaultCwd;
  const recent = visibleThreads().filter((t) => t && t.cwd)[0];
  if (recent && recent.cwd) return recent.cwd;
  return loadString(KEYS.defaultCwd);
}

function folderLeaf(path) {
  const leaf = shortName(expandHomePath(path));
  return leaf || String(path || '').trim() || 'folder';
}

/** Pick-list for New session — leaf labels, full path as value. No typing. */
export function knownFolders() {
  const def = (app.host.defaultCwd || '').trim();
  const seen = new Set();
  const out = [];
  const add = (path, isDefault) => {
    const p = (path || '').trim();
    if (!p || seen.has(p)) return;
    seen.add(p);
    out.push({
      path: p,
      label: folderLeaf(p),
      isDefault: !!(isDefault || (def && p === def)),
    });
  };
  if (def) add(def, true);
  const last = loadString(KEYS.defaultCwd);
  if (last) add(last, false);
  const byRecent = visibleThreads()
    .filter((t) => t && t.cwd)
    .slice()
    .sort((a, b) => (b.lastEventAt || 0) - (a.lastEventAt || 0));
  byRecent.forEach((t) => {
    if (out.length >= MAX_KNOWN_FOLDERS) return;
    add(t.cwd, false);
  });
  return out.slice(0, MAX_KNOWN_FOLDERS);
}

/* Create honesty: the web never invents a thread ID. A session exists only
   when the relay broadcasts thread_started (with session_id). Until then the
   UI shows "starting…"; failures arrive as create_status frames (or the HTTP
   error) and render as errors. Always spawns a new session (New / New here). */
export function startNewThread(cwd, prompt) {
  const text = (prompt || '').trim();
  const dir = (cwd || app.newDraft.cwd || defaultCwd() || '').trim();
  if (dir) saveString(KEYS.defaultCwd, dir);
  if (!text) { showToast('enter a first message', 'error'); return false; }
  if (!wsLive()) { showToast('not connected', 'error'); return false; }
  createHostSession(app.pickedAgent, dir, text);
  return true;
}

function createHostSession(agent, cwd, text) {
  env.fetch('/ambient-link/sessions', {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ agent, cwd, prompt: text }),
  })
    .then((r) => {
      if (!r.ok) {
        return r.text().then((body) => {
          let msg = body;
          try { msg = JSON.parse(body).error || body; } catch { /* raw body */ }
          throw new Error(msg || ('session create failed (' + r.status + ')'));
        });
      }
      app.pendingCreate = { agent, cwd: cwd || '', at: clockNow() };
      showToast('starting ' + agent + '…', 'success');
      showView('list');
    })
    .catch((err) => {
      showToast((err && err.message) || ('could not start ' + agent), 'error');
    });
}

function applyCreateStatus(msg) {
  if (!msg) return;
  if (msg.ok === false) {
    app.pendingCreate = null;
    showToast('agent failed to start — ' + (msg.error || 'unknown error'), 'error');
    return;
  }
  showToast((msg.agent || 'agent') + ' starting — session will appear shortly', 'success');
}

// ── inbound frame dispatch ──────────────────────────────────────────────────

export function handleFrame(msg) {
  if (!msg || !msg.type) return;

  if (msg.type === 'hello') {
    if (wsLive()) wsc.send({ type: 'subscribe', since: (msg.cursor && typeof msg.cursor === 'object') ? msg.cursor : {} });
    reconcileRestoredRows(msg.threads);
    (msg.threads || []).forEach(upsertHelloRow);
    if (msg.relay_debug) {
      app.host.relayDebug = true;
      showToast('relay debug — explicit cards only', 'success');
    }
    syncFromHost();
    flushPendingInputs();
    tryPendingDeepLink();
    sendCompanionUi(app.view);
    return;
  }

  if (msg.type === 'thread_started') {
    const started = threadRow(msg.thread);
    if (msg.label) started.label = msg.label;
    if (msg.agent) started.agent = msg.agent;
    if (msg.cwd) started.cwd = msg.cwd;
    if (msg.session_id) started.sessionId = msg.session_id;
    started.busy = true;
    started.ended = false;
    started.lastEventAt = msg.at || clockNow();
    if (app.pendingCreate && (app.pendingCreate.agent === (msg.agent || '') || !msg.agent)) {
      app.pendingCreate = null;
      openThread(msg.thread, true);
    }
    return;
  }

  if (msg.type === 'thread_ended') {
    const ended = threadRow(msg.thread);
    ended.ended = true;
    ended.busy = false;
    ended.yank = null;
    ended.lastEventAt = msg.at || clockNow();
    reapDeadThreads();
    return;
  }

  if (msg.type === 'thread_busy') {
    const busy = threadRow(msg.thread);
    busy.busy = true;
    busy.ended = false;
    busy.lastEventAt = msg.at || clockNow();
    return;
  }

  if (msg.type === 'thread_idle' || msg.type === 'hud_yank') {
    applyYank(msg);
    return;
  }

  if (msg.type === 'companion_config') {
    applyCompanionConfig(msg);
    return;
  }

  if (msg.type === 'dictate_active' && app.activeThread === msg.thread) {
    if (msg.source && msg.source !== 'web') app.dictate.phoneThread = msg.thread;
    app.dictate.phase = 'listening';
    app.dictate.partial = '';
    return;
  }

  if (msg.type === 'dictate_partial' && app.activeThread === msg.thread && msg.text) {
    app.dictate.partial = msg.text;
    return;
  }

  if (msg.type === 'dictate_end' && app.activeThread === msg.thread) {
    applyDictateResult(msg.thread, msg.text || '', msg.ok !== false, msg.error || '');
    return;
  }

  if (msg.type === 'input_status') {
    applyInputStatus(msg);
    return;
  }

  if (msg.type === 'create_status') {
    applyCreateStatus(msg);
    return;
  }
}

// ── host status poll ────────────────────────────────────────────────────────

export function syncFromHost() {
  return env.fetch('/ambient-link/status', { headers: authHeaders() })
    .then((r) => (r.ok ? r.json() : null))
    .then((data) => {
      if (!data) return;
      app.host.relayDebug = !!data.relay_debug;
      app.host.journal = data.journal || 0;
      app.host.now = data.now || env.now();
      if (typeof data.default_cwd === 'string' && data.default_cwd) app.host.defaultCwd = data.default_cwd;
      app.host.delivery = {};
      (data.delivery || []).forEach((d) => {
        if (d.SessionID) app.host.delivery[d.SessionID] = d;
      });
      applyOutboxStatus(data.outbox || []);
      if (!data.sessions) return;
      const laptopPeer = !!(data.laptop_peer_connected || data.cloud_peer);
      const liveOnHost = data.sessions.some((s) => s.state !== 'DEAD');
      // Cloud relay mux can lag behind the laptop peer — don't let stale DEAD
      // snapshots wipe rows we already have from live WS broadcasts.
      if (laptopPeer && !liveOnHost) {
        app.host.relayConnected = true;
        app.host.laptopPeerConnected = true;
        app.host.liveSessionCount = 0;
        return;
      }
      const bestByThread = {};
      data.sessions.forEach((s) => {
        const id = s.thread_id || s.session_id;
        if (!id) return;
        const cur = bestByThread[id];
        const live = s.state !== 'DEAD';
        if (!cur) { bestByThread[id] = s; return; }
        const curLive = cur.state !== 'DEAD';
        if (live && !curLive) { bestByThread[id] = s; return; }
        if (live === curLive && (s.last_event_at || 0) >= (cur.last_event_at || 0)) {
          bestByThread[id] = s;
        }
      });
      Object.keys(bestByThread).forEach((id) => {
        const s = bestByThread[id];
        const row = threadRow(id);
        if (laptopPeer && s.state === 'DEAD' && row.lastEventAt && !row.ended) return;
        if (s.label) row.label = s.label;
        else if (s.agent && s.cwd) row.label = s.agent + ': ' + (s.cwd.split('/').pop() || s.cwd);
        if (s.agent) row.agent = s.agent;
        row.cwd = s.cwd || row.cwd || '';
        row.sessionId = s.session_id || row.sessionId;
        row.sessionState = s.state || row.sessionState || 'IDLE';
        row.deliverable = sessionDeliverable(s.session_id);
        row.busy = s.state === 'BUSY' || s.state === 'STARTING';
        row.ended = s.state === 'DEAD';
        row.lastEventAt = s.last_event_at || row.lastEventAt || clockNow();
        if (s.last_user_input) row.lastUserInput = s.last_user_input;
        if (s.last_assistant) row.lastAssistant = s.last_assistant;
      });
      reapDeadThreads();
      app.host.relayConnected = true;
      app.host.laptopPeerConnected = laptopPeer;
      app.host.liveSessionCount = Object.keys(bestByThread)
        .filter((id) => bestByThread[id].state !== 'DEAD').length;
      if (app.activeThread) {
        // The open thread may only now have learned its session_id.
        hydrateFromRelayHistory(app.threads[app.activeThread]);
      }
    })
    .catch(() => {
      app.host.relayConnected = false;
    });
}

// ── boot ────────────────────────────────────────────────────────────────────

export function init(overrides) {
  env = { ...env, ...(overrides || {}) };
  TOKEN = loadToken();
  pendingDeepLink = parseDeepLink();
  restoreListSnapshot();
  loadChatLogs();
  app.pendingInputs = loadJson(KEYS.pendingInputs, []).filter?.((x) => x && x.thread && x.text) || [];
  app.deliveryStates = loadJson(KEYS.deliveryStates, {});
  setStatus('connecting');

  wsc = createWsClient({
    url: wsUrl,
    WebSocketImpl: env.WebSocketImpl,
    onOpen: () => {
      setStatus('on');
      sendCompanionUi(app.view);
    },
    onFrame: handleFrame,
    onDown: () => setStatus('off'),
  });
  setStatus('warn');
  wsc.start();
  syncFromHost();
  setInterval(syncFromHost, 15000);
  setInterval(pulseCompanionUi, COMPANION_UI_HEARTBEAT_MS);

  window.addEventListener('online', () => wsc.forceReconnect());
  window.addEventListener('pagehide', () => {
    if (wsLive()) wsc.send({ type: 'companion_ui', screen: 'idle', source: 'web' });
  });
  const regainSessionFocus = () => {
    wsc.forceReconnect();
    if (!app.activeThread || app.view !== 'thread') return;
    if (!wsLive()) return;
    sendSessionSignal('session_focus', app.activeThread);
    sendCompanionUi('thread');
  };
  window.addEventListener('pageshow', regainSessionFocus);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      regainSessionFocus();
      sendCompanionUi(app.view);
    } else if (wsLive()) {
      wsc.send({ type: 'companion_ui', screen: 'idle', source: 'web' });
    }
  });

  window.__ambientOpenNew = openNewSession;
  // Device / WebView probe — phone preview + CDP can read stack state.
  window.__ambientDebug = {
    snapshot: () => ({
      view: app.view,
      activeThread: app.activeThread,
      conn: app.conn,
      wakeStack: (app.wakeStack || []).map((h) => ({
        thread: h.thread,
        reason: h.reason,
        at: h.at,
      })),
      waiting: waitingWakeStack().map((h) => h.thread),
    }),
    switchToNextWake,
    pushWakeHint,
  };
}

// ── test hooks ──────────────────────────────────────────────────────────────

/** Reset module state between vitest cases (no ws, no timers). */
export function resetForTest(opts) {
  opts = opts || {};
  app.view = 'list';
  app.activeThread = null;
  app.threads = {};
  app.threadOrder = [];
  app.conn = 'connecting';
  app.dictate = { phase: 'idle', partial: '', draft: '', phoneThread: null, mic: null };
  app.companion = { quickReplies: [], snoozeUntil: 0, showContinue: true, showDictate: true, dictateMic: 'phone' };
  app.wakeStack = [];
  app.host = {
    relayDebug: false, journal: 0, now: 0, delivery: {}, defaultCwd: '',
    relayConnected: null, laptopPeerConnected: false, liveSessionCount: 0,
  };
  app.pendingInputs = [];
  app.deliveryStates = {};
  app.pickedAgent = 'cursor';
  app.pendingCreate = null;
  app.listFocusedThreadId = null;
  app.newDraft = { cwd: '', fromThread: null };
  app.toast = { text: '', kind: '', seq: 0 };
  if (connGraceTimer) { clearTimeout(connGraceTimer); connGraceTimer = null; }
  pendingConnState = null;
  pendingDeepLink = null;
  TOKEN = '';
  env = {
    fetch: opts.fetch || (() => Promise.resolve({ ok: false })),
    now: opts.now || (() => Date.now()),
    WebSocketImpl: undefined,
  };
  wsc = opts.wsc || null;
}

export function setWsClientForTest(fake) {
  wsc = fake;
}
