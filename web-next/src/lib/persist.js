// localStorage helpers — same keys as the legacy app so an in-place upgrade
// keeps the user's token, theme, snapshot, chat logs, and offline queue.

export const KEYS = {
  token: 'ambient-link:token',
  theme: 'ambient-link:theme',
  listSnapshot: 'ambient-link:list-snapshot',
  chatLogs: 'ambient-link:chat-logs-v3',
  pendingInputs: 'ambient-link:pending-inputs',
  deliveryStates: 'ambient-link:delivery-states',
  defaultCwd: 'al_default_cwd',
};

export function loadJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    const v = JSON.parse(raw);
    return v == null ? fallback : v;
  } catch {
    return fallback;
  }
}

export function saveJson(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* full/blocked */ }
}

export function loadString(key) {
  try { return localStorage.getItem(key) || ''; } catch { return ''; }
}

export function saveString(key, value) {
  try { localStorage.setItem(key, value); } catch { /* full/blocked */ }
}
