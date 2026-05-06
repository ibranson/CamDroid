package app.camdroid.review.data

import android.content.Context

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

    companion object {
        private const val NAME = "camdroid_prefs"
        private const val KEY_ASPECT_RATIO_LABEL = "aspect_ratio_label"
        private const val KEY_ASPECT_ACTIVE = "aspect_overlay_active"
        private const val KEY_ROTATION_SNAP = "rotation_snap_enabled"
    }
}
