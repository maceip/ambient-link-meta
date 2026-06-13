import Foundation

enum Awaiting { case reply, permission }

struct AgentYank {
  let thread: String
  let label: String
  let agent: String
  let lastAssistant: String
  let awaiting: Awaiting
  let permissionPrompt: String?

  var bodyText: String {
    switch awaiting {
    case .permission:
      if let p = permissionPrompt, !p.isEmpty { return p }
      return lastAssistant
    case .reply:
      return lastAssistant
    }
  }

  var metaLine: String {
    switch awaiting {
    case .permission: return "\(label) · needs approval"
    case .reply:      return "\(label) · paused"
    }
  }
}
