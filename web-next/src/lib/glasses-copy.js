// Glasses-native copy: show asks and readiness, never essays/diffs/tables.
// Full agent text stays on the Mac; waveguide + web session only need decisions.

import { isDiffLike } from './content-pipeline.js';

export const ASK_MAX_CHARS = 120;
export const ASK_MAX_LINES = 3;
export const READY_USER_CHARS = 40;

const CHIP_SUFFIX = /\s+[—–-]\s+(continue|dictate|dismiss)(\s*\|\s*(continue|dictate|dismiss))+\.?\s*$/i;

export function stripChipSuffix(text) {
  return String(text || '').replace(CHIP_SUFFIX, '').trim();
}

function isCodeDump(text) {
  if (!text) return false;
  const fence = (text.match(/```/g) || []).length;
  return fence >= 4 && text.length > 600;
}

/** Markdown-ish tables / pipe grids — useless on 600×600. */
export function isTableLike(text) {
  if (!text) return false;
  const lines = String(text).split('\n').filter((l) => l.trim());
  let pipes = 0;
  for (let i = 0; i < lines.length && i < 24; i++) {
    if ((lines[i].match(/\|/g) || []).length >= 2) pipes++;
  }
  return pipes >= 3;
}

export function isDump(text) {
  const t = String(text || '');
  return isDiffLike(t) || isCodeDump(t) || isTableLike(t);
}

export function clampAsk(text) {
  let s = String(text || '').trim();
  if (!s) return '';
  const lines = s.split('\n').slice(0, ASK_MAX_LINES).join('\n').trim();
  if (lines.length <= ASK_MAX_CHARS) return lines;
  return lines.slice(0, ASK_MAX_CHARS - 1).trimEnd() + '…';
}

/**
 * Pull the actual question out of agent prose.
 * Prefer last "?…" sentence; else last short paragraph; else clamped head.
 */
export function extractAsk(text) {
  const cleaned = stripChipSuffix(text);
  if (!cleaned) return '';
  if (isDump(cleaned)) return '';

  const chunks = cleaned.split(/(?<=[.!?])(?:\s+|\n+)/);
  for (let i = chunks.length - 1; i >= 0; i--) {
    const s = (chunks[i] || '').trim();
    if (s.includes('?')) return clampAsk(s);
  }

  const lines = cleaned.split('\n').map((l) => l.trim()).filter(Boolean);
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].includes('?')) return clampAsk(lines[i]);
  }

  const paras = cleaned.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  const last = paras[paras.length - 1] || cleaned;
  if (last.length <= ASK_MAX_CHARS && last.split('\n').length <= ASK_MAX_LINES) {
    return last;
  }
  return clampAsk(cleaned);
}

function readyLine(lastUserInput) {
  const user = String(lastUserInput || '').trim().split('\n')[0] || '';
  if (!user) return 'ready';
  const short = user.length > READY_USER_CHARS
    ? user.slice(0, READY_USER_CHARS - 1).trimEnd() + '…'
    : user;
  return 'ready · last: ' + short;
}

/**
 * HUD / meta / list / chat body for a yank.
 * permission → prompt only; question → ask extract; done → ready (± last user).
 */
export function displayForYank(yank) {
  if (!yank) return '';
  const awaiting = yank.awaiting || 'done';

  if (awaiting === 'permission') {
    const raw = (yank.permissionPrompt && String(yank.permissionPrompt).trim())
      || stripChipSuffix(yank.lastAssistant)
      || '';
    return clampAsk(raw) || 'needs approval';
  }

  if (awaiting === 'question') {
    return extractAsk(yank.lastAssistant) || 'question';
  }

  // done / idle — never dump assistant prose on glasses
  return readyLine(yank.lastUserInput);
}

/**
 * History / unknown-awaiting agent turns: keep short asks, drop dumps,
 * collapse long prose to "ready".
 */
export function displayAgentHistory(text) {
  const cleaned = stripChipSuffix(text);
  if (!cleaned) return '';
  if (isDump(cleaned)) return '';
  if (cleaned.includes('?')) return extractAsk(cleaned);
  if (cleaned.length <= ASK_MAX_CHARS && cleaned.split('\n').length <= ASK_MAX_LINES) {
    return cleaned;
  }
  return 'ready';
}
