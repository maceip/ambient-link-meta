package com.lowkey.facechat.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// WS client for the relay. Implements only the subset of the protocol the daemon needs
// (see phone-shared/PROTOCOL.md): on connect → send `subscribe`; on `thread_idle`/`thread_busy`
// → emit to events flow; outbound `input`/`special` → send JSON frames.
//
// Reconnects with exponential backoff (500ms → 10s cap), same as the relay's other clients.
class RelayClient(private val url: String) {
  private val client = OkHttpClient.Builder().build()
  private var ws: WebSocket? = null
  private var backoff = 500L
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loopJob: Job? = null

  sealed class Event {
    data object Connected : Event()
    data object Disconnected : Event()
    data class Hello(val threads: List<ThreadMeta>) : Event()
    data class ThreadIdle(val thread: String, val label: String, val lastAssistant: String) : Event()
    data class ThreadBusy(val thread: String) : Event()
    data class Error(val msg: String) : Event()
  }
  data class ThreadMeta(val id: String, val label: String, val agent: String)

  private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
  val events: SharedFlow<Event> = _events
  private val labels = mutableMapOf<String, String>()

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
    val listener = object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, resp: Response) {
        backoff = 500
        _events.tryEmit(Event.Connected)
        ws.send(JSONObject().put("type", "subscribe").put("since", JSONObject()).toString())
      }
      override fun onMessage(ws: WebSocket, text: String) {
        try {
          val obj = JSONObject(text)
          when (obj.optString("type")) {
            "hello" -> {
              labels.clear()
              val arr = obj.optJSONArray("threads")
              val list = mutableListOf<ThreadMeta>()
              if (arr != null) for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val tm = ThreadMeta(t.optString("id"), t.optString("label", t.optString("id")), t.optString("agent", "generic"))
                labels[tm.id] = tm.label
                list += tm
              }
              _events.tryEmit(Event.Hello(list))
            }
            "thread_idle" -> {
              val id = obj.optString("thread")
              _events.tryEmit(Event.ThreadIdle(
                thread = id,
                label  = labels[id] ?: id,
                lastAssistant = obj.optString("lastAssistant", ""),
              ))
            }
            "thread_busy" -> _events.tryEmit(Event.ThreadBusy(obj.optString("thread")))
            // ignore append/snapshot — the daemon doesn't need streaming text
          }
        } catch (e: Exception) { Log.w("RelayClient", "parse: ${e.message}") }
      }
      override fun onClosed(ws: WebSocket, code: Int, reason: String) { _events.tryEmit(Event.Disconnected) }
      override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
        _events.tryEmit(Event.Error(t.message ?: "ws failure"))
        _events.tryEmit(Event.Disconnected)
      }
    }
    ws = client.newWebSocket(req, listener)
    // Hold connection until it closes; the surrounding loop's `delay(backoff)` then waits and retries.
    // We use a coarse-grained suspend (5min) since onClosed/onFailure already trigger the disconnect event.
    delay(300_000L)
  }

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
