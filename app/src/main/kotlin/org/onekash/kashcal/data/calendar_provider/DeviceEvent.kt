package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * A row of CalendarProvider's Events table.
 *
 * Unlike [DeviceCalendarInstance] it carries the recurrence as stored (RRULE, DURATION, RDATE,
 * EXDATE, EXRULE) and the exception link to the master.
 */
@Immutable
data class DeviceEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val startTs: Long,
    /** End in epoch ms; null for a series, which uses [duration]. */
    val endTs: Long?,
    /** RFC 5545 duration of a series' occurrences, e.g. "PT1H30M". */
    val duration: String?,
    val isAllDay: Boolean,
    /** RFC 5545 RRULE, e.g. "FREQ=WEEKLY;BYDAY=MO,WE,FR". */
    val rrule: String?,
    /** RFC 5545 RDATE: extra occurrence dates. */
    val rdate: String?,
    /** RFC 5545 EXDATE: excluded occurrence dates. */
    val exdate: String?,
    /** RFC 5545 EXRULE: excluded occurrence rule. */
    val exrule: String?,
    /** Zone id, e.g. "America/New_York"; the device zone when the row has none. */
    val timezone: String,
    /** The master's id for an exception, null otherwise. */
    val originalId: Long?,
    /** The start of the occurrence an exception replaces, null otherwise. */
    val originalInstanceTime: Long?,
    /** STATUS_TENTATIVE = 0, STATUS_CONFIRMED = 1, STATUS_CANCELED = 2. */
    val status: Int,
    /** AVAILABILITY_BUSY = 0, AVAILABILITY_FREE = 1, AVAILABILITY_TENTATIVE = 2. */
    val availability: Int,
    /** ACCESS_DEFAULT = 0, ACCESS_CONFIDENTIAL = 1, ACCESS_PRIVATE = 2, ACCESS_PUBLIC = 3. */
    val accessLevel: Int,
    /** The calendar's color (`Calendars.CALENDAR_COLOR`). */
    val calendarColor: Int?,
    /** `Events.EVENT_COLOR`, the event's color override. */
    val eventColor: Int?,
    /**
     * Tags (RFC 5545 CATEGORIES) from the event's [EXTNAME_CATEGORIES] extended property, read
     * in a separate query since the Events projection has no extended-property columns. Empty
     * when it has none.
     */
    val categories: List<String> = emptyList()
)
