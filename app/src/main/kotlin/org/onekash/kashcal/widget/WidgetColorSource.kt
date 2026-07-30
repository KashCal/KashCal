package org.onekash.kashcal.widget

/**
 * Where the widgets' colors come from — independent of the app's
 * [org.onekash.kashcal.ui.theme.ColorSource].
 *
 * - [FOLLOW_APP]: mirror the app's color source and accent (default, so users who never open the
 *   widget appearance settings keep the pre-existing behavior: widgets colored like the app).
 * - [DYNAMIC]: the device's Material You palette, regardless of any in-app accent.
 * - [SEED]: a widget-only accent seed, independent of the app's seed.
 */
enum class WidgetColorSource(val prefValue: String) {
    FOLLOW_APP("follow_app"),
    DYNAMIC("dynamic"),
    SEED("seed");

    companion object {
        /** Maps a stored pref value to a source, falling back to [FOLLOW_APP] for unknown/null. */
        fun fromPrefValue(value: String?): WidgetColorSource =
            entries.firstOrNull { it.prefValue == value } ?: FOLLOW_APP
    }
}
