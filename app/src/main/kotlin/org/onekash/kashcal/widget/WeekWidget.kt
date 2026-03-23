package org.onekash.kashcal.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Week View widget showing events for the next 7 days (today + 6 days).
 *
 * Features:
 * - Shows 7-day rolling week in a scrollable list
 * - Up to 5 events per day with overflow indicator
 * - Day headers with "Today" highlight
 * - Tap day header → navigate to that day in app
 * - Tap event → open event quick view
 * - Tap empty day → create event on that day
 *
 * Updates:
 * - On event create/update/delete
 * - On sync completion
 * - At midnight (new day)
 * - Periodically (every 30 minutes)
 */
class WeekWidget : GlanceAppWidget() {

    /**
     * Use exact size mode for consistent rendering.
     */
    override val sizeMode = SizeMode.Exact

    /**
     * Provide widget content.
     *
     * This runs in a coroutine context, so database queries are safe.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WeekWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Get data via Hilt EntryPoint (Glance widgets can't use standard @Inject)
        val entryPoint = EntryPointAccessors.fromApplication(context, WeekWidgetEntryPoint::class.java)
        val repository = entryPoint.widgetDataRepository()
        val weekEvents = repository.getWeekEvents()

        // Get display preferences
        val dataStore = KashCalDataStore(context)
        val showEventEmojis = dataStore.showEventEmojis.first()
        val maxEventsPerDay = dataStore.widgetMaxEventsPerDay.first()

        // Get time format preference
        val timeFormatPref = dataStore.getTimeFormat()
        val is24HourDevice = android.text.format.DateFormat.is24HourFormat(context)
        val timePattern = DateTimeUtils.getTimePattern(timeFormatPref, is24HourDevice)

        provideContent {
            GlanceTheme {
                WeekWidgetContent(
                    weekEvents = weekEvents,
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern,
                    maxEventsPerDay = maxEventsPerDay
                )
            }
        }
    }
}
