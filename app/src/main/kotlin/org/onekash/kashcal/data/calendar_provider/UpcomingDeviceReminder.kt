package org.onekash.kashcal.data.calendar_provider

/**
 * Data class representing an upcoming reminder for a device calendar event.
 *
 * Used by [CalendarProviderRepository.getNextUpcomingReminder] to return
 * the next reminder that should be scheduled.
 *
 * Key design: Uses (eventId, occurrenceStartTs) as stable composite key,
 * NOT instanceId (which is ephemeral and changes based on query range).
 */
data class UpcomingDeviceReminder(
    /** Event ID from CalendarContract.Events (stable) */
    val eventId: Long,

    /** Occurrence start timestamp - forms stable key with eventId */
    val occurrenceStartTs: Long,

    /** Event title for notification display */
    val title: String,

    /** Event location (nullable) */
    val location: String?,

    /** Whether this is an all-day event */
    val isAllDay: Boolean,

    /** Reminder offset in minutes before event start */
    val reminderMinutes: Int,

    /**
     * Calculated trigger time for the alarm.
     * For timed events: occurrenceStartTs - (reminderMinutes * 60 * 1000)
     * For all-day events: 9 AM local time, N days before
     */
    val triggerTime: Long,

    /** Calendar display color */
    val calendarColor: Int,

    /** Calendar ID (for filtering) */
    val calendarId: Long
)
