package org.onekash.kashcal.domain.rrule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RRULE builder utility for creating and parsing RFC 5545 recurrence rules.
 *
 * This object provides methods to:
 * - Build RRULE strings from components
 * - Parse RRULE strings to components for UI display
 * - Format RRULE strings for human-readable display
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.10">RFC 5545 RRULE</a>
 */
object RruleBuilder {

    /** RFC 5545 day abbreviations */
    private val DAY_ABBREV = mapOf(
        DayOfWeek.SUNDAY to "SU",
        DayOfWeek.MONDAY to "MO",
        DayOfWeek.TUESDAY to "TU",
        DayOfWeek.WEDNESDAY to "WE",
        DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR",
        DayOfWeek.SATURDAY to "SA"
    )

    /** Reverse mapping for parsing */
    private val ABBREV_TO_DAY = DAY_ABBREV.entries.associate { (day, abbrev) -> abbrev to day }

    /** Day order for consistent BYDAY output (Monday first) */
    private val DAY_ORDER = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    )

    /** Human-readable day names for display */
    private val DAY_DISPLAY = mapOf(
        "SU" to "Sun", "MO" to "Mon", "TU" to "Tue", "WE" to "Wed",
        "TH" to "Thu", "FR" to "Fri", "SA" to "Sat"
    )

    // ==================== Building RRULE Strings ====================

    /**
     * Convert DayOfWeek to RFC 5545 abbreviation.
     */
    fun toDayAbbrev(day: DayOfWeek): String = DAY_ABBREV[day] ?: "MO"

    /**
     * Build a daily recurrence rule.
     *
     * @param interval Days between occurrences (default 1)
     * @return RRULE string like "FREQ=DAILY" or "FREQ=DAILY;INTERVAL=2"
     */
    fun daily(interval: Int = 1): String {
        return if (interval == 1) "FREQ=DAILY"
        else "FREQ=DAILY;INTERVAL=$interval"
    }

    /**
     * Build a weekly recurrence rule.
     *
     * @param interval Weeks between occurrences (default 1)
     * @param days Specific days of week (empty = same day as start)
     * @return RRULE string like "FREQ=WEEKLY;BYDAY=MO,WE,FR"
     */
    fun weekly(interval: Int = 1, days: Set<DayOfWeek> = emptySet()): String {
        val parts = mutableListOf("FREQ=WEEKLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (days.isNotEmpty()) {
            val sortedDays = DAY_ORDER.filter { it in days }
            parts.add("BYDAY=${sortedDays.joinToString(",") { toDayAbbrev(it) }}")
        }
        return parts.joinToString(";")
    }

    /**
     * Build a monthly recurrence rule on a specific day of month.
     *
     * @param interval Months between occurrences (default 1)
     * @param dayOfMonth Day of month (1-31, null = same as start date)
     * @return RRULE string like "FREQ=MONTHLY;BYMONTHDAY=15"
     */
    fun monthly(interval: Int = 1, dayOfMonth: Int? = null): String {
        val parts = mutableListOf("FREQ=MONTHLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (dayOfMonth != null) parts.add("BYMONTHDAY=$dayOfMonth")
        return parts.joinToString(";")
    }

    /**
     * Build a monthly recurrence rule on the last day of month.
     *
     * @param interval Months between occurrences (default 1)
     * @return RRULE string like "FREQ=MONTHLY;BYMONTHDAY=-1"
     */
    fun monthlyLastDay(interval: Int = 1): String {
        val parts = mutableListOf("FREQ=MONTHLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        parts.add("BYMONTHDAY=-1")
        return parts.joinToString(";")
    }

    /**
     * Build a monthly recurrence rule on Nth weekday (e.g., "2nd Tuesday").
     *
     * @param ordinal 1-4 for 1st-4th, -1 for last
     * @param weekday The day of week
     * @param interval Months between occurrences (default 1)
     * @return RRULE string like "FREQ=MONTHLY;BYDAY=2TU"
     */
    fun monthlyNthWeekday(ordinal: Int, weekday: DayOfWeek, interval: Int = 1): String {
        val parts = mutableListOf("FREQ=MONTHLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        val prefix = if (ordinal == -1) "-1" else ordinal.toString()
        parts.add("BYDAY=$prefix${toDayAbbrev(weekday)}")
        return parts.joinToString(";")
    }

    /**
     * Build a yearly recurrence rule.
     *
     * @param interval Years between occurrences (default 1)
     * @return RRULE string like "FREQ=YEARLY"
     */
    fun yearly(interval: Int = 1): String {
        return if (interval == 1) "FREQ=YEARLY"
        else "FREQ=YEARLY;INTERVAL=$interval"
    }

    /**
     * Add occurrence count to an RRULE.
     *
     * @param rrule Base RRULE string
     * @param count Number of occurrences
     * @return RRULE with COUNT appended
     */
    fun withCount(rrule: String, count: Int): String {
        return "$rrule;COUNT=$count"
    }

    /**
     * Add end date (UNTIL) to an RRULE.
     *
     * @param rrule Base RRULE string
     * @param untilMillis End date timestamp in milliseconds
     * @return RRULE with UNTIL appended in UTC format
     */
    fun withUntil(rrule: String, untilMillis: Long): String {
        val instant = Instant.ofEpochMilli(untilMillis)
        val utc = instant.atZone(ZoneOffset.UTC)
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        return "$rrule;UNTIL=${utc.format(formatter)}"
    }

    // ==================== Parsing RRULE Strings ====================

    /**
     * Parse RRULE string to determine frequency category.
     * Returns CUSTOM for complex rules (interval > 1, COUNT, UNTIL, BYSETPOS).
     *
     * @param rrule RRULE string to parse
     * @return Detected [RecurrenceFrequency]
     */
    fun parseFrequency(rrule: String?): RecurrenceFrequency {
        if (rrule.isNullOrBlank()) return RecurrenceFrequency.NONE

        // Check for complexity markers that make it "custom"
        if (rrule.contains("INTERVAL=") ||
            rrule.contains("COUNT=") ||
            rrule.contains("UNTIL=") ||
            rrule.contains("BYSETPOS=")) {
            return RecurrenceFrequency.CUSTOM
        }

        return when {
            rrule.contains("FREQ=DAILY") -> RecurrenceFrequency.DAILY
            rrule.contains("FREQ=WEEKLY") -> RecurrenceFrequency.WEEKLY
            rrule.contains("FREQ=MONTHLY") -> RecurrenceFrequency.MONTHLY
            rrule.contains("FREQ=YEARLY") -> RecurrenceFrequency.YEARLY
            else -> RecurrenceFrequency.CUSTOM
        }
    }

    /**
     * Parse RRULE string to components for UI state.
     *
     * @param rrule RRULE string to parse
     * @param defaultWeekday Default weekday for monthly NthWeekday pattern
     * @param defaultDayOfMonth Default day for monthly SameDay pattern
     * @param defaultOrdinal Default ordinal for NthWeekday (1-4 or -1)
     * @return [ParsedRecurrence] with extracted values
     */
    fun parseRrule(
        rrule: String?,
        defaultWeekday: DayOfWeek,
        defaultDayOfMonth: Int,
        defaultOrdinal: Int
    ): ParsedRecurrence {
        if (rrule.isNullOrBlank()) return ParsedRecurrence()

        // Parse frequency
        val frequency = when {
            rrule.contains("FREQ=DAILY") -> RecurrenceFrequency.DAILY
            rrule.contains("FREQ=WEEKLY") -> RecurrenceFrequency.WEEKLY
            rrule.contains("FREQ=MONTHLY") -> RecurrenceFrequency.MONTHLY
            rrule.contains("FREQ=YEARLY") -> RecurrenceFrequency.YEARLY
            else -> RecurrenceFrequency.NONE
        }

        // Parse interval
        val intervalMatch = Regex("INTERVAL=(\\d+)").find(rrule)
        val interval = intervalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Parse weekdays (for weekly)
        val bydayMatch = Regex("BYDAY=([A-Z,]+)").find(rrule)
        val weekdays = if (bydayMatch != null) {
            bydayMatch.groupValues[1].split(",")
                .mapNotNull { ABBREV_TO_DAY[it] }
                .toSet()
        } else {
            emptySet()
        }

        // Parse monthly pattern
        val monthlyPattern: MonthlyPattern? = if (frequency == RecurrenceFrequency.MONTHLY) {
            val nthWeekdayMatch = Regex("BYDAY=(-?\\d+)([A-Z]{2})").find(rrule)
            val byMonthdayMatch = Regex("BYMONTHDAY=(-?\\d+)").find(rrule)
            when {
                nthWeekdayMatch != null -> {
                    val ordinal = nthWeekdayMatch.groupValues[1].toIntOrNull() ?: defaultOrdinal
                    val dayAbbrev = nthWeekdayMatch.groupValues[2]
                    val weekday = ABBREV_TO_DAY[dayAbbrev] ?: defaultWeekday
                    MonthlyPattern.NthWeekday(ordinal, weekday)
                }
                byMonthdayMatch != null -> {
                    val day = byMonthdayMatch.groupValues[1].toIntOrNull() ?: defaultDayOfMonth
                    if (day == -1) MonthlyPattern.LastDay
                    else MonthlyPattern.SameDay(day)
                }
                else -> MonthlyPattern.SameDay(defaultDayOfMonth)
            }
        } else null

        // Parse end condition
        val countMatch = Regex("COUNT=(\\d+)").find(rrule)
        val untilMatch = Regex("UNTIL=(\\d{8}T\\d{6}Z?)").find(rrule)
        val endCondition: EndCondition = when {
            countMatch != null -> {
                val count = countMatch.groupValues[1].toIntOrNull() ?: 10
                EndCondition.Count(count)
            }
            untilMatch != null -> {
                try {
                    val dateTimeStr = untilMatch.groupValues[1]
                    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    val dateTime = LocalDateTime.parse(dateTimeStr, formatter)
                    val millis = dateTime.atZone(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                    EndCondition.Until(millis)
                } catch (e: Exception) {
                    EndCondition.Never
                }
            }
            else -> EndCondition.Never
        }

        return ParsedRecurrence(
            frequency = frequency,
            interval = interval,
            weekdays = weekdays,
            monthlyPattern = monthlyPattern,
            endCondition = endCondition
        )
    }

    // ==================== Display Formatting ====================

    /**
     * Format RRULE for human-readable display.
     *
     * Examples:
     * - "FREQ=DAILY" -> "Daily"
     * - "FREQ=WEEKLY;INTERVAL=2" -> "Biweekly"
     * - "FREQ=WEEKLY;BYDAY=MO,WE,FR" -> "Weekly on Mon, Wed, Fri"
     * - "FREQ=MONTHLY;BYDAY=2TU" -> "Monthly on 2nd Tue"
     * - "FREQ=MONTHLY;BYMONTHDAY=-1" -> "Monthly on last day"
     *
     * @param rrule RRULE string to format
     * @return Human-readable description
     */
    fun formatForDisplay(rrule: String?): String {
        if (rrule.isNullOrBlank()) return "Does not repeat"

        val intervalMatch = Regex("INTERVAL=(\\d+)").find(rrule)
        val interval = intervalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val freq = when {
            rrule.contains("FREQ=DAILY") -> if (interval > 1) "Every $interval days" else "Daily"
            rrule.contains("FREQ=WEEKLY") -> {
                val bydayMatch = Regex("BYDAY=([A-Z,]+)").find(rrule)
                val base = when {
                    interval == 2 -> "Biweekly"
                    interval > 1 -> "Every $interval weeks"
                    else -> "Weekly"
                }
                if (bydayMatch != null) {
                    val days = bydayMatch.groupValues[1].split(",")
                        .mapNotNull { DAY_DISPLAY[it] }
                    "$base on ${days.joinToString(", ")}"
                } else {
                    base
                }
            }
            rrule.contains("FREQ=MONTHLY") -> {
                val base = when {
                    interval == 3 -> "Quarterly"
                    interval > 1 -> "Every $interval months"
                    else -> "Monthly"
                }
                val bydayMatch = Regex("BYDAY=(-?\\d*)([A-Z]{2})").find(rrule)
                val byMonthdayMatch = Regex("BYMONTHDAY=(-?\\d+)").find(rrule)
                when {
                    bydayMatch != null -> {
                        val ordinal = bydayMatch.groupValues[1]
                        val dayAbbrev = bydayMatch.groupValues[2]
                        val dayName = DAY_DISPLAY[dayAbbrev] ?: dayAbbrev
                        val ordinalLabel = when (ordinal) {
                            "1" -> "1st"
                            "2" -> "2nd"
                            "3" -> "3rd"
                            "4" -> "4th"
                            "-1" -> "last"
                            else -> "${ordinal}th"
                        }
                        "$base on $ordinalLabel $dayName"
                    }
                    byMonthdayMatch != null -> {
                        val day = byMonthdayMatch.groupValues[1]
                        if (day == "-1") "$base on last day"
                        else "$base on day $day"
                    }
                    else -> base
                }
            }
            rrule.contains("FREQ=YEARLY") -> if (interval > 1) "Every $interval years" else "Yearly"
            else -> "Repeats"
        }

        // Add end condition to display
        val countMatch = Regex("COUNT=(\\d+)").find(rrule)
        val untilMatch = Regex("UNTIL=(\\d{8})").find(rrule)
        val endSuffix = when {
            countMatch != null -> ", ${countMatch.groupValues[1]} times"
            untilMatch != null -> {
                val dateStr = untilMatch.groupValues[1]
                val year = dateStr.substring(0, 4)
                val month = dateStr.substring(4, 6)
                val day = dateStr.substring(6, 8)
                ", until $month/$day/$year"
            }
            else -> ""
        }

        return freq + endSuffix
    }
}
