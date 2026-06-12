package com.lowkey.facechat.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lowkey.facechat.BuildConfig
import com.lowkey.facechat.MainActivity
import com.lowkey.facechat.R
import com.lowkey.facechat.hud.HudPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Foreground service. Keeps a WS connection to the relay alive while the app is
// backgrounded, and bridges relay events into the HudPresenter.
//
// Started by MainActivity (or by ACTION_BOOT_COMPLETED in a future revision once we
// wire a BootReceiver). Survives task-swipe via START_STICKY.
class RelayService : Service() {
  private val scope = MainScope()
  private var client: RelayClient? = null
  private var presenter: HudPresenter? = null
  private var eventsJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    state = this
    startForeground(NOTIF_ID, buildNotification("starting…"))
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val url = intent?.getStringExtra(EXTRA_URL)
      ?: getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("relay_url", BuildConfig.DEFAULT_RELAY_URL)
      ?: BuildConfig.DEFAULT_RELAY_URL
    restart(url)
    return START_STICKY
  }

  private fun restart(url: String) {
    eventsJob?.cancel()
    client?.stop()
    val c = RelayClient(url)
    val p = HudPresenter(c)
    client = c
    presenter = p
    _status.update { it.copy(url = url, connected = false) }

    eventsJob = scope.launch {
      c.events.collect { ev ->
        when (ev) {
          is RelayClient.Event.Connected    -> { _status.update { it.copy(connected = true,  lastError = null) }; setNotif("connected") }
          is RelayClient.Event.Disconnected -> { _status.update { it.copy(connected = false) };                  setNotif("reconnecting…") }
          is RelayClient.Event.Hello        -> _status.update { it.copy(threads = ev.threads.map { t -> t.label }) }
          is RelayClient.Event.ThreadIdle   -> p.yank(ev.thread, ev.label, ev.lastAssistant)
          is RelayClient.Event.ThreadBusy   -> p.cancelIfFor(ev.thread)
          is RelayClient.Event.Error        -> _status.update { it.copy(lastError = ev.msg) }
        }
      }
    }
    c.start()
  }

  override fun onDestroy() {
    eventsJob?.cancel()
    client?.stop()
    scope.cancel()
    state = null
    super.onDestroy()
  }

  private fun setNotif(text: String) {
    val nm = getSystemService(android.app.NotificationManager::class.java)
    nm.notify(NOTIF_ID, buildNotification(text))
  }
  private fun buildNotification(text: String): Notification {
    val pi = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setContentIntent(pi)
      .setOngoing(true)
      .build()
  }

  // ── observability for MainActivity ────────────────────────────────────
  data class Status(
    val url: String = "",
    val connected: Boolean = false,
    val threads: List<String> = emptyList(),
    val lastError: String? = null,
  )
  companion object {
    private const val NOTIF_ID = 1
    private const val PREFS    = "face-chat-final"
    const val EXTRA_URL = "relay_url"
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private var state: RelayService? = null
    fun start(ctx: Context, url: String?) {
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
        if (url != null) putString("relay_url", url)
        apply()
      }
      val i = Intent(ctx, RelayService::class.java)
      if (url != null) i.putExtra(EXTRA_URL, url)
      ctx.startForegroundService(i)
    }
    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, RelayService::class.java))
    }
  }
}
