package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.widget.WidgetColorSource
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

/**
 * Drives the account hub's "Make it yours" section: theme mode, accent color,
 * and color source. Activities collect [themeMode]/[accentSeed]/[colorSource]
 * into the app theme, so a change recolors the running app; [setAccentSeed] and
 * [setColorSource] also refresh widgets. App icon is handled composable-locally
 * via AppIconUtility and is not part of this ViewModel.
 *
 * The widget-appearance half ([widgetThemeMode]/[widgetColorSource]/[widgetAccentSeed])
 * is deliberately independent of the app face: the app never reads it, only the
 * Glance widgets do (via [org.onekash.kashcal.widget.resolveWidgetAccentColors]),
 * so every setter here pushes a widget refresh but recolors nothing in the app.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val dataStore: KashCalDataStore,
    private val userPreferences: UserPreferencesRepository,
    private val widgetUpdateManager: WidgetUpdateManager,
) : ViewModel() {

    val themeMode: Flow<ThemeMode> = dataStore.theme.map { ThemeMode.fromPrefValue(it) }

    val colorSource: Flow<ColorSource> = userPreferences.resolvedColorSource

    val accentSeed: Flow<Int> = dataStore.accentSeed

    /** Persist the theme face; the running app recolors via the collected [themeMode]. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { dataStore.setTheme(mode.prefValue) }
    }

    /** Pick an accent seed, switch the source to seed-derived, and refresh widgets. */
    fun setAccentSeed(seed: Int) {
        viewModelScope.launch {
            dataStore.setAccentSeed(seed)
            dataStore.setColorSource(ColorSource.SEED.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("accent_changed")
        }
    }

    /** Switch the color source (e.g. back to dynamic Material You) and refresh widgets. */
    fun setColorSource(source: ColorSource) {
        viewModelScope.launch {
            dataStore.setColorSource(source.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("color_source_changed")
        }
    }

    // ========== Widget appearance (independent of the app face) ==========

    val widgetThemeMode: Flow<ThemeMode> = dataStore.widgetThemeMode.map { ThemeMode.fromPrefValue(it) }

    val widgetColorSource: Flow<WidgetColorSource> =
        dataStore.widgetColorSource.map { WidgetColorSource.fromPrefValue(it) }

    val widgetAccentSeed: Flow<Int> = dataStore.widgetAccentSeed

    /** Pin the widgets' light/dark face (or follow the system again) and refresh widgets. */
    fun setWidgetThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            dataStore.setWidgetThemeMode(mode.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("widget_theme_changed")
        }
    }

    /** Pick a widget-only accent seed, switch the widget source to it, and refresh widgets. */
    fun setWidgetAccentSeed(seed: Int) {
        viewModelScope.launch {
            dataStore.setWidgetAccentSeed(seed)
            dataStore.setWidgetColorSource(WidgetColorSource.SEED.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("widget_accent_changed")
        }
    }

    /** Switch the widget color source (follow app / dynamic) and refresh widgets. */
    fun setWidgetColorSource(source: WidgetColorSource) {
        viewModelScope.launch {
            dataStore.setWidgetColorSource(source.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("widget_color_source_changed")
        }
    }
}
