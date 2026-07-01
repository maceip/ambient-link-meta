package producers

// Producer is anything that can inject a human reply into the agent it observes.
// Two impls exist (Demo, and the OS-console injector to come), so the interface
// earns its place. Observe-only producers (e.g. the live Cursor tailer) simply
// don't register, and their sessions are reported replyable=false.
type Producer interface {
	Deliver(handle, text string) error
}
