package com.lowkey.ambientlink.hud

// Pattern-classify agent output to pick HUD chip sets. Host sets awaiting=question|done.

data class Chip(val label: String, val text: String?, val enter: Boolean = true, val kind: ChipKind = ChipKind.SEND)
enum class ChipKind { SEND, DICTATE, MODIFY, SNOOZE }

object ChipSet {
  private val SEND_CONT   = Chip("continue",     "continue")
  private val SEND_VERIFY = Chip("verify",       "please verify the tasks are completed")
  private val SEND_APPRV  = Chip("approve",      "y")
  private val SEND_DENY   = Chip("deny",         "n")
  private val DICTATE     = Chip("dictate",      null, kind = ChipKind.DICTATE)
  private val MODIFY      = Chip("modify",       null, kind = ChipKind.MODIFY)

  fun forYank(yank: AgentYank): List<Chip> = when (yank.awaiting) {
    Awaiting.PERMISSION -> listOf(SEND_APPRV, SEND_DENY)
    Awaiting.QUESTION   -> listOf(DICTATE)
    Awaiting.DONE       -> listOf(SEND_CONT, DICTATE)
    else                -> listOf(SEND_CONT, DICTATE)
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
