package org.onekash.kashcal.ui.components.pickers

import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.ParsedRecurrence
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.RruleBuilder
import java.time.DayOfWeek

/**
 * Unit dimension for the Custom recurrence builder.
 *
 * Drives the segmented Day/Week/Month/Year control and selects
 * which auxiliary selectors are visible (weekday picker for WEEK,
 * monthly pattern picker for MONTH).
 */
enum class CustomRecurrenceUnit { DAY, WEEK, MONTH, YEAR }

/**
 * Decides which chip to highlight when the picker opens.
 *
 * INTERVAL=1 (or absent) canonicalises to the matching preset; any
 * INTERVAL>1 lands on CUSTOM so the original interval survives an
 * unedited save. RecurrenceFrequency.CUSTOM is the parse-sentinel
 * for rules that don't fit a simple bucket and always opens CUSTOM.
 */
fun selectInitialFrequencyOption(parsed: ParsedRecurrence): FrequencyOption {
    if (parsed.frequency == RecurrenceFrequency.NONE) return FrequencyOption.NEVER
    if (parsed.frequency == RecurrenceFrequency.CUSTOM) return FrequencyOption.CUSTOM
    // BY*-extras (BYMONTH/BYWEEKNO/BYYEARDAY/BYSETPOS) don't fit the
    // preset model — route to CUSTOM so emission goes through verbatim
    // re-append of the captured tokens instead of preset coercion.
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
 * Single source of truth for the picker's editable state.
 *
 * Lives outside the composable so it can be unit-tested without
 * Robolectric. Replaces the previous eight `var by remember(parsed)`
 * cells, which leaked a tri-state interval (originalInterval +
 * customInterval + userTouchedStepper) and let the chip detour path
 * silently reset an inbound `INTERVAL=200` to 1.
 *
 * Single [interval] field: stored verbatim from the parsed RRULE
 * (clamped to >= 1 only as a safety floor). The stepper UI clamps the
 * upper bound at 99 by disabling its '+' button — the holder never
 * does so, which is how `INTERVAL=200` round-trips a no-op save and
 * survives a Custom→preset→Custom chip detour.
 *
 * [startDayOfWeek] and [startDayOfMonth] are stored on the holder so
 * [toRrule] can fall back to a sensible default when the user opens a
 * MONTHLY rule whose monthlyPattern hasn't been picked yet.
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
     * WKST extracted from the inbound rule (null = rule omitted WKST). Stored
     * verbatim so a CalDAV-pulled `WKST=SU` survives a no-op edit. Without
     * this, opening such a rule on a Monday-week device and saving silently
     * rewrites it to `WKST=MO`, shifting occurrences for biweekly multi-day
     * rules where Sunday and Monday land in different ISO weeks.
     */
    val parsedWkst: DayOfWeek? = null,
    /**
     * RRULE parts the picker UI doesn't model directly (BYMONTH, BYWEEKNO,
     * BYYEARDAY, BYSETPOS). Captured verbatim from the inbound rule and
     * re-appended in [toRrule] so a CalDAV-pulled rule like `FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15`
     * round-trips through a no-op save without losing the BY* qualifier.
     * Empty for new rules and rules built entirely from picker state.
     */
    val extraTokens: List<String> = emptyList(),
) {
    fun toRrule(deviceWkst: DayOfWeek?): String? {
        // Prefer the inbound rule's WKST so an unedited save round-trips
        // exactly. The caller passes deviceWkst only for brand-new rules; for
        // loaded rules that omitted WKST it passes null so the omission is
        // preserved (RFC §3.3.10 default-MO anchoring stays intact). Without
        // that distinction, opening a CalDAV-pulled WEEKLY;INTERVAL=2;BYDAY=SA,SU
        // rule on a Sunday-first-day device would silently inject WKST=SU on
        // save and shift occurrence dates.
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
        // Append captured extras (BYMONTH/BYWEEKNO/BYYEARDAY/BYSETPOS) before
        // COUNT/UNTIL so a CalDAV-pulled rule round-trips with its qualifier
        // intact. The picker doesn't expose these as editable controls, so a
        // user who interacts with the rule keeps the same extras unless they
        // explicitly start over via Never.
        val withExtras = if (extraTokens.isEmpty()) base
        else "$base;${extraTokens.joinToString(";")}"
        return when (endCondition) {
            EndCondition.Never -> withExtras
            is EndCondition.Count -> RruleBuilder.withCount(withExtras, endCondition.count)
            is EndCondition.Until -> RruleBuilder.withUntil(withExtras, endCondition.dateMillis)
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
 * Reconciles weekday/monthly state when the user flips the segmented
 * unit control.
 *
 * Non-destructive: weekdays and monthlyPattern are preserved across
 * transitions so toggling the unit (e.g. MONTH→WEEK→MONTH) doesn't
 * silently lose user selections. The only mutation is the WEEK
 * seeding case — switching INTO WEEK with no current selection seeds
 * [startDayOfWeek] so the weekday picker isn't empty.
 *
 * The null-monthlyPattern fallback for the MONTH unit lives in
 * [RecurrencePickerSelections.toRrule] (uses startDayOfMonth there),
 * not here, so this function doesn't need startDayOfMonth.
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
