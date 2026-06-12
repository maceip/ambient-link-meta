// WS client using URLSessionWebSocketTask. Implements the subset of the protocol
// (see ../phone-shared/PROTOCOL.md) the daemon needs: on connect → send `subscribe`;
// emit Hello / ThreadIdle / ThreadBusy events; outbound `input` / `special` send JSON frames.
// Exponential reconnect backoff 500ms → 10s.
import Foundation

@MainActor
final class RelayClient {
  struct ThreadMeta { let id: String; let label: String; let agent: String }
  enum Event {
    case connected
    case disconnected
    case hello(threads: [ThreadMeta])
    case threadIdle(thread: String, label: String, lastAssistant: String)
    case threadBusy(thread: String)
    case error(String)
  }

  private let url: URL
  private var task: URLSessionWebSocketTask?
  private var backoff: TimeInterval = 0.5
  private var loopTask: Task<Void, Never>?
  private var labels: [String: String] = [:]

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

  private func handle(_ raw: String) {
    guard let data = raw.data(using: .utf8),
          let obj  = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let type = obj["type"] as? String else { return }
    switch type {
    case "hello":
      labels.removeAll()
      let arr = (obj["threads"] as? [[String: Any]]) ?? []
      var threads: [ThreadMeta] = []
      for t in arr {
        let id = t["id"] as? String ?? ""
        let label = (t["label"] as? String) ?? id
        let agent = (t["agent"] as? String) ?? "generic"
        labels[id] = label
        threads.append(.init(id: id, label: label, agent: agent))
      }
      cont.yield(.hello(threads: threads))
    case "thread_idle":
      let id = obj["thread"] as? String ?? ""
      cont.yield(.threadIdle(
        thread: id,
        label:  labels[id] ?? id,
        lastAssistant: obj["lastAssistant"] as? String ?? "",
      ))
    case "thread_busy":
      cont.yield(.threadBusy(thread: obj["thread"] as? String ?? ""))
    default: break
    }
  }

  func sendInput(thread: String, text: String, enter: Bool = true) {
    let payload: [String: Any] = ["type": "input", "thread": thread, "text": text, "enter": enter]
    sendJSON(payload)
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
