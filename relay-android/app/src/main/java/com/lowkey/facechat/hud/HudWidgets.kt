package com.lowkey.facechat.hud

import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// DAT display cards — peek is always two actions max (primary first for glasses focus ring).
object HudWidgets {
  fun sendPeek(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    primaryLabel: String,
    secondaryLabel: String,
  ) {
    scope.launch {
      display.sendContent {
        flexBox(gap = 8, padding = 16) {
          text(yank.metaLine, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(yank.bodyText.take(220), style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            button(primaryLabel, style = ButtonStyle.PRIMARY, onClick = onPrimary)
            button(secondaryLabel, style = ButtonStyle.SECONDARY, onClick = onSecondary)
          }
        }
      }
    }
  }

  fun sendListening(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
  ) {
    sendDictating(scope, display, yank, "", {}, {})
  }

  /** Live partial transcript on glasses; tap send when done speaking. */
  fun sendDictating(
    scope: CoroutineScope,
    display: Display,
    yank: AgentYank,
    partial: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
  ) {
    scope.launch {
      val line = partial.trim().ifBlank { "listening… speak now" }
      display.sendContent {
        flexBox(gap = 8, padding = 16) {
          text("dictating", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(line.take(220), style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            button("send", style = ButtonStyle.PRIMARY, onClick = onSend)
            button("cancel", style = ButtonStyle.SECONDARY, onClick = onCancel)
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
            chips.take(2).forEachIndexed { i, c ->
              val style = if (i == 0) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY
              button(c.label, style = style, onClick = { onChip(c) })
            }
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
