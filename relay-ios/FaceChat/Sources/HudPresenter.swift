// Swift twin of phone-android/.../hud/HudPresenter.kt. Same state machine
// (Ambient → Peeking → Engaged → Snoozed) defined in phone-shared/PROTOCOL.md.
// DAT iOS DSL: FlexBox/Text/Button with @ComponentBuilder trailing closures
// (see vendor sample CarMaintenanceDisplay.swift).
import Foundation
import MWDATCore
import MWDATDisplay

@MainActor
final class HudPresenter {
  enum State { case ambient, peeking, engaged, snoozed }
  struct Yank { let thread: String; let label: String; let lastAssistant: String }

  private weak var relay: RelayClient?
  private let wearables: any WearablesInterface
  private var session: DeviceSession?
  private var display: Display?
  private var current: Yank?
  private var peekTimer: Task<Void, Never>?
  private var snoozeTimer: Task<Void, Never>?
  private(set) var state: State = .ambient

  private let peekTimeoutMs: UInt64 = 12_000
  private let snoozeMs:      UInt64 = 60_000

  // How long to wait for linkState to flip to .connected after a yank arrives, and how
  // often to re-poll while waiting. The SDK exposes no public "request connect" API; using
  // SpecificDeviceSelector lets the DAT runtime lazily bring the link up, and we observe
  // metadata until it does. Mirrors phone-android HudPresenter.LINK_WAIT_MS.
  private let linkWaitMs: UInt64 = 20_000
  private let linkPollMs: UInt64 = 500

  init(wearables: any WearablesInterface, relay: RelayClient) {
    self.wearables = wearables
    self.relay = relay
  }

  func yank(thread: String, label: String, lastAssistant: String) {
    snoozeTimer?.cancel(); snoozeTimer = nil
    current = Yank(thread: thread, label: label, lastAssistant: lastAssistant)
    openSessionAndPeek()
  }
  func cancelIfFor(thread: String) {
    if current?.thread == thread { closeSession() }
  }

  private func openSessionAndPeek() {
    if display != nil {
      // Already have a live session — just re-render the peek with the new content.
      state = .peeking; renderPeek(); armPeekTimer(); return
    }

    // Find the first display-capable device the SDK knows about. We deliberately do NOT
    // pre-filter on linkState (the old AutoDeviceSelector did, which rejects a device whose
    // link is still .disconnected and so could never trigger a wake) — instead we pick the
    // device and let SpecificDeviceSelector bring the link up on demand.
    let ids = wearables.devices
    guard let candidate = ids.first(where: { wearables.deviceForIdentifier($0)?.supportsDisplay() == true }) else {
      NSLog("[face-chat] no display-capable device known to SDK (\(ids.count) total)")
      return
    }

    let initial = wearables.deviceForIdentifier(candidate)?.linkState
    if initial == .connected {
      NSLog("[face-chat] device already CONNECTED, opening session immediately")
      createSession(for: candidate)
      return
    }

    NSLog("[face-chat] device link=\(String(describing: initial)) — waiting up to \(linkWaitMs)ms for CONNECTED")
    Task { [weak self] in
      guard let self else { return }
      if await self.awaitConnected(candidate) {
        NSLog("[face-chat] device transitioned to CONNECTED — opening session")
        self.createSession(for: candidate)
      } else {
        let final = self.wearables.deviceForIdentifier(candidate)?.linkState
        NSLog("[face-chat] timed out waiting for CONNECTED (final link=\(String(describing: final))); dropping yank")
      }
    }
  }

  // Poll the device's linkState until it reaches .connected or linkWaitMs elapses. Logs
  // every observed transition so a stuck link is diagnosable from device logs — the iOS
  // analog of phone-android's startWatchingLink metadata subscription.
  private func awaitConnected(_ id: DeviceIdentifier) async -> Bool {
    let deadline = DispatchTime.now().uptimeNanoseconds + linkWaitMs * 1_000_000
    var last: LinkState? = nil
    while DispatchTime.now().uptimeNanoseconds < deadline {
      let ls = wearables.deviceForIdentifier(id)?.linkState
      if ls != last {
        NSLog("[face-chat] link-watch: id=\(id) link=\(String(describing: ls))")
        last = ls
      }
      if ls == .connected { return true }
      try? await Task.sleep(nanoseconds: linkPollMs * 1_000_000)
    }
    return false
  }

