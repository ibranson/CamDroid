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
}
