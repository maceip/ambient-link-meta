package com.lowkey.ambientlink.soda

import android.content.Context
import android.util.Log
import com.google.research.air.cosmo.lib.soda.SodaPrepareResult
import com.google.research.air.cosmo.lib.soda.SodaSession
import com.google.research.air.cosmo.lib.soda.SodaStartResult
import com.google.research.air.cosmo.lib.soda.SodaTranscriptCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock

/**
 * On-device dictate: mic PCM → SODA partial/final → [DictationCallback] via [DictationLauncher].
 * Maps neural's per-source SodaSession pattern to Ambient Link's single dictate surface.
 */
class SodaDictationEngine(
  private val context: Context,
  private val onPartial: (String) -> Unit,
  private val onFinal: (String) -> Unit,
  private val onError: (String) -> Unit,
  private val onStatus: (String) -> Unit,
  private val onSessionEndedListener: () -> Unit = {},
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var session: SodaSession? = null
  private var mic: MicCapture? = null
  @Volatile private var lastPartial = ""

  fun lastPartialText(): String = lastPartial

  fun start(useBluetoothSco: Boolean = false) {
    scope.launch {
      onStatus("preparing on-device speech…")
      if (!SodaRuntime.isAvailable(context)) {
        onError("soda_unavailable")
        return@launch
      }
      val pack = withContext(Dispatchers.IO) { SodaRuntime.preparePack(context) }
      val packDir = when (pack) {
        is SodaPrepareResult.Available -> pack.packDir
        is SodaPrepareResult.Unavailable -> {
          onError(pack.reason)
          return@launch
        }
      }
      val sodaSession = SodaSession(context, packDir, Clock.systemUTC())
      val callback = object : SodaTranscriptCallback {
        override fun onTranscript(text: String, isFinal: Boolean) {
          if (text.isBlank()) return
          if (isFinal) {
            lastPartial = ""
            Log.i(TAG, "onFinal: $text")
            onFinal(text.trim())
          } else {
            lastPartial = text.trim()
            Log.i(TAG, "onPartial: $lastPartial")
            onPartial(lastPartial)
          }
        }
        override fun onSessionEnded() {
          Log.i(TAG, "SODA session ended")
          onSessionEndedListener()
        }
      }
      when (val started = withContext(Dispatchers.IO) { sodaSession.start(callback) }) {
        is SodaStartResult.Started -> {
          session = sodaSession
          val capture = MicCapture(context, useBluetoothSco = useBluetoothSco) { pcm, n ->
            val direct = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            direct.put(pcm, 0, n)
            direct.position(0)
            sodaSession.addAudio(direct, n)
          }
          mic = capture
          if (!capture.start()) {
            stop()
            onError("mic_start_failed")
            return@launch
          }
          onStatus("listening…")
        }
        is SodaStartResult.Failed -> {
          sodaSession.stop()
          onError(started.reason)
        }
      }
    }
  }

  /** Stop capture; if we have a partial but no final yet, commit it. */
  fun stop(commitPartial: Boolean = true) {
    mic?.stop()
    mic = null
    session?.stop()
    session = null
    if (commitPartial && lastPartial.isNotBlank()) {
      Log.i(TAG, "committing trailing partial")
      onFinal(lastPartial)
      lastPartial = ""
    }
  }

  private companion object {
    const val TAG = "SodaDictationEngine"
  }
}
