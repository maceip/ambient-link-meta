// Message-in → state-out for every inbound frame type (PROTOCOL-WEB.md).
import { describe, it, expect, beforeEach } from 'vitest';
import {
  app, handleFrame, resetForTest, setWsClientForTest, threadRow,
  openThread, listThreads, statusBadge,
  pushWakeHint, waitingWakeStack, switchToNextWake,
} from '../src/lib/store.svelte.js';

function fakeWs() {
  const sent = [];
  return {
    sent,
    live: () => true,
    send: (obj) => { sent.push(obj); return true; },
    forceReconnect: () => {},
  };
}

let ws;

beforeEach(() => {
  localStorage.clear();
  resetForTest();
  ws = fakeWs();
  setWsClientForTest(ws);
});

describe('hello', () => {
  it('upserts rows, subscribes from the cursor, and reconciles restored rows', () => {
    // A restored (snapshot) row the relay no longer knows must be dropped.
    const ghost = threadRow('ghost');
    ghost.restored = true;

    handleFrame({
      type: 'hello',
      cursor: { journal: 42 },
      threads: [{ id: 't1', label: 'claude: proj', agent: 'claude', session_id: 'sess-1' }],
    });

    expect(app.threads.ghost).toBeUndefined();
    expect(app.threads.t1.agent).toBe('claude');
    expect(app.threads.t1.sessionId).toBe('sess-1');
    const sub = ws.sent.find((m) => m.type === 'subscribe');
    expect(sub.since).toEqual({ journal: 42 });
  });
});

describe('thread lifecycle frames', () => {
  it('thread_started / thread_busy / thread_ended drive row state and reaping', () => {
    handleFrame({ type: 'thread_started', thread: 't1', agent: 'codex', cwd: '/x/y', session_id: 's1', at: 111 });
    expect(app.threads.t1.busy).toBe(true);
    expect(app.threads.t1.cwd).toBe('/x/y');
    expect(app.threads.t1.lastEventAt).toBe(111);

    handleFrame({ type: 'thread_busy', thread: 't1', at: 222 });
    expect(app.threads.t1.busy).toBe(true);

    handleFrame({ type: 'thread_ended', thread: 't1', at: 333 });
    // Ended rows are reaped — the list can't fill with ghosts.
    expect(app.threads.t1).toBeUndefined();
    expect(listThreads()).toHaveLength(0);
  });

  it('hud_yank updates the card and merges ask/ready into the chat log', () => {
    handleFrame({
      type: 'hud_yank',
      thread: 't1',
      label: 'claude: proj',
      agent: 'claude',
      lastAssistant: 'I finished the refactor.',
      awaiting: 'done',
      at: 500,
    });
    const row = app.threads.t1;
    expect(row.yank.awaiting).toBe('done');
    expect(row.busy).toBe(false);
    // Done turns never dump assistant prose — ready marker only.
    expect(row.chatLog.some((m) => m.role === 'agent' && m.text === 'ready')).toBe(true);
    expect(statusBadge(row)).toBe('done');

    handleFrame({
      type: 'hud_yank',
      thread: 't2',
      lastAssistant: 'Plan looks good.\n\nShip the Switch button now?',
      awaiting: 'question',
      at: 600,
    });
    expect(app.threads.t2.chatLog.some(
      (m) => m.role === 'agent' && m.text === 'Ship the Switch button now?',
    )).toBe(true);
  });

  it('yank is ignored while snoozed', () => {
    app.companion.snoozeUntil = Date.now() + 60_000;
    handleFrame({ type: 'hud_yank', thread: 't1', lastAssistant: 'hi', awaiting: 'done' });
    expect(app.threads.t1).toBeUndefined();
  });
});

describe('companion_config', () => {
  it('updates quick replies and the default agent', () => {
    handleFrame({
      type: 'companion_config',
      quick_replies: ['looks good', '  ', 'explain more'],
      show_dictate: true,
      default_agent: 'codex',
    });
    expect(app.companion.quickReplies).toEqual(['looks good', 'explain more']);
    expect(app.pickedAgent).toBe('codex');
  });
});

