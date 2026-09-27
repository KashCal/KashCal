package org.onekash.kashcal.domain.model

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.mapper.availabilityIntToTransp
import org.onekash.kashcal.domain.mapper.statusIntToString
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Presents an event from either source to the UI without it knowing which source it came from.
 *
 * [Room] wraps a Room [Event], [Occurrence] and [Calendar]; [Device] wraps a
 * [DeviceCalendarInstance] from Android's CalendarProvider.
 */
@Immutable
sealed interface DisplayEvent {
    val title: String
    val description: String?
    val location: String?
    /**
     * RFC 5545 CATEGORIES (tags). A device event's come from the sync-adapter `categories`
     * extended property.
     */
    val categories: List<String>
    val startTs: Long
    val endTs: Long
    val startDay: Int
    val endDay: Int
    val isAllDay: Boolean
    val hasRrule: Boolean
    /** The calendar's own color, which identifies the calendar; [eventColor] never replaces it. */
    val calendarColor: Int
    /** Per-event color override, or null if none is set. */
    val eventColor: Int?
    val calendarName: String
    val isReadOnly: Boolean
    val isFree: Boolean
    /**
     * True when the current user has declined this event. A Room event takes it from the
     * PARTSTAT=DECLINED ATTENDEE row that [Account.matchesAttendee] matches, set when
     * `DisplayEventRepository` assembles it; a device event reads `instance.selfAttendeeStatus`.
     * With "Show declined events" on, the UI dims and strikes these through; with it off, the
     * repository and the device query drop them before they reach the UI.
     */
    val isDeclinedByMe: Boolean

    /**
     * True when the whole event is cancelled (RFC 5545 STATUS:CANCELLED), for example an
     * organizer called off a meeting. Unlike [isDeclinedByMe] it isn't per attendee. The UI dims
     * and strikes these through and never hides them, so the user sees the meeting was called
     * off. The device read skips a cancelled device exception, which marks a deleted occurrence.
     */
    val isCancelled: Boolean

    /**
     * Identifies one on-screen occurrence, for example as a Compose `key()`, so a list keeps
     * identity across a re-sort. A Room event id is shared by every occurrence of a series, so
     * the occurrence start is appended; the pair is unique by the occurrences table's
     * `(event_id, start_ts)` index. A device instance id already names one occurrence.
     */
    val stableKey: String

    /** An occurrence of a Room event. */
    @Immutable
    data class Room(
        val event: Event,
        val occurrence: Occurrence,
        val calendar: Calendar?,
        override val isDeclinedByMe: Boolean = false
    ) : DisplayEvent {
        override val title get() = event.title
        override val description get() = event.description
        override val location get() = event.location
        override val categories get() = event.categories.orEmpty()
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
        override val isCancelled get() = event.status == "CANCELLED"
        override val stableKey get() = "room:${event.id}:${occurrence.startTs}"
    }

    /** An Instances row of a device event from CalendarProvider. */
    @Immutable
    data class Device(val instance: DeviceCalendarInstance) : DisplayEvent {
        override val title get() = instance.title
        override val description get() = instance.description
        override val location get() = instance.location
        override val categories get() = instance.categories
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
        override val isDeclinedByMe get() = instance.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED
        override val isCancelled get() = instance.status == Events.STATUS_CANCELED
        override val stableKey get() = "device:${instance.instanceId}"

        /** RFC 5545 RRULE, or null for an event that doesn't repeat. */
        val rrule: String? get() = instance.rrule

        /** Reminder offsets in minutes before the start, e.g. [15, 60]. */
        val reminders: List<Int> get() = instance.reminders

        /** True for any occurrence of a recurring series, including an exception. */
        val isPartOfRecurringSeries: Boolean get() = instance.isPartOfRecurringSeries
    }
}

/**
 * Builds an unsaved [Event] copy of this device event for the duplicate form.
 *
 * `calendarId` is 0 because device calendar ids aren't Room ids; the form picks the calendar
 * from the source device calendar id, falling back to the default if it isn't writable.
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
    transp = availabilityIntToTransp(instance.availability),
    categories = categories
)

/**
 * Builds an unsaved [Event] from this device event for the share-card flow, which handles it
 * like a Room event.
 *
 * `id` and `calendarId` are 0 and the UID is fresh, so the recipient sees a new event.
 * AVAILABILITY (as TRANSP), STATUS, the event color and reminders carry over, so the .ics
 * matches what a Room event with the same fields produces. Series and server fields (rrule,
 * organizer, caldavUrl, etag and the like) stay null: `singleOccurrenceForShare` builds one
 * occurrence regardless, and no attendee or organizer data is read from CalendarProvider, so no
 * PII leaks into the share.
 *
 * The device read turns a missing description or location into `""`; both become null here so
 * the ICS generator omits the property instead of writing an empty one.
 */
fun DisplayEvent.Device.toEventForShareCard(): Event = Event(
    uid = UUID.randomUUID().toString(),
    calendarId = 0,
    title = title,
    location = location.ifEmpty { null },
    description = description.ifEmpty { null },
    startTs = startTs,
    endTs = endTs,
    // CalendarProvider stores an all-day BEGIN as UTC midnight whatever the row's
    // EVENT_TIMEZONE. "UTC" makes normalizeAllDay read it in that zone; a sync adapter that
    // writes a non-UTC IANA id there (some Exchange bridges do) would otherwise shift the
    // emitted DTSTART/DTEND by a day.
    timezone = if (isAllDay) "UTC" else instance.timezone,
    isAllDay = isAllDay,
    status = statusIntToString(instance.status),
    transp = availabilityIntToTransp(instance.availability),
    color = eventColor,
    reminders = instance.reminders.takeIf { it.isNotEmpty() }
        ?.map { ContactEventUtils.minutesToIsoDuration(it) },
    dtstamp = System.currentTimeMillis(),
)

/**
 * Builds the plain-text share of a device event: title, date and time, location, footer.
 * The format matches the Room event share from `EventQuickViewSheet`.
 *
 * The caller passes every user-facing label, so this needs no Context.
 *
 * @param timePattern time pattern, e.g. "h:mm a" or "HH:mm".
 * @param allDayLabel label put in parentheses after all-day dates, e.g. "All day".
 * @param locationPrefix text before the location, e.g. "Location: ".
 * @param footer last line, after a blank line.
 */
fun DisplayEvent.Device.buildShareText(
    timePattern: String,
    allDayLabel: String,
    locationPrefix: String,
    footer: String
): String = buildString {
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
            appendLine("$startStr - $endStr ($allDayLabel)")
        } else {
            appendLine("$startStr ($allDayLabel)")
        }
    } else {
        val startDate = Date(startTs)
        val endDate = Date(endTs)
        val startDateStr = dateFormat.format(startDate)
        val endDateStr = dateFormat.format(endDate)
        if (startDateStr != endDateStr) {
            appendLine("$startDateStr ${timeFormat.format(startDate)} - $endDateStr ${timeFormat.format(endDate)}")
        } else {
            appendLine("$startDateStr ${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}")
        }
    }

    if (location.isNotEmpty()) {
        appendLine("$locationPrefix$location")
    }

    appendLine()
    appendLine(footer)
}
