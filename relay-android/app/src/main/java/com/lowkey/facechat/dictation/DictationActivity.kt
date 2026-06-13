package com.lowkey.facechat.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lowkey.facechat.soda.SodaDictationEngine
import com.lowkey.facechat.soda.SodaRuntime

/**
 * Phone-side dictate capture. Primary path: on-device SODA (~/neural stack).
 * Falls back to system SpeechRecognizer only when libsoda / pack is unavailable.
 */
class DictationActivity : ComponentActivity() {
  private var speechRecognizer: SpeechRecognizer? = null
  private var sodaEngine: SodaDictationEngine? = null
  private var status by mutableStateOf("listening…")
  private var useSoda by mutableStateOf(false)

  private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    if (ok) startListening() else {
      Toast.makeText(this, "microphone permission required for dictate", Toast.LENGTH_LONG).show()
      finishCancel()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF000000), primary = Color(0xFF00D4FF))) {
        Surface(Modifier.fillMaxSize().background(Color(0xFF0A0A0F))) {
          Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text("dictate", color = Color(0xFF00D4FF), fontSize = 22.sp)
            Spacer(Modifier.height(12.dp))
            Text(status, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(
              if (useSoda) "on-device SODA · speak into your phone" else "speak into your phone",
              color = Color(0xFFA0A0B0),
              fontSize = 13.sp,
            )
            if (useSoda) {
              Spacer(Modifier.height(20.dp))
              Button(
                onClick = { finishWithPartial() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D4FF)),
              ) { Text("send", color = Color.Black) }
            }
          }
        }
      }
    }

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      startListening()
    } else {
      status = "need mic permission"
      micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  private fun startListening() {
    if (SodaRuntime.isAvailable(this)) {
      useSoda = true
      startSoda()
      return
    }
    startSpeechRecognizer()
  }

  private fun startSoda() {
    status = "preparing…"
    sodaEngine = SodaDictationEngine(
      context = this,
      onPartial = { text ->
        status = text
        DictationLauncher.deliverPartial(text)
      },
      onFinal = { text ->
        DictationLauncher.deliverFinal(text)
        finish()
      },
      onError = { reason ->
        Toast.makeText(this, "SODA failed ($reason) — trying system STT", Toast.LENGTH_SHORT).show()
        sodaEngine?.stop(commitPartial = false)
        sodaEngine = null
        useSoda = false
        startSpeechRecognizer()
      },
      onStatus = { status = it },
    ).also { it.start() }
  }

  private fun finishWithPartial() {
    sodaEngine?.stop(commitPartial = true)
    sodaEngine = null
    finish()
  }

  private fun startSpeechRecognizer() {
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      Toast.makeText(this, "speech recognition not available on this device", Toast.LENGTH_LONG).show()
      finishCancel()
      return
    }
    status = "listening…"
    speechRecognizer?.destroy()
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
      sr.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status = "listening…" }
        override fun onBeginningOfSpeech() { status = "hearing you…" }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status = "processing…" }
        override fun onError(error: Int) {
          val msg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that — try again"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timed out — try again"
            else -> "dictation error ($error)"
          }
          Toast.makeText(this@DictationActivity, msg, Toast.LENGTH_SHORT).show()
          DictationLauncher.cancel(msg)
          finish()
        }
        override fun onResults(results: Bundle?) {
          val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
          if (!text.isNullOrBlank()) {
            DictationLauncher.deliverFinal(text)
            finish()
          } else {
            finishCancel()
          }
        }
        override fun onPartialResults(partialResults: Bundle?) {
          val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
          if (!text.isNullOrBlank()) {
            status = text
            DictationLauncher.deliverPartial(text)
          }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
      })
      sr.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        },
      )
    }
  }

  private fun finishCancel() {
    DictationLauncher.cancel()
    finish()
  }

  override fun onDestroy() {
    sodaEngine?.stop(commitPartial = false)
    sodaEngine = null
    speechRecognizer?.destroy()
    speechRecognizer = null
    super.onDestroy()
  }

  companion object {
    const val EXTRA_THREAD = "thread"
  }
}
