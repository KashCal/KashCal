package org.onekash.kashcal.ui.viewmodels

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel

/** Holds what EventFormSheet needs to edit a device event: the event, reminders and calendar. */
@Immutable
data class DeviceEventEditData(
    /** The event row from the CalendarProvider Events table. */
    val event: DeviceEvent,
    /** Reminder offsets in minutes before the event, from the Reminders table. */
    val reminders: List<Int>,
    /** The calendar's display name, seeded into the form state. */
    val calendarName: String,
    /** The calendar's color, seeded into the form state. */
    val calendarColor: Int?,
    /** Whether the calendar allows writes; the form edits guests only when true. */
    val isWritable: Boolean,
    /**
     * Existing guests on the event, empty when it has no attendee rows. They seed the guest
     * picker, which is editable only on a whole-event edit of a writable calendar; otherwise they
     * show read-only. "You" is matched against the calendar's `OWNER_ACCOUNT`.
     */
    val attendees: List<AttendeeUiModel> = emptyList()
)
