package com.lowkey.ambientlink.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** LAN discovery for ambient-link-host via mDNS (_ambientlink._tcp). */
object RelayDiscovery {
  private const val TAG = "RelayDiscovery"
  private const val SERVICE_TYPE = "_ambientlink._tcp."

  suspend fun discover(ctx: Context, timeoutMs: Long = 6_000): String? {
    val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
    return withTimeoutOrNull(timeoutMs) {
      suspendCancellableCoroutine { cont ->
        var done = false
        lateinit var discoveryListener: NsdManager.DiscoveryListener

        fun finish(url: String?) {
          if (done) return
          done = true
          try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
          cont.resume(url)
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
          override fun onStartDiscoveryFailed(type: String, code: Int) {
            Log.w(TAG, "discovery start failed $code")
            finish(null)
          }
          override fun onStopDiscoveryFailed(type: String, code: Int) {}
          override fun onDiscoveryStarted(type: String) {
            Log.i(TAG, "discovering $type")
          }
          override fun onDiscoveryStopped(type: String) {}
          override fun onServiceFound(service: NsdServiceInfo) {
            if (!service.serviceType.contains("ambientlink")) return
            nsd.resolveService(service, object : NsdManager.ResolveListener {
              override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "resolve failed $code")
              }
              override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val port = info.port
                val path = info.attributes?.get("path")?.let { String(it, Charsets.UTF_8) }
                  ?: "/ambient-link/ws"
                val url = "ws://$host:$port$path"
                Log.i(TAG, "resolved $url")
                finish(url)
              }
            })
          }
          override fun onServiceLost(service: NsdServiceInfo) {}
        }

        cont.invokeOnCancellation {
          try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
      }
    }
  }
}
