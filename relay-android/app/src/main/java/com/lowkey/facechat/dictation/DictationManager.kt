package com.lowkey.facechat.dictation

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.lowkey.facechat.soda.SodaDictationEngine

/**
 * Headless dictate — runs in [RelayService] so you never need to see or touch the phone.
 * Glasses chip → mic → SODA → relay WS → host.
 */
object DictationManager {
  private const val TAG = "DictationManager"
  private var engine: SodaDictationEngine? = null
  private var callback: DictationCallback? = null

  fun isActive(): Boolean = engine != null

  fun start(context: Context, cb: DictationCallback) {
    stop(commitPartial = false, notify = false)
    val app = context.applicationContext
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
      != PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "RECORD_AUDIO not granted")
      cb.onError("mic_denied")
      return
    }
    callback = cb
    engine = SodaDictationEngine(
      context = app,
      onPartial = { text -> callback?.onPartial(text) },
      onFinal = { text ->
        callback?.onFinal(text)
        clear()
      },
      onError = { reason ->
        callback?.onError(reason)
        clear()
      },
      onStatus = { Log.i(TAG, it) },
    ).also { it.start() }
    Log.i(TAG, "headless dictate started")
  }

  fun stop(commitPartial: Boolean = true, notify: Boolean = true) {
    val eng = engine ?: return
    val cb = callback
    engine = null
    callback = null
    eng.stop(commitPartial = commitPartial)
    if (!commitPartial && notify) cb?.onCancelled()
  }

  private fun clear() {
    engine = null
    callback = null
  }
}
