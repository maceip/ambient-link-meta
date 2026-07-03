package com.lowkey.ambientlink.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-device AI Core / Gemini Nano readiness for companion suggestions. */
object AiCoreProbe {
  private const val TAG = "AiCoreProbe"
  private const val AICORE_PACKAGE = "com.google.android.aicore"

  enum class Tier {
    /** Device or OS build does not support AI Core — suggestions stay heuristic-only. */
    UNSUPPORTED,
    /** AI Core is present but Gemini Nano is not downloaded yet. */
    NEEDS_MODEL,
    /** Model downloaded and inference is available. */
    READY,
  }

  data class Status(
    val tier: Tier,
    val modelName: String? = null,
    val isDownloading: Boolean = false,
    val featureStatus: Int = FeatureStatus.UNAVAILABLE,
  ) {
    val isReady: Boolean get() = tier == Tier.READY
  }

  suspend fun probe(): Status = withContext(Dispatchers.Default) {
    try {
      val client = Generation.getClient()
      when (val status = client.checkStatus()) {
        FeatureStatus.AVAILABLE -> {
          val name = runCatching { client.getBaseModelName() }.getOrNull()?.trim()
          Status(
            tier = Tier.READY,
            modelName = name?.takeIf { it.isNotEmpty() } ?: "Gemini Nano",
            featureStatus = status,
          )
        }
        FeatureStatus.DOWNLOADABLE -> Status(
          tier = Tier.NEEDS_MODEL,
          featureStatus = status,
        )
        FeatureStatus.DOWNLOADING -> Status(
          tier = Tier.NEEDS_MODEL,
          isDownloading = true,
          featureStatus = status,
        )
        else -> Status(tier = Tier.UNSUPPORTED, featureStatus = status)
      }
    } catch (e: Exception) {
      Log.w(TAG, "probe failed: ${e.message}")
      Status(tier = Tier.UNSUPPORTED)
    }
  }

  /** Trigger ML Kit model download; returns true when complete. */
  suspend fun downloadModel(
    onProgress: (bytesDownloaded: Long) -> Unit = {},
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      var completed = false
      Generation.getClient()
        .download()
        .collect { event ->
          when (event) {
            is DownloadStatus.DownloadProgress -> onProgress(event.totalBytesDownloaded)
            DownloadStatus.DownloadCompleted -> completed = true
            is DownloadStatus.DownloadFailed -> {
              Log.w(TAG, "download failed: ${event.e.message}")
              completed = false
            }
            else -> Unit
          }
        }
      completed
    } catch (e: Exception) {
      Log.w(TAG, "downloadModel: ${e.message}")
      false
    }
  }

  /** Open AI Core app or its system settings page so the user can fetch Gemini Nano. */
  fun openAiCore(ctx: Context): Boolean {
    val intents = listOf(
      ctx.packageManager.getLaunchIntentForPackage(AICORE_PACKAGE),
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$AICORE_PACKAGE")),
      Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$AICORE_PACKAGE")),
    )
    for (intent in intents) {
      if (intent == null) continue
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        ctx.startActivity(intent)
        return true
      } catch (_: ActivityNotFoundException) {
      }
    }
    return false
  }
}
