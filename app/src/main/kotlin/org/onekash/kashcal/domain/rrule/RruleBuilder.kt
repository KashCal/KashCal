package org.onekash.kashcal.domain.rrule

import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Holds the localized strings for [RruleBuilder.formatForDisplay]; `rememberRruleDisplayStrings`
 * builds them from string resources, and [english] gives the English defaults.
 */
data class RruleDisplayStrings(
    val doesNotRepeat: String,
    val freqDaily: String,
    val freqWeekly: String,
    val freqMonthly: String,
    val freqYearly: String,
    val repeats: String,
    val everyNDays: String,
    val everyNWeeks: String,
    val everyNMonths: String,
    val everyNYears: String,
    val freqOnDays: String,
    val freqOnOrdinalDay: String,
    val freqOnLastDay: String,
    val freqOnDayN: String,
    val ordinals: List<String>,
    val ordinalLast: String,
    val ordinalNth: String,
    val countSuffix: (Int) -> String,
    val untilSuffix: String
) {
    companion object {
        fun english() = RruleDisplayStrings(
            doesNotRepeat = "Does not repeat",
            freqDaily = "Daily",
            freqWeekly = "Weekly",
            freqMonthly = "Monthly",
            freqYearly = "Yearly",
            repeats = "Repeats",
            everyNDays = "Every %1\$d days",
            everyNWeeks = "Every %1\$d weeks",
            everyNMonths = "Every %1\$d months",
            everyNYears = "Every %1\$d years",
            freqOnDays = "%1\$s on %2\$s",
            freqOnOrdinalDay = "%1\$s on %2\$s %3\$s",
            freqOnLastDay = "%1\$s on last day",
            freqOnDayN = "%1\$s on day %2\$d",
            ordinals = listOf("1st", "2nd", "3rd", "4th"),
            ordinalLast = "last",
            ordinalNth = "%1\$dth",
            countSuffix = { count -> ", $count times" },
            untilSuffix = ", until %1\$s"
        )
    }
}

/**
 * Builds RFC 5545 RRULE strings from components, parses them back for the picker, and formats
 * them for display.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.10">RFC 5545 RRULE</a>
 */
object RruleBuilder {

    private val DAY_ABBREV = mapOf(
        DayOfWeek.SUNDAY to "SU",
        DayOfWeek.MONDAY to "MO",
        DayOfWeek.TUESDAY to "TU",
        DayOfWeek.WEDNESDAY to "WE",
        DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR",
        DayOfWeek.SATURDAY to "SA"
    )

    private val ABBREV_TO_DAY = DAY_ABBREV.entries.associate { (day, abbrev) -> abbrev to day }

