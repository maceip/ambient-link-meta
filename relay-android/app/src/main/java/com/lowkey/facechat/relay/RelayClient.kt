package com.lowkey.facechat.relay

import android.util.Log
import com.lowkey.facechat.hud.AgentYank
import com.lowkey.facechat.hud.Awaiting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class RelayClient(val url: String) {
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .build()
  private var ws: WebSocket? = null
  private var backoff = 500L
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loopJob: Job? = null

  sealed class Event {
    data object Connected : Event()
    data object Disconnected : Event()
    data class Hello(val threads: List<ThreadMeta>) : Event()
    data class ThreadIdle(val yank: AgentYank) : Event()
    data class HudYank(val yank: AgentYank) : Event()
    data class ThreadBusy(val thread: String) : Event()
    data class Error(val msg: String) : Event()
  }
  data class ThreadMeta(val id: String, val label: String, val agent: String)

  private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
  val events: SharedFlow<Event> = _events
  private val labels = mutableMapOf<String, String>()
  private val agents = mutableMapOf<String, String>()
  @Volatile private var subscribed = false

  fun start() {
    if (loopJob?.isActive == true) return
    loopJob = scope.launch {
      while (true) {
        connectOnce()
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(10_000)
      }
    }
  }
  fun stop() {
    loopJob?.cancel()
    ws?.cancel()
    ws = null
  }

  private suspend fun connectOnce() {
    val req = Request.Builder().url(url).build()
    val done = CompletableDeferred<Unit>()
    val listener = object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, resp: Response) {
        backoff = 500
        Log.i("RelayClient", "connected $url")
        _events.tryEmit(Event.Connected)
        // subscribe is sent after hello (with journal cursor) to avoid replay storms
      }
      override fun onMessage(ws: WebSocket, text: String) {
        try {
          val obj = JSONObject(text)
          when (obj.optString("type")) {
            "hello" -> {
              labels.clear()
              agents.clear()
              val arr = obj.optJSONArray("threads")
              val list = mutableListOf<ThreadMeta>()
              if (arr != null) for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val tm = ThreadMeta(
                  t.optString("id"),
                  t.optString("label", t.optString("id")),
                  t.optString("agent", "generic"),
                )
                labels[tm.id] = tm.label
                agents[tm.id] = tm.agent
                list += tm
              }
              // Subscribe at journal head — skip replaying stale thread_idle cards.
              val since = obj.optJSONObject("cursor") ?: JSONObject()
              ws.send(JSONObject().put("type", "subscribe").put("since", since).toString())
              subscribed = true
              _events.tryEmit(Event.Hello(list))
            }
            "thread_idle" -> {
              if (!subscribed) return
              val y = parseYank(obj)
              Log.i("RelayClient", "thread_idle thread=${y.thread} awaiting=${y.awaiting}")
              _events.tryEmit(Event.ThreadIdle(y))
            }
            "hud_yank" -> {
              val y = parseYank(obj)
              Log.i("RelayClient", "hud_yank thread=${y.thread} awaiting=${y.awaiting}")
              _events.tryEmit(Event.HudYank(y))
            }
            "thread_busy" -> _events.tryEmit(Event.ThreadBusy(obj.optString("thread")))
          }
        } catch (e: Exception) { Log.w("RelayClient", "parse: ${e.message}") }
      }
      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        Log.i("RelayClient", "closed code=$code reason=$reason")
        _events.tryEmit(Event.Disconnected)
        done.complete(Unit)
      }
      override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
        Log.w("RelayClient", "failure: ${t.message}")
        _events.tryEmit(Event.Error(t.message ?: "ws failure"))
        _events.tryEmit(Event.Disconnected)
        done.complete(Unit)
      }
    }
    Log.i("RelayClient", "connecting $url")
    ws = client.newWebSocket(req, listener)
    done.await()
  }

  private fun parseYank(obj: JSONObject): AgentYank {
    val id = obj.optString("thread")
    val awaiting = when (obj.optString("awaiting")) {
      "permission" -> Awaiting.PERMISSION
      "question"   -> Awaiting.QUESTION
      "done"       -> Awaiting.DONE
      else         -> Awaiting.DONE
    }
    val perm = obj.optString("permissionPrompt", "").trim().ifBlank { null }
    return AgentYank(
      thread = id,
      label = obj.optString("label", labels[id] ?: id),
      agent = obj.optString("agent", agents[id] ?: "generic"),
      lastAssistant = obj.optString("lastAssistant", ""),
      lastUserInput = obj.optString("lastUserInput", ""),
      awaiting = awaiting,
      permissionPrompt = perm,
    )
  }

  fun sendDictateBegin(thread: String) = sendDictate("dictate_begin", thread, null)
  fun sendDictatePartial(thread: String, text: String) = sendDictate("dictate_partial", thread, text)
  fun sendDictateCommit(thread: String, text: String) = sendDictate("dictate_commit", thread, text)
  fun sendDictateAbort(thread: String) = sendDictate("dictate_abort", thread, null)

  private fun sendDictate(type: String, thread: String, text: String?) {
    val o = JSONObject().put("type", type).put("thread", thread).put("source", "phone")
    if (text != null) o.put("text", text)
    ws?.send(o.toString())
  }

  fun companionComposeUrl(thread: String): String = CompanionUrls.composeUrl(url, thread)

  fun sendInput(thread: String, text: String, enter: Boolean = true) {
    ws?.send(JSONObject()
      .put("type", "input").put("thread", thread)
      .put("text", text).put("enter", enter).toString())
  }
  fun sendSpecial(thread: String, key: String) {
    ws?.send(JSONObject()
      .put("type", "special").put("thread", thread).put("key", key).toString())
  }
}
