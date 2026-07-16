// Pure label/time helpers ported from web/app.js — no state, fully testable.

export function truncate(s, n) {
  if (!s) return '';
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

export function shortName(path) {
  const trimmed = (path || '').replace(/[\\/]+$/, '');
  if (!trimmed) return '';
  const parts = trimmed.split(/[\\/]/);
  return parts[parts.length - 1] || trimmed;
}

export function expandHomePath(path) {
  const p = (path || '').trim();
  if (!p || p === '~') return '';
  if (p.startsWith('~/')) return p.slice(2);
  if (p.charAt(0) === '~') return p.slice(1).replace(/^[\\/]+/, '');
  return p;
}

/** List card title — last folder name only, no ~ or full path. */
export function folderTitle(t) {
  let cwd = expandHomePath((t && t.cwd) || '');
  if (!cwd && t && t.label) {
    const bits = String(t.label).split(':');
    if (bits.length > 1) cwd = expandHomePath(bits.slice(1).join(':').trim());
  }
  const leaf = shortName(cwd);
  if (leaf) return leaf;
  const agent = ((t && t.agent) || '').trim();
  return agent || 'session';
}

export function displayLabel(t) {
  const fromFolder = folderTitle(t);
  if (fromFolder && fromFolder !== 'session') return fromFolder;
  let label = ((t && t.label) || '').trim();
  if (!label || /:\s*$/.test(label)) label = ((t && t.agent) || 'session').trim();
  return label || 'session';
}

/** List card preview — one short line (validation gate). */
export function listPreviewPlain(raw) {
  const s = String(raw || '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/https?:\/\/\S+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return truncate(s, 64);
}

export function listTimeLabel(ms, now) {
  if (!ms) return '';
  const nowDate = new Date(now);
  const d = new Date(ms);
  if (nowDate.toDateString() === d.toDateString()) {
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }
  const delta = Math.max(0, now - ms);
  if (delta < 7 * 24 * 60 * 60 * 1000) {
    return d.toLocaleDateString([], { weekday: 'short' });
  }
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

export function relativeTime(ms, now) {
  if (!ms) return '';
  const delta = Math.max(0, now - ms);
  if (delta > 30 * 24 * 60 * 60 * 1000) return '';
  const sec = Math.floor(delta / 1000);
  if (sec < 45) return 'now';
  const min = Math.floor(sec / 60);
  if (min < 60) return min + 'm';
  const hr = Math.floor(min / 60);
  if (hr < 24) return hr + 'h';
  return Math.floor(hr / 24) + 'd';
}

export function agentClass(agent) {
  const a = (agent || '').toLowerCase();
  if (a === 'cursor') return 'cursor';
  if (a === 'claude') return 'claude';
  if (a === 'codex' || a === 'openai') return 'codex';
  return 'generic';
}

export function chatAgentLabel(agent) {
  const a = (agent || 'agent').toLowerCase();
  if (a === 'cursor') return 'Cursor';
  if (a === 'claude') return 'Claude';
  if (a === 'codex' || a === 'openai') return 'Codex';
  const raw = agent || 'agent';
  return raw.charAt(0).toUpperCase() + raw.slice(1);
}

export function chatStatusLabel(status, error) {
  switch (status) {
    case 'sending': return 'sending…';
    case 'offline': return 'waiting for connection';
    case 'accepted': return 'relay accepted';
    case 'queued': return 'queued for agent';
    case 'delivered': return 'delivered';
    case 'landed': return '✓ landed';
    case 'failed': return 'failed' + (error ? ' — ' + error : '');
    default: return status;
  }
}

export function agentStatusLabel(state) {
  switch (state) {
    case 'dead': return 'crashed';
    case 'unreachable': return 'unreachable';
    case 'offline': return 'offline';
    case 'busy': return 'working';
    case 'permission': return 'permission';
    case 'question': return 'question';
    case 'done': return 'ready';
    case 'idle': return 'idle';
    default: return state;
  }
}

/* Uniform round zinc avatars (agents/assets → icons/agents/zinc/). */
const ZINC_ICONS = ['amp', 'apple', 'claude', 'claudecode', 'cline', 'codex', 'copilot',
  'cursor', 'deepseek', 'githubcopilot', 'goose', 'grok', 'hermesagent', 'huggingface',
  'hunyuan', 'kimi', 'longcat', 'manus', 'mcp', 'meta', 'metaai', 'microsoft',
  'midjourney', 'minimax', 'mistral', 'openclaw', 'openhands', 'poe', 'qwen',
  'replit', 'roocode', 'trae', 'venice'];

export function zincIconFor(agent) {
  let key = agentClass(agent);
  if (key === 'generic') key = 'mcp';
  return ZINC_ICONS.includes(key) ? 'icons/agents/zinc/' + key + '.png' : '';
}
