package org.onekash.kashcal.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.data.contacts.ContactEventTitleFormatter
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for fetching widget data.
 *
 * Queries today's events for the agenda widget, respecting calendar visibility.
 * Events are sorted with all-day events first, then timed events by start time.
 * Past events are marked for visual differentiation (grayed/strikethrough).
 *
 * Uses [DisplayEventRepository] to merge Room + device calendar events.
 */
@Singleton
class WidgetDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displayEventRepository: DisplayEventRepository
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
        val isPast: Boolean,
        val isDeviceEvent: Boolean,
        val startDay: Int,
        val isCancelled: Boolean = false,
        val isFree: Boolean = false,
        val endDay: Int = startDay
    ) {
        /** Multi-day events span more than one day cell and render as continuous bars. */
        val isMultiDay: Boolean get() = startDay != endDay

        /** Stable identity across the day buckets the event appears in (for span dedup). */
        val spanKey: String get() = "$eventId:$occurrenceStartTs"
    }

    /**
     * Get today's events for the widget.
     *
     * @return List of events for today, sorted with all-day events first, then by start time.
     *         Past events are marked with isPast=true.
     */
    suspend fun getTodayEvents(): List<WidgetEvent> {
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)

        val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(todayCode, todayCode)
        val todayEvents = eventsMap[todayCode] ?: return emptyList()

        return todayEvents.map { toWidgetEvent(it) }
            .sortedWith(compareBy({ !it.isAllDay }, { it.startTs }))
    }

    /**
     * Get events for the next 7 days (today + 6 days).
     *
     * Multi-day events appear on each day they span within the 7-day window.
     * Events are sorted within each day: all-day events first, then timed by start time.
     *
     * @return Map of dayCode (YYYYMMDD) to list of events for that day.
     *         Always returns exactly 7 entries, one for each day.
     */
    suspend fun getWeekEvents(): Map<Int, List<WidgetEvent>> {
        // Generate 7 day codes: today, tomorrow, ..., +6 days
        val dayCodes = (0..6).map { offset ->
            val date = LocalDate.now().plusDays(offset.toLong())
            date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        }

        val startDayCode = dayCodes.first()
        val endDayCode = dayCodes.last()

        val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)

        // Build result with exactly 7 entries, sorted within each day
        return dayCodes.associateWith { dayCode ->
            eventsMap[dayCode].orEmpty()
                .map { toWidgetEvent(it) }
                .sortedWith(compareBy({ !it.isAllDay }, { it.startTs }))
        }
    }

    /**
     * Get events for an arbitrary day code range.
     *
     * Used by MonthWidget to fetch events for the full calendar grid range
     * (computed via [MonthGrid.toDayCodeRange]).
     *
     * @param startDayCode Start of range in YYYYMMDD format
     * @param endDayCode End of range in YYYYMMDD format
     * @return Map of dayCode to list of events for that day.
     *         Only days with events are included (no empty-day entries).
     */
    suspend fun getEventsInRange(startDayCode: Int, endDayCode: Int): Map<Int, List<WidgetEvent>> {
        val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)

        return eventsMap.mapValues { (_, displayEvents) ->
            displayEvents
                .map { toWidgetEvent(it) }
                .sortedWith(compareBy({ !it.isAllDay }, { it.startTs }))
        }
    }

    /**
     * Convert a [DisplayEvent] to a [WidgetEvent] for widget rendering.
     */
    private fun toWidgetEvent(displayEvent: DisplayEvent): WidgetEvent {
        return WidgetEvent(
            eventId = when (displayEvent) {
                is DisplayEvent.Room -> displayEvent.event.id
                is DisplayEvent.Device -> displayEvent.instance.eventId
            },
            occurrenceStartTs = displayEvent.startTs,
            title = when (displayEvent) {
                is DisplayEvent.Room -> ContactEventTitleFormatter.format(
                    displayEvent.event, displayEvent.startTs, context.resources
                )
                is DisplayEvent.Device -> displayEvent.title
            },
            startTs = displayEvent.startTs,
            endTs = displayEvent.endTs,
            isAllDay = displayEvent.isAllDay,
            calendarColor = (displayEvent.eventColor ?: displayEvent.calendarColor).takeIf { it != 0 } ?: DEFAULT_CALENDAR_COLOR,
            isPast = DateTimeUtils.isEventPast(displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay),
            isDeviceEvent = displayEvent is DisplayEvent.Device,
            startDay = displayEvent.startDay,
            isCancelled = displayEvent.isCancelled,
            isFree = displayEvent.isFree,
            endDay = displayEvent.endDay
        )
    }

    companion object {
        /** Default calendar color (Material Blue 500) */
        private const val DEFAULT_CALENDAR_COLOR = 0xFF2196F3.toInt()
    }
}
