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
 * Glasses web dictate: warm the phone mic on session focus (optional), then stream
 * partials once [dictate_active] arrives from the relay.
 */
class WebDictationBridge(
  private val context: Context,
  private val client: RelayClient,
  private val scope: CoroutineScope,
  private val prefs: SharedPreferences,
) {
  private var standbyThread: String? = null
  private var liveThread: String? = null
  @Volatile private var forwardPartials = false
  private var standbyTimeoutJob: Job? = null

  fun isPreWarmEnabled(): Boolean =
    prefs.getBoolean(PREF_PREWARM_MIC, true)

  fun setPreWarmEnabled(on: Boolean) {
    prefs.edit().putBoolean(PREF_PREWARM_MIC, on).apply()
    if (!on) stopStandby()
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

  fun onActive(msgThread: String, source: String) {
    if (source != "web") return
    standbyTimeoutJob?.cancel()
    liveThread = msgThread
    forwardPartials = true
    RelayService.setMicrophoneForeground(true)
    if (standbyThread == msgThread && DictationManager.isActive()) {
      Log.i(TAG, "dictate live (mic already warm) thread=$msgThread")
      DictationManager.lastPartialText().takeIf { it.isNotBlank() }?.let {
        client.sendDictatePartial(msgThread, it)
      }
      return
    }
    standbyThread = msgThread
    startLiveCapture(msgThread)
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

  fun stop() {
    abortLive()
    stopStandby()
  }

  private fun armStandbyMic(thread: String) {
    if (DictationManager.isActive() && standbyThread != thread) return
    RelayService.setMicrophoneForeground(true)
    startCapture(thread, live = false)
  }

  private fun startLiveCapture(thread: String) {
    forwardPartials = true
    if (DictationManager.isActive()) return
    startCapture(thread, live = true)
  }

  private fun startCapture(thread: String, live: Boolean) {
    forwardPartials = live
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
      useBluetoothSco = false,
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
    private const val STANDBY_TIMEOUT_MS = 120_000L
  }
}
