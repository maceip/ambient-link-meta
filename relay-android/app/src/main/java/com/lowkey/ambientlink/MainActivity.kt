package com.lowkey.ambientlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lowkey.ambientlink.hud.GlassesDisplay
import com.lowkey.ambientlink.relay.RelayConfig
import com.lowkey.ambientlink.relay.RelayService
import com.lowkey.ambientlink.relay.CwdSaveOutcome
import com.lowkey.ambientlink.settings.AiCoreProbe
import com.lowkey.ambientlink.settings.CompanionSuggest
import com.lowkey.ambientlink.settings.UserPrefs
import com.lowkey.ambientlink.ui.AiCoreSettingsSection
import com.lowkey.ambientlink.ui.AiQuickReplySuggestions
import com.lowkey.ambientlink.ui.AiSnoozeSuggestions
import com.lowkey.ambientlink.ui.ActionLine
import com.lowkey.ambientlink.ui.AmbientPrimaryButton
import com.lowkey.ambientlink.ui.InlineActionStatus
import com.lowkey.ambientlink.ui.InlineSaveField
import com.lowkey.ambientlink.ui.QuickRepliesEditor
import com.lowkey.ambientlink.ui.SettingsBlockLabel
import com.lowkey.ambientlink.ui.AmbientPillGrid
import com.lowkey.ambientlink.ui.AmbientTheme
import com.lowkey.ambientlink.ui.SodaDebugPanel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lowkey.ambientlink.ui.FirstRunTipOverlay
import com.lowkey.ambientlink.ui.formatSnoozeLabel
import com.lowkey.ambientlink.wearables.WearablesRepository
import com.lowkey.ambientlink.wearables.WearablesRuntime
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
private fun AmbientApp(activity: MainActivity, wearablesRepo: WearablesRepository) {
  val ctx = activity.applicationContext
  var uiTheme by remember { mutableStateOf(UserPrefs.getUiTheme(ctx)) }
  MaterialTheme(colorScheme = AmbientTheme.colorSchemeFor(uiTheme), typography = MaterialTheme.typography) {
    Surface(
      Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars),
      color = MaterialTheme.colorScheme.background,
    ) {
      ControlScreen(activity, wearablesRepo, uiTheme = uiTheme, onUiThemeChange = { next ->
        uiTheme = next
        UserPrefs.setUiTheme(ctx, next)
      })
    }
  }
}

class MainActivity : ComponentActivity() {
  private val wearablesRepo by lazy { WearablesRepository.getInstance(applicationContext) }

