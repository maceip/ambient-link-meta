// Minimal observable wrapper around the Wearables singleton, just what MainView needs
// (registration state + connect button). The DAT iOS sample app ships a much fuller version
// (device list, firmware update prompts, etc.) at
// vendor/meta-wearables-dat-ios/samples/DisplayAccess/.../WearablesViewModel.swift — if you
// want UI for any of that, lift bits from there.
import Foundation
import MWDATCore
import Observation

@Observable @MainActor
final class WearablesViewModel {
  var registrationState: RegistrationState = .unavailable
  private let wearables: any WearablesInterface
  private var watch: Task<Void, Never>?

  init(wearables: any WearablesInterface) {
    self.wearables = wearables
    self.registrationState = wearables.registrationState
    watch = Task { [weak self] in
      guard let wearables = self?.wearables else { return }
      for await rs in wearables.registrationStateStream() {
        await MainActor.run { self?.registrationState = rs }
      }
    }
  }

  func connectGlasses() async {
    do { try await wearables.startRegistration() }
    catch { NSLog("[face-chat] startRegistration failed: \(error)") }
  }
}
