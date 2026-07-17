/**
 * @vitest-environment jsdom
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { tap } from '../src/lib/tap.js';

describe('tap action', () => {
  let btn;
  let destroy;
  let clicks;

  beforeEach(() => {
    clicks = 0;
    btn = document.createElement('button');
    btn.className = 'focusable';
    btn.addEventListener('click', () => { clicks += 1; });
    document.body.appendChild(btn);
    destroy = tap(btn).destroy;
  });

  afterEach(() => {
    destroy();
    btn.remove();
  });

  it('opens on pointerup of a short tap (one gesture)', () => {
    btn.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 10, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    btn.dispatchEvent(new PointerEvent('pointerup', {
      clientX: 11, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    expect(clicks).toBe(1);
  });

  it('opens on focus during an in-flight gesture (Meta focus-first)', () => {
    btn.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 10, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    btn.focus();
    expect(clicks).toBe(1);
  });

  it('does not open on programmatic focus alone', () => {
    btn.focus();
    expect(clicks).toBe(0);
  });

  it('ignores a drag', () => {
    btn.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 10, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    btn.dispatchEvent(new PointerEvent('pointerup', {
      clientX: 40, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    expect(clicks).toBe(0);
  });

  it('dedupes overlapping pointer + focus activation', () => {
    btn.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 10, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    btn.focus();
    btn.dispatchEvent(new PointerEvent('pointerup', {
      clientX: 10, clientY: 10, bubbles: true, pointerType: 'touch',
    }));
    expect(clicks).toBe(1);
  });

});