  private val permissionsLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
      val btOk = WearablesRuntime.PERMISSIONS.all { results[it] == true }
      if (btOk) {
        WearablesRuntime.initialize(this)
        RelayService.start(this, null)
      } else {
        Toast.makeText(this, "Bluetooth permissions are required for glasses HUD", Toast.LENGTH_LONG).show()
      }
      maybeRequestNotificationPermission()
    }

  private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    if (!ok) Log.w("MainActivity", "mic permission denied — dictate will not work")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AmbientApp(this, wearablesRepo)
    }
  }

  override fun onStart() {
    super.onStart()
    if (WearablesRuntime.permissionsGranted(this)) {
      WearablesRuntime.initialize(this)
      wearablesRepo.refreshNow()
      maybeRequestNotificationPermission()
      maybeRequestMicPermission()
      RelayService.start(this, null)
    } else {
      permissionsLauncher.launch(WearablesRuntime.PERMISSIONS)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    Log.i("MainActivity", "onNewIntent ${intent.data}")
  }

  private fun maybeRequestMicPermission() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  private fun maybeRequestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
private fun ControlScreen(
  activity: ComponentActivity,
  wearablesRepo: WearablesRepository,
  uiTheme: String,
  onUiThemeChange: (String) -> Unit,
) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val regState by wearablesRepo.registrationState.collectAsState()
  val devicesMeta by wearablesRepo.devicesMetadata.collectAsState()
  val svcStatus by RelayService.status.collectAsState()
  val sdkReady = WearablesRuntime.initialized
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, wearablesRepo) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        wearablesRepo.refreshNow()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val displayDevice = devicesMeta.values.firstOrNull { it.isDisplayCapable() }
  val hazeState = remember { HazeState() }
  val scrollState = rememberScrollState()
  val hazeStyle = HazeMaterials.regular(MaterialTheme.colorScheme.surface)

  var url by remember {
    mutableStateOf(
      ctx.getSharedPreferences("ambient-link-meta", Context.MODE_PRIVATE)
        .getString("relay_url", BuildConfig.DEFAULT_RELAY_URL) ?: BuildConfig.DEFAULT_RELAY_URL,
    )
  }
  LaunchedEffect(svcStatus.url) {
    if (svcStatus.url.isNotBlank()) url = svcStatus.url
  }

  var cwd by remember {
    mutableStateOf(
      ctx.getSharedPreferences("ambient-link-meta", Context.MODE_PRIVATE)
        .getString("default_cwd", "") ?: "",
    )
  }
  var preloadSoda by remember { mutableStateOf(RelayService.isSodaPreloadEnabled(ctx)) }
  var bluetoothSco by remember { mutableStateOf(RelayService.isBluetoothScoEnabled(ctx)) }
  var quickReplies by remember { mutableStateOf(UserPrefs.getQuickReplies(ctx)) }
  var showContinue by remember { mutableStateOf(UserPrefs.showContinueChip(ctx)) }
  var showDictate by remember { mutableStateOf(UserPrefs.showDictateChip(ctx)) }
  var autoContinue by remember { mutableStateOf(UserPrefs.autoContinueEnabled(ctx)) }
  var defaultAgent by remember { mutableStateOf(UserPrefs.getDefaultAgent(ctx)) }
  var snoozeUntilMs by remember { mutableStateOf(UserPrefs.getSnoozeUntilMs(ctx)) }
  var showTipOverlay by remember { mutableStateOf(!UserPrefs.hasSeenCompanionTip(ctx)) }
  var suggestionsLoading by remember { mutableStateOf(false) }
  var suggestions by remember { mutableStateOf(CompanionSuggest.Result()) }
  var aiCoreStatus by remember { mutableStateOf(AiCoreProbe.Status(AiCoreProbe.Tier.UNSUPPORTED)) }
  var modelDownloadBusy by remember { mutableStateOf(false) }
  val hasUsageData = CompanionSuggest.hasData(ctx)
  var debugExpanded by remember { mutableStateOf(false) }
  var settingsExpanded by remember { mutableStateOf(true) }
  var addReplyStatus by remember { mutableStateOf<ActionLine?>(null) }
  var cwdSaveStatus by remember { mutableStateOf<ActionLine?>(null) }
  var cwdSaveLoading by remember { mutableStateOf(false) }
  var cwdCreatePrompt by remember { mutableStateOf<String?>(null) }
  var cwdCreateLoading by remember { mutableStateOf(false) }
  var debugWidgetLoading by remember { mutableStateOf(false) }
  var debugWidgetStatus by remember { mutableStateOf<ActionLine?>(null) }
  var relayActionStatus by remember { mutableStateOf<ActionLine?>(null) }
  var lanOnly by remember { mutableStateOf(RelayService.isLanOnlyEnabled(ctx)) }
  var selectedSnoozeLabel by remember { mutableStateOf<String?>(null) }
  var snoozeActionStatus by remember { mutableStateOf<ActionLine?>(null) }

  LaunchedEffect(Unit) {
    RelayService.setPreWarmMicEnabled(ctx, true)
    suggestionsLoading = true
    val loaded = withContext(Dispatchers.Default) { CompanionSuggest.load(ctx) }
    suggestions = loaded
    aiCoreStatus = loaded.aiCore
    suggestionsLoading = false
  }

  LaunchedEffect(snoozeUntilMs) {
    if (System.currentTimeMillis() < snoozeUntilMs) {
      val mins = (UserPrefs.snoozeDurationMs(ctx) / 60_000L).toInt().coerceAtLeast(1)
      selectedSnoozeLabel = formatSnoozeLabel(mins)
    } else {
      selectedSnoozeLabel = null
    }
  }

  suspend fun refreshAiCoreAndSuggestions() {
    suggestionsLoading = true
    val loaded = withContext(Dispatchers.Default) { CompanionSuggest.load(ctx) }
    suggestions = loaded
    aiCoreStatus = loaded.aiCore
    suggestionsLoading = false
  }

  fun persistQuickReplies(list: List<String>) {
    quickReplies = list
    UserPrefs.setQuickReplies(ctx, list)
    RelayService.pushCompanionConfig(ctx)
  }

  fun persistChipToggles(continueOn: Boolean, dictateOn: Boolean, autoContinueOn: Boolean = autoContinue) {
    showContinue = continueOn
    showDictate = dictateOn
    autoContinue = autoContinueOn
    UserPrefs.setShowContinueChip(ctx, continueOn)
    UserPrefs.setShowDictateChip(ctx, dictateOn)
    UserPrefs.setAutoContinueEnabled(ctx, autoContinueOn)
    RelayService.pushCompanionConfig(ctx)
  }

  fun activateSnoozeMinutes(minutes: Int) {
    UserPrefs.activateSnooze(ctx, minutes * 60_000L)
    snoozeUntilMs = UserPrefs.getSnoozeUntilMs(ctx)
    selectedSnoozeLabel = formatSnoozeLabel(minutes)
    snoozeActionStatus = ActionLine("Snooze ${formatSnoozeLabel(minutes)} — agent cards hidden", ok = true)
    RelayService.pushCompanionConfig(ctx)
  }

  fun clearSnooze() {
    UserPrefs.clearSnooze(ctx)
    snoozeUntilMs = 0L
    selectedSnoozeLabel = null
    snoozeActionStatus = ActionLine("Snooze ended", ok = true)
    RelayService.pushCompanionConfig(ctx)
  }

  fun pickSnoozeMinutes(minutes: Int) {
    val label = formatSnoozeLabel(minutes)
    val active = System.currentTimeMillis() < snoozeUntilMs
    if (active && selectedSnoozeLabel == label) clearSnooze()
    else activateSnoozeMinutes(minutes)
  }

  fun clearCwdFeedback() {
    cwdSaveStatus = null
    cwdCreatePrompt = null
  }

  suspend fun persistCwdToMac(path: String, create: Boolean) {
    val normalized = RelayConfig.normalizeCwdInput(path)
    when (val outcome = RelayConfig.saveDefaultCwd(ctx, url, svcStatus.url, normalized, create)) {
      is CwdSaveOutcome.Saved -> {
        ctx.getSharedPreferences("ambient-link-meta", Context.MODE_PRIVATE)
          .edit().putString("default_cwd", normalized).apply()
        cwd = normalized
        clearCwdFeedback()
        cwdSaveStatus = ActionLine("Directory saved", ok = true)
      }
      is CwdSaveOutcome.NotFound -> {
        cwdCreatePrompt = outcome.resolvedPath
        cwdSaveStatus = null
      }
      is CwdSaveOutcome.Unreachable -> {
        cwdCreatePrompt = null
        cwdSaveStatus = ActionLine(outcome.hint, ok = false)
      }
      is CwdSaveOutcome.Failed -> {
        cwdCreatePrompt = null
        cwdSaveStatus = ActionLine(outcome.message, ok = false)
      }
    }
  }

  val glassesTint = glassesIconColor(regState, displayDevice)

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = Color.Transparent,
    topBar = {
      Column(
        Modifier
          .fillMaxWidth()
          .hazeEffect(state = hazeState, style = hazeStyle)
          .padding(top = 10.dp, bottom = 8.dp),
      ) {
        Row(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            "ambient link",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Icon(
            painter = painterResource(R.drawable.ic_meta_glasses),
            contentDescription = "Glasses link",
            tint = glassesTint,
            modifier = Modifier
              .size(78.dp)
              .clickable {
                if (regState != RegistrationState.REGISTERED && sdkReady) {
                  wearablesRepo.startRegistration(activity)
                }
              },
          )
        }
        StatusRail(
          metaAiColor = regState.statusColor(),
          proxyColor = relayStatusColor(svcStatus),
        )
      }
    },
  ) { innerPadding ->
    Box(
      Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      Column(
        Modifier
          .fillMaxSize()
          .verticalScroll(scrollState)
          .hazeSource(state = hazeState)
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
      if (!sdkReady) {
        HintBanner("Grant Bluetooth permissions to initialize the glasses SDK.")
      }
      if (regState != RegistrationState.REGISTERED && sdkReady) {
        HintBanner("Tap the glasses icon to connect Meta AI.")
      }

      CollapsibleSectionCard(
        title = "Settings",
        expanded = settingsExpanded,
        onToggle = { settingsExpanded = !settingsExpanded },
      ) {
        SettingsBlockLabel(
          "Default agent",
          "Used when you start a new session from the glasses web app.",
        )
        AmbientPillGrid(
          pills = UserPrefs.DEFAULT_AGENTS.map { it.replaceFirstChar { c -> c.uppercase() } },
          selected = setOf(defaultAgent.replaceFirstChar { it.uppercase() }),
          onPillClick = { label ->
            val agent = UserPrefs.DEFAULT_AGENTS.firstOrNull {
              it.replaceFirstChar { c -> c.uppercase() } == label
            } ?: return@AmbientPillGrid
            defaultAgent = agent
            UserPrefs.setDefaultAgent(ctx, agent)
            RelayService.pushCompanionConfig(ctx)
          },
        )
        InlineSaveField(
          label = "Working directory",
          value = cwd,
          onValueChange = { cwd = it; clearCwdFeedback() },
          placeholder = "~/Projects/my-app",
          actionLabel = "Save",
          actionLoading = cwdSaveLoading,
          onAction = {
            val v = cwd.trim()
            if (v.isEmpty()) {
              cwdSaveStatus = ActionLine("Enter a folder path", ok = false)
              return@InlineSaveField
            }
            cwdSaveLoading = true
            clearCwdFeedback()
            scope.launch {
              persistCwdToMac(v, create = false)
              cwdSaveLoading = false
            }
          },
        )
        InlineActionStatus(cwdSaveStatus)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        SettingsBlockLabel("Theme")
        AmbientPillGrid(
          pills = listOf("Meta", "Dracula", "Tokyo", "Catppuccin", "Nord"),
          selected = setOf(
            when (uiTheme) {
              "dracula" -> "Dracula"
              "tokyo-night" -> "Tokyo"
              "catppuccin" -> "Catppuccin"
              "nord" -> "Nord"
              else -> "Meta"
            },
          ),
          onPillClick = { label ->
            val next = when (label) {
              "Dracula" -> "dracula"
              "Tokyo" -> "tokyo-night"
              "Catppuccin" -> "catppuccin"
              "Nord" -> "nord"
              else -> "meta"
            }
            onUiThemeChange(next)
          },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        SettingsBlockLabel("Glasses action chips")
        CompactToggle(
          label = "Continue button",
          checked = showContinue,
          onCheckedChange = { persistChipToggles(it, showDictate) },
        )
        CompactToggle(
          label = "5s auto-continue countdown",
          checked = autoContinue,
          onCheckedChange = { persistChipToggles(showContinue, showDictate, it) },
        )
        CompactToggle(
          label = "Dictate button",
          checked = showDictate,
          onCheckedChange = { persistChipToggles(showContinue, it) },
        )
        if (hasUsageData) {
          AiQuickReplySuggestions(
            hazeState = hazeState,
            suggestions = suggestions.quickReplies,
            loading = suggestionsLoading,
            fromAi = suggestions.fromAi,
            selected = quickReplies.toSet(),
            onAdd = { pill ->
              persistQuickReplies(
                if (pill in quickReplies) quickReplies.filter { it != pill }
                else (quickReplies + pill).distinct().take(12),
              )
            },
          )
        }
        QuickRepliesEditor(
          replies = quickReplies,
          hazeState = hazeState,
          addStatus = addReplyStatus,
          onChange = { list ->
            val added = list.size > quickReplies.size
            persistQuickReplies(list)
            if (added) addReplyStatus = ActionLine("Quick reply added", ok = true)
          },
        )
      }

      SectionCard(title = "Snooze") {
        val snoozing = System.currentTimeMillis() < snoozeUntilMs
        Text(
          if (snoozing) "Active — agent cards are discarded until snooze ends."
          else "Hide agent cards for a while. Messages are discarded, not saved for later.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AmbientPillGrid(
          pills = UserPrefs.SUGGESTED_SNOOZE_MINUTES.map { formatSnoozeLabel(it) },
          selected = buildSet {
            selectedSnoozeLabel?.let { add(it) }
          },
          onPillClick = { label ->
            val mins = UserPrefs.SUGGESTED_SNOOZE_MINUTES.firstOrNull { formatSnoozeLabel(it) == label }
            if (mins != null) pickSnoozeMinutes(mins)
          },
        )
        if (hasUsageData) {
          AiSnoozeSuggestions(
            hazeState = hazeState,
            suggestions = suggestions.snooze,
            loading = suggestionsLoading,
            fromAi = suggestions.fromAi,
            selected = buildSet { selectedSnoozeLabel?.let { add(it) } },
            onPick = { s -> pickSnoozeMinutes(s.minutes) },
          )
        }
        InlineActionStatus(snoozeActionStatus)
        if (snoozing) {
          AmbientPrimaryButton(text = "End snooze now", onClick = { clearSnooze() })
        }
      }

      CollapsibleSectionCard(
        title = "Debug",
        expanded = debugExpanded,
        onToggle = { debugExpanded = !debugExpanded },
      ) {
        Text(
          "Mac relay",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompactToggle(
          label = "LAN only (debug)",
          description = "Relay + glasses web from your Mac — no cloud fallback.",
          checked = lanOnly,
          onCheckedChange = { on ->
            lanOnly = on
            RelayService.setLanOnlyEnabled(ctx, on)
            busy = true
            relayActionStatus = null
            scope.launch {
              if (on) {
                relayActionStatus = ActionLine("LAN-only — discovering Mac…", ok = true)
                val found = RelayService.discoverUrl(ctx)
                if (found != null) {
                  url = found
                  RelayService.start(ctx, found, force = true)
                  relayActionStatus = ActionLine("LAN-only → $found", ok = true)
                } else {
                  RelayService.start(ctx, null, force = true)
                  relayActionStatus = ActionLine(
                    "LAN-only — no Mac on Wi‑Fi (relay running? same network?)",
                    ok = false,
                  )
                }
              } else {
                RelayService.start(ctx, null, force = true)
                relayActionStatus = ActionLine("Cloud fallback enabled", ok = true)
              }
              busy = false
            }
          },
        )
        if (svcStatus.url.isNotBlank()) {
          InlineMono("Host", svcStatus.url)
          val webOrigin = svcStatus.url
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .substringBefore("/ambient-link")
          InlineMono("Web", if (lanOnly || svcStatus.url.startsWith("ws://")) "$webOrigin/ambient-link/" else "public.computer (cloud)")
        }
        if (svcStatus.threads.isNotEmpty()) InlineMono("Threads", svcStatus.threads.joinToString(", "))
        svcStatus.lastError?.takeIf { !svcStatus.running }?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
        }
        CompactToggle(
          label = "Pre-load speech model",
          checked = preloadSoda,
          onCheckedChange = {
            preloadSoda = it
            RelayService.setSodaPreloadEnabled(ctx, it)
          },
        )
        CompactToggle(
          label = "Glasses Bluetooth mic (in-call UI)",
          description = "Triggers in-call UI on glasses — leave off unless testing.",
          checked = bluetoothSco,
          onCheckedChange = {
            bluetoothSco = it
            RelayService.setBluetoothScoEnabled(ctx, it)
          },
        )
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("Relay URL") },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth(),
        )
        DaemonActions(
          busy = busy,
          daemonRunning = svcStatus.running,
          onStart = {
            busy = true
            relayActionStatus = null
            scope.launch {
              var target = url.trim()
              if (!isUsableRelayUrl(target)) {
                relayActionStatus = ActionLine("Discovering host…", ok = true)
                target = RelayService.discoverUrl(ctx) ?: ""
              }
              if (!isUsableRelayUrl(target)) {
                relayActionStatus = ActionLine("No host — start ambient-link on your Mac", ok = false)
                busy = false
                return@launch
              }
              url = target
              RelayService.start(ctx, target)
              relayActionStatus = ActionLine("Connecting to $target", ok = true)
              busy = false
            }
          },
          onStop = {
            RelayService.stop(ctx)
            relayActionStatus = ActionLine("Daemon stopped", ok = true)
          },
          onDiscover = {
            busy = true
            relayActionStatus = null
            scope.launch {
              relayActionStatus = ActionLine("Discovering…", ok = true)
              val found = RelayService.discoverUrl(ctx)
              if (found != null) {
                url = found
                RelayService.start(ctx, found, force = true)
                relayActionStatus = ActionLine("Connecting to $found", ok = true)
              } else {
                val cached = com.lowkey.ambientlink.relay.RelayLanStore.lastLanWs(ctx)
                relayActionStatus = ActionLine(
                  if (cached != null) {
                    "Mac not found — last seen $cached (same Wi‑Fi? relay running?)"
                  } else {
                    "No host on LAN — start ambient-link on your Mac"
                  },
                  ok = false,
                )
              }
              busy = false
            }
          },
        )
        InlineActionStatus(relayActionStatus)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        SodaDebugPanel(ctx)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        val debugSession = GlassesDisplay.session
        val canDebug = sdkReady && displayDevice != null && displayDevice.linkState == LinkState.CONNECTED
        AmbientPrimaryButton(
          text = if (debugWidgetLoading) "Sending…" else "Fire test widget on glasses",
          enabled = canDebug && !debugWidgetLoading,
          loading = debugWidgetLoading,
          onClick = {
            val id = devicesMeta.entries.firstOrNull { it.value == displayDevice }?.key
            if (id == null) {
              debugWidgetStatus = ActionLine("No display device", ok = false)
              return@AmbientPrimaryButton
            }
            debugWidgetLoading = true
            debugWidgetStatus = null
            scope.launch {
              try {
                debugFireWidget(scope, debugSession, id)
                debugWidgetStatus = ActionLine("Test card sent — tap OK on glasses", ok = true)
              } catch (e: Exception) {
                debugWidgetStatus = ActionLine("Failed: ${e.message ?: "unknown"}", ok = false)
              }
              debugWidgetLoading = false
            }
          },
        )
        InlineActionStatus(debugWidgetStatus)
        if (!canDebug) {
          Text(
            "Requires Meta AI registered and glasses connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        SettingsBlockLabel(
          "AI Core",
          "On-device Gemini Nano for smarter quick-reply and snooze suggestions.",
        )
        AiCoreSettingsSection(status = aiCoreStatus, probing = suggestionsLoading)
      }

      Spacer(Modifier.height(8.dp))
      }

      cwdCreatePrompt?.let { resolved ->
        AlertDialog(
          onDismissRequest = { cwdCreatePrompt = null },
          title = { Text("Create folder?") },
          text = {
            Text(
              "This folder is not on your Mac yet:\n$resolved",
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          confirmButton = {
            TextButton(
              enabled = !cwdCreateLoading,
              onClick = {
                cwdCreateLoading = true
                scope.launch {
                  persistCwdToMac(cwd, create = true)
                  cwdCreateLoading = false
                }
              },
            ) { Text(if (cwdCreateLoading) "Creating…" else "Create on Mac") }
          },
          dismissButton = {
            TextButton(onClick = { cwdCreatePrompt = null }) { Text("Cancel") }
          },
        )
      }

      if (showTipOverlay) {
        FirstRunTipOverlay(
          aiCore = aiCoreStatus,
          probing = suggestionsLoading,
          downloadBusy = modelDownloadBusy,
          onDismiss = {
            UserPrefs.setCompanionTipSeen(ctx)
            showTipOverlay = false
          },
          onDownloadModel = {
            modelDownloadBusy = true
            scope.launch {
              val ok = AiCoreProbe.downloadModel()
              aiCoreStatus = AiCoreProbe.probe()
              if (ok) refreshAiCoreAndSuggestions()
              modelDownloadBusy = false
              Toast.makeText(
                ctx,
                if (ok) "Gemini Nano ready" else "Download failed — try AI Core settings",
                Toast.LENGTH_LONG,
              ).show()
            }
          },
          onOpenAiCore = {
            if (!AiCoreProbe.openAiCore(ctx)) {
              Toast.makeText(
                ctx,
                "Settings → Google → System services → AI Core",
                Toast.LENGTH_LONG,
              ).show()
            }
          },
        )
      }
    }
  }
}

@Composable
private fun SectionCard(
  title: String,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = AmbientTheme.sectionBackground),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(
      Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        trailing?.invoke()
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
      content()
    }
  }
}

