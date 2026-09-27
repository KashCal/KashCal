package org.onekash.kashcal.ui.theme

import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Selects where the app's colors come from, independent of the light or dark [ThemeMode] face.
 *
 * - [DYNAMIC]: Material You (wallpaper-derived on Android 12+) or the platform baseline. The
 *   default, so a user who never picks an accent keeps these colors.
 * - [SEED]: a full Material 3 scheme generated from the user's accent seed color.
 */
enum class ColorSource(val prefValue: String) {
    DYNAMIC("dynamic"),
    SEED("seed");

    companion object {
        /**
         * Resolves the effective color source; an unknown or null [explicit] falls back to
         * [legacyTheme].
         *
         * @param explicit the stored color-source pref value, or null if never set.
         * @param legacyTheme the stored legacy theme string. The retired "teal" theme maps to
         *   [SEED], anything else to [DYNAMIC]; the accent seed defaults to brand teal, so those
         *   users keep their color.
         */
        fun fromPrefValue(explicit: String?, legacyTheme: String?): ColorSource {
            entries.firstOrNull { it.prefValue == explicit }?.let { return it }
            return if (legacyTheme == KashCalDataStore.THEME_TEAL) SEED else DYNAMIC
        }
    }
}
