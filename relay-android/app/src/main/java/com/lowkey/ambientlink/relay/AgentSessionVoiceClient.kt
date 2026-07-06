package com.lowkey.ambientlink.relay

import android.content.Context
import android.util.Log
import com.lowkey.ambientlink.dictation.DictationCallback
import com.lowkey.ambientlink.dictation.DictationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Polls the clean agent-session-service for real voice commands, then uses the
 * existing on-device SODA capture path with Bluetooth SCO enabled for glasses mic.
 */
class AgentSessionVoiceClient(
  private val context: Context,
  private val baseUrl: String,
  private val scope: CoroutineScope,
  private val setMicrophoneActive: (Boolean) -> Unit,
) {
  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(35, TimeUnit.SECONDS)
    .build()
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private var job: Job? = null
  @Volatile private var activeSession: String? = null
  @Volatile private var lastPartialAt = 0L
  @Volatile private var lastPartialText = ""

  fun start() {
    if (job?.isActive == true) return
    job = scope.launch(Dispatchers.IO) {
      var after = prefs.getLong(cursorKey(), 0L)
      after = primeCursor(after)
      while (true) {
        try {
          val commands = fetchCommands(after, waitMs = 25_000)
          for (cmd in commands) {
            after = maxOf(after, cmd.id)
            saveCursor(after)
            if (cmd.type == "start") startCapture(cmd.sessionID)
          }
        } catch (e: Exception) {
          Log.w(TAG, "voice poll failed: ${e.message}")
          delay(2_000)
        }
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    stopCapture(sendAbort = false, reason = "voice client stopped")
  }

  private suspend fun primeCursor(after: Long): Long {
    if (prefs.getBoolean(primedKey(), false)) return after
    return try {
      val commands = fetchCommands(after, waitMs = 0)
      val head = commands.maxOfOrNull { it.id } ?: after
      saveCursor(head)
      prefs.edit().putBoolean(primedKey(), true).apply()
      head
    } catch (e: Exception) {
      Log.w(TAG, "voice prime failed: ${e.message}")
      after
    }
  }

  private fun startCapture(sessionID: String) {
    if (sessionID.isBlank()) return
    val busySession = activeSession
    if (busySession != null) {
      postVoice(sessionID, "abort", "dictation already active")
      return
    }
    activeSession = sessionID
    lastPartialAt = 0L
    lastPartialText = ""
    RelayService.setBluetoothScoEnabled(context, true)
    setMicrophoneActive(true)
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    DictationManager.start(
      context,
      object : DictationCallback {
        override fun onPartial(text: String) {
          val spoken = text.trim()
          if (spoken.isBlank()) return
          val now = System.currentTimeMillis()
          if (spoken == lastPartialText || now - lastPartialAt < PARTIAL_MIN_INTERVAL_MS) return
          lastPartialAt = now
          lastPartialText = spoken
          postVoice(sessionID, "partial", spoken)
        }

        override fun onFinal(text: String) {
          val spoken = text.trim()
          activeSession = null
          setMicrophoneActive(false)
          if (spoken.isBlank()) {
            postVoice(sessionID, "abort", "empty transcript")
          } else {
            postVoice(sessionID, "commit", spoken)
          }
        }

        override fun onCancelled() {
          stopCapture(sendAbort = true, reason = "capture cancelled")
        }

        override fun onError(message: String) {
          stopCapture(sendAbort = true, reason = message.ifBlank { "capture error" })
        }
      },
      useBluetoothSco = true,
    )
    Log.i(TAG, "voice capture started session=$sessionID base=$baseUrl")
  }

  private fun stopCapture(sendAbort: Boolean, reason: String) {
    val sessionID = activeSession
    activeSession = null
    if (DictationManager.isActive()) {
      DictationManager.stop(commitPartial = false, notify = false)
    }
    setMicrophoneActive(false)
    if (sendAbort && sessionID != null) {
      postVoice(sessionID, "abort", reason)
    }
  }

  private fun postVoice(sessionID: String, action: String, text: String) {
    scope.launch(Dispatchers.IO) {
      try {
        postVoiceBlocking(sessionID, action, text)
      } catch (e: Exception) {
        Log.w(TAG, "voice $action failed: ${e.message}")
      }
    }
  }

  private fun postVoiceBlocking(sessionID: String, action: String, text: String) {
    val body = JSONObject()
      .put("text", text)
      .toString()
      .toRequestBody(JSON)
    val url = "$baseUrl/api/voice/sessions/${encodePath(sessionID)}/$action"
    val req = Request.Builder().url(url).post(body).build()
    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("HTTP ${resp.code}")
    }
  }

  private fun fetchCommands(after: Long, waitMs: Int): List<Command> {
    val req = Request.Builder()
      .url("$baseUrl/api/voice/commands?after=$after&wait_ms=$waitMs")
      .build()
    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("HTTP ${resp.code}")
      val body = resp.body?.string().orEmpty()
      val arr = JSONObject(body).optJSONArray("commands") ?: return emptyList()
      val out = ArrayList<Command>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        out += Command(
          id = obj.optLong("id"),
          sessionID = obj.optString("session_id"),
          type = obj.optString("type"),
        )
      }
      return out
    }
  }

  private fun saveCursor(id: Long) {
    prefs.edit().putLong(cursorKey(), id).apply()
  }

  private fun cursorKey() = "cursor_${baseUrl.hashCode()}"
  private fun primedKey() = "primed_${baseUrl.hashCode()}"

  private data class Command(
    val id: Long,
    val sessionID: String,
    val type: String,
  )

  companion object {
    private const val TAG = "AgentSessionVoice"
    private const val PREFS = "agent-session-voice"
    private const val PARTIAL_MIN_INTERVAL_MS = 450L
    private val JSON = "application/json".toMediaType()

    fun baseUrlFromRelayUrl(url: String): String? {
      var u = url.trim()
      if (u.isBlank() || u.contains("example.com")) return null
      u = when {
        u.startsWith("wss://") -> "https://" + u.removePrefix("wss://")
        u.startsWith("ws://") -> "http://" + u.removePrefix("ws://")
        else -> u
      }
      u = u.substringBefore("/ambient-link")
      u = u.substringBefore("/face-chat")
      if (!u.startsWith("http://") && !u.startsWith("https://")) return null
      return u.trimEnd('/')
    }

    private fun encodePath(raw: String): String =
      URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")
  }
}
