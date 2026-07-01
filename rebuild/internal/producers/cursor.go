package producers

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"ambient/internal/model"
	"ambient/internal/store"
)

// StartCursorLive tails the newest real Cursor agent transcript on this machine
// and projects it as a live, observe-only session. This is R2 against real data:
// the running conversation shows up with real status. It does NOT register as a
// Producer, so it is reported replyable=false (we don't claim an injection path
// we can't honestly prove here).
//
// handle = "cursor:<transcript-uuid>" — derived from the agent's own session id,
// never from a filesystem path (R3).
func StartCursorLive(s *store.Store) bool {
	home := os.Getenv("USERPROFILE")
	if home == "" {
		home = os.Getenv("HOME")
	}
	pattern := filepath.Join(home, ".cursor", "projects", "*", "agent-transcripts", "*", "*.jsonl")
	matches, _ := filepath.Glob(pattern)
	if len(matches) == 0 {
		return false
	}
	sort.Slice(matches, func(i, j int) bool {
		fi, ei := os.Stat(matches[i])
		fj, ej := os.Stat(matches[j])
		if ei != nil || ej != nil {
			return false
		}
		return fi.ModTime().After(fj.ModTime())
	})
	path := matches[0]
	id := strings.TrimSuffix(filepath.Base(path), ".jsonl")
	handle := "cursor:" + id
	s.Register(model.SessionRef{Handle: handle, Agent: "cursor", Title: "this machine (live)", Workdir: filepath.Dir(path)})
	s.Append(model.Event{SessionHandle: handle, Producer: "laptop", Direction: model.FromAgent, Kind: model.SessionOpen})
	go tailCursor(s, handle, path)
	return true
}

type cursorLine struct {
	Role    string `json:"role"`
	Message struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	} `json:"message"`
}

func tailCursor(s *store.Store, handle, path string) {
	processed := 0
	for {
		f, err := os.Open(path)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 1024*1024), 16*1024*1024)
		i := 0
		for sc.Scan() {
			i++
			if i <= processed {
				continue
			}
			processed = i
			var cl cursorLine
			if json.Unmarshal(sc.Bytes(), &cl) != nil {
				continue
			}
			text := ""
			tool := false
			for _, c := range cl.Message.Content {
				switch c.Type {
				case "text":
					text += c.Text
				case "tool_use":
					tool = true
				}
			}
			text = clean(text)
			switch cl.Role {
			case "assistant":
				if text != "" {
					s.Append(model.Event{SessionHandle: handle, Producer: "laptop", Direction: model.FromAgent, Kind: model.AgentMessage, Text: text})
				} else if tool {
					s.Append(model.Event{SessionHandle: handle, Producer: "laptop", Direction: model.FromAgent, Kind: model.ToolActivity, Text: "working"})
				}
			case "user":
				if text != "" {
					s.Append(model.Event{SessionHandle: handle, Producer: "laptop", Direction: model.FromHuman, Kind: model.HumanMessage, Text: text, Consumed: true})
				}
			}
		}
		f.Close()
		time.Sleep(1500 * time.Millisecond)
	}
}

func clean(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	r := []rune(s)
	if len(r) > 400 {
		s = string(r[:400]) + "…"
	}
	return strings.TrimSpace(s)
}
