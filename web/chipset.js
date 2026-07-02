// Chip classification — host sets awaiting: permission | question | done.
(function (root) {
  'use strict';

  var Awaiting = { PERMISSION: 'permission', QUESTION: 'question', DONE: 'done' };

  function stripChipActionSuffix(text) {
    return (text || '').replace(
      /\s+[—–-]\s+(continue|dictate|dismiss)(\s*\|\s*(continue|dictate|dismiss))+\.?\s*$/i,
      '',
    ).trim();
  }

  function bodyText(yank) {
    var parts = [];
    if (yank.awaiting === Awaiting.PERMISSION) {
      var perm = (yank.permissionPrompt && yank.permissionPrompt.trim()) || yank.lastAssistant || '';
      if (perm) parts.push(perm);
    } else if (yank.lastAssistant) {
      parts.push(stripChipActionSuffix(yank.lastAssistant));
    }
    if (yank.lastUserInput && yank.lastUserInput.trim()) {
      parts.push('You: ' + yank.lastUserInput.trim());
    }
    return parts.join('\n\n') || yank.lastAssistant || '';
  }

  function metaLine(yank) {
    var label = yank.label || yank.thread || 'agent';
    if (yank.awaiting === Awaiting.PERMISSION) return label + ' · needs approval';
    if (yank.awaiting === Awaiting.QUESTION) return label + ' · question';
    if (yank.awaiting === Awaiting.DONE) return label + ' · done';
    return label;
  }

  function parseYank(msg) {
    var awaiting = Awaiting.DONE;
    if (msg.awaiting === 'permission') awaiting = Awaiting.PERMISSION;
    else if (msg.awaiting === 'question') awaiting = Awaiting.QUESTION;
    else if (msg.awaiting === 'done') awaiting = Awaiting.DONE;
    return {
      thread: msg.thread,
      label: msg.label || msg.thread,
      agent: msg.agent || 'generic',
      lastAssistant: msg.lastAssistant || '',
      lastUserInput: msg.lastUserInput || '',
      awaiting: awaiting,
      permissionPrompt: msg.permissionPrompt || null,
    };
  }

  var SEND_CONT   = { label: 'continue', text: 'continue', kind: 'send' };
  var MODIFY      = { label: 'modify', text: null, kind: 'modify' };
  var SEND_APPRV  = { label: 'approve', text: 'y', kind: 'send' };
  var SEND_DENY   = { label: 'deny', text: 'n', kind: 'send' };
  var DICTATE     = { label: 'dictate', text: null, kind: 'dictate' };

  function forYank(yank) {
    if (yank.awaiting === Awaiting.PERMISSION) return [SEND_APPRV, SEND_DENY];
    if (yank.awaiting === Awaiting.QUESTION) return [DICTATE];
    return [SEND_CONT, DICTATE];
  }

  function followUpChips(agent) {
    var key = (agent || '').toLowerCase();
    var extras = [];
    if (key.indexOf('codex') >= 0) {
      extras.push({ label: 'fix errors', text: 'fix any errors and try again', kind: 'send' });
    }
    if (key.indexOf('claude') >= 0) {
      extras.push({ label: 'continue task', text: 'continue with the current task', kind: 'send' });
    }
    return [
      { label: 'change it', text: 'actually, change the approach', kind: 'send' },
      { label: 'explain more', text: 'can you explain that in more detail?', kind: 'send' },
      { label: "what's next?", text: 'what should we do next?', kind: 'send' },
    ].concat(extras);
  }

  function chipStyle(kind) {
    if (kind === 'dictate' || kind === 'send') return 'primary';
    return 'outline';
  }

  root.AmbientChipSet = {
    Awaiting: Awaiting,
    bodyText: bodyText,
    metaLine: metaLine,
    parseYank: parseYank,
    forYank: forYank,
    followUpChips: followUpChips,
    chipStyle: chipStyle,
  };
})(typeof window !== 'undefined' ? window : globalThis);
