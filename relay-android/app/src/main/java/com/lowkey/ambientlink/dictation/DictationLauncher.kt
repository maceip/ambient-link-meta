package com.lowkey.ambientlink.dictation

import android.content.Context
import android.content.Intent

/**
 * Phone-side capture UI. Protocol + commit path live in ambient-link-core
 * (`host/internal/dictate`). On-device SODA reference: `~/neural/.../SodaSession.kt`.
 */
object DictationLauncher {
  private var callback: DictationCallback? = null

  fun start(context: Context, thread: String, callback: DictationCallback) {
    this.callback = callback
    val intent = Intent(context, DictationActivity::class.java)
      .putExtra(DictationActivity.EXTRA_THREAD, thread)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }

  internal fun deliverPartial(text: String) {
    callback?.onPartial(text)
  }

  internal fun deliverFinal(text: String) {
    callback?.onFinal(text)
    clear()
  }

  internal fun cancel(message: String? = null) {
    if (message != null) callback?.onError(message)
    callback?.onCancelled()
    clear()
  }

  private fun clear() {
    callback = null
  }
}
