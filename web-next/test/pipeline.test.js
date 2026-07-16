// Ports must behave exactly like the legacy modules they replace.
import { describe, it, expect } from 'vitest';
import { classify, preview } from '../src/lib/content-pipeline.js';
import { sessionQuickReplies, parseYank, quickReplyChip, Awaiting } from '../src/lib/chipset.js';
import { folderTitle, displayLabel, listPreviewPlain, relativeTime } from '../src/lib/format.js';

describe('content pipeline', () => {
  it('passes short text through untouched', () => {
    expect(classify('hello world')).toEqual({ kind: 'normal', truncated: false, display: 'hello world' });
  });

  it('collapses diffs to a head + count line', () => {
    const diff = 'diff --git a/x b/x\n' + Array.from({ length: 30 }, (_, i) => '+line ' + i).join('\n');
    const c = classify(diff);
    expect(c.kind).toBe('diff');
    expect(c.display).toContain('diff lines)');
    expect(preview(diff)).toBe('Large diff · open on Mac for full context');
  });

  it('truncates long prose with a bare ellipsis — no meta commentary', () => {
    const long = 'x'.repeat(400);
    const c = classify(long);
    expect(c.kind).toBe('long');
    expect(c.display.endsWith('…')).toBe(true);
    expect(c.display).not.toContain('chars');
  });
});

describe('chipset', () => {
  it('caps quick replies at 3 and drops blanks', () => {
    const chips = sessionQuickReplies({ quickReplies: ['a', ' ', 'b', 'c', 'd'] });
    expect(chips.map((c) => c.text)).toEqual(['a', 'b', 'c']);
  });

  it('long chip text keeps full payload, truncated label', () => {
    const chip = quickReplyChip('please explain that in much more detail');
    expect(chip.text).toBe('please explain that in much more detail');
    expect(chip.label.length).toBeLessThanOrEqual(16);
    expect(chip.label.endsWith('…')).toBe(true);
  });

  it('parseYank normalizes awaiting states', () => {
    expect(parseYank({ thread: 't', awaiting: 'permission' }).awaiting).toBe(Awaiting.PERMISSION);
    expect(parseYank({ thread: 't', awaiting: 'bogus' }).awaiting).toBe(Awaiting.DONE);
  });
});

describe('format', () => {
  it('folderTitle prefers the cwd leaf and falls back through label to agent', () => {
    expect(folderTitle({ cwd: '/Users/me/proj/webapp' })).toBe('webapp');
    expect(folderTitle({ label: 'claude: ~/x/api' })).toBe('api');
    expect(folderTitle({ agent: 'codex' })).toBe('codex');
    expect(displayLabel({ label: 'claude: ', agent: 'claude' })).toBe('claude');
  });

  it('listPreviewPlain strips links and collapses whitespace to one line', () => {
    expect(listPreviewPlain('see [docs](https://x.y)\n\nand https://foo.bar now'))
      .toBe('see docs and now');
  });

  it('relativeTime buckets', () => {
    const now = 1_000_000_000_000;
    expect(relativeTime(now - 10_000, now)).toBe('now');
    expect(relativeTime(now - 5 * 60_000, now)).toBe('5m');
    expect(relativeTime(now - 3 * 3_600_000, now)).toBe('3h');
    expect(relativeTime(now - 2 * 86_400_000, now)).toBe('2d');
  });
});
