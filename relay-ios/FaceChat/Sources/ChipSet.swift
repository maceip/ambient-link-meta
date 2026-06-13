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
  private static let dismiss = Chip("dismiss", nil, kind: .dismiss)

  static func forYank(_ yank: AgentYank) -> [Chip] {
    if yank.awaiting == .permission {
      return [Chip("approve", "y"), Chip("deny", "n"), dismiss]
    }
    return forLastAssistant(yank.bodyText)
  }

  static func forLastAssistant(_ last: String) -> [Chip] {
    let trimmed = last.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let approvalPrompt = last.range(of: #"\b[yY]/[nN]\b"#, options: .regularExpression) != nil
      || trimmed.contains("approve this") || trimmed.contains("do you want to allow")
      || trimmed.contains("allow this")
    if approvalPrompt {
      return [Chip("approve", "y"), Chip("deny", "n"), dismiss]
    }
    let asksQuestion = trimmed.hasSuffix("?")
      || trimmed.contains("should i") || trimmed.contains("would you like")
      || trimmed.contains("do you want") || trimmed.contains("shall i")
    if asksQuestion {
      return [
        Chip("yes", "yes"), Chip("no", "no"),
        Chip("tell me more", "tell me more"), dismiss,
      ]
    }
    return [
      Chip("continue", "continue"),
      Chip("looks good", "looks good, thanks"),
      Chip("ask follow-up", nil, kind: .askFollowup),
      dismiss,
    ]
  }

  static func followUpChips(agent: String) -> [Chip] {
    let key = agent.lowercased()
    var extras: [Chip] = []
    if key.contains("codex") { extras.append(Chip("fix errors", "fix any errors and try again")) }
    if key.contains("claude") { extras.append(Chip("continue task", "continue with the current task")) }
    return [
      Chip("what's next?", "what should we do next?"),
      Chip("explain more", "can you explain that in more detail?"),
    ] + extras + [dismiss]
  }
}
