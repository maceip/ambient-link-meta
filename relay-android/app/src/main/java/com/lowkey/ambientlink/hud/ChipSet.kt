package com.lowkey.ambientlink.hud

// Pattern-classify agent output to pick HUD chip sets. Host sets awaiting=question|done.
// Peek cards use word labels on every button — DAT display often won't render emoji/glyphs.

data class Chip(
  val label: String,
  val text: String?,
  val enter: Boolean = true,
  val kind: ChipKind = ChipKind.SEND,
  val primary: Boolean = false,
)
enum class ChipKind { SEND, DICTATE, MODIFY, SNOOZE, BROWSE }

object ChipSet {
  private val CONTINUE = Chip("continue", "continue", kind = ChipKind.SEND, primary = true)
  private val APPROVE  = Chip("approve", "y", kind = ChipKind.SEND, primary = true)
  private val DENY     = Chip("deny", "n", kind = ChipKind.SEND)
  private val DICTATE  = Chip("dictate", null, kind = ChipKind.DICTATE)

  fun forYank(yank: AgentYank): List<Chip> = when (yank.awaiting) {
    Awaiting.PERMISSION -> listOf(APPROVE, DENY)
    Awaiting.QUESTION   -> listOf(DICTATE.copy(primary = true))
    Awaiting.DONE       -> listOf(CONTINUE, DICTATE)
    else                -> listOf(CONTINUE, DICTATE)
  }

  fun followUpChips(agent: String): List<Chip> {
    val agentKey = agent.lowercase()
    val extras = when {
      "codex" in agentKey -> listOf(Chip("fix errors", "fix any errors and try again"))
      "claude" in agentKey -> listOf(Chip("continue task", "continue with the current task"))
      else -> emptyList()
    }
    return listOf(
      Chip("change it", "actually, change the approach"),
      Chip("explain more", "can you explain that in more detail?"),
      Chip("what's next?", "what should we do next?"),
    ) + extras
  }
}
