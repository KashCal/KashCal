package org.onekash.kashcal.ui.viewmodels

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceEvent

/**
 * Data class for device event edit loading.
 *
 * Contains the event, reminders, and calendar metadata needed
 * to populate EventFormSheet for editing a device calendar event.
 */
@Immutable
data class DeviceEventEditData(
    /** Full event data from CalendarProvider Events table. */
    val event: DeviceEvent,
    /** Reminder minutes before event (from Reminders table). */
    val reminders: List<Int>,
    /** Calendar display name for form header. */
    val calendarName: String,
    /** Calendar color for form picker. */
    val calendarColor: Int?,
    /** Whether the calendar allows write operations. */
    val isWritable: Boolean
)
