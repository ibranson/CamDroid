package app.camdroid.review.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire-shape models for the Pi's v0 API. Keep these in sync with
 * docs/api-contract-v0.md.
 *
 * The WS event stream sends JSON objects with a "type" field. We parse the type
 * out, then deserialize into the matching ServerEvent variant. Anything we
 * don't recognize falls back to ServerEvent.Unknown so a new server-side event
 * type doesn't crash the app.
 */

@Serializable
data class ShootingExif(
    val iso: Int? = null,
    val shutter: String? = null,
    val aperture: Double? = null,
    @SerialName("focal_length") val focalLength: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ImageSummary(
    val id: String,
    val ts: String,
    val width: Int,
    val height: Int,
    @SerialName("thumb_url") val thumbUrl: String,
    @SerialName("preview_url") val previewUrl: String,
    @SerialName("full_url") val fullUrl: String,
    val exif: ShootingExif,
    val favorite: Boolean = false,
    val flag: String = "none",
)

@Serializable
data class CameraInfo(
    val manufacturer: String = "",
    val model: String = "",
    val serial: String = "",
    val firmware: String = "",
)

@Serializable
data class CameraSnapshot(
    val state: String,
    val info: CameraInfo? = null,
)

/** Discriminated union of every server-sent WS event we know about. */
sealed interface ServerEvent {
    val type: String

    data class Hello(
        val sessionId: String,
        val serverTime: String,
        val apiVersion: String,
        val camera: CameraSnapshot,
    ) : ServerEvent {
        override val type = "hello"
    }

    data class ImageCaptured(val image: ImageSummary) : ServerEvent {
        override val type = "image_captured"
    }

    data class CameraState(
        val from: String,
        val to: String,
        val reason: String,
        val ts: String?,
    ) : ServerEvent {
        override val type = "camera_state"
    }

    data class Battery(val levelPct: Int) : ServerEvent {
        override val type = "battery"
    }

    data class FavoriteChanged(val id: String, val favorite: Boolean, val ts: String?) : ServerEvent {
        override val type = "favorite_changed"
    }

    data class FlagChanged(val id: String, val flag: String, val ts: String?) : ServerEvent {
        override val type = "flag_changed"
    }

    data class Pong(val ts: JsonElement?) : ServerEvent {
        override val type = "pong"
    }

    data class Error(val code: String, val message: String, val recoverable: Boolean) : ServerEvent {
        override val type = "error"
    }

    data class Unknown(override val type: String, val raw: JsonElement) : ServerEvent
}
