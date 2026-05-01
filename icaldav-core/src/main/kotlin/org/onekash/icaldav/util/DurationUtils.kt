package org.onekash.icaldav.util

import java.time.Duration

/**
 * Utilities for parsing and formatting durations in iCalendar format.
 *
 * Supports both RFC 5545 (iCalendar) and ISO 8601 duration formats.
 *
 * RFC 5545 Duration format: [+/-]P[weeks]W or [+/-]P[days]D[T[hours]H[minutes]M[seconds]S]
 * ISO 8601 Duration format: P[n]Y[n]M[n]DT[n]H[n]M[n]S
 *
 * Examples:
 * - "-PT15M" = 15 minutes before (negative)
 * - "PT1H30M" = 1 hour 30 minutes
 * - "P1D" = 1 day
 * - "P1W" = 1 week
 * - "-P1DT2H" = 1 day and 2 hours before (negative)
 */
object DurationUtils {

    /**
     * Parse a duration string in RFC 5545 or ISO 8601 format.
     *
     * Handles:
     * - Negative durations (-PT15M)
     * - Weeks (P1W)
     * - Days, hours, minutes, seconds (P1DT2H30M15S)
     * - Java Duration.parse() compatible strings
     *
     * @param value Duration string
     * @return Parsed Duration, or null if parsing fails
     */
    fun parse(value: String?): Duration? {
        if (value.isNullOrBlank()) return null

        val trimmed = value.trim()

        // Try Java's built-in parser first (handles ISO 8601)
        try {
            return Duration.parse(trimmed)
        } catch (e: Exception) {
            // Fall through to custom parsing
        }

        // Custom parsing for iCalendar-specific formats
        return parseICalDuration(trimmed)
    }

    /**
     * Parse iCalendar duration format per RFC 5545 Section 3.3.6.
     *
     * dur-value  = (["+"] / "-") "P" (dur-date / dur-time / dur-week)
     * dur-date   = dur-day [dur-time]
     * dur-time   = "T" (dur-hour / dur-minute / dur-second)
     * dur-week   = 1*DIGIT "W"
     * dur-hour   = 1*DIGIT "H" [dur-minute]
     * dur-minute = 1*DIGIT "M" [dur-second]
     * dur-second = 1*DIGIT "S"
     * dur-day    = 1*DIGIT "D"
     */
    private fun parseICalDuration(value: String): Duration? {
        var str = value.uppercase()
        var negative = false

        // Handle sign
        when {
            str.startsWith("-") -> {
                negative = true
                str = str.substring(1)
            }
            str.startsWith("+") -> {
                str = str.substring(1)
            }
        }

        // Must start with P
        if (!str.startsWith("P")) return null
        str = str.substring(1)

        // Handle week format (P1W)
        if (str.endsWith("W")) {
            val weeks = str.dropLast(1).toLongOrNull() ?: return null
            val duration = Duration.ofDays(weeks * 7)
            return if (negative) duration.negated() else duration
        }

        // Parse days, hours, minutes, seconds
        var days = 0L
        var hours = 0L
        var minutes = 0L
        var seconds = 0L

        // Split on T to separate date and time parts
        val parts = str.split("T", limit = 2)
        val datePart = parts[0]
        val timePart = parts.getOrNull(1) ?: ""

        // Parse days from date part
        if (datePart.isNotEmpty()) {
            val daysMatch = Regex("(\\d+)D").find(datePart)
            days = daysMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
        }

        // Parse time components
        if (timePart.isNotEmpty()) {
            val hoursMatch = Regex("(\\d+)H").find(timePart)
            val minutesMatch = Regex("(\\d+)M").find(timePart)
            val secondsMatch = Regex("(\\d+)S").find(timePart)

            hours = hoursMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
            minutes = minutesMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
            seconds = secondsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
        }

        // Build duration
        val duration = Duration.ofDays(days)
            .plusHours(hours)
            .plusMinutes(minutes)
            .plusSeconds(seconds)

        return if (negative) duration.negated() else duration
    }

    /**
     * Format a Duration as RFC 5545 duration string.
     *
     * Produces compact format:
     * - Negative durations start with "-"
     * - Uses T separator for time components
     * - Omits zero components
     *
     * Examples:
     * - Duration.ofMinutes(-15) -> "-PT15M"
     * - Duration.ofHours(1).plusMinutes(30) -> "PT1H30M"
     * - Duration.ofDays(1) -> "P1D"
     *
     * @param duration Duration to format
     * @return RFC 5545 formatted string
     */
    fun format(duration: Duration): String {
        val negative = duration.isNegative
        val abs = duration.abs()

        val days = abs.toDays()
        val hours = abs.toHoursPart()
        val minutes = abs.toMinutesPart()
        val seconds = abs.toSecondsPart()

        val sb = StringBuilder()
        if (negative) sb.append("-")
        sb.append("P")

        if (days > 0) {
            sb.append("${days}D")
        }

        if (hours > 0 || minutes > 0 || seconds > 0) {
            sb.append("T")
            if (hours > 0) sb.append("${hours}H")
            if (minutes > 0) sb.append("${minutes}M")
            if (seconds > 0) sb.append("${seconds}S")
        }

        // Handle zero duration
        if (sb.length <= 2) {
            sb.append("T0S")
        }

        return sb.toString()
    }

    /**
     * Parse duration with a default value if parsing fails.
     *
     * @param value Duration string
     * @param default Default duration if parsing fails
     * @return Parsed duration or default
     */
    fun parseOrDefault(value: String?, default: Duration): Duration {
        return parse(value) ?: default
    }

    /**
     * Check if a string looks like a duration (starts with P or -P/+P).
     */
    fun isDurationString(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim().uppercase()
        return trimmed.startsWith("P") ||
               trimmed.startsWith("-P") ||
               trimmed.startsWith("+P")
    }
}
