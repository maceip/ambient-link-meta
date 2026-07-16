// Chip classification — host sets awaiting: permission | question | done.
// ESM port of web/chipset.js (behavior unchanged).

export const Awaiting = { PERMISSION: 'permission', QUESTION: 'question', DONE: 'done' };

function stripChipActionSuffix(text) {
  return (text || '').replace(
    /\s+[—–-]\s+(continue|dictate|dismiss)(\s*\|\s*(continue|dictate|dismiss))+\.?\s*$/i,
    '',
  ).trim();
}

export function bodyText(yank) {
  const parts = [];
  if (yank.awaiting === Awaiting.PERMISSION) {
    const perm = (yank.permissionPrompt && yank.permissionPrompt.trim()) || yank.lastAssistant || '';
    if (perm) parts.push(perm);
  } else if (yank.lastAssistant) {
    parts.push(stripChipActionSuffix(yank.lastAssistant));
  }
  if (yank.lastUserInput && yank.lastUserInput.trim()) {
    parts.push('You: ' + yank.lastUserInput.trim());
  }
  return parts.join('\n\n') || yank.lastAssistant || '';
}

export function metaLine(yank) {
  const label = yank.label || yank.thread || 'agent';
  if (yank.awaiting === Awaiting.PERMISSION) return label + ' · needs approval';
  if (yank.awaiting === Awaiting.QUESTION) return label + ' · question';
  if (yank.awaiting === Awaiting.DONE) return label + ' · done';
  return label;
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
