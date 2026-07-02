package com.lowkey.ambientlink.hud

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ambient.session"

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
  private var onDisplayFailed: (() -> Unit)? = null

  val activeDisplay: Display? get() = display

  fun prepareDisplay(
    deviceId: DeviceIdentifier,
    onReady: (Display) -> Unit,
    onFailed: (() -> Unit)? = null,
  ) {
    onDisplayReady = onReady
    onDisplayFailed = onFailed
    val currentDisplay = synchronized(lock) { display }
    val currentSession = synchronized(lock) { session }

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
      currentSession != null -> {
        Log.i(TAG, "session active (display asleep), re-attaching for $deviceId")
        synchronized(lock) { pendingDeviceId = deviceId }
        attachDisplay()
      }
      else -> {
        synchronized(lock) { pendingDeviceId = deviceId }
        startSession(deviceId)
      }
    }
  }

  private fun startSession(deviceId: DeviceIdentifier, attempt: Int = 0) {
    scope.launch {
      releaseSessionFully()
      Log.i(TAG, "createSession id=$deviceId attempt=$attempt")
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
          val alreadyExists = error.description.contains("already exists", ignoreCase = true)
          if (alreadyExists && attempt < 2) {
            releaseSessionFully()
            delay(500L * (attempt + 1))
            synchronized(lock) { pendingDeviceId = deviceId }
            startSession(deviceId, attempt + 1)
            return@fold
          }
          synchronized(lock) { pendingDeviceId = null }
          onDisplayFailed?.invoke()
          onDisplayFailed = null
        },
      )
    }
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
    if (error.description.contains("update", ignoreCase = true)) {
      Log.w(TAG, "glasses DAT app update required — open Meta AI App Connections")
    }
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

  /**
   * Dismiss the waveguide without killing the DeviceSession — sends the SDK's
   * DisplayStopRequest via [removeDisplay]. Calling [DeviceSession.stop] alone
   * often leaves Meta's home shell lit until the glasses' own screen timeout.
   */
  suspend fun sleepDisplay() {
    val d = synchronized(lock) { display }
    val s = synchronized(lock) { session }
    if (d == null && s == null) return
    Log.i(TAG, "sleepDisplay — removeDisplay (keep session=${s != null})")
    cleanupDisplay()
    if (d != null) {
      try { d.stop() } catch (e: Throwable) {
        Log.w(TAG, "display.stop: ${e.message}")
      }
    }
    if (s != null) {
      val result = withTimeoutOrNull(4_000) {
        s.removeDisplay()
      }
      if (result == null) {
        Log.w(TAG, "removeDisplay timed out")
      } else {
        result.fold(
          onSuccess = { Log.i(TAG, "removeDisplay ok") },
          onFailure = { err, _ -> Log.w(TAG, "removeDisplay fail: ${err.description}") },
        )
      }
    }
  }

  fun stop() {
    onDisplayReady = null
    onDisplayFailed = null
    synchronized(lock) { pendingDeviceId = null }
    scope.launch { releaseSessionFully() }
  }

  private suspend fun releaseSessionFully() {
    sleepDisplay()
    releaseSession()
  }

  private fun releaseSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    cleanupDisplay()
    val s = synchronized(lock) { session.also { session = null } }
    try { s?.stop() } catch (_: Throwable) {}
  }
}

/** One glasses DAT session for the whole app — Meta SDK allows only one per device. */
object GlassesDisplay {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  val session: DatDisplaySession = DatDisplaySession(scope)
}
