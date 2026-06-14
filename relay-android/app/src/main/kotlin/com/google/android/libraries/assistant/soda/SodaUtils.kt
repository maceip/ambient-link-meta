package com.google.android.libraries.assistant.soda

import com.google.speech.soda.AudioProto.RawAudioFormat
import com.google.speech.soda.SodaEventProto.SodaEvent
import com.google.speech.soda.client.SodaClientConfigProto.SodaClientConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Replacement for the recovered com.google.android.libraries.assistant.soda.SodaUtils.
object SodaUtils {

  @JvmStatic
  fun numBytesPerFrame(format: RawAudioFormat): Int {
    val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 16_000
    val bytesPerSample = 2
    val channels = format.channelCount.takeIf { it > 0 } ?: 1
    return sampleRate * bytesPerSample * channels / 50
  }

  @JvmStatic
  fun stopReasonFromInt(value: Int): SodaStopReason {
    val all = SodaStopReason.values()
    return all.getOrElse(value) { SodaStopReason.UNKNOWN }
  }

  @JvmStatic
  fun createDefaultConfig(): SodaClientConfig.Builder = SodaClientConfig.newBuilder()

  @JvmStatic
  fun convertHotqueryToQuickPhraseEvent(builder: SodaEvent.Builder): SodaEvent = builder.build()

  @JvmStatic
  fun convertHotwordToQuickPhraseEvent(builder: SodaEvent.Builder): SodaEvent = builder.build()

  class DirectByteBufferMaker {
    fun createOrReuse(size: Int): ByteBuffer =
      ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    fun createOrReuse(size: Int, order: ByteOrder): ByteBuffer =
      ByteBuffer.allocateDirect(size).order(order)
  }
}
