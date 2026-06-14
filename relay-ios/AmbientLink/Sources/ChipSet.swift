import Foundation

enum ChipKind { case send, dictate, snooze, modify }
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
  private static let cont   = Chip("continue", "continue")
  private static let apprv  = Chip("approve", "y")
  private static let deny   = Chip("deny", "n")
  private static let dictate = Chip("dictate", nil, kind: .dictate)

  static func forYank(_ yank: AgentYank) -> [Chip] {
    switch yank.awaiting {
    case .permission: return [apprv, deny]
    case .question:   return [dictate]
    case .done:       return [cont, dictate]
    }
  }

  static func followUpChips(agent: String) -> [Chip] {
    let key = agent.lowercased()
    var extras: [Chip] = []
    if key.contains("codex") { extras.append(Chip("fix errors", "fix any errors and try again")) }
    if key.contains("claude") { extras.append(Chip("continue task", "continue with the current task")) }
    return [
      Chip("change it", "actually, change the approach"),
      Chip("explain more", "can you explain that in more detail?"),
      Chip("what's next?", "what should we do next?"),
    ] + extras
  }
}
