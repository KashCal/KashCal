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
import org.onekash.kashcal.util.DateTimeUtils
import java.util.Locale

/**
 * Today's Agenda widget showing events for the current day.
 *
 * Features:
 * - Shows today's date in header
 * - Lists upcoming events with time and calendar color
 * - Past events shown grayed out with strikethrough
 * - Tap event to open quick view
 * - Tap empty state to create new event
 *
 * Updates:
 * - On event create/update/delete
 * - On sync completion
 * - At midnight (new day)
 * - Periodically (every 30 minutes)
 */
class AgendaWidget : GlanceAppWidget() {

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
        val events = repository.getTodayEvents()

        // Get display preferences
        val dataStore = KashCalDataStore(context)
        val showEventEmojis = dataStore.showEventEmojis.first()

        // Get time format preference
        val timeFormatPref = dataStore.getTimeFormat()
        val is24HourDevice = android.text.format.DateFormat.is24HourFormat(context)
        val timePattern = DateTimeUtils.getTimePattern(timeFormatPref, is24HourDevice)

        // Format current date
        val currentDate = formatCurrentDate()

        provideContent {
            GlanceTheme {
                AgendaWidgetContent(
                    events = events,
                    currentDate = currentDate,
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern
                )
            }
        }
    }

    /**
     * Format the current date for display in the widget header.
     * Example: "Thursday, Jan 2"
     */
    private fun formatCurrentDate(): String {
        return DateTimeUtils.formatEventDate(
            timestampMs = System.currentTimeMillis(),
            isAllDay = false,
            pattern = "EEEE, MMM d"
        )
    }
}
