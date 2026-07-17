package com.lowkey.ambientlink.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** LAN reachability helpers for the Mac relay (no durable config POSTs from the phone). */
object RelayConfig {
  private const val TAG = "RelayConfig"
  private val http = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .build()

  private fun probeHealth(base: String): Boolean {
    return try {
      http.newCall(Request.Builder().url("$base/healthz").get().build())
        .execute().use { it.isSuccessful }
    } catch (e: Exception) {
      Log.w(TAG, "health probe failed for $base: ${e.message}")
      false
    }
  }

  private fun wsToHttp(ws: String): String? = RelayLanStore.wsToHttp(ws)

  /** True when the Mac relay HTTP API responds (LAN only — never cloud). Must run off main thread. */
  suspend fun isReachableWs(ws: String): Boolean = withContext(Dispatchers.IO) {
    val base = wsToHttp(ws.trim()) ?: return@withContext false
    if (isCloudRelay(base)) return@withContext false
    probeHealth(base)
  }

  private fun isCloudRelay(httpBase: String): Boolean {
    val host = httpBase.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    return host.contains("public.computer") || host.contains("example.com")
  }
}
