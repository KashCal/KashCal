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
 * Month View widget showing a full month calendar grid.
 *
 * Features:
 * - 6x7 calendar grid with day numbers; when the widget is tall enough to fit them, events
 *   show as title rows with continuous bars for multi-day events (mirroring the in-app month
 *   view), and otherwise fall back to colored indicator dots — decided purely by widget size
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

        // Resolve preferences BEFORE provideContent so the very first RemoteViews render with the
        // correct grid start-day and week-number column. These are only the INITIAL values — they
        // are re-read reactively inside provideContent (keyed on the refresh stamp) so toggling the
        // first-day-of-week or week-number setting takes effect on the next update() without waiting
        // for the widget session to be torn down and recreated (see the produceState calls below).
        val dataStore = KashCalDataStore(context)
        val initialFirstDayOfWeek = dataStore.getFirstDayOfWeek()
        val initialShowWeekNumbers = dataStore.showWeekNumbers.first()
        // Resolve the accent BEFORE provideContent so the very first RemoteViews already carry the
        // picked seed. Seeding produceState with null would render one frame on the platform dynamic
        // palette (null ?: GlanceTheme.colors) and only swap to the seed on a later push — which, if
        // the host snapshots the widget before that push lands, leaves a SEED user showing wallpaper
        // colors ("randomly didn't take the tint"). null colors here still mean the genuine
        // DYNAMIC source on the system face.
        val initialColorConfig = resolveWidgetAccentColors(context, dataStore)

        provideContent {
            // Read month offset + refresh stamp reactively — currentState updates on
            // recomposition triggered by ActionCallback / updateAppWidgetState / update()
            val prefs = currentState<Preferences>()
            val monthOffset = prefs[MonthWidgetStateKeys.MONTH_OFFSET] ?: 0
            val refreshStamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L

            // Re-read the day-of-week and week-number prefs reactively, keyed on the refresh stamp so
            // toggling either setting (which bumps the stamp via WidgetUpdateManager) recomposes with
            // the new value. Reading them once outside provideContent froze them for the session's
            // life, so the toggle only took effect after the widget was removed and re-added.
            val firstDayOfWeek by produceState(initialValue = initialFirstDayOfWeek, key1 = refreshStamp) {
                value = dataStore.getFirstDayOfWeek()
            }
            val showWeekNumbers by produceState(initialValue = initialShowWeekNumbers, key1 = refreshStamp) {
                value = dataStore.showWeekNumbers.first()
            }

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
     * Renders the current month with sample indicator dots into the widget picker.
     * The preview grid starts the week on the locale's first weekday rather than the
     * user's stored preference — see [MonthPreviewContent].
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { MonthPreviewContent(context) }
    }
}
