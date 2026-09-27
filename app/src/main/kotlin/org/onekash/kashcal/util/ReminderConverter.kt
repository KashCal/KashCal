package org.onekash.kashcal.util

import org.onekash.kashcal.reminder.scheduler.parseIsoDuration

/**
 * Converts ISO 8601 reminder durations ("-PT15M", "-P1D") to CalendarProvider reminder minutes.
 *
 * A leading `-` is dropped, so "PT15M" also gives 15. Unparseable entries are skipped.
 *
 * @return sorted, deduplicated minutes, e.g. [15, 1440]
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
