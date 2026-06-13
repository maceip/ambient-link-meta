// Observable status object the UI binds to. Owns the RelayClient + HudPresenter pair, and
// pumps RelayClient events into HudPresenter calls. Equivalent of phone-android's
// RelayService.status StateFlow, but in Swift's `@Observable` / SwiftUI flavor.
import Foundation
import MWDATCore
import Observation

@Observable @MainActor
final class DaemonState {
  var url: String = ""
  var connected: Bool = false
  var threads: [String] = []
  var lastError: String?

  private let wearables: any WearablesInterface
  private var client: RelayClient?
  private var presenter: HudPresenter?
  private var pumpTask: Task<Void, Never>?

  init(wearables: any WearablesInterface) { self.wearables = wearables }

  func start(url: String) {
    guard let u = URL(string: url) else { lastError = "bad URL"; return }
    stop()
    self.url = url
    let c = RelayClient(url: u)
    let p = HudPresenter(wearables: wearables, relay: c)
    client = c
    presenter = p

    pumpTask = Task { [weak self] in
      for await ev in c.events {
        guard let self else { return }
        switch ev {
        case .connected:                            self.connected = true;  self.lastError = nil
        case .disconnected:                         self.connected = false
        case .hello(let threads):                   self.threads = threads.map { $0.label }
        case .threadIdle(let yank): p.yank(yank)
        case .hudYank(let yank):    p.yank(yank)
        case .threadBusy(let t):                    p.cancelIfFor(thread: t)
        case .error(let m):                         self.lastError = m
        }
      }
    }
    c.start()
  }

  func stop() {
    pumpTask?.cancel(); pumpTask = nil
    client?.stop(); client = nil
    presenter = nil
    connected = false
  }
}
