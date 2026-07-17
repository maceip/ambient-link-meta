package com.lowkey.ambientlink.hud

import android.content.Context
import android.util.Log
import com.lowkey.ambientlink.dictation.DictationCallback
import com.lowkey.ambientlink.dictation.DictationManager
import com.lowkey.ambientlink.relay.RelayClient
import com.lowkey.ambientlink.relay.RelayService
import com.lowkey.ambientlink.settings.UserPrefs
import com.lowkey.ambientlink.wearables.WearablesRepository
import com.lowkey.ambientlink.wearables.WearablesRuntime
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.FlexBoxBackground
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
  enum class State { AMBIENT, PEEKING, ENGAGED, FOLLOWUP, DICTATING, SNOOZED, BROWSING }

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

  // Native session browser model (mirrors the web home list).
  private val sessions = LinkedHashMap<String, SessionRow>()
  private var browseFilter: String? = null
  data class SessionRow(
    val thread: String,
    var label: String,
    var agent: String,
    var status: String,
    var lastEventAt: Long,
    var yank: AgentYank?,
  )

  private val PEEK_TIMEOUT_MS = 300_000L
  private val DICTATE_TIMEOUT_MS = 300_000L
  private val SNOOZE_MS = 60_000L
  private val LINK_WAIT_MS = 20_000L
  private val RENDER_DEBOUNCE_MS = 200L
  // Auto-advance: a "done" card left untouched counts down, then fires its
  // primary action (continue). Permission/question cards never auto-advance.
  private val AUTO_ADVANCE_SECS = 5
  private var autoJob: Job? = null
  private var commitJob: Job? = null
  private val COMMIT_SHOW_MS = 2_300L
  private val DICTATE_RENDER_DEBOUNCE_MS = 350L
  /** Drop host idle refreshes right after the user responds, so the lingering
   *  idle for that thread doesn't immediately re-open the card and keep the
   *  glasses lit while the agent picks up the input. */
  private var suppressIdleThread: String? = null
  private var suppressIdleUntilMs = 0L
  private val RESPONDED_SUPPRESS_MS = 12_000L
  // After the user responds, keep the screen dark for a beat so a different
  // session's idle event doesn't immediately re-open a menu on the glasses.
  private var quietUntilMs = 0L
  private val QUIET_AFTER_RESPONSE_MS = 10_000L
  /** When the web companion is on the glasses display, queue native HUD peeks.
   *  Lease expires if web stops heartbeating (~15s) for [WEB_COMPANION_LEASE_MS]. */
  private var webCompanionScreen: String? = null
  private var webCompanionAtMs = 0L
  private var companionLeaseJob: Job? = null
  private val WEB_COMPANION_LEASE_MS = 45_000L

  private fun webScreenActive(): Boolean {
    val s = webCompanionScreen ?: return false
    return s.isNotBlank() && s != "idle"
  }

  private fun webOccupiesDisplay(): Boolean {
    if (!webScreenActive()) return false
    if (System.currentTimeMillis() - webCompanionAtMs > WEB_COMPANION_LEASE_MS) {
      clearWebCompanionLease("lease_expired")
      return false
    }
    return true
  }

  private fun clearWebCompanionLease(reason: String) {
    val was = webScreenActive()
    webCompanionScreen = null
    companionLeaseJob?.cancel()
    companionLeaseJob = null
    Log.i("HudPresenter", "companion_ui cleared reason=$reason")
    if (was) maybeShowQueuedAfterWeb()
  }

  private fun rescheduleCompanionLease() {
    companionLeaseJob?.cancel()
    if (!webScreenActive()) return
    companionLeaseJob = scope.launch {
      delay(WEB_COMPANION_LEASE_MS + 250L)
      if (webScreenActive() &&
        System.currentTimeMillis() - webCompanionAtMs >= WEB_COMPANION_LEASE_MS
      ) {
        clearWebCompanionLease("lease_expired")
      }
    }
  }

  /** Web app owns the waveguide — hide native DAT and queue yanks until idle. */
  fun onCompanionUi(screen: String) {
    val wasOccupied = webOccupiesDisplay()
    webCompanionScreen = screen
    webCompanionAtMs = System.currentTimeMillis()
    rescheduleCompanionLease()
    Log.i("HudPresenter", "companion_ui screen=$screen occupied=${webOccupiesDisplay()}")
    if (webOccupiesDisplay()) {
      if (isOccupied() || opening || datSession.activeDisplay != null) {
        current?.let { y ->
          queue.removeAll { it.thread == y.thread }
          queue.addLast(y)
        }
        dismissCard(showNext = false)
      }
      return
    }
    if (!wasOccupied) return
    maybeShowQueuedAfterWeb()
  }

  private fun maybeShowQueuedAfterWeb() {
    if (webOccupiesDisplay() || isOccupied() || opening) return
    dequeueNext()?.let { yank(it) }
  }

  private fun wakeReason(yank: AgentYank): String = when (yank.awaiting) {
    Awaiting.PERMISSION -> "permission"
    Awaiting.QUESTION -> "question"
    Awaiting.DONE -> "done"
  }

  /** Always publish — even when web owns the display — so the web can stack
   *  Switch targets for sessions that would have peeked the HUD. */
  private fun publishWakeHint(yank: AgentYank) {
    try {
      relay.sendWakeHint(yank.thread, wakeReason(yank))
    } catch (e: Exception) {
      Log.w("HudPresenter", "wake_hint failed: ${e.message}")
    }
  }

  fun yank(yank: AgentYank) {
    if (!WearablesRuntime.initialized) {
      Log.w("HudPresenter", "Wearables SDK not initialized (open app and grant BT permissions)")
      return
    }
    if (UserPrefs.isSnoozing(appContext)) {
      Log.i("HudPresenter", "snooze — discard yank thread=${yank.thread}")
      return
    }
    upsertSession(yank)
    publishWakeHint(yank)
    if (webOccupiesDisplay()) {
      enqueue(yank)
      Log.i("HudPresenter", "queued yank — web owns display ($webCompanionScreen) thread=${yank.thread}")
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
    upsertSession(yank)
    if (UserPrefs.isSnoozing(appContext)) {
      Log.i("HudPresenter", "snooze — discard idle thread=${yank.thread}")
      return
    }

    val actionable = yank.awaiting == Awaiting.PERMISSION || yank.awaiting == Awaiting.QUESTION

    if (actionable) {
      publishWakeHint(yank)
      if (isOccupied() || opening || commitJob?.isActive == true) {
        if (current?.thread == yank.thread) {
          current = yank
          scheduleRender(immediate = true) { renderCurrent() }
        } else {
          enqueue(yank)
        }
        return
      }
      if (webOccupiesDisplay()) {
        enqueue(yank)
        Log.i("HudPresenter", "queued actionable idle — web owns display thread=${yank.thread}")
        return
      }
      // yank() publishes again — fine; web de-dupes by thread.
      yank(yank)
      return
    }

    if (System.currentTimeMillis() < quietUntilMs) {
      Log.i("HudPresenter", "quiet after response — skip idle thread=${yank.thread}")
      return
    }
    if (shouldSuppressIdle(yank.thread)) {
      Log.i("HudPresenter", "ignore thread_idle (post-reply) thread=${yank.thread}")
      return
    }
    if (isOccupied() || opening || commitJob?.isActive == true) {
      Log.i("HudPresenter", "ignore thread_idle while card showing thread=${yank.thread}")
      return
    }
    if (state == State.AMBIENT && yank.lastUserInput.isNotBlank()) {
      Log.i("HudPresenter", "skip re-yank after user reply thread=${yank.thread}")
      return
    }
    if (webOccupiesDisplay()) {
      publishWakeHint(yank)
      enqueue(yank)
      Log.i("HudPresenter", "queued idle — web owns display ($webCompanionScreen) thread=${yank.thread}")
      return
    }
    yank(yank)
  }

  private fun shouldSuppressIdle(thread: String): Boolean =
    thread == suppressIdleThread && System.currentTimeMillis() < suppressIdleUntilMs

  /** Mark a thread just-responded-to so the host's still-idle event for it does
   *  not re-open the card and re-light the display right after we tear down. */
  private fun markResponded(thread: String) {
    suppressIdleThread = thread
    suppressIdleUntilMs = System.currentTimeMillis() + RESPONDED_SUPPRESS_MS
    quietUntilMs = System.currentTimeMillis() + QUIET_AFTER_RESPONSE_MS
  }

  fun cancelIfFor(thread: String) {
    sessions[thread]?.let { it.status = "busy"; it.lastEventAt = System.currentTimeMillis() }
    queue.removeAll { it.thread == thread }
    Log.i("HudPresenter", "thread_busy $thread (HUD stays until dismissed)")
  }

  /** Seed the browser's session list from the relay hello (labels + agents). */
  fun hello(threads: List<RelayClient.ThreadMeta>) {
    for (t in threads) {
      val row = sessions.getOrPut(t.id) {
        SessionRow(t.id, t.label, t.agent, "online", System.currentTimeMillis(), null)
      }
      if (t.label.isNotBlank()) row.label = t.label
      if (t.agent.isNotBlank()) row.agent = t.agent
    }
  }

  /** Host `thread_ended` — the session is gone; drop its row and queued cards. */
  fun threadEnded(thread: String) {
    sessions.remove(thread)
    queue.removeAll { it.thread == thread }
    if (current?.thread == thread && state != State.DICTATING) {
      Log.i("HudPresenter", "thread_ended $thread — dismissing its card")
      dismissCard()
    }
  }

  /**
   * v2 lifecycle: a failed input_status means our reply never reached the
   * agent. Undo the post-response idle suppression (the turn is still open),
   * surface the failure, then bring the card back so the user can retry.
   */
  fun onInputFailed(thread: String, error: String) {
    val t = thread.ifBlank { suppressIdleThread ?: return }
    Log.w("HudPresenter", "input failed thread=$t err=$error")
    sessions[t]?.let { it.status = "failed"; it.lastEventAt = System.currentTimeMillis() }
    if (suppressIdleThread == t) {
      suppressIdleThread = null
      suppressIdleUntilMs = 0
    }
    quietUntilMs = 0
    val retry = sessions[t]?.yank
    showTransientError("not delivered", error.ifBlank { "reply failed to reach agent" }) {
      retry?.let { yank(it) }
    }
  }

  /** v2 `dictate_end ok=false` — committed text did NOT land; replace the "sent" confirm. */
  fun onDictateFailed(thread: String, error: String) {
    if (suppressIdleThread == thread) {
      suppressIdleThread = null
      suppressIdleUntilMs = 0
      quietUntilMs = 0
    }
    sessions[thread]?.let { it.status = "failed"; it.lastEventAt = System.currentTimeMillis() }
    if (current?.thread == thread && commitJob?.isActive == true) {
      commitJob?.cancel()
      showDictateError(error.ifBlank { "dictation did not reach the agent" })
    } else {
      Log.w("HudPresenter", "dictate failed thread=$thread err=$error")
    }
  }

  /** Render an error card briefly if we own the display, then run [after]. */
  private fun showTransientError(title: String, message: String, after: () -> Unit = {}) {
    val d = datSession.activeDisplay
    if (d == null || webOccupiesDisplay()) {
      after()
      return
    }
    lastRenderedKey = null
    HudWidgets.sendError(scope, d, message, title)
    scope.launch {
      delay(3_000)
      after()
    }
  }

  private fun upsertSession(y: AgentYank) {
    val row = sessions.getOrPut(y.thread) {
      SessionRow(y.thread, y.label, y.agent, "online", 0L, null)
    }
    if (y.label.isNotBlank()) row.label = y.label
    if (y.agent.isNotBlank()) row.agent = y.agent
    row.status = when (y.awaiting) {
      Awaiting.PERMISSION -> "permission"
      Awaiting.QUESTION -> "question"
      else -> "done"
    }
    row.yank = y
    row.lastEventAt = System.currentTimeMillis()
  }

  private fun agentMatches(agent: String, filter: String): Boolean {
    val a = agent.lowercase()
    return when (filter) {
      "cursor" -> a.contains("cursor")
      "codex" -> a.contains("codex") || a.contains("openai")
      else -> true
    }
  }

  private fun isOccupied(): Boolean =
    state == State.PEEKING || state == State.ENGAGED || state == State.FOLLOWUP ||
      state == State.DICTATING || state == State.BROWSING

  private fun enqueue(yank: AgentYank) {
    if (UserPrefs.isSnoozing(appContext)) return
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
        // No glasses connected in time. Reset to AMBIENT instead of staying
        // "PEEKING" forever — otherwise every later event is queued/ignored and
        // nothing renders even once the glasses do connect. The next host event
        // re-attempts the peek and finds the now-connected display.
        Log.w("HudPresenter", "still no connected display device after wait — resetting to AMBIENT")
        opening = false
        current = null
        queue.clear()
        lastRenderedKey = null
        state = State.AMBIENT
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
    armAutoAdvance()
  }

  private fun chipConfig() = ChipSet.config(appContext)

  private fun chipsFor(y: AgentYank) = ChipSet.forYank(y, chipConfig())

  // On a "done" peek the user hasn't touched, tick a visible countdown on the
  // primary button, then invoke it automatically. Any tap, dictate, dismiss, or
  // card change cancels it (re-checked each tick). Never armed for permission or
  // question cards — those always require an explicit human choice.
  private fun armAutoAdvance() {
    autoJob?.cancel()
    if (!UserPrefs.autoContinueEnabled(appContext)) return
    val y = current ?: return
    if (state != State.PEEKING || y.awaiting != Awaiting.DONE) return
    val primary = chipsFor(y).firstOrNull { it.kind == ChipKind.SEND } ?: return
    autoJob = scope.launch {
      var remaining = AUTO_ADVANCE_SECS
      while (remaining > 0) {
        if (state != State.PEEKING || current !== y) return@launch
        renderPeekCountdown(y, remaining)
        delay(1_000L)
        remaining--
      }
      if (state != State.PEEKING || current !== y) return@launch
      autoJob = null
      onChip(primary)
    }
  }

  private fun cancelAutoAdvance() {
    autoJob?.cancel()
    autoJob = null
  }

  // Re-render the peek with the remaining seconds appended to the primary chip's
  // label (e.g. "continue · 3s"). The chip's action is unchanged, so a manual tap
  // still fires the real primary action.
  private fun renderPeekCountdown(y: AgentYank, remaining: Int) {
    val display = datSession.activeDisplay ?: return
    val chips = chipsFor(y).map {
      if (it.primary && it.kind == ChipKind.SEND) {
        it.copy(label = "${it.label} · ${remaining}s")
      } else {
        it
      }
    }
    lastRenderedKey = null
    HudWidgets.sendPeek(scope, display, y, chips, ::onChip)
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
    val chips = chipsFor(y)
    if (chips.isEmpty()) return
    val key = renderKey(y, mode = "peek")
    if (key == lastRenderedKey) return
    lastRenderedKey = key
    HudWidgets.sendPeek(scope, display, y, chips, ::onChip)
    RelayService.warmMicForThread(y.thread)
  }

  private fun renderExpanded() {
    val d = datSession.activeDisplay ?: return
    val y = current ?: return
    val key = renderKey(y, mode = "expanded")
    if (key == lastRenderedKey) return
    lastRenderedKey = key
    HudWidgets.sendExpanded(scope, d, y, chipsFor(y), ::onChip)
    RelayService.warmMicForThread(y.thread)
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
      scope, d, y, dictatingPartial,
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
    markResponded(y.thread)
    Log.i("HudPresenter", "dictate commit thread=${y.thread} text=$text")
    relay.sendDictateCommit(y.thread, text)
    val d = datSession.activeDisplay
    if (d == null) {
      dismissCard(showNext = false)
      return
    }
    HudWidgets.sendDictateConfirm(scope, d, text)
    commitJob?.cancel()
    commitJob = scope.launch {
      delay(COMMIT_SHOW_MS)
      dismissCard(showNext = false)
    }
  }

  private fun startDictating(y: AgentYank) {
    cancelAutoAdvance()
    RelayService.onHudDictationStart(y.thread)
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
      // Bluetooth SCO routes glasses HFP mic; may flash call-style UI on glasses.
      useBluetoothSco = RelayService.isBluetoothScoEnabled(appContext),
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
    cancelAutoAdvance()
    if (state == State.DICTATING) {
      if (c.label == "cancel") finishDictating(commit = false)
      return
    }
    Log.i("HudPresenter", "chip tapped: ${c.label} thread=${y.thread}")
    when (c.kind) {
      ChipKind.SEND -> {
        if (c.text != null) {
          val msgID = relay.sendInput(y.thread, c.text)
          Log.i("HudPresenter", "input sent thread=${y.thread} id=$msgID")
          // Responding ends this card: suppress the thread's lingering idle and
          // go dark instead of popping the next queued card. A failed
          // input_status (onInputFailed) undoes the suppression and retries.
          markResponded(y.thread)
          queue.clear()
          dismissCard(showNext = false)
        }
      }
      ChipKind.DICTATE -> startDictating(y)
      ChipKind.BROWSE -> enterBrowsing()
      ChipKind.SNOOZE -> onSnooze()
    }
  }

  // Native session browser (opened from a card's browse glyph). Renders the
  // session list on the glasses; a row opens that session, back goes dark.
  private fun enterBrowsing() {
    cancelAutoAdvance()
    peekTimer?.cancel(); peekTimer = null
    renderJob?.cancel()
    current = null
    lastRenderedKey = null
    state = State.BROWSING
    renderBrowser()
  }

  private fun renderBrowser() {
    val d = datSession.activeDisplay ?: return
    var rows = sessions.values.sortedBy { it.lastEventAt } // newest at the bottom
    browseFilter?.let { f -> rows = rows.filter { agentMatches(it.agent, f) } }
    val views = rows.map { HudWidgets.HudSession(it.thread, it.label, it.agent, it.status) }
    HudWidgets.sendSessionList(
      scope, d, views, browseFilter,
      onRow = { openFromBrowser(it) },
      onFilter = { setBrowseFilter(it) },
      onBack = { dismissCard(showNext = false) },
    )
  }

  private fun setBrowseFilter(filter: String) {
    browseFilter = if (filter == "all") null else filter
    renderBrowser()
  }

  private fun openFromBrowser(thread: String) {
    state = State.AMBIENT
    current = null
    opening = false
    val row = sessions[thread]
    if (row?.yank != null) {
      yank(row.yank!!)
    } else {
      relay.sendHudYank(thread) // pull the real card; HudYank event will peek it
    }
  }

  private fun onSnooze() {
    val duration = UserPrefs.snoozeDurationMs(appContext)
    UserPrefs.activateSnooze(appContext, duration)
    queue.clear()
    snoozeTimer?.cancel()
    snoozeTimer = null
    dismissCard(showNext = false)
    state = State.AMBIENT
    RelayService.pushCompanionConfig(appContext)
    Log.i("HudPresenter", "snooze ${duration / 1000}s — agent cards discarded until expiry")
  }

  /** Hide current card; keep DAT session alive if more are queued. */
  private fun dismissCard(showNext: Boolean = true) {
    peekTimer?.cancel(); peekTimer = null
    commitJob?.cancel(); commitJob = null
    renderJob?.cancel(); renderJob = null
    cancelAutoAdvance()
    val endingThread = current?.thread
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    dictatingPartial = ""
    current = null
    lastRenderedKey = null
    opening = false
    state = State.AMBIENT

    if (!showNext) {
      endingThread?.let { RelayService.coolMicForThread(it) }
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
    endingThread?.let { RelayService.coolMicForThread(it) }
  }

  /** Turn the glasses screen off — blank HUD, removeDisplay, release session. */
  private fun tearDownDisplay() {
    Log.i("HudPresenter", "tear down display (power off waveguide)")
    val d = datSession.activeDisplay
    scope.launch {
      try {
        if (d != null) {
          d.sendContent { flexBox(gap = 0, padding = 0, background = FlexBoxBackground.NONE) {} }
          delay(180)
        }
      } catch (_: Throwable) {}
      // powerOff (not sleep-only): keeping DeviceSession alive leaves Meta home lit.
      datSession.powerOffDisplay()
    }
  }
}
