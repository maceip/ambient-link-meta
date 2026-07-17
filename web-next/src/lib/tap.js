// Svelte action port of blocks.js wireImmediateTap.
//
// Glasses browsers focus on first tap and click on second, which reads as
// "I have to double-tap everything" plus click-delay lag. Synthesize the
// click when the tap ENDS instead — but ONLY for a true tap (finger didn't
// move, short press), so scrolling across a card never opens it.
//
// Wired on touch, pointer, AND mouse: the temple touchpad is a pointer
// device and may surface taps as pointer/mouse events with no touch events
// at all. Some Meta builds only move focus on the first gesture (no click /
// no pointerup) — activate on focus while a gesture is in flight so one
// temple tap opens the row. activate() dedupes so overlapping event
// families still fire exactly once.
export function tap(el) {
  let startX = 0;
  let startY = 0;
  let startedAt = 0;
  let lastActivateAt = 0;

  function begin(x, y) {
    startX = x;
    startY = y;
    startedAt = Date.now();
  }

  function clearGesture() {
    startedAt = 0;
  }

  function isTap(x, y) {
    if (!startedAt) return false;
    return Math.hypot(x - startX, y - startY) <= 12 && Date.now() - startedAt <= 700;
  }

  function gestureAlive() {
    return startedAt > 0 && Date.now() - startedAt <= 700;
  }

  function activate() {
    if (el.disabled) return;
    const now = Date.now();
    if (now - lastActivateAt < 350) return; // touch+pointer+mouse double-report
    lastActivateAt = now;
    clearGesture();
    // preventDefault also suppressed native focus; keep the visible focus
    // ring on the element that was actually tapped.
    try { el.focus({ preventScroll: true }); } catch { /* detached */ }
    el.click();
  }

  // The browser may still dispatch its own (trusted) click after our
  // synthetic one; swallow it. el.click() is untrusted and passes.
  const onClick = (e) => {
    if (e.isTrusted && Date.now() - lastActivateAt < 600) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  };
  const onTouchStart = (e) => {
    const t = e.touches[0];
    begin(t.clientX, t.clientY);
  };
  const onTouchEnd = (e) => {
    const t = e.changedTouches && e.changedTouches[0];
    if (!t || !isTap(t.clientX, t.clientY)) {
      clearGesture();
      return;
    }
    e.preventDefault();
    activate();
  };
  const onPointerDown = (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    begin(e.clientX, e.clientY);
  };
  const onPointerUp = (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) {
      clearGesture();
      return;
    }
    if (!isTap(e.clientX, e.clientY)) {
      clearGesture();
      return;
    }
    activate();
  };
  // Fallback when PointerEvent exists in the engine but the temple path
  // only emits mouse events (no pointer* on the target).
  const onMouseDown = (e) => {
    if (e.button !== 0) return;
    if (e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents) return;
    begin(e.clientX, e.clientY);
  };
  const onMouseUp = (e) => {
    if (e.button !== 0) return;
    if (e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents) return;
    if (!isTap(e.clientX, e.clientY)) {
      clearGesture();
      return;
    }
    activate();
  };
  // Meta focus-first: first temple tap focuses the row and never clicks.
  // If focus arrives during an open gesture, open immediately.
  const onFocus = () => {
    if (!gestureAlive()) return;
    activate();
  };

  el.addEventListener('click', onClick, true);
  el.addEventListener('touchstart', onTouchStart, { passive: true });
  el.addEventListener('touchend', onTouchEnd, { passive: false });
  el.addEventListener('focus', onFocus);
  if (window.PointerEvent) {
    el.addEventListener('pointerdown', onPointerDown);
    el.addEventListener('pointerup', onPointerUp);
  }
  el.addEventListener('mousedown', onMouseDown);
  el.addEventListener('mouseup', onMouseUp);

  return {
    destroy() {
      el.removeEventListener('click', onClick, true);
      el.removeEventListener('touchstart', onTouchStart);
      el.removeEventListener('touchend', onTouchEnd);
      el.removeEventListener('focus', onFocus);
      if (window.PointerEvent) {
        el.removeEventListener('pointerdown', onPointerDown);
        el.removeEventListener('pointerup', onPointerUp);
      }
      el.removeEventListener('mousedown', onMouseDown);
      el.removeEventListener('mouseup', onMouseUp);
    },
  };
}

/** Meta HUD button row: exactly one expanded pill — the focused control only.
    Svelte action port of blocks.js wireRbtnGroups (attach to the row). */
export function rbtnGroup(group) {
  function setActive(active) {
    group.querySelectorAll('.blk-rbtn, .rbtn').forEach((b) => {
      b.classList.toggle('blk-rbtn--active', b === active);
      b.classList.toggle('rbtn-active', b === active);
    });
  }
  const onFocusIn = (e) => {
    const btn = e.target.closest && e.target.closest('.blk-rbtn, .rbtn');
    if (btn) setActive(btn);
  };
  const onFocusOut = () => {
    requestAnimationFrame(() => {
      if (!group.contains(document.activeElement)) setActive(null);
    });
  };
  group.addEventListener('focusin', onFocusIn);
  group.addEventListener('focusout', onFocusOut);
  return {
    destroy() {
      group.removeEventListener('focusin', onFocusIn);
      group.removeEventListener('focusout', onFocusOut);
    },
  };
}
