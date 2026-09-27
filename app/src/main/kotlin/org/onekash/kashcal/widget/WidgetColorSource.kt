package org.onekash.kashcal.widget

/**
 * Where the widgets' colors come from, independent of the app's
 * [org.onekash.kashcal.ui.theme.ColorSource].
 *
 * - [FOLLOW_APP]: the app's color source and accent. The default, so users who never open the
 *   widget appearance settings get widgets colored like the app.
 * - [DYNAMIC]: the device's Material You palette, whatever the in-app accent.
 * - [SEED]: a widget-only accent seed, independent of the app's seed.
 */
enum class WidgetColorSource(val prefValue: String) {
    FOLLOW_APP("follow_app"),
    DYNAMIC("dynamic"),
    SEED("seed");

    companion object {
        /** Maps a stored pref value to a source; null or unknown falls back to [FOLLOW_APP]. */
        fun fromPrefValue(value: String?): WidgetColorSource =
            entries.firstOrNull { it.prefValue == value } ?: FOLLOW_APP
    }
}
