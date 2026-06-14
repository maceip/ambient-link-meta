package com.lowkey.ambientlink.hud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lowkey.ambientlink.dictation.DictationCallback
import com.lowkey.ambientlink.dictation.DictationManager
import com.lowkey.ambientlink.relay.RelayClient
import com.lowkey.ambientlink.relay.RelayService
import com.lowkey.ambientlink.wearables.WearablesRepository
import com.lowkey.ambientlink.wearables.WearablesRuntime
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
import java.util.ArrayDeque

class HudPresenter(
  private val appContext: Context,
  private val relay: RelayClient,
  private val wearables: WearablesRepository,
) {
  enum class State { AMBIENT, PEEKING, ENGAGED, FOLLOWUP, DICTATING, SNOOZED }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val datSession = GlassesDisplay.session
  private var current: AgentYank? = null
  private val queue = ArrayDeque<AgentYank>()
  private var dictatingPartial = ""
  private var peekTimer: Job? = null
  private var snoozeTimer: Job? = null
  private var renderJob: Job? = null
  private var state: State = State.AMBIENT
  private var lastRenderedKey: String? = null
  /** True while DAT session is opening — blocks duplicate yanks from clobbering. */
  private var opening = false

  private val PEEK_TIMEOUT_MS = 300_000L
  private val DICTATE_TIMEOUT_MS = 300_000L
  private val SNOOZE_MS = 60_000L
  private val LINK_WAIT_MS = 20_000L
  private val RENDER_DEBOUNCE_MS = 200L
  private var commitJob: Job? = null
  private val COMMIT_SHOW_MS = 2_300L
  private val DICTATE_RENDER_DEBOUNCE_MS = 350L
  /** Drop host idle refreshes right after the user dictates (avoids a second card). */
  private var suppressIdleThread: String? = null
  private var suppressIdleUntilMs = 0L

  fun yank(yank: AgentYank) {
    if (!WearablesRuntime.initialized) {
      Log.w("HudPresenter", "Wearables SDK not initialized (open app and grant BT permissions)")
      return
    }
    if (state == State.DICTATING && current?.thread == yank.thread) {
      Log.i("HudPresenter", "ignore yank while dictating thread=${yank.thread}")
      return
    }
    val showing = isOccupied() || opening || current != null
    if (showing) {
      if (current?.thread == yank.thread) {
        if (current?.bodyText == yank.bodyText) {
          Log.i("HudPresenter", "skip duplicate yank thread=${yank.thread}")
          return
        }
        Log.i("HudPresenter", "update thread=${yank.thread} state=$state")
        current = yank
        if (state != State.DICTATING) scheduleRender(immediate = true) { renderCurrent() }
        return
      }
      enqueue(yank)
      Log.i("HudPresenter", "queued yank thread=${yank.thread} (showing ${current?.thread}, q=${queue.size})")
      return
    }
    snoozeTimer?.cancel(); snoozeTimer = null
    current = yank
    state = State.PEEKING
    opening = true
    lastRenderedKey = null
    openSessionAndPeek()
  }

  /** Host `thread_idle` — surfaces agent idle; does not clobber an open card. */
  fun onIdle(yank: AgentYank) {
    if (shouldSuppressIdle(yank.thread)) {
      Log.i("HudPresenter", "ignore thread_idle (post-reply) thread=${yank.thread}")
      return
    }
    if (isOccupied() || opening || commitJob?.isActive == true) {
      if (yank.awaiting == Awaiting.PERMISSION && current?.thread == yank.thread) {
        current = yank
        scheduleRender(immediate = true) { renderCurrent() }
        return
      }
      Log.i("HudPresenter", "ignore thread_idle while card showing thread=${yank.thread}")
      return
    }
    if (state == State.AMBIENT && yank.lastUserInput.isNotBlank()) {
      Log.i("HudPresenter", "skip re-yank after user reply thread=${yank.thread}")
      return
    }
    yank(yank)
  }

  private fun shouldSuppressIdle(thread: String): Boolean =
    thread == suppressIdleThread && System.currentTimeMillis() < suppressIdleUntilMs

  fun cancelIfFor(thread: String) {
    queue.removeAll { it.thread == thread }
    Log.i("HudPresenter", "thread_busy $thread (HUD stays until dismissed)")
  }

  private fun isOccupied(): Boolean =
    state == State.PEEKING || state == State.ENGAGED || state == State.FOLLOWUP || state == State.DICTATING

  private fun enqueue(yank: AgentYank) {
    queue.clear()
    if (queue.any { it.thread == yank.thread && it.bodyText == yank.bodyText }) return
    queue.addLast(yank)
  }

  private fun dequeueNext(): AgentYank? {
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (next.bodyText.isNotBlank()) return next
    }
    return null
  }

  private fun scheduleRender(immediate: Boolean = false, block: () -> Unit) {
    renderJob?.cancel()
    val debounce = if (state == State.DICTATING) DICTATE_RENDER_DEBOUNCE_MS else RENDER_DEBOUNCE_MS
    renderJob = scope.launch {
      if (!immediate) delay(debounce)
      block()
    }
  }

  private fun renderKey(yank: AgentYank, partial: String = "", mode: String): String =
    "$mode|${yank.thread}|${yank.bodyText}|$partial"

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
      opening = false
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
        opening = false
        return@launch
      }
      val id = connectedDisplayDevice() ?: return@launch
      prepareAndPeek(id)
    }
  }

  private fun prepareAndPeek(deviceId: DeviceIdentifier) {
    datSession.prepareDisplay(
      deviceId,
      onReady = { d ->
        opening = false
        state = State.PEEKING
        renderPeek(d)
        armPeekTimer()
      },
      onFailed = {
        Log.e("HudPresenter", "display session failed — will retry once")
        opening = false
        scope.launch {
          delay(800)
          if (current == null || state != State.PEEKING) return@launch
          opening = true
          datSession.stop()
          delay(400)
          openSessionAndPeek()
        }
      },
    )
  }

  private fun armPeekTimer() {
    peekTimer?.cancel()
    peekTimer = scope.launch {
      delay(PEEK_TIMEOUT_MS)
      if (state == State.PEEKING) dismissCard()
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
    if (chips.isEmpty()) return
    val key = renderKey(y, mode = "peek")
    if (key == lastRenderedKey) return
    lastRenderedKey = key
    HudWidgets.sendPeek(scope, display, y, chips, ::onChip)
  }

  private fun renderExpanded() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    val key = renderKey(y, mode = "expanded")
    if (key == lastRenderedKey) return
    lastRenderedKey = key
    HudWidgets.sendExpanded(scope, d, y, ChipSet.forYank(y), ::onChip)
  }

  private fun renderFollowUp() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    state = State.FOLLOWUP
    peekTimer?.cancel()
    val key = renderKey(y, mode = "followup")
    if (key == lastRenderedKey) return
    lastRenderedKey = key
    HudWidgets.sendFollowUp(scope, d, y, ChipSet.followUpChips(y.agent), ::onChip)
  }

  private fun renderDictating() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    val key = renderKey(y, partial = dictatingPartial, mode = "dictate")
    if (key == lastRenderedKey) return
    lastRenderedKey = key
    HudWidgets.sendDictating(
      scope, d, dictatingPartial,
      onCancel = { finishDictating(commit = false) },
    )
  }

  private fun finishDictating(commit: Boolean) {
    val y = current ?: return
    RelayService.setMicrophoneForeground(false)
    if (commit) {
      val harvested = DictationManager.harvestPartial()
      val text = (dictatingPartial.trim().ifBlank { harvested }).trim()
      if (text.isBlank()) {
        showDictateError("no speech detected — tap dictate and try again")
        return
      }
      commitDictation(y, text)
    } else {
      DictationManager.stop(commitPartial = false)
      relay.sendDictateAbort(y.thread)
      dictatingPartial = ""
      state = State.PEEKING
      lastRenderedKey = null
      renderPeek()
      armPeekTimer()
    }
  }

  private fun commitDictation(y: AgentYank, text: String) {
    RelayService.setMicrophoneForeground(false)
    DictationManager.stop(commitPartial = false, notify = false)
    peekTimer?.cancel()
    dictatingPartial = text
    lastRenderedKey = null
    queue.clear()
    suppressIdleThread = y.thread
    suppressIdleUntilMs = System.currentTimeMillis() + 6_000L
    Log.i("HudPresenter", "dictate commit thread=${y.thread} text=$text")
    relay.sendDictateCommit(y.thread, text)
    val d = datSession.activeDisplay
    if (d == null) {
      dismissCard()
      return
    }
    HudWidgets.sendDictateConfirm(scope, d, text)
    commitJob?.cancel()
    commitJob = scope.launch {
      delay(COMMIT_SHOW_MS)
      dismissCard()
    }
  }

  private fun startDictating(y: AgentYank) {
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    RelayService.setMicrophoneForeground(true)
    state = State.DICTATING
    dictatingPartial = ""
    lastRenderedKey = null
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
          Log.i("HudPresenter", "dictate partial: $text")
          dictatingPartial = text
          relay.sendDictatePartial(y.thread, text)
          lastRenderedKey = null
          renderDictating()
        }
        override fun onFinal(text: String) {
          if (state != State.DICTATING) {
            Log.w("HudPresenter", "dictate final ignored — state=$state text=$text")
            return
          }
          val spoken = text.trim()
          Log.i("HudPresenter", "dictate final: $spoken")
          DictationManager.stop(commitPartial = false, notify = false)
          if (spoken.isBlank()) {
            RelayService.setMicrophoneForeground(false)
            showDictateError("no speech detected — speak after the call UI appears on glasses")
            return
          }
          commitDictation(y, spoken)
        }
        override fun onCancelled() {
          RelayService.setMicrophoneForeground(false)
          relay.sendDictateAbort(y.thread)
          dictatingPartial = ""
          state = State.PEEKING
          lastRenderedKey = null
          renderPeek()
          armPeekTimer()
        }
        override fun onError(message: String) {
          Log.w("HudPresenter", "dictate error: $message")
          relay.sendDictateAbort(y.thread)
          dictatingPartial = ""
          showDictateError(message)
        }
      },
      // Phone mic — no Bluetooth SCO / call UI (keeps screen recording alive).
      // Speak toward the phone. Set true to route glasses HFP mic (shows call UI on glasses).
      useBluetoothSco = false,
    )
  }

  private fun showDictateError(message: String) {
    RelayService.setMicrophoneForeground(false)
    DictationManager.stop(commitPartial = false, notify = false)
    dictatingPartial = ""
    state = State.PEEKING
    val d = datSession.activeDisplay ?: run {
      dismissCard()
      return
    }
    val friendly = when (message) {
      "soda_pack_unconfigured", "soda_pack_asset_mismatch" -> "speech pack missing — rebuild app with SODA assets"
      "soda_unavailable", "soda_native_unavailable" -> "on-device speech not available on this phone"
      "mic_denied" -> "microphone permission required — open ambient link on phone once"
      "mic_silenced" -> "microphone blocked — allow mic for ambient link in phone settings"
      "mic_start_failed" -> "could not start microphone"
      else -> message
    }
    lastRenderedKey = null
    HudWidgets.sendError(scope, d, friendly)
    scope.launch {
      delay(8_000)
      dismissCard()
    }
  }

  private fun onChip(c: Chip) {
    val y = current ?: return
    if (state == State.DICTATING) {
      if (c.label == "cancel") finishDictating(commit = false)
      return
    }
    Log.i("HudPresenter", "chip tapped: ${c.label} thread=${y.thread}")
    when (c.kind) {
      ChipKind.SEND -> {
        if (c.text != null) {
          relay.sendInput(y.thread, c.text, c.enter)
          dismissCard()
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
        dismissCard()
      }
      ChipKind.SNOOZE -> onSnooze()
    }
  }

  private fun onSnooze() {
    state = State.SNOOZED
    val y = current
    val keepQueue = queue.toList()
    queue.clear()
    queue.addAll(keepQueue)
    dismissCard(showNext = false)
    snoozeTimer = scope.launch {
      delay(SNOOZE_MS)
      if (y != null) yank(y)
    }
  }

  /** Hide current card; keep DAT session alive if more are queued. */
  private fun dismissCard(showNext: Boolean = true) {
    peekTimer?.cancel(); peekTimer = null
    commitJob?.cancel(); commitJob = null
    renderJob?.cancel(); renderJob = null
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    dictatingPartial = ""
    current = null
    lastRenderedKey = null
    opening = false
    state = State.AMBIENT

    if (!showNext) {
      tearDownDisplay()
      return
    }

    val next = dequeueNext()
    if (next != null) {
      current = next
      state = State.PEEKING
      val d = datSession.activeDisplay
      if (d != null) {
        Log.i("HudPresenter", "next card thread=${next.thread} (session kept alive)")
        renderPeek(d)
        armPeekTimer()
      } else {
        openSessionAndPeek()
      }
      return
    }

    tearDownDisplay()
  }

  /** Ends the glasses display session — the "call" UI goes away. */
  private fun tearDownDisplay() {
    Log.i("HudPresenter", "tear down display session")
    datSession.stop()
  }
}
