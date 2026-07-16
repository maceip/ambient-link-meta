// D-pad focus model — port of app.js wireDpadNavigation + focus helpers.
// D-pad moves between BUTTONS only (history is inert chrome); Enter clicks;
// Escape backs out of thread/new views.

function isTextEntry(el) {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable;
}

function viewRoot(view) {
  const id = view === 'list' ? 'view-threads' : view === 'thread' ? 'view-thread' : 'view-new';
  return document.getElementById(id);
}

export function focusablesInView(view) {
  const root = viewRoot(view);
  if (!root || root.classList.contains('hidden')) return [];
  return Array.from(root.querySelectorAll('.focusable:not([disabled])')).filter((el) => {
    if (view === 'new' && (el.id === 'new-prompt' || el.id === 'new-cwd')) return false;
    return !el.classList.contains('hidden') && el.offsetParent !== null;
  });
}

export function moveFocus(view, direction) {
  const items = focusablesInView(view);
  if (!items.length) return;
  const idx = items.indexOf(document.activeElement);
  if (idx === -1) {
    items[0].focus();
    return;
  }
  let next;
  if (direction === 'up' || direction === 'left') {
    next = idx > 0 ? idx - 1 : items.length - 1;
  } else {
    next = idx < items.length - 1 ? idx + 1 : 0;
  }
  items[next].focus();
}

export function focusLastListRow(preferThreadId) {
  const list = document.getElementById('threads');
  if (!list) return;
  const rows = list.querySelectorAll('.thread-row');
  if (!rows.length) return;
  const pick = preferThreadId
    ? list.querySelector('.thread-row[data-thread-id="' + preferThreadId + '"]')
    : null;
  (pick || rows[rows.length - 1]).focus({ preventScroll: true });
}

/** Glasses can't type — land on Dictate (mic), expanded and ready.
    Respond is the #1 action in a session; never focus the scrollback. */
export function focusSessionPrimary() {
  setTimeout(() => {
    const dictate = document.getElementById('dictate');
    if (dictate && !dictate.disabled && dictate.offsetParent !== null) {
      dictate.focus();
      return;
    }
    const items = focusablesInView('thread');
    if (items.length) items[0].focus();
  }, 60);
}

export function focusNewPrimary() {
  setTimeout(() => {
    const start = document.getElementById('new-start');
    const back = document.getElementById('new-back');
    if (start && !start.disabled) start.focus();
    else if (back) back.focus();
  }, 60);
}

/** Wire once at boot. Callbacks: getView(), onEscape(view). */
export function wireDpadNavigation(getView, onEscape) {
  document.addEventListener('keydown', (e) => {
    if (isTextEntry(document.activeElement)) return;
    const key = e.key;
    const view = getView();
    if (key === 'ArrowUp') { e.preventDefault(); moveFocus(view, 'up'); return; }
    if (key === 'ArrowDown') { e.preventDefault(); moveFocus(view, 'down'); return; }
    if (key === 'ArrowLeft') { e.preventDefault(); moveFocus(view, 'left'); return; }
    if (key === 'ArrowRight') { e.preventDefault(); moveFocus(view, 'right'); return; }
    if (key === 'Enter' && document.activeElement.classList.contains('focusable')) {
      e.preventDefault();
      document.activeElement.click();
    }
    if (key === 'Escape' && view !== 'list') {
      e.preventDefault();
      onEscape(view);
    }
  });
}
