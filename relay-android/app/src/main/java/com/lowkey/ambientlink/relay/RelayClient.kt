package com.lowkey.ambientlink.relay

import android.util.Log
import com.lowkey.ambientlink.hud.AgentYank
import com.lowkey.ambientlink.hud.Awaiting
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
import java.util.UUID

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
    /** mic: "phone" (default) or "glasses" (Bluetooth SCO / HFP). */
    data class DictateActive(val thread: String, val source: String, val mic: String = "phone") : Event()
    data class DictateCommit(val thread: String, val text: String, val source: String) : Event()
    data class DictateAbort(val thread: String, val source: String) : Event()
    data class DictatePartial(val thread: String, val text: String, val source: String) : Event()
    data class DictateEnd(
      val thread: String,
      val text: String,
      val source: String,
      val ok: Boolean,
      val error: String,
    ) : Event()
    data class SessionFocus(val thread: String, val source: String) : Event()
    data class SessionBlur(val thread: String, val source: String) : Event()
    data class CompanionUi(val screen: String, val source: String) : Event()
    /** PROTOCOL v2 message lifecycle: accepted → queued|delivered → landed|failed. */
    data class InputStatus(
      val id: String,
      val sessionId: String,
      val thread: String,
      val status: String,
      val error: String,
    ) : Event()
    data class ThreadStarted(val meta: ThreadMeta) : Event()
    data class ThreadEnded(val thread: String) : Event()
    data class Error(val msg: String) : Event()
  }
  data class ThreadMeta(val id: String, val label: String, val agent: String, val sessionId: String = "")

  private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
  val events: SharedFlow<Event> = _events
  private val labels = mutableMapOf<String, String>()
  private val agents = mutableMapOf<String, String>()
  /** thread id → session_id learned from hello/thread_* frames (v2 addressing). */
  private val sessionsByThread = mutableMapOf<String, String>()
  /** Message IDs this client minted — via the proxy, other clients' statuses
   *  are mirrored to everyone, so only react to our own. */
  private val pendingInputs = object : LinkedHashMap<String, Boolean>() {
    override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > 64
  }
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
              synchronized(sessionsByThread) { sessionsByThread.clear() }
              val arr = obj.optJSONArray("threads")
              val list = mutableListOf<ThreadMeta>()
              if (arr != null) for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val tm = ThreadMeta(
                  t.optString("id"),
                  t.optString("label", t.optString("id")),
                  t.optString("agent", "generic"),
                  t.optString("session_id", ""),
                )
                labels[tm.id] = tm.label
                agents[tm.id] = tm.agent
                rememberSession(tm.id, tm.sessionId)
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
            "thread_busy" -> {
              rememberSession(obj.optString("thread"), obj.optString("session_id", ""))
              _events.tryEmit(Event.ThreadBusy(obj.optString("thread")))
            }
            "thread_started" -> {
              val id = obj.optString("thread")
              val meta = ThreadMeta(
                id,
                obj.optString("label", labels[id] ?: id),
                obj.optString("agent", agents[id] ?: "generic"),
                obj.optString("session_id", ""),
              )
              labels[meta.id] = meta.label
              agents[meta.id] = meta.agent
              rememberSession(meta.id, meta.sessionId)
              _events.tryEmit(Event.ThreadStarted(meta))
            }
            "thread_ended" -> {
              val id = obj.optString("thread")
              labels.remove(id)
              agents.remove(id)
              synchronized(sessionsByThread) { sessionsByThread.remove(id) }
              _events.tryEmit(Event.ThreadEnded(id))
            }
            "input_status" -> {
              val st = Event.InputStatus(
                id = obj.optString("id"),
                sessionId = obj.optString("session_id", ""),
                thread = obj.optString("thread", ""),
                status = obj.optString("status"),
                error = obj.optString("error", ""),
              )
              // The proxy mirrors landed/failed frames to every client — only
              // surface lifecycle statuses for messages this phone minted.
              val mine = synchronized(pendingInputs) { pendingInputs.containsKey(st.id) }
              if (mine) {
                Log.i("RelayClient", "input_status id=${st.id} status=${st.status} err=${st.error}")
                if (st.status == "landed" || st.status == "failed") {
                  synchronized(pendingInputs) { pendingInputs.remove(st.id) }
                }
                _events.tryEmit(st)
              }
            }
            "dictate_active" -> _events.tryEmit(
              Event.DictateActive(
                obj.optString("thread"),
                obj.optString("source", ""),
                normalizeMic(obj.optString("mic", "phone")),
              ),
            )
            "dictate_begin" -> {
              if (obj.optString("source") == "web") {
                _events.tryEmit(
                  Event.DictateActive(
                    obj.optString("thread"),
                    "web",
                    normalizeMic(obj.optString("mic", "phone")),
                  ),
                )
              }
            }
            "dictate_commit" -> {
              if (obj.optString("source") == "web") {
                _events.tryEmit(
                  Event.DictateCommit(
                    obj.optString("thread"),
                    obj.optString("text", ""),
                    "web",
                  ),
                )
              }
            }
            "dictate_abort" -> {
              if (obj.optString("source") == "web") {
                _events.tryEmit(Event.DictateAbort(obj.optString("thread"), "web"))
              }
            }
            "dictate_partial" -> {
              val text = obj.optString("text", "")
              if (text.isNotBlank()) {
                _events.tryEmit(
                  Event.DictatePartial(obj.optString("thread"), text, obj.optString("source", "")),
                )
              }
            }
            // v2: dictate_end carries the real outcome — ok=false means the
            // committed text never reached the agent (render failure, not sent).
            "dictate_end" -> _events.tryEmit(
              Event.DictateEnd(
                obj.optString("thread"),
                obj.optString("text", ""),
                obj.optString("source", ""),
                obj.optBoolean("ok", true),
                obj.optString("error", ""),
              ),
            )
            "session_focus" -> _events.tryEmit(
              Event.SessionFocus(obj.optString("thread"), obj.optString("source", "")),
            )
            "session_blur" -> _events.tryEmit(
              Event.SessionBlur(obj.optString("thread"), obj.optString("source", "")),
            )
            "companion_ui" -> _events.tryEmit(
              Event.CompanionUi(obj.optString("screen", "idle"), obj.optString("source", "")),
            )
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

  private fun rememberSession(thread: String, sessionId: String) {
    if (thread.isBlank() || sessionId.isBlank()) return
    synchronized(sessionsByThread) { sessionsByThread[thread] = sessionId }
  }

  private fun sessionFor(thread: String): String =
    synchronized(sessionsByThread) { sessionsByThread[thread] ?: "" }

  private fun parseYank(obj: JSONObject): AgentYank {
    val id = obj.optString("thread")
    rememberSession(id, obj.optString("session_id", ""))
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

  fun sendHudYank(thread: String) {
    ws?.send(JSONObject().put("type", "hud_yank").put("thread", thread).toString())
  }

  /**
   * PROTOCOL v2 input: session_id-first addressing (thread as fallback), a
   * client-minted message ID echoed back on every input_status frame, and no
   * enter flag — delivery always submits. Returns the message ID so callers
   * can correlate lifecycle statuses.
   */
  fun sendInput(thread: String, text: String): String {
    val messageID = UUID.randomUUID().toString()
    synchronized(pendingInputs) { pendingInputs[messageID] = true }
    val o = JSONObject()
      .put("type", "input")
      .put("text", text)
      .put("client_id", messageID)
    sessionFor(thread).takeIf { it.isNotBlank() }?.let { o.put("session_id", it) }
    if (thread.isNotBlank()) o.put("thread", thread)
    ws?.send(o.toString())
    return messageID
  }
  fun sendSpecial(thread: String, key: String) {
    val o = JSONObject().put("type", "special").put("key", key)
    sessionFor(thread).takeIf { it.isNotBlank() }?.let { o.put("session_id", it) }
    if (thread.isNotBlank()) o.put("thread", thread)
    ws?.send(o.toString())
  }

  private fun normalizeMic(raw: String): String =
    if (raw.equals("glasses", ignoreCase = true)) "glasses" else "phone"

  /** Quick replies + snooze window — synced to web companion. */
  fun sendCompanionConfig(
    quickReplies: List<String>,
    snoozeUntilMs: Long,
    showContinue: Boolean,
    showDictate: Boolean,
    defaultAgent: String,
    dictateMic: String = "phone",
    wakeHint: WakeHint? = null,
  ) {
    val arr = org.json.JSONArray()
    quickReplies.forEach { arr.put(it) }
    val mic = if (dictateMic.equals("glasses", ignoreCase = true)) "glasses" else "phone"
    val o = JSONObject()
      .put("type", "companion_config")
      .put("quick_replies", arr)
      .put("snooze_until", snoozeUntilMs)
      .put("show_continue", showContinue)
      .put("show_dictate", showDictate)
      .put("default_agent", defaultAgent)
      .put("dictate_mic", mic)
      .put("source", "phone")
    if (wakeHint != null) o.put("wake_hint", wakeHint.toJson())
    ws?.send(o.toString())
  }

  /**
   * Soft handoff for the glasses web app: after a DAT wake, opening the
   * launcher lands on this thread if the hint is still fresh (~2 min).
   * Does not open the web app itself (unsupported from DAT).
   */
  fun sendWakeHint(thread: String, reason: String, sessionId: String = "") {
    if (thread.isBlank()) return
    val hint = WakeHint(
      thread = thread,
      at = System.currentTimeMillis(),
      reason = reason.ifBlank { "done" },
      sessionId = sessionId,
    )
    ws?.send(
      JSONObject()
        .put("type", "companion_config")
        .put("wake_hint", hint.toJson())
        .put("source", "phone")
        .toString(),
    )
    Log.i("RelayClient", "wake_hint thread=$thread reason=${hint.reason}")
  }

  data class WakeHint(
    val thread: String,
    val at: Long,
    val reason: String,
    val sessionId: String = "",
  ) {
    fun toJson(): JSONObject {
      val o = JSONObject()
        .put("thread", thread)
        .put("at", at)
        .put("reason", reason)
      if (sessionId.isNotBlank()) o.put("session_id", sessionId)
      return o
    }
  }
}
