package com.lowkey.facechat.hud

// Pattern-classify the agent's last message to pick the expanded-card chip set.
// Matches the table in phone-shared/PROTOCOL.md. Keep iOS in sync.
//
// A chip carries a label, an outgoing `text` payload, an `enter` flag, and a `kind` that
// the HudPresenter uses to decide whether to fire-and-close (most chips) vs open a
// follow-up picker (`ASK_FOLLOWUP`) vs handle as a session control (`DISMISS`, `SNOOZE`).
data class Chip(val label: String, val text: String?, val enter: Boolean = true, val kind: ChipKind = ChipKind.SEND)
enum class ChipKind { SEND, ASK_FOLLOWUP, DISMISS, SNOOZE }

object ChipSet {
  private val SEND_OK     = Chip("looks good",   "looks good, thanks")
  private val SEND_GO     = Chip("continue",     "continue")
  private val SEND_YES    = Chip("yes",          "yes")
  private val SEND_NO     = Chip("no",           "no")
  private val SEND_MORE   = Chip("tell me more", "tell me more")
  private val SEND_APPRV  = Chip("approve",      "y")
  private val SEND_DENY   = Chip("deny",         "n")
  private val ASK_FOLLOW  = Chip("ask follow-up", null, kind = ChipKind.ASK_FOLLOWUP)
  private val DISMISS     = Chip("dismiss",      null, kind = ChipKind.DISMISS)
  private val SNOOZE      = Chip("snooze",       null, kind = ChipKind.SNOOZE)

  fun forLastAssistant(last: String): List<Chip> {
    val trimmed = last.trim().lowercase()
    val isApprovalPrompt =
      Regex("\\b[yY]/[nN]\\b").containsMatchIn(last) ||
      "approve this" in trimmed || "do you want to allow" in trimmed
    if (isApprovalPrompt) return listOf(SEND_APPRV, SEND_DENY, DISMISS)

    val asksQuestion = trimmed.endsWith("?") ||
      "should i"      in trimmed || "would you like" in trimmed ||
      "do you want"   in trimmed || "shall i"        in trimmed
    if (asksQuestion) return listOf(SEND_YES, SEND_NO, SEND_MORE, DISMISS)

    return listOf(SEND_GO, SEND_OK, ASK_FOLLOW, DISMISS)
  }
}
