// Chip classification — host sets awaiting: permission | question | done.
// Body copy for glasses is ask/ready only (see glasses-copy.js).

import { displayForYank, stripChipSuffix } from './glasses-copy.js';

export const Awaiting = { PERMISSION: 'permission', QUESTION: 'question', DONE: 'done' };

export function bodyText(yank) {
  return displayForYank(yank || {});
}

export function metaLine(yank) {
  const label = yank.label || yank.thread || 'agent';
  if (yank.awaiting === Awaiting.PERMISSION) return label + ' · needs approval';
  if (yank.awaiting === Awaiting.QUESTION) return label + ' · question';
  if (yank.awaiting === Awaiting.DONE) return label + ' · done';
  return label;
}

/** @deprecated use stripChipSuffix from glasses-copy */
export function stripChipActionSuffix(text) {
  return stripChipSuffix(text);
}

export function parseYank(msg) {
  let awaiting = Awaiting.DONE;
  if (msg.awaiting === 'permission') awaiting = Awaiting.PERMISSION;
  else if (msg.awaiting === 'question') awaiting = Awaiting.QUESTION;
  else if (msg.awaiting === 'done') awaiting = Awaiting.DONE;
  return {
    thread: msg.thread,
    label: msg.label || msg.thread,
    agent: msg.agent || 'generic',
    lastAssistant: msg.lastAssistant || '',
    lastUserInput: msg.lastUserInput || '',
    awaiting,
    permissionPrompt: msg.permissionPrompt || null,
  };
}

const MAX_CHIPS = 3;

export function quickReplyChip(text) {
  const t = String(text || '').trim();
  const label = t.length <= 16 ? t : t.slice(0, 14).trim() + '…';
  return { label, text: t, kind: 'send' };
}

/** Active session compose: custom quick-reply pills only — no continue or
    dictate here (dictate lives in the action bar). */
export function sessionQuickReplies(config) {
  config = config || {};
  const out = [];
  (config.quickReplies || []).forEach((text) => {
    if (out.length >= MAX_CHIPS) return;
    const t = String(text || '').trim();
    if (!t) return;
    out.push(quickReplyChip(t));
  });
  return out;
}
