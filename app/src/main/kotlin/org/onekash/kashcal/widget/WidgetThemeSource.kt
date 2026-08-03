package org.onekash.kashcal.widget

import androidx.annotation.StringRes
import org.onekash.kashcal.R

/**
 * Where the widgets' light/dark face comes from — independent of, but able to track, the app's
 * own [org.onekash.kashcal.ui.theme.ThemeMode].
 *
 * - [FOLLOW_APP]: mirror the app's face (default). When the app itself follows the device, the
 *   widget follows the device too — so this transitively covers device-following without a
 *   separate "system" option. For a user whose app is on the default (follow-device) theme this
 *   is indistinguishable from the earlier "system" default; a user who has pinned the app to
 *   Light or Dark now gets a widget that adopts that pin rather than tracking the device.
 * - [LIGHT]/[DARK]: pin the widget to that face regardless of the app or the device setting.
 *
 * The stored pref value ("follow_app"/"light"/"dark") reuses the same key the earlier widget-theme
 * setting wrote. A legacy "system" value is unknown here and therefore falls back to [FOLLOW_APP],
 * which is exactly the desired target: an unpinned widget that tracks the app (and thus the device).
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
        /** Maps a stored pref value to a source, falling back to [FOLLOW_APP] for unknown/null. */
        fun fromPrefValue(value: String?): WidgetThemeSource =
            entries.firstOrNull { it.prefValue == value } ?: FOLLOW_APP
    }
}
