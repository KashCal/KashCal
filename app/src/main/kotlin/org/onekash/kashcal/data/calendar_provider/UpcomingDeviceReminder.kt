package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * The next device-event reminder to schedule, from
 * [CalendarProviderRepository.getNextUpcomingReminder].
 *
 * Keyed by (eventId, occurrenceStartTs). An Instances `_ID` can't be the key: it changes with
 * the queried range.
 */
@Immutable
data class UpcomingDeviceReminder(
    /** The Events row id. */
    val eventId: Long,

    /** The occurrence's start; with [eventId], the key. */
    val occurrenceStartTs: Long,

    /** Shown in the notification. */
    val title: String,

    val location: String?,

    val isAllDay: Boolean,

    /** Minutes before the start; negative means after. */
    val reminderMinutes: Int,

    /**
     * When the alarm fires: [occurrenceStartTs] minus [reminderMinutes] for a timed event; for an
     * all-day event, local midnight of the day minus [reminderMinutes].
     */
    val triggerTime: Long,

    /** The event's color override if set, else the calendar's color. */
    val calendarColor: Int,

    val calendarId: Long
)
