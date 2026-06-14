package com.lowkey.ambientlink.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lowkey.ambientlink.BuildConfig
import com.lowkey.ambientlink.MainActivity
import com.lowkey.ambientlink.R
import com.lowkey.ambientlink.hud.HudPresenter
import com.lowkey.ambientlink.wearables.WearablesRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
  private var activeUrl: String = ""
  private var microphoneForeground = false
  private val restartMutex = Mutex()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    state = this
    _status.update { it.copy(running = true) }
    startForeground(NOTIF_ID, buildNotification("starting…"))
  }

  /** Promote FGS to microphone while glasses dictate runs (Android 14+ requires this for real audio). */
  fun setMicrophoneForeground(active: Boolean) {
    if (microphoneForeground == active) return
    microphoneForeground = active
    val text = when {
      active -> "dictating…"
      _status.value.connected -> "connected"
      else -> "reconnecting…"
    }
    applyForeground(text)
  }

  private fun foregroundServiceType(): Int {
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    if (microphoneForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    return type
  }

  private fun applyForeground(text: String) {
    val notification = buildNotification(text)
    ServiceCompat.startForeground(this, NOTIF_ID, notification, foregroundServiceType())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val explicit = intent?.getStringExtra(EXTRA_URL)
    val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
    scope.launch {
      val url = resolveRelayUrl(explicit)
      restart(url, force)
    }
    return START_STICKY
  }

  private suspend fun resolveRelayUrl(explicit: String?): String {
    explicit?.takeIf { it.isNotBlank() }?.let { return normalizeRelayUrl(it) }
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString("relay_url", null)?.takeIf { isUsableRelayUrl(it) }?.let { return normalizeRelayUrl(it) }
    BuildConfig.DEFAULT_RELAY_URL.takeIf { isUsableRelayUrl(it) }?.let { return normalizeRelayUrl(it) }
    RelayDiscovery.discover(applicationContext)?.also { found ->
      prefs.edit().putString("relay_url", found).apply()
      return found
    }
    return normalizeRelayUrl(BuildConfig.DEFAULT_RELAY_URL)
  }

  private fun normalizeRelayUrl(raw: String): String {
    var u = raw.trim()
    if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
      u = if (u.startsWith("/")) "ws:/$u" else "ws://$u"
    }
    if (!u.contains("/ambient-link/ws")) {
      u = u.trimEnd('/') + "/ambient-link/ws"
    }
    return u
  }

  private fun isUsableRelayUrl(url: String) =
    url.isNotBlank() && !url.contains("example.com") && url.contains('.')

  private suspend fun restart(url: String, force: Boolean = false) {
    restartMutex.withLock {
      if (!force && url == activeUrl && eventsJob?.isActive == true && client != null) {
        Log.i(TAG, "skip restart — already on $url")
        return
      }
      activeUrl = url
      doRestart(url)
    }
  }

  private fun doRestart(url: String) {
    eventsJob?.cancel()
    client?.stop()
    val c = RelayClient(url)
    val p = HudPresenter(applicationContext, c, WearablesRepository.getInstance(applicationContext))
    client = c
    presenter = p
    _status.update { it.copy(url = url, connected = false, lastError = null) }

    eventsJob = scope.launch {
      c.events.collect { ev ->
        when (ev) {
          is RelayClient.Event.Connected    -> { _status.update { it.copy(connected = true,  lastError = null) }; setNotif("connected") }
          is RelayClient.Event.Disconnected -> {
            _status.update { s -> s.copy(connected = false) }
            setNotif("reconnecting…")
          }
          is RelayClient.Event.Hello        -> _status.update { it.copy(threads = ev.threads.map { t -> t.label }, lastError = null) }
          is RelayClient.Event.ThreadIdle   -> p.onIdle(ev.yank)
          is RelayClient.Event.HudYank      -> p.yank(ev.yank)
          is RelayClient.Event.ThreadBusy   -> p.cancelIfFor(ev.thread)
          is RelayClient.Event.Error        -> {
            // Keep retrying silently; surface error only if we stay disconnected.
            _status.update { s -> if (!s.connected) s.copy(lastError = ev.msg) else s }
          }
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
    _status.update { Status() }
    super.onDestroy()
  }

  private fun setNotif(text: String) {
    if (microphoneForeground) {
      applyForeground("dictating…")
      return
    }
    applyForeground(text)
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
    val running: Boolean = false,
    val url: String = "",
    val connected: Boolean = false,
    val threads: List<String> = emptyList(),
    val lastError: String? = null,
  )
  companion object {
    private const val NOTIF_ID = 1
    private const val PREFS    = "ambient-link-meta"
    private const val TAG = "RelayService"
    const val EXTRA_URL = "relay_url"
    const val EXTRA_FORCE = "relay_force"
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private var state: RelayService? = null

    /** Debug: push a test card to glasses (adb broadcast). */
    fun debugYank(yank: com.lowkey.ambientlink.hud.AgentYank) {
      state?.presenter?.yank(yank)
    }

    fun setMicrophoneForeground(active: Boolean) {
      state?.setMicrophoneForeground(active)
    }

    fun start(ctx: Context, url: String?, force: Boolean = false) {
      val cur = _status.value
      val sameUrl = url != null && (url == cur.url || url == cur.url.ifBlank { null })
      if (cur.running && url == null) {
        ctx.startForegroundService(Intent(ctx, RelayService::class.java))
        return
      }
      if (!force && cur.running && cur.connected && (url == null || sameUrl)) {
        return
      }
      _status.update {
        it.copy(
          running = true,
          lastError = null,
          connected = if (force) false else it.connected,
          url = url ?: it.url,
        )
      }
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
        if (url != null) putString("relay_url", url)
        apply()
      }
      val i = Intent(ctx, RelayService::class.java)
      if (url != null) i.putExtra(EXTRA_URL, url)
      if (force) i.putExtra(EXTRA_FORCE, true)
      ctx.startForegroundService(i)
    }
    fun stop(ctx: Context) {
      _status.update { Status() }
      ctx.stopService(Intent(ctx, RelayService::class.java))
    }

    suspend fun discoverUrl(ctx: Context): String? = RelayDiscovery.discover(ctx)
  }
}
