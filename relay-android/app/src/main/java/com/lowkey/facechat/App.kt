package com.lowkey.facechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.lowkey.facechat.debug.DictateDebugReceiver
// Application-level wiring. Wearables.initialize runs from MainActivity after runtime
// BT permissions (Meta DisplayAccess pattern). Notification channel must exist before
// RelayService.startForeground.
class App : Application() {
  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(
        getString(R.string.notif_channel_id),
        getString(R.string.notif_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ),
    )
    DictateDebugReceiver.registerIfDebug(this)
  }
}
