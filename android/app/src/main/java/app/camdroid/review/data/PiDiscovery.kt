package app.camdroid.review.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import app.camdroid.review.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Resolves the Pi's address on the local network without the user typing an
 * IP. Two paths, tried in priority order:
 *
 *   1. Gateway-IP probe — when the tablet is on the Pi's own AP, the Pi *is*
 *      the gateway. Read its IP from WifiManager.dhcpInfo and probe the
 *      v0/status endpoint. Zero round-trips beyond a single HTTP HEAD.
 *      (Will fall through quickly when Pi is not the gateway, e.g. during
 *      development on home Wi-Fi.)
 *
 *   2. mDNS / NSD discovery — Avahi on the Pi advertises _camdroid._tcp via
 *      the service file at pi/avahi/camdroid.service. Android's NsdManager
 *      enumerates and resolves it.
 *
 * Returns the resolved address, or null if both fail within the timeout.
 */
class PiDiscovery(private val context: Context) {

    private val tag = "PiDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun findPi(): PiAddress? = withContext(Dispatchers.IO) {
        // Path 1: gateway probe (cheap, instant when applicable).
        gatewayPiAddress()?.let { gw ->
            if (probe(gw)) {
                Log.i(tag, "discovered Pi via gateway: $gw")
                return@withContext gw
            }
        }
        // Path 2: NSD discovery with timeout.
        withTimeoutOrNull(Config.DISCOVERY_TIMEOUT_MS) { discoverViaNsd() }?.also {
            Log.i(tag, "discovered Pi via NSD: $it")
        }
    }

    private fun gatewayPiAddress(): PiAddress? {
        @Suppress("DEPRECATION")
        val info = wifiManager.dhcpInfo ?: return null
        val ip = info.gateway
        if (ip == 0) return null
        val host = String.format(
            java.util.Locale.US,
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        )
        return PiAddress(host, Config.FALLBACK_PORT)
    }

    private suspend fun probe(addr: PiAddress): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("${addr.baseUrl}${Config.API_PREFIX}/status?limit=1")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.connect()
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun discoverViaNsd(): PiAddress? = suspendCancellableCoroutine { cont ->
        // Multicast lock — some Wi-Fi chipsets drop multicast packets unless
        // the app explicitly asks for them. Always safe to acquire.
        val multicastLock = wifiManager.createMulticastLock("camdroid-nsd").also {
            it.setReferenceCounted(true)
            it.acquire()
        }

        var resolveInProgress = false
        var resolved = false

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(tag, "discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(tag, "discovery stopped: $serviceType")
                safeRelease(multicastLock)
                if (cont.isActive && !resolved) cont.resume(null)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "discovery start failed: $errorCode")
                safeRelease(multicastLock)
                if (cont.isActive) cont.resume(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "discovery stop failed: $errorCode")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (resolved || resolveInProgress) return
                if (!service.serviceType.contains(SERVICE_TYPE_BASE)) return
                resolveInProgress = true

                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(tag, "resolve failed: $errorCode")
                        resolveInProgress = false
                    }

                    override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                        val host = resolvedService.host?.hostAddress
                        val port = resolvedService.port
                        if (host == null || port <= 0) {
                            resolveInProgress = false
                            return
                        }
                        resolved = true
                        try {
                            nsdManager.stopServiceDiscovery(listener)
                        } catch (_: Exception) { }
                        if (cont.isActive) cont.resume(PiAddress(host, port))
                    }
                }
                nsdManager.resolveService(service, resolveListener)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(tag, "service lost: ${service.serviceName}")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(tag, "discoverServices threw: ${e.message}")
            safeRelease(multicastLock)
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation {
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) { }
            safeRelease(multicastLock)
        }
    }

    private fun safeRelease(lock: WifiManager.MulticastLock) {
        try { if (lock.isHeld) lock.release() } catch (_: Exception) { }
    }

    companion object {
        // Trailing dot is required by some NSD impls.
        private const val SERVICE_TYPE = "_camdroid._tcp."
        private const val SERVICE_TYPE_BASE = "_camdroid._tcp"
    }
}

data class PiAddress(val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
    val wsUrl: String get() = "ws://$host:$port${Config.API_PREFIX}/events"
}
