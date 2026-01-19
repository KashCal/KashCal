package org.onekash.kashcal.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.preferences.KashCalDataStore

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
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Get data from database
        val database = KashCalDatabase.getInstance(context)
        val repository = WidgetDataRepository(database)
        val weekEvents = repository.getWeekEvents()

        // Get display preferences
        val dataStore = KashCalDataStore(context)
        val showEventEmojis = dataStore.showEventEmojis.first()

        provideContent {
            GlanceTheme {
                WeekWidgetContent(
                    weekEvents = weekEvents,
                    showEventEmojis = showEventEmojis
                )
            }
        }
    }
}
