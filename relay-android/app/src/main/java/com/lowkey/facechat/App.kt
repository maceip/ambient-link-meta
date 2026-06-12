package com.lowkey.facechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.meta.wearable.dat.core.Wearables

// Application-level wiring. Wearables.initialize must run on the main process before any
// activity touches the SDK, and the foreground-service notification channel must exist
// before RelayService.startForeground is called.
class App : Application() {
  override fun onCreate() {
    super.onCreate()
    Wearables.initialize(this)
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(
        getString(R.string.notif_channel_id),
        getString(R.string.notif_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ),
    )
  }
}