@Composable
private fun CollapsibleSectionCard(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = AmbientTheme.sectionBackground),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp))) {
      Row(
        Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          if (expanded) "▾" else "▸",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (expanded) {
        HorizontalDivider(
          modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
          color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          content()
        }
      }
    }
  }
}

@Composable
private fun StatusRail(
  metaAiColor: Color,
  proxyColor: Color,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StatusRailCell("Meta AI", metaAiColor)
    StatusRailCell("Relay", proxyColor)
  }
}

@Composable
private fun StatusRailCell(
  label: String,
  dotColor: Color,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(dotColor),
    )
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Medium,
      fontSize = 11.sp,
      maxLines = 1,
    )
  }
}

@Composable
private fun glassesIconColor(
  regState: RegistrationState,
  displayDevice: com.meta.wearable.dat.core.types.Device?,
): Color = when {
  regState != RegistrationState.REGISTERED -> Color(0xFFF0A93C)
  displayDevice == null -> MaterialTheme.colorScheme.onSurfaceVariant
  displayDevice.linkState == LinkState.CONNECTED -> MaterialTheme.colorScheme.secondary
  displayDevice.linkState == LinkState.CONNECTING -> Color(0xFFF0A93C)
  else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun linkStatusColor(state: LinkState): Color = when (state) {
  LinkState.CONNECTED -> MaterialTheme.colorScheme.secondary
  LinkState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
  LinkState.CONNECTING -> Color(0xFFF0A93C)
  else -> Color(0xFFF0A93C)
}

@Composable
private fun CompactToggle(
  label: String,
  description: String? = null,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier.weight(1f).padding(end = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
      )
      if (!description.isNullOrBlank()) {
        Text(
          description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 17.sp,
        )
      }
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = AmbientTheme.switchColors(),
    )
  }
}

