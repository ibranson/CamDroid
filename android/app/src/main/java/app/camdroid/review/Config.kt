package app.camdroid.review

import app.camdroid.review.data.PiAddress

/**
 * Static configuration. The Pi address is normally discovered at runtime
 * via mDNS/NSD (see PiDiscovery); the values here are the fallback used
 * when discovery fails — for example, if avahi isn't running on the Pi
 * or the network blocks multicast.
 *
 * Edit FALLBACK_HOST to your Pi's IP if you find yourself relying on the
 * fallback often.
 */
object Config {
    const val FALLBACK_HOST = "192.168.0.171"
    const val FALLBACK_PORT = 8080
    const val API_PREFIX = "/api/v0"
    const val DISCOVERY_TIMEOUT_MS = 8000L

    val FALLBACK_ADDRESS = PiAddress(FALLBACK_HOST, FALLBACK_PORT)

    /**
     * Current Pi base URL. Initialized to the fallback so composable URL
     * construction (e.g. thumbnail/preview model strings) doesn't crash before
     * discovery completes; updated by [setActiveAddress] once the ViewModel
     * has resolved the real address. Single-instance app, single-process —
     * mutable global is fine here and avoids plumbing the address through
     * every Composable that builds an image URL.
     */
    @Volatile
    var BASE_URL: String = "http://$FALLBACK_HOST:$FALLBACK_PORT"
        private set

    fun setActiveAddress(address: PiAddress) {
        BASE_URL = address.baseUrl
    }
}
