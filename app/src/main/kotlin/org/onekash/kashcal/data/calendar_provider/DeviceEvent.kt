package org.onekash.kashcal.data.calendar_provider

/**
 * Full event data from CalendarProvider's Events table.
 *
 * Unlike [DeviceCalendarInstance] (from Instances table), this contains
 * complete event information needed for editing:
 * - RRULE string (not just hasRrule boolean)
 * - Duration (for recurring events)
 * - RDATE/EXDATE/EXRULE
 * - Original event ID for exceptions
 *
 * Used by EventFormSheet when editing device events.
 */
data class DeviceEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val startTs: Long,
    /** End timestamp (null for recurring events that use duration). */
    val endTs: Long?,
    /** RFC 5545 duration string for recurring events (e.g., "PT1H30M"). */
    val duration: String?,
    val isAllDay: Boolean,
    /** RFC 5545 RRULE string (e.g., "FREQ=WEEKLY;BYDAY=MO,WE,FR"). */
    val rrule: String?,
    /** RFC 5545 RDATE string (additional recurrence dates). */
    val rdate: String?,
    /** RFC 5545 EXDATE string (exception dates). */
    val exdate: String?,
    /** RFC 5545 EXRULE string (exception rules). */
    val exrule: String?,
    /** IANA timezone ID (e.g., "America/New_York"). */
    val timezone: String,
    /** Master event ID if this is an exception, null otherwise. */
    val originalId: Long?,
    /** Original occurrence time if this is an exception, null otherwise. */
    val originalInstanceTime: Long?,
    /** Event status: STATUS_TENTATIVE=0, STATUS_CONFIRMED=1, STATUS_CANCELED=2. */
    val status: Int,
    /** Availability: AVAILABILITY_BUSY=0, AVAILABILITY_FREE=1, AVAILABILITY_TENTATIVE=2. */
    val availability: Int,
    /** Access level: ACCESS_DEFAULT=0, ACCESS_CONFIDENTIAL=1, ACCESS_PRIVATE=2, ACCESS_PUBLIC=3. */
    val accessLevel: Int,
    /** Calendar color (from Calendars table). */
    val calendarColor: Int?,
    /** Event-specific color override (from Events table). */
    val eventColor: Int?
)
