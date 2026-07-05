package com.lowkey.ambientlink.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** LAN discovery for ambient-link-host via mDNS (_ambientlink._tcp). */
object RelayDiscovery {
  private const val TAG = "RelayDiscovery"
  private const val SERVICE_TYPE = "_ambientlink._tcp."
  private const val DEFAULT_PORT = 5181
  private const val DEFAULT_PATH = "/ambient-link/ws"

  /** mDNS, then cached LAN URL, then last-known Mac IP on :5181. */
  suspend fun discoverOrDirect(ctx: Context, timeoutMs: Long = 10_000): String? =
    withContext(Dispatchers.IO) {
      discover(ctx, timeoutMs)?.let { return@withContext it }
      RelayLanStore.lastLanWs(ctx)?.let { cached ->
        if (RelayConfig.isReachableWs(cached)) {
          Log.i(TAG, "direct: cached LAN $cached")
          return@withContext cached
        }
      }
      RelayLanStore.lastLanIp(ctx)?.let { ip ->
        val url = defaultWsUrl(ip)
        if (RelayConfig.isReachableWs(url)) {
          Log.i(TAG, "direct: probed $url")
          RelayLanStore.rememberLanWs(ctx, url)
          return@withContext url
        }
      }
      null
    }

  fun defaultWsUrl(host: String, port: Int = DEFAULT_PORT): String {
    val h = host.trim().removePrefix("ws://").removePrefix("wss://")
      .substringBefore('/')
      .substringBefore(':')
    return "ws://$h:$port$DEFAULT_PATH"
  }

  suspend fun discover(ctx: Context, timeoutMs: Long = 10_000): String? {
    val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
    val multicast = acquireMulticastLock(ctx)
    try {
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
                  Log.w(TAG, "resolve failed $code for ${info.serviceName}")
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                  val host = info.host?.hostAddress ?: return
                  val port = if (info.port > 0) info.port else DEFAULT_PORT
                  val path = info.attributes?.get("path")?.let { String(it, Charsets.UTF_8) }
                    ?: DEFAULT_PATH
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
    } finally {
      releaseMulticastLock(multicast)
    }
  }

  private suspend fun acquireMulticastLock(ctx: Context): WifiManager.MulticastLock? =
    withContext(Dispatchers.IO) {
      val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return@withContext null
      try {
        wifi.createMulticastLock("ambient-link-mdns").apply {
          setReferenceCounted(false)
          acquire()
        }
      } catch (e: Exception) {
        Log.w(TAG, "multicast lock failed: ${e.message}")
        null
      }
    }

  private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
    try {
      if (lock?.isHeld == true) lock.release()
    } catch (_: Exception) {}
  }
}
