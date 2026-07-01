// Command agent is the per-machine node: observe local agents, serve consumers,
// inject replies. Flagless — it just runs. Optional env overrides only.
//
//	AMBIENT_PORT  (default 8765)
//	AMBIENT_WEB   (default ./web)
package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"

	"ambient/internal/producers"
	"ambient/internal/server"
	"ambient/internal/store"
)

func main() {
	port := env("AMBIENT_PORT", "8765")
	webRoot := env("AMBIENT_WEB", webDir())

	s := store.New()
	h := server.New(s)
	go h.Run()

	// Two replyable demo agents that prove the full bidirectional loop.
	d1 := producers.StartDemo(s, "claude", "auth-service", cwd(),
		"I can refactor the auth module to use the new token flow. Want me to proceed?")
	h.RegisterProducer(d1.Handle(), d1)

	d2 := producers.StartDemo(s, "codex", "data-pipeline", cwd(),
		"Tests pass except one flaky timeout. Should I bump the limit and re-run?")
	h.RegisterProducer(d2.Handle(), d2)

	// Live, observe-only view of the real Cursor session on this machine.
	if producers.StartCursorLive(s) {
		log.Printf("observing live Cursor transcript on this machine")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", h.ServeWS)
	mux.Handle("/", http.FileServer(http.Dir(webRoot)))

	addr := ":" + port
	log.Printf("ambient agent node listening on http://localhost:%s  (web=%s)", port, webRoot)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func cwd() string {
	d, err := os.Getwd()
	if err != nil {
		return ""
	}
	return d
}

// webDir resolves ./web next to the working dir, falling back to the source tree
// layout so `go run ./cmd/agent` works from the module root.
func webDir() string {
	if _, err := os.Stat("web"); err == nil {
		return "web"
	}
	if _, err := os.Stat(filepath.Join("..", "..", "web")); err == nil {
		return filepath.Join("..", "..", "web")
	}
	return "web"
}
