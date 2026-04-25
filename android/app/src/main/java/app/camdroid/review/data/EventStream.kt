package app.camdroid.review.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket connection to the Pi's /api/v0/events stream.
 *
 * Exposes:
 *   - state: ConnectionState as a StateFlow (UNCONNECTED -> CONNECTING -> CONNECTED -> ...)
 *   - events: SharedFlow<ServerEvent> of decoded server events
 *
 * Reconnects automatically on failure with a fixed 2s delay. More sophisticated
 * backoff lands in the reliability-polish stage.
 */
class EventStream(
    private val url: String,
) {
    enum class ConnectionState { UNCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val tag = "EventStream"

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val _state = MutableStateFlow(ConnectionState.UNCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var closedByCaller = false

    fun connect() {
        closedByCaller = false
        if (_state.value == ConnectionState.CONNECTING || _state.value == ConnectionState.CONNECTED) return
        openSocket()
    }

    fun close() {
        closedByCaller = true
        reconnectJob?.cancel()
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.UNCONNECTED
    }

    fun sendPing() {
        val payload = """{"type":"ping","ts":${System.currentTimeMillis() / 1000.0}}"""
        ws?.send(payload)
    }

    fun shutdown() {
        close()
        scope.cancel()
    }

    private fun openSocket() {
        _state.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (closedByCaller) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000)
            if (!closedByCaller) openSocket()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(tag, "ws open")
            _state.value = ConnectionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parse(text) ?: return
            scope.launch { _events.emit(event) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(tag, "ws closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(tag, "ws closed: $code $reason")
            _state.value = ConnectionState.UNCONNECTED
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(tag, "ws failure: ${t.message}")
            _state.value = ConnectionState.FAILED
            scheduleReconnect()
        }
    }

    private fun parse(text: String): ServerEvent? {
        return try {
            val element = json.parseToJsonElement(text).jsonObject
            when (val type = element["type"]?.jsonPrimitive?.contentOrNull) {
                "hello" -> json.decodeFromJsonElement(HelloDto.serializer(), element).toEvent()
                "image_captured" -> {
                    val image = json.decodeFromJsonElement(ImageSummary.serializer(), element)
                    ServerEvent.ImageCaptured(image)
                }
                "camera_state" -> {
                    val dto = json.decodeFromJsonElement(CameraStateDto.serializer(), element)
                    ServerEvent.CameraState(dto.from, dto.to, dto.reason, dto.ts)
                }
                "battery" -> {
                    val dto = json.decodeFromJsonElement(BatteryDto.serializer(), element)
                    ServerEvent.Battery(dto.levelPct)
                }
                "favorite_changed" -> {
                    val dto = json.decodeFromJsonElement(FavoriteChangedDto.serializer(), element)
                    ServerEvent.FavoriteChanged(dto.id, dto.favorite, dto.ts)
                }
                "flag_changed" -> {
                    val dto = json.decodeFromJsonElement(FlagChangedDto.serializer(), element)
                    ServerEvent.FlagChanged(dto.id, dto.flag, dto.ts)
                }
                "pong" -> ServerEvent.Pong(element["ts"])
                "error" -> {
                    val dto = json.decodeFromJsonElement(ErrorDto.serializer(), element)
                    ServerEvent.Error(dto.code, dto.message, dto.recoverable)
                }
                null -> null
                else -> ServerEvent.Unknown(type, element)
            }
        } catch (e: Exception) {
            Log.w(tag, "failed to parse event: ${e.message}")
            null
        }
    }

    /* ---- DTOs (private wire shapes) ---- */

    @kotlinx.serialization.Serializable
    private data class HelloDto(
        @kotlinx.serialization.SerialName("session_id") val sessionId: String,
        @kotlinx.serialization.SerialName("server_time") val serverTime: String,
        @kotlinx.serialization.SerialName("api_version") val apiVersion: String,
        val camera: CameraSnapshot,
    ) {
        fun toEvent() = ServerEvent.Hello(sessionId, serverTime, apiVersion, camera)
    }

    @kotlinx.serialization.Serializable
    private data class CameraStateDto(
        val from: String,
        val to: String,
        val reason: String,
        val ts: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class BatteryDto(
        @kotlinx.serialization.SerialName("level_pct") val levelPct: Int,
    )

    @kotlinx.serialization.Serializable
    private data class FavoriteChangedDto(
        val id: String,
        val favorite: Boolean,
        val ts: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class FlagChangedDto(
        val id: String,
        val flag: String,
        val ts: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class ErrorDto(
        val code: String,
        val message: String,
        val recoverable: Boolean = true,
    )
}
