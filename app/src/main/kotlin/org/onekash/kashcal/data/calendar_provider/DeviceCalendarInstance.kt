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
    val calendarId: Long,
    val calendarDisplayName: String,
    val displayColor: Int,
    val status: Int,
    val availability: Int,
    val hasAlarm: Boolean,
    val selfAttendeeStatus: Int,
    val isWritable: Boolean,
)
