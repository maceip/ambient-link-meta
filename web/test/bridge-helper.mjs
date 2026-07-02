/** Production ingest bridge — laptop sessions → deployed relay (not a Playwright mock). */
const LOCAL = (process.env.AMBIENT_LOCAL_RELAY || 'http://127.0.0.1:5181').replace(/\/$/, '');
const REMOTE = (process.env.AMBIENT_RELAY_HOST || 'https://public.computer').replace(/\/$/, '');

export async function getJSON(base, path) {
  const res = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(12_000) });
  if (!res.ok) throw new Error(`${base}${path} → ${res.status}`);
  return res.json();
}

function previewFor(session) {
  if (session.preview) return String(session.preview).slice(0, 200);
  if (session.last_assistant) return String(session.last_assistant).slice(0, 200);
  if (session.state === 'BUSY') return 'thinking…';
  return 'session online';
}

export async function bridgeOnce() {
  const sessData = await getJSON(LOCAL, '/ambient-link/sessions');
  const live = (sessData.sessions || []).filter((s) => s.state !== 'DEAD');
  let pushed = 0;
  for (const s of live) {
    const message = previewFor(s);
    const body = {
      source: 'virtual',
      session_id: s.session_id,
      agent: s.agent,
      cwd: s.cwd,
      observed_at: Date.now(),
    };
    try {
      let res = await fetch(`${REMOTE}/ambient-link/ingest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...body, event_type: 'session_start', payload: {} }),
        signal: AbortSignal.timeout(15_000),
      });
      if (res.ok || res.status === 204) pushed++;
      if (message) {
        res = await fetch(`${REMOTE}/ambient-link/ingest`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            ...body,
            event_type: 'assistant_message',
            payload: { message },
          }),
          signal: AbortSignal.timeout(15_000),
        });
      }
      if (message && (res.ok || res.status === 204)) pushed++;
    } catch (e) {
      console.warn('[bridge]', s.label || s.thread_id, e.message);
    }
  }
  return { live: live.length, pushed };
}
