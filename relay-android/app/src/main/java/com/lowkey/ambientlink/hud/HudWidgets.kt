package com.lowkey.ambientlink.hud

import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// DAT display cards — up to three action chips on one row (primary first for focus ring).
object HudWidgets {
  private const val ROOT_GAP = 10
  private const val ROOT_PADDING = 16
  private const val CARD_PADDING = 14
  private const val ACTION_GAP = 12
  private const val MAX_ACTIONS = 3
  private const val MAX_BODY_CHARS = 220

  private var dictateJob: Job? = null

  private fun truncateBody(text: String): String =
    if (text.length <= MAX_BODY_CHARS) text else text.take(MAX_BODY_CHARS - 1) + "…"

  /** Original card body plus live dictation under a You: line — keeps the same CARD shell. */
  private fun dictateCardBody(yank: AgentYank, partial: String): String {
    val base = yank.bodyText.trim()
    val userLine = partial.trim().ifBlank { "listening…" }
    val combined = if (base.isBlank()) "You: $userLine" else "$base\n\nYou: $userLine"
    return truncateBody(combined)
  }

  /** Matches web `chipset.js` — send/dictate chips are primary; deny/modify stay secondary. */
  private fun chipStyle(chip: Chip): ButtonStyle = when {
    chip.primary -> ButtonStyle.PRIMARY
    chip.kind == ChipKind.DICTATE -> ButtonStyle.PRIMARY
    chip.kind == ChipKind.SEND -> ButtonStyle.SECONDARY
    else -> ButtonStyle.SECONDARY
  }

  private fun orderedChips(chips: List<Chip>): List<Chip> =
    chips.sortedByDescending { it.primary }.take(MAX_ACTIONS)

  fun sendPeek(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    chips: List<Chip>,
    onChip: (Chip) -> Unit,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(truncateBody(yank.bodyText), style = TextStyle.BODY)
          }
          flexBox(
            direction = Direction.ROW,
            gap = ACTION_GAP,
            padding = 0,
            crossAlignment = Alignment.CENTER,
            background = FlexBoxBackground.NONE,
          ) {
            orderedChips(chips).forEach { c ->
              button(c.label, style = chipStyle(c), onClick = { onChip(c) })
            }
          }
        }
      }
    }
  }

  fun sendListening(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    onCancel: () -> Unit,
  ) {
    sendDictating(scope, display, yank, "", onCancel)
  }

  /** Live partial transcript while SODA listens; each sendContent replaces the full layout. */
  fun sendDictating(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    partial: String,
    onCancel: () -> Unit,
  ) {
    dictateJob?.cancel()
    dictateJob = scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(dictateCardBody(yank, partial), style = TextStyle.BODY)
          }
          flexBox(
            direction = Direction.ROW,
            gap = ACTION_GAP,
            padding = 0,
            crossAlignment = Alignment.CENTER,
            background = FlexBoxBackground.NONE,
          ) {
            button("cancel", style = ButtonStyle.SECONDARY, onClick = onCancel)
          }
        }
      }
    }
  }

  /** Brief confirmation — shows recognized text before auto-dismiss. */
  fun sendDictateConfirm(
    scope: CoroutineScope,
    display: Display,
    text: String,
  ) {
    dictateJob?.cancel()
    dictateJob = scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text("sent", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(truncateBody(text), style = TextStyle.BODY)
          }
        }
      }
    }
  }

  fun sendExpanded(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    chips: List<Chip>,
    onChip: (Chip) -> Unit,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(yank.bodyText, style = TextStyle.BODY)
          }
          flexBox(
            direction = Direction.ROW,
            gap = ACTION_GAP,
            padding = 0,
            crossAlignment = Alignment.CENTER,
            background = FlexBoxBackground.NONE,
          ) {
            orderedChips(chips).forEach { c ->
              button(c.label, style = chipStyle(c), onClick = { onChip(c) })
            }
          }
        }
      }
    }
  }

  /** Dictate failed — no action chips; caller auto-dismisses. */
  fun sendError(
    scope: CoroutineScope,
    display: Display,
    message: String,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text("dictate error", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(truncateBody(message), style = TextStyle.BODY)
          }
        }
      }
    }
  }

  fun sendFollowUp(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    chips: List<Chip>,
    onChip: (Chip) -> Unit,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text("${yank.label} · modify", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text("pick a change to send", style = TextStyle.BODY)
          }
          flexBox(
            direction = Direction.ROW,
            gap = ACTION_GAP,
            padding = 0,
            crossAlignment = Alignment.CENTER,
            background = FlexBoxBackground.NONE,
          ) {
            orderedChips(chips).forEach { c ->
              button(c.label, style = chipStyle(c), onClick = { onChip(c) })
            }
          }
        }
      }
    }
  }

  // Native session browser — a scrollable list of session rows (newest at the
  // bottom) plus a bottom shelf of filter glyphs. Rows and glyphs are DAT buttons.
  data class HudSession(val thread: String, val label: String, val agent: String, val status: String)

  private fun agentPrefix(agent: String): String = when {
    agent.lowercase().contains("cursor") -> "Cu"
    agent.lowercase().contains("claude") -> "Cl"
    agent.lowercase().contains("codex") || agent.lowercase().contains("openai") -> "Cx"
    else -> "Ag"
  }

  private fun statusLabel(status: String): String = when (status) {
    "permission" -> "perm"
    "question" -> "ask"
    "busy" -> "busy"
    "done" -> "done"
    else -> status.take(4)
  }

  fun sendSessionList(
    scope: CoroutineScope,
    display: Display,
    rows: List<HudSession>,
    filter: String?,
    onRow: (String) -> Unit,
    onFilter: (String) -> Unit,
    onBack: () -> Unit,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(if (filter == null) "sessions" else "$filter sessions", style = TextStyle.META, color = TextColor.SECONDARY)
          if (rows.isEmpty()) {
            flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
              text("no sessions yet", style = TextStyle.BODY)
            }
          } else {
            rows.takeLast(8).forEach { r ->
              button("${agentPrefix(r.agent)} ${r.label} ${statusLabel(r.status)}", style = ButtonStyle.PRIMARY, onClick = { onRow(r.thread) })
            }
          }
          flexBox(
            direction = Direction.ROW,
            gap = ACTION_GAP,
            padding = 0,
            crossAlignment = Alignment.CENTER,
            background = FlexBoxBackground.NONE,
          ) {
            button("back", style = ButtonStyle.SECONDARY, onClick = { onBack() })
            button(if (filter == null) "all" else "All", style = if (filter == null) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, onClick = { onFilter("all") })
            button("cursor", style = if (filter == "cursor") ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, onClick = { onFilter("cursor") })
            button("codex", style = if (filter == "codex") ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, onClick = { onFilter("codex") })
          }
        }
      }
    }
  }
}