  private func createSession(for id: DeviceIdentifier) {
    NSLog("[face-chat] createSession via SpecificDeviceSelector id=\(id)")
    do {
      let s = try wearables.createSession(deviceSelector: SpecificDeviceSelector(device: id))
      session = s
      Task { [weak self] in
        for await st in s.stateStream() {
          guard let self else { return }
          NSLog("[face-chat] session.state -> \(st)")
          if st == .started, self.display == nil { await self.attachDisplay(s) }
        }
      }
      try s.start()
    } catch {
      NSLog("[face-chat] createSession failed: \(error)")
    }
  }

  private func attachDisplay(_ s: DeviceSession) async {
    do {
      let cap = try s.addDisplay()
      display = cap
      // Display exposes statePublisher (Announcer<DisplayState>), not an AsyncStream.
      // Listen via the announcer's `listen` callback.
      _ = cap.statePublisher.listen { [weak self] ds in
        Task { @MainActor in
          guard let self else { return }
          if ds == .started {
            self.state = .peeking
            self.renderPeek()
            self.armPeekTimer()
          }
        }
      }
      await cap.start()
    } catch {
      NSLog("[face-chat] addDisplay failed: \(error)")
    }
  }

  private func armPeekTimer() {
    peekTimer?.cancel()
    peekTimer = Task { [weak self] in
      guard let ns = self?.peekTimeoutMs else { return }
      try? await Task.sleep(nanoseconds: ns * 1_000_000)
      if self?.state == .peeking { self?.closeSession() }
    }
  }

  // ── Peek card: label, truncated message, [open] [snooze] [dismiss]
  private func renderPeek() {
    guard let d = display, let y = current else { return }
    let view = FlexBox(direction: .column, spacing: 8) {
      Text(y.label, style: .meta, color: .secondary)
      FlexBox(direction: .column) {
        Text(String(y.lastAssistant.prefix(200)), style: .body)
      }
      .padding(12)
      .background(.card)
      FlexBox(direction: .row, spacing: 6, wrap: true) {
        Button(label: "open",    style: .primary,   onClick: { Task { @MainActor in self.onEngage() } })
        Button(label: "snooze",  style: .secondary, onClick: { Task { @MainActor in self.onSnooze() } })
        Button(label: "dismiss", style: .outline,   onClick: { Task { @MainActor in self.closeSession() } })
      }
    }
    Task { try? await d.send(view) }
  }

  // ── Expanded card: full message + classified chip set
  private func renderExpanded() {
    guard let d = display, let y = current else { return }
    let chips = ChipSet.forLastAssistant(y.lastAssistant)
    let buttons: [any ViewComponent] = chips.map { c in
      let style: ButtonStyle = {
        switch c.kind {
        case .send:        return .primary
        case .askFollowup: return .secondary
        case .dismiss:     return .outline
        case .snooze:      return .outline
        }
      }()
      return Button(label: c.label, style: style, onClick: { Task { @MainActor in self.onChip(c) } })
    }
    let view = FlexBox(direction: .column, spacing: 8) {
      Text(y.label, style: .meta, color: .secondary)
      FlexBox(direction: .column) {
        Text(y.lastAssistant, style: .body)
      }
      .padding(12)
      .background(.card)
      FlexBox(direction: .row, spacing: 6, wrap: true) {
        // ComponentBuilder accepts arrays via the for-loop spread; pre-built buttons are
        // splatted into the children list.
        for b in buttons { b }
      }
    }
    Task { try? await d.send(view) }
  }

  private func onEngage() { state = .engaged; peekTimer?.cancel(); renderExpanded() }
  private func onSnooze() {
    state = .snoozed
    let y = current
    closeSession()
    snoozeTimer = Task { [weak self] in
      guard let ms = self?.snoozeMs else { return }
      try? await Task.sleep(nanoseconds: ms * 1_000_000)
      if let y { self?.yank(thread: y.thread, label: y.label, lastAssistant: y.lastAssistant) }
    }
  }
  private func onChip(_ c: Chip) {
    let y = current
    switch c.kind {
    case .send:
      if let y, let text = c.text { relay?.sendInput(thread: y.thread, text: text, enter: c.enter) }
      closeSession()
    case .askFollowup:
      // TODO: secondary chip picker — pre-canned + history.
      closeSession()
    case .dismiss: closeSession()
    case .snooze:  onSnooze()
    }
  }

  private func closeSession() {
    peekTimer?.cancel(); peekTimer = nil
    session?.stop()
    display = nil
    session = nil
    current = nil
    state = .ambient
  }
}
