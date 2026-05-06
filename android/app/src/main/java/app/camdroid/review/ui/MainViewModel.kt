package app.camdroid.review.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.camdroid.review.Config
import app.camdroid.review.data.ApiClient
import app.camdroid.review.data.DiscoveryMethod
import app.camdroid.review.data.DiscoveryResult
import app.camdroid.review.data.EventStream
import app.camdroid.review.data.ImageSummary
import app.camdroid.review.data.PiAddress
import app.camdroid.review.data.PiDiscovery
import app.camdroid.review.data.Preferences
import app.camdroid.review.data.ServerEvent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAX_EVENT_LOG = 50
private const val MAX_RECENT_IMAGES = 50

const val HALF_LOCK_TIMEOUT_SECONDS = 15
const val LOCKED_PULSE_DELAY_SECONDS = 5

enum class LockState { UNLOCKED, HALF_LOCKED, LOCKED }

data class UiState(
    val wsState: EventStream.ConnectionState = EventStream.ConnectionState.UNCONNECTED,
    val cameraState: String = "unknown",
    val cameraModel: String = "",
    val sessionId: String? = null,
    val recentImages: List<ImageSummary> = emptyList(),
    val eventLog: List<String> = emptyList(),
    val currentImageId: String? = null,
    val chromeVisible: Boolean = true,
    val showEventLog: Boolean = false,
    val lockState: LockState = LockState.UNLOCKED,
    /** Unseen captures since LOCKED was entered. Cleared on unlock. */
    val unseenCount: Int = 0,
    /** Countdown remaining while HALF_LOCKED (in seconds). Resets on user activity. */
    val halfLockSecondsRemaining: Int = HALF_LOCK_TIMEOUT_SECONDS,
    /** Seconds spent in LOCKED. Used to delay the pulse so a quick lock-and-unlock doesn't nag. */
    val secondsLocked: Int = 0,
    /** Persistent toggle: when true, an EXIF strip is overlaid on the image. */
    val exifPanelToggled: Boolean = false,
    /** Transient: long-press currently active (only at fit zoom). Overrides chrome
     *  visibility — the EXIF popup shows even in immersive mode while held. */
    val exifLongPressActive: Boolean = false,
    /** Any finger currently touching the image. Source of truth for "user is
     *  actively engaged" — suppresses half-lock expiry whenever true. */
    val pressActive: Boolean = false,
    /** Whether the settings placeholder dialog is shown. */
    val settingsDialogOpen: Boolean = false,
    /** Camera-icon details popup visibility. */
    val cameraDetailsOpen: Boolean = false,
    /** Wi-Fi/connection-icon details popup visibility. */
    val connectionDetailsOpen: Boolean = false,
    /** Pi connection diagnostics, surfaced in the connection details popup. */
    val piHost: String? = null,
    val piPort: Int? = null,
    val discoveryMethod: DiscoveryMethod? = null,
    val lastPongRttMs: Long? = null,
    val lastImageTs: String? = null,
    /** Aspect-ratio framing overlay. The current ratio persists across on/off
     *  toggles within the session so the user doesn't have to re-pick it
     *  after a quick "show, hide, show again" workflow. Both the ratio and
     *  the on/off state are persisted to Preferences and restored at launch. */
    val aspectOverlayActive: Boolean = false,
    val aspectRatio: AspectRatio = AspectRatios.SQUARE,
    val aspectPickerOpen: Boolean = false,
    /** Two-finger-twist soft-snap to cardinals (0/90/180/270°). Persisted. */
    val rotationSnapEnabled: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private lateinit var stream: EventStream
    private lateinit var api: ApiClient
    private val discovery = PiDiscovery(application)
    private val prefs = Preferences(application)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val currentImage: ImageSummary?
        get() = _ui.value.currentImageId?.let { id ->
            _ui.value.recentImages.firstOrNull { it.id == id }
        } ?: _ui.value.recentImages.firstOrNull()

    init {
        // Restore persisted user preferences before any other state runs.
        val savedRatioLabel = prefs.aspectRatioLabel
        val savedRatio = AspectRatios.ALL.firstOrNull { it.label == savedRatioLabel }
            ?: AspectRatios.SQUARE
        _ui.value = _ui.value.copy(
            aspectRatio = savedRatio,
            aspectOverlayActive = prefs.aspectOverlayActive,
            rotationSnapEnabled = prefs.rotationSnapEnabled,
        )

        // Lock-state timer doesn't depend on the network — start it immediately.
        viewModelScope.launch { lockTimerLoop() }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        appendLog("discovering Pi…")
        val result = discovery.findPi() ?: run {
            appendLog("discovery failed; using fallback ${Config.FALLBACK_HOST}")
            DiscoveryResult(Config.FALLBACK_ADDRESS, DiscoveryMethod.FALLBACK)
        }
        val address = result.address
        appendLog("Pi address: ${address.host}:${address.port} (${result.method})")
        _ui.value = _ui.value.copy(
            piHost = address.host,
            piPort = address.port,
            discoveryMethod = result.method,
        )
        // Update the global so Composables that build image URLs (thumb,
        // preview, full) see the resolved address.
        Config.setActiveAddress(address)
        stream = EventStream(address.wsUrl)
        api = ApiClient(address.baseUrl)

        viewModelScope.launch {
            stream.state.collect { s ->
                _ui.value = _ui.value.copy(wsState = s)
                appendLog("ws -> $s")
                if (s == EventStream.ConnectionState.CONNECTED) refreshFromStatus()
            }
        }
        viewModelScope.launch { stream.events.collect(::onEvent) }
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (_ui.value.wsState == EventStream.ConnectionState.CONNECTED) stream.sendPing()
            }
        }
        stream.connect()
    }

    override fun onCleared() {
        if (::stream.isInitialized) stream.shutdown()
        super.onCleared()
    }

    /* ---- public actions (each registers user activity for the half-lock timer) ---- */

    fun setCurrentImage(id: String) {
        recordActivity()
        if (_ui.value.recentImages.any { it.id == id }) {
            _ui.value = _ui.value.copy(currentImageId = id)
        }
    }

    fun toggleChrome() {
        recordActivity()
        _ui.value = _ui.value.copy(chromeVisible = !_ui.value.chromeVisible)
    }

    fun toggleFavoriteOnCurrent() {
        recordActivity()
        val img = currentImage ?: return
        val newState = !img.favorite
        val updated = _ui.value.recentImages.map {
            if (it.id == img.id) it.copy(favorite = newState) else it
        }
        _ui.value = _ui.value.copy(recentImages = updated)
        // Only push to server if discovery has completed; the UI optimistic
        // update above stands either way.
        if (::api.isInitialized) {
            viewModelScope.launch { api.setFavorite(img.id, newState) }
        }
    }

    fun toggleEventLog() {
        recordActivity()
        _ui.value = _ui.value.copy(showEventLog = !_ui.value.showEventLog)
    }

    fun toggleExifPanel() {
        recordActivity()
        _ui.value = _ui.value.copy(exifPanelToggled = !_ui.value.exifPanelToggled)
    }

    fun setExifLongPress(active: Boolean) {
        if (active) recordActivity()
        if (_ui.value.exifLongPressActive != active) {
            _ui.value = _ui.value.copy(exifLongPressActive = active)
        }
    }

    fun setPressActive(active: Boolean) {
        if (active) recordActivity()  // give the user a fresh window the moment they touch
        if (_ui.value.pressActive != active) {
            _ui.value = _ui.value.copy(pressActive = active)
        }
    }

    fun openSettingsDialog() {
        recordActivity()
        _ui.value = _ui.value.copy(settingsDialogOpen = true)
    }

    fun closeSettingsDialog() {
        _ui.value = _ui.value.copy(settingsDialogOpen = false)
    }

    fun openCameraDetails() {
        recordActivity()
        _ui.value = _ui.value.copy(cameraDetailsOpen = true)
    }

    fun closeCameraDetails() {
        _ui.value = _ui.value.copy(cameraDetailsOpen = false)
    }

    fun openConnectionDetails() {
        recordActivity()
        _ui.value = _ui.value.copy(connectionDetailsOpen = true)
    }

    fun closeConnectionDetails() {
        _ui.value = _ui.value.copy(connectionDetailsOpen = false)
    }

    fun toggleAspectOverlay() {
        recordActivity()
        val next = !_ui.value.aspectOverlayActive
        _ui.value = _ui.value.copy(aspectOverlayActive = next)
        prefs.aspectOverlayActive = next
    }

    fun setAspectRatio(ratio: AspectRatio) {
        recordActivity()
        _ui.value = _ui.value.copy(aspectRatio = ratio, aspectOverlayActive = true)
        prefs.aspectRatioLabel = ratio.label
        prefs.aspectOverlayActive = true
    }

    fun turnOffAspectOverlay() {
        recordActivity()
        _ui.value = _ui.value.copy(aspectOverlayActive = false)
        prefs.aspectOverlayActive = false
    }

    fun openAspectPicker() {
        recordActivity()
        _ui.value = _ui.value.copy(aspectPickerOpen = true)
    }

    fun closeAspectPicker() {
        _ui.value = _ui.value.copy(aspectPickerOpen = false)
    }

    fun toggleRotationSnap() {
        recordActivity()
        val next = !_ui.value.rotationSnapEnabled
        _ui.value = _ui.value.copy(rotationSnapEnabled = next)
        prefs.rotationSnapEnabled = next
    }

    /** Cycles UNLOCKED → HALF_LOCKED → LOCKED → UNLOCKED. */
    fun cycleLock() {
        recordActivity()
        val next = when (_ui.value.lockState) {
            LockState.UNLOCKED -> LockState.HALF_LOCKED
            LockState.HALF_LOCKED -> LockState.LOCKED
            LockState.LOCKED -> LockState.UNLOCKED
        }
        applyLockState(next)
    }

    private fun applyLockState(next: LockState) {
        val cur = _ui.value
        val unseen = if (next == LockState.LOCKED) cur.unseenCount else 0
        val newCurrentId = if (next == LockState.UNLOCKED) {
            cur.recentImages.firstOrNull()?.id ?: cur.currentImageId
        } else {
            cur.currentImageId
        }
        _ui.value = cur.copy(
            lockState = next,
            unseenCount = unseen,
            halfLockSecondsRemaining = HALF_LOCK_TIMEOUT_SECONDS,
            secondsLocked = 0,
            currentImageId = newCurrentId,
        )
        appendLog("lock: ${cur.lockState} -> $next")
    }

    /** Reset the half-lock countdown on any user-driven action. Public so the
     *  UI layer can report things the ViewModel can't see directly — notably
     *  pinch/pan gestures handled inside Telephoto. */
    fun recordActivity() {
        if (_ui.value.lockState == LockState.HALF_LOCKED) {
            _ui.value = _ui.value.copy(halfLockSecondsRemaining = HALF_LOCK_TIMEOUT_SECONDS)
        }
    }

    /* ---- background timers ---- */

    private suspend fun lockTimerLoop() {
        while (true) {
            delay(1_000)
            val cur = _ui.value
            when (cur.lockState) {
                LockState.HALF_LOCKED -> {
                    // Suppress timer advance entirely while the user is touching
                    // the image at all — touch == active engagement, period.
                    // Also suppressed while the long-press EXIF popup is active,
                    // which is implied by pressActive but kept explicit for
                    // clarity in case popup mechanics evolve.
                    if (cur.pressActive || cur.exifLongPressActive) continue
                    val remaining = (cur.halfLockSecondsRemaining - 1).coerceAtLeast(0)
                    if (remaining == 0) {
                        // Inactivity ran out — snap to latest AND drop to UNLOCKED.
                        // Half-lock is a "let me dwell for a beat" gesture, not a
                        // persistent mode; once we catch the user up to the
                        // latest shot, normal auto-advance resumes until they
                        // explicitly half-lock again.
                        val latest = cur.recentImages.firstOrNull()?.id ?: cur.currentImageId
                        _ui.value = cur.copy(
                            currentImageId = latest,
                            lockState = LockState.UNLOCKED,
                            halfLockSecondsRemaining = HALF_LOCK_TIMEOUT_SECONDS,
                        )
                        appendLog("half-lock expired -> unlocked, snapped to latest")
                    } else {
                        _ui.value = cur.copy(halfLockSecondsRemaining = remaining)
                    }
                }
                LockState.LOCKED -> {
                    _ui.value = cur.copy(secondsLocked = cur.secondsLocked + 1)
                }
                LockState.UNLOCKED -> { /* no timer running */ }
            }
        }
    }

    /* ---- network plumbing ---- */

    private suspend fun refreshFromStatus() {
        val status = api.fetchStatus() ?: return
        val images = status.recentImages.take(MAX_RECENT_IMAGES)
        val cur = _ui.value
        val latestId = images.firstOrNull()?.id
        // If the user is UNLOCKED and the snapshot has a newer image than what
        // they were last viewing, jump to latest. This handles the on-wake /
        // post-reconnect "I missed N captures" scenario without relying on
        // image_captured event replay (which we don't get for missed events).
        // LOCKED and HALF_LOCKED stay put — same lock-state semantics that
        // govern live captures.
        val newCurrentId = when {
            cur.currentImageId == null -> latestId
            cur.lockState == LockState.UNLOCKED && latestId != null && latestId != cur.currentImageId -> latestId
            else -> cur.currentImageId
        }
        _ui.value = cur.copy(
            cameraState = status.camera.state,
            cameraModel = status.camera.info?.model.orEmpty(),
            sessionId = status.sessionId,
            recentImages = images,
            currentImageId = newCurrentId,
        )
    }

    /** Called from the Activity's lifecycle when the device wakes / app re-foregrounds
     *  after a full ON_STOP. Resets the session-scoped lock state — see
     *  feedback_camdroid_lock_semantics memory. */
    fun onAppForegroundedAfterStop() {
        val cur = _ui.value
        if (cur.lockState != LockState.UNLOCKED) {
            _ui.value = cur.copy(
                lockState = LockState.UNLOCKED,
                halfLockSecondsRemaining = HALF_LOCK_TIMEOUT_SECONDS,
                secondsLocked = 0,
                unseenCount = 0,
            )
            appendLog("lock reset to UNLOCKED on app re-foreground")
        }
    }

    private fun onEvent(e: ServerEvent) {
        when (e) {
            is ServerEvent.Hello -> {
                _ui.value = _ui.value.copy(
                    cameraState = e.camera.state,
                    cameraModel = e.camera.info?.model.orEmpty(),
                    sessionId = e.sessionId,
                )
                appendLog("hello: session=${e.sessionId.take(8)}")
            }
            is ServerEvent.ImageCaptured -> {
                val img = e.image
                val cur = _ui.value
                val recent = (listOf(img) + cur.recentImages.filterNot { it.id == img.id })
                    .take(MAX_RECENT_IMAGES)
                // Auto-advance behavior depends on lock state.
                val newCurrentId = when (cur.lockState) {
                    LockState.UNLOCKED -> img.id
                    LockState.HALF_LOCKED, LockState.LOCKED -> cur.currentImageId
                }
                val newUnseen = if (cur.lockState == LockState.LOCKED) cur.unseenCount + 1 else cur.unseenCount
                _ui.value = cur.copy(
                    recentImages = recent,
                    currentImageId = newCurrentId,
                    unseenCount = newUnseen,
                    lastImageTs = img.ts,
                )
                appendLog("captured ${img.id}")
            }
            is ServerEvent.CameraState -> {
                _ui.value = _ui.value.copy(cameraState = e.to)
                appendLog("camera: ${e.from} -> ${e.to}  (${e.reason})")
            }
            is ServerEvent.Battery -> appendLog("battery: ${e.levelPct}%")
            is ServerEvent.FavoriteChanged -> {
                val recent = _ui.value.recentImages.map {
                    if (it.id == e.id) it.copy(favorite = e.favorite) else it
                }
                _ui.value = _ui.value.copy(recentImages = recent)
                appendLog("favorite: ${e.id} -> ${e.favorite}")
            }
            is ServerEvent.FlagChanged -> {
                val recent = _ui.value.recentImages.map {
                    if (it.id == e.id) it.copy(flag = e.flag) else it
                }
                _ui.value = _ui.value.copy(recentImages = recent)
                appendLog("flag: ${e.id} -> ${e.flag}")
            }
            is ServerEvent.Pong -> {
                // The Pi echoes back the ts we sent in our ping; compute RTT
                // from now - that. Surfaced in the connection details popup.
                val pingTs = e.ts?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                if (pingTs != null) {
                    val nowSec = System.currentTimeMillis() / 1000.0
                    val rttMs = ((nowSec - pingTs) * 1000).toLong().coerceAtLeast(0)
                    _ui.value = _ui.value.copy(lastPongRttMs = rttMs)
                }
            }
            is ServerEvent.Error -> appendLog("ERROR ${e.code}: ${e.message}")
            is ServerEvent.Unknown -> appendLog("unknown event: ${e.type}")
        }
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val log = (listOf("$ts  $line") + _ui.value.eventLog).take(MAX_EVENT_LOG)
        _ui.value = _ui.value.copy(eventLog = log)
    }
}
