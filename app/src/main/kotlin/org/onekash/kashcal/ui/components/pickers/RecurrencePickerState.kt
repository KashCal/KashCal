package org.onekash.kashcal.ui.components.pickers

import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.ParsedRecurrence
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.util.RruleUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.time.DayOfWeek

/**
 * Lists the Custom builder's Day/Week/Month/Year units. WEEK also shows the weekday picker and
 * MONTH the monthly pattern picker.
 */
enum class CustomRecurrenceUnit { DAY, WEEK, MONTH, YEAR }

/**
 * Returns the chip to highlight when the picker opens.
 *
 * INTERVAL=1 or absent maps to the matching preset; INTERVAL>1 opens CUSTOM so the interval
 * survives an unedited save. [RecurrenceFrequency.CUSTOM] is the parse result for rules that fit
 * no simple bucket and always opens CUSTOM.
 */
fun selectInitialFrequencyOption(parsed: ParsedRecurrence): FrequencyOption {
    if (parsed.frequency == RecurrenceFrequency.NONE) return FrequencyOption.NEVER
    if (parsed.frequency == RecurrenceFrequency.CUSTOM) return FrequencyOption.CUSTOM
    // BYMONTH, BYWEEKNO, BYYEARDAY and BYSETPOS don't fit a preset, so open CUSTOM, whose
    // emission re-appends the captured tokens verbatim.
    if (parsed.extraTokens.isNotEmpty()) return FrequencyOption.CUSTOM
    val effectiveInterval = if (parsed.interval <= 0) 1 else parsed.interval
    if (effectiveInterval > 1) return FrequencyOption.CUSTOM
    return when (parsed.frequency) {
        RecurrenceFrequency.DAILY -> FrequencyOption.DAILY
        RecurrenceFrequency.WEEKLY -> FrequencyOption.WEEKLY
        RecurrenceFrequency.MONTHLY -> FrequencyOption.MONTHLY
        RecurrenceFrequency.YEARLY -> FrequencyOption.YEARLY
        else -> FrequencyOption.NEVER
    }
}

fun mapFrequencyToCustomUnit(freq: RecurrenceFrequency): CustomRecurrenceUnit = when (freq) {
    RecurrenceFrequency.DAILY -> CustomRecurrenceUnit.DAY
    RecurrenceFrequency.WEEKLY -> CustomRecurrenceUnit.WEEK
    RecurrenceFrequency.MONTHLY -> CustomRecurrenceUnit.MONTH
    RecurrenceFrequency.YEARLY -> CustomRecurrenceUnit.YEAR
    RecurrenceFrequency.NONE,
    RecurrenceFrequency.CUSTOM -> CustomRecurrenceUnit.WEEK
}

/**
 * Holds the recurrence picker's editable state; the source of truth for what it emits. Kept
 * outside the composable so it can be unit-tested without Robolectric.
 *
 * [interval] is the parsed INTERVAL, floored at 1 and never capped. The stepper caps new
 * input at 99 by disabling '+', so an inbound `INTERVAL=200` round-trips a no-op save and
 * survives a Custom, preset, Custom chip detour.
 *
 * [startDayOfMonth] is the day [toRrule] uses for a monthly rule when [monthlyPattern] is null.
 */
data class RecurrencePickerSelections(
    val frequencyOption: FrequencyOption,
    val interval: Int,
    val customUnit: CustomRecurrenceUnit,
    val weekdays: Set<DayOfWeek>,
    val monthlyPattern: MonthlyPattern?,
    val endCondition: EndCondition,
    val startDayOfWeek: DayOfWeek,
    val startDayOfMonth: Int,
    /**
     * WKST from the inbound rule, or null if it had none. Kept so a synced `WKST=SU` survives
     * a save; dropping it would fall back to the default `WKST=MO`, shifting occurrences of
     * biweekly multi-day rules where Sunday and Monday land in different weeks. [toRrule] emits
     * it only where [RruleBuilder.weekly] writes a WKST: a Custom weekly rule with an interval
     * over 1 and two or more days.
     */
    val parsedWkst: DayOfWeek? = null,
    /**
     * RRULE parts the picker doesn't model (BYMONTH, BYWEEKNO, BYYEARDAY, BYSETPOS), captured
     * verbatim and re-appended by [toRrule] so a synced rule like
     * `FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15` survives a no-op save. Empty for new rules.
     */
    val extraTokens: List<String> = emptyList(),
) {
    fun toRrule(deviceWkst: DayOfWeek?, isAllDay: Boolean = false): String? {
        // The inbound rule's WKST wins so an unedited save round-trips. The caller passes
        // deviceWkst only for new rules; a loaded rule without WKST gets null and keeps the
        // RFC 5545 §3.3.10 default of MO. Otherwise a synced WEEKLY;INTERVAL=2;BYDAY=SA,SU
        // rule saved on a Sunday-first device would silently gain WKST=SU and shift dates.
        val effectiveWkst = parsedWkst ?: deviceWkst
        val base = when (frequencyOption) {
            FrequencyOption.NEVER -> return null
            FrequencyOption.DAILY -> RruleBuilder.daily()
            FrequencyOption.WEEKLY -> RruleBuilder.weekly(days = weekdays)
            FrequencyOption.MONTHLY -> buildMonthly(monthlyPattern, interval = 1, startDayOfMonth)
            FrequencyOption.YEARLY -> RruleBuilder.yearly()
            FrequencyOption.CUSTOM -> when (customUnit) {
                CustomRecurrenceUnit.DAY -> RruleBuilder.daily(interval)
                CustomRecurrenceUnit.WEEK -> RruleBuilder.weekly(interval, weekdays, effectiveWkst)
                CustomRecurrenceUnit.MONTH -> buildMonthly(monthlyPattern, interval, startDayOfMonth)
                CustomRecurrenceUnit.YEAR -> RruleBuilder.yearly(interval)
            }
        }
        // Append the captured extras before COUNT/UNTIL. The picker has no controls for them,
        // so every rule it emits for this holder keeps them, including one picked after Never.
        val withExtras = if (extraTokens.isEmpty()) base
        else "$base;${extraTokens.joinToString(";")}"
        return when (endCondition) {
            EndCondition.Never -> withExtras
            is EndCondition.Count -> RruleBuilder.withCount(withExtras, endCondition.count)
            is EndCondition.Until -> if (isAllDay) {
                // An all-day DTSTART is a DATE, so UNTIL is a DATE too (RFC 5545 section 3.3.10).
                "$withExtras;UNTIL=${RruleUtils.formatUntilDate(endCondition.dateMillis, isAllDay = true)}"
            } else {
                RruleBuilder.withUntil(withExtras, endCondition.dateMillis)
            }
        }
    }

    companion object {
        fun from(
            parsed: ParsedRecurrence,
            startDayOfWeek: DayOfWeek,
            startDayOfMonth: Int,
        ): RecurrencePickerSelections = RecurrencePickerSelections(
            frequencyOption = selectInitialFrequencyOption(parsed),
            interval = parsed.interval.coerceAtLeast(1),
            customUnit = mapFrequencyToCustomUnit(parsed.frequency),
            weekdays = parsed.weekdays.ifEmpty { setOf(startDayOfWeek) },
            monthlyPattern = parsed.monthlyPattern ?: MonthlyPattern.SameDay(startDayOfMonth),
            endCondition = parsed.endCondition,
            startDayOfWeek = startDayOfWeek,
            startDayOfMonth = startDayOfMonth,
            parsedWkst = parsed.wkst,
            extraTokens = parsed.extraTokens,
        )
    }
}

