package com.lowkey.facechat.hud

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "fc.session"

// Session lifecycle copied from Meta DisplayAccess DisplayViewModel:
// prepareDisplayConnection → createSession (CONNECTED device) → start → addDisplay → STARTED.
class DatDisplaySession(private val scope: CoroutineScope) {
  private val lock = Any()

  @Volatile private var session: DeviceSession? = null
  @Volatile private var display: Display? = null
  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var displayStateJob: Job? = null
  private var pendingDeviceId: DeviceIdentifier? = null
  private var onDisplayReady: ((Display) -> Unit)? = null

  val activeDisplay: Display? get() = display

  fun prepareDisplay(deviceId: DeviceIdentifier, onReady: (Display) -> Unit) {
    onDisplayReady = onReady
    val currentDisplay = synchronized(lock) { display }
    val currentSession = synchronized(lock) { session }
    val selectedId = synchronized(lock) { pendingDeviceId }

    when {
      currentDisplay != null && currentSession != null -> {
        Log.i(TAG, "display already attached for $deviceId")
        val d = currentDisplay
        scope.launch {
          if (d.state.value == DisplayState.STARTED) onReady(d)
          else {
            d.state.first { it == DisplayState.STARTED }
            onReady(d)
          }
        }
      }
      currentSession != null && selectedId == deviceId -> {
        Log.i(TAG, "session active, attaching display for $deviceId")
        attachDisplay()
      }
      else -> {
        synchronized(lock) { pendingDeviceId = deviceId }
        startSession(deviceId)
      }
    }
  }

  private fun startSession(deviceId: DeviceIdentifier) {
    Log.i(TAG, "createSession id=$deviceId")
    Wearables.createSession(SpecificDeviceSelector(deviceId)).fold(
      onSuccess = { newSession ->
        synchronized(lock) { session = newSession }

        sessionStateJob?.cancel()
        sessionStateJob = scope.launch {
          newSession.state.collect { state ->
            Log.i(TAG, "session.state -> $state")
            when (state) {
              DeviceSessionState.STARTED -> {
                if (consumePendingDeviceId(deviceId)) attachDisplay()
              }
              DeviceSessionState.STOPPED -> {
                synchronized(lock) { pendingDeviceId = null }
                cleanupDisplay()
              }
              else -> {}
            }
          }
        }

        sessionErrorJob?.cancel()
        sessionErrorJob = scope.launch {
          newSession.errors.collect { error -> handleSessionError(error) }
        }

        newSession.start()
      },
      onFailure = { error, _ ->
        Log.e(TAG, "createSession FAIL: ${error.description}")
        synchronized(lock) { pendingDeviceId = null }
      },
    )
  }

  private fun attachDisplay() {
    val currentSession = synchronized(lock) { session }
      ?: run {
        Log.w(TAG, "attachDisplay: no session")
        return
      }

    currentSession.addDisplay().fold(
      onSuccess = { newDisplay ->
        synchronized(lock) { display = newDisplay }
        Log.i(TAG, "addDisplay SUCCESS")

        displayStateJob?.cancel()
        displayStateJob = scope.launch {
          newDisplay.state.collect { state ->
            Log.i(TAG, "display.state -> $state")
            if (state == DisplayState.STARTED) {
              onDisplayReady?.invoke(newDisplay)
            }
          }
        }
      },
      onFailure = { error, _ -> Log.e(TAG, "addDisplay FAIL: ${error.description}") },
    )
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "session error: ${error.description}")
    synchronized(lock) { pendingDeviceId = null }
    stop()
  }

  private fun consumePendingDeviceId(deviceId: DeviceIdentifier): Boolean =
    synchronized(lock) {
      if (pendingDeviceId == deviceId && display == null) {
        pendingDeviceId = null
        true
      } else {
        false
      }
    }

  private fun cleanupDisplay() {
    displayStateJob?.cancel()
    displayStateJob = null
    synchronized(lock) { display = null }
  }

  fun stop() {
    onDisplayReady = null
    synchronized(lock) { pendingDeviceId = null }
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    cleanupDisplay()
    val s = synchronized(lock) { session.also { session = null } }
    try { s?.stop() } catch (_: Throwable) {}
  }
}
