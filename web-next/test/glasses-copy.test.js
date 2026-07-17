import { describe, it, expect } from 'vitest';
import {
  extractAsk, displayForYank, displayAgentHistory, isDump, clampAsk,
} from '../src/lib/glasses-copy.js';

describe('extractAsk', () => {
  it('prefers the last question sentence', () => {
    const text = 'I looked at the layout.\n\nShould we drop the Back button?\nAlso notes.';
    expect(extractAsk(text)).toBe('Should we drop the Back button?');
  });

  it('clamps long asks', () => {
    const q = 'Should we ' + 'really '.repeat(40) + 'do this?';
    const out = extractAsk(q);
    expect(out.endsWith('…')).toBe(true);
    expect(out.length).toBeLessThanOrEqual(120);
  });

  it('returns empty for diffs', () => {
    const diff = 'diff --git a/x b/x\n' + Array.from({ length: 30 }, (_, i) => '+line ' + i).join('\n');
    expect(isDump(diff)).toBe(true);
    expect(extractAsk(diff)).toBe('');
  });
});

describe('displayForYank', () => {
  it('permission uses prompt only', () => {
    expect(displayForYank({
      awaiting: 'permission',
      permissionPrompt: 'Allow edit src/app.ts?',
      lastAssistant: 'long reasoning\n\nAllow edit src/app.ts?',
    })).toBe('Allow edit src/app.ts?');
  });

  it('question extracts ask, ignores preceding essay', () => {
    expect(displayForYank({
      awaiting: 'question',
      lastAssistant: 'Here is a plan with many steps.\n\nShip the Switch button now?',
    })).toBe('Ship the Switch button now?');
  });

  it('done is ready, not assistant prose', () => {
    expect(displayForYank({
      awaiting: 'done',
      lastAssistant: 'I refactored twelve files and fixed the tests.',
      lastUserInput: 'fix the layout',
    })).toBe('ready · last: fix the layout');
  });

  it('done without user context is bare ready', () => {
    expect(displayForYank({
      awaiting: 'done',
      lastAssistant: 'All done.',
    })).toBe('ready');
  });
});

describe('displayAgentHistory', () => {
  it('keeps short asks, drops dumps, collapses essays to ready', () => {
    expect(displayAgentHistory('Ship it now?')).toBe('Ship it now?');
    const diff = 'diff --git a/x b/x\n' + Array.from({ length: 30 }, (_, i) => '+x' + i).join('\n');
    expect(displayAgentHistory(diff)).toBe('');
    expect(displayAgentHistory('x'.repeat(400))).toBe('ready');
  });
});

describe('clampAsk', () => {
  it('limits lines', () => {
    expect(clampAsk('a\nb\nc\nd\ne').split('\n')).toHaveLength(3);
  });
});
