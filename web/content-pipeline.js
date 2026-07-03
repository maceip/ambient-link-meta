// Glasses-safe content pipeline — full text kept for context; display is filtered.
(function (root) {
  'use strict';

  var DISPLAY_MAX_CHARS = 320;
  var DISPLAY_MAX_LINES = 8;
  var HARD_MAX_CHARS = 1200;

  function isDiffLike(text) {
    if (!text) return false;
    if (/^diff --git /m.test(text)) return true;
    if (/^(\+\+\+|---|@@\s)/m.test(text)) return true;
    var lines = text.split('\n');
    var diffish = 0;
    for (var i = 0; i < lines.length && i < 40; i++) {
      if (/^[+\-@\\]/.test(lines[i])) diffish++;
    }
    return diffish >= 12;
  }

  function isCodeDump(text) {
    if (!text) return false;
    var fence = (text.match(/```/g) || []).length;
    return fence >= 4 && text.length > 600;
  }

  function summarizeLarge(text, kind) {
    var trimmed = (text || '').trim();
    if (!trimmed) return '';
    var lines = trimmed.split('\n');
    var head = lines.slice(0, 3).join('\n');
    if (kind === 'diff') {
      return head + '\n… (' + lines.length + ' lines of diff hidden on glasses)';
    }
    if (kind === 'code') {
      return head + '\n… (large code block hidden on glasses)';
    }
    var slice = trimmed.slice(0, DISPLAY_MAX_CHARS);
    var suffix = trimmed.length > DISPLAY_MAX_CHARS ? '…' : '';
    return slice + suffix + '\n(' + trimmed.length + ' chars — collapsed for display)';
  }

  function classify(text) {
    if (!text || !String(text).trim()) {
      return { kind: 'empty', truncated: false, display: '' };
    }
    var raw = String(text);
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

  /** List preview — more text than bubble cap, still filtered for diffs. */
  function preview(text, maxChars) {
    var cap = maxChars || 180;
    var c = classify(text);
    if (c.truncated && (c.kind === 'diff' || c.kind === 'code' || c.kind === 'large')) {
      if (c.kind === 'diff') return 'Large diff · open on Mac for full context';
      if (c.kind === 'code') return 'Large code block · open on Mac for full context';
      return c.display.split('\n')[0].slice(0, cap);
    }
    var d = c.display || '';
    return d.length > cap ? d.slice(0, cap - 1) + '…' : d;
  }

  root.AmbientContentPipeline = {
    DISPLAY_MAX_CHARS: DISPLAY_MAX_CHARS,
    classify: classify,
    filterForDisplay: classify,
    preview: preview,
    isDiffLike: isDiffLike,
  };
})(typeof window !== 'undefined' ? window : globalThis);
