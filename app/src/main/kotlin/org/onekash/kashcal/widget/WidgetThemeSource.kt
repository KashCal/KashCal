package org.onekash.kashcal.widget

import androidx.annotation.StringRes
import org.onekash.kashcal.R

/**
 * Chooses where the widgets' light/dark face comes from, separate from but able to track the app's
 * [org.onekash.kashcal.ui.theme.ThemeMode].
 *
 * - [FOLLOW_APP]: use the app's face (default). An app that follows the device makes the widget
 *   follow it too, so there is no separate "system" option.
 * - [LIGHT], [DARK]: pin the widget to that face whatever the app or device uses.
 *
 * [prefValue] is stored under the `widget_theme_mode` key, which an older widget-theme setting
 * also wrote. Its "system" value is unknown here and falls back to [FOLLOW_APP], the intended
 * target: an unpinned widget that tracks the app, and through it the device.
 */
enum class WidgetThemeSource(
    val prefValue: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    FOLLOW_APP(
        prefValue = "follow_app",
        labelRes = R.string.settings_widget_theme_follow_app,
        descriptionRes = R.string.settings_widget_theme_follow_app_desc,
    ),
    LIGHT(
        prefValue = "light",
        labelRes = R.string.option_light,
        descriptionRes = R.string.settings_theme_light_desc,
    ),
    DARK(
        prefValue = "dark",
        labelRes = R.string.option_dark,
        descriptionRes = R.string.settings_theme_dark_desc,
    );

    companion object {
        /** Maps a stored pref value to a source; null or unknown falls back to [FOLLOW_APP]. */
        fun fromPrefValue(value: String?): WidgetThemeSource =
            entries.firstOrNull { it.prefValue == value } ?: FOLLOW_APP
    }
}
