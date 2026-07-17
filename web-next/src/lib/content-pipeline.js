// Legacy classifier — kept for call sites that still pass raw blobs.
// Glasses surfaces should prefer glasses-copy.js (ask / ready only).

export const DISPLAY_MAX_CHARS = 320;
const DISPLAY_MAX_LINES = 8;
const HARD_MAX_CHARS = 1200;

export function isDiffLike(text) {
  if (!text) return false;
  if (/^diff --git /m.test(text)) return true;
  if (/^(\+\+\+|---|@@\s)/m.test(text)) return true;
  const lines = text.split('\n');
  let diffish = 0;
  for (let i = 0; i < lines.length && i < 40; i++) {
    if (/^[+\-@\\]/.test(lines[i])) diffish++;
  }
  return diffish >= 12;
}

function isCodeDump(text) {
  if (!text) return false;
  const fence = (text.match(/```/g) || []).length;
  return fence >= 4 && text.length > 600;
}

export function classify(text) {
  if (!text || !String(text).trim()) {
    return { kind: 'empty', truncated: false, display: '' };
  }
  const raw = String(text);
  // Dumps are not shown on glasses — empty display, no head/ellipsis chrome.
  if (isDiffLike(raw)) {
    return { kind: 'diff', truncated: true, display: '' };
  }
  if (isCodeDump(raw)) {
    return { kind: 'code', truncated: true, display: '' };
  }
  if (raw.length > HARD_MAX_CHARS || raw.split('\n').length > DISPLAY_MAX_LINES * 3) {
    return { kind: 'large', truncated: true, display: '' };
  }
  if (raw.length > DISPLAY_MAX_CHARS || raw.split('\n').length > DISPLAY_MAX_LINES) {
    return {
      kind: 'long',
      truncated: true,
      display: raw.slice(0, DISPLAY_MAX_CHARS).trim() + '…',
    };
  }
  return { kind: 'normal', truncated: false, display: raw.trim() };
}

export const filterForDisplay = classify;

/** List preview — first useful line only; no meta commentary. */
export function preview(text, maxChars) {
  const cap = maxChars || 180;
  const c = classify(text);
  const d = (c.display || '').split('\n')[0] || '';
  return d.length > cap ? d.slice(0, cap - 1) + '…' : d;
}
