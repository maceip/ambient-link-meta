package com.lowkey.facechat.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.lowkey.facechat.soda.SodaDictationEngine

/**
 * Headless dictate — runs in [RelayService] so you never need to see or touch the phone.
 * Glasses chip → mic → SODA oneshot → relay WS → host.
 */
object DictationManager {
  private const val TAG = "DictationManager"
  private val main = Handler(Looper.getMainLooper())
  private var engine: SodaDictationEngine? = null
  private var callback: DictationCallback? = null
  @Volatile private var finalDelivered = false

  fun isActive(): Boolean = engine != null

  fun start(context: Context, cb: DictationCallback, useBluetoothSco: Boolean = false) {
    stop(commitPartial = false, notify = false)
    finalDelivered = false
    val app = context.applicationContext
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
      != PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "RECORD_AUDIO not granted")
      cb.onError("mic_denied")
      return
    }
    callback = cb
    engine = buildEngine(app).also { it.start(useBluetoothSco) }
    Log.i(TAG, "headless dictate started sco=$useBluetoothSco")
  }

  fun stop(commitPartial: Boolean = true, notify: Boolean = true) {
    val eng = engine ?: return
    val cb = callback
    eng.stop(commitPartial = commitPartial)
    engine = null
    callback = null
    if (!commitPartial && notify) main.post { cb?.onCancelled() }
  }

  /** Stop capture without callback; returns trailing partial (manual send before final). */
  fun harvestPartial(): String {
    val eng = engine ?: return ""
    val partial = eng.lastPartialText()
    eng.stop(commitPartial = false)
    engine = null
    callback = null
    return partial
  }

  private fun buildEngine(app: Context) = SodaDictationEngine(
    context = app,
    onPartial = { text -> main.post { callback?.onPartial(text) } },
    onFinal = { text ->
      main.post {
        if (finalDelivered) return@post
        finalDelivered = true
        Log.i(TAG, "final transcript: $text")
        callback?.onFinal(text)
        engine = null
      }
    },
    onError = { reason -> main.post { callback?.onError(reason); clear() } },
    onStatus = { Log.i(TAG, it) },
    onSessionEndedListener = { main.post { finishWithPartialIfNeeded() } },
  )

  /** SODA ended without a final event — commit trailing partial if we have one. */
  private fun finishWithPartialIfNeeded() {
    if (finalDelivered) return
    val eng = engine ?: return
    val partial = eng.lastPartialText()
    if (partial.isBlank()) return
    finalDelivered = true
    Log.i(TAG, "session ended — committing trailing partial: $partial")
    eng.stop(commitPartial = false)
    engine = null
    callback?.onFinal(partial)
    callback = null
  }

  private fun clear() {
    engine = null
    callback = null
  }
}
