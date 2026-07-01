// Package server is the WebSocket hub: it serves SessionState + Events to
// consumers and routes inbound HUMAN_MESSAGE replies to the owning producer.
package server

import (
	"encoding/json"
	"net/http"
	"os"
	"sync"

	"ambient/internal/model"
	"ambient/internal/producers"
	"ambient/internal/store"
	"ambient/internal/wsutil"
)

type Hub struct {
	store  *store.Store
	router map[string]producers.Producer
	rmu    sync.Mutex

	clients map[*client]bool
	cmu     sync.Mutex
}

type client struct {
	conn *wsutil.Conn
	send chan []byte
}

func New(s *store.Store) *Hub {
	return &Hub{
		store:   s,
		router:  map[string]producers.Producer{},
		clients: map[*client]bool{},
	}
}

// RegisterProducer marks a handle as replyable and wires reply routing.
func (h *Hub) RegisterProducer(handle string, p producers.Producer) {
	h.rmu.Lock()
	h.router[handle] = p
	h.rmu.Unlock()
}

func (h *Hub) replyable(handle string) bool {
	h.rmu.Lock()
	defer h.rmu.Unlock()
	_, ok := h.router[handle]
	return ok
}

// Run fans out every log change to all connected consumers as State + Event.
func (h *Hub) Run() {
	_, ch := h.store.Subscribe()
	for ev := range ch {
		st := h.store.StateOf(ev.SessionHandle)
		st.Replyable = h.replyable(ev.SessionHandle)
		h.broadcast(frame(model.Frame{State: &st}))
		evCopy := ev
		h.broadcast(frame(model.Frame{Event: &evCopy}))
	}
}

func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request) {
	c, err := wsutil.Upgrade(w, r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	cl := &client{conn: c, send: make(chan []byte, 512)}
	h.cmu.Lock()
	h.clients[cl] = true
	h.cmu.Unlock()

	go func() {
		for msg := range cl.send {
			if c.WriteMessage(msg) != nil {
				break
			}
		}
	}()

	node, _ := os.Hostname()
	cl.push(frame(model.Frame{Hello: &model.Hello{ProtocolVersion: model.ProtocolVersion, Role: "SUBSTRATE", Node: node}}))
	for _, st := range h.store.Snapshot() {
		st.Replyable = h.replyable(st.Ref.Handle)
		stCopy := st
		cl.push(frame(model.Frame{State: &stCopy}))
	}

	for {
		data, err := c.ReadMessage()
		if err != nil {
			break
		}
		var f model.Frame
		if json.Unmarshal(data, &f) != nil {
			continue
		}
		if f.Hello != nil && f.Hello.ProtocolVersion != model.ProtocolVersion {
			break // refuse version mismatch (R1)
		}
		if f.Event != nil && f.Event.Kind == model.HumanMessage {
			h.handleHuman(*f.Event)
		}
	}

	h.cmu.Lock()
	delete(h.clients, cl)
	h.cmu.Unlock()
	close(cl.send)
	c.Close()
}

func (h *Hub) handleHuman(ev model.Event) {
	h.rmu.Lock()
	p := h.router[ev.SessionHandle]
	h.rmu.Unlock()
	if p == nil {
		return // observe-only session; no honest injection path
	}
	_ = p.Deliver(ev.SessionHandle, ev.Text)
}

func (h *Hub) broadcast(msg []byte) {
	h.cmu.Lock()
	defer h.cmu.Unlock()
	for cl := range h.clients {
		select {
		case cl.send <- msg:
		default: // slow consumer: drop rather than block the hub
		}
	}
}

func (c *client) push(msg []byte) {
	select {
	case c.send <- msg:
	default:
	}
}

func frame(f model.Frame) []byte {
	b, _ := json.Marshal(f)
	return b
}
