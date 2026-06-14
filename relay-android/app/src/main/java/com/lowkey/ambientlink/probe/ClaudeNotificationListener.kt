// Probe-only NotificationListenerService. Filters for com.anthropic.claude notifications and
// dumps everything: text, action button labels, the action PendingIntents and their target
// component / extras. Goal: confirm whether we can later `.send()` Claude's own
// action PendingIntents from our HUD chip taps (= the cleanest local-agent integration path).
//
// Requires the user to grant Notification access (Settings → Notifications → Special access),
// or via adb:  settings put secure enabled_notification_listeners <pkg>/<class>
package com.lowkey.ambientlink.probe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class ClaudeNotificationListener : NotificationListenerService() {
  companion object { private const val TAG = "ambient.notif"; private const val CLAUDE = "com.anthropic.claude" }

  override fun onListenerConnected() {
    Log.i(TAG, "listener connected — scanning active notifications")
    activeNotifications?.filter { it.packageName == CLAUDE }?.forEach { dump("active", it) }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return
    if (sbn.packageName != CLAUDE) return
    dump("posted", sbn)
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    sbn ?: return
    if (sbn.packageName != CLAUDE) return
    Log.i(TAG, "removed key=${sbn.key} id=${sbn.id} reason=removed")
  }

  private fun dump(kind: String, sbn: StatusBarNotification) {
    val n: Notification = sbn.notification
    val x = n.extras
    val title    = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val text     = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val bigText  = x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    val subText  = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    val tickerT  = n.tickerText?.toString()
    Log.i(TAG, "── $kind ──────────────────────────────────────────────")
    Log.i(TAG, "key=${sbn.key}  id=${sbn.id}  post=${sbn.postTime}  group=${sbn.groupKey}")
    Log.i(TAG, "channel=${n.channelId}  category=${n.category}  flags=0x${Integer.toHexString(n.flags)}")
    Log.i(TAG, "title=$title")
    Log.i(TAG, "text=$text")
    if (bigText != null) Log.i(TAG, "bigText=$bigText")
    if (subText != null) Log.i(TAG, "subText=$subText")
    if (tickerT != null) Log.i(TAG, "ticker=$tickerT")

    val contentPi = n.contentIntent
    if (contentPi != null) Log.i(TAG, "contentIntent target=${contentPi.creatorPackage}/${contentPi.intentSender}")

    val actions = n.actions ?: emptyArray()
    Log.i(TAG, "actions(${actions.size}):")
    actions.forEachIndexed { i, a ->
      val piTarget = a.actionIntent?.let { pi -> "creator=${pi.creatorPackage} desc=${pi}" } ?: "<no intent>"
      Log.i(TAG, "  [$i] title='${a.title}' $piTarget")
      // RemoteInputs (for inline-reply actions like SESSION_REPLY)
      a.remoteInputs?.forEach { ri ->
        Log.i(TAG, "       remoteInput key=${ri.resultKey} label='${ri.label}' allowFreeForm=${ri.allowFreeFormInput}")
      }
    }
    // Dump everything in extras as a last resort — sometimes the useful payload is there.
    for (key in x.keySet()) {
      val v = runCatching { x.get(key) }.getOrNull()
      val vs = v?.toString()?.take(200)
      if (vs != null && key !in setOf(
          Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
          Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
        )) Log.i(TAG, "  extra[$key] = $vs")
    }
    Log.i(TAG, "──────────────────────────────────────────────────────")
  }
}
