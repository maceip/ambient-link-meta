package producers

import (
	"fmt"
	"sync"
	"time"

	"ambient/internal/model"
	"ambient/internal/store"
)

// Demo is a self-contained, replyable agent. It proves the full bidirectional
// loop deterministically: it works, asks a question (WAITING), receives your
// reply, marks it consumed ("landed") after taking it in, acknowledges it, then
// goes idle (DONE). For the demo the log IS the agent's transcript, so "landed"
// is ground-truth: your exact text reappears as a consumed FROM_HUMAN turn.
type Demo struct {
	store    *store.Store
	handle   string
	producer string
}

func StartDemo(s *store.Store, agent, title, workdir, opening string) *Demo {
	h := agent + ":" + shortID()
	d := &Demo{store: s, handle: h, producer: "laptop-demo"}
	s.Register(model.SessionRef{Handle: h, Agent: agent, Title: title, Workdir: workdir})
	s.Append(model.Event{SessionHandle: h, Producer: d.producer, Direction: model.FromAgent, Kind: model.SessionOpen})
	go d.open(opening)
	return d
}

func (d *Demo) Handle() string { return d.handle }

func (d *Demo) open(opening string) {
	d.store.Append(model.Event{SessionHandle: d.handle, Producer: d.producer, Direction: model.FromAgent, Kind: model.ToolActivity, Text: "reading project files"})
	time.Sleep(2500 * time.Millisecond)
	d.store.Append(model.Event{SessionHandle: d.handle, Producer: d.producer, Direction: model.FromAgent, Kind: model.AgentMessage, Text: opening})
	// now WAITING for a human reply
}

// Deliver: the reply arrives. Record the human turn, then take it in (mark
// consumed) and acknowledge with the exact text, then resume and fall idle.
func (d *Demo) Deliver(handle, text string) error {
	if handle != d.handle {
		return fmt.Errorf("demo: unknown handle %q", handle)
	}
	ev := d.store.Append(model.Event{SessionHandle: d.handle, Producer: d.producer, Direction: model.FromHuman, Kind: model.HumanMessage, Text: text})
	go func(seq uint64) {
		time.Sleep(500 * time.Millisecond)
		d.store.MarkConsumed(d.handle, seq, d.producer) // landed, proven
		d.store.Append(model.Event{SessionHandle: d.handle, Producer: d.producer, Direction: model.FromAgent, Kind: model.AgentMessage, Text: "On it — " + text})
		d.store.Append(model.Event{SessionHandle: d.handle, Producer: d.producer, Direction: model.FromAgent, Kind: model.ToolActivity, Text: "applying changes"})
		// no further activity -> projection falls to DONE after the idle window
	}(ev.Seq)
	return nil
}

var (
	idMu sync.Mutex
	idN  int
)

func shortID() string {
	idMu.Lock()
	defer idMu.Unlock()
	idN++
	return fmt.Sprintf("%d-%d", time.Now().UnixNano(), idN)
}
