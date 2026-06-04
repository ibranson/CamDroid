package app.camdroid.review.data

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Lightweight wrapper around SharedPreferences for app-wide persistent state
 * that genuinely should survive process death (settings, last-used choices).
 *
 * Note: session-scoped UI state (lock, current image, etc.) explicitly does
 * NOT live here — see feedback_camdroid_lock_semantics for the rule.
 */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Label of the last-selected aspect ratio (e.g. "4:5"). null = never set. */
    var aspectRatioLabel: String?
        get() = prefs.getString(KEY_ASPECT_RATIO_LABEL, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ASPECT_RATIO_LABEL) else putString(KEY_ASPECT_RATIO_LABEL, value)
                apply()
            }
        }

    /** Whether the aspect-ratio overlay was active when the app last quit. */
    var aspectOverlayActive: Boolean
        get() = prefs.getBoolean(KEY_ASPECT_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ASPECT_ACTIVE, value).apply()

    /** Whether two-finger-twist soft-snap to cardinals (0/90/180/270°) is on. */
    var rotationSnapEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROTATION_SNAP, true)
        set(value) = prefs.edit().putBoolean(KEY_ROTATION_SNAP, value).apply()

    /** Whether the persistent EXIF strip is shown by default. */
    var exifPanelDefault: Boolean
        get() = prefs.getBoolean(KEY_EXIF_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_EXIF_DEFAULT, value).apply()

    /** Whether the dev event-log overlay is shown by default. */
    var eventLogDefault: Boolean
        get() = prefs.getBoolean(KEY_EVENT_LOG_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_EVENT_LOG_DEFAULT, value).apply()

    /** When UNLOCKED, jump to the newest capture as it arrives. */
    var autoShowOnCapture: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHOW_ON_CAPTURE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SHOW_ON_CAPTURE, value).apply()

    /** App theme preference. */
    var themeMode: ThemeMode
        get() = when (prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    /**
     * Manually-pinned bridge address override. When non-null, bootstrap skips
     * discovery and goes straight here. Cleared by "Find bridge" in settings.
     */
    var manualBridgeHost: String?
        get() = prefs.getString(KEY_MANUAL_BRIDGE_HOST, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_MANUAL_BRIDGE_HOST)
                else putString(KEY_MANUAL_BRIDGE_HOST, value)
                apply()
            }
        }

    var manualBridgePort: Int
        get() = prefs.getInt(KEY_MANUAL_BRIDGE_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_MANUAL_BRIDGE_PORT, value).apply()

    fun clearManualBridge() {
        prefs.edit()
            .remove(KEY_MANUAL_BRIDGE_HOST)
            .remove(KEY_MANUAL_BRIDGE_PORT)
            .apply()
    }

    companion object {
        private const val NAME = "camdroid_prefs"
        private const val KEY_ASPECT_RATIO_LABEL = "aspect_ratio_label"
        private const val KEY_ASPECT_ACTIVE = "aspect_overlay_active"
        private const val KEY_ROTATION_SNAP = "rotation_snap_enabled"
        private const val KEY_EXIF_DEFAULT = "exif_default_on"
        private const val KEY_EVENT_LOG_DEFAULT = "event_log_default_on"
        private const val KEY_AUTO_SHOW_ON_CAPTURE = "auto_show_on_capture"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_MANUAL_BRIDGE_HOST = "manual_bridge_host"
        private const val KEY_MANUAL_BRIDGE_PORT = "manual_bridge_port"
    }
}
