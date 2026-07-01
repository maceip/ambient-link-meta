// Package status projects a session's log into a SessionState.
// This is a PURE function: same log in -> same status out, on any node.
// It is the core of "trustworthy status" and is meant to be read and verified
// directly (see status_test.go).
package status

import (
	"sort"
	"strings"

	"ambient/internal/model"
)

// DefaultIdleMs: how long after the last agent activity a session is still
// considered WORKING before it falls to DONE.
const DefaultIdleMs int64 = 4000

// Project computes the state of one session from its events.
// now and idleMs are passed in so the function is deterministic and testable.
func Project(ref model.SessionRef, events []model.Event, now, idleMs int64) model.SessionState {
	ev := append([]model.Event(nil), events...)
	sort.SliceStable(ev, func(i, j int) bool { return ev[i].TsUnixMs < ev[j].TsUnixMs })

	var lastAgentTs, lastHumanTs, lastToolTs, lastPermTs, lastTs int64
	var lastAgentText, lastHumanText, lastPermText, location string
	closed := false

	for _, e := range ev {
		if e.TsUnixMs >= lastTs {
			lastTs = e.TsUnixMs
			location = e.Producer
		}
		switch e.Kind {
		case model.SessionClose:
			closed = true
		case model.AgentMessage:
			lastAgentTs, lastAgentText = e.TsUnixMs, e.Text
		case model.HumanMessage:
			lastHumanTs, lastHumanText = e.TsUnixMs, e.Text
		case model.ToolActivity:
			lastToolTs = e.TsUnixMs
		case model.PermissionRequest:
			lastPermTs, lastPermText = e.TsUnixMs, e.Text
		}
	}

	st := model.Done
	awaiting := false
	permText := ""

	switch {
	case closed:
		st = model.Ended
	case lastPermTs > 0 && lastPermTs > lastHumanTs:
		st, awaiting, permText = model.Waiting, true, lastPermText
	case lastAgentTs > 0 && isQuestion(lastAgentText) && lastAgentTs > lastHumanTs && lastAgentTs >= lastToolTs:
		st = model.Waiting
	default:
		recent := lastAgentTs
		if lastToolTs > recent {
			recent = lastToolTs
		}
		if recent > 0 && now-recent <= idleMs {
			st = model.Working
		} else {
			st = model.Done
		}
	}

	preview := lastAgentText
	if awaiting {
		preview = permText
	} else if preview == "" {
		preview = lastHumanText
	}

	return model.SessionState{
		Ref:                ref,
		Status:             st,
		Preview:            clamp(preview, 140),
		AwaitingPermission: awaiting,
		PermissionText:     permText,
		LastEventTs:        lastTs,
		Location:           location,
	}
}

func isQuestion(s string) bool {
	s = strings.TrimSpace(s)
	if s == "" {
		return false
	}
	if strings.HasSuffix(s, "?") {
		return true
	}
	l := strings.ToLower(s)
	for _, cue := range []string{"(y/n)", "[y/n]", "yes/no", "shall i", "want me to", "should i", "proceed"} {
		if strings.Contains(l, cue) {
			return true
		}
	}
	return false
}

func clamp(s string, n int) string {
	s = strings.TrimSpace(s)
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}
