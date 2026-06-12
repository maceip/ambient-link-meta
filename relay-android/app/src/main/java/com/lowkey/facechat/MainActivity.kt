package com.lowkey.facechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lowkey.facechat.relay.RelayService
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState

// Minimal "settings + status" UI. The actual work lives in RelayService; this Activity
// just lets the user pair with the glasses (Wearables registration), point the daemon at
// the relay URL, and verify the connection is alive.
class MainActivity : ComponentActivity() {
  private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF000000),
        surface    = Color(0xFF0A0A0F),
        primary    = Color(0xFF00D4FF),
      )) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), color = MaterialTheme.colorScheme.background) {
          SettingsScreen(this)
        }
      }
    }
  }
}

@Composable
private fun SettingsScreen(activity: ComponentActivity) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val regState by Wearables.registrationState.collectAsState(initial = RegistrationState.UNAVAILABLE)
  val svcStatus by RelayService.status.collectAsState()

  var url by remember {
    mutableStateOf(ctx.getSharedPreferences("face-chat-final", Context.MODE_PRIVATE)
      .getString("relay_url", BuildConfig.DEFAULT_RELAY_URL) ?: BuildConfig.DEFAULT_RELAY_URL)
  }

  Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("face·chat", color = Color.White, fontSize = 22.sp)
    Text("background daemon — keeps glasses notifiable from your relay", color = Color(0xFFA0A0B0), fontSize = 12.sp)

    StatusRow("glasses pairing", regState.name, when (regState) {
      RegistrationState.REGISTERED  -> Color(0xFF00FF88)
      RegistrationState.AVAILABLE,
      RegistrationState.REGISTERING -> Color(0xFFFFAA00)
      else -> Color(0xFFFF4466)
    })
    if (regState != RegistrationState.REGISTERED) {
      Button(onClick = { Wearables.startRegistration(activity) }) { Text("pair glasses") }
    }

    StatusRow("relay", if (svcStatus.connected) "connected" else "disconnected",
      if (svcStatus.connected) Color(0xFF00FF88) else Color(0xFFFF4466))
    if (svcStatus.threads.isNotEmpty()) {
      Text("threads: " + svcStatus.threads.joinToString(", "), color = Color(0xFFA0A0B0), fontSize = 12.sp)
    }
    svcStatus.lastError?.let { Text("last error: $it", color = Color(0xFFFF4466), fontSize = 11.sp) }

    OutlinedTextField(
      value = url, onValueChange = { url = it },
      label = { Text("relay URL") }, singleLine = true,
      keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { RelayService.start(ctx, url) }) { Text("start daemon") }
      OutlinedButton(onClick = { RelayService.stop(ctx) }) { Text("stop") }
    }
  }
}

@Composable
private fun StatusRow(label: String, value: String, dotColor: Color) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(dotColor))
    Text(label, color = Color(0xFFA0A0B0), fontSize = 13.sp, modifier = Modifier.weight(1f))
    Text(value, color = Color.White, fontSize = 13.sp)
  }
}
