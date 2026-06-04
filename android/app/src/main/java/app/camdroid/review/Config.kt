package app.camdroid.review

import app.camdroid.review.data.BridgeAddress

/**
 * Static configuration. The bridge address is normally discovered at runtime
 * via mDNS/NSD or by gateway probe (see BridgeDiscovery); the values here
 * are the fallback used when discovery fails — for example, if avahi isn't
 * running on the bridge or the network blocks multicast.
 *
 * Edit FALLBACK_HOST to your bridge's IP if you find yourself relying on the
 * fallback often. The user can also pin an explicit address from the in-app
 * settings screen ("Set bridge address…"), which takes priority over both
 * discovery and this fallback.
 */
object Config {
    const val FALLBACK_HOST = "192.168.0.171"
    const val FALLBACK_PORT = 8080
    const val API_PREFIX = "/api/v0"
    const val DISCOVERY_TIMEOUT_MS = 8000L

    val FALLBACK_ADDRESS = BridgeAddress(FALLBACK_HOST, FALLBACK_PORT)

    /**
     * Current bridge base URL. Initialized to the fallback so composable URL
     * construction (e.g. thumbnail/preview model strings) doesn't crash before
     * discovery completes; updated by [setActiveAddress] once the ViewModel
     * has resolved the real address. Single-instance app, single-process —
     * mutable global is fine here and avoids plumbing the address through
     * every Composable that builds an image URL.
     */
    @Volatile
    var BASE_URL: String = "http://$FALLBACK_HOST:$FALLBACK_PORT"
        private set

    fun setActiveAddress(address: BridgeAddress) {
        BASE_URL = address.baseUrl
    }
}
