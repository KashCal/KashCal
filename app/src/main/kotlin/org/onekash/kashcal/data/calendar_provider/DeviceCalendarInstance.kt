package org.onekash.kashcal.data.calendar_provider

/**
 * A single calendar instance from the device's CalendarProvider.
 * Maps to one row from CalendarContract.Instances.
 *
 * Instances are pre-expanded occurrences of events — for recurring events,
 * there is one instance per occurrence in the queried range.
 */
data class DeviceCalendarInstance(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val description: String,
    val location: String,
    val startTs: Long,
    val endTs: Long,
    val startDay: Int,
    val endDay: Int,
    val isAllDay: Boolean,
    val hasRrule: Boolean,
    /** RFC 5545 RRULE string, null for non-recurring events. */
    val rrule: String?,
    /** Reminder minutes before event (e.g., [15, 60] = 15 min and 1 hour before). */
    val reminders: List<Int>,
    val calendarId: Long,
    val calendarDisplayName: String,
    val displayColor: Int,
    val status: Int,
    val availability: Int,
    val hasAlarm: Boolean,
    val selfAttendeeStatus: Int,
    val isWritable: Boolean,
    /** Master event ID if this is a modified occurrence (exception), null otherwise. */
    val originalId: Long?,
    /** Original occurrence time if this is a modified occurrence, null otherwise. */
    val originalInstanceTime: Long?,
    /** Event timezone from the master event. For exception-specific timezone, query Events table. */
    val timezone: String?,
) {
    /** True if this instance is part of a recurring event series (regular or exception occurrence). */
    val isPartOfRecurringSeries: Boolean get() = hasRrule || originalId != null
}
