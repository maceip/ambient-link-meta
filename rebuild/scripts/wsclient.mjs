// Real over-the-wire consumer check: connects to the running agent node, prints
// live session status (R5), sends a reply to a replyable WAITING session, and
// confirms it lands as a consumed turn (R6). Exercises the stdlib WS server.
const url = "ws://localhost:8765/ws";
const ws = new WebSocket(url);
const states = new Map();
let replied = false;
let marker = "";

const done = (code, msg) => { console.log(msg); try { ws.close(); } catch {} process.exit(code); };
const timeout = setTimeout(() => done(1, "TIMEOUT: reply did not land"), 15000);

ws.onopen = () => {
  console.log("connected");
  ws.send(JSON.stringify({ hello: { protocol_version: 1, role: "CONSUMER", node: "checker" } }));
};

ws.onmessage = (e) => {
  const f = JSON.parse(e.data.toString());
  if (f.hello) console.log(`hello: server role=${f.hello.role} node=${f.hello.node} v=${f.hello.protocol_version}`);

  if (f.state) {
    const s = f.state;
    states.set(s.ref.handle, s);
    console.log(`STATE  ${pad(s.ref.agent)} ${pad(s.ref.title,22)} ${pad(s.status,8)} replyable=${s.replyable}  | ${s.preview || ""}`);

    if (!replied && s.replyable && s.status === "WAITING") {
      replied = true;
      marker = "WIRE-PROOF-" + Date.now();
      console.log(`\n>>> sending reply to ${s.ref.handle}: "${marker}"\n`);
      ws.send(JSON.stringify({ event: { session_handle: s.ref.handle, direction: "FROM_HUMAN", kind: "HUMAN_MESSAGE", text: marker } }));
    }
  }

  if (f.event && f.event.kind === "HUMAN_MESSAGE" && f.event.text === marker && f.event.consumed) {
    clearTimeout(timeout);
    done(0, `\n*** LANDED: human reply "${marker}" became a consumed turn in the agent. R6 verified over the wire. ***`);
  }
};

ws.onerror = (e) => done(1, "WS ERROR: " + (e.message || e));
ws.onclose = () => { if (!replied) done(1, "closed early"); };

function pad(s, n = 8) { s = String(s ?? ""); return s.length >= n ? s.slice(0, n) : s + " ".repeat(n - s.length); }
