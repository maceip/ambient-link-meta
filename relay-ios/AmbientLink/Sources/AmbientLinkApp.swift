// ambient-link-meta — iOS daemon entry. Configures the DAT SDK on app launch,
// then presents the one-screen MainView for relay-URL config + status.
import MWDATCore
import SwiftUI

@main
struct AmbientLinkApp: App {
  @State private var wearablesVM: WearablesViewModel
  @State private var daemon: DaemonState

  init() {
    do { try Wearables.configure() }
    catch { NSLog("[ambient-link] Wearables.configure failed: \(error)") }

    let wearables = Wearables.shared
    self._wearablesVM = State(wrappedValue: WearablesViewModel(wearables: wearables))
    self._daemon      = State(wrappedValue: DaemonState(wearables: wearables))
  }

  var body: some Scene {
    WindowGroup {
      MainView(wearablesVM: wearablesVM, daemon: daemon)
    }
  }
}
