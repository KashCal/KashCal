package org.onekash.kashcal.domain.mapper

import android.util.Log
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.shared.MAX_REMINDERS
import org.onekash.kashcal.util.DateTimeUtils
import java.util.Calendar

private const val TAG = "DeviceEventMapper"

/**
 * Convert a DeviceEvent to EventFormState for editing.
 *
 * Handles:
 * - Duration parsing for recurring events (CalendarProvider stores DURATION, not DTEND)
 * - All-day UTC to local date conversion
 * - Reminder mapping (first 5 only, logs warning if truncated)
 * - Color precedence (eventColor over calendarColor)
 *
 * @param reminders List of reminder minutes from CalendarProvider
 * @param calendarColor Calendar's default color
 * @param calendarName Calendar display name
 * @param deviceCalendarGroups Device calendar groups for picker
 * @return EventFormState populated with device event data
 */
fun DeviceEvent.toFormState(
    reminders: List<Int>,
    calendarColor: Int?,
    calendarName: String,
    deviceCalendarGroups: List<CalendarGroup>,
    occurrenceTs: Long? = null
): EventFormState {
    // Compute end timestamp from duration or endTs
    val computedEndTs = computeEndTs()

    // For single occurrence edit, use occurrenceTs to show the correct date.
    // Exception events (originalId != null) already have their own startTs.
    // Same pattern as Room events (EventFormSheet.kt:402).
    val eventDuration = (computedEndTs ?: startTs) - startTs
    val actualStartTs = if (originalId != null) startTs else (occurrenceTs ?: startTs)
    val actualEndTs = actualStartTs + eventDuration

    // For all-day events, convert UTC midnight to local date
    val (startDateMillis, endDateMillis) = if (isAllDay) {
        val localStart = DateTimeUtils.utcMidnightToLocalDate(actualStartTs)
        val localEnd = DateTimeUtils.utcMidnightToLocalDate(actualEndTs)
        localStart to localEnd
    } else {
        actualStartTs to actualEndTs
    }

    // Extract time components
    val startCal = Calendar.getInstance().apply { timeInMillis = actualStartTs }
    val endCal = Calendar.getInstance().apply { timeInMillis = actualEndTs }

    // Map reminders (take first 5, track truncated count for UI warning)
    val (mappedReminders, truncatedCount) = mapReminders(reminders)

    // Color precedence: eventColor > calendarColor
    val displayColor = eventColor ?: calendarColor

    return EventFormState(
        title = title,
        dateMillis = startDateMillis,
        endDateMillis = endDateMillis,
        startHour = startCal.get(Calendar.HOUR_OF_DAY),
        startMinute = startCal.get(Calendar.MINUTE),
        endHour = endCal.get(Calendar.HOUR_OF_DAY),
        endMinute = endCal.get(Calendar.MINUTE),
        selectedCalendarId = calendarId,
        selectedCalendarName = calendarName,
        selectedCalendarColor = displayColor,
        reminders = mappedReminders,
        isAllDay = isAllDay,
        location = location.orEmpty(),
        description = description.orEmpty(),
        rrule = rrule,
        timezone = timezone,
        transp = when (availability) {
            1 -> "TRANSPARENT"
            else -> "OPAQUE"
        },
        eventColor = this.eventColor,
        deviceCalendarGroups = deviceCalendarGroups,
        isLoading = false,
        isDeviceCalendar = true,
        editingDeviceEventId = originalId ?: id,
        truncatedReminderCount = truncatedCount,
        isEditMode = true
    )
}

/**
 * Compute end timestamp from duration (for recurring) or endTs (for single events).
 */
private fun DeviceEvent.computeEndTs(): Long? {
    // For recurring events, CalendarProvider uses DURATION instead of DTEND
    if (!duration.isNullOrEmpty()) {
        val durationMs = DateTimeUtils.parseDurationToMillis(duration)
        if (durationMs != null) {
            return startTs + durationMs
        }
    }

    // Fall back to endTs (for non-recurring events)
    return endTs
}

/**
 * Map reminder minutes to form state.
 * Takes first MAX_REMINDERS (5), logs warning and tracks truncated count if exceeded.
 *
 * @return Pair of (reminderMinutes, truncatedCount)
 */
private fun mapReminders(reminders: List<Int>): Pair<List<Int>, Int> {
    val truncatedCount = (reminders.size - MAX_REMINDERS).coerceAtLeast(0)
    if (truncatedCount > 0) {
        Log.w(TAG, "Event has ${reminders.size} reminders, only first $MAX_REMINDERS will be used ($truncatedCount truncated)")
    }

    return Pair(reminders.take(MAX_REMINDERS), truncatedCount)
}
