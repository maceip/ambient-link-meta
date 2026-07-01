// Package model mirrors ambient.proto 1:1 as the JSON wire shape.
// Single contract: every surface uses these types. No surface hand-rolls a
// different shape. (When protoc is in the toolchain, generate from the .proto
// instead; the field names/tags here match it exactly.)
package model

const ProtocolVersion = 1

type Direction string

const (
	FromAgent Direction = "FROM_AGENT"
	FromHuman Direction = "FROM_HUMAN"
)

type EventKind string

const (
	SessionOpen       EventKind = "SESSION_OPEN"
	SessionClose      EventKind = "SESSION_CLOSE"
	AgentMessage      EventKind = "AGENT_MESSAGE"
	HumanMessage      EventKind = "HUMAN_MESSAGE"
	ToolActivity      EventKind = "TOOL_ACTIVITY"
	PermissionRequest EventKind = "PERMISSION_REQUEST"
)

type Status string

const (
	Working Status = "WORKING"
	Waiting Status = "WAITING"
	Done    Status = "DONE"
	Ended   Status = "ENDED"
)

// SessionRef: a session reachable by a stable, location-independent handle.
// handle is NOT a credential. workdir is data for display, never used to derive
// the handle.
type SessionRef struct {
	Handle  string `json:"handle"`
	Agent   string `json:"agent"`
	Title   string `json:"title"`
	Workdir string `json:"workdir"`
}

// Event: one immutable entry in a session's log (the only truth).
type Event struct {
	SessionHandle string    `json:"session_handle"`
	Seq           uint64    `json:"seq"`
	Producer      string    `json:"producer"`
	TsUnixMs      int64     `json:"ts_unix_ms"`
	Direction     Direction `json:"direction"`
	Kind          EventKind `json:"kind"`
	Text          string    `json:"text"`
	Consumed      bool      `json:"consumed"` // FROM_HUMAN only: the "landed" property
}

// SessionState: a projection of a session's log. Never asserted on its own.
type SessionState struct {
	Ref                SessionRef `json:"ref"`
	Status             Status     `json:"status"`
	Preview            string     `json:"preview"`
	AwaitingPermission bool       `json:"awaiting_permission"`
	PermissionText     string     `json:"permission_text"`
	LastEventTs        int64      `json:"last_event_ts"`
	Location           string     `json:"location"`
	Replyable          bool       `json:"replyable"`
}

type Hello struct {
	ProtocolVersion int    `json:"protocol_version"`
	Role            string `json:"role"`
	Node            string `json:"node"`
}

type Subscribe struct {
	Since map[string]uint64 `json:"since"`
}

// Frame: the single envelope on the wire (one field set).
type Frame struct {
	Hello     *Hello        `json:"hello,omitempty"`
	Subscribe *Subscribe    `json:"subscribe,omitempty"`
	Event     *Event        `json:"event,omitempty"`
	State     *SessionState `json:"state,omitempty"`
}
