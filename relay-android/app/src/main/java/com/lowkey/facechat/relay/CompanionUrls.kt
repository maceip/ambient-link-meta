package com.lowkey.facechat.relay

import java.net.URLEncoder

object CompanionUrls {
  /** `ws://host:5181/face-chat/ws` → `http://host:5181/?session=…&compose=1` */
  fun composeUrl(relayWsUrl: String, threadId: String): String {
    val http = relayWsUrl
      .replace("wss://", "https://")
      .replace("ws://", "http://")
    val base = http.substringBefore("/face-chat").ifBlank { http.trimEnd('/') }
    val enc = URLEncoder.encode(threadId, Charsets.UTF_8.name())
    return "$base/?session=$enc&compose=1"
  }
}
