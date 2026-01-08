package org.onekash.kashcal.widget

import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.util.DateTimeUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for fetching widget data.
 *
 * Queries today's events for the agenda widget, respecting calendar visibility.
 * Events are sorted with timed events first (by start time), all-day events last.
 * Past events are marked for visual differentiation (grayed/strikethrough).
 */
@Singleton
class WidgetDataRepository @Inject constructor(
    private val database: KashCalDatabase
) {
    /**
     * Data class representing an event for widget display.
     */
    data class WidgetEvent(
        val eventId: Long,
        val occurrenceStartTs: Long,
        val title: String,
        val startTs: Long,
        val endTs: Long,
        val isAllDay: Boolean,
        val calendarColor: Int,
        val isPast: Boolean
    )

    /**
     * Get today's events for the widget.
     *
     * @return List of events for today, sorted by time with all-day events at the end.
     *         Past events are marked with isPast=true.
     */
    suspend fun getTodayEvents(): List<WidgetEvent> {
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)

        // Get visible calendars
        val visibleCalendarIds = database.calendarsDao()
            .getAllOnce()
            .filter { it.isVisible }
            .map { it.id }
            .toSet()

        if (visibleCalendarIds.isEmpty()) {
            return emptyList()
        }

        // Get today's occurrences
        val occurrences = database.occurrencesDao().getForDayOnce(todayCode)
            .filter { it.calendarId in visibleCalendarIds }

        if (occurrences.isEmpty()) return emptyList()

        // Batch load events (1 query instead of N)
        val eventIds = occurrences.map { it.exceptionEventId ?: it.eventId }.distinct()
        val eventsMap = database.eventsDao().getByIds(eventIds).associateBy { it.id }

        // Batch load calendars (1 query instead of N)
        val calendarIds = occurrences.map { it.calendarId }.distinct()
        val calendarsMap = database.calendarsDao().getByIds(calendarIds).associateBy { it.id }

        // Map to WidgetEvent with event details
        return occurrences.mapNotNull { occ ->
            val eventId = occ.exceptionEventId ?: occ.eventId
            val event = eventsMap[eventId] ?: return@mapNotNull null
            val calendar = calendarsMap[occ.calendarId]

            WidgetEvent(
                eventId = eventId,
                occurrenceStartTs = occ.startTs,
                title = event.title,
                startTs = occ.startTs,
                endTs = occ.endTs,
                isAllDay = event.isAllDay,
                calendarColor = calendar?.color ?: DEFAULT_CALENDAR_COLOR,
                isPast = occ.endTs < now
            )
        }.sortedWith(
            // Sort: timed events by start time first, all-day events at the end
            compareBy({ it.isAllDay }, { it.startTs })
        )
    }

    companion object {
        /** Default calendar color (Material Blue 500) */
        private const val DEFAULT_CALENDAR_COLOR = 0xFF2196F3.toInt()
    }
}
