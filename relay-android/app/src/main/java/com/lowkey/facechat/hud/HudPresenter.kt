package com.lowkey.facechat.hud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lowkey.facechat.dictation.DictationCallback
import com.lowkey.facechat.dictation.DictationManager
import com.lowkey.facechat.relay.RelayClient
import com.lowkey.facechat.wearables.WearablesRepository
import com.lowkey.facechat.wearables.WearablesRuntime
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.display.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HudPresenter(
  private val appContext: Context,
  private val relay: RelayClient,
  private val wearables: WearablesRepository,
) {
  enum class State { AMBIENT, PEEKING, ENGAGED, FOLLOWUP, DICTATING, SNOOZED }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val datSession = DatDisplaySession(scope)
  private var current: AgentYank? = null
  private var pending: AgentYank? = null
  private var dictatingPartial = ""
  private var peekTimer: Job? = null
  private var snoozeTimer: Job? = null
  private var state: State = State.AMBIENT

  private val PEEK_TIMEOUT_MS = 30_000L
  private val DICTATE_TIMEOUT_MS = 300_000L
  private val SNOOZE_MS = 60_000L
  private val LINK_WAIT_MS = 20_000L

  fun yank(yank: AgentYank) {
    if (!WearablesRuntime.initialized) {
      Log.w("HudPresenter", "Wearables SDK not initialized (open app and grant BT permissions)")
      return
    }
    if ((state == State.PEEKING || state == State.ENGAGED || state == State.FOLLOWUP) &&
      current?.thread != null && current?.thread != yank.thread) {
      pending = yank
      Log.i("HudPresenter", "queued yank thread=${yank.thread} (busy with ${current?.thread})")
      return
    }
    snoozeTimer?.cancel(); snoozeTimer = null
    current = yank
    openSessionAndPeek()
  }

  fun refresh(yank: AgentYank) {
    if (current?.thread == yank.thread &&
      (state == State.PEEKING || state == State.ENGAGED || state == State.FOLLOWUP)) {
      Log.i("HudPresenter", "refresh thread=${yank.thread} state=$state")
      current = yank
      renderCurrent()
      return
    }
    // Host echoes our reply as thread_idle — don't pop the card back up.
    if (state == State.AMBIENT && yank.lastUserInput.isNotBlank()) {
      Log.i("HudPresenter", "skip re-yank after user reply thread=${yank.thread}")
      return
    }
    yank(yank)
  }

  fun cancelIfFor(thread: String) {
    if (current?.thread == thread) closeSession()
    if (pending?.thread == thread) pending = null
  }

  private fun isHudDevice(d: com.meta.wearable.dat.core.types.Device): Boolean =
    d.isDisplayCapable()

  private fun connectedDisplayDevice(): DeviceIdentifier? {
    val meta = wearables.devicesMetadata.value
    return wearables.devices.value.firstOrNull { id ->
      val d = meta[id]
      d != null && isHudDevice(d) && d.linkState == LinkState.CONNECTED
    }
  }

  private fun displayCapableDevice(): DeviceIdentifier? {
    val meta = wearables.devicesMetadata.value
    return wearables.devices.value.firstOrNull { id ->
      val d = meta[id]
      d != null && isHudDevice(d)
    }
  }

  private fun openSessionAndPeek() {
    if (datSession.activeDisplay != null) {
      state = State.PEEKING
      renderPeek()
      armPeekTimer()
      return
    }

    val connected = connectedDisplayDevice()
    if (connected != null) {
      prepareAndPeek(connected)
      return
    }

    val candidate = displayCapableDevice()
    if (candidate == null) {
      Log.w("HudPresenter", "no display-capable device known to SDK — waiting for pairing")
      waitForDisplayDeviceThenPeek()
      return
    }

    scope.launch {
      val ok = withTimeoutOrNull(LINK_WAIT_MS) {
        wearables.devicesMetadata.first { meta ->
          meta[candidate]?.linkState == LinkState.CONNECTED
        }
      } != null
      if (ok) prepareAndPeek(candidate)
      else {
        Log.w("HudPresenter", "timed out waiting for CONNECTED — retrying when link appears")
        waitForDisplayDeviceThenPeek()
      }
    }
  }

  private fun waitForDisplayDeviceThenPeek() {
    scope.launch {
      withTimeoutOrNull(LINK_WAIT_MS * 3) {
        wearables.devicesMetadata.first { meta ->
          meta.values.any { isHudDevice(it) && it.linkState == LinkState.CONNECTED }
        }
      } ?: run {
        Log.w("HudPresenter", "still no connected display device after wait")
        return@launch
      }
      val id = connectedDisplayDevice() ?: return@launch
      prepareAndPeek(id)
    }
  }

  private fun prepareAndPeek(deviceId: DeviceIdentifier) {
    datSession.prepareDisplay(deviceId) { d ->
      state = State.PEEKING
      renderPeek(d)
      armPeekTimer()
    }
  }

  private fun armPeekTimer() {
    peekTimer?.cancel()
    peekTimer = scope.launch {
      delay(PEEK_TIMEOUT_MS)
      if (state == State.PEEKING) closeSession()
    }
  }

  private fun renderCurrent() {
    when (state) {
      State.PEEKING -> renderPeek()
      State.ENGAGED -> renderExpanded()
      State.FOLLOWUP -> renderFollowUp()
      State.DICTATING -> renderDictating()
      else -> {}
    }
  }

  private fun renderPeek(d: Display? = datSession.activeDisplay) {
    val display = d ?: return
    val y = current ?: return
    val chips = ChipSet.forYank(y)
    val primary = chips.firstOrNull() ?: return
    val secondary = chips.getOrNull(1) ?: return

    HudWidgets.sendPeek(
      scope, display, y,
      onPrimary = { onChip(primary) },
      onSecondary = { onChip(secondary) },
      primaryLabel = primary.label,
      secondaryLabel = secondary.label,
    )
  }

  private fun renderExpanded() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    HudWidgets.sendExpanded(scope, d, y, ChipSet.forYank(y), ::onChip)
  }

  private fun renderFollowUp() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    state = State.FOLLOWUP
    peekTimer?.cancel()
    HudWidgets.sendFollowUp(scope, d, y, ChipSet.followUpChips(y.agent), ::onChip)
  }

  private fun renderDictating() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    HudWidgets.sendDictating(
      scope, d, y, dictatingPartial,
      onSend = { finishDictating(commit = true) },
      onCancel = { finishDictating(commit = false) },
    )
  }

  private fun finishDictating(commit: Boolean) {
    val y = current ?: return
    if (commit) {
      DictationManager.stop(commitPartial = true)
    } else {
      DictationManager.stop(commitPartial = false)
      relay.sendDictateAbort(y.thread)
      dictatingPartial = ""
      state = State.PEEKING
      renderPeek()
      armPeekTimer()
    }
  }

  private fun startDictating(y: AgentYank) {
    if (DictationManager.isActive()) return
    state = State.DICTATING
    dictatingPartial = ""
    peekTimer?.cancel()
    peekTimer = scope.launch {
      delay(DICTATE_TIMEOUT_MS)
      if (state == State.DICTATING) finishDictating(commit = true)
    }
    relay.sendDictateBegin(y.thread)
    renderDictating()
    DictationManager.start(
      appContext,
      object : DictationCallback {
        override fun onPartial(text: String) {
          dictatingPartial = text
          relay.sendDictatePartial(y.thread, text)
          renderDictating()
        }
        override fun onFinal(text: String) {
          dictatingPartial = ""
          relay.sendDictateCommit(y.thread, text)
          DictationManager.stop(commitPartial = false, notify = false)
          closeSession()
        }
        override fun onCancelled() {
          relay.sendDictateAbort(y.thread)
          dictatingPartial = ""
          state = State.PEEKING
          renderPeek()
          armPeekTimer()
        }
        override fun onError(message: String) {
          Log.w("HudPresenter", "dictate error: $message")
          relay.sendDictateAbort(y.thread)
          dictatingPartial = ""
          state = State.PEEKING
          renderPeek()
          armPeekTimer()
        }
      },
    )
  }

  private fun onChip(c: Chip) {
    val y = current ?: return
    if (state == State.DICTATING) {
      when (c.label) {
        "send" -> finishDictating(commit = true)
        "cancel" -> finishDictating(commit = false)
      }
      return
    }
    Log.i("HudPresenter", "chip tapped: ${c.label} thread=${y.thread}")
    when (c.kind) {
      ChipKind.SEND -> {
        if (c.text != null) {
          relay.sendInput(y.thread, c.text, c.enter)
          closeSession()
        }
      }
      ChipKind.DICTATE -> startDictating(y)
      ChipKind.MODIFY -> {
        try {
          val url = relay.companionComposeUrl(y.thread)
          Log.i("HudPresenter", "modify → companion $url")
          appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          )
        } catch (e: Exception) {
          Log.e("HudPresenter", "modify open failed: ${e.message}")
        }
        closeSession()
      }
      ChipKind.DISMISS -> closeSession()
      ChipKind.SNOOZE -> onSnooze()
    }
  }

  private fun onSnooze() {
    state = State.SNOOZED
    val y = current
    closeSession(clearPending = false)
    snoozeTimer = scope.launch {
      delay(SNOOZE_MS)
      if (y != null) yank(y)
    }
  }

  private fun closeSession(clearPending: Boolean = true) {
    peekTimer?.cancel(); peekTimer = null
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    dictatingPartial = ""
    datSession.stop()
    current = null
    state = State.AMBIENT
    val next = if (clearPending) pending.also { pending = null } else null
    if (next != null) yank(next)
  }
}