describe('wake_hint stack', () => {
  it('stacks multiple waiting sessions and Switch opens FIFO', () => {
    const now = Date.now();
    // Stay on a thread so soft-open does not consume the stack.
    threadRow('current');
    openThread('current');
    pushWakeHint({ thread: 'a', at: now, reason: 'done' });
    pushWakeHint({ thread: 'b', at: now + 1, reason: 'question' });
    pushWakeHint({ thread: 'a', at: now + 2, reason: 'done' }); // re-ping → end
    expect(waitingWakeStack().map((h) => h.thread)).toEqual(['b', 'a']);

    expect(switchToNextWake()).toBe(true);
    expect(app.activeThread).toBe('b');
    expect(waitingWakeStack().map((h) => h.thread)).toEqual(['a']);

    expect(switchToNextWake()).toBe(true);
    expect(app.activeThread).toBe('a');
    expect(waitingWakeStack()).toHaveLength(0);
  });

  it('soft-opens a fresh hint from the list', () => {
    app.view = 'list';
    app.activeThread = null;
    pushWakeHint({ thread: 'fresh', at: Date.now(), reason: 'done' });
    expect(app.activeThread).toBe('fresh');
    expect(app.view).toBe('thread');
    // Opened thread is dismissed from the stack.
    expect(app.wakeStack).toHaveLength(0);
  });

  it('empty stack Switch returns to the session list', () => {
    threadRow('solo');
    openThread('solo');
    expect(app.wakeStack).toHaveLength(0);
    expect(switchToNextWake()).toBe(false);
    expect(app.view).toBe('list');
    expect(app.activeThread).toBeNull();
  });
});

describe('dictation frames', () => {
  beforeEach(() => {
    threadRow('t1');
    openThread('t1');
  });

  it('dictate_active from the phone enters listening; partials stream in', () => {
    handleFrame({ type: 'dictate_active', thread: 't1', source: 'phone' });
    expect(app.dictate.phase).toBe('listening');
    expect(app.dictate.phoneThread).toBe('t1');

    handleFrame({ type: 'dictate_partial', thread: 't1', text: 'hello from' });
    expect(app.dictate.partial).toBe('hello from');
  });

  it('dictate_end ok appends a delivered user bubble and resets the UI', () => {
    handleFrame({ type: 'dictate_active', thread: 't1', source: 'phone' });
    handleFrame({ type: 'dictate_end', thread: 't1', text: 'ship it', ok: true });
    expect(app.dictate.phase).toBe('idle');
    const log = app.threads.t1.chatLog;
    expect(log.some((m) => m.role === 'user' && m.text === 'ship it' && m.status === 'delivered')).toBe(true);
  });

  it('dictate_end failure keeps the transcript as a draft — never a sent bubble', () => {
    handleFrame({ type: 'dictate_active', thread: 't1', source: 'phone' });
    handleFrame({ type: 'dictate_end', thread: 't1', text: 'lost words', ok: false, error: 'inject failed' });
    expect(app.dictate.draft).toBe('lost words');
    expect(app.threads.t1.chatLog.some((m) => m.role === 'user' && m.text === 'lost words')).toBe(false);
    expect(app.toast.text).toContain('not delivered');
  });

  it('frames for a different thread are ignored', () => {
    handleFrame({ type: 'dictate_partial', thread: 'other', text: 'nope' });
    expect(app.dictate.partial).toBe('');
  });
});

describe('create_status', () => {
  it('failure clears the pending create and surfaces the error', () => {
    app.pendingCreate = { agent: 'codex', cwd: '', at: 1 };
    handleFrame({ type: 'create_status', ok: false, error: 'spawn failed' });
    expect(app.pendingCreate).toBeNull();
    expect(app.toast.text).toContain('spawn failed');
    expect(app.toast.kind).toBe('error');
  });

  it('thread_started matching a pending create opens the thread', () => {
    app.pendingCreate = { agent: 'codex', cwd: '', at: 1 };
    handleFrame({ type: 'thread_started', thread: 'tNew', agent: 'codex', session_id: 's9' });
    expect(app.pendingCreate).toBeNull();
    expect(app.activeThread).toBe('tNew');
    expect(app.view).toBe('thread');
  });
});
