package org.onekash.kashcal.sync.parser.icaldav

import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.parser.ICalParser

/**
 * On-demand parser for extracting additional data from rawIcal.
 *
 * Used when we need data that wasn't stored in the Event entity columns,
 * such as alarms beyond the first 3 (for events with alarmCount > 3).
 *
 * This is more efficient than storing all alarm data in the database
 * for events that rarely need it.
 */
object RawIcsParser {

    private val parser = ICalParser()

    /**
     * Extract all alarms from raw ICS data.
     *
     * Use this when event.alarmCount > 3 and you need all reminders.
     *
     * @param rawIcal Original ICS data
     * @return List of all ICalAlarms, or empty if parsing fails
     */
    fun getAllAlarms(rawIcal: String?): List<ICalAlarm> {
        if (rawIcal.isNullOrBlank()) return emptyList()

        return try {
            val events = parser.parseAllEvents(rawIcal).getOrNull()
            events?.firstOrNull()?.alarms ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extract alarm trigger strings from raw ICS data.
     *
     * Convenience method that returns trigger durations as formatted strings.
     *
     * @param rawIcal Original ICS data
     * @return List of trigger strings (e.g., "-PT15M", "-P1D")
     */
    fun getAllAlarmTriggers(rawIcal: String?): List<String> {
        return getAllAlarms(rawIcal).mapNotNull { alarm ->
            alarm.trigger?.let { ICalAlarm.formatDuration(it) }
        }
    }

    /**
     * Get alarm count from raw ICS data.
     *
     * Useful for verification without full alarm extraction.
     *
     * @param rawIcal Original ICS data
     * @return Number of VALARM components
     */
    fun getAlarmCount(rawIcal: String?): Int {
        return getAllAlarms(rawIcal).size
    }
}
