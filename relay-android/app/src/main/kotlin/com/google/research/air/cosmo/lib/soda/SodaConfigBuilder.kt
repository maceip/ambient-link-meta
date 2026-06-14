package com.google.research.air.cosmo.lib.soda

import android.content.Context
import android.os.Build
import com.google.speech.soda.AudioProto
import com.google.speech.soda.ApplicationDomainProto
import com.google.speech.soda.ClientInfoProto
import com.google.speech.soda.HybridAsrConfigProto
import com.google.speech.soda.IntendedSpeechConfigProto
import com.google.speech.soda.LoggingProto
import com.google.speech.soda.SodaCoreConfigProto
import com.google.speech.soda.client.SodaClientConfigProto
import java.io.File

/** Kotlin port of neural/cosmo SodaConfigUtils — external PCM via STREAMING_DATA. */
internal object SodaConfigBuilder {
  private const val SAMPLE_RATE_HZ = 16_000
  private const val CHANNEL_COUNT_MONO = 1
  private const val MAX_CACHED_AUDIO_MS = 60_000
  private const val PLATFORM_ID_ANDROID = "android"

  fun coreConfig(
    context: Context,
    languagePackDirectory: File,
    sessionCacheId: String,
  ): SodaCoreConfigProto.SodaCoreConfig {
    val cacheDir = File(context.filesDir, "soda/sessions/$sessionCacheId").apply { mkdirs() }
    val micsFormat = AudioProto.RawAudioFormat.newBuilder()
      .setSampleFormat(AudioProto.RawAudioFormat.SampleFormat.INT16)
      .setSampleRate(SAMPLE_RATE_HZ)
      .setChannelCount(CHANNEL_COUNT_MONO)
      .build()
    val asrConfig = SodaCoreConfigProto.OnDeviceAsrConfig.newBuilder()
      .setLanguagePackDirectory(languagePackDirectory.absolutePath)
      .setApplicationDomain(ApplicationDomainProto.ApplicationDomain.AMBIENT_ONESHOT)
      .setAttachHotwordEvent(true)
      .build()
    val intendedSpeechConfig = IntendedSpeechConfigProto.IntendedSpeechConfig.newBuilder()
      .setEnabled(true)
      .setIntentDetectionMode(
        IntendedSpeechConfigProto.IntendedSpeechConfig.IntentDetectionMode.INTENT_DETECTION_MODE_OPEN_MIC,
      )
      .setAttachOpenMicResult(true)
      .build()
    val audioInputConfig = SodaCoreConfigProto.AudioInputConfig.newBuilder()
      .setMicsAudioFormat(micsFormat)
      .build()
    val audioProcessingConfig = SodaCoreConfigProto.AudioProcessingConfig.newBuilder()
      .setCachedAudioConfig(
        SodaCoreConfigProto.CachedAudioConfig.newBuilder().setMaxAudioMs(MAX_CACHED_AUDIO_MS),
      ).build()
    val audioLoggingPolicy = LoggingProto.AudioLoggingPolicy.newBuilder()
      .setPolicyType(LoggingProto.AudioLoggingPolicyType.AUDIO_LOGGING_POLICY_TYPE_NONE)
      .build()
    val serverAsrConfig = HybridAsrConfigProto.ServerAsrConfig.newBuilder()
      .setEnabled(false)
      .build()
    val clientInfo = ClientInfoProto.ClientInfo.newBuilder()
      .setApplicationVersion(versionName(context))
      .setPlatformId(PLATFORM_ID_ANDROID)
      .setDeviceModel(Build.MODEL.orEmpty().take(MAX_DEVICE_MODEL_CHARS))
      .build()
    return SodaCoreConfigProto.SodaCoreConfig.newBuilder()
      .setCacheDirectory(cacheDir.absolutePath)
      .setOnDeviceAsrConfig(asrConfig)
      .setIntendedSpeechConfig(intendedSpeechConfig)
      .setAudioInputConfig(audioInputConfig)
      .setAudioProcessingConfig(audioProcessingConfig)
      .setAudioLoggingPolicy(audioLoggingPolicy)
      .setServerAsrConfig(serverAsrConfig)
      .setClientInfo(clientInfo)
      .build()
  }

  fun clientConfig(): SodaClientConfigProto.SodaClientConfig {
    val rawFormat = AudioProto.RawAudioFormat.newBuilder()
      .setSampleFormat(AudioProto.RawAudioFormat.SampleFormat.INT16)
      .setSampleRate(SAMPLE_RATE_HZ)
      .setChannelCount(CHANNEL_COUNT_MONO)
      .build()
    val encodedProvider = SodaClientConfigProto.EncodedAudioProvider.newBuilder()
      .setAudioFormat(rawFormat)
      .setEncodingType(SodaClientConfigProto.EncodedAudioProvider.EncodingType.DEFAULT_RAW)
      .setInputType(SodaClientConfigProto.EncodedAudioProvider.InputType.STREAMING_DATA)
      .build()
    val providerConfig = SodaClientConfigProto.AudioProviderConfig.newBuilder()
      .setEncodedAudioProvider(encodedProvider)
      .build()
    return SodaClientConfigProto.SodaClientConfig.newBuilder()
      .setMicsAudioProviderConfig(providerConfig)
      .setRequireHotword(false)
      .build()
  }

  private fun versionName(context: Context): String =
    runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

  private const val MAX_DEVICE_MODEL_CHARS = 64
}
