package com.andrewf13.tuned.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class FoundTv(val name: String, val host: String, val port: Int)

/** Finds TVs running Tuned on this Wi-Fi (they announce "_tuned._tcp" over mDNS). */
class TvFinder(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun scan(): Flow<List<FoundTv>> = callbackFlow {
        val found = LinkedHashMap<String, FoundTv>()
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tuned-remote").apply { setReferenceCounted(false); runCatching { acquire() } }

        fun next() {
            if (!resolving.compareAndSet(false, true)) return
            val info = queue.poll() ?: run { resolving.set(false); return }
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { resolving.set(false); next() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull()?.hostAddress else @Suppress("DEPRECATION") i.host?.hostAddress
                    if (host != null) {
                        found[i.serviceName] = FoundTv(i.serviceName, host, i.port)
                        trySend(found.values.toList())
                    }
                    resolving.set(false); next()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { queue.add(info); next() }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName); trySend(found.values.toList())
            }
        }
        trySend(emptyList())
        // If discovery isn't available, the manual address box still works.
        val started = runCatching { nsd.discoverServices("_tuned._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        awaitClose {
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }
}
