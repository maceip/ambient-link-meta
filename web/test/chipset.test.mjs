import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { CS } from './load-chipset.mjs';

describe('AmbientChipSet', () => {
  it('parseYank maps awaiting kinds', () => {
    const y = CS.parseYank({
      thread: 'cursor-abc',
      label: 'cursor',
      agent: 'cursor',
      awaiting: 'question',
      lastAssistant: 'Ship it?',
    });
    assert.equal(y.awaiting, CS.Awaiting.QUESTION);
    assert.equal(y.thread, 'cursor-abc');
  });

  it('bodyText skips empty redacted-only assistant', () => {
    const y = CS.parseYank({
      thread: 't',
      awaiting: 'done',
      lastAssistant: '',
      lastUserInput: 'hello',
    });
    assert.match(CS.bodyText(y), /You: hello/);
  });

  it('forYank permission shows approve/deny', () => {
    const chips = CS.forYank({ awaiting: CS.Awaiting.PERMISSION });
    assert.equal(chips.map((c) => c.label).join(','), 'approve,deny');
  });

  it('forYank question shows dictate only', () => {
    const chips = CS.forYank({ awaiting: CS.Awaiting.QUESTION });
    assert.equal(chips.map((c) => c.label).join(','), 'dictate');
  });

  it('forYank done shows continue/dictate', () => {
    const chips = CS.forYank({ awaiting: CS.Awaiting.DONE });
    assert.equal(chips.map((c) => c.label).join(','), 'continue,dictate');
  });

  it('forYank done hides continue when configured off', () => {
    const chips = CS.forYank(
      { awaiting: CS.Awaiting.DONE },
      { showContinue: false, showDictate: true, quickReplies: ['looks good'] },
    );
    assert.equal(chips.map((c) => c.label).join(','), 'dictate,looks good');
  });

  it('forYank merges quick replies up to three chips', () => {
    const chips = CS.forYank(
      { awaiting: CS.Awaiting.DONE },
      { showContinue: false, showDictate: false, quickReplies: ['a', 'b', 'c', 'd'] },
    );
    assert.equal(chips.length, 3);
    assert.equal(chips.map((c) => c.text).join(','), 'a,b,c');
  });

  it('followUpChips adds agent-specific extras', () => {
    const codex = CS.followUpChips('codex').map((c) => c.label);
    assert.ok(codex.includes('fix errors'));
    const claude = CS.followUpChips('claude').map((c) => c.label);
    assert.ok(claude.includes('continue task'));
  });

  it('metaLine reflects awaiting state', () => {
    const y = CS.parseYank({
      thread: 't',
      label: 'cursor: proj',
      awaiting: 'permission',
      permissionPrompt: 'run tests?',
    });
    assert.match(CS.metaLine(y), /needs approval/);
  });
});
