package org.onekash.icaldav.model

import java.time.DayOfWeek

/**
 * Holds one RRULE value (RFC 5545 §3.3.10).
 *
 * Examples:
 * - RRULE:FREQ=DAILY;INTERVAL=1
 * - RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
 * - RRULE:FREQ=MONTHLY;BYMONTHDAY=15
 * - RRULE:FREQ=MONTHLY;BYDAY=2TU (second Tuesday)
 * - RRULE:FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25
 */
data class RRule(
    /** FREQ, SECONDLY through YEARLY. */
    val freq: Frequency,

    /** INTERVAL: how many FREQ periods between repetitions (default 1). */
    val interval: Int = 1,

    /** COUNT; mutually exclusive with [until]. */
    val count: Int? = null,

    /** UNTIL; mutually exclusive with [count]. */
    val until: ICalDateTime? = null,

    /** BYDAY; an ordinal is allowed only with MONTHLY or YEARLY. */
    val byDay: List<WeekdayNum>? = null,

    /** BYMONTHDAY, 1..31 or -31..-1; not allowed with WEEKLY. */
    val byMonthDay: List<Int>? = null,

    /** BYMONTH, 1..12. */
    val byMonth: List<Int>? = null,

    /** BYWEEKNO, 1..53 or -53..-1; YEARLY only. */
    val byWeekNo: List<Int>? = null,

    /** BYYEARDAY, 1..366 or -366..-1; not allowed with DAILY, WEEKLY or MONTHLY. */
    val byYearDay: List<Int>? = null,

    /** BYHOUR, 0..23. */
    val byHour: List<Int>? = null,

    /** BYMINUTE, 0..59. */
    val byMinute: List<Int>? = null,

    /** BYSECOND, 0..60; 60 permits a leap second. */
    val bySecond: List<Int>? = null,

    /** BYSETPOS: positions within each period's set, for example -1 for the last. */
    val bySetPos: List<Int>? = null,

    /** WKST; MONDAY, the RFC 5545 default, is not written. */
    val wkst: DayOfWeek = DayOfWeek.MONDAY
) {
    /** Returns the RRULE value without the "RRULE:" prefix, omitting INTERVAL=1 and WKST=MO. */
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
         * Parses an RRULE value given without the "RRULE:" prefix.
         *
         * Throws when FREQ is missing or unknown, UNTIL doesn't parse, or a BYDAY entry is
         * invalid. Non-numeric entries in the other BY-lists are dropped, a non-numeric INTERVAL
         * reads as 1, and an unknown WKST reads as MONDAY.
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

/** FREQ values (RFC 5545 §3.3.10). */
enum class Frequency {
    SECONDLY,
    MINUTELY,
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}

/** Holds one BYDAY entry: a weekday with an optional ordinal, for example MO, 2TU or -1FR. */
data class WeekdayNum(
    val dayOfWeek: DayOfWeek,
    val ordinal: Int? = null  // null means every such weekday; otherwise 1..53 or -53..-1
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
        // RFC 5545 §3.3.10: weekdaynum = [[plus / minus] ordwk] weekday, where
        // ordwk = 1*2DIGIT (1..53), so values like 53SU, -12MO and +1FR are valid.
        private val WEEKDAY_PATTERN = Regex("""([+-]?\d{1,2})?([A-Z]{2})""")

        fun parse(value: String): WeekdayNum {
            val match = WEEKDAY_PATTERN.matchEntire(value.uppercase())
                ?: throw IllegalArgumentException("Invalid weekday: $value")

            val ordinal = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
            // RFC 5545 §3.3.10: ordwk is 1..53; the sign only sets direction, so
            // a zero ordinal or |ordinal| > 53 is not a valid weekdaynum.
            if (ordinal != null && (ordinal == 0 || ordinal < -53 || ordinal > 53)) {
                throw IllegalArgumentException("Invalid weekday ordinal: $value")
            }
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