package org.onekash.kashcal.domain.model

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Unified display type for events from any source.
 *
 * Sealed interface with [Room] and [Device] variants. Common properties
 * (title, times, colors) are delegated to the underlying data so the UI
 * doesn't need to know which data source an event came from.
 *
 * [Room] wraps KashCal's own Event + Occurrence + Calendar from Room DB.
 * [Device] wraps a DeviceCalendarInstance from Android's CalendarProvider.
 */
@Immutable
sealed interface DisplayEvent {
    val title: String
    val description: String?
    val location: String?
    val startTs: Long
    val endTs: Long
    val startDay: Int
    val endDay: Int
    val isAllDay: Boolean
    val hasRrule: Boolean
    /** The calendar's own color. Carries calendar identity — not overridden by per-event color. */
    val calendarColor: Int
    /** User-picked per-event color override. Null if no override set. */
    val eventColor: Int?
    val calendarName: String
    val isReadOnly: Boolean
    val isFree: Boolean

    /** Room event with full Event + Occurrence data */
    @Immutable
    data class Room(
        val event: Event,
        val occurrence: Occurrence,
        val calendar: Calendar?
    ) : DisplayEvent {
        override val title get() = event.title
        override val description get() = event.description
        override val location get() = event.location
        override val startTs get() = occurrence.startTs
        override val endTs get() = occurrence.endTs
        override val startDay get() = occurrence.startDay
        override val endDay get() = occurrence.endDay
        override val isAllDay get() = event.isAllDay
        override val hasRrule get() = event.rrule != null
        override val calendarColor get() = calendar?.color ?: 0
        override val eventColor get() = event.color
        override val calendarName get() = calendar?.displayName.orEmpty()
        override val isReadOnly get() = calendar?.isReadOnly ?: false
        override val isFree get() = event.transp == "TRANSPARENT"
    }

    /** Device calendar event from CalendarProvider */
    @Immutable
    data class Device(val instance: DeviceCalendarInstance) : DisplayEvent {
        override val title get() = instance.title
        override val description get() = instance.description
        override val location get() = instance.location
        override val startTs get() = instance.startTs
        override val endTs get() = instance.endTs
        override val startDay get() = instance.startDay
        override val endDay get() = instance.endDay
        override val isAllDay get() = instance.isAllDay
        override val hasRrule get() = instance.hasRrule
        override val calendarColor get() = instance.calendarColor
        override val eventColor get() = instance.eventColor
        override val calendarName get() = instance.calendarDisplayName
        override val isReadOnly get() = !instance.isWritable
        override val isFree get() = instance.availability == 1

        /** RFC 5545 RRULE string, null for non-recurring events. */
        val rrule: String? get() = instance.rrule

        /** Reminder minutes before event (e.g., [15, 60] = 15 min and 1 hour before). */
        val reminders: List<Int> get() = instance.reminders

        /** True if this instance is part of a recurring series (regular or exception occurrence). */
        val isPartOfRecurringSeries: Boolean get() = instance.isPartOfRecurringSeries
    }
}

/**
 * Create a temporary [Event] from a device calendar event for use with EventFormSheet duplicate.
 * The form handles calendar selection (falls back to default if source isn't writable).
 */
fun DisplayEvent.Device.toEventForDuplicate(): Event = Event(
    uid = UUID.randomUUID().toString(),
    calendarId = 0,
    title = title,
    location = location,
    description = description,
    startTs = startTs,
    endTs = endTs,
    isAllDay = isAllDay,
    dtstamp = System.currentTimeMillis(),
    transp = when (instance.availability) {
        1 -> "TRANSPARENT"
        else -> "OPAQUE"
    }
)

/**
 * Build share text for a device calendar event.
 * Same format as EventQuickViewSheet share: title, date/time, location, footer.
 *
 * @param timePattern Time format pattern (e.g., "h:mm a" or "HH:mm")
 */
fun DisplayEvent.Device.buildShareText(timePattern: String = "h:mm a"): String = buildString {
    appendLine(title)

    val dateFormat = SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEMMMd"), Locale.getDefault())
    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())

    if (isAllDay) {
        val utcDateFormat = SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEMMMd"), Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val startStr = utcDateFormat.format(Date(startTs))
        val endStr = utcDateFormat.format(Date(endTs))
        if (startStr != endStr) {
            appendLine("$startStr - $endStr (All day)")
        } else {
            appendLine("$startStr (All day)")
        }
    } else {
        // Timed event
        val startDate = Date(startTs)
        val endDate = Date(endTs)
        val startDateStr = dateFormat.format(startDate)
        val endDateStr = dateFormat.format(endDate)
        if (startDateStr != endDateStr) {
            // Multi-day timed event: show both dates
            appendLine("$startDateStr ${timeFormat.format(startDate)} - $endDateStr ${timeFormat.format(endDate)}")
        } else {
            // Same-day timed event: show date once
            appendLine("$startDateStr ${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}")
        }
    }

    if (location.isNotEmpty()) {
        appendLine("Location: $location")
    }

    appendLine()
    appendLine("Shared from KashCal")
}
