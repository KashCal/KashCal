package org.onekash.kashcal.util

import org.onekash.kashcal.reminder.scheduler.parseIsoDuration

/**
 * Convert ISO 8601 reminder durations to minutes.
 *
 * Event.reminders stores ISO durations like "-PT15M", "-PT1H", "-P1D".
 * CalendarProvider expects reminder minutes as List<Int>.
 *
 * @param isoReminders List of ISO 8601 duration strings (e.g., ["-PT15M", "-P1D"])
 * @return Sorted, deduplicated list of minutes (e.g., [15, 1440])
 */
fun isoRemindersToMinutes(isoReminders: List<String>?): List<Int> {
    if (isoReminders.isNullOrEmpty()) return emptyList()

    return isoReminders
        .mapNotNull { reminder ->
            val durationStr = reminder.removePrefix("-")
            val millis = parseIsoDuration(durationStr) ?: return@mapNotNull null
            (millis / 60_000).toInt()
        }
        .distinct()
        .sorted()
}
