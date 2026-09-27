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
import org.onekash.kashcal.widget.WidgetThemeSource
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

/**
 * Drives the account hub's "Make it yours" section: theme mode, accent color and color source,
 * plus the widget appearance. The app icon row uses `AppIconUtility` instead.
 *
 * The activities theme themselves from the same preferences through their own ViewModels
 * (HomeViewModel, AccountSettingsViewModel), so a change here recolors the running app.
 * [setAccentSeed] and [setColorSource] also refresh widgets; [setThemeMode] doesn't.
 *
 * The widget half ([widgetThemeSource], [widgetColorSource], [widgetAccentSeed]) is independent
 * of the app face but can follow it. The app never reads it; the Glance widgets do, through
 * [org.onekash.kashcal.widget.resolveWidgetAccentColors], where "Follow app" reads the app's
 * own theme, so its setters refresh widgets and recolor nothing in the app.
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

    /** Persists the theme face; the activities recolor from the same preference. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { dataStore.setTheme(mode.prefValue) }
    }

    /** Stores an accent seed, switches to [ColorSource.SEED] and refreshes widgets. */
    fun setAccentSeed(seed: Int) {
        viewModelScope.launch {
            dataStore.setAccentSeed(seed)
            dataStore.setColorSource(ColorSource.SEED.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("accent_changed")
        }
    }

    /** Switches the color source, for example back to Material You, and refreshes widgets. */
    fun setColorSource(source: ColorSource) {
        viewModelScope.launch {
            dataStore.setColorSource(source.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("color_source_changed")
        }
    }

    // ========== Widget appearance (independent of the app face) ==========

    val widgetThemeSource: Flow<WidgetThemeSource> =
        dataStore.widgetThemeSource.map { WidgetThemeSource.fromPrefValue(it) }

    val widgetColorSource: Flow<WidgetColorSource> =
        dataStore.widgetColorSource.map { WidgetColorSource.fromPrefValue(it) }

    val widgetAccentSeed: Flow<Int> = dataStore.widgetAccentSeed

    /** Pins the widgets' light or dark face, or follows the app again, and refreshes widgets. */
    fun setWidgetThemeSource(source: WidgetThemeSource) {
        viewModelScope.launch {
            dataStore.setWidgetThemeSource(source.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("widget_theme_changed")
        }
    }

    /** Stores a widget-only accent seed, switches the widget source to it and refreshes widgets. */
    fun setWidgetAccentSeed(seed: Int) {
        viewModelScope.launch {
            dataStore.setWidgetAccentSeed(seed)
            dataStore.setWidgetColorSource(WidgetColorSource.SEED.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("widget_accent_changed")
        }
    }

    /** Switches the widget color source and refreshes widgets. */
    fun setWidgetColorSource(source: WidgetColorSource) {
        viewModelScope.launch {
            dataStore.setWidgetColorSource(source.prefValue)
            widgetUpdateManager.updateAllWidgetsForColorChange("widget_color_source_changed")
        }
    }
}
