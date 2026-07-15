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
import kotlinx.coroutines.withTimeoutOrNull

/** On-device AI Core / Gemini Nano readiness for companion suggestions. */
object AiCoreProbe {
  private const val TAG = "AiCoreProbe"
  private const val AICORE_PACKAGE = "com.google.android.aicore"
  /** Long enough for the local shared-weights registration, far too short for
   *  a multi-GB network fetch — which is exactly the split we want. */
  private const val REGISTER_TIMEOUT_MS = 10_000L

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
        FeatureStatus.AVAILABLE -> ready(status)
        // DOWNLOADABLE only means "not registered for THIS app yet". AICore
        // shares one Gemini Nano across apps, so on devices that already have
        // the weights (e.g. Pixel with system AI features) download() is a
        // quick local registration, not a network fetch. Kick it off silently
        // and re-check instead of nagging the user to download a model their
        // phone already has. A real (large, networked) fetch won't finish
        // inside the timeout and we fall back to the explicit download UI.
        FeatureStatus.DOWNLOADABLE -> {
          val registered = withTimeoutOrNull(REGISTER_TIMEOUT_MS) { downloadModel() }
          when {
            registered == true || client.checkStatus() == FeatureStatus.AVAILABLE -> {
              Log.i(TAG, "downloadable → registered shared model without user prompt")
              ready(FeatureStatus.AVAILABLE)
            }
            // Timed out: a real fetch is now running in AICore — report it.
            registered == null ->
              Status(tier = Tier.NEEDS_MODEL, isDownloading = true, featureStatus = status)
            // Hard failure: keep the explicit download UI available.
            else -> Status(tier = Tier.NEEDS_MODEL, featureStatus = status)
          }
        }
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

  private suspend fun ready(status: Int): Status {
    val name = runCatching { Generation.getClient().getBaseModelName() }.getOrNull()?.trim()
    return Status(
      tier = Tier.READY,
      modelName = name?.takeIf { it.isNotEmpty() } ?: "Gemini Nano",
      featureStatus = status,
    )
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
