/**
 * Preflight for deployed-web e2e:
 * 1. Laptop relay up (sessions source of truth)
 * 2. One relay-bridge cycle → public.computer (same path as glasses / deployed UI)
 * 3. Remote /ambient-link/status has live sessions + cloud_peer
 */
import { bridgeOnce, getJSON } from './bridge-helper.mjs';

const LOCAL = (process.env.AMBIENT_LOCAL_RELAY || 'http://127.0.0.1:5181').replace(/\/$/, '');
const REMOTE = (process.env.AMBIENT_RELAY_HOST || 'https://public.computer').replace(/\/$/, '');

async function probe(base) {
  try {
    const h = await fetch(`${base}/healthz`, { signal: AbortSignal.timeout(8000) });
    if (h.ok) return true;
  } catch { /* cloud has no /healthz */ }
  const s = await fetch(`${base}/ambient-link/status`, { signal: AbortSignal.timeout(12_000) });
  return s.ok;
}

export default async function globalSetup() {
  if (!(await probe(LOCAL))) {
    throw new Error(`Laptop relay down at ${LOCAL}. Run: bash scripts/start-host.sh`);
  }
  if (!(await probe(REMOTE))) {
    throw new Error(`Deployed relay unreachable at ${REMOTE}/ambient-link/status`);
  }

  const { live, pushed } = await bridgeOnce();
  console.log(`[e2e setup] bridged ${pushed} ingest frames for ${live} laptop sessions → ${REMOTE}`);

  const remote = await getJSON(REMOTE, '/ambient-link/status');
  if (!remote.cloud_peer) {
    throw new Error(
      `Laptop cloud bridge not connected to ${REMOTE}/ambient-link/relay.\n` +
        'Ensure AMBIENT_LINK_CLOUD=wss://public.computer/ambient-link/relay on the laptop host ' +
        'and wait for "cloud: connected" in ~/Library/Logs/ambient-link-host.log.',
    );
  }

  const liveRemote = (remote.sessions || []).filter((s) => s.state !== 'DEAD');
  if (!liveRemote.length) {
    throw new Error(
      `No live sessions on deployed relay ${REMOTE} after bridge.\n` +
        'Ensure laptop host has live cursor sessions and cloud_peer is connected.',
    );
  }
  console.log(`[e2e setup] deployed relay has ${liveRemote.length} live session(s), cloud_peer=true`);
}
