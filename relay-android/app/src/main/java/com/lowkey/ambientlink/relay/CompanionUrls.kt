package com.lowkey.ambientlink.relay

import com.lowkey.ambientlink.BuildConfig
import java.net.URLEncoder

object CompanionUrls {
  /** Installed Meta Display web app origin (cloud). See repo README. */
  const val CLOUD_GLASSES_WEB = "https://agent.public.computer/"

  /** `ws://host:5181/ambient-link/ws` → `http://host:5181/ambient-link/?session=…&compose=1` */
  fun composeUrl(relayWsUrl: String, threadId: String): String {
    val http = relayWsUrl
      .replace("wss://", "https://")
      .replace("ws://", "http://")
    val base = http.substringBefore("/ambient-link").ifBlank { http.trimEnd('/') }
    val enc = URLEncoder.encode(threadId, Charsets.UTF_8.name())
    val cb = URLEncoder.encode("${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}", Charsets.UTF_8.name())
    return "$base/ambient-link/?session=$enc&compose=1&cb=$cb"
  }

  /**
   * Phone preview of the same web UI glasses load.
   * LAN → Mac HTTP `/ambient-link/`; otherwise the installed cloud origin.
   */
  fun glassesWebAppUrl(relayWsUrl: String, lanOnly: Boolean = false): String {
    val ws = relayWsUrl.trim()
    if (ws.isBlank()) return CLOUD_GLASSES_WEB
    if (lanOnly || ws.startsWith("ws://")) {
      val http = ws
        .replace("wss://", "https://")
        .replace("ws://", "http://")
      val base = http.substringBefore("/ambient-link").trimEnd('/')
      if (base.isNotBlank()) return "$base/ambient-link/"
    }
    return CLOUD_GLASSES_WEB
  }
}
