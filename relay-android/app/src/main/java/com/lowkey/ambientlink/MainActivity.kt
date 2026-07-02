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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.lowkey.ambientlink.relay.RelayService
import com.lowkey.ambientlink.wearables.WearablesRepository
import com.lowkey.ambientlink.wearables.WearablesRuntime
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.views.Alignment as HudAlignment
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
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
private fun ControlScreen(activity: ComponentActivity, wearablesRepo: WearablesRepository) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val regState by wearablesRepo.registrationState.collectAsState()
  val devicesMeta by wearablesRepo.devicesMetadata.collectAsState()
  val svcStatus by RelayService.status.collectAsState()
  val sdkReady = WearablesRuntime.initialized
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }

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

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        title = {
          Text(
            "ambient link",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Color.Transparent,
          scrolledContainerColor = Color.Transparent,
          titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
          .fillMaxWidth()
          .hazeEffect(state = hazeState, style = hazeStyle),
      )
    },
  ) { innerPadding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(scrollState)
        .hazeSource(state = hazeState)
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Background relay daemon for glasses notifications.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (!sdkReady) {
        HintBanner("Grant Bluetooth permissions to initialize the glasses SDK.")
      }

      SectionCard(title = "Glasses") {
        StatusLine("Pairing", regState.displayLabel(), regState.statusColor())
        if (displayDevice != null) {
          StatusLine("Link", displayDevice.linkState.displayLabel(), displayDevice.linkState.statusColor())
          LabeledValue("Device", displayDevice.name)
          LabeledValue("Compatibility", displayDevice.compatibility.name.replace('_', ' ').lowercase()
            .replaceFirstChar { it.titlecase() })
        } else if (sdkReady) {
          Text(
            "No display-capable glasses found.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (regState != RegistrationState.REGISTERED && sdkReady) {
          FilledTonalButton(
            onClick = { wearablesRepo.startRegistration(activity) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Pair glasses")
          }
        }
        if (displayDevice != null && displayDevice.linkState != LinkState.CONNECTED) {
          HintBanner("Open Meta AI and connect your glasses until Link shows Connected.")
        }
      }

      SectionCard(title = "Relay") {
        StatusLine("Status", relayLabel(svcStatus), relayStatusColor(svcStatus))
        Text(
          if (svcStatus.running) "Daemon running in background" else "Daemon stopped",
          style = MaterialTheme.typography.bodySmall,
          color = if (svcStatus.running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (svcStatus.url.isNotBlank()) {
          LabeledValue("Connected to", svcStatus.url)
        }
        if (svcStatus.threads.isNotEmpty()) {
          LabeledValue("Active threads", svcStatus.threads.joinToString(", "))
        }
        if (!svcStatus.running && svcStatus.lastError != null) {
          Text(
            svcStatus.lastError ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
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
            scope.launch {
              var target = url.trim()
              if (!isUsableRelayUrl(target)) {
                Toast.makeText(ctx, "Discovering host…", Toast.LENGTH_SHORT).show()
                target = RelayService.discoverUrl(ctx) ?: ""
              }
              if (!isUsableRelayUrl(target)) {
                Toast.makeText(ctx, "No host found — start ambient-link on your Mac", Toast.LENGTH_LONG).show()
                busy = false
                return@launch
              }
              url = target
              RelayService.start(ctx, target)
              Toast.makeText(ctx, "Connecting to $target", Toast.LENGTH_SHORT).show()
              busy = false
            }
          },
          onStop = {
            RelayService.stop(ctx)
            Toast.makeText(ctx, "Daemon stopped", Toast.LENGTH_SHORT).show()
          },
          onDiscover = {
            busy = true
            scope.launch {
              Toast.makeText(ctx, "Discovering…", Toast.LENGTH_SHORT).show()
              val found = RelayService.discoverUrl(ctx)
              if (found != null) {
                url = found
                RelayService.start(ctx, found, force = true)
                Toast.makeText(ctx, "Connecting to $found", Toast.LENGTH_SHORT).show()
              } else {
                Toast.makeText(ctx, "No host on LAN", Toast.LENGTH_SHORT).show()
              }
              busy = false
            }
          },
        )
      }

      SectionCard(title = "New session defaults") {
        OutlinedTextField(
          value = cwd,
          onValueChange = { cwd = it },
          label = { Text("Working directory") },
          placeholder = { Text("/path/to/project") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Button(
          onClick = {
            val v = cwd.trim()
            ctx.getSharedPreferences("ambient-link-meta", Context.MODE_PRIVATE)
              .edit().putString("default_cwd", v).apply()
            scope.launch {
              val ok = pushDefaultCwd(url, v)
              Toast.makeText(
                ctx,
                if (ok) "Default directory saved" else "Saved locally (host offline)",
                Toast.LENGTH_SHORT,
              ).show()
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Save default directory")
        }
      }

      SectionCard(title = "Debug") {
        val debugSession = GlassesDisplay.session
        val canDebug = sdkReady && displayDevice != null && displayDevice.linkState == LinkState.CONNECTED
        Text(
          "Send a one-shot test card to the glasses HUD.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = {
            val id = devicesMeta.entries.firstOrNull { it.value == displayDevice }?.key ?: return@OutlinedButton
            scope.launch { debugFireWidget(debugSession, id) }
          },
          enabled = canDebug,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Fire test widget")
        }
      }

      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = MaterialTheme.shapes.large,
  ) {
    Column(
      Modifier.padding(PaddingValues(16.dp)),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
      content()
    }
  }
}

@Composable
private fun StatusLine(label: String, value: String, dotColor: Color) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
      Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(dotColor),
    )
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
    Text(
      value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun LabeledValue(label: String, value: String) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      value,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 3,
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
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
          Text(if (busy) "Starting…" else "Start daemon")
        }
        OutlinedButton(onClick = onStop, enabled = daemonRunning && !busy, modifier = Modifier.fillMaxWidth()) {
          Text("Stop daemon")
        }
        OutlinedButton(onClick = onDiscover, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
          Text("Discover host")
        }
      }
    } else {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(onClick = onStart, enabled = !busy) {
          Text(if (busy) "Starting…" else "Start daemon")
        }
        OutlinedButton(onClick = onStop, enabled = daemonRunning && !busy) {
          Text("Stop daemon")
        }
        OutlinedButton(onClick = onDiscover, enabled = !busy) {
          Text("Discover host")
        }
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
  else -> name.lowercase().replaceFirstChar { it.titlecase() }
}

@Composable
private fun LinkState.statusColor(): Color = when (this) {
  LinkState.CONNECTED -> MaterialTheme.colorScheme.secondary
  else -> Color(0xFFF0A93C)
}

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
  session: com.lowkey.ambientlink.hud.DatDisplaySession,
  deviceId: com.meta.wearable.dat.core.types.DeviceIdentifier,
) {
  val TAG = "ambient.debug"
  Log.i(TAG, "prepareDisplay id=$deviceId")
  session.prepareDisplay(deviceId, onReady = { d ->
    kotlinx.coroutines.GlobalScope.launch {
      d.sendContent {
        flexBox(gap = 10, padding = 16) {
          text("debug", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 14, background = FlexBoxBackground.CARD) {
            text("Hello from ambient link debug.", style = TextStyle.BODY)
          }
          flexBox(
            direction = Direction.ROW,
            gap = 12,
            padding = 0,
            crossAlignment = HudAlignment.CENTER,
            background = FlexBoxBackground.NONE,
          ) {
            button("ok", style = ButtonStyle.PRIMARY, onClick = { Log.i(TAG, "button tapped") })
          }
        }
      }.fold(
        onSuccess = { Log.i(TAG, "sendContent SUCCESS — widget should be on HUD now") },
        onFailure = { e, _ -> Log.e(TAG, "sendContent FAIL: ${e.description}") },
      )
    }
  })
}

private fun isUsableRelayUrl(url: String) =
  url.isNotBlank() && !url.contains("example.com")

private suspend fun pushDefaultCwd(relayUrl: String, cwd: String): Boolean =
  kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
      var base = relayUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
      val cut = base.indexOf("/ambient-link").let { if (it >= 0) it else base.indexOf("/face-chat") }
      if (cut >= 0) base = base.substring(0, cut)
      base = base.trimEnd('/')
      if (base.isBlank()) return@withContext false
      val payload = "{\"default_cwd\":${org.json.JSONObject.quote(cwd)}}"
      val req = okhttp3.Request.Builder()
        .url("$base/ambient-link/config")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
      okhttp3.OkHttpClient().newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
      Log.w("ambient.config", "pushDefaultCwd failed: ${e.message}")
      false
    }
  }
