package com.lowkey.ambientlink.relay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Result of asking the Mac relay to validate or save a default working directory. */
sealed class CwdSaveOutcome {
  data class Saved(val resolvedPath: String?) : CwdSaveOutcome()
  data class NotFound(val resolvedPath: String) : CwdSaveOutcome()
  data class Unreachable(val hint: String) : CwdSaveOutcome()
  data class Failed(val message: String) : CwdSaveOutcome()
}

object RelayConfig {
  private const val TAG = "RelayConfig"
  private val http = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .build()

  fun normalizeCwdInput(raw: String): String =
    raw.trim().replace('～', '~')

  /** Resolve the Mac host HTTP base — cloud WS URLs cannot accept folder config directly. */
  suspend fun resolveHostHttpBase(ctx: Context, relayWsUrl: String, daemonWsUrl: String): String? =
    withContext(Dispatchers.IO) {
      listOf(relayWsUrl, daemonWsUrl)
        .mapNotNull { wsToHttp(it) }
        .firstOrNull { !isCloudRelay(it) }
        ?: RelayService.discoverUrl(ctx)?.let { wsToHttp(it) }?.takeUnless { isCloudRelay(it) }
    }

  suspend fun saveDefaultCwd(
    ctx: Context,
    relayWsUrl: String,
    daemonWsUrl: String,
    cwd: String,
    create: Boolean,
  ): CwdSaveOutcome = withContext(Dispatchers.IO) {
    val normalized = normalizeCwdInput(cwd)
    val base = resolveHostHttpBase(ctx, relayWsUrl, daemonWsUrl)
    if (base.isNullOrBlank()) {
      return@withContext CwdSaveOutcome.Unreachable(
        "Can't reach your Mac. In Debug, tap Discover or start ambient-link on your Mac.",
      )
    }
    try {
      val payload = JSONObject()
        .put("default_cwd", normalized)
        .put("create", create)
        .toString()
      val req = Request.Builder()
        .url("$base/ambient-link/config")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
      http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          return@withContext CwdSaveOutcome.Failed(
            if (body.isNotBlank()) body else "Mac relay returned ${resp.code}",
          )
        }
        parseConfigResponse(body)
      }
    } catch (e: Exception) {
      Log.w(TAG, "saveDefaultCwd failed: ${e.message}")
      CwdSaveOutcome.Unreachable(
        "Can't reach your Mac. In Debug, tap Discover or start ambient-link on your Mac.",
      )
    }
  }

  private fun parseConfigResponse(body: String): CwdSaveOutcome {
    val obj = try {
      JSONObject(body)
    } catch (_: Exception) {
      return CwdSaveOutcome.Failed("Invalid response from Mac relay")
    }
    if (obj.optBoolean("ok", false)) {
      return CwdSaveOutcome.Saved(obj.optString("resolved_path").ifBlank { null })
    }
    if (!obj.optBoolean("exists", true)) {
      val resolved = obj.optString("resolved_path")
      if (resolved.isNotBlank()) return CwdSaveOutcome.NotFound(resolved)
    }
    val err = obj.optString("error").ifBlank { "Mac relay rejected that path" }
    return CwdSaveOutcome.Failed(err)
  }

  private fun wsToHttp(ws: String): String? {
    var base = ws.trim()
    if (base.isBlank()) return null
    base = base.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
    val cut = base.indexOf("/ambient-link").let { if (it >= 0) it else base.indexOf("/face-chat") }
    if (cut >= 0) base = base.substring(0, cut)
    base = base.trimEnd('/')
    return base.ifBlank { null }
  }

  private fun isCloudRelay(httpBase: String): Boolean {
    val host = httpBase.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    return host.contains("public.computer") || host.contains("example.com")
  }
}
