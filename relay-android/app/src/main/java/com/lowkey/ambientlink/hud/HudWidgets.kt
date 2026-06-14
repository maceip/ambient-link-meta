package com.lowkey.ambientlink.hud

import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// DAT display cards — up to three action chips on one row (primary first for focus ring).
object HudWidgets {
  private var dictateJob: Job? = null

  fun sendPeek(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    chips: List<Chip>,
    onChip: (Chip) -> Unit,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = 8, padding = 16) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(yank.bodyText.take(220), style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            chips.take(3).forEachIndexed { i, c ->
              val style = if (i == 0) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY
              button(c.label, style = style, onClick = { onChip(c) })
            }
          }
        }
      }
    }
  }

  fun sendListening(
    scope: CoroutineScope,
    display: Display,
    onCancel: () -> Unit,
  ) {
    sendDictating(scope, display, "", onCancel)
  }

  /** Live partial transcript while SODA listens; oneshot final auto-commits (no send tap). */
  fun sendDictating(
    scope: CoroutineScope,
    display: Display,
    partial: String,
    onCancel: () -> Unit,
  ) {
    dictateJob?.cancel()
    dictateJob = scope.launch {
      val line = partial.trim().ifBlank { "listening…" }
      display.sendContent {
        flexBox(gap = 8, padding = 16) {
          text("dictating", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(line.take(220), style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
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
        flexBox(gap = 8, padding = 16) {
          text("sent", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(text.take(220), style = TextStyle.BODY)
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
        flexBox(gap = 8, padding = 16) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(yank.bodyText, style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            chips.take(3).forEachIndexed { i, c ->
              val style = if (i == 0) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY
              button(c.label, style = style, onClick = { onChip(c) })
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
        flexBox(gap = 8, padding = 16) {
          text("dictate error", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(message.take(220), style = TextStyle.BODY)
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
        flexBox(gap = 8, padding = 16) {
          text("${yank.label} · modify", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text("pick a change to send", style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            chips.forEach { c ->
              button(c.label, style = ButtonStyle.PRIMARY, onClick = { onChip(c) })
            }
          }
        }
      }
    }
  }
}
