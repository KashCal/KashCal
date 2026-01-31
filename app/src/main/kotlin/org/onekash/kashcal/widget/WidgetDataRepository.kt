package org.onekash.kashcal.widget

import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
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
                isPast = DateTimeUtils.isEventPast(occ.endTs, occ.endDay, event.isAllDay)
            )
        }.sortedWith(
            // Sort: timed events by start time first, all-day events at the end
            compareBy({ it.isAllDay }, { it.startTs })
        )
    }

    /**
     * Get events for the next 7 days (today + 6 days).
     *
     * Multi-day events appear on each day they span within the 7-day window.
     * Events are sorted within each day: timed events by start time, all-day at end.
     *
     * @return Map of dayCode (YYYYMMDD) to list of events for that day.
     *         Always returns exactly 7 entries, one for each day.
     */
    suspend fun getWeekEvents(): Map<Int, List<WidgetEvent>> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        // Generate 7 day codes: today, tomorrow, ..., +6 days
        // dayCode format: YYYYMMDD (e.g., 20250119)
        val dayCodes = (0..6).map { offset ->
            val date = LocalDate.now().plusDays(offset.toLong())
            date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        }

        // Get visible calendars
        val visibleCalendarIds = database.calendarsDao()
            .getAllOnce()
            .filter { it.isVisible }
            .map { it.id }
            .toSet()

        if (visibleCalendarIds.isEmpty()) {
            return dayCodes.associateWith { emptyList() }
        }

        // Calculate timestamp range for 7 days
        val startTs = LocalDate.now()
            .atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val endTs = LocalDate.now().plusDays(7)
            .atStartOfDay(zone)
            .toInstant().toEpochMilli()

        // Query occurrences using existing range method
        val occurrences = database.occurrencesDao()
            .getInRangeOnce(startTs, endTs)
            .filter { it.calendarId in visibleCalendarIds }

        if (occurrences.isEmpty()) {
            return dayCodes.associateWith { emptyList() }
        }

        // Batch load events and calendars
        val eventIds = occurrences.map { it.exceptionEventId ?: it.eventId }.distinct()
        val eventsMap = database.eventsDao().getByIds(eventIds).associateBy { it.id }

        val calendarIds = occurrences.map { it.calendarId }.distinct()
        val calendarsMap = database.calendarsDao().getByIds(calendarIds).associateBy { it.id }

        // Group by day code (handle multi-day events appearing on each day)
        val eventsByDay = dayCodes.associateWith { mutableListOf<WidgetEvent>() }.toMutableMap()
        val dayCodesSet = dayCodes.toSet()

        occurrences.forEach { occ ->
            val eventId = occ.exceptionEventId ?: occ.eventId
            val event = eventsMap[eventId] ?: return@forEach
            val calendar = calendarsMap[occ.calendarId]

            // Calculate which days this occurrence spans
            val occStartDayCode = DateTimeUtils.eventTsToDayCode(occ.startTs, event.isAllDay)
            val occEndDayCode = DateTimeUtils.eventTsToDayCode(occ.endTs, event.isAllDay)

            // Add event to each day it spans within our 7-day window
            for (dayCode in dayCodesSet) {
                if (dayCode in occStartDayCode..occEndDayCode) {
                    eventsByDay[dayCode]!!.add(
                        WidgetEvent(
                            eventId = eventId,
                            occurrenceStartTs = occ.startTs,
                            title = event.title,
                            startTs = occ.startTs,
                            endTs = occ.endTs,
                            isAllDay = event.isAllDay,
                            calendarColor = calendar?.color ?: DEFAULT_CALENDAR_COLOR,
                            isPast = DateTimeUtils.isEventPast(occ.endTs, occ.endDay, event.isAllDay)
                        )
                    )
                }
            }
        }

        // Sort each day's events: timed events by start time, all-day at end
        return eventsByDay.mapValues { (_, events) ->
            events.sortedWith(compareBy({ it.isAllDay }, { it.startTs }))
        }
    }

    companion object {
        /** Default calendar color (Material Blue 500) */
        private const val DEFAULT_CALENDAR_COLOR = 0xFF2196F3.toInt()
    }
}
