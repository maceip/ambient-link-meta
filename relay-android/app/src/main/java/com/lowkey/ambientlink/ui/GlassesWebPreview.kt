package com.lowkey.ambientlink.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lowkey.ambientlink.BuildConfig

/**
 * Glasses web preview without dumping the companion.
 *
 * Chrome Custom Tabs refuse cleartext `http://` (HTTPS-First). LAN Mac origins
 * are http, so those open in an in-app WebView sheet. HTTPS (cloud) still uses
 * a partial Custom Tab.
 */
private const val TAG = "GlassesWebPreview"

object GlassesWebPreview {
  fun usesInAppSheet(url: String): Boolean =
    Uri.parse(url).scheme.equals("http", ignoreCase = true)

  fun launchPartial(
    activity: Activity,
    url: String,
    launcher: ActivityResultLauncher<Intent>,
  ) {
    val uri = Uri.parse(url)
    val heightPx = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
    val customTabsIntent = CustomTabsIntent.Builder()
      .setShowTitle(true)
      .setUrlBarHidingEnabled(true)
      .setInitialActivityHeightPx(
        heightPx,
        CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE,
      )
      .build()
    val intent = customTabsIntent.intent.apply { data = uri }
    val packageName = CustomTabsClient.getPackageName(activity, null)
    if (packageName != null) intent.setPackage(packageName)
    try {
      // startActivityForResult path is required for partial-height Custom Tabs.
      launcher.launch(intent)
    } catch (e: Exception) {
      Log.w(TAG, "Custom Tab failed, falling back to browser: ${e.message}")
      activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesWebPreviewSheet(
  url: String,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  // Fixed height — weight() inside ModalBottomSheet often collapses the WebView to 0px.
  val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
  val chromeHeight = 72.dp
  val webView = rememberWebView(url)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .height(sheetHeight),
    ) {
      Row(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Glasses web",
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier
            .weight(1f)
            .padding(start = 8.dp),
        )
        TextButton(onClick = onDismiss) { Text("Close") }
      }
      Text(
        url,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      )
      AndroidView(
        factory = { webView },
        modifier = Modifier
          .fillMaxWidth()
          .height(sheetHeight - chromeHeight),
      )
    }
  }

  DisposableEffect(webView) {
    onDispose {
      webView.stopLoading()
      (webView.parent as? ViewGroup)?.removeView(webView)
      webView.destroy()
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun rememberWebView(url: String): WebView {
  val context = androidx.compose.ui.platform.LocalContext.current
  return remember(url) {
    if (BuildConfig.DEBUG) {
      WebView.setWebContentsDebuggingEnabled(true)
    }
    WebView(context).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
          val msg = consoleMessage?.message().orEmpty()
          if (msg.startsWith("AMBIENT_DEBUG")) {
            Log.i(TAG, msg)
          }
          return super.onConsoleMessage(consoleMessage)
        }
      }
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView?,
          request: WebResourceRequest?,
        ): Boolean = false

        override fun onPageFinished(view: WebView?, loaded: String?) {
          super.onPageFinished(view, loaded)
          Log.i(TAG, "page finished $loaded")
          // Poll debug snapshot into logcat (web exposes window.__ambientDebug).
          view?.evaluateJavascript(
            """
            (function(){
              if (window.__ambientDebugProbe) return;
              window.__ambientDebugProbe = setInterval(function(){
                try {
                  var d = window.__ambientDebug;
                  if (!d || !d.snapshot) return;
                  console.log('AMBIENT_DEBUG ' + JSON.stringify(d.snapshot()));
                } catch (e) {}
              }, 1000);
            })();
            """.trimIndent(),
            null,
          )
        }
      }
      // Bust SW/HTML cache so LAN preview picks up freshly built web assets.
      clearCache(true)
      val bust = if (url.contains("?")) "&" else "?"
      loadUrl("$url${bust}_preview=${System.currentTimeMillis()}")
    }
  }
}
