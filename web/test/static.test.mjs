import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

describe('static web shell', () => {
  it('index.html includes companion views and scripts', () => {
    const html = readFileSync(join(root, 'index.html'), 'utf8');
    for (const id of [
      'view-threads', 'view-thread', 'view-new', 'w-chips', 'host-panel',
      'btn-refresh', 'btn-pull', 'relay-badge',
    ]) {
      assert.ok(html.includes('id="' + id + '"'), 'missing #' + id);
    }
    assert.ok(html.includes('chipset.js'));
    assert.ok(html.includes('app.js'));
  });

  it('manifest.json is valid PWA metadata', () => {
    const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
    assert.equal(manifest.display, 'standalone');
    assert.ok(manifest.name);
    assert.ok(manifest.start_url);
  });

  it('service worker file exists', () => {
    assert.ok(existsSync(join(root, 'sw.js')));
  });

  it('app.js wires host panel and pull card', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes('pullCard'));
    assert.ok(js.includes('syncFromHost'));
    assert.ok(js.includes('followUpChips'));
    assert.ok(js.includes('host-panel'));
  });
});
