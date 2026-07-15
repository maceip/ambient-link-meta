package com.lowkey.ambientlink.hud

import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.FlexBoxScope
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// DAT display cards — up to three action chips on one row (primary first for focus ring).
object HudWidgets {
  private const val ROOT_GAP = 10
  private const val ROOT_PADDING = 16
  private const val CARD_PADDING = 14
  private const val ACTION_GAP = 12
  private const val MAX_ACTIONS = 3
  private const val MAX_BODY_CHARS = 220

  /** Single in-flight sendContent — prevents peek overwriting dictate (and vice versa). */
  private var contentJob: Job? = null

  private fun postContent(scope: CoroutineScope, block: suspend () -> Unit) {
    contentJob?.cancel()
    contentJob = scope.launch { block() }
  }

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

  /** Bare button child — passing alignSelf/flex params wraps the button in a
   *  FlexChildWrapper, and the glasses renderer then sizes the wrapper instead
   *  of the label: text stays start-anchored and clips at the trailing edge.
   *  Bare buttons size to their label. Row centering lives on actionChipRow. */
  private fun FlexBoxScope.hudChipButton(label: String, style: ButtonStyle, onClick: () -> Unit) {
    button(label, style = style, onClick = onClick)
  }

  private fun FlexBoxScope.actionChipRow(block: FlexBoxScope.() -> Unit) {
    flexBox(
      direction = Direction.ROW,
      gap = ACTION_GAP,
      padding = 0,
      alignment = Alignment.CENTER,
      crossAlignment = Alignment.CENTER,
      // Three full-width chips can exceed the 600px waveguide; wrap to a second
      // line instead of letting the renderer shrink-clip every label.
      wrap = true,
      background = FlexBoxBackground.NONE,
    ) {
      block()
    }
  }

  /** Blank waveguide then removeDisplay — same path as HudPresenter tearDownDisplay. */
  suspend fun dismissWaveguide(display: Display, session: DatDisplaySession) {
    try {
      display.sendContent {
        flexBox(gap = 0, padding = 0, background = FlexBoxBackground.NONE) {}
      }
      delay(180)
    } catch (_: Throwable) {
    }
    session.sleepDisplay()
  }

  /** Debug card — same layout/chips as production HUD cards; OK dismisses the waveguide. */
  fun sendDebugCard(
    scope: CoroutineScope,
    display: Display,
    session: DatDisplaySession,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text("debug", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text("Hello from ambient link debug.", style = TextStyle.BODY)
          }
          actionChipRow {
            hudChipButton("ok", ButtonStyle.PRIMARY) {
              scope.launch { dismissWaveguide(display, session) }
            }
          }
        }
      }
    }
  }

  fun sendPeek(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    chips: List<Chip>,
    onChip: (Chip) -> Unit,
  ) {
    postContent(scope) {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(truncateBody(yank.bodyText), style = TextStyle.BODY)
          }
          actionChipRow {
            orderedChips(chips).forEach { c ->
              hudChipButton(c.label, chipStyle(c)) { onChip(c) }
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
    postContent(scope) {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(dictateCardBody(yank, partial), style = TextStyle.BODY)
          }
          actionChipRow {
            hudChipButton("cancel", ButtonStyle.SECONDARY) { onCancel() }
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
    postContent(scope) {
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
    postContent(scope) {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text(yank.bodyText, style = TextStyle.BODY)
          }
          actionChipRow {
            orderedChips(chips).forEach { c ->
              hudChipButton(c.label, chipStyle(c)) { onChip(c) }
            }
          }
        }
      }
    }
  }

  /** Failure card (dictate or delivery) — no action chips; caller auto-dismisses. */
  fun sendError(
    scope: CoroutineScope,
    display: Display,
    message: String,
    title: String = "dictate error",
  ) {
    postContent(scope) {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text(title, style = TextStyle.META, color = TextColor.SECONDARY)
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
    postContent(scope) {
      display.sendContent {
        flexBox(gap = ROOT_GAP, padding = ROOT_PADDING) {
          text("${yank.label} · modify", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = CARD_PADDING, background = FlexBoxBackground.CARD) {
            text("pick a change to send", style = TextStyle.BODY)
          }
          actionChipRow {
            orderedChips(chips).forEach { c ->
              hudChipButton(c.label, chipStyle(c)) { onChip(c) }
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
              hudChipButton(
                "${agentPrefix(r.agent)} ${r.label} ${statusLabel(r.status)}",
                ButtonStyle.PRIMARY,
              ) { onRow(r.thread) }
            }
          }
          actionChipRow {
            hudChipButton("back", ButtonStyle.SECONDARY) { onBack() }
            hudChipButton(if (filter == null) "all" else "All", if (filter == null) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY) { onFilter("all") }
            hudChipButton("cursor", if (filter == "cursor") ButtonStyle.PRIMARY else ButtonStyle.SECONDARY) { onFilter("cursor") }
            hudChipButton("codex", if (filter == "codex") ButtonStyle.PRIMARY else ButtonStyle.SECONDARY) { onFilter("codex") }
          }
        }
      }
    }
  }
}
