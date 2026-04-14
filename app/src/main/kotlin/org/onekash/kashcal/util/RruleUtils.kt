package org.onekash.kashcal.util

/**
 * RRULE utility functions for adding/replacing UNTIL clauses.
 *
 * Extracted from EventWriter so both Room and CalendarProvider layers can use them
 * without cross-layer dependencies.
 */
object RruleUtils {

    /**
     * Add UNTIL parameter to RRULE, replacing existing UNTIL or COUNT.
     *
     * @param rrule The RRULE string (e.g., "FREQ=WEEKLY;BYDAY=MO")
     * @param untilMs Timestamp in millis for UNTIL value
     * @param isAllDay If true, formats UNTIL as date-only (RFC 5545 §3.3.10)
     * @return Modified RRULE string with UNTIL clause
     */
    fun addUntilToRrule(rrule: String, untilMs: Long, isAllDay: Boolean = false): String {
        val untilDate = formatUntilDate(untilMs, isAllDay)

        return when {
            rrule.contains("UNTIL=") -> {
                rrule.replace(Regex("UNTIL=[^;]+"), "UNTIL=$untilDate")
            }
            rrule.contains("COUNT=") -> {
                val withoutCount = rrule.replace(Regex(";?COUNT=\\d+"), "")
                "$withoutCount;UNTIL=$untilDate"
            }
            else -> "$rrule;UNTIL=$untilDate"
        }
    }

    /**
     * Format timestamp as RRULE UNTIL value.
     *
     * @param timestampMs Timestamp in epoch millis
     * @param isAllDay If true, returns date-only format (e.g., "20260115").
     *                 If false, returns datetime format (e.g., "20260115T100000Z").
     * @return Formatted UNTIL string
     */
    fun formatUntilDate(timestampMs: Long, isAllDay: Boolean = false): String {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestampMs

        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)

        if (isAllDay) {
            return String.format(java.util.Locale.ROOT, "%04d%02d%02d", year, month, day)
        }

        return String.format(
            java.util.Locale.ROOT,
            "%04d%02d%02dT%02d%02d%02dZ",
            year, month, day,
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
    }
}
