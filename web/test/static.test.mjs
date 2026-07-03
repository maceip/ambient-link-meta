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
      'view-threads', 'view-thread', 'view-new', 'host-panel',
      'conn-dot', 'thread-actions', 'relay-badge', 'list-scroll', 'new-session-pill', 'new-start', 'w-chat',
    ]) {
      assert.ok(html.includes('id="' + id + '"'), 'missing #' + id);
    }
    assert.ok(html.includes('chipset.js'));
    assert.ok(html.includes('content-pipeline.js'));
    assert.ok(html.includes('app.js'));
  });

  it('manifest.json is valid PWA metadata', () => {
    const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
    assert.equal(manifest.display, 'standalone');
    assert.ok(manifest.name);
    assert.ok(manifest.start_url);
    assert.ok(Array.isArray(manifest.icons));
    assert.ok(manifest.icons.length > 0);
  });

  it('app shell uses the Meta Display viewport and full-bleed canvas', () => {
    const html = readFileSync(join(root, 'index.html'), 'utf8');
    const css = readFileSync(join(root, 'styles.css'), 'utf8');
    assert.ok(html.includes('width=device-width'));
    assert.ok(css.includes('width: 100vw;'));
    assert.ok(css.includes('height: 100vh;'));
    assert.ok(css.includes('--bg-primary: #000000;'));
  });

  it('service worker caches the complete app shell', () => {
    assert.ok(existsSync(join(root, 'sw.js')));
    const sw = readFileSync(join(root, 'sw.js'), 'utf8');
    for (const asset of ['app.js', 'chipset.js', 'content-pipeline.js', 'styles.css', 'companion.css', 'manifest.json', 'icon.svg']) {
      assert.ok(sw.includes(asset), 'missing shell asset ' + asset);
    }
    assert.match(sw, /sessions\|ingest/);
  });

  it('app.js declares session list state before use', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.match(js, /var threads = \{\};/);
    assert.match(js, /var threadOrder = \[\];/);
  });

  it('app.js wires host panel and pull card', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes('pullCard'));
    assert.ok(js.includes('syncFromHost'));
    assert.ok(js.includes('companion_ui'));
    assert.ok(js.includes('host-panel'));
    assert.ok(js.includes('renderChatThread'));
    assert.ok(js.includes('AmbientContentPipeline'));
    assert.ok(!js.includes('renderChips'));
  });

  it('delivery bookkeeping is not surfaced in visible copy', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes('deliveryStates'));
    assert.ok(js.includes('input_status'));
    assert.ok(!js.includes('inject ready'));
    assert.ok(!js.includes('queued: '));
    assert.ok(!js.includes("showToast('queued'"));
  });

  it('session list uses pull-to-reveal new session control', () => {
    const html = readFileSync(join(root, 'index.html'), 'utf8');
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(html.includes('id="new-session-pill"'));
    assert.ok(html.includes('id="list-scroll"'));
    assert.ok(!html.includes('id="shelf"'));
    assert.ok(js.includes('wireListPullReveal'));
    assert.ok(js.includes('default_agent'));
    assert.ok(js.includes('openNewSession'));
    assert.ok(js.includes('BLK.renderListItem'));
    assert.ok(js.includes('displayLabel(t)'));
  });

  it('subscribes from the relay hello cursor instead of replaying the full journal', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes('subscribeFromCursor(msg.cursor)'));
    assert.ok(!js.includes("ws.send(JSON.stringify({ type: 'subscribe', since: {} }))"));
  });

  it('companion stylesheet has balanced blocks', () => {
    const css = readFileSync(join(root, 'companion.css'), 'utf8');
    let depth = 0;
    for (const ch of css) {
      if (ch === '{') depth++;
      if (ch === '}') depth--;
      assert.ok(depth >= 0, 'extra closing brace');
    }
    assert.equal(depth, 0);
  });

  it('new session form uses rbtn row for actions', () => {
    const html = readFileSync(join(root, 'index.html'), 'utf8');
    assert.ok(html.includes('id="new-start"'));
    assert.ok(html.includes('blk-rbtn-row'));
  });

  it('interactive controls use the published Meta Display webapp component tokens', () => {
    const baseCss = readFileSync(join(root, 'styles.css'), 'utf8');
    const companionCss = readFileSync(join(root, 'companion.css'), 'utf8');
    assert.ok(baseCss.includes('--bg-secondary: #0d0f13;'));
    assert.ok(baseCss.includes('--bg-tertiary: #1d2025;'));
    assert.ok(baseCss.includes('--accent-primary: #1c84ff;'));
    assert.match(companionCss, /\.ig-list \.thread-row\s*\{[^}]*min-height: 68px;/s);
    assert.match(companionCss, /\.status-tag::before\s*\{[^}]*border-radius: 50%;/s);
    assert.match(baseCss, /--rbtn-size:\s*58px;/);
    assert.match(baseCss, /--rbtn-icon-size:\s*26px;/);
    assert.match(baseCss, /--rbtn-glass-top:/);
    const blocksCss = readFileSync(join(root, 'blocks/blocks.css'), 'utf8');
    assert.match(blocksCss, /\.rbtn \.rbtn-icon svg\s*\{[^}]*var\(--rbtn-icon-size/);
    assert.match(blocksCss, /\.blk-chat-bubble--agent/);
  });

  it('hidden toast does not occupy the session list viewport', () => {
    const css = readFileSync(join(root, 'companion.css'), 'utf8');
    assert.match(css, /\.toast\s*\{[^}]*opacity: 0;/s);
    assert.match(css, /\.toast\s*\{[^}]*visibility: hidden;/s);
    assert.match(css, /\.toast\.visible\s*\{[^}]*opacity: 1;/s);
    assert.match(css, /\.toast\.visible\s*\{[^}]*visibility: visible;/s);
  });
});
