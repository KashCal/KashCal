package org.onekash.kashcal.util.text

import android.content.res.Resources
import org.onekash.kashcal.R

/**
 * Formats Room reminder offsets, ISO 8601 durations such as "-PT15M", as a comma-joined list
 * ("15 min before, 1 day before"), or null when there are none.
 *
 * Offsets [formatDuration] can't parse are left out.
 */
fun formatRemindersForDisplay(reminders: List<String>?, resources: Resources): String? {
    if (reminders.isNullOrEmpty()) return null
    return reminders.mapNotNull { formatDuration(it, resources) }.joinToString(", ")
}

/**
 * Formats one ISO 8601 reminder offset: "-PT15M" as "15 min before", "PT30M" as "30 min after".
 *
 * Shows days, hours and minutes, and seconds only when none of those is set; a zero offset is
 * "At time of event". Returns null for a blank string or one outside that shape, such as a week
 * duration.
 */
fun formatDuration(isoDuration: String, resources: Resources): String? {
    if (isoDuration.isBlank()) return null

    val negative = isoDuration.startsWith("-")
    val duration = isoDuration.removePrefix("-").removePrefix("+")

    val match = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
        .matchEntire(duration) ?: return null

    val days = match.groupValues[1].toIntOrNull() ?: 0
    val hours = match.groupValues[2].toIntOrNull() ?: 0
    val minutes = match.groupValues[3].toIntOrNull() ?: 0
    val seconds = match.groupValues[4].toIntOrNull() ?: 0

    val parts = mutableListOf<String>()
    if (days > 0) {
        parts.add(resources.getQuantityString(R.plurals.time_days, days, days))
    }
    if (hours > 0) {
        parts.add(resources.getQuantityString(R.plurals.time_hours, hours, hours))
    }
    if (minutes > 0) {
        parts.add(resources.getQuantityString(R.plurals.time_minutes_short, minutes, minutes))
    }
    if (seconds > 0 && parts.isEmpty()) {
        parts.add(resources.getQuantityString(R.plurals.time_seconds, seconds, seconds))
    }

    if (parts.isEmpty()) {
        return resources.getString(R.string.reminder_at_time_of_event)
    }

    val timeString = parts.joinToString(" ")
    return if (negative) resources.getString(R.string.reminder_time_before, timeString)
    else resources.getString(R.string.reminder_time_after, timeString)
}

/**
 * Formats device-calendar reminders, stored as minutes before the event, as a sorted,
 * comma-joined list ("15 min before, 1 hour before, 1 day before"), or null when there are none.
 *
 * A negative value reads as 0 ("At time of event"). From one day up only whole days show, so
 * 1500 minutes reads "1 day before".
 */
fun formatRemindersFromMinutes(minutes: List<Int>, resources: Resources): String? {
    if (minutes.isEmpty()) return null

    return minutes.sorted().joinToString(", ") { rawMins ->
        val mins = rawMins.coerceAtLeast(0)
        when {
            mins == 0 -> resources.getString(R.string.reminder_at_time_of_event)
            mins < 60 -> {
                val minStr = resources.getQuantityString(R.plurals.time_minutes_short, mins, mins)
                resources.getString(R.string.reminder_time_before, minStr)
            }
            mins < 1440 -> {
                val hours = mins / 60
                val remainingMins = mins % 60
                val hourPart = resources.getQuantityString(R.plurals.time_hours, hours, hours)
                val timePart = if (remainingMins > 0) {
                    val minPart = resources.getQuantityString(R.plurals.time_minutes_short, remainingMins, remainingMins)
                    "$hourPart $minPart"
                } else {
                    hourPart
                }
                resources.getString(R.string.reminder_time_before, timePart)
            }
            else -> {
                val days = mins / 1440
                resources.getQuantityString(R.plurals.reminder_days_before, days, days)
            }
        }
    }
}
