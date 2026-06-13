package com.lowkey.facechat.wearables

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables

// Meta DisplayAccess pattern: runtime BT permissions, then Wearables.initialize,
// then continuous device/metadata monitoring via WearablesRepository.
object WearablesRuntime {
  val PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.INTERNET,
  )

  @Volatile var initialized = false
    private set

  fun permissionsGranted(ctx: Context): Boolean =
    PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

  fun initialize(activity: ComponentActivity) {
    if (initialized) return
    Wearables.initialize(activity)
    WearablesRepository.getInstance(activity.applicationContext).startMonitoring()
    initialized = true
  }
}
