// Consumer client. Speaks the single contract (ambient.proto, JSON wire).
const PROTOCOL_VERSION = 1;

const sessions = new Map(); // handle -> SessionState
const pendingMarker = new Map(); // handle -> text we just sent (to detect "landed")
let ws;

const listEl = document.getElementById("list");
const emptyEl = document.getElementById("empty");
const connEl = document.getElementById("conn");

function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  setConn("connecting", "connecting…");

  ws.onopen = () => {
    setConn("open", "live");
    ws.send(JSON.stringify({ hello: { protocol_version: PROTOCOL_VERSION, role: "CONSUMER", node: "web" } }));
    ws.send(JSON.stringify({ subscribe: { since: {} } }));
  };
  ws.onclose = () => { setConn("closed", "offline"); setTimeout(connect, 1500); };
  ws.onerror = () => ws.close();
  ws.onmessage = (e) => {
    let f; try { f = JSON.parse(e.data); } catch { return; }
    if (f.state) onState(f.state);
    if (f.event) onEvent(f.event);
  };
}

function setConn(state, text) { connEl.dataset.state = state; connEl.textContent = text; }

function onState(st) {
  sessions.set(st.ref.handle, st);
  render();
}

function onEvent(ev) {
  // Detect our reply landing: a FROM_HUMAN event matching what we sent, consumed.
  if (ev.direction === "FROM_HUMAN" && ev.consumed) {
    const want = pendingMarker.get(ev.session_handle);
    if (want && ev.text === want) {
      pendingMarker.delete(ev.session_handle);
      flashLanded(ev.session_handle);
    }
  }
}

function statusClass(s) { return (s || "DONE").toLowerCase(); }

function render() {
  const items = [...sessions.values()].sort((a, b) => (b.last_event_ts || 0) - (a.last_event_ts || 0));
  emptyEl.style.display = items.length ? "none" : "block";

  for (const st of items) {
    const id = "card-" + cssId(st.ref.handle);
    let card = document.getElementById(id);
    if (!card) { card = document.createElement("div"); card.id = id; listEl.appendChild(card); }
    card.className = "card " + (st.status === "WAITING" ? "waiting" : "");

    const agent = st.ref.agent || "agent";
    const initial = agent[0] ? agent[0].toUpperCase() : "?";
    const sc = statusClass(st.status);

    card.innerHTML = `
      <div class="row">
        <div class="icon ${agent}">${initial}</div>
        <div class="meta">
          <div class="name">${esc(cap(agent))} <span class="sub">· ${esc(st.ref.title || "")}</span></div>
          <div class="sub">${esc(st.location || "")}</div>
        </div>
        <div class="chip ${sc}"><span class="dot"></span>${esc(st.status || "")}</div>
      </div>
      <div class="preview ${st.awaiting_permission ? "perm" : ""}">${esc(st.preview || "")}</div>
      ${st.replyable ? replyHTML(st) : observeHTML()}
      <div class="landed" id="landed-${cssId(st.ref.handle)}">✓ landed — the agent took your message in</div>
    `;

    if (st.replyable) wireReply(card, st);
  }

  // remove cards for sessions no longer present
  for (const child of [...listEl.children]) {
    const handle = child.id.replace(/^card-/, "");
    if (![...sessions.keys()].some(h => cssId(h) === handle)) child.remove();
  }
}

function replyHTML(st) {
  const ph = st.awaiting_permission ? "Approve or reply…" : "Reply to this agent…";
  return `<div class="reply">
      <input type="text" placeholder="${esc(ph)}" />
      <button class="send">Send</button>
    </div>`;
}
function observeHTML() {
  return `<div class="observe"><span class="dot"></span>live · observing</div>`;
}

function wireReply(card, st) {
  const input = card.querySelector("input");
  const btn = card.querySelector(".send");
  const send = () => {
    const text = input.value.trim();
    if (!text) return;
    pendingMarker.set(st.ref.handle, text);
    ws.send(JSON.stringify({ event: { session_handle: st.ref.handle, direction: "FROM_HUMAN", kind: "HUMAN_MESSAGE", text } }));
    input.value = "";
    input.blur();
  };
  btn.onclick = send;
  input.onkeydown = (e) => { if (e.key === "Enter") send(); };
}

function flashLanded(handle) {
  const el = document.getElementById("landed-" + cssId(handle));
  if (!el) return;
  el.classList.add("show");
  setTimeout(() => el.classList.remove("show"), 2600);
}

function cssId(s) { return s.replace(/[^a-zA-Z0-9_-]/g, "_"); }
function cap(s) { return s ? s[0].toUpperCase() + s.slice(1) : s; }
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }

connect();
