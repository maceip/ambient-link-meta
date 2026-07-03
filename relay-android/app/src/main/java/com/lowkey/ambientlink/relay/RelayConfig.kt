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

  /** Resolve a Mac host that accepts POST /ambient-link/config (never the cloud WS URL). */
  suspend fun resolveHostHttpBase(
    ctx: Context,
    relayWsUrl: String,
    daemonWsUrl: String,
    discoverIfNeeded: Boolean = true,
  ): String? = withContext(Dispatchers.IO) {
    val candidates = linkedSetOf<String>()
    listOf(relayWsUrl, daemonWsUrl).forEach { ws ->
      wsToHttp(ws)?.takeIf { !isCloudRelay(it) }?.let { candidates.add(it) }
    }
    RelayLanStore.lastLanHttp(ctx)?.let { candidates.add(it) }
    if (discoverIfNeeded) {
      RelayDiscovery.discover(ctx, timeoutMs = 10_000)?.let { found ->
        RelayLanStore.rememberLanWs(ctx, found)
        wsToHttp(found)?.let { candidates.add(it) }
      }
    }
    for (base in candidates) {
      if (probeHealth(base)) {
        Log.i(TAG, "config host: $base")
        return@withContext base
      }
    }
    Log.w(TAG, "no LAN config host (tried ${candidates.size} candidates)")
    null
  }

  suspend fun saveDefaultCwd(
    ctx: Context,
    relayWsUrl: String,
    daemonWsUrl: String,
    cwd: String,
    create: Boolean,
  ): CwdSaveOutcome = withContext(Dispatchers.IO) {
    val normalized = normalizeCwdInput(cwd)
    val base = resolveHostHttpBase(ctx, relayWsUrl, daemonWsUrl, discoverIfNeeded = true)
      ?: return@withContext CwdSaveOutcome.Unreachable(
        "Can't reach your Mac on Wi‑Fi. In Debug, set Relay URL to ws://YOUR_MAC_IP:5181/ambient-link/ws",
      )
    postConfig(base, normalized, create)
  }

  private fun postConfig(base: String, cwd: String, create: Boolean): CwdSaveOutcome {
    try {
      val payload = JSONObject()
        .put("default_cwd", cwd)
        .put("create", create)
        .toString()
      val req = Request.Builder()
        .url("$base/ambient-link/config")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
      http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          return CwdSaveOutcome.Failed(
            if (body.isNotBlank()) body else "Mac relay returned ${resp.code}",
          )
        }
        return parseConfigResponse(body)
      }
    } catch (e: Exception) {
      Log.w(TAG, "saveDefaultCwd failed: ${e.message}")
      return CwdSaveOutcome.Unreachable(
        "Can't reach your Mac on Wi‑Fi. In Debug, set Relay URL to ws://YOUR_MAC_IP:5181/ambient-link/ws",
      )
    }
  }

  private fun probeHealth(base: String): Boolean {
    return try {
      http.newCall(Request.Builder().url("$base/healthz").get().build())
        .execute().use { it.isSuccessful }
    } catch (_: Exception) {
      false
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

  private fun wsToHttp(ws: String): String? = RelayLanStore.wsToHttp(ws)

  /** True when the Mac relay HTTP API responds (LAN only — never cloud). */
  fun isReachableWs(ws: String): Boolean {
    val base = wsToHttp(ws.trim()) ?: return false
    if (isCloudRelay(base)) return false
    return probeHealth(base)
  }

  private fun isCloudRelay(httpBase: String): Boolean {
    val host = httpBase.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    return host.contains("public.computer") || host.contains("example.com")
  }
}
