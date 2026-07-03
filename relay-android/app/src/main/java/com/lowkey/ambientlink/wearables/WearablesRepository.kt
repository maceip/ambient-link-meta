package com.lowkey.ambientlink.wearables

import android.app.Activity
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.sdk.concurrency.coroutines.WearableCoroutineScopes
import com.meta.wearable.sdk.concurrency.coroutines.WearableDispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ambient.wearables"

class WearablesRepository(
  private val applicationContext: Context,
  private val scope: CoroutineScope,
) {
  private val lock = Object()

  private val _registrationState =
    MutableStateFlow(RegistrationState.UNAVAILABLE)
  val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

  private val _devices = MutableStateFlow<Set<DeviceIdentifier>>(emptySet())
  val devices: StateFlow<Set<DeviceIdentifier>> = _devices.asStateFlow()

  private val _devicesMetadata = MutableStateFlow<Map<DeviceIdentifier, Device>>(emptyMap())
  val devicesMetadata: StateFlow<Map<DeviceIdentifier, Device>> = _devicesMetadata.asStateFlow()

  /** One collector per device — Meta DisplayAccess sample uses getOrPut, not "added only". */
  private val metadataJobs = ConcurrentHashMap<DeviceIdentifier, Job>()

  private val monitoringExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e(TAG, "Wearables monitoring failed", throwable)
  }

  private var monitoringStarted = false

  fun startMonitoring() {
    if (monitoringStarted) return
    monitoringStarted = true

    scope.launch(monitoringExceptionHandler) {
      Wearables.registrationState.collect { value ->
        Log.i(TAG, "registrationState=$value")
        _registrationState.value = value
      }
    }
    scope.launch(monitoringExceptionHandler) {
      Wearables.registrationErrorStream.collect { error ->
        Log.e(TAG, "registrationError=$error")
      }
    }
    scope.launch(monitoringExceptionHandler) {
      Wearables.devices.collect { identifiers -> updateDevices(identifiers) }
    }
  }

  /** Pull current SDK snapshots — call on resume so UI matches Meta AI immediately. */
  fun refreshNow() {
    val identifiers = Wearables.devices.value
    Log.i(TAG, "refreshNow devices=${identifiers.size}")
    _devices.value = identifiers
    if (identifiers.isEmpty()) {
      metadataJobs.values.forEach { it.cancel() }
      metadataJobs.clear()
      _devicesMetadata.value = emptyMap()
      return
    }
    for (id in identifiers) {
      Wearables.devicesMetadata[id]?.value?.let { updateMetadata(id, it) }
      ensureMetadataWatch(id)
    }
    val removed = metadataJobs.keys.toSet() - identifiers
    for (id in removed) {
      metadataJobs.remove(id)?.cancel()
    }
    _devicesMetadata.update { it.filterKeys { key -> key in identifiers } }
  }

  private fun updateDevices(identifiers: Set<DeviceIdentifier>) {
    Log.i(TAG, "devices=${identifiers.size}")
    _devices.value = identifiers

    val removed = metadataJobs.keys.toSet() - identifiers
    for (id in removed) {
      metadataJobs.remove(id)?.cancel()
    }
    if (removed.isNotEmpty()) {
      _devicesMetadata.update { it.filterKeys { key -> key !in removed } }
    }

    for (id in identifiers) {
      ensureMetadataWatch(id)
    }
  }

  private fun ensureMetadataWatch(id: DeviceIdentifier) {
    val existing = metadataJobs[id]
    if (existing != null && existing.isActive) return

    metadataJobs[id] = scope.launch(monitoringExceptionHandler) {
      val flow = Wearables.devicesMetadata[id]
      if (flow == null) {
        Log.w(TAG, "no metadata flow for id=$id")
        return@launch
      }
      updateMetadata(id, flow.value)
      flow.collect { device -> updateMetadata(id, device) }
    }
  }

  private fun updateMetadata(id: DeviceIdentifier, device: Device) {
    Log.i(
      TAG,
      "device id=$id type=${device.deviceType.name} display=${device.isDisplayCapable()} " +
        "link=${device.linkState.name} name=${device.name}",
    )
    synchronized(lock) { _devicesMetadata.update { it.toMutableMap().apply { put(id, device) } } }
  }

  fun startRegistration(activity: Activity) {
    if (_registrationState.value == RegistrationState.REGISTERED) {
      Log.d(TAG, "Already registered")
      return
    }
    Wearables.startRegistration(activity)
  }

  companion object {
    @Volatile private var instance: WearablesRepository? = null

    fun getInstance(applicationContext: Context): WearablesRepository {
      return instance
        ?: synchronized(this) {
          instance
            ?: WearablesRepository(
              applicationContext,
              WearableCoroutineScopes.applicationScope(WearableDispatchers.main()),
            ).also { instance = it }
        }
    }
  }
}
