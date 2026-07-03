package com.lowkey.ambientlink.relay

import com.lowkey.ambientlink.BuildConfig
import java.net.URLEncoder

object CompanionUrls {
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
}
