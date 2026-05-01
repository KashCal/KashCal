package org.onekash.icaldav.model

import java.time.DayOfWeek

/**
 * Recurrence rule from RRULE property per RFC 5545 Section 3.3.10.
 *
 * Examples:
 * - RRULE:FREQ=DAILY;INTERVAL=1
 * - RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
 * - RRULE:FREQ=MONTHLY;BYMONTHDAY=15
 * - RRULE:FREQ=MONTHLY;BYDAY=2TU (second Tuesday)
 * - RRULE:FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25
 */
data class RRule(
    /** Recurrence frequency: DAILY, WEEKLY, MONTHLY, YEARLY */
    val freq: Frequency,

    /** Interval between occurrences (default 1) */
    val interval: Int = 1,

    /** Number of occurrences (mutually exclusive with until) */
    val count: Int? = null,

    /** End date of recurrence (mutually exclusive with count) */
    val until: ICalDateTime? = null,

    /** Days of week for WEEKLY or MONTHLY recurrence */
    val byDay: List<WeekdayNum>? = null,

    /** Days of month for MONTHLY recurrence (1-31 or -1 to -31) */
    val byMonthDay: List<Int>? = null,

    /** Months for YEARLY recurrence (1-12) */
    val byMonth: List<Int>? = null,

    /** Week numbers for YEARLY recurrence (1-53 or -1 to -53) */
    val byWeekNo: List<Int>? = null,

    /** Days of year for YEARLY recurrence (1-366 or -1 to -366) */
    val byYearDay: List<Int>? = null,

    /** Hours for sub-daily expansion (RFC 5545 §3.3.10 BYHOUR, valid range 0..23). */
    val byHour: List<Int>? = null,

    /** Minutes for sub-daily expansion (RFC 5545 §3.3.10 BYMINUTE, valid range 0..59). */
    val byMinute: List<Int>? = null,

    /** Seconds for sub-daily expansion (RFC 5545 §3.3.10 BYSECOND, valid range 0..60; 60 permits leap seconds). */
    val bySecond: List<Int>? = null,

    /** Position within set (e.g., -1 for last occurrence) */
    val bySetPos: List<Int>? = null,

    /** Week start day (default MONDAY per RFC 5545) */
    val wkst: DayOfWeek = DayOfWeek.MONDAY
) {
    /**
     * Convert to iCalendar RRULE string.
     */
    fun toICalString(): String {
        val parts = mutableListOf<String>()

        parts.add("FREQ=${freq.name}")

        if (interval != 1) {
            parts.add("INTERVAL=$interval")
        }

        count?.let { parts.add("COUNT=$it") }
        until?.let { parts.add("UNTIL=${it.toICalString()}") }

        byDay?.let { days ->
            parts.add("BYDAY=${days.joinToString(",") { it.toICalString() }}")
        }

        byMonthDay?.let { days ->
            parts.add("BYMONTHDAY=${days.joinToString(",")}")
        }

        byMonth?.let { months ->
            parts.add("BYMONTH=${months.joinToString(",")}")
        }

        byWeekNo?.let { weeks ->
            parts.add("BYWEEKNO=${weeks.joinToString(",")}")
        }

        byYearDay?.let { days ->
            parts.add("BYYEARDAY=${days.joinToString(",")}")
        }

        bySecond?.let { seconds ->
            parts.add("BYSECOND=${seconds.joinToString(",")}")
        }

        byMinute?.let { minutes ->
            parts.add("BYMINUTE=${minutes.joinToString(",")}")
        }

        byHour?.let { hours ->
            parts.add("BYHOUR=${hours.joinToString(",")}")
        }

        bySetPos?.let { positions ->
            parts.add("BYSETPOS=${positions.joinToString(",")}")
        }

        if (wkst != DayOfWeek.MONDAY) {
            parts.add("WKST=${dayOfWeekToIcal(wkst)}")
        }

        return parts.joinToString(";")
    }

    companion object {
        private val RRULE_PATTERN = Regex("""([A-Z]+)=([^;]+)""")

        /**
         * Parse RRULE string to RRule object.
         *
         * @param rruleString The RRULE value (without "RRULE:" prefix)
         * @return Parsed RRule
         */
        fun parse(rruleString: String): RRule {
            val parts = mutableMapOf<String, String>()

            RRULE_PATTERN.findAll(rruleString).forEach { match ->
                parts[match.groupValues[1]] = match.groupValues[2]
            }

            val freq = parts["FREQ"]?.let { Frequency.valueOf(it) }
                ?: throw IllegalArgumentException("RRULE missing FREQ: $rruleString")

            return RRule(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull() ?: 1,
                count = parts["COUNT"]?.toIntOrNull(),
                until = parts["UNTIL"]?.let { ICalDateTime.parse(it) },
                byDay = parts["BYDAY"]?.split(",")?.map { WeekdayNum.parse(it) },
                byMonthDay = parts["BYMONTHDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byMonth = parts["BYMONTH"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byWeekNo = parts["BYWEEKNO"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byYearDay = parts["BYYEARDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byHour = parts["BYHOUR"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byMinute = parts["BYMINUTE"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                bySecond = parts["BYSECOND"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                bySetPos = parts["BYSETPOS"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                wkst = parts["WKST"]?.let { icalToDayOfWeek(it) } ?: DayOfWeek.MONDAY
            )
        }

        private fun dayOfWeekToIcal(dow: DayOfWeek): String = when (dow) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }

        private fun icalToDayOfWeek(ical: String): DayOfWeek = when (ical.uppercase()) {
            "MO" -> DayOfWeek.MONDAY
            "TU" -> DayOfWeek.TUESDAY
            "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY
            "FR" -> DayOfWeek.FRIDAY
            "SA" -> DayOfWeek.SATURDAY
            "SU" -> DayOfWeek.SUNDAY
            else -> DayOfWeek.MONDAY
        }
    }
}

/**
 * Recurrence frequency.
 */
enum class Frequency {
    SECONDLY,
    MINUTELY,
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * Weekday with optional ordinal number.
 * Examples: MO, TU, 2TU (second Tuesday), -1FR (last Friday)
 */
data class WeekdayNum(
    val dayOfWeek: DayOfWeek,
    val ordinal: Int? = null  // null means every occurrence, 1-5 or -1 to -5
) {
    fun toICalString(): String {
        val dayStr = when (dayOfWeek) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }
        return if (ordinal != null) "$ordinal$dayStr" else dayStr
    }

    companion object {
        private val WEEKDAY_PATTERN = Regex("""(-?\d)?([A-Z]{2})""")

        fun parse(value: String): WeekdayNum {
            val match = WEEKDAY_PATTERN.matchEntire(value.uppercase())
                ?: throw IllegalArgumentException("Invalid weekday: $value")

            val ordinal = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val day = when (match.groupValues[2]) {
                "MO" -> DayOfWeek.MONDAY
                "TU" -> DayOfWeek.TUESDAY
                "WE" -> DayOfWeek.WEDNESDAY
                "TH" -> DayOfWeek.THURSDAY
                "FR" -> DayOfWeek.FRIDAY
                "SA" -> DayOfWeek.SATURDAY
                "SU" -> DayOfWeek.SUNDAY
                else -> throw IllegalArgumentException("Invalid weekday: $value")
            }

            return WeekdayNum(day, ordinal)
        }
    }
}