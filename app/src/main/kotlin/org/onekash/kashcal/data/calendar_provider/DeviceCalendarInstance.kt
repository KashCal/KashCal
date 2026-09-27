package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * One row of CalendarProvider's Instances view: one occurrence of an event, so a series has one
 * per occurrence in the queried range.
 */
@Immutable
data class DeviceCalendarInstance(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val description: String,
    val location: String,
    val startTs: Long,
    /**
     * Inclusive end in UTC ms. For an all-day event it's the provider's exclusive end minus
     * 1 ms, the last millisecond of the last day, matching Room's `Event.endTs`; for a timed
     * event it's the provider's end.
     */
    val endTs: Long,
    val startDay: Int,
    val endDay: Int,
    val isAllDay: Boolean,
    val hasRrule: Boolean,
    /** RFC 5545 RRULE, or null for a one-off. */
    val rrule: String?,
    /** Minutes before the start of each reminder, e.g. [15, 60]. */
    val reminders: List<Int>,
    val calendarId: Long,
    val calendarDisplayName: String,
    /** `Calendars.CALENDAR_COLOR`, the calendar's identity color. */
    val calendarColor: Int,
    /** `Events.EVENT_COLOR`, or null when the event has no override. */
    val eventColor: Int?,
    val status: Int,
    val availability: Int,
    val hasAlarm: Boolean,
    val selfAttendeeStatus: Int,
    val isWritable: Boolean,
    /** The master's id for an exception, null otherwise. */
    val originalId: Long?,
    /** The start of the occurrence an exception replaces, null otherwise. */
    val originalInstanceTime: Long?,
    /** The event row's zone: an exception's own, otherwise the master's. */
    val timezone: String?,
    /**
     * DTSTART of the event row behind this instance (`Instances.DTSTART`), distinct from the
     * per-instance [startTs]. For an occurrence of a series it's the series' first occurrence.
     * The provider joins each instance to its own event row, so for an exception it's the
     * exception's own start, not the master's. Anchors the first-occurrence rule for the
     * drag-to-reschedule and delete scope options.
     *
     * Has no default, so every construction site sets it.
     */
    val eventStartTs: Long,
    /**
     * Tags (RFC 5545 CATEGORIES) from the event's [EXTNAME_CATEGORIES] extended property. Empty
     * when it has none or the read failed. Filled after the Instances query, like [reminders].
     */
    val categories: List<String> = emptyList(),
) {
    /**
     * True for an occurrence of a series or an exception. Checks RRULE (an occurrence),
     * ORIGINAL_ID (an exception) and ORIGINAL_INSTANCE_TIME (an exception from a sync adapter
     * that sets the time but not ORIGINAL_ID).
     */
    val isPartOfRecurringSeries: Boolean get() = hasRrule || originalId != null || originalInstanceTime != null
}
