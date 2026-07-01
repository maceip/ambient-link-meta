package com.lowkey.ambientlink.hud

// Pattern-classify agent output to pick HUD chip sets. Host sets awaiting=question|done.

// One-label rule: the primary chip renders its word label; the rest render their
// glyph only (see HudWidgets). Permission keeps both approve/deny labeled for safety.
data class Chip(
  val label: String,
  val text: String?,
  val enter: Boolean = true,
  val kind: ChipKind = ChipKind.SEND,
  val glyph: String = "",
  val primary: Boolean = false,
)
enum class ChipKind { SEND, DICTATE, MODIFY, SNOOZE, BROWSE }

object ChipSet {
  private val CONTINUE = Chip("Continue", "continue", kind = ChipKind.SEND, glyph = "▶", primary = true)
  private val APPROVE  = Chip("Approve", "y", kind = ChipKind.SEND, glyph = "✓", primary = true)
  private val DENY     = Chip("Deny", "n", kind = ChipKind.SEND, glyph = "✕", primary = true)
  private val DICTATE_GLYPH   = Chip("Dictate", null, kind = ChipKind.DICTATE, glyph = "🎙")
  private val DICTATE_PRIMARY = Chip("Dictate", null, kind = ChipKind.DICTATE, glyph = "🎙", primary = true)
  private val BROWSE   = Chip("Browse", null, kind = ChipKind.BROWSE, glyph = "▤")

  fun forYank(yank: AgentYank): List<Chip> = when (yank.awaiting) {
    Awaiting.PERMISSION -> listOf(APPROVE, DENY, BROWSE)
    Awaiting.QUESTION   -> listOf(DICTATE_PRIMARY, BROWSE)
    Awaiting.DONE       -> listOf(CONTINUE, DICTATE_GLYPH, BROWSE)
    else                -> listOf(CONTINUE, DICTATE_GLYPH, BROWSE)
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
