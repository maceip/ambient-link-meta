package com.lowkey.ambientlink.relay

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
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
import com.lowkey.ambientlink.soda.SodaRuntime
import com.google.research.air.cosmo.lib.soda.SodaPrepareResult
import com.lowkey.ambientlink.settings.UserPrefs
import com.lowkey.ambientlink.wearables.WearablesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Foreground service. Keeps a WS connection to the relay alive while the app is
// backgrounded, and bridges relay events into the HudPresenter.
//
// Started by MainActivity (or by ACTION_BOOT_COMPLETED in a future revision once we
// wire a BootReceiver). Survives task-swipe via START_STICKY.
class RelayService : Service() {
  private val scope = MainScope()
  private var client: RelayClient? = null
  private var presenter: HudPresenter? = null
  private var webDictation: WebDictationBridge? = null
  private var agentVoiceClient: AgentSessionVoiceClient? = null
  private var eventsJob: Job? = null
  private var activeUrl: String = ""
  private var microphoneForeground = false
  private var foregroundStarted = false
  private val restartMutex = Mutex()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    state = this
    _status.update { it.copy(running = true) }
    // Start as DATA_SYNC only. The 2-arg startForeground() applies the union of
    // the manifest's declared types (dataSync|microphone), so Android 14+ would
    // enforce the microphone FGS rules (RECORD_AUDIO + eligible state) at cold
    // start and crash. Microphone is promoted later via setMicrophoneForeground.
    applyForeground("starting…")
    scope.launch(Dispatchers.IO) { preloadSodaIfEnabled() }
  }

  private fun preloadSodaIfEnabled() {
    if (!isSodaPreloadEnabled(applicationContext)) return
    try {
      when (val result = SodaRuntime.preparePack(applicationContext)) {
        is SodaPrepareResult.Available ->
          Log.i(TAG, "SODA pack pre-loaded")
        is SodaPrepareResult.Unavailable ->
          Log.w(TAG, "SODA preload skipped: ${result.reason}")
      }
    } catch (e: Exception) {
      Log.w(TAG, "SODA preload failed: ${e.message}")
    }
  }

  fun requestSodaPreload() {
    scope.launch(Dispatchers.IO) { preloadSodaIfEnabled() }
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
    if (microphoneForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasRecordAudio()) {
      type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    return type
  }

  // Never request the microphone FGS type without the runtime permission, or
  // Android 14+ throws SecurityException. Dictation degrades instead of crashing.
  private fun hasRecordAudio(): Boolean =
    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun applyForeground(text: String) {
    val notification = buildNotification(text)
    if (!foregroundStarted) {
      try {
        ServiceCompat.startForeground(this, NOTIF_ID, notification, foregroundServiceType())
        foregroundStarted = true
      } catch (e: Exception) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
          Log.w(TAG, "FGS start blocked — stopping relay to avoid crash", e)
          stopSelf()
          return
        }
        throw e
      }
      return
    }
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val explicit = intent?.getStringExtra(EXTRA_URL)
    val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
    scope.launch {
      val url = resolveRelayUrl(explicit)
      if (url == null) {
        eventsJob?.cancel()
        client?.stop()
        webDictation?.stop()
        client = null
        presenter = null
        webDictation = null
        activeUrl = ""
        _status.update {
          it.copy(
            running = true,
            connected = false,
            url = "",
            lastError = "LAN-only: no Mac relay on Wi‑Fi",
          )
        }
        setNotif("LAN-only — no host")
        return@launch
      }
      restart(url, force)
    }
    return START_STICKY
  }

  private suspend fun resolveLanRelayUrl(): String? {
    RelayDiscovery.discoverOrDirect(applicationContext)?.let { found ->
      Log.i(TAG, "relay: using LAN $found")
      RelayLanStore.rememberLanWs(applicationContext, found)
      return found
    }
    return null
  }

  private suspend fun resolveRelayUrl(explicit: String?): String? {
    val lanOnly = isLanOnlyEnabled(applicationContext)
    explicit?.takeIf { it.isNotBlank() }?.let { raw ->
      val normalized = normalizeRelayUrl(raw)
      if (!lanOnly || normalized.startsWith("ws://")) return normalized
      Log.w(TAG, "relay: LAN-only — ignoring cloud URL $normalized")
    }
    savedRelayUrlFromPrefs()?.takeIf { it.startsWith("ws://") }?.let { saved ->
      if (withContext(Dispatchers.IO) { RelayConfig.isReachableWs(saved) }) {
        Log.i(TAG, "relay: using saved LAN $saved")
        return saved
      }
      if (lanOnly) {
        Log.i(TAG, "relay: trying saved LAN $saved")
        return saved
      }
    }
    resolveLanRelayUrl()?.let { return it }
    if (lanOnly) {
      Log.w(TAG, "relay: LAN-only — no Mac relay found")
      return null
    }
    savedRelayUrlFromPrefs()?.takeIf { it.startsWith("wss://") }?.let { return it }
    val cloud = cloudRelayUrl()
    Log.i(TAG, "relay: using cloud $cloud")
    return cloud
  }

  private fun savedRelayUrlFromPrefs(): String? {
    val raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString("relay_url", null)
      ?.trim()
      ?.takeIf { isUsableRelayUrl(it) }
      ?: return null
    return normalizeRelayUrl(raw)
  }

  private fun cloudRelayUrl(): String {
    val fromBuild = BuildConfig.DEFAULT_RELAY_URL.trim()
    if (isUsableRelayUrl(fromBuild) && fromBuild.startsWith("wss://")) {
      return normalizeRelayUrl(fromBuild)
    }
    return normalizeRelayUrl("wss://public.computer/ambient-link/ws")
  }

  private fun normalizeRelayUrl(raw: String): String {
    var u = raw.trim()
    if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
      u = if (u.startsWith("/")) "ws:/$u" else "ws://$u"
    }
    u = u.trimEnd('/')
    // Accept a bare host:port, the canonical …/ambient-link/ws, or the legacy
    // …/face-chat/ws. Only append the default path when no /ws endpoint present,
    // so a configured endpoint isn't double-stacked (…/face-chat/ws/ambient-link/ws).
    if (!u.endsWith("/ws")) {
      u += "/ambient-link/ws"
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
    webDictation?.stop()
    agentVoiceClient?.stop()
    val c = RelayClient(url)
    val p = HudPresenter(applicationContext, c, WearablesRepository.getInstance(applicationContext))
    val dictation = WebDictationBridge(
      applicationContext,
      c,
      scope,
      getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )
    client = c
    presenter = p
    webDictation = dictation
    agentVoiceClient = AgentSessionVoiceClient.baseUrlFromRelayUrl(url)?.let { base ->
      AgentSessionVoiceClient(applicationContext, base, scope) { active ->
        setMicrophoneForeground(active)
      }.also { it.start() }
    }
    _status.update { it.copy(url = url, connected = false, lastError = null) }

    eventsJob = scope.launch {
      c.events.collect { ev ->
        when (ev) {
          is RelayClient.Event.Connected    -> {
            _status.update { it.copy(connected = true,  lastError = null) }
            if (activeUrl.startsWith("ws://")) {
              RelayLanStore.rememberLanWs(applicationContext, activeUrl)
            }
            setNotif("connected")
            scope.launch(Dispatchers.IO) { preloadSodaIfEnabled() }
          }
          is RelayClient.Event.Disconnected -> {
            _status.update { s -> s.copy(connected = false) }
            setNotif("reconnecting…")
            if (url.startsWith("ws://") && !isLanOnlyEnabled(applicationContext)) {
              launch {
                delay(8_000)
                if (!_status.value.connected && activeUrl.startsWith("ws://")) {
                  val cloud = cloudRelayUrl()
                  Log.i(TAG, "LAN unreachable — switching to $cloud")
                  restart(cloud, force = true)
                }
              }
            }
          }
          is RelayClient.Event.Hello        -> {
            _status.update { it.copy(threads = ev.threads.map { t -> t.label }, lastError = null) }
            p.hello(ev.threads)
            pushCompanionConfig(applicationContext)
          }
          is RelayClient.Event.ThreadIdle   -> {
            com.lowkey.ambientlink.settings.CompanionSuggest.noteYank(applicationContext, ev.yank)
            p.onIdle(ev.yank)
          }
          is RelayClient.Event.HudYank      -> {
            com.lowkey.ambientlink.settings.CompanionSuggest.noteYank(applicationContext, ev.yank)
            p.yank(ev.yank)
          }
          is RelayClient.Event.ThreadBusy   -> p.cancelIfFor(ev.thread)
          is RelayClient.Event.DictateActive -> dictation.onActive(ev.thread, ev.source)
          is RelayClient.Event.DictateCommit -> dictation.onCommitFromWeb(ev.thread)
          is RelayClient.Event.DictateAbort -> dictation.onAbortFromWeb(ev.thread)
          is RelayClient.Event.DictateEnd   -> dictation.onEnd(ev.thread)
          is RelayClient.Event.SessionFocus -> {
            dictation.onSessionFocus(ev.thread)
            p.onCompanionUi("session")
          }
          is RelayClient.Event.SessionBlur -> {
            dictation.onSessionBlur(ev.thread)
            p.onCompanionUi("list")
          }
          is RelayClient.Event.CompanionUi  -> p.onCompanionUi(ev.screen)
          is RelayClient.Event.Error        -> {
            _status.update { s -> if (!s.connected) s.copy(lastError = ev.msg) else s }
          }
          is RelayClient.Event.DictatePartial -> { /* HUD/web clients consume via host fan-out */ }
        }
      }
    }
    c.start()
  }

  override fun onDestroy() {
    eventsJob?.cancel()
    webDictation?.stop()
    agentVoiceClient?.stop()
    client?.stop()
    scope.cancel()
    foregroundStarted = false
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
    private const val PREF_PREWARM_MIC = "prewarm_mic"
    private const val PREF_PRELOAD_SODA = "preload_soda"
    private const val PREF_BLUETOOTH_SCO = "use_bluetooth_sco"
    private const val PREF_LAN_ONLY = "debug_lan_only"
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

    /** Pre-warm mic while user is in a session (glasses web or HUD card). */
    fun warmMicForThread(thread: String) {
      state?.webDictation?.onSessionFocus(thread)
    }

    fun coolMicForThread(thread: String) {
      state?.webDictation?.onSessionBlur(thread)
    }

    fun isPreWarmMicEnabled(ctx: Context): Boolean =
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_PREWARM_MIC, true)

    fun setPreWarmMicEnabled(ctx: Context, on: Boolean) {
      state?.webDictation?.setPreWarmEnabled(on)
        ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit().putBoolean(PREF_PREWARM_MIC, on).apply()
    }

    fun isSodaPreloadEnabled(ctx: Context? = null): Boolean {
      val c = ctx ?: state?.applicationContext ?: return true
      return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_PRELOAD_SODA, true)
    }

    fun setSodaPreloadEnabled(ctx: Context, on: Boolean) {
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_PRELOAD_SODA, on).apply()
      if (on) state?.requestSodaPreload()
    }

    /** Off by default — SCO triggers the glasses in-call UI overlay. */
    fun isBluetoothScoEnabled(ctx: Context): Boolean =
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_BLUETOOTH_SCO, false)

    fun setBluetoothScoEnabled(ctx: Context, on: Boolean) {
      state?.webDictation?.setBluetoothScoEnabled(on)
        ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit().putBoolean(PREF_BLUETOOTH_SCO, on).apply()
    }

    /** Debug: never use cloud relay; glasses web loads from Mac HTTP origin. */
    fun isLanOnlyEnabled(ctx: Context): Boolean =
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_LAN_ONLY, false)

    fun setLanOnlyEnabled(ctx: Context, on: Boolean) {
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_LAN_ONLY, on).apply()
    }

    fun pushCompanionConfig(ctx: Context) {
      state?.client?.sendCompanionConfig(
        UserPrefs.getQuickReplies(ctx),
        UserPrefs.getSnoozeUntilMs(ctx),
        UserPrefs.showContinueChip(ctx),
        UserPrefs.showDictateChip(ctx),
        UserPrefs.getDefaultAgent(ctx),
      )
    }

    fun onHudDictationStart(thread: String) {
      state?.webDictation?.onHudDictationStart(thread)
    }

    fun start(ctx: Context, url: String?, force: Boolean = false) {
      val cur = _status.value
      val sameUrl = url != null && (url == cur.url || url == cur.url.ifBlank { null })
      if (cur.running && url == null) {
        try {
          ctx.startForegroundService(Intent(ctx, RelayService::class.java))
        } catch (e: IllegalStateException) {
          Log.w(TAG, "FGS ping blocked from background", e)
        }
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
      try {
        ctx.startForegroundService(i)
      } catch (e: IllegalStateException) {
        Log.w(TAG, "FGS start blocked from background — open app once", e)
        _status.update { it.copy(lastError = "Open ambient link once to reconnect") }
      }
    }
    fun stop(ctx: Context) {
      _status.update { Status() }
      ctx.stopService(Intent(ctx, RelayService::class.java))
    }

    suspend fun discoverUrl(ctx: Context): String? =
      RelayDiscovery.discoverOrDirect(ctx, timeoutMs = 12_000)
  }
}
