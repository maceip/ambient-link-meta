package com.lowkey.facechat

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lowkey.facechat.hud.GlassesDisplay
import com.lowkey.facechat.relay.RelayService
import com.lowkey.facechat.wearables.WearablesRepository
import com.lowkey.facechat.wearables.WearablesRuntime
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
      MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF000000),
        surface    = Color(0xFF0A0A0F),
        primary    = Color(0xFF00D4FF),
      )) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), color = MaterialTheme.colorScheme.background) {
          SettingsScreen(this, wearablesRepo)
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
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}

@Composable
private fun SettingsScreen(activity: ComponentActivity, wearablesRepo: WearablesRepository) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val regState by wearablesRepo.registrationState.collectAsState()
  val devicesMeta by wearablesRepo.devicesMetadata.collectAsState()
  val svcStatus by RelayService.status.collectAsState()
  val sdkReady = WearablesRuntime.initialized

  val displayDevice = devicesMeta.values.firstOrNull { it.isDisplayCapable() }
  val linkLabel = displayDevice?.linkState?.name?.lowercase() ?: "unknown"
  val linkColor = when (displayDevice?.linkState) {
    LinkState.CONNECTED -> Color(0xFF00FF88)
    else -> Color(0xFFFFAA00)
  }

  var url by remember {
    mutableStateOf(ctx.getSharedPreferences("face-chat-final", Context.MODE_PRIVATE)
      .getString("relay_url", BuildConfig.DEFAULT_RELAY_URL) ?: BuildConfig.DEFAULT_RELAY_URL)
  }
  LaunchedEffect(svcStatus.url) {
    if (svcStatus.url.isNotBlank()) url = svcStatus.url
  }

  Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("face·chat", color = Color.White, fontSize = 22.sp)
    Text("background daemon — keeps glasses notifiable from your relay", color = Color(0xFFA0A0B0), fontSize = 12.sp)

    if (!sdkReady) {
      Text("grant Bluetooth permissions to initialize glasses SDK", color = Color(0xFFFFAA00), fontSize = 12.sp)
    }

    StatusRow("glasses pairing", regState.name, when (regState) {
      RegistrationState.REGISTERED  -> Color(0xFF00FF88)
      RegistrationState.AVAILABLE,
      RegistrationState.REGISTERING -> Color(0xFFFFAA00)
      else -> Color(0xFFFF4466)
    })
    if (displayDevice != null) {
      StatusRow("glasses link", linkLabel, linkColor)
      Text(
        "${displayDevice.name} · ${displayDevice.compatibility.name.lowercase()}",
        color = Color(0xFFA0A0B0), fontSize = 11.sp,
      )
    }
    if (regState != RegistrationState.REGISTERED && sdkReady) {
      Button(onClick = { wearablesRepo.startRegistration(activity) }) { Text("pair glasses") }
    }

    StatusRow("relay", relayLabel(svcStatus), relayColor(svcStatus))
    if (svcStatus.url.isNotBlank()) {
      Text(svcStatus.url, color = Color(0xFFA0A0B0), fontSize = 11.sp)
    }
    Text(
      if (svcStatus.running) "daemon running" else "daemon stopped",
      color = if (svcStatus.running) Color(0xFF00FF88) else Color(0xFFA0A0B0),
      fontSize = 11.sp,
    )
    if (svcStatus.threads.isNotEmpty()) {
      Text("threads: " + svcStatus.threads.joinToString(", "), color = Color(0xFFA0A0B0), fontSize = 12.sp)
    }
    if (!svcStatus.running && svcStatus.lastError != null) {
      Text("relay error: ${svcStatus.lastError}", color = Color(0xFFFF4466), fontSize = 11.sp)
    }

    OutlinedTextField(
      value = url, onValueChange = { url = it },
      label = { Text("relay URL") }, singleLine = true,
      keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = {
          busy = true
          scope.launch {
            var target = url.trim()
            if (!isUsableRelayUrl(target)) {
              Toast.makeText(ctx, "discovering host…", Toast.LENGTH_SHORT).show()
              target = RelayService.discoverUrl(ctx) ?: ""
            }
            if (!isUsableRelayUrl(target)) {
              Toast.makeText(ctx, "no host found — start ambient-link on your Mac", Toast.LENGTH_LONG).show()
              busy = false
              return@launch
            }
            url = target
            RelayService.start(ctx, target)
            Toast.makeText(ctx, "connecting to $target", Toast.LENGTH_SHORT).show()
            busy = false
          }
        },
        enabled = !busy,
      ) { Text(if (busy) "starting…" else "start daemon") }
      OutlinedButton(
        onClick = {
          RelayService.stop(ctx)
          Toast.makeText(ctx, "daemon stopped", Toast.LENGTH_SHORT).show()
        },
        enabled = svcStatus.running && !busy,
      ) { Text("stop") }
      OutlinedButton(
        onClick = {
          busy = true
          scope.launch {
            Toast.makeText(ctx, "discovering…", Toast.LENGTH_SHORT).show()
            val found = RelayService.discoverUrl(ctx)
            if (found != null) {
              url = found
              RelayService.start(ctx, found, force = true)
              Toast.makeText(ctx, "connecting to $found", Toast.LENGTH_SHORT).show()
            } else {
              Toast.makeText(ctx, "no host on LAN", Toast.LENGTH_SHORT).show()
            }
            busy = false
          }
        },
        enabled = !busy,
      ) { Text("discover") }
    }

    val debugSession = GlassesDisplay.session
    val canDebug = sdkReady && displayDevice != null && displayDevice.linkState == LinkState.CONNECTED

    Spacer(Modifier.height(8.dp))
    Text("debug", color = Color(0xFFA0A0B0), fontSize = 11.sp)
    if (displayDevice != null && displayDevice.linkState != LinkState.CONNECTED) {
      Text(
        "open Meta AI / connect glasses in Stella until link shows connected",
        color = Color(0xFFFFAA00), fontSize = 11.sp,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = {
          val id = devicesMeta.entries.firstOrNull { it.value == displayDevice }?.key ?: return@Button
          scope.launch { debugFireWidget(debugSession, id) }
        },
        enabled = canDebug,
      ) { Text("DEBUG: fire widget") }
    }
  }
}

private suspend fun debugFireWidget(session: com.lowkey.facechat.hud.DatDisplaySession, deviceId: com.meta.wearable.dat.core.types.DeviceIdentifier) {
  val TAG = "fc.debug"
  Log.i(TAG, "prepareDisplay id=$deviceId")
  session.prepareDisplay(deviceId, onReady = { d ->
    kotlinx.coroutines.GlobalScope.launch {
      d.sendContent {
        flexBox(gap = 8, padding = 16) {
          text("debug", style = TextStyle.META, color = TextColor.SECONDARY)
          flexBox(padding = 12, background = FlexBoxBackground.CARD) {
            text("Hello from face·chat debug.", style = TextStyle.BODY)
          }
          flexBox(gap = 6, padding = 0, background = FlexBoxBackground.NONE) {
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

private fun relayLabel(s: RelayService.Status): String = when {
  !s.running -> "stopped"
  s.connected -> "connected"
  else -> "reconnecting"
}

private fun relayColor(s: RelayService.Status): Color = when {
  !s.running -> Color(0xFFA0A0B0)
  s.connected -> Color(0xFF00FF88)
  else -> Color(0xFFFFAA00)
}

@Composable
private fun StatusRow(label: String, value: String, dotColor: Color) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(dotColor))
    Text(label, color = Color(0xFFA0A0B0), fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(value, color = Color.White, fontSize = 13.sp)
  }
}
