package org.onekash.kashcal.util

import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Import parsed ICS events into a device calendar via CalendarProvider.
 *
 * Maps Event entity fields to CalendarProviderRepository.createEvent params.
 * Failed events are skipped (continues to next).
 *
 * @param events List of events to import
 * @param calendarId Target device calendar ID
 * @param repo CalendarProviderRepository for writing
 * @param defaultTimedReminderMinutes The user's configured default for timed
 *   events (KashCalDataStore.defaultReminderMinutes). Applied when a parsed
 *   event has no reminders (no VALARM in the ICS file). Pass
 *   [KashCalDataStore.REMINDER_OFF] to skip default.
 * @param defaultAllDayReminderMinutes The user's configured default for
 *   all-day events. Same semantics.
 * @return Count of successfully imported events
 */
suspend fun importEventsToDeviceCalendar(
    events: List<Event>,
    calendarId: Long,
    repo: CalendarProviderRepository,
    defaultTimedReminderMinutes: Int = KashCalDataStore.REMINDER_OFF,
    defaultAllDayReminderMinutes: Int = KashCalDataStore.REMINDER_OFF
): Int {
    var successCount = 0

    for (event in events) {
        try {
            val isRecurring = !event.rrule.isNullOrBlank()

            val endTs: Long? = if (isRecurring) null else event.endTs
            val duration: String? = if (isRecurring) {
                event.duration ?: computeDurationString(event.startTs, event.endTs, event.isAllDay)
            } else {
                null
            }

            val timezone = event.timezone ?: java.util.TimeZone.getDefault().id
            // ICS file had VALARMs → preserve them. Otherwise apply the user's
            // configured default (matches the EventCoordinator import path so
            // device-calendar imports behave the same way).
            val reminders = if (event.reminders != null) {
                isoRemindersToMinutes(event.reminders)
            } else {
                val defaultMinutes = if (event.isAllDay) {
                    defaultAllDayReminderMinutes
                } else {
                    defaultTimedReminderMinutes
                }
                if (defaultMinutes == KashCalDataStore.REMINDER_OFF) {
                    emptyList()
                } else {
                    listOf(defaultMinutes)
                }
            }

            val result = repo.createEvent(
                calendarId = calendarId,
                title = event.title,
                description = event.description?.takeIf { it.isNotBlank() },
                location = event.location?.takeIf { it.isNotBlank() },
                startTs = event.startTs,
                endTs = endTs,
                isAllDay = event.isAllDay,
                rrule = event.rrule,
                duration = duration,
                timezone = timezone,
                reminders = reminders
            )

            if (result.isSuccess) {
                successCount++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Skip failed event, continue to next
        }
    }

    return successCount
}
