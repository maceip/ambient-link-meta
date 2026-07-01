package server

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"ambient/internal/model"
	"ambient/internal/producers"
	"ambient/internal/store"
)

// R6 acceptance check: a human reply reaches the agent, lands (consumed=true)
// as the exact text, and the agent acknowledges it referencing that text.
func TestReplyLands(t *testing.T) {
	s := store.New()
	d := producers.StartDemo(s, "claude", "test", "/tmp", "Want me to proceed?")

	if !waitFor(5*time.Second, func() bool { return s.StateOf(d.Handle()).Status == model.Waiting }) {
		t.Fatalf("session never reached WAITING (got %s)", s.StateOf(d.Handle()).Status)
	}

	marker := fmt.Sprintf("MARKER-%d", time.Now().UnixNano())
	if err := d.Deliver(d.Handle(), marker); err != nil {
		t.Fatalf("deliver: %v", err)
	}

	landed := waitFor(2*time.Second, func() bool {
		for _, e := range s.EventsFor(d.Handle()) {
			if e.Kind == model.HumanMessage && e.Text == marker && e.Consumed {
				return true
			}
		}
		return false
	})
	if !landed {
		t.Fatalf("human reply never landed (consumed=true) for marker %q", marker)
	}

	acked := waitFor(2*time.Second, func() bool {
		for _, e := range s.EventsFor(d.Handle()) {
			if e.Kind == model.AgentMessage && strings.Contains(e.Text, marker) {
				return true
			}
		}
		return false
	})
	if !acked {
		t.Fatalf("agent never acknowledged marker %q", marker)
	}
}

func waitFor(d time.Duration, cond func() bool) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return true
		}
		time.Sleep(20 * time.Millisecond)
	}
	return cond()
}
