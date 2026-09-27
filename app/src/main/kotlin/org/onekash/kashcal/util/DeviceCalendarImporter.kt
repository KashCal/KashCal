package org.onekash.kashcal.util

import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Imports parsed ICS events into device calendar [calendarId] through
 * [CalendarProviderRepository.createEvent], one event at a time.
 *
 * A recurring event is written with a DURATION and no end time. An event that fails, by a
 * failed result or an exception, is skipped; cancellation still propagates.
 *
 * @param defaultTimedReminderMinutes the user's default for timed events
 *   (`KashCalDataStore.defaultReminderMinutes`), applied when a parsed event has no reminders
 *   (no VALARM in the ICS file). [KashCalDataStore.REMINDER_OFF] applies none.
 * @param defaultAllDayReminderMinutes the same for all-day events
 * @return how many events were created
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
            // Parsed VALARMs are kept; otherwise the user's default applies, as on the
            // EventCoordinator import path.
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
        }
    }

    return successCount
}
