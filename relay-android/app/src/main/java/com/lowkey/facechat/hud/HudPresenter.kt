package com.lowkey.facechat.hud

import android.util.Log
import com.lowkey.facechat.relay.RelayClient
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Owns the DAT session lifecycle and renders the peek + expanded cards.
// State machine matches phone-shared/PROTOCOL.md.
//
// One presenter per RelayService instance. Last-yank-wins: if a new ThreadIdle arrives
// while ENGAGED, the current session is replaced.
class HudPresenter(private val relay: RelayClient) {
  enum class State { AMBIENT, PEEKING, ENGAGED, SNOOZED }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var session: DeviceSession? = null
  private var display: Display? = null
  private var current: Yank? = null
  private var peekTimer: Job? = null
  private var snoozeTimer: Job? = null
  private var state: State = State.AMBIENT

  data class Yank(val thread: String, val label: String, val lastAssistant: String)

  // The peek auto-dismisses after this if the user doesn't engage.
  private val PEEK_TIMEOUT_MS = 12_000L
  // After "snooze" the same yank re-fires after this delay.
  private val SNOOZE_MS = 60_000L

  fun yank(thread: String, label: String, lastAssistant: String) {
    // Cancel any queued snooze; replace any open peek/engaged session.
    snoozeTimer?.cancel(); snoozeTimer = null
    current = Yank(thread, label, lastAssistant)
    openSessionAndPeek()
  }

  fun cancelIfFor(thread: String) {
    if (current?.thread == thread) closeSession()
  }

  private fun openSessionAndPeek() {
    if (display != null) {
      // Already have a live session — just re-render the peek with the new content.
      state = State.PEEKING
      renderPeek()
      armPeekTimer()
      return
    }
    // Strategy: skip the AutoDeviceSelector filter (which rejects any device whose
    // link isn't already CONNECTED). Pick the first known display-capable device id
    // and let the SDK try to bring its link up on demand via SpecificDeviceSelector.
    val knownIds = Wearables.devices.value
    val meta = Wearables.devicesMetadata
    val candidate = knownIds.firstOrNull { id ->
      val d = meta[id]?.value
      d != null && d.deviceType.name == "META_RAYBAN_DISPLAY" && d.isDisplayCapable()
    }
    if (candidate == null) {
      Log.w("HudPresenter", "no display-capable device known to SDK (${knownIds.size} total)")
      return
    }
    val initial = meta[candidate]?.value
    Log.i("HudPresenter", "createSession via SpecificDeviceSelector id=$candidate link=${initial?.linkState} name=${initial?.name}")
    val selector = SpecificDeviceSelector(candidate)
    Wearables.createSession(selector).fold(
      onSuccess = { s ->
        Log.i("HudPresenter", "createSession SUCCESS via SpecificDeviceSelector")
        session = s
        scope.launch {
          s.state.collect { st ->
            Log.i("HudPresenter", "session.state -> $st")
            if (st == DeviceSessionState.STARTED && display == null) attachDisplay(s)
          }
        }
        s.start()
      },
      onFailure = { e, _ ->
        Log.w("HudPresenter", "createSession failed: ${e.description}")
        Log.w("HudPresenter", "  known device count = ${knownIds.size}")
        knownIds.forEach { id ->
          val d = meta[id]?.value
          Log.w("HudPresenter", "  device id=$id name=${d?.name} link=${d?.linkState} type=${d?.deviceType} compat=${d?.compatibility} disp=${d?.isDisplayCapable()}")
        }
      },
    )
  }

  private fun attachDisplay(s: DeviceSession) {
    s.addDisplay().fold(
      onSuccess = { d ->
        display = d
        scope.launch {
          d.state.collect { ds ->
            if (ds == DisplayState.STARTED) { state = State.PEEKING; renderPeek(); armPeekTimer() }
          }
        }
      },
      onFailure = { e, _ -> Log.w("HudPresenter", "addDisplay failed: ${e.description}") },
    )
  }

  private fun armPeekTimer() {
    peekTimer?.cancel()
    peekTimer = scope.launch {
      delay(PEEK_TIMEOUT_MS)
      if (state == State.PEEKING) closeSession()
    }
  }

  // ── Peek card: label, truncated message, [open] [snooze] [dismiss]
  private fun renderPeek() {
    val d = display ?: return
    val y = current ?: return
    scope.launch {
      d.sendContent {
        flexBox(gap = 8, padding = 16) {
          text(y.label, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(y.lastAssistant.take(200), style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            button("open",    style = ButtonStyle.PRIMARY,   onClick = { onEngage() })
            button("snooze",  style = ButtonStyle.SECONDARY, onClick = { onSnooze() })
            button("dismiss", style = ButtonStyle.OUTLINE,   onClick = { closeSession() })
          }
        }
      }
    }
  }

  // ── Expanded card: full message + classified chip set
  private fun renderExpanded() {
    val d = display ?: return
    val y = current ?: return
    val chips = ChipSet.forLastAssistant(y.lastAssistant)
    scope.launch {
      d.sendContent {
        flexBox(gap = 8, padding = 16) {
          text(y.label, style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text(y.lastAssistant, style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
            chips.forEach { c ->
              val style = when (c.kind) {
                ChipKind.SEND        -> ButtonStyle.PRIMARY
                ChipKind.ASK_FOLLOWUP -> ButtonStyle.SECONDARY
                ChipKind.DISMISS     -> ButtonStyle.OUTLINE
                ChipKind.SNOOZE      -> ButtonStyle.OUTLINE
              }
              button(c.label, style = style, onClick = { onChip(c) })
            }
          }
        }
      }
    }
  }

  private fun onEngage() { state = State.ENGAGED; peekTimer?.cancel(); renderExpanded() }
  private fun onSnooze() {
    state = State.SNOOZED
    val y = current
    closeSession()
    snoozeTimer = scope.launch {
      delay(SNOOZE_MS)
      if (y != null) yank(y.thread, y.label, y.lastAssistant)
    }
  }
  private fun onChip(c: Chip) {
    val y = current
    when (c.kind) {
      ChipKind.SEND -> {
        if (y != null && c.text != null) relay.sendInput(y.thread, c.text, c.enter)
        closeSession()
      }
      ChipKind.ASK_FOLLOWUP -> { /* TODO: secondary chip picker — pre-canned + history */ closeSession() }
      ChipKind.DISMISS -> closeSession()
      ChipKind.SNOOZE  -> onSnooze()
    }
  }

  private fun closeSession() {
    peekTimer?.cancel(); peekTimer = null
    try { session?.stop() } catch (_: Throwable) {}
    display = null
    session = null
    current = null
    state = State.AMBIENT
  }
}
