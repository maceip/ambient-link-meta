// Package store is the append-only log + projection. In-memory here; a durable
// substrate swaps the backing slices for a file/db without changing the API.
package store

import (
	"sync"
	"time"

	"ambient/internal/model"
	"ambient/internal/status"
)

type Store struct {
	mu            sync.Mutex
	refs          map[string]model.SessionRef
	events        map[string][]model.Event
	order         []string
	seqByProducer map[string]uint64
	subs          map[int]chan model.Event
	nextSub       int
	idleMs        int64
}

func New() *Store {
	return &Store{
		refs:          map[string]model.SessionRef{},
		events:        map[string][]model.Event{},
		seqByProducer: map[string]uint64{},
		subs:          map[int]chan model.Event{},
		idleMs:        status.DefaultIdleMs,
	}
}

func (s *Store) Register(ref model.SessionRef) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.refs[ref.Handle]; !ok {
		s.order = append(s.order, ref.Handle)
	}
	s.refs[ref.Handle] = ref
}

// Append assigns a per-producer seq, stamps time, stores, and notifies.
func (s *Store) Append(ev model.Event) model.Event {
	s.mu.Lock()
	if ev.TsUnixMs == 0 {
		ev.TsUnixMs = time.Now().UnixMilli()
	}
	s.seqByProducer[ev.Producer]++
	ev.Seq = s.seqByProducer[ev.Producer]
	s.events[ev.SessionHandle] = append(s.events[ev.SessionHandle], ev)
	if _, ok := s.refs[ev.SessionHandle]; !ok {
		s.refs[ev.SessionHandle] = model.SessionRef{Handle: ev.SessionHandle}
		s.order = append(s.order, ev.SessionHandle)
	}
	subs := s.snapshotSubs()
	s.mu.Unlock()
	notify(subs, ev)
	return ev
}

// MarkConsumed flips the "landed" property on a FROM_HUMAN event and re-notifies.
func (s *Store) MarkConsumed(handle string, seq uint64, producer string) {
	s.mu.Lock()
	var upd model.Event
	found := false
	evs := s.events[handle]
	for i := range evs {
		if evs[i].Seq == seq && evs[i].Producer == producer {
			evs[i].Consumed = true
			upd = evs[i]
			found = true
			break
		}
	}
	subs := s.snapshotSubs()
	s.mu.Unlock()
	if found {
		notify(subs, upd)
	}
}

func (s *Store) snapshotSubs() []chan model.Event {
	out := make([]chan model.Event, 0, len(s.subs))
	for _, c := range s.subs {
		out = append(out, c)
	}
	return out
}

func notify(subs []chan model.Event, ev model.Event) {
	for _, c := range subs {
		select {
		case c <- ev:
		default: // backpressure: drop for a slow subscriber rather than block
		}
	}
}

func (s *Store) Subscribe() (int, chan model.Event) {
	s.mu.Lock()
	defer s.mu.Unlock()
	id := s.nextSub
	s.nextSub++
	ch := make(chan model.Event, 512)
	s.subs[id] = ch
	return id, ch
}

func (s *Store) Unsubscribe(id int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if c, ok := s.subs[id]; ok {
		delete(s.subs, id)
		close(c)
	}
}

func (s *Store) EventsFor(handle string) []model.Event {
	s.mu.Lock()
	defer s.mu.Unlock()
	src := s.events[handle]
	out := make([]model.Event, len(src))
	copy(out, src)
	return out
}

func (s *Store) StateOf(handle string) model.SessionState {
	s.mu.Lock()
	ref := s.refs[handle]
	evs := append([]model.Event(nil), s.events[handle]...)
	idle := s.idleMs
	s.mu.Unlock()
	return status.Project(ref, evs, time.Now().UnixMilli(), idle)
}

func (s *Store) Snapshot() []model.SessionState {
	s.mu.Lock()
	handles := append([]string(nil), s.order...)
	s.mu.Unlock()
	out := make([]model.SessionState, 0, len(handles))
	for _, h := range handles {
		out = append(out, s.StateOf(h))
	}
	return out
}
