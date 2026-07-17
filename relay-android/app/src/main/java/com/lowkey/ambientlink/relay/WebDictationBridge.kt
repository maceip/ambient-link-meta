package com.lowkey.ambientlink.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lowkey.ambientlink.dictation.DictationCallback
import com.lowkey.ambientlink.dictation.DictationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glasses web dictate: warm the mic on session focus (optional), then stream partials
 * once [dictate_active] arrives from the relay. Capture path is chosen per turn via
 * `mic` on the frame: "phone" (default pocket mic) or "glasses" (Bluetooth SCO/HFP —
 * may flash the in-call UI on glasses).
 */
class WebDictationBridge(
  private val context: Context,
  private val client: RelayClient,
  private val scope: CoroutineScope,
  private val prefs: SharedPreferences,
) {
  private var standbyThread: String? = null
  private var liveThread: String? = null
  /** Last requested capture path for the live/standby session. */
  @Volatile private var activeMic: String = "phone"
  @Volatile private var forwardPartials = false
  private var standbyTimeoutJob: Job? = null

  fun isPreWarmEnabled(): Boolean =
    prefs.getBoolean(PREF_PREWARM_MIC, true)

  fun setPreWarmEnabled(on: Boolean) {
    prefs.edit().putBoolean(PREF_PREWARM_MIC, on).apply()
    if (!on) stopStandby()
  }

  fun isBluetoothScoEnabled(): Boolean =
    prefs.getBoolean(PREF_BLUETOOTH_SCO, false)

  fun setBluetoothScoEnabled(on: Boolean) {
    prefs.edit().putBoolean(PREF_BLUETOOTH_SCO, on).apply()
  }

  /** User opened a session (web) or HUD is showing a card for this thread. */
  fun onSessionFocus(thread: String) {
    if (!isPreWarmEnabled() || thread.isBlank()) return
    if (liveThread != null) return
    if (standbyThread != null && standbyThread != thread) stopStandby()
    if (standbyThread == thread && DictationManager.isActive()) return
    standbyThread = thread
    forwardPartials = false
    armStandbyMic(thread)
    resetStandbyTimeout()
    Log.i(TAG, "mic standby thread=$thread")
  }

  fun onSessionBlur(thread: String) {
    if (thread.isBlank()) return
    if (liveThread != null && liveThread == thread) return
    if (standbyThread != thread) return
    stopStandby()
  }

  @Suppress("UNUSED_PARAMETER")
  fun onActive(msgThread: String, source: String, mic: String = "phone") {
    if (source != "web") return
    standbyTimeoutJob?.cancel()
    // Source of truth is the Android app setting (phone mic vs glasses SCO).
    // Frame `mic` is ignored so glasses stay a single Dictate button.
    val wantSco = isBluetoothScoEnabled()
    val alreadyWarm =
      standbyThread == msgThread &&
        DictationManager.isActive() &&
        (activeMic == "glasses") == wantSco
    liveThread = msgThread
    activeMic = if (wantSco) "glasses" else "phone"
    forwardPartials = true
    RelayService.setMicrophoneForeground(true)
    if (alreadyWarm) {
      Log.i(TAG, "dictate live (mic already warm) thread=$msgThread mic=$activeMic")
      DictationManager.lastPartialText().takeIf { it.isNotBlank() }?.let {
        client.sendDictatePartial(msgThread, it)
      }
      return
    }
    // Mic path changed (e.g. standby phone → live glasses) — restart capture.
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    standbyThread = msgThread
    startLiveCapture(msgThread, wantSco)
  }

  fun onHudDictationStart(thread: String) {
    standbyTimeoutJob?.cancel()
    standbyThread = null
    liveThread = null
    forwardPartials = false
  }

  fun onEnd(msgThread: String) {
    if (msgThread != liveThread && msgThread != standbyThread) return
    stopAllQuietly()
  }

  /** Web composer finished — stop mic; text already sent to relay by the browser. */
  fun onCommitFromWeb(msgThread: String) {
    if (msgThread != liveThread && msgThread != standbyThread) return
    DictationManager.stop(commitPartial = false, notify = false)
    liveThread = null
    standbyThread = null
    forwardPartials = false
    standbyTimeoutJob?.cancel()
    RelayService.setMicrophoneForeground(false)
  }

  fun onAbortFromWeb(msgThread: String) {
    if (msgThread != liveThread && msgThread != standbyThread) return
    abortLive()
  }

  fun stop() {
    abortLive()
    stopStandby()
  }

  private fun armStandbyMic(thread: String) {
    if (DictationManager.isActive() && standbyThread != thread) return
    // Standby always warms the phone mic — SCO/in-call UI only on explicit glasses tap.
    activeMic = "phone"
    RelayService.setMicrophoneForeground(true)
    startCapture(thread, live = false, useBluetoothSco = false)
  }

  private fun startLiveCapture(thread: String, useBluetoothSco: Boolean) {
    forwardPartials = true
    if (DictationManager.isActive()) return
    startCapture(thread, live = true, useBluetoothSco = useBluetoothSco)
  }

  private fun startCapture(thread: String, live: Boolean, useBluetoothSco: Boolean) {
    forwardPartials = live
    Log.i(TAG, "startCapture thread=$thread live=$live sco=$useBluetoothSco")
    DictationManager.start(
      context,
      object : DictationCallback {
        override fun onPartial(text: String) {
          if (!forwardPartials) return
          client.sendDictatePartial(thread, text)
        }
        override fun onFinal(text: String) {
          if (!live && !forwardPartials) {
            // Standby-only final (silence timeout inside SODA) — keep mic warm.
            Log.d(TAG, "standby final ignored thread=$thread")
            return
          }
          val spoken = text.trim()
          DictationManager.stop(commitPartial = false, notify = false)
          RelayService.setMicrophoneForeground(false)
          liveThread = null
          standbyThread = null
          forwardPartials = false
          if (spoken.isBlank()) {
            client.sendDictateAbort(thread)
            return
          }
          client.sendDictateCommit(thread, spoken)
        }
        override fun onCancelled() {
          if (live) abortLive() else stopStandby()
        }
        override fun onError(message: String) {
          Log.w(TAG, "dictate error: $message")
          if (live) abortLive() else stopStandby()
        }
      },
      useBluetoothSco = useBluetoothSco,
    )
  }

  private fun resetStandbyTimeout() {
    standbyTimeoutJob?.cancel()
    standbyTimeoutJob = scope.launch {
      delay(STANDBY_TIMEOUT_MS)
      if (liveThread == null && standbyThread != null) {
        Log.i(TAG, "mic standby timeout thread=$standbyThread")
        stopStandby()
      }
    }
  }

  private fun stopStandby() {
    standbyTimeoutJob?.cancel()
    standbyTimeoutJob = null
    if (liveThread != null) return
    standbyThread = null
    forwardPartials = false
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    RelayService.setMicrophoneForeground(false)
  }

  private fun abortLive() {
    val t = liveThread ?: standbyThread
    liveThread = null
    standbyThread = null
    forwardPartials = false
    standbyTimeoutJob?.cancel()
    DictationManager.stop(commitPartial = false, notify = false)
    RelayService.setMicrophoneForeground(false)
    if (t != null) client.sendDictateAbort(t)
  }

  private fun stopAllQuietly() {
    liveThread = null
    standbyThread = null
    forwardPartials = false
    standbyTimeoutJob?.cancel()
    DictationManager.stop(commitPartial = false, notify = false)
    RelayService.setMicrophoneForeground(false)
  }

  companion object {
    private const val TAG = "WebDictationBridge"
    private const val PREF_PREWARM_MIC = "prewarm_mic"
    private const val PREF_BLUETOOTH_SCO = "use_bluetooth_sco"
    private const val STANDBY_TIMEOUT_MS = 120_000L
  }
}
