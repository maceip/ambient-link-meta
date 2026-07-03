import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

describe('AmbientContentPipeline', () => {
  it('collapses diffs and huge payloads for glasses display', () => {
    const src = readFileSync(join(root, 'content-pipeline.js'), 'utf8');
    const sandbox = { globalThis: {} };
    sandbox.window = sandbox.globalThis;
    vm.runInNewContext(src, sandbox);
    const PIPE = sandbox.globalThis.AmbientContentPipeline;
    assert.ok(PIPE);

    const diff = 'diff --git a/foo b/foo\n+++ b/foo\n@@ -1 +1 @@\n-old\n+new\n'.repeat(40);
    const d = PIPE.classify(diff);
    assert.equal(d.truncated, true);
    assert.equal(d.kind, 'diff');

    const long = 'word '.repeat(400);
    const l = PIPE.classify(long);
    assert.equal(l.truncated, true);
    assert.ok(l.display.length < long.length);
  });
});
