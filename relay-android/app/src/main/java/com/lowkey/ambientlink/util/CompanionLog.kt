package com.lowkey.ambientlink.util

import android.util.Log

/** Structured logcat for relay / web companion debugging. Filter: adb logcat -s AmbientLink */
object CompanionLog {
  private const val TAG = "AmbientLink"

  fun i(area: String, msg: String) = Log.i(TAG, "[$area] $msg")

  fun w(area: String, msg: String) = Log.w(TAG, "[$area] $msg")

  fun e(area: String, msg: String, t: Throwable? = null) {
    if (t != null) Log.e(TAG, "[$area] $msg", t) else Log.e(TAG, "[$area] $msg")
  }
}
