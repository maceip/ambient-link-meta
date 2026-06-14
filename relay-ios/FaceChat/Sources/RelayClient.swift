import Foundation

@MainActor
final class RelayClient {
  struct ThreadMeta { let id: String; let label: String; let agent: String }
  enum Event {
    case connected
    case disconnected
    case hello(threads: [ThreadMeta])
    case threadIdle(yank: AgentYank)
    case hudYank(yank: AgentYank)
    case threadBusy(thread: String)
    case error(String)
  }

  private let url: URL
  private var task: URLSessionWebSocketTask?
  private var backoff: TimeInterval = 0.5
  private var loopTask: Task<Void, Never>?
  private var labels: [String: String] = [:]
  private var agents: [String: String] = [:]

  let events: AsyncStream<Event>
  private let cont: AsyncStream<Event>.Continuation

  init(url: URL) {
    self.url = url
    var c: AsyncStream<Event>.Continuation!
    self.events = AsyncStream { c = $0 }
    self.cont = c
  }

  func start() {
    loopTask?.cancel()
    loopTask = Task { [weak self] in
      while !Task.isCancelled {
        await self?.connectOnce()
        try? await Task.sleep(nanoseconds: UInt64((self?.backoff ?? 0.5) * 1_000_000_000))
        if let s = self { s.backoff = min(s.backoff * 2, 10.0) }
      }
    }
  }
  func stop() {
    loopTask?.cancel()
    task?.cancel(with: .normalClosure, reason: nil)
    task = nil
  }

  private func connectOnce() async {
    let session = URLSession(configuration: .default)
    let t = session.webSocketTask(with: url)
    self.task = t
    t.resume()
    backoff = 0.5
    cont.yield(.connected)
    let subscribe = #"{"type":"subscribe","since":{}}"#
    do { try await t.send(.string(subscribe)) }
    catch { cont.yield(.error("subscribe send failed: \(error)")); cont.yield(.disconnected); return }
    while !Task.isCancelled {
      do {
        let msg = try await t.receive()
        switch msg {
        case .string(let s): handle(s)
        case .data(let d):   if let s = String(data: d, encoding: .utf8) { handle(s) }
        @unknown default:    break
        }
      } catch {
        cont.yield(.error(error.localizedDescription))
        cont.yield(.disconnected)
        return
      }
    }
  }

  private func parseYank(_ obj: [String: Any]) -> AgentYank {
    let id = obj["thread"] as? String ?? ""
    let awaitingRaw = obj["awaiting"] as? String ?? "done"
    let awaiting: Awaiting = {
      switch awaitingRaw {
      case "permission": return .permission
      case "question":   return .question
      default:           return .done
      }
    }()
    let perm = (obj["permissionPrompt"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
    return AgentYank(
      thread: id,
      label: (obj["label"] as? String) ?? labels[id] ?? id,
      agent: (obj["agent"] as? String) ?? agents[id] ?? "generic",
      lastAssistant: obj["lastAssistant"] as? String ?? "",
      lastUserInput: obj["lastUserInput"] as? String ?? "",
      awaiting: awaiting,
      permissionPrompt: (perm?.isEmpty == false) ? perm : nil
    )
  }

  private func handle(_ raw: String) {
    guard let data = raw.data(using: .utf8),
          let obj  = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let type = obj["type"] as? String else { return }
    switch type {
    case "hello":
      labels.removeAll(); agents.removeAll()
      let arr = (obj["threads"] as? [[String: Any]]) ?? []
      var threads: [ThreadMeta] = []
      for t in arr {
        let id = t["id"] as? String ?? ""
        let label = (t["label"] as? String) ?? id
        let agent = (t["agent"] as? String) ?? "generic"
        labels[id] = label; agents[id] = agent
        threads.append(.init(id: id, label: label, agent: agent))
      }
      cont.yield(.hello(threads: threads))
    case "thread_idle": cont.yield(.threadIdle(yank: parseYank(obj)))
    case "hud_yank":    cont.yield(.hudYank(yank: parseYank(obj)))
    case "thread_busy": cont.yield(.threadBusy(thread: obj["thread"] as? String ?? ""))
    default: break
    }
  }

  func sendDictateBegin(thread: String) { sendDictate(type: "dictate_begin", thread: thread, text: nil) }
  func sendDictatePartial(thread: String, text: String) { sendDictate(type: "dictate_partial", thread: thread, text: text) }
  func sendDictateCommit(thread: String, text: String) { sendDictate(type: "dictate_commit", thread: thread, text: text) }
  func sendDictateAbort(thread: String) { sendDictate(type: "dictate_abort", thread: thread, text: nil) }

  private func sendDictate(type: String, thread: String, text: String?) {
    var o: [String: Any] = ["type": type, "thread": thread, "source": "phone"]
    if let text { o["text"] = text }
    sendJSON(o)
  }

  func sendInput(thread: String, text: String, enter: Bool = true) {
    sendJSON(["type": "input", "thread": thread, "text": text, "enter": enter])
  }
  func sendSpecial(thread: String, key: String) {
    sendJSON(["type": "special", "thread": thread, "key": key])
  }
  private func sendJSON(_ obj: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: obj),
          let s = String(data: data, encoding: .utf8) else { return }
    Task { try? await task?.send(.string(s)) }
  }
}
