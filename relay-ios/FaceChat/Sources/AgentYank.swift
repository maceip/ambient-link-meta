import Foundation

enum Awaiting { case permission, question, done }

struct AgentYank {
  let thread: String
  let label: String
  let agent: String
  let lastAssistant: String
  let lastUserInput: String
  let awaiting: Awaiting
  let permissionPrompt: String?

  var bodyText: String {
    switch awaiting {
    case .permission:
      let perm = (permissionPrompt?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
      var parts: [String] = []
      if let p = perm { parts.append(p) }
      else if !lastAssistant.isEmpty { parts.append(lastAssistant) }
      if !lastUserInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        parts.append("You: \(lastUserInput.trimmingCharacters(in: .whitespacesAndNewlines))")
      }
      return parts.joined(separator: "\n\n")
    case .question, .done:
      var parts: [String] = []
      if !lastAssistant.isEmpty { parts.append(lastAssistant) }
      if !lastUserInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        parts.append("You: \(lastUserInput.trimmingCharacters(in: .whitespacesAndNewlines))")
      }
      return parts.joined(separator: "\n\n")
    }
  }

  var metaLine: String {
    switch awaiting {
    case .permission: return "\(label) - needs approval"
    case .question:   return "\(label) - question"
    case .done:       return "\(label) - done"
    }
  }
}
