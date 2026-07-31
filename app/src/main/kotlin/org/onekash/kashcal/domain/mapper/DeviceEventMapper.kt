package org.onekash.kashcal.domain.mapper

import android.provider.CalendarContract
import android.util.Log
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Event
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
 * - Two color channels: selectedCalendarColor = calendar identity (picker dot),
 *   eventColor = per-event override (separate field on form state)
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
        selectedCalendarColor = calendarColor,
        reminders = mappedReminders,
        isAllDay = isAllDay,
        location = location.orEmpty(),
        description = description.orEmpty(),
        categories = categories,
        rrule = rrule,
        timezone = timezone,
        transp = availabilityIntToTransp(availability),
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
 *
 * For recurring all-day events, parseDurationToMillis returns whole-day milliseconds
 * (P1D = 86_400_000), so startTs + duration yields the exclusive next-day midnight.
 * Roll back 1 ms to match KashCal's inclusive last-ms-of-last-day convention — the
 * same convention non-recurring all-day events arrive in via inclusiveEndForDeviceEvent.
 * Without this, the edit form's date picker would show the day after the event's
 * actual last day for any recurring all-day event.
 */
private fun DeviceEvent.computeEndTs(): Long? {
    // For recurring events, CalendarProvider uses DURATION instead of DTEND
    if (!duration.isNullOrEmpty()) {
        val durationMs = DateTimeUtils.parseDurationToMillis(duration)
        if (durationMs != null) {
            val rawEnd = startTs + durationMs
            return if (isAllDay && rawEnd > startTs) rawEnd - 1 else rawEnd
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

/**
 * Convert a device calendar event to a synthetic Room [Event] for ICS export.
 *
 * The returned [Event] is never persisted — it's a transport object that
 * feeds [org.onekash.kashcal.sync.parser.icaldav.IcsPatcher.serialize] /
 * [serializeWithExceptions], so device-event export reuses the same serialization
 * pipeline Room events go through.
 *
 * Key mappings:
 * - UID: always `device-{masterId}@kashcal` where `masterId = originalId ?: id`.
 *   For an exception row this uses the master's id, giving master + exception
 *   the shared UID that RFC 5545 requires.
 * - RRULE: nulled for exceptions (CalendarProvider's Events-table exception rows
 *   have RRULE=NULL, but defensive null-out protects against Instances-derived
 *   inputs and future refactors).
 * - STATUS: CalendarProvider int → RFC 5545 string. STATUS_CANCELED preserves
 *   cancelled occurrences on export.
 * - AVAILABILITY: CalendarProvider int → TRANSP string. BUSY/TENTATIVE → OPAQUE,
 *   FREE → TRANSPARENT.
 * - Reminders: caller passes minutes-before-start (fetched separately via
 *   [org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository.getReminders]);
 *   mapped to ISO durations via [ContactEventUtils.minutesToIsoDuration].
 */
fun DeviceEvent.toExportEvent(reminderMinutes: List<Int> = emptyList()): Event {
    val masterId = originalId ?: id
    val now = System.currentTimeMillis()
    return Event(
        uid = "device-$masterId@kashcal",
        calendarId = 0,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs ?: startTs,
        timezone = timezone,
        isAllDay = isAllDay,
        status = statusIntToString(status),
        transp = availabilityIntToTransp(availability),
        classification = "PUBLIC",
        rrule = if (originalId != null) null else rrule,
        rdate = rdate,
        exdate = exdate,
        originalEventId = originalId,
        originalInstanceTime = originalInstanceTime,
        reminders = reminderMinutes.takeIf { it.isNotEmpty() }
            ?.map { ContactEventUtils.minutesToIsoDuration(it) },
        color = eventColor,
        dtstamp = now,
        sequence = 0,
        createdAt = now,
        updatedAt = now
    )
}

/**
 * CalendarProvider STATUS int → RFC 5545 status string. Shared by
 * [toExportEvent] and [DisplayEvent.Device.toEventForShareCard] so a
 * TENTATIVE device event preserves its status across both share paths.
 */
internal fun statusIntToString(status: Int): String = when (status) {
    CalendarContract.Events.STATUS_TENTATIVE -> "TENTATIVE"
    CalendarContract.Events.STATUS_CANCELED -> "CANCELLED"
    else -> "CONFIRMED"
}

/**
 * CalendarProvider AVAILABILITY int → RFC 5545 TRANSP string.
 * BUSY/TENTATIVE → OPAQUE, FREE → TRANSPARENT. Shared across [toFormState],
 * [toExportEvent], and [DisplayEvent.Device.toEventForDuplicate].
 */
internal fun availabilityIntToTransp(availability: Int): String = when (availability) {
    CalendarContract.Events.AVAILABILITY_FREE -> "TRANSPARENT"
    else -> "OPAQUE"
}
