package org.onekash.kashcal.domain.mapper

import android.provider.CalendarContract
import android.util.Log
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.allDayFormDateFields
import org.onekash.kashcal.ui.components.timedFormDateFields
import org.onekash.kashcal.ui.components.withDateFields
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.shared.MAX_REMINDERS
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils

private const val TAG = "DeviceEventMapper"

/**
 * Maps a device event to an [EventFormState] for editing.
 *
 * - A recurring event's end comes from DURATION, which CalendarProvider stores instead of
 *   DTEND.
 * - All-day UTC times become local dates.
 * - Only the first MAX_REMINDERS (5) reminders are kept; the rest are counted for a UI
 *   warning and logged.
 * - Two color channels: selectedCalendarColor is the calendar's (picker dot), eventColor the
 *   per-event override.
 *
 * @param reminders reminder minutes from CalendarProvider
 * @param calendarColor the calendar's default color
 * @param occurrenceTs start of the occurrence being edited, shown instead of the series start
 */
fun DeviceEvent.toFormState(
    reminders: List<Int>,
    calendarColor: Int?,
    calendarName: String,
    deviceCalendarGroups: List<CalendarGroup>,
    occurrenceTs: Long? = null
): EventFormState {
    val computedEndTs = computeEndTs()

    // A single-occurrence edit shows occurrenceTs; an exception (originalId != null) has its
    // own startTs. Same rule as Event.toFormDateFields for Room events.
    val eventDuration = (computedEndTs ?: startTs) - startTs
    val actualStartTs = if (originalId != null) startTs else (occurrenceTs ?: startTs)
    val actualEndTs = actualStartTs + eventDuration

    // The form edits a timed event's clock time in the event's own timezone, the
    // zone the save reads it back in. All-day events carry the provider's "UTC"
    // placeholder and use the device's dates, so they get no form timezone. An ID
    // the app can't resolve falls back to the device zone and is kept aside so an
    // unchanged save writes it back.
    val recognisedTimezone = timezone.takeIf { TimezoneUtils.resolveZoneOrNull(it) != null }
    val formTimezone = if (isAllDay) null else recognisedTimezone
    val sourceTimezoneId = if (!isAllDay && timezone.isNotBlank() && recognisedTimezone == null) timezone else null

    val dateFields = if (isAllDay) {
        allDayFormDateFields(actualStartTs, actualEndTs)
    } else {
        timedFormDateFields(actualStartTs, actualEndTs, formTimezone)
    }

    val (mappedReminders, truncatedCount) = mapReminders(reminders)

    return EventFormState(
        title = title,
        selectedCalendarId = calendarId,
        selectedCalendarName = calendarName,
        selectedCalendarColor = calendarColor,
        reminders = mappedReminders,
        isAllDay = isAllDay,
        location = location.orEmpty(),
        description = description.orEmpty(),
        categories = categories,
        rrule = rrule,
        timezone = formTimezone,
        transp = availabilityIntToTransp(availability),
        eventColor = this.eventColor,
        deviceCalendarGroups = deviceCalendarGroups,
        isLoading = false,
        isDeviceCalendar = true,
        editingDeviceEventId = originalId ?: id,
        truncatedReminderCount = truncatedCount,
        isEditMode = true,
        sourceTimezoneId = sourceTimezoneId,
    ).withDateFields(dateFields)
}

/**
 * Returns the end from DURATION when it parses (recurring events), else endTs.
 *
 * For an all-day event, startTs + a whole-day DURATION (P1D = 86_400_000) is the exclusive
 * next-day midnight, so 1 ms is taken off to match the app's inclusive last-ms-of-last-day
 * convention, which non-recurring all-day events already arrive in via
 * `inclusiveEndForDeviceEvent`. Without it the edit form's date picker shows the day after a
 * recurring all-day event's last day.
 */
private fun DeviceEvent.computeEndTs(): Long? {
    if (!duration.isNullOrEmpty()) {
        val durationMs = DateTimeUtils.parseDurationToMillis(duration)
        if (durationMs != null) {
            val rawEnd = startTs + durationMs
            return if (isAllDay && rawEnd > startTs) rawEnd - 1 else rawEnd
        }
    }

    return endTs
}

/**
 * Returns the first MAX_REMINDERS (5) reminder minutes and how many were dropped, logging a
 * warning when any were.
 */
private fun mapReminders(reminders: List<Int>): Pair<List<Int>, Int> {
    val truncatedCount = (reminders.size - MAX_REMINDERS).coerceAtLeast(0)
    if (truncatedCount > 0) {
        Log.w(TAG, "Event has ${reminders.size} reminders, only first $MAX_REMINDERS will be used ($truncatedCount truncated)")
    }

    return Pair(reminders.take(MAX_REMINDERS), truncatedCount)
}

/**
 * Maps a device event to a synthetic Room [Event] for ICS export.
 *
 * The [Event] is never persisted. It feeds
 * [org.onekash.kashcal.sync.parser.icaldav.IcsPatcher.serialize] or
 * [org.onekash.kashcal.sync.parser.icaldav.IcsPatcher.serializeWithExceptions], so device
 * events export through the same serialization as Room events.
 *
 * - UID is `device-{masterId}@kashcal` with `masterId = originalId ?: id`, so an exception
 *   shares its master's UID as RFC 5545 requires.
 * - RRULE is null for exceptions. CalendarProvider's exception rows already have none; the
 *   null-out guards against Instances-derived input.
 * - STATUS maps through [statusIntToString]; STATUS_CANCELED keeps cancelled occurrences on
 *   export.
 * - AVAILABILITY maps through [availabilityIntToTransp].
 * - [reminderMinutes] are minutes before start (fetched separately, e.g. by
 *   [org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository.getRemindersForEvents]),
 *   mapped to ISO durations by [ContactEventUtils.minutesToIsoDuration].
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
 * Maps a CalendarProvider STATUS int to an RFC 5545 status string; anything else is
 * CONFIRMED. Shared by [toExportEvent] and `DisplayEvent.Device.toEventForShareCard` so a
 * TENTATIVE device event keeps its status on both share paths.
 */
internal fun statusIntToString(status: Int): String = when (status) {
    CalendarContract.Events.STATUS_TENTATIVE -> "TENTATIVE"
    CalendarContract.Events.STATUS_CANCELED -> "CANCELLED"
    else -> "CONFIRMED"
}

/**
 * Maps a CalendarProvider AVAILABILITY int to an RFC 5545 TRANSP string: FREE is TRANSPARENT,
 * anything else (BUSY, TENTATIVE) is OPAQUE.
 */
internal fun availabilityIntToTransp(availability: Int): String = when (availability) {
    CalendarContract.Events.AVAILABILITY_FREE -> "TRANSPARENT"
    else -> "OPAQUE"
}
