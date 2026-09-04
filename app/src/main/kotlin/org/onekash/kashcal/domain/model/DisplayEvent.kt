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
    /**
     * RFC 5545 CATEGORIES (tags). Room events carry their own; device-calendar
     * events carry theirs from the sync-adapter `categories` extended property.
     */
    val categories: List<String>
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
    /**
     * True when the current user has declined this event. Resolved per-source:
     * Room reads it from the [Account.matchesAttendee]-matched ATTENDEE row
     * (PARTSTAT=DECLINED) at composite-repository assembly time; Device
     * reads it from `instance.selfAttendeeStatus`. Used by the display layer
     * to dim and strike-through declined events when the "Show declined
     * events" preference is on, and to filter them out when it's off.
     */
    val isDeclinedByMe: Boolean

    /**
     * True when the whole event has been cancelled (RFC 5545 STATUS:CANCELLED),
     * e.g. an organizer called off a meeting or the event was cancelled from
     * another client. Unlike [isDeclinedByMe] this is not per-attendee and does
     * not require any cross-referencing — it reads the already-loaded status.
     * Used by the display layer to dim and strike-through cancelled events.
     * Cancelled events are always shown (never filtered out) so the user can
     * see the meeting was called off.
     */
    val isCancelled: Boolean

    /**
     * Stable identity for a single on-screen occurrence. For Room events the id
     * is the series id shared by every occurrence, so the occurrence start is
     * appended to keep distinct occurrences distinguishable — the pair is unique
     * by the `(event_id, start_ts)` unique index on the occurrences table; for
     * device events the instance id (a provider row id) already identifies one
     * occurrence. Lets a list keep each occurrence's identity across a re-sort
     * (e.g. a Compose `key()`) rather than matching by position.
     */
    val stableKey: String

    /** Room event with full Event + Occurrence data */
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

    /** Device calendar event from CalendarProvider */
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
    transp = availabilityIntToTransp(instance.availability),
    categories = categories
)

/**
 * Build a synthetic [Event] from a device calendar event so it can flow
 * through the share-card pipeline (singleOccurrenceForShare → IcsExporter)
 * unchanged. The Event is never persisted to Room — id and calendarId are
 * 0 and the UID is fresh so the recipient sees a brand-new insert.
 *
 * Parity with the Room share path: AVAILABILITY → TRANSP, STATUS, per-event
 * color override, and reminders all flow through, so sharing a device event
 * produces the same .ics content the user would get from a Room event with
 * the same field values.
 *
 * Series-membership state and server-side fields (rrule, originalEventId,
 * rawIcal, organizer*, caldavUrl, etag, extraProperties) are left null —
 * singleOccurrenceForShare synthesizes a single occurrence regardless, and
 * we never read attendee or organizer data from CalendarProvider here, so
 * PII can't leak in.
 *
 * Empty description / location strings (CalendarProvider exposes missing
 * values as `""` via `cursor.getString(...).orEmpty()`) are normalized to
 * null so the ICS generator skips the property entirely instead of emitting
 * a literal blank line.
 */
fun DisplayEvent.Device.toEventForShareCard(): Event = Event(
    uid = UUID.randomUUID().toString(),
    calendarId = 0,
    title = title,
    location = location.ifEmpty { null },
    description = description.ifEmpty { null },
    startTs = startTs,
    endTs = endTs,
    // Android's CalendarProvider stores all-day BEGIN as UTC midnight
    // regardless of the row's EVENT_TIMEZONE column. Force "UTC" on the
    // synthetic Event so normalizeAllDay reads the timestamp in the same
    // zone CalendarProvider wrote it — otherwise sync adapters that
    // populate EVENT_TIMEZONE with a non-UTC IANA id (some Exchange/Outlook
    // bridges) cause the emitted DTSTART/DTEND to drift by a day.
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
 * Build share text for a device calendar event.
 * Same format as EventQuickViewSheet share: title, date/time, location, footer.
 *
 * Caller supplies all user-facing labels so this helper stays Context-free
 * and unit-testable.
 *
 * @param timePattern Time format pattern (e.g., "h:mm a" or "HH:mm")
 * @param allDayLabel Parenthesized label appended to all-day dates (e.g., "(All day)")
 * @param locationPrefix Label prefixed to the location line (e.g., "Location: ")
 * @param footer Trailing line appended after a blank line
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
        appendLine("$locationPrefix$location")
    }

    appendLine()
    appendLine(footer)
}