    /** BYDAY output order, Monday first. */
    private val DAY_ORDER = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    )

    private val INTERVAL_REGEX = Regex("INTERVAL=(\\d+)")
    private val BYDAY_LIST_REGEX = Regex("BYDAY=([A-Z,]+)")
    private val BYDAY_NTH_REGEX = Regex("BYDAY=(-?\\d+)([A-Z]{2})")
    private val BYMONTHDAY_REGEX = Regex("BYMONTHDAY=(-?\\d+)")
    private val COUNT_REGEX = Regex("COUNT=(\\d+)")
    private val UNTIL_FULL_REGEX = Regex("UNTIL=(\\d{8}T\\d{6}Z?)")
    private val UNTIL_DATE_REGEX = Regex("UNTIL=(\\d{8})")
    private val WKST_REGEX = Regex("WKST=([A-Z]{2})")

    /**
     * BY* parts the picker models, per frequency. Every other BY* part becomes a
     * [ParsedRecurrence.extraTokens] entry.
     */
    private val CONSUMED_BY_TOKENS_BY_FREQ = mapOf(
        RecurrenceFrequency.WEEKLY to setOf("BYDAY"),
        RecurrenceFrequency.MONTHLY to setOf("BYDAY", "BYMONTHDAY"),
    )

    // ==================== Building RRULE Strings ====================

    /** Returns the RFC 5545 abbreviation of [day]. */
    fun toDayAbbrev(day: DayOfWeek): String = DAY_ABBREV[day] ?: "MO"

    /** Builds a daily rule, like "FREQ=DAILY" or "FREQ=DAILY;INTERVAL=2". */
    fun daily(interval: Int = 1): String {
        return if (interval == 1) "FREQ=DAILY"
        else "FREQ=DAILY;INTERVAL=$interval"
    }

    /**
     * Builds a weekly rule, like "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU,TH;WKST=SU".
     *
     * @param wkst week start, emitted only when `interval >= 2 && days.size >= 2`: per RFC 5545
     *   §3.3.10 WKST has no effect on an interval-1 or single-day weekly rule (#214).
     */
    fun weekly(
        interval: Int = 1,
        days: Set<DayOfWeek> = emptySet(),
        wkst: DayOfWeek? = null,
    ): String {
        val parts = mutableListOf("FREQ=WEEKLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (days.isNotEmpty()) {
            val sortedDays = DAY_ORDER.filter { it in days }
            parts.add("BYDAY=${sortedDays.joinToString(",") { toDayAbbrev(it) }}")
        }
        if (wkst != null && interval > 1 && days.size >= 2) {
            parts.add("WKST=${toDayAbbrev(wkst)}")
        }
        return parts.joinToString(";")
    }

    /**
     * Builds a monthly rule on a day of the month, like "FREQ=MONTHLY;BYMONTHDAY=15".
     *
     * @param dayOfMonth 1-31, or null for no BYMONTHDAY (the start date's day).
     */
    fun monthly(interval: Int = 1, dayOfMonth: Int? = null): String {
        val parts = mutableListOf("FREQ=MONTHLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (dayOfMonth != null) parts.add("BYMONTHDAY=$dayOfMonth")
        return parts.joinToString(";")
    }

    /** Builds a monthly rule on the last day of the month, like "FREQ=MONTHLY;BYMONTHDAY=-1". */
    fun monthlyLastDay(interval: Int = 1): String {
        val parts = mutableListOf("FREQ=MONTHLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        parts.add("BYMONTHDAY=-1")
        return parts.joinToString(";")
    }

    /**
     * Builds a monthly rule on the nth weekday, like "FREQ=MONTHLY;BYDAY=2TU".
     *
     * @param ordinal 1-4 for 1st-4th, -1 for last.
     */
    fun monthlyNthWeekday(ordinal: Int, weekday: DayOfWeek, interval: Int = 1): String {
        val parts = mutableListOf("FREQ=MONTHLY")
        if (interval > 1) parts.add("INTERVAL=$interval")
        val prefix = if (ordinal == -1) "-1" else ordinal.toString()
        parts.add("BYDAY=$prefix${toDayAbbrev(weekday)}")
        return parts.joinToString(";")
    }

    /** Builds a yearly rule, like "FREQ=YEARLY" or "FREQ=YEARLY;INTERVAL=2". */
    fun yearly(interval: Int = 1): String {
        return if (interval == 1) "FREQ=YEARLY"
        else "FREQ=YEARLY;INTERVAL=$interval"
    }

    /** Returns [rrule] with a COUNT of [count] appended. */
    fun withCount(rrule: String, count: Int): String {
        return "$rrule;COUNT=$count"
    }

    /** Returns [rrule] with [untilMillis] appended as a UTC date-time UNTIL. */
    fun withUntil(rrule: String, untilMillis: Long): String {
        val instant = Instant.ofEpochMilli(untilMillis)
        val utc = instant.atZone(ZoneOffset.UTC)
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        return "$rrule;UNTIL=${utc.format(formatter)}"
    }

    // ==================== Parsing RRULE Strings ====================

    /**
     * Returns the frequency of [rrule]: NONE for null or blank, CUSTOM for any INTERVAL, COUNT,
     * UNTIL or BYSETPOS part or an unknown FREQ.
     */
    fun parseFrequency(rrule: String?): RecurrenceFrequency {
        if (rrule.isNullOrBlank()) return RecurrenceFrequency.NONE

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
     * Parses [rrule] into the picker's [ParsedRecurrence]; an unknown FREQ parses as NONE.
     *
     * @param defaultWeekday weekday for an NthWeekday pattern whose day can't be read.
     * @param defaultDayOfMonth day for a monthly rule with neither an nth-weekday BYDAY nor a
     *   readable BYMONTHDAY.
     * @param defaultOrdinal ordinal (1-4 or -1) for an NthWeekday pattern whose ordinal can't be
     *   read.
     */
    fun parseRrule(
        rrule: String?,
        defaultWeekday: DayOfWeek,
        defaultDayOfMonth: Int,
        defaultOrdinal: Int
    ): ParsedRecurrence {
        if (rrule.isNullOrBlank()) return ParsedRecurrence()

        val tokens = rrule.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        val frequency = when {
            rrule.contains("FREQ=DAILY") -> RecurrenceFrequency.DAILY
            rrule.contains("FREQ=WEEKLY") -> RecurrenceFrequency.WEEKLY
            rrule.contains("FREQ=MONTHLY") -> RecurrenceFrequency.MONTHLY
            rrule.contains("FREQ=YEARLY") -> RecurrenceFrequency.YEARLY
            else -> RecurrenceFrequency.NONE
        }

        // How extras are kept on save: [ParsedRecurrence.extraTokens].
        val consumed = CONSUMED_BY_TOKENS_BY_FREQ[frequency].orEmpty()
        val extraTokens = tokens.filter { token ->
            val name = token.substringBefore('=')
            name.startsWith("BY") && name !in consumed
        }

        val intervalMatch = INTERVAL_REGEX.find(rrule)
        val interval = intervalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val bydayMatch = BYDAY_LIST_REGEX.find(rrule)
        val weekdays = if (bydayMatch != null) {
            bydayMatch.groupValues[1].split(",")
                .mapNotNull { ABBREV_TO_DAY[it] }
                .toSet()
        } else {
            emptySet()
        }

        val monthlyPattern: MonthlyPattern? = if (frequency == RecurrenceFrequency.MONTHLY) {
            val nthWeekdayMatch = BYDAY_NTH_REGEX.find(rrule)
            val byMonthdayMatch = BYMONTHDAY_REGEX.find(rrule)
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

        val countMatch = COUNT_REGEX.find(rrule)
        val untilFullMatch = UNTIL_FULL_REGEX.find(rrule)
        val untilDateMatch = if (untilFullMatch == null) UNTIL_DATE_REGEX.find(rrule) else null
        val endCondition: EndCondition = when {
            countMatch != null -> {
                val count = countMatch.groupValues[1].toIntOrNull() ?: 10
                EndCondition.Count(count)
            }
            untilFullMatch != null -> {
                try {
                    val dateTimeStr = untilFullMatch.groupValues[1]
                    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    val dateTime = LocalDateTime.parse(dateTimeStr, formatter)
                    val millis = dateTime.atZone(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                    EndCondition.Until(millis)
                } catch (_: Exception) {
                    EndCondition.Never
                }
            }
            untilDateMatch != null -> {
                // A date UNTIL (RFC 5545 §3.3.10, for a VALUE=DATE DTSTART). End of day UTC, so
                // the bound includes the named day.
                try {
                    val dateStr = untilDateMatch.groupValues[1]
                    val date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"))
                    val millis = date.atTime(23, 59, 59).atZone(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                    EndCondition.Until(millis)
                } catch (_: Exception) {
                    EndCondition.Never
                }
            }
            else -> EndCondition.Never
        }

        // The RFC 5545 §3.3.10 default is MO; an absent WKST stays null so callers can tell it
        // from an explicit WKST=MO. On emit the picker falls back to the device's week start
        // only for a new rule.
        val wkst = WKST_REGEX.find(rrule)
            ?.groupValues?.get(1)
            ?.let { ABBREV_TO_DAY[it] }

        return ParsedRecurrence(
            frequency = frequency,
            interval = interval,
            weekdays = weekdays,
            monthlyPattern = monthlyPattern,
            endCondition = endCondition,
            wkst = wkst,
            extraTokens = extraTokens,
        )
    }

    // ==================== Display Formatting ====================

    private fun localizedDayName(abbrev: String): String {
        val day = ABBREV_TO_DAY[abbrev] ?: return abbrev
        return day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    /**
     * Formats [rrule] for display with [RruleDisplayStrings.english]. Day names and the UNTIL
     * date follow the device locale; other labels are English.
     */
    fun formatForDisplay(rrule: String?): String {
        return formatForDisplay(rrule, RruleDisplayStrings.english())
    }

    /**
     * Formats [rrule] for display with [strings]. A UTC date-time UNTIL shows as its date in
     * [untilZone] (see [untilZoneFor]), or as its UTC date when null.
     *
     * Examples:
     * - "FREQ=DAILY" -> "Daily"
     * - "FREQ=WEEKLY;INTERVAL=2" -> "Every 2 weeks"
     * - "FREQ=WEEKLY;BYDAY=MO,WE,FR" -> "Weekly on Mon, Wed, Fri"
     * - "FREQ=MONTHLY;BYDAY=2TU" -> "Monthly on 2nd Tue"
     * - "FREQ=MONTHLY;BYMONTHDAY=-1" -> "Monthly on last day"
     */
    fun formatForDisplay(rrule: String?, strings: RruleDisplayStrings, untilZone: ZoneId? = null): String {
        if (rrule.isNullOrBlank()) return strings.doesNotRepeat

        val intervalMatch = INTERVAL_REGEX.find(rrule)
        val interval = intervalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val freq = when {
            rrule.contains("FREQ=DAILY") -> {
                if (interval > 1) String.format(Locale.getDefault(), strings.everyNDays, interval)
                else strings.freqDaily
            }
            rrule.contains("FREQ=WEEKLY") -> {
                val bydayMatch = BYDAY_LIST_REGEX.find(rrule)
                val base = when {
                    interval > 1 -> String.format(Locale.getDefault(), strings.everyNWeeks, interval)
                    else -> strings.freqWeekly
                }
                if (bydayMatch != null) {
                    val days = bydayMatch.groupValues[1].split(",")
                        .mapNotNull { localizedDayName(it).ifBlank { null } }
                    String.format(Locale.getDefault(), strings.freqOnDays, base, days.joinToString(", "))
                } else {
                    base
                }
            }
            rrule.contains("FREQ=MONTHLY") -> {
                val base = when {
                    interval > 1 -> String.format(Locale.getDefault(), strings.everyNMonths, interval)
                    else -> strings.freqMonthly
                }
                val bydayMatch = BYDAY_NTH_REGEX.find(rrule)
                val byMonthdayMatch = BYMONTHDAY_REGEX.find(rrule)
                when {
                    bydayMatch != null -> {
                        val ordinal = bydayMatch.groupValues[1]
                        val dayAbbrev = bydayMatch.groupValues[2]
                        val dayName = localizedDayName(dayAbbrev)
                        val ordinalLabel = when (ordinal) {
                            "-1" -> strings.ordinalLast
                            else -> {
                                val idx = (ordinal.toIntOrNull() ?: 0) - 1
                                if (idx in strings.ordinals.indices) strings.ordinals[idx]
                                else String.format(Locale.getDefault(), strings.ordinalNth, ordinal.toIntOrNull() ?: 0)
                            }
                        }
                        String.format(Locale.getDefault(), strings.freqOnOrdinalDay, base, ordinalLabel, dayName)
                    }
                    byMonthdayMatch != null -> {
                        val day = byMonthdayMatch.groupValues[1]
                        if (day == "-1") String.format(Locale.getDefault(), strings.freqOnLastDay, base)
                        else String.format(Locale.getDefault(), strings.freqOnDayN, base, day.toIntOrNull() ?: 0)
                    }
                    else -> base
                }
            }
            rrule.contains("FREQ=YEARLY") -> {
                if (interval > 1) String.format(Locale.getDefault(), strings.everyNYears, interval)
                else strings.freqYearly
            }
            else -> strings.repeats
        }

        val countMatch = COUNT_REGEX.find(rrule)
        val untilMatch = UNTIL_DATE_REGEX.find(rrule)
        val endSuffix = when {
            countMatch != null -> {
                val count = countMatch.groupValues[1].toIntOrNull() ?: 0
                strings.countSuffix(count)
            }
            untilMatch != null -> {
                val untilDate = untilDisplayDate(rrule, untilZone)
                if (untilDate == null) {
                    ""
                } else {
                    val currentYear = LocalDate.now().year
                    val pattern = if (untilDate.year == currentYear) DateTimeUtils.localizedPattern("MMMd") else DateTimeUtils.localizedPattern("yMMMd")
                    val formatted = untilDate.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
                    String.format(Locale.getDefault(), strings.untilSuffix, formatted)
                }
            }
            else -> ""
        }

        return freq + endSuffix
    }

    /**
     * Returns the date an RRULE's UNTIL names. A date UNTIL is taken as written. A UTC date-time
     * UNTIL is an instant (RFC 5545 section 3.3.10); with [untilZone] it is shown as the date it
     * falls on in that zone (the event's), otherwise as its UTC date. Callers pass null for
     * all-day events, which live in UTC ([untilZoneFor]). Second 60 (a leap second) is read as
     * 59 (RFC 5545 section 3.3.12); any other time that can't be read falls back to the date as
     * written, and a date that can't exist gives null.
     */
    private fun untilDisplayDate(rrule: String, untilZone: ZoneId?): LocalDate? {
        val full = UNTIL_FULL_REGEX.find(rrule)?.groupValues?.get(1)
        if (untilZone != null && full != null && full.endsWith("Z")) {
            val readable = if (full.substring(13, 15) == "60") full.replaceRange(13, 15, "59") else full
            try {
                // Strict, so a date that can't exist is rejected, not moved to the month's end
                val strict = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(java.time.format.ResolverStyle.STRICT)
                val utc = LocalDateTime.parse(readable, strict)
                return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(untilZone).toLocalDate()
            } catch (_: java.time.format.DateTimeParseException) {
                // fall through to the date as written
            }
        }
        val dateStr = UNTIL_DATE_REGEX.find(rrule)!!.groupValues[1]
        return try {
            LocalDate.of(dateStr.substring(0, 4).toInt(), dateStr.substring(4, 6).toInt(), dateStr.substring(6, 8).toInt())
        } catch (_: java.time.DateTimeException) {
            null // a date that can't exist: the summary leaves the end out
        }
    }

    /** Returns the zone a repeat end date is shown in: the event's, or null (UTC) if all-day. */
    fun untilZoneFor(isAllDay: Boolean, timezone: String?): ZoneId? =
        if (isAllDay) null else TimezoneUtils.resolveZone(timezone)

    /** Formats [rrule] like [formatForDisplayParts] with [RruleDisplayStrings.english]. */
    fun formatForDisplayParts(rrule: String?): Pair<String, String?> {
        return formatForDisplayParts(rrule, RruleDisplayStrings.english())
    }

    /**
     * Formats [rrule] like [formatForDisplay] but returns the frequency text and the end suffix
     * (or null) apart, so callers can insert content between them (e.g. the series start date).
     */
    fun formatForDisplayParts(rrule: String?, strings: RruleDisplayStrings, untilZone: ZoneId? = null): Pair<String, String?> {
        if (rrule.isNullOrBlank()) return strings.doesNotRepeat to null

        val countMatch = COUNT_REGEX.find(rrule)
        val untilMatch = UNTIL_DATE_REGEX.find(rrule)
        val full = formatForDisplay(rrule, strings, untilZone)

        return when {
            countMatch != null -> {
                val count = countMatch.groupValues[1].toIntOrNull() ?: 0
                val suffix = strings.countSuffix(count)
                val freq = full.removeSuffix(suffix)
                freq to suffix
            }
            untilMatch != null -> {
                val untilDate = untilDisplayDate(rrule, untilZone) ?: return full to null
                val currentYear = LocalDate.now().year
                val pattern = if (untilDate.year == currentYear) DateTimeUtils.localizedPattern("MMMd") else DateTimeUtils.localizedPattern("yMMMd")
                val formatted = untilDate.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
                val suffix = String.format(Locale.getDefault(), strings.untilSuffix, formatted)
                val freq = full.removeSuffix(suffix)
                freq to suffix
            }
            else -> full to null
        }
    }
}
