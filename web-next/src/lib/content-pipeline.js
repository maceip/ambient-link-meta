// Glasses-safe content pipeline — full text kept for context; display is
// filtered. ESM port of web/content-pipeline.js (byte-for-byte behavior).

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

function summarizeLarge(text, kind) {
  const trimmed = (text || '').trim();
  if (!trimmed) return '';
  const lines = trimmed.split('\n');
  const head = lines.slice(0, 3).join('\n');
  // Diff/code markers replace hidden content (one line, load-bearing);
  // plain-text truncation is just "…" — no char-count meta line, vertical
  // space on the waveguide is too scarce for commentary.
  if (kind === 'diff') {
    return head + '\n… (' + lines.length + ' diff lines)';
  }
  if (kind === 'code') {
    return head + '\n…';
  }
  const slice = trimmed.slice(0, DISPLAY_MAX_CHARS);
  return slice + (trimmed.length > DISPLAY_MAX_CHARS ? '…' : '');
}

export function classify(text) {
  if (!text || !String(text).trim()) {
    return { kind: 'empty', truncated: false, display: '' };
  }
  const raw = String(text);
  if (isDiffLike(raw)) {
    return { kind: 'diff', truncated: true, display: summarizeLarge(raw, 'diff') };
  }
  if (isCodeDump(raw)) {
    return { kind: 'code', truncated: true, display: summarizeLarge(raw, 'code') };
  }
  if (raw.length > HARD_MAX_CHARS || raw.split('\n').length > DISPLAY_MAX_LINES * 3) {
    return { kind: 'large', truncated: true, display: summarizeLarge(raw, 'large') };
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

/** List preview — more text than bubble cap, still filtered for diffs. */
export function preview(text, maxChars) {
  const cap = maxChars || 180;
  const c = classify(text);
  if (c.truncated && (c.kind === 'diff' || c.kind === 'code' || c.kind === 'large')) {
    if (c.kind === 'diff') return 'Large diff · open on Mac for full context';
    if (c.kind === 'code') return 'Large code block · open on Mac for full context';
    return c.display.split('\n')[0].slice(0, cap);
  }
  const d = c.display || '';
  return d.length > cap ? d.slice(0, cap - 1) + '…' : d;
}
