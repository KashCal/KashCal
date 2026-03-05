package org.onekash.kashcal.domain.model

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
    val calendarColor: Int
    val calendarName: String
    val isReadOnly: Boolean

    /** Room event with full Event + Occurrence data */
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
        override val calendarName get() = calendar?.displayName ?: ""
        override val isReadOnly get() = calendar?.isReadOnly ?: false
    }

    /** Device calendar event from CalendarProvider */
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
        override val calendarColor get() = instance.displayColor
        override val calendarName get() = instance.calendarDisplayName
        override val isReadOnly get() = !instance.isWritable
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
    dtstamp = System.currentTimeMillis()
)

/**
 * Build share text for a device calendar event.
 * Same format as EventQuickViewSheet share: title, date/time, location, footer.
 *
 * @param timePattern Time format pattern (e.g., "h:mm a" or "HH:mm")
 */
fun DisplayEvent.Device.buildShareText(timePattern: String = "h:mm a"): String = buildString {
    appendLine(title)

    val dateFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())

    if (isAllDay) {
        val utcDateFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).apply {
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

    if (!location.isNullOrEmpty()) {
        appendLine("Location: $location")
    }

    appendLine()
    appendLine("Shared from KashCal")
}
