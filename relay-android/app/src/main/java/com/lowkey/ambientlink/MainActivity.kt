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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.lowkey.ambientlink.ui.QuickRepliesEditor
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

private val AmbientColorScheme = darkColorScheme(
  primary = Color(0xFF1C84FF),
  onPrimary = Color(0xFFF3F5F8),
  secondary = Color(0xFF3DC97A),
  onSecondary = Color(0xFF0D0F13),
  background = Color(0xFF000000),
  surface = Color(0xFF0D0F13),
  surfaceVariant = Color(0xFF1D2025),
  onSurface = Color(0xFFF3F5F8),
  onSurfaceVariant = Color(0xFF8C939E),
  outline = Color(0xFF2E323A),
  error = Color(0xFFF0566E),
)

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
      MaterialTheme(colorScheme = AmbientColorScheme, typography = MaterialTheme.typography) {
        Surface(
          Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
          color = MaterialTheme.colorScheme.background,
        ) {
          ControlScreen(this, wearablesRepo)
        }
      }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ControlScreen(activity: ComponentActivity, wearablesRepo: WearablesRepository) {
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
  var addReplyStatus by remember { mutableStateOf<ActionLine?>(null) }
  var cwdSaveStatus by remember { mutableStateOf<ActionLine?>(null) }
  var cwdSaveLoading by remember { mutableStateOf(false) }
  var cwdCreatePrompt by remember { mutableStateOf<String?>(null) }
  var cwdCreateLoading by remember { mutableStateOf(false) }
  var debugWidgetLoading by remember { mutableStateOf(false) }
  var debugWidgetStatus by remember { mutableStateOf<ActionLine?>(null) }
  var relayActionStatus by remember { mutableStateOf<ActionLine?>(null) }
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
          .padding(top = 8.dp, bottom = 6.dp),
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
              .size(26.dp)
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

      SectionCard(title = "Auto-responder") {
        Text(
          "Default agent",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          UserPrefs.DEFAULT_AGENTS.forEach { agent ->
            FilterChip(
              selected = defaultAgent == agent,
              onClick = {
                defaultAgent = agent
                UserPrefs.setDefaultAgent(ctx, agent)
                RelayService.pushCompanionConfig(ctx)
              },
              label = { Text(agent.replaceFirstChar { it.uppercase() }) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
              ),
            )
          }
        }
        OutlinedTextField(
          value = cwd,
          onValueChange = { cwd = it; clearCwdFeedback() },
          label = { Text("Working directory") },
          placeholder = { Text("~/Projects/my-app") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        AmbientPrimaryButton(
          text = "Save directory",
          loading = cwdSaveLoading,
          onClick = {
            val v = cwd.trim()
            if (v.isEmpty()) {
              cwdSaveStatus = ActionLine("Enter a folder path", ok = false)
              return@AmbientPrimaryButton
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
        CompactToggle("Continue button", showContinue) {
          persistChipToggles(it, showDictate)
        }
        CompactToggle("5s auto-continue countdown", autoContinue) {
          persistChipToggles(showContinue, showDictate, it)
        }
        CompactToggle("Dictate button", showDictate) {
          persistChipToggles(showContinue, it)
        }
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
        com.lowkey.ambientlink.ui.FuzzyPillGrid(
          hazeState = hazeState,
          pills = UserPrefs.SUGGESTED_SNOOZE_MINUTES.map { formatSnoozeLabel(it) },
          selected = buildSet {
            selectedSnoozeLabel?.let { add(it) }
          },
          onPillClick = { label ->
            val mins = UserPrefs.SUGGESTED_SNOOZE_MINUTES.firstOrNull { formatSnoozeLabel(it) == label }
            if (mins != null) activateSnoozeMinutes(mins)
          },
        )
        if (hasUsageData) {
          AiSnoozeSuggestions(
            hazeState = hazeState,
            suggestions = suggestions.snooze,
            loading = suggestionsLoading,
            fromAi = suggestions.fromAi,
            selected = buildSet { selectedSnoozeLabel?.let { add(it) } },
            onPick = { s -> activateSnoozeMinutes(s.minutes) },
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
        if (svcStatus.url.isNotBlank()) InlineMono("Host", svcStatus.url)
        if (svcStatus.threads.isNotEmpty()) InlineMono("Threads", svcStatus.threads.joinToString(", "))
        svcStatus.lastError?.takeIf { !svcStatus.running }?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
        }
        CompactToggle("Pre-load speech model", preloadSoda) {
          preloadSoda = it
          RelayService.setSodaPreloadEnabled(ctx, it)
        }
        CompactToggle("Glasses Bluetooth mic (in-call UI)", bluetoothSco) {
          bluetoothSco = it
          RelayService.setBluetoothScoEnabled(ctx, it)
        }
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
                relayActionStatus = ActionLine("No host on LAN", ok = false)
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
      }

      SectionCard(title = "AI Core") {
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
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
private fun CompactToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Switch(checked = checked, onCheckedChange = onCheckedChange)
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

@OptIn(ExperimentalLayoutApi::class)
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