private fun buildMonthly(pattern: MonthlyPattern?, interval: Int, startDayOfMonth: Int): String = when (pattern) {
    null -> RruleBuilder.monthly(interval, startDayOfMonth)
    is MonthlyPattern.SameDay -> RruleBuilder.monthly(interval, pattern.dayOfMonth)
    is MonthlyPattern.LastDay -> RruleBuilder.monthlyLastDay(interval)
    is MonthlyPattern.NthWeekday -> RruleBuilder.monthlyNthWeekday(pattern.ordinal, pattern.weekday, interval)
}

/**
 * Returns the weekdays and monthly pattern to keep when the user switches the Custom unit.
 *
 * Both are kept, so MONTH to WEEK to MONTH loses no selection. The one change: switching into
 * WEEK with no weekdays seeds [startDayOfWeek] so the weekday picker isn't empty. A null
 * pattern for MONTH is resolved by [RecurrencePickerSelections.toRrule].
 */
fun applyUnitTransition(
    previous: CustomRecurrenceUnit,
    new: CustomRecurrenceUnit,
    weekdays: Set<DayOfWeek>,
    monthlyPattern: MonthlyPattern?,
    startDayOfWeek: DayOfWeek,
): Pair<Set<DayOfWeek>, MonthlyPattern?> {
    if (previous == new) return weekdays to monthlyPattern
    val newWeekdays = if (new == CustomRecurrenceUnit.WEEK && weekdays.isEmpty()) {
        setOf(startDayOfWeek)
    } else {
        weekdays
    }
    return newWeekdays to monthlyPattern
}

/**
 * Returns the UNTIL value for an end date the user picked (a device-local date, as the
 * picker's grid returns it). UNTIL bounds the rule inclusively and matches DTSTART's value
 * type (RFC 5545 section 3.3.10), and the picked date means that day in the event's own
 * timezone:
 * - timed: the last second of that day in [timezone], as an instant (written as UTC);
 * - all-day: that date's last second in UTC, which [RecurrencePickerSelections.toRrule]
 *   writes as a date.
 */
internal fun untilForPickedDate(dateMillis: Long, isAllDay: Boolean, timezone: String?): Long {
    val day = java.time.Instant.ofEpochMilli(dateMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val zone = if (isAllDay) java.time.ZoneOffset.UTC else TimezoneUtils.resolveZone(timezone)
    return day.plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant().toEpochMilli()
}

/**
 * Returns the date an UNTIL value ends on, as a device-local midnight for the picker's
 * grid and label: the UTC date for all-day rules, else the date in [timezone].
 */
internal fun untilDisplayMillis(untilMillis: Long, isAllDay: Boolean, timezone: String?): Long {
    val zone = if (isAllDay) java.time.ZoneOffset.UTC else TimezoneUtils.resolveZone(timezone)
    val day = java.time.Instant.ofEpochMilli(untilMillis).atZone(zone).toLocalDate()
    return day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Returns the default UNTIL: the end of the day one year after the start date (a form date). */
internal fun defaultUntilMillis(startDateMillis: Long, isAllDay: Boolean, timezone: String?): Long {
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(startDateMillis).atZone(zone).toLocalDate()
    val yearLater = start.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return untilForPickedDate(yearLater, isAllDay, timezone)
}

/**
 * Returns true when two rules differ only in their UNTIL value, as when an all-day toggle
 * re-expresses the end date. Both must have an UNTIL.
 */
internal fun onlyUntilDiffers(a: String?, b: String?): Boolean {
    if (a == null || b == null || a == b) return false
    val until = Regex("UNTIL=[^;]*")
    if (!until.containsMatchIn(a) || !until.containsMatchIn(b)) return false
    return until.replace(a, "UNTIL=") == until.replace(b, "UNTIL=")
}
