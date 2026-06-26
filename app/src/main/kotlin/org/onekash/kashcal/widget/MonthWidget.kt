package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.model.MonthGrid
import java.time.YearMonth

/**
 * Month View widget showing a full month calendar grid with event indicator dots.
 *
 * Features:
 * - 6x7 calendar grid with day numbers and event indicator dots
 * - Today highlighted with accent color
 * - Past days dimmed
 * - Tap day → navigate to that day in app
 * - Tap header → return to current month (if navigated) or open app at today
 * - Tap "+" → create event
 * - Month navigation via forward/backward arrows
 *
 * Updates:
 * - On event create/update/delete
 * - On sync completion
 * - At midnight (new day)
 * - Periodically (every 30 minutes)
 *
 * State management:
 * - Month offset + [WIDGET_REFRESH_STAMP] stored in Glance PreferencesGlanceStateDefinition
 * - State read inside provideContent via currentState<Preferences>() for reactive updates
 * - Glance 1.1+ session management means update() recomposes provideContent without
 *   re-calling provideGlance(), so state MUST be read inside provideContent
 * - [WIDGET_REFRESH_STAMP] is bumped by [WidgetUpdateManager] on event CRUD/sync so
 *   [produceState] re-keys and re-fetches events. Without it, month-nav arrows triggered
 *   refetches via the `monthGrid` key, but event CRUD would leave stale dots on the grid.
 */
class MonthWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val stateDefinition = PreferencesGlanceStateDefinition

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MonthWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, MonthWidgetEntryPoint::class.java)
        val repository = entryPoint.widgetDataRepository()

        // Read preferences (stable across arrow taps)
        val dataStore = KashCalDataStore(context)
        val firstDayOfWeek = dataStore.getFirstDayOfWeek()

        provideContent {
            // Read month offset + refresh stamp reactively — currentState updates on
            // recomposition triggered by ActionCallback / updateAppWidgetState / update()
            val prefs = currentState<Preferences>()
            val monthOffset = prefs[MonthWidgetStateKeys.MONTH_OFFSET] ?: 0
            val refreshStamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L

            // Compute target month and grid (pure computation, no suspend needed)
            val targetMonth = remember(monthOffset) {
                YearMonth.now().plusMonths(monthOffset.toLong())
            }
            val monthGrid = remember(targetMonth, firstDayOfWeek) {
                MonthGrid.compute(targetMonth.year, targetMonth.monthValue - 1, firstDayOfWeek)
            }

            // Fetch events asynchronously — grid renders immediately, dots appear when ready.
            // Re-fetches when either the grid changes (month-nav arrows, day-of-week pref) OR
            // the refresh stamp changes (event CRUD, sync completion, midnight, periodic).
            val monthEvents by produceState(
                initialValue = emptyMap<Int, List<WidgetDataRepository.WidgetEvent>>(),
                key1 = monthGrid,
                key2 = refreshStamp
            ) {
                val (startDayCode, endDayCode) = monthGrid.toDayCodeRange()
                value = fetchMonthEvents(repository, startDayCode, endDayCode)
            }

            GlanceTheme {
                MonthWidgetContent(
                    monthGrid = monthGrid,
                    monthEvents = monthEvents,
                    monthOffset = monthOffset,
                    targetYear = targetMonth.year,
                    targetMonth0 = targetMonth.monthValue - 1,
                    firstDayOfWeek = firstDayOfWeek
                )
            }
        }
    }
}
