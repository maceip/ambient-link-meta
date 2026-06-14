import Foundation
import MWDATCore
import MWDATDisplay

@MainActor
final class HudPresenter {
  enum State { case ambient, peeking, engaged, followup, dictating, snoozed }

  private weak var relay: RelayClient?
  private let wearables: any WearablesInterface
  private var session: DeviceSession?
  private var display: Display?
  private var current: AgentYank?
  private var pending: AgentYank?
  private var dictatingPartial = ""
  private var peekTimer: Task<Void, Never>?
  private var snoozeTimer: Task<Void, Never>?
  private(set) var state: State = .ambient

  private let peekTimeoutMs: UInt64 = 300_000
  private let snoozeMs:      UInt64 = 60_000

  init(wearables: any WearablesInterface, relay: RelayClient) {
    self.wearables = wearables
    self.relay = relay
  }

  func onIdle(_ yank: AgentYank) {
    if current?.thread == yank.thread,
       state == .peeking || state == .engaged || state == .followup || state == .dictating {
      if yank.awaiting == .permission {
        current = yank
        renderPeek()
      }
      return
    }
    if state == .ambient, !yank.lastUserInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      return
    }
    yank(yank)
  }

  func yank(_ yank: AgentYank) {
    if (state == .peeking || state == .engaged || state == .followup),
       let cur = current?.thread, cur != yank.thread {
      pending = yank
      return
    }
    snoozeTimer?.cancel(); snoozeTimer = nil
    current = yank
    openSessionAndPeek()
  }

  func cancelIfFor(thread: String) {
    if pending?.thread == thread { pending = nil }
    NSLog("[face-chat] thread_busy %@ (HUD stays until dismissed)", thread)
  }

  private func openSessionAndPeek() {
    if display != nil {
      state = .peeking; renderPeek(); armPeekTimer(); return
    }
    do {
      let selector = AutoDeviceSelector(wearables: wearables, filter: { d in
        d.linkState == .connected && d.supportsDisplay()
      })
      let s = try wearables.createSession(deviceSelector: selector)
      session = s
      Task { [weak self] in
        for await st in s.stateStream() {
          guard let self else { return }
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

  private func renderPeek() {
    guard let d = display, let y = current else { return }
    let chips = ChipSet.forYank(y).prefix(3)
    let buttons: [any ViewComponent] = chips.enumerated().map { i, c in
      let style: ButtonStyle = i == 0 ? .primary : .secondary
      return Button(label: c.label, style: style, onClick: { Task { @MainActor in self.onChip(c) } })
    }
    let view = FlexBox(direction: .column, spacing: 8) {
      Text(y.metaLine, style: .meta, color: .secondary)
      FlexBox(direction: .column) {
        Text(String(y.bodyText.prefix(220)), style: .body)
      }
      .padding(12)
      .background(.card)
      FlexBox(direction: .row, spacing: 6, wrap: true) { for b in buttons { b } }
    }
    Task { try? await d.send(view) }
  }

  private func renderExpanded() {
    guard let d = display, let y = current else { return }
    let chips = ChipSet.forYank(y)
    let buttons: [any ViewComponent] = chips.prefix(3).enumerated().map { i, c in
      let style: ButtonStyle = i == 0 ? .primary : .secondary
      return Button(label: c.label, style: style, onClick: { Task { @MainActor in self.onChip(c) } })
    }
    let view = FlexBox(direction: .column, spacing: 8) {
      Text(y.metaLine, style: .meta, color: .secondary)
      FlexBox(direction: .column) { Text(y.bodyText, style: .body) }
        .padding(12).background(.card)
      FlexBox(direction: .row, spacing: 6, wrap: true) { for b in buttons { b } }
    }
    Task { try? await d.send(view) }
  }

  private func renderFollowUp() {
    guard let d = display, let y = current else { return }
    state = .followup
    peekTimer?.cancel()
    let chips = ChipSet.followUpChips(agent: y.agent)
    let buttons: [any ViewComponent] = chips.map { c in
      Button(label: c.label, style: .primary, onClick: { Task { @MainActor in self.onChip(c) } })
    }
    let view = FlexBox(direction: .column, spacing: 8) {
      Text("\(y.label) · follow-up", style: .meta, color: .secondary)
      FlexBox(direction: .column) { Text("pick a reply to send", style: .body) }
        .padding(12).background(.card)
      FlexBox(direction: .row, spacing: 6, wrap: true) {
        for b in buttons { b }
        Button(label: "back", style: .outline, onClick: { Task { @MainActor in self.renderExpanded() } })
      }
    }
    Task { try? await d.send(view) }
  }

  private func onEngage() { state = .engaged; peekTimer?.cancel(); renderExpanded() }
  private func onSnooze() {
    state = .snoozed
    let y = current
    closeSession(clearPending: false)
    snoozeTimer = Task { [weak self] in
      guard let ms = self?.snoozeMs else { return }
      try? await Task.sleep(nanoseconds: ms * 1_000_000)
      if let y { self?.yank(y) }
    }
  }
  private func onChip(_ c: Chip) {
    let y = current
    switch c.kind {
    case .send:
      if let y, let text = c.text { relay?.sendInput(thread: y.thread, text: text, enter: c.enter) }
      closeSession()
    case .dictate:
      if let y { Task { await startDictating(y) } }
    case .modify:
      closeSession()
    case .snooze: onSnooze()
    }
  }

  private func startDictating(_ y: AgentYank) async {
    if DictationManager.shared.isActive() { return }
    state = .dictating
    dictatingPartial = ""
    peekTimer?.cancel()
    relay?.sendDictateBegin(thread: y.thread)
    renderDictating()
    let dm = DictationManager.shared
    dm.onPartial = { [weak self] text in
      Task { @MainActor in
        self?.dictatingPartial = text
        self?.relay?.sendDictatePartial(thread: y.thread, text: text)
        self?.renderDictating()
      }
    }
    dm.onFinal = { [weak self] text in
      Task { @MainActor in
        self?.relay?.sendDictateCommit(thread: y.thread, text: text)
        self?.closeSession()
      }
    }
    dm.onCancelled = { [weak self] in
      Task { @MainActor in
        self?.relay?.sendDictateAbort(thread: y.thread)
        self?.state = .peeking
        self?.renderPeek()
        self?.armPeekTimer()
      }
    }
    dm.onError = { [weak self] msg in
      Task { @MainActor in
        self?.relay?.sendDictateAbort(thread: y.thread)
        NSLog("[face-chat] dictate error: \(msg)")
        self?.closeSession()
      }
    }
    await dm.start()
  }

  private func renderDictating() {
    guard let d = display, let y = current else { return }
    let line = dictatingPartial.trimmingCharacters(in: .whitespacesAndNewlines)
    let body = line.isEmpty ? "listening… speak now" : String(line.prefix(220))
    let view = FlexBox(direction: .column, spacing: 8) {
      Text("dictating", style: .meta, color: .secondary)
      FlexBox(direction: .column) { Text(body, style: .body) }
        .padding(12).background(.card)
      FlexBox(direction: .row, spacing: 6, wrap: true) {
        Button(label: "send", style: .primary, onClick: { Task { @MainActor in
          DictationManager.shared.stop(commit: true)
        }})
        Button(label: "cancel", style: .secondary, onClick: { Task { @MainActor in
          DictationManager.shared.stop(commit: false)
        }})
      }
    }
    Task { try? await d.send(view) }
  }

  private func closeSession(clearPending: Bool = true) {
    if DictationManager.shared.isActive() {
      DictationManager.shared.stop(commit: false)
    }
    dictatingPartial = ""
    peekTimer?.cancel(); peekTimer = nil
    session?.stop()
    display = nil
    session = nil
    current = nil
    state = .ambient
    let next = clearPending ? pending : nil
    if clearPending { pending = nil }
    if let next { yank(next) }
  }
}
