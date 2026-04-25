package app.camdroid.review.data

import android.util.Log
import app.camdroid.review.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for the Pi's v0 HTTP endpoints. Used for one-shot calls
 * (snapshot fetch, favorite/flag PUTs). The continuous event stream goes
 * through EventStream over WebSocket.
 */
class ApiClient(
    private val baseUrl: String,
) {
    private val tag = "ApiClient"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun fetchStatus(limit: Int = 50): StatusResponse? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl${Config.API_PREFIX}/status?limit=$limit").build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                json.decodeFromString<StatusResponse>(body)
            }
        }.onFailure { Log.w(tag, "fetchStatus failed: ${it.message}") }.getOrNull()
    }

    suspend fun setFavorite(imageId: String, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val body = """{"favorite": $favorite}""".toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("$baseUrl${Config.API_PREFIX}/images/$imageId/favorite")
            .put(body)
            .build()
        runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.onFailure { Log.w(tag, "setFavorite failed: ${it.message}") }.getOrDefault(false)
    }

    suspend fun setFlag(imageId: String, flag: String): Boolean = withContext(Dispatchers.IO) {
        val body = """{"flag": "$flag"}""".toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("$baseUrl${Config.API_PREFIX}/images/$imageId/flag")
            .put(body)
            .build()
        runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.onFailure { Log.w(tag, "setFlag failed: ${it.message}") }.getOrDefault(false)
    }
}

@Serializable
data class StatusResponse(
    @SerialName("api_version") val apiVersion: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("server_time") val serverTime: String,
    val camera: CameraSnapshot,
    @SerialName("recent_images") val recentImages: List<ImageSummary> = emptyList(),
)
