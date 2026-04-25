package app.camdroid.review.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.camdroid.review.Config
import app.camdroid.review.data.ApiClient
import app.camdroid.review.data.EventStream
import app.camdroid.review.data.ImageSummary
import app.camdroid.review.data.PiAddress
import app.camdroid.review.data.PiDiscovery
import app.camdroid.review.data.ServerEvent
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private lateinit var stream: EventStream
    private lateinit var api: ApiClient
    private val discovery = PiDiscovery(application)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val currentImage: ImageSummary?
        get() = _ui.value.currentImageId?.let { id ->
            _ui.value.recentImages.firstOrNull { it.id == id }
        } ?: _ui.value.recentImages.firstOrNull()

    init {
        // Lock-state timer doesn't depend on the network — start it immediately.
        viewModelScope.launch { lockTimerLoop() }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        appendLog("discovering Pi…")
        val address = discovery.findPi() ?: run {
            appendLog("discovery failed; using fallback ${Config.FALLBACK_HOST}")
            Config.FALLBACK_ADDRESS
        }
        appendLog("Pi address: ${address.host}:${address.port}")
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
        _ui.value = _ui.value.copy(
            cameraState = status.camera.state,
            cameraModel = status.camera.info?.model.orEmpty(),
            sessionId = status.sessionId,
            recentImages = images,
            currentImageId = _ui.value.currentImageId ?: images.firstOrNull()?.id,
        )
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
            is ServerEvent.Pong -> { /* silent */ }
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
