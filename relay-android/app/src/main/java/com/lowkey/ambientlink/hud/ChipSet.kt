package com.lowkey.ambientlink.hud

import android.content.Context
import com.lowkey.ambientlink.settings.UserPrefs

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

data class ActionConfig(
  val quickReplies: List<String> = emptyList(),
  val showContinue: Boolean = true,
  val showDictate: Boolean = true,
)

object ChipSet {
  private const val MAX_CHIPS = 3

  private val CONTINUE = Chip("continue", "continue", kind = ChipKind.SEND, primary = true)
  private val APPROVE  = Chip("approve", "y", kind = ChipKind.SEND, primary = true)
  private val DENY     = Chip("deny", "n", kind = ChipKind.SEND)
  private val DICTATE  = Chip("dictate", null, kind = ChipKind.DICTATE, primary = true)

  fun config(ctx: Context): ActionConfig = ActionConfig(
    quickReplies = UserPrefs.getQuickReplies(ctx),
    showContinue = UserPrefs.showContinueChip(ctx),
    showDictate = UserPrefs.showDictateChip(ctx),
  )

  fun forYank(yank: AgentYank, config: ActionConfig = ActionConfig()): List<Chip> = when (yank.awaiting) {
    Awaiting.PERMISSION -> listOf(APPROVE, DENY)
    Awaiting.QUESTION   -> buildActionRow(config, includeContinue = false)
    Awaiting.DONE       -> buildActionRow(config, includeContinue = config.showContinue)
    else                -> buildActionRow(config, includeContinue = config.showContinue)
  }

  private fun buildActionRow(config: ActionConfig, includeContinue: Boolean): List<Chip> {
    val out = mutableListOf<Chip>()
    // User quick replies first so they aren't pushed off by continue + dictate.
    config.quickReplies.forEach { text ->
      if (out.size >= MAX_CHIPS) return@forEach
      val t = text.trim()
      if (t.isEmpty()) return@forEach
      if (includeContinue && config.showContinue && t.equals("continue", ignoreCase = true)) return@forEach
      out.add(quickReplyChip(t))
    }
    if (config.showDictate && out.size < MAX_CHIPS) out.add(DICTATE)
    if (includeContinue && config.showContinue && out.size < MAX_CHIPS) out.add(CONTINUE)
    return out.take(MAX_CHIPS)
  }

  private fun quickReplyChip(text: String): Chip {
    val t = text.trim()
    val label = if (t.length <= 16) t else t.take(14).trimEnd() + "…"
    return Chip(label, t, kind = ChipKind.SEND)
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
