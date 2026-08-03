package org.onekash.kashcal.ui.theme

import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore

/** How a theme derives its light/dark face. */
enum class ThemeFace { FOLLOW_SYSTEM, FORCE_LIGHT, FORCE_DARK }

/**
 * The user's light/dark face choice, persisted as a [KashCalDataStore] theme string.
 *
 * A face only decides light vs dark; the actual colors come from the app's [ColorSource]
 * (dynamic Material You / baseline, or an accent-seed-derived scheme). [SYSTEM] follows the
 * device setting; [LIGHT]/[DARK] pin the face.
 */
enum class ThemeMode(
    val prefValue: String,
    val face: ThemeFace,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    SYSTEM(
        prefValue = KashCalDataStore.THEME_SYSTEM,
        face = ThemeFace.FOLLOW_SYSTEM,
        labelRes = R.string.option_system_default,
        descriptionRes = R.string.settings_theme_system_desc,
    ),
    LIGHT(
        prefValue = KashCalDataStore.THEME_LIGHT,
        face = ThemeFace.FORCE_LIGHT,
        labelRes = R.string.option_light,
        descriptionRes = R.string.settings_theme_light_desc,
    ),
    DARK(
        prefValue = KashCalDataStore.THEME_DARK,
        face = ThemeFace.FORCE_DARK,
        labelRes = R.string.option_dark,
        descriptionRes = R.string.settings_theme_dark_desc,
    );

    /** Whether this mode renders the dark face, given the current device dark setting. */
    fun isDark(systemInDark: Boolean): Boolean = when (face) {
        ThemeFace.FOLLOW_SYSTEM -> systemInDark
        ThemeFace.FORCE_LIGHT -> false
        ThemeFace.FORCE_DARK -> true
    }

    /** The pinned dark face, or null when this mode follows the device: null / false / true. */
    val forcedDark: Boolean?
        get() = when (face) {
            ThemeFace.FOLLOW_SYSTEM -> null
            ThemeFace.FORCE_LIGHT -> false
            ThemeFace.FORCE_DARK -> true
        }

    companion object {
        /** Maps a stored theme string to a mode, falling back to [SYSTEM] for unknown/null. */
        fun fromPrefValue(value: String?): ThemeMode =
            entries.firstOrNull { it.prefValue == value } ?: SYSTEM
    }
}
