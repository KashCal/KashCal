package org.onekash.kashcal.ui.theme

import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Where the app's colors come from, independent of the light/dark [ThemeMode] face.
 *
 * - [DYNAMIC]: Material You (wallpaper-derived on Android 12+) or the platform baseline. This is
 *   the default so existing users see no change until they opt into an accent.
 * - [SEED]: a full Material 3 scheme generated from the user's chosen accent seed color.
 */
enum class ColorSource(val prefValue: String) {
    DYNAMIC("dynamic"),
    SEED("seed");

    companion object {
        /**
         * Resolves the effective color source.
         *
         * @param explicit the stored color-source pref value, or null if never set.
         * @param legacyTheme the stored (legacy) theme string, used only to migrate users who had
         *   picked the retired "teal" theme onto the seed path — their brand color is preserved
         *   because the accent seed defaults to brand teal.
         */
        fun fromPrefValue(explicit: String?, legacyTheme: String?): ColorSource {
            entries.firstOrNull { it.prefValue == explicit }?.let { return it }
            // No explicit choice yet: migrate the retired teal theme to the seed path, else dynamic.
            return if (legacyTheme == KashCalDataStore.THEME_TEAL) SEED else DYNAMIC
        }
    }
}
