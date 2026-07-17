import { describe, it, expect, beforeEach } from 'vitest';
import {
  app, resetForTest, setWsClientForTest, threadRow,
  knownFolders, openNewSession, openNewHere, closeNewSessionView,
  startNewThread, pickFolder, defaultCwd,
} from '../src/lib/store.svelte.js';
import { KEYS, saveString } from '../src/lib/persist.js';

function fakeWs() {
  const sent = [];
  return {
    sent,
    live: () => true,
    send: (obj) => { sent.push(obj); return true; },
    forceReconnect: () => {},
  };
}

let fetches;

beforeEach(() => {
  localStorage.clear();
  fetches = [];
  resetForTest({
    fetch: (url, opts) => {
      fetches.push({ url, opts });
      return Promise.resolve({
        ok: true,
        text: async () => '',
        json: async () => ({}),
      });
    },
  });
  setWsClientForTest(fakeWs());
  app.conn = 'on';
});

describe('knownFolders', () => {
  it('lists host default, last-used, and session cwds as leaf labels', () => {
    app.host.defaultCwd = '/Users/mac/Projects/default-app';
    saveString(KEYS.defaultCwd, '/Users/mac/Projects/last-used');
    const a = threadRow('t1');
    a.cwd = '/Users/mac/ambient-link-meta';
    a.lastEventAt = 200;
    a.ended = false;
    a.busy = true;
    const b = threadRow('t2');
    b.cwd = '/Users/mac/Projects/default-app';
    b.lastEventAt = 100;
    b.ended = false;
    b.busy = true;

    const folders = knownFolders();
    expect(folders.map((f) => f.label)).toContain('default-app');
    expect(folders.map((f) => f.label)).toContain('last-used');
    expect(folders.map((f) => f.label)).toContain('ambient-link-meta');
    expect(folders.find((f) => f.path === app.host.defaultCwd).isDefault).toBe(true);
    expect(folders.length).toBeLessThanOrEqual(6);
  });
});

describe('New / New here', () => {
  it('openNewSession prefills draft cwd and shows new view', () => {
    app.host.defaultCwd = '/Users/mac/work';
    openNewSession();
    expect(app.view).toBe('new');
    expect(app.newDraft.cwd).toBe('/Users/mac/work');
  });

  it('openNewHere copies agent and cwd from the list row', () => {
    const row = threadRow('sess');
    row.agent = 'claude';
    row.cwd = '/Users/mac/ambient-link-meta';
    openNewHere('sess');
    expect(app.view).toBe('new');
    expect(app.pickedAgent).toBe('claude');
    expect(app.newDraft.cwd).toBe('/Users/mac/ambient-link-meta');
    expect(app.newDraft.fromThread).toBe('sess');
  });

  it('closeNewSessionView returns to list', () => {
    openNewSession();
    closeNewSessionView();
    expect(app.view).toBe('list');
  });

  it('startNewThread POSTs /ambient-link/sessions with picked folder', async () => {
    pickFolder('/Users/mac/proj');
    app.pickedAgent = 'codex';
    const ok = startNewThread('/Users/mac/proj', 'clone github.com/foo/bar and set up');
    expect(ok).toBe(true);
    await Promise.resolve();
    expect(fetches.length).toBe(1);
    expect(fetches[0].url).toContain('/ambient-link/sessions');
    const body = JSON.parse(fetches[0].opts.body);
    expect(body).toEqual({
      agent: 'codex',
      cwd: '/Users/mac/proj',
      prompt: 'clone github.com/foo/bar and set up',
    });
  });

  it('defaultCwd prefers host then recent session', () => {
    expect(defaultCwd()).toBe('');
    const row = threadRow('r');
    row.cwd = '/tmp/recent';
    row.lastEventAt = 1;
    expect(defaultCwd()).toBe('/tmp/recent');
    app.host.defaultCwd = '/tmp/host';
    expect(defaultCwd()).toBe('/tmp/host');
  });
});
