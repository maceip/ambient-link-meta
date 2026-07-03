package com.lowkey.ambientlink.relay

import android.content.Context

/** Last-known LAN relay URL — config HTTP must hit the Mac, not the cloud WS endpoint. */
object RelayLanStore {
  private const val PREFS = "ambient-link-meta"
  private const val KEY_LAN_WS = "last_lan_relay_ws"

  fun rememberLanWs(ctx: Context, wsUrl: String) {
    val trimmed = wsUrl.trim()
    if (!trimmed.startsWith("ws://")) return
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_LAN_WS, trimmed)
      .apply()
  }

  fun lastLanWs(ctx: Context): String? =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY_LAN_WS, null)
      ?.trim()
      ?.takeIf { it.startsWith("ws://") }

  fun lastLanHttp(ctx: Context): String? = wsToHttp(lastLanWs(ctx))

  internal fun wsToHttp(ws: String?): String? {
    if (ws.isNullOrBlank()) return null
    var base = ws.trim()
    base = base.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
    val cut = base.indexOf("/ambient-link").let { if (it >= 0) it else base.indexOf("/face-chat") }
    if (cut >= 0) base = base.substring(0, cut)
    base = base.trimEnd('/')
    return base.ifBlank { null }
  }
}