@Composable
private fun InlineMono(label: String, value: String) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(52.dp),
    )
    Text(
      value,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun HintBanner(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = Color(0xFFF0A93C),
  )
}

@Composable
private fun DaemonActions(
  busy: Boolean,
  daemonRunning: Boolean,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onDiscover: () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stack = maxWidth < 420.dp
    if (stack) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AmbientPrimaryButton(
          text = if (busy) "Starting…" else "Start daemon",
          enabled = !busy,
          loading = busy,
          onClick = onStart,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
          AmbientPrimaryButton(
            text = "Stop",
            enabled = daemonRunning && !busy,
            onClick = onStop,
            modifier = Modifier.weight(1f),
          )
          AmbientPrimaryButton(
            text = "Discover",
            enabled = !busy,
            onClick = onDiscover,
            modifier = Modifier.weight(1f),
          )
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        AmbientPrimaryButton(
          text = if (busy) "Starting…" else "Start",
          enabled = !busy,
          loading = busy,
          onClick = onStart,
          modifier = Modifier.weight(1f),
        )
        AmbientPrimaryButton(
          text = "Stop",
          enabled = daemonRunning && !busy,
          onClick = onStop,
          modifier = Modifier.weight(1f),
        )
        AmbientPrimaryButton(
          text = "Discover",
          enabled = !busy,
          onClick = onDiscover,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

private fun RegistrationState.displayLabel(): String = when (this) {
  RegistrationState.REGISTERED -> "Registered"
  RegistrationState.REGISTERING -> "Registering"
  RegistrationState.AVAILABLE -> "Available"
  RegistrationState.UNAVAILABLE -> "Unavailable"
  else -> name.lowercase().replaceFirstChar { it.titlecase() }
}

@Composable
private fun RegistrationState.statusColor(): Color = when (this) {
  RegistrationState.REGISTERED -> MaterialTheme.colorScheme.secondary
  RegistrationState.AVAILABLE, RegistrationState.REGISTERING -> Color(0xFFF0A93C)
  else -> MaterialTheme.colorScheme.error
}

private fun LinkState.displayLabel(): String = when (this) {
  LinkState.CONNECTED -> "Connected"
  LinkState.DISCONNECTED -> "Disconnected"
  LinkState.CONNECTING -> "Connecting"
  else -> name.lowercase().replaceFirstChar { it.titlecase() }
}

@Composable
private fun LinkState.statusColor(): Color = linkStatusColor(this)

private fun relayLabel(s: RelayService.Status): String = when {
  !s.running -> "Stopped"
  s.connected -> "Connected"
  else -> "Reconnecting"
}

@Composable
private fun relayStatusColor(s: RelayService.Status): Color = when {
  !s.running -> MaterialTheme.colorScheme.onSurfaceVariant
  s.connected -> MaterialTheme.colorScheme.secondary
  else -> Color(0xFFF0A93C)
}

private suspend fun debugFireWidget(
  scope: kotlinx.coroutines.CoroutineScope,
  session: com.lowkey.ambientlink.hud.DatDisplaySession,
  deviceId: com.meta.wearable.dat.core.types.DeviceIdentifier,
) {
  val TAG = "ambient.debug"
  Log.i(TAG, "prepareDisplay id=$deviceId")
  session.prepareDisplay(deviceId, onReady = { display ->
    Log.i(TAG, "display ready — sending debug card")
    com.lowkey.ambientlink.hud.HudWidgets.sendDebugCard(scope, display, session)
  })
}

private fun isUsableRelayUrl(url: String) =
  url.isNotBlank() && !url.contains("example.com")
