package org.onekash.kashcal.domain.rrule

import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Localized strings for [RruleBuilder.formatForDisplay].
 * Constructed from Android string resources by composable callers.
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

    private val INTERVAL_REGEX = Regex("INTERVAL=(\\d+)")
    private val BYDAY_LIST_REGEX = Regex("BYDAY=([A-Z,]+)")
    private val BYDAY_NTH_REGEX = Regex("BYDAY=(-?\\d+)([A-Z]{2})")
    private val BYMONTHDAY_REGEX = Regex("BYMONTHDAY=(-?\\d+)")
    private val COUNT_REGEX = Regex("COUNT=(\\d+)")
    private val UNTIL_FULL_REGEX = Regex("UNTIL=(\\d{8}T\\d{6}Z?)")
    private val UNTIL_DATE_REGEX = Regex("UNTIL=(\\d{8})")
    private val WKST_REGEX = Regex("WKST=([A-Z]{2})")

    /**
     * BY* token names the picker UI consumes for a given frequency. Anything
     * else (BYMONTH/BYWEEKNO/BYYEARDAY/BYSETPOS, plus BYMONTHDAY/BYDAY on
     * frequencies where the picker doesn't render them) gets captured as an
     * extra and re-appended on emission so CalDAV-pulled rules like "every
     * Jan 15" (FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15) round-trip a no-op save.
     */
    private val CONSUMED_BY_TOKENS_BY_FREQ = mapOf(
        RecurrenceFrequency.WEEKLY to setOf("BYDAY"),
        RecurrenceFrequency.MONTHLY to setOf("BYDAY", "BYMONTHDAY"),
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
     * @param wkst Week-start day. Emitted only when `interval >= 2 && days.size >= 2`
     *   per RFC 5545 §3.3.10 (WKST has no effect on interval=1 or single-day weekly
     *   rules). Issue #214.
     * @return RRULE string like "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU,TH;WKST=SU"
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

        val tokens = rrule.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        // Parse frequency
        val frequency = when {
            rrule.contains("FREQ=DAILY") -> RecurrenceFrequency.DAILY
            rrule.contains("FREQ=WEEKLY") -> RecurrenceFrequency.WEEKLY
            rrule.contains("FREQ=MONTHLY") -> RecurrenceFrequency.MONTHLY
            rrule.contains("FREQ=YEARLY") -> RecurrenceFrequency.YEARLY
            else -> RecurrenceFrequency.NONE
        }

        // Capture any BY* token the picker doesn't model for this frequency.
        // selectInitialFrequencyOption routes any rule with extras to
        // FrequencyOption.CUSTOM so emission goes through the unit-keyed
        // builder + extras append, not a preset coercion.
        val consumed = CONSUMED_BY_TOKENS_BY_FREQ[frequency].orEmpty()
        val extraTokens = tokens.filter { token ->
            val name = token.substringBefore('=')
            name.startsWith("BY") && name !in consumed
        }

        // Parse interval
        val intervalMatch = INTERVAL_REGEX.find(rrule)
        val interval = intervalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Parse weekdays (for weekly)
        val bydayMatch = BYDAY_LIST_REGEX.find(rrule)
        val weekdays = if (bydayMatch != null) {
            bydayMatch.groupValues[1].split(",")
                .mapNotNull { ABBREV_TO_DAY[it] }
                .toSet()
        } else {
            emptySet()
        }

        // Parse monthly pattern
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

        // Parse end condition
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
                // RFC 5545 §3.3.10 date-value UNTIL (valid when DTSTART is VALUE=DATE).
                // Anchor to end-of-day UTC so the bound includes the named day.
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

        // Parse WKST. RFC 5545 §3.3.10 default is MO; we represent absence as
        // null so callers can tell "rule omitted WKST" from "rule said WKST=MO".
        // Picker uses null-means-fallback-to-device on emit.
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
     * Format RRULE for human-readable display using English defaults.
     * Day names are locale-aware via java.time; all other labels are English.
     * Use the overload with [RruleDisplayStrings] for full localization.
     */
    fun formatForDisplay(rrule: String?): String {
        return formatForDisplay(rrule, RruleDisplayStrings.english())
    }

    /**
     * Format RRULE for human-readable display with localized strings.
     *
     * Examples:
     * - "FREQ=DAILY" -> "Daily"
     * - "FREQ=WEEKLY;INTERVAL=2" -> "Every 2 weeks"
     * - "FREQ=WEEKLY;BYDAY=MO,WE,FR" -> "Weekly on Mon, Wed, Fri"
     * - "FREQ=MONTHLY;BYDAY=2TU" -> "Monthly on 2nd Tue"
     * - "FREQ=MONTHLY;BYMONTHDAY=-1" -> "Monthly on last day"
     *
     * @param rrule RRULE string to format
     * @param strings Localized display strings
     * @return Human-readable description
     */
    fun formatForDisplay(rrule: String?, strings: RruleDisplayStrings): String {
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
                val dateStr = untilMatch.groupValues[1]
                val untilDate = LocalDate.of(
                    dateStr.substring(0, 4).toInt(),
                    dateStr.substring(4, 6).toInt(),
                    dateStr.substring(6, 8).toInt()
                )
                val currentYear = LocalDate.now().year
                val pattern = if (untilDate.year == currentYear) DateTimeUtils.localizedPattern("MMMd") else DateTimeUtils.localizedPattern("yMMMd")
                val formatted = untilDate.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
                String.format(Locale.getDefault(), strings.untilSuffix, formatted)
            }
            else -> ""
        }

        return freq + endSuffix
    }

    /**
     * Format RRULE for display, returning frequency and end condition as separate parts.
     * Uses English defaults. Use the overload with [RruleDisplayStrings] for full localization.
     */
    fun formatForDisplayParts(rrule: String?): Pair<String, String?> {
        return formatForDisplayParts(rrule, RruleDisplayStrings.english())
    }

    /**
     * Format RRULE for display, returning frequency and end condition as separate parts.
     * Enables callers to insert content between them (e.g., series start date).
     *
     * @param rrule RRULE string to format
     * @param strings Localized display strings
     * @return Pair of (frequency text, end suffix or null)
     */
    fun formatForDisplayParts(rrule: String?, strings: RruleDisplayStrings): Pair<String, String?> {
        if (rrule.isNullOrBlank()) return strings.doesNotRepeat to null

        val countMatch = COUNT_REGEX.find(rrule)
        val untilMatch = UNTIL_DATE_REGEX.find(rrule)
        val full = formatForDisplay(rrule, strings)

        return when {
            countMatch != null -> {
                val count = countMatch.groupValues[1].toIntOrNull() ?: 0
                val suffix = strings.countSuffix(count)
                val freq = full.removeSuffix(suffix)
                freq to suffix
            }
            untilMatch != null -> {
                val dateStr = untilMatch.groupValues[1]
                val untilDate = LocalDate.of(
                    dateStr.substring(0, 4).toInt(),
                    dateStr.substring(4, 6).toInt(),
                    dateStr.substring(6, 8).toInt()
                )
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
