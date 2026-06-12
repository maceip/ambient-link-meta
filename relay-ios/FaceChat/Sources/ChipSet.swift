// Swift twin of phone-android/.../hud/ChipSet.kt. Keep heuristic patterns in sync — they live
// in phone-shared/PROTOCOL.md as the canonical reference.
import Foundation

enum ChipKind { case send, askFollowup, dismiss, snooze }
struct Chip {
  let label: String
  let text:  String?
  let enter: Bool
  let kind:  ChipKind
  init(_ label: String, _ text: String?, enter: Bool = true, kind: ChipKind = .send) {
    self.label = label; self.text = text; self.enter = enter; self.kind = kind
  }
}

enum ChipSet {
  static func forLastAssistant(_ last: String) -> [Chip] {
    let trimmed = last.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let approvalPrompt = last.range(of: #"\b[yY]/[nN]\b"#, options: .regularExpression) != nil
      || trimmed.contains("approve this") || trimmed.contains("do you want to allow")
    if approvalPrompt {
      return [
        Chip("approve", "y"),
        Chip("deny",    "n"),
        Chip("dismiss", nil, kind: .dismiss),
      ]
    }
    let asksQuestion = trimmed.hasSuffix("?")
      || trimmed.contains("should i") || trimmed.contains("would you like")
      || trimmed.contains("do you want") || trimmed.contains("shall i")
    if asksQuestion {
      return [
        Chip("yes", "yes"),
        Chip("no", "no"),
        Chip("tell me more", "tell me more"),
        Chip("dismiss", nil, kind: .dismiss),
      ]
    }
    return [
      Chip("continue", "continue"),
      Chip("looks good", "looks good, thanks"),
      Chip("ask follow-up", nil, kind: .askFollowup),
      Chip("dismiss", nil, kind: .dismiss),
    ]
  }
}
