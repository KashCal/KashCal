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
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject

/**
 * Drives the account hub's "Make it yours" section: theme mode, accent color,
 * and color source. Activities collect [themeMode]/[accentSeed]/[colorSource]
 * into the app theme, so a change recolors the running app; [setAccentSeed] and
 * [setColorSource] also refresh widgets. App icon is handled composable-locally
 * via AppIconUtility and is not part of this ViewModel.
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
}
