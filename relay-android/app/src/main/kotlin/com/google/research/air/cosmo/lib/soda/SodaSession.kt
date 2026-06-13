package com.google.research.air.cosmo.lib.soda

import android.content.Context
import android.util.Log
import com.google.android.libraries.assistant.soda.Soda
import com.google.android.libraries.assistant.soda.SodaCallback
import com.google.android.libraries.assistant.soda.SodaStopReason
import com.google.speech.soda.SodaEventProto
import com.google.speech.soda.StatusProto
import java.io.File
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant

/**
 * One SODA recognition session — ported from ~/neural/.../SodaSession.kt.
 * Ambient Link uses a single dictate session at a time (phone mic → relay WS).
 */
class SodaSession(
  private val context: Context,
  private val packDir: File,
  private val clock: Clock,
) {
  private val lock = Any()
  private var soda: Soda? = null

  fun start(callback: SodaTranscriptCallback): SodaStartResult = synchronized(lock) {
    if (soda != null) return@synchronized SodaStartResult.Started
    return@synchronized runCatching {
      val sodaCallback = TranscriptingSodaCallback(callback, clock)
      val instance = Soda(context, sodaCallback)
      val configResult = instance.initSoda(SodaConfigBuilder.coreConfig(context, packDir))
      val status = configResult.configStatus
      if (status != StatusProto.ConfigStatus.NO_ERROR) {
        instance.delete()
        Log.w(TAG, "initSoda returned configStatus=$status")
        return@runCatching SodaStartResult.Failed("soda_init_failed:$status")
      }
      instance.startCapture(SodaConfigBuilder.clientConfig())
      soda = instance
      Log.i(TAG, "SODA session started; packDir=${packDir.absolutePath}")
      SodaStartResult.Started
    }.getOrElse {
      Log.w(TAG, "SODA session start failed: ${it.message}", it)
      SodaStartResult.Failed("soda_start_failed:${it.javaClass.simpleName}")
    }
  }

  fun addAudio(audio: ByteBuffer, length: Int) {
    val active = synchronized(lock) { soda } ?: return
    if (length <= 0) return
    runCatching { active.addAudio(audio, length) }
      .onFailure { Log.w(TAG, "addAudio failed: ${it.message}") }
  }

  fun stop() {
    val active = synchronized(lock) {
      val current = soda
      soda = null
      current
    } ?: return
    runCatching { active.stopCapture() }
    runCatching { active.delete() }
  }

  private companion object {
    const val TAG = "SodaSession"
  }
}

internal class TranscriptingSodaCallback(
  private val callback: SodaTranscriptCallback,
  private val clock: Clock,
) : SodaCallback {
  override fun handleStart() {
    Log.i(TAG, "SODA event: start")
  }

  override fun handleStop(reason: SodaStopReason) {
    Log.i(TAG, "SODA event: stop reason=$reason")
  }

  override fun handleShutdown() {
    Log.i(TAG, "SODA event: shutdown")
  }

  override fun handleSodaEvent(event: SodaEventProto.SodaEvent) {
    handleRecognitionEvent(event)
  }

  private fun handleRecognitionEvent(event: SodaEventProto.SodaEvent) {
    if (!event.hasRecognitionEvent()) return
    val recognitionEvent = event.recognitionEvent
    if (recognitionEvent.hasFinalResult()) {
      handleIntendedSpeechSignal(recognitionEvent.finalResult)
      emitFinalTranscript(recognitionEvent.finalResult)
    } else if (recognitionEvent.hasPartialResult()) {
      emitPartialTranscript(recognitionEvent.partialResult)
    }
  }

  private fun handleIntendedSpeechSignal(finalResult: SodaEventProto.FinalRecognitionResult) {
    if (!finalResult.hasIntendedQueryScore() && !finalResult.hasIntendedQueryThreshold()) return
    val score = if (finalResult.hasIntendedQueryScore()) finalResult.intendedQueryScore else Float.NaN
    val threshold = if (finalResult.hasIntendedQueryThreshold()) finalResult.intendedQueryThreshold else Float.NaN
    val directed = !score.isNaN() && !threshold.isNaN() && score >= threshold
    if (directed) callback.onDirectednessTrigger(clock.instant())
  }

  private fun emitFinalTranscript(finalResult: SodaEventProto.FinalRecognitionResult) {
    val hypotheses = finalResult.hypothesisList
    if (hypotheses.isEmpty()) return
    Log.i(TAG, "SODA final transcript: ${hypotheses.first()}")
    callback.onTranscript(hypotheses.first(), isFinal = true)
  }

  private fun emitPartialTranscript(partialResult: SodaEventProto.PartialRecognitionResult) {
    val parts = partialResult.hypothesisPartsList
    if (parts.isEmpty()) return
    val sb = StringBuilder()
    for (part in parts) {
      if (part.textCount == 0) continue
      if (part.leadingSpace && sb.isNotEmpty()) sb.append(' ')
      sb.append(part.getText(0))
    }
    if (sb.isNotEmpty()) {
      Log.i(TAG, "SODA partial transcript: $sb")
      callback.onTranscript(sb.toString(), isFinal = false)
    }
  }

  private companion object {
    const val TAG = "SodaSession.Callback"
    @Suppress("unused")
    val EpochZero: Instant = Instant.EPOCH
  }
}
