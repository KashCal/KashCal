package org.onekash.icaldav.util

import java.time.Duration

/**
 * Parses and formats RFC 5545 §3.3.6 DURATION values as [Duration].
 *
 * Format: `[+/-]P<n>W` or `[+/-]P[<n>D][T[<n>H][<n>M][<n>S]]`. Examples: "-PT15M" (15 minutes
 * before), "PT1H30M", "P1D", "P1W", "-P1DT2H". Days and weeks become fixed 24-hour units,
 * although §3.3.6 makes them nominal.
 */
object DurationUtils {

    /**
     * Parses [value] with [Duration.parse] (the ISO 8601 `PnDTnHnMn.nS` form), then falls back
     * to the RFC 5545 grammar for forms it rejects, such as weeks (P1W).
     *
     * Returns null for a null or blank value, one without a leading P after the sign, or a
     * non-numeric week count. Other malformed input isn't rejected: a missing component counts
     * as 0, so "P1Y" (no year designator in either grammar) gives zero.
     */
    fun parse(value: String?): Duration? {
        if (value.isNullOrBlank()) return null

        val trimmed = value.trim()

        try {
            return Duration.parse(trimmed)
        } catch (e: Exception) {
            // Not a java.time form: try the iCalendar grammar.
        }

        return parseICalDuration(trimmed)
    }

    /**
     * Parses the RFC 5545 §3.3.6 grammar below, reading each designator it finds and treating
     * a missing one as 0.
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

        when {
            str.startsWith("-") -> {
                negative = true
                str = str.substring(1)
            }
            str.startsWith("+") -> {
                str = str.substring(1)
            }
        }

        if (!str.startsWith("P")) return null
        str = str.substring(1)

        if (str.endsWith("W")) {
            val weeks = str.dropLast(1).toLongOrNull() ?: return null
            val duration = Duration.ofDays(weeks * 7)
            return if (negative) duration.negated() else duration
        }

        var days = 0L
        var hours = 0L
        var minutes = 0L
        var seconds = 0L

        val parts = str.split("T", limit = 2)
        val datePart = parts[0]
        val timePart = parts.getOrNull(1) ?: ""

        if (datePart.isNotEmpty()) {
            val daysMatch = Regex("(\\d+)D").find(datePart)
            days = daysMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
        }

        if (timePart.isNotEmpty()) {
            val hoursMatch = Regex("(\\d+)H").find(timePart)
            val minutesMatch = Regex("(\\d+)M").find(timePart)
            val secondsMatch = Regex("(\\d+)S").find(timePart)

            hours = hoursMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
            minutes = minutesMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
            seconds = secondsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
        }

        val duration = Duration.ofDays(days)
            .plusHours(hours)
            .plusMinutes(minutes)
            .plusSeconds(seconds)

        return if (negative) duration.negated() else duration
    }

    /**
     * Formats [duration] as an RFC 5545 DURATION in days, hours, minutes and seconds.
     *
     * Zero components are omitted, weeks are never emitted and sub-second parts are dropped, so
     * anything under one second gives "PT0S" ("-PT0S" when negative). Examples: -15 minutes
     * gives "-PT15M", 1 hour 30 minutes "PT1H30M", 1 day "P1D".
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

        // Nothing follows "P": no whole-second component, so emit a zero duration.
        if (sb.length <= 2) {
            sb.append("T0S")
        }

        return sb.toString()
    }

    /** Returns [parse] of [value], or [default] when that is null. */
    fun parseOrDefault(value: String?, default: Duration): Duration {
        return parse(value) ?: default
    }

    /** Returns true when the trimmed value starts with P, -P or +P, in any case. */
    fun isDurationString(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim().uppercase()
        return trimmed.startsWith("P") ||
               trimmed.startsWith("-P") ||
               trimmed.startsWith("+P")
    }
}
