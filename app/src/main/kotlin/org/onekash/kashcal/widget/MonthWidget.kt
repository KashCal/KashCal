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
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.model.MonthGrid
import java.time.YearMonth

/**
 * Month widget: a 6x7 day grid, today marked in the accent color and past days dimmed.
 *
 * Widget size alone decides how events show: when the widget is tall enough, as title rows with
 * continuous bars for multi-day events (like the in-app month view), otherwise as colored dots.
 * Tapping a day opens it in the app; the arrows change month; the header returns to the current
 * month when navigated away, else opens the app at today; "+" creates an event.
 *
 * Refreshes on each [WidgetUpdateManager.updateAllWidgets] call (for example an event write, a
 * sync, midnight or a settings change) and every 30 minutes.
 *
 * ## State
 *
 * The month offset and [WIDGET_REFRESH_STAMP] live in [PreferencesGlanceStateDefinition].
 * Glance 1.1+ keeps a session, so update() recomposes [provideContent] without calling
 * [provideGlance] again; state must be read inside provideContent via
 * `currentState<Preferences>()`. The stamp keys [produceState], so a bump re-fetches events.
 * Without it only the arrows (through the `monthGrid` key) re-fetch, and an event write leaves
 * the grid stale.
 */
class MonthWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val previewSizeMode = WidgetPreviewSizes.MONTH

    override val stateDefinition = PreferencesGlanceStateDefinition

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MonthWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, MonthWidgetEntryPoint::class.java)
        val repository = entryPoint.widgetDataRepository()

        // Resolve preferences before provideContent so the first RemoteViews have the right grid
        // start day and week-number column. These are initial values only; the produceState calls
        // below re-read them on each refresh stamp.
        val dataStore = KashCalDataStore(context)
        val initialFirstDayOfWeek = dataStore.getFirstDayOfWeek()
        val initialShowWeekNumbers = dataStore.showWeekNumbers.first()
        // Resolve the accent before provideContent so the first RemoteViews carry the picked seed.
        // Seeding produceState with null renders a frame on the platform dynamic palette, and a
        // host that snapshots it then leaves a seed user on wallpaper colors. Null colors mean the
        // DYNAMIC source on the system face.
        val initialColorConfig = resolveWidgetAccentColors(context, dataStore)

        provideContent {
            // currentState updates on a recomposition from an ActionCallback,
            // updateAppWidgetState or update().
            val prefs = currentState<Preferences>()
            val monthOffset = prefs[MonthWidgetStateKeys.MONTH_OFFSET] ?: 0
            val refreshStamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L

            // Re-read the first-day-of-week and week-number settings on each refresh stamp, which
            // toggling either one bumps through WidgetUpdateManager. Read only outside
            // provideContent, they would stay frozen until the widget was removed and re-added.
            val firstDayOfWeek by produceState(initialValue = initialFirstDayOfWeek, key1 = refreshStamp) {
                value = dataStore.getFirstDayOfWeek()
            }
            val showWeekNumbers by produceState(initialValue = initialShowWeekNumbers, key1 = refreshStamp) {
                value = dataStore.showWeekNumbers.first()
            }

            val targetMonth = remember(monthOffset) {
                YearMonth.now().plusMonths(monthOffset.toLong())
            }
            val monthGrid = remember(targetMonth, firstDayOfWeek) {
                MonthGrid.compute(targetMonth.year, targetMonth.monthValue - 1, firstDayOfWeek)
            }

            // The grid renders at once and events appear when fetched. Re-fetches when the grid
            // changes (arrows, first-day-of-week) or the refresh stamp does.
            val monthEvents by produceState(
                initialValue = emptyMap<Int, List<WidgetDataRepository.WidgetEvent>>(),
                key1 = monthGrid,
                key2 = refreshStamp
            ) {
                val (startDayCode, endDayCode) = monthGrid.toDayCodeRange()
                value = fetchMonthEvents(repository, startDayCode, endDayCode)
            }
            val colorConfig by produceState(initialValue = initialColorConfig, key1 = refreshStamp) {
                value = resolveWidgetAccentColors(context, dataStore)
            }

            GlanceTheme(colors = colorConfig.colors ?: GlanceTheme.colors) {
                MonthWidgetContent(
                    monthGrid = monthGrid,
                    monthEvents = monthEvents,
                    monthOffset = monthOffset,
                    targetYear = targetMonth.year,
                    targetMonth0 = targetMonth.monthValue - 1,
                    firstDayOfWeek = firstDayOfWeek,
                    showWeekNumbers = showWeekNumbers,
                    forcedDark = colorConfig.forcedDark
                )
            }
        }
    }

    /**
     * Renders the current month with sample dots into the widget picker. The preview week starts
     * on the locale's first weekday, not the user's setting ([MonthPreviewContent]).
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { MonthPreviewContent(context) }
    }
}
