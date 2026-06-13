// DEBUG-only direct DAT round-trip. Mirror of phone-android MainActivity.debugFireWidget:
// bypasses the relay / WS / HudPresenter entirely — picks the first known device, opens a
// session, adds a Display, and sends a one-shot hello card. Isolates the question "can we
// render a widget on the HUD at all?" from the relay/event path. All logs under tag fc.debug.
//
// Lives in its own file (no `import SwiftUI`) because the MWDATDisplay DSL exposes `Text`
// and `Button` types that would collide with SwiftUI's in MainView.swift.
import Foundation
import MWDATCore
import MWDATDisplay

@MainActor
func debugFireWidget() {
  let wearables = Wearables.shared
  let ids = wearables.devices
  NSLog("[fc.debug] known devices count=\(ids.count)")
  for id in ids {
    let d = wearables.deviceForIdentifier(id)
    NSLog("[fc.debug]   id=\(id) name=\(d?.name ?? "?") link=\(String(describing: d?.linkState)) disp=\(String(describing: d?.supportsDisplay()))")
  }
  guard let target = ids.first else { NSLog("[fc.debug] no known device"); return }
  NSLog("[fc.debug] createSession via SpecificDeviceSelector id=\(target)")
  do {
    let s = try wearables.createSession(deviceSelector: SpecificDeviceSelector(device: target))
    Task {
      for await st in s.stateStream() {
        NSLog("[fc.debug] session.state -> \(st)")
        if st == .started { await debugAttachAndRender(s) }
      }
    }
    try s.start()
  } catch {
    NSLog("[fc.debug] createSession FAIL: \(error)")
  }
}

@MainActor
private func debugAttachAndRender(_ s: DeviceSession) async {
  do {
    let cap = try s.addDisplay()
    NSLog("[fc.debug] addDisplay SUCCESS")
    _ = cap.statePublisher.listen { ds in
      Task { @MainActor in
        NSLog("[fc.debug] display.state -> \(ds)")
        if ds == .started { await debugSendCard(cap) }
      }
    }
    await cap.start()
  } catch {
    NSLog("[fc.debug] addDisplay FAIL: \(error)")
  }
}

@MainActor
private func debugSendCard(_ d: Display) async {
  let view = FlexBox(direction: .column, spacing: 8) {
    Text("debug", style: .meta, color: .secondary)
    FlexBox(direction: .column) {
      Text("Hello from face·chat debug.", style: .body)
    }
    .padding(12)
    .background(.card)
    FlexBox(direction: .row, spacing: 6, wrap: true) {
      Button(label: "ok", style: .primary, onClick: { NSLog("[fc.debug] button tapped") })
    }
  }
  do {
    try await d.send(view)
    NSLog("[fc.debug] send SUCCESS — widget should be on HUD now")
  } catch {
    NSLog("[fc.debug] send FAIL: \(error)")
  }
}
