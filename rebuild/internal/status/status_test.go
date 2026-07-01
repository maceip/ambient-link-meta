package status

import (
	"testing"

	"ambient/internal/model"
)

// R4 acceptance check: a fixed table of (event sequence -> expected status).
func TestProjection(t *testing.T) {
	const now int64 = 100000
	const idle = DefaultIdleMs
	ref := model.SessionRef{Handle: "claude:1", Agent: "claude"}

	ev := func(kind model.EventKind, ts int64, text string) model.Event {
		return model.Event{SessionHandle: ref.Handle, Producer: "laptop", TsUnixMs: ts, Kind: kind, Text: text}
	}

	cases := []struct {
		name   string
		events []model.Event
		want   model.Status
		perm   bool
	}{
		{"empty -> done", nil, model.Done, false},
		{"recent tool -> working", []model.Event{ev(model.ToolActivity, now - 1000, "build")}, model.Working, false},
		{"stale tool -> done", []model.Event{ev(model.ToolActivity, now - 10000, "build")}, model.Done, false},
		{"agent question -> waiting", []model.Event{ev(model.AgentMessage, now - 500, "Want me to proceed?")}, model.Waiting, false},
		{"answered question -> not waiting", []model.Event{
			ev(model.AgentMessage, now - 10000, "Want me to proceed?"),
			ev(model.HumanMessage, now - 9000, "yes"),
		}, model.Done, false},
		{"permission -> waiting+perm", []model.Event{ev(model.PermissionRequest, now - 200, "Allow write to /etc?")}, model.Waiting, true},
		{"closed -> ended", []model.Event{
			ev(model.AgentMessage, now - 500, "done"),
			ev(model.SessionClose, now - 100, ""),
		}, model.Ended, false},
		{"working after recent agent msg", []model.Event{ev(model.AgentMessage, now - 1000, "applying patch")}, model.Working, false},
	}

	for _, c := range cases {
		got := Project(ref, c.events, now, idle)
		if got.Status != c.want {
			t.Errorf("%s: status = %s, want %s", c.name, got.Status, c.want)
		}
		if got.AwaitingPermission != c.perm {
			t.Errorf("%s: awaiting = %v, want %v", c.name, got.AwaitingPermission, c.perm)
		}
	}
}
