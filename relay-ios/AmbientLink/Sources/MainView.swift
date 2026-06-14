// Settings + status screen. Mirrors relay-android/MainActivity — pairing button, relay
// URL field, connection status, list of known threads. No chat UI: the HUD is the chat UI.
import MWDATCore
import SwiftUI

private let defaultsKey = "ambient.relayURL"

struct MainView: View {
  @Bindable var wearablesVM: WearablesViewModel
  @Bindable var daemon: DaemonState
  @State private var url: String = UserDefaults.standard.string(forKey: defaultsKey) ?? "wss://example.com/ambient-link/ws"

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 16) {
        Text("ambient link").font(.system(size: 22, weight: .bold))
        Text("background daemon — keeps glasses notifiable from your relay")
          .font(.system(size: 12)).foregroundStyle(.secondary)

        statusRow(label: "glasses pairing",
                  value: String(describing: wearablesVM.registrationState),
                  color: pairingColor(wearablesVM.registrationState))
        if wearablesVM.registrationState != .registered {
          Button("pair glasses") { Task { await wearablesVM.connectGlasses() } }
            .buttonStyle(.borderedProminent)
        }

        statusRow(label: "relay",
                  value: daemon.connected ? "connected" : "disconnected",
                  color: daemon.connected ? .green : .red)
        if !daemon.threads.isEmpty {
          Text("threads: \(daemon.threads.joined(separator: ", "))")
            .font(.system(size: 12)).foregroundStyle(.secondary)
        }
        if let err = daemon.lastError {
          Text("last error: \(err)").font(.system(size: 11)).foregroundStyle(.red)
        }

        TextField("relay URL", text: $url)
          .keyboardType(.URL).autocapitalization(.none).disableAutocorrection(true)
          .textFieldStyle(.roundedBorder)
        HStack {
          Button("start daemon") {
            UserDefaults.standard.set(url, forKey: defaultsKey)
            daemon.start(url: url)
          }.buttonStyle(.borderedProminent)
          Button("stop") { daemon.stop() }.buttonStyle(.bordered)
        }

        // ── DEBUG ────────────────────────────────────────────────────────
        // Direct DAT round-trip: pick the first known device, createSession,
        // addDisplay, sendContent a one-shot card. Bypasses relay/WS — isolates
        // "can we render a widget on the HUD at all?" Logs under tag ambient.debug.
        Spacer().frame(height: 8)
        Text("debug").font(.system(size: 11)).foregroundStyle(.secondary)
        Button("DEBUG: fire widget") { debugFireWidget() }.buttonStyle(.bordered)
      }
      .padding(20)
    }
    .background(Color.black)
    .preferredColorScheme(.dark)
  }

  @ViewBuilder private func statusRow(label: String, value: String, color: Color) -> some View {
    HStack(spacing: 10) {
      Circle().fill(color).frame(width: 10, height: 10)
      Text(label).foregroundStyle(.secondary).font(.system(size: 13))
      Spacer()
      Text(value).font(.system(size: 13))
    }
  }
  private func pairingColor(_ s: RegistrationState) -> Color {
    switch s {
    case .registered:                 return .green
    case .available, .registering:    return .orange
    default:                          return .red
    }
  }
}
