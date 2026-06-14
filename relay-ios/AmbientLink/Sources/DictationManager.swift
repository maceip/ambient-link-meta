import AVFoundation
import Speech

/// On-device dictation via Apple Speech framework (iOS equivalent of Android SODA path).
@MainActor
final class DictationManager: NSObject {
  static let shared = DictationManager()

  private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
  private let engine = AVAudioEngine()
  private var request: SFSpeechAudioBufferRecognitionRequest?
  private var task: SFSpeechRecognitionTask?
  private var active = false

  var onPartial: ((String) -> Void)?
  var onFinal: ((String) -> Void)?
  var onCancelled: (() -> Void)?
  var onError: ((String) -> Void)?

  func isActive() -> Bool { active }

  func requestAuthorization() async -> Bool {
    await withCheckedContinuation { cont in
      SFSpeechRecognizer.requestAuthorization { status in
        cont.resume(returning: status == .authorized)
      }
    }
  }

  func start() async {
    if active { return }
    guard await requestAuthorization() else {
      onError?("speech permission denied")
      return
    }
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(.record, mode: .measurement, options: .duckOthers)
      try session.setActive(true, options: .notifyOthersOnDeactivation)
    } catch {
      onError?("audio session failed")
      return
    }

    request = SFSpeechAudioBufferRecognitionRequest()
    request?.shouldReportPartialResults = true
    guard let request else { return }

    let input = engine.inputNode
    let format = input.outputFormat(forBus: 0)
    input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
      self?.request?.append(buffer)
    }

    do {
      try engine.start()
    } catch {
      onError?("mic start failed")
      return
    }

    active = true
    task = recognizer?.recognitionTask(with: request) { [weak self] result, err in
      Task { @MainActor in
        guard let self else { return }
        if let err {
          self.onError?(err.localizedDescription)
          self.stop(commit: false)
          return
        }
        guard let result else { return }
        let text = result.bestTranscription.formattedString
        if result.isFinal {
          self.onFinal?(text)
          self.stop(commit: false)
        } else {
          self.onPartial?(text)
        }
      }
    }
  }

  func stop(commit: Bool) {
    if !active { return }
    active = false
    engine.stop()
    engine.inputNode.removeTap(onBus: 0)
    request?.endAudio()
    task?.cancel()
    task = nil
    request = nil
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    if !commit { onCancelled?() }
  }
}
