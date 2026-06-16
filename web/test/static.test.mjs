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
    assert.ok(Array.isArray(manifest.icons));
    assert.ok(manifest.icons.length > 0);
  });

  it('app shell uses the Meta Display 600px canvas', () => {
    const html = readFileSync(join(root, 'index.html'), 'utf8');
    const css = readFileSync(join(root, 'styles.css'), 'utf8');
    assert.ok(html.includes('width=device-width'));
    assert.ok(css.includes('width: 600px;'));
    assert.ok(css.includes('height: 600px;'));
  });

  it('service worker caches the complete app shell', () => {
    assert.ok(existsSync(join(root, 'sw.js')));
    const sw = readFileSync(join(root, 'sw.js'), 'utf8');
    for (const asset of ['app.js', 'chipset.js', 'styles.css', 'companion.css', 'manifest.json', 'icon.svg']) {
      assert.ok(sw.includes(asset), 'missing shell asset ' + asset);
    }
    assert.match(sw, /sessions\|ingest/);
  });

  it('app.js wires host panel and pull card', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes('pullCard'));
    assert.ok(js.includes('syncFromHost'));
    assert.ok(js.includes('followUpChips'));
    assert.ok(js.includes('host-panel'));
  });

  it('delivery bookkeeping is not surfaced in visible copy', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes('deliveryStates'));
    assert.ok(js.includes('input_status'));
    assert.ok(!js.includes('inject ready'));
    assert.ok(!js.includes('queued: '));
    assert.ok(!js.includes("showToast('queued'"));
  });

  it('session list avoids duplicate time/status filler', () => {
    const js = readFileSync(join(root, 'app.js'), 'utf8');
    assert.ok(js.includes("meta.textContent = t.agent || 'agent';"));
    assert.ok(js.includes("if (preview.textContent) body.appendChild(preview);"));
    assert.ok(!js.includes("return 'ready';"));
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

  it('new session primary action stays compact', () => {
    const css = readFileSync(join(root, 'companion.css'), 'utf8');
    assert.match(css, /\.new-body \.nav-item\s*\{[^}]*flex: none;/s);
    assert.match(css, /\.new-body \.nav-item\s*\{[^}]*min-height: 88px;/s);
  });

  it('interactive controls use the published Meta Display webapp component tokens', () => {
    const baseCss = readFileSync(join(root, 'styles.css'), 'utf8');
    const companionCss = readFileSync(join(root, 'companion.css'), 'utf8');
    assert.ok(baseCss.includes('--bg-secondary: #0a0a0f;'));
    assert.ok(baseCss.includes('--bg-tertiary: #14141f;'));
    assert.ok(baseCss.includes('--bg-card: #1a1a2e;'));
    assert.ok(baseCss.includes('--accent-primary: #00d4ff;'));
    assert.ok(baseCss.includes('--focus-glow: rgba(0, 212, 255, 0.4);'));
    assert.ok(!baseCss.includes('--control-primary'));
    assert.ok(!baseCss.includes('--surface-raised'));
    assert.match(companionCss, /\.thread-row\s*\{[^}]*min-height: 88px;/s);
    assert.match(companionCss, /\.thread-row\s*\{[^}]*background: var\(--bg-tertiary\);/s);
    assert.match(companionCss, /\.status-tag\s*\{[^}]*border-radius: 20px;/s);
    assert.match(companionCss, /\.chip\s*\{[^}]*min-height: 88px;/s);
    assert.match(companionCss, /\.chip-primary\s*\{[^}]*background: var\(--accent-primary\);/s);
  });

  it('hidden toast does not occupy the session list viewport', () => {
    const css = readFileSync(join(root, 'companion.css'), 'utf8');
    assert.match(css, /\.toast\s*\{[^}]*opacity: 0;/s);
    assert.match(css, /\.toast\s*\{[^}]*visibility: hidden;/s);
    assert.match(css, /\.toast\.visible\s*\{[^}]*opacity: 1;/s);
    assert.match(css, /\.toast\.visible\s*\{[^}]*visibility: visible;/s);
  });
});
