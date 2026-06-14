package com.lowkey.facechat.hud

data class AgentYank(
  val thread: String,
  val label: String,
  val agent: String = "generic",
  val lastAssistant: String = "",
  val lastUserInput: String = "",
  val awaiting: Awaiting = Awaiting.DONE,
  val permissionPrompt: String? = null,
) {
  val bodyText: String
    get() {
      val parts = mutableListOf<String>()
      when (awaiting) {
        Awaiting.PERMISSION -> {
          permissionPrompt?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            ?: lastAssistant.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        else -> lastAssistant.takeIf { it.isNotBlank() }?.let { parts.add(it) }
      }
      lastUserInput.takeIf { it.isNotBlank() }?.let { parts.add("You: $it") }
      return parts.joinToString("\n\n").ifBlank { lastAssistant }
    }

  val metaLine: String
    get() = when (awaiting) {
      Awaiting.PERMISSION -> "$label - needs approval"
      Awaiting.QUESTION   -> "$label - question"
      Awaiting.DONE       -> "$label - done"
      else                -> label
    }
}

enum class Awaiting { PERMISSION, QUESTION, DONE }
