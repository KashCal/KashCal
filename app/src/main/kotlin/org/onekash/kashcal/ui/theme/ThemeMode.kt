package org.onekash.kashcal.ui.theme

import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore

/** How a theme derives its light/dark face. */
enum class ThemeFace { FOLLOW_SYSTEM, FORCE_LIGHT, FORCE_DARK }

/**
 * The user's app-theme choice, persisted as a [KashCalDataStore] theme string.
 *
 * Each mode is self-describing: its light/dark [face], its fixed [palette] (or null to use
 * Material You dynamic color / the platform baseline), and its picker [labelRes]/[descriptionRes].
 * Resolution, the settings picker, and the row subtitle all derive from this data, so adding a
 * new branded shade is purely additive — a new entry here (plus its [ThemePalette] and strings),
 * with no changes to the theme composable or the picker.
 *
 * - [SYSTEM]/[LIGHT]/[DARK] carry no palette: they use Material You dynamic color (baseline
 *   schemes pre-Android 12). LIGHT/DARK simply pin the face.
 * - [TEAL] carries the fixed KashCal Teal brand palette and follows the device light/dark setting.
 */
enum class ThemeMode(
    val prefValue: String,
    val face: ThemeFace,
    val palette: ThemePalette?,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    SYSTEM(
        prefValue = KashCalDataStore.THEME_SYSTEM,
        face = ThemeFace.FOLLOW_SYSTEM,
        palette = null,
        labelRes = R.string.option_system_default,
        descriptionRes = R.string.settings_theme_system_desc,
    ),
    LIGHT(
        prefValue = KashCalDataStore.THEME_LIGHT,
        face = ThemeFace.FORCE_LIGHT,
        palette = null,
        labelRes = R.string.option_light,
        descriptionRes = R.string.settings_theme_light_desc,
    ),
    DARK(
        prefValue = KashCalDataStore.THEME_DARK,
        face = ThemeFace.FORCE_DARK,
        palette = null,
        labelRes = R.string.option_dark,
        descriptionRes = R.string.settings_theme_dark_desc,
    ),
    TEAL(
        prefValue = KashCalDataStore.THEME_TEAL,
        face = ThemeFace.FOLLOW_SYSTEM,
        palette = TealPalette,
        labelRes = R.string.option_kashcal_teal,
        descriptionRes = R.string.settings_theme_teal_desc,
    );

    /** Whether this mode renders the dark face, given the current device dark setting. */
    fun isDark(systemInDark: Boolean): Boolean = when (face) {
        ThemeFace.FOLLOW_SYSTEM -> systemInDark
        ThemeFace.FORCE_LIGHT -> false
        ThemeFace.FORCE_DARK -> true
    }

    companion object {
        /** Maps a stored theme string to a mode, falling back to [SYSTEM] for unknown/null. */
        fun fromPrefValue(value: String?): ThemeMode =
            entries.firstOrNull { it.prefValue == value } ?: SYSTEM
    }
}
