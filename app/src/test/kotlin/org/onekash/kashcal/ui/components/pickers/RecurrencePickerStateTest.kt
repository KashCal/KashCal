package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.ParsedRecurrence
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.rrule.toFrequency
import java.time.DayOfWeek

/**
 * Pure-helper tests for the recurrence picker state mapping logic.
 *
 * Covers selectInitialFrequencyOption, mapFrequencyToCustomUnit,
 * applyUnitTransition, FrequencyOption.toFrequency(), and the
 * RecurrencePickerSelections holder — the state that previously lived
 * inline in the composable and is now extracted for direct testability
 * without Robolectric.
 */
class RecurrencePickerStateTest {

    // ==================== selectInitialFrequencyOption ====================

    @Test
    fun `NONE maps to NEVER`() {
        assertEquals(
            FrequencyOption.NEVER,
            selectInitialFrequencyOption(ParsedRecurrence(frequency = RecurrenceFrequency.NONE))
        )
    }

    @Test
    fun `DAILY interval 1 maps to DAILY preset`() {
        assertEquals(
            FrequencyOption.DAILY,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.DAILY, interval = 1)
            )
        )
    }

    @Test
    fun `WEEKLY interval 1 maps to WEEKLY preset`() {
        assertEquals(
            FrequencyOption.WEEKLY,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1)
            )
        )
    }

    @Test
    fun `MONTHLY interval 1 maps to MONTHLY preset`() {
        assertEquals(
            FrequencyOption.MONTHLY,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.MONTHLY, interval = 1)
            )
        )
    }

    @Test
    fun `YEARLY interval 1 maps to YEARLY preset`() {
        assertEquals(
            FrequencyOption.YEARLY,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.YEARLY, interval = 1)
            )
        )
    }

    @Test
    fun `WEEKLY interval 2 maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 2)
            )
        )
    }

    @Test
    fun `WEEKLY interval 4 maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 4)
            )
        )
    }

    @Test
    fun `MONTHLY interval 3 maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.MONTHLY, interval = 3)
            )
        )
    }

    @Test
    fun `MONTHLY interval 6 maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.MONTHLY, interval = 6)
            )
        )
    }

    @Test
    fun `DAILY interval 10 maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.DAILY, interval = 10)
            )
        )
    }

    @Test
    fun `YEARLY interval 2 maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.YEARLY, interval = 2)
            )
        )
    }

    @Test
    fun `WEEKLY interval 200 from CalDAV server maps to CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 200)
            )
        )
    }

    @Test
    fun `WEEKLY interval 0 canonicalises to WEEKLY preset`() {
        assertEquals(
            FrequencyOption.WEEKLY,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 0)
            )
        )
    }

    @Test
    fun `RecurrenceFrequency CUSTOM parse-sentinel maps to FrequencyOption CUSTOM`() {
        assertEquals(
            FrequencyOption.CUSTOM,
            selectInitialFrequencyOption(
                ParsedRecurrence(frequency = RecurrenceFrequency.CUSTOM, interval = 1)
            )
        )
    }

    // ==================== mapFrequencyToCustomUnit ====================

    @Test
    fun `mapFrequencyToCustomUnit covers DAILY WEEKLY MONTHLY YEARLY`() {
        assertEquals(CustomRecurrenceUnit.DAY, mapFrequencyToCustomUnit(RecurrenceFrequency.DAILY))
        assertEquals(CustomRecurrenceUnit.WEEK, mapFrequencyToCustomUnit(RecurrenceFrequency.WEEKLY))
        assertEquals(CustomRecurrenceUnit.MONTH, mapFrequencyToCustomUnit(RecurrenceFrequency.MONTHLY))
        assertEquals(CustomRecurrenceUnit.YEAR, mapFrequencyToCustomUnit(RecurrenceFrequency.YEARLY))
    }

    @Test
    fun `mapFrequencyToCustomUnit defaults NONE and CUSTOM to WEEK`() {
        assertEquals(CustomRecurrenceUnit.WEEK, mapFrequencyToCustomUnit(RecurrenceFrequency.NONE))
        assertEquals(CustomRecurrenceUnit.WEEK, mapFrequencyToCustomUnit(RecurrenceFrequency.CUSTOM))
    }

    // ==================== toRrule with COUNT end condition ====================

    @Test
    fun `toRrule WEEKLY with COUNT appends COUNT token`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 1,
                weekdays = setOf(DayOfWeek.MONDAY),
                endCondition = EndCondition.Count(10),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(DayOfWeek.SUNDAY)
        assertTrue("expected COUNT=10, got $rrule", rrule!!.contains("COUNT=10"))
    }

    @Test
    fun `toRrule CUSTOM Week interval 3 with multiple weekdays emits BYDAY`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 3,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(DayOfWeek.SUNDAY)!!
        assertTrue("expected INTERVAL=3, got $rrule", rrule.contains("INTERVAL=3"))
        assertTrue("expected BYDAY=MO,WE,FR, got $rrule", rrule.contains("BYDAY=MO,WE,FR"))
        assertTrue("expected FREQ=WEEKLY, got $rrule", rrule.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `toRrule CUSTOM Year interval 2 emits FREQ YEARLY INTERVAL 2`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.YEARLY, interval = 2),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals("FREQ=YEARLY;INTERVAL=2", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule MONTHLY preset emits no INTERVAL token`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                monthlyPattern = MonthlyPattern.SameDay(15),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule YEARLY preset emits no INTERVAL token`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.YEARLY, interval = 1),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals("FREQ=YEARLY", selections.toRrule(DayOfWeek.SUNDAY))
    }

    // ==================== applyUnitTransition ====================

    @Test
    fun `applyUnitTransition Week to Month preserves multi-day BYDAY for re-entry`() {
        val initialWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val (weekdays, monthly) = applyUnitTransition(
            previous = CustomRecurrenceUnit.WEEK,
            new = CustomRecurrenceUnit.MONTH,
            weekdays = initialWeekdays,
            monthlyPattern = MonthlyPattern.SameDay(15),
            startDayOfWeek = DayOfWeek.MONDAY,
        )
        // After Week→Month, weekdays must be preserved AS-IS so coming back to Week
        // doesn't lose the user's selection.
        assertEquals(initialWeekdays, weekdays)
        // Monthly pattern carried in is preserved (not reset to a default).
        assertEquals(MonthlyPattern.SameDay(15), monthly)
    }

    @Test
    fun `applyUnitTransition Month to Year preserves monthly pattern for re-entry`() {
        val (_, monthly) = applyUnitTransition(
            previous = CustomRecurrenceUnit.MONTH,
            new = CustomRecurrenceUnit.YEAR,
            weekdays = emptySet(),
            monthlyPattern = MonthlyPattern.LastDay,
            startDayOfWeek = DayOfWeek.MONDAY,
        )
        // Even though Year unit doesn't render a monthly pattern, the value must
        // round-trip back if the user returns to Month — preserved AS-IS.
        assertEquals(MonthlyPattern.LastDay, monthly)
    }

    @Test
    fun `applyUnitTransition Day to Week initialises weekdays to start day`() {
        val (weekdays, _) = applyUnitTransition(
            previous = CustomRecurrenceUnit.DAY,
            new = CustomRecurrenceUnit.WEEK,
            weekdays = emptySet(),
            monthlyPattern = MonthlyPattern.SameDay(15),
            startDayOfWeek = DayOfWeek.WEDNESDAY,
        )
        assertEquals(setOf(DayOfWeek.WEDNESDAY), weekdays)
    }

    @Test
    fun `applyUnitTransition Day to Week with existing weekdays does not overwrite`() {
        val existing = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val (weekdays, _) = applyUnitTransition(
            previous = CustomRecurrenceUnit.DAY,
            new = CustomRecurrenceUnit.WEEK,
            weekdays = existing,
            monthlyPattern = null,
            startDayOfWeek = DayOfWeek.FRIDAY,
        )
        assertEquals(existing, weekdays)
    }

    @Test
    fun `applyUnitTransition same unit is idempotent`() {
        val initialWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        val (weekdays, monthly) = applyUnitTransition(
            previous = CustomRecurrenceUnit.WEEK,
            new = CustomRecurrenceUnit.WEEK,
            weekdays = initialWeekdays,
            monthlyPattern = MonthlyPattern.SameDay(15),
            startDayOfWeek = DayOfWeek.MONDAY,
        )
        assertEquals(initialWeekdays, weekdays)
        assertEquals(MonthlyPattern.SameDay(15), monthly)
    }

    @Test
    fun `applyUnitTransition MONTH to WEEK to MONTH preserves LastDay across two transitions`() {
        val initialPattern = MonthlyPattern.LastDay
        val (weekdays1, monthly1) = applyUnitTransition(
            previous = CustomRecurrenceUnit.MONTH,
            new = CustomRecurrenceUnit.WEEK,
            weekdays = emptySet(),
            monthlyPattern = initialPattern,
            startDayOfWeek = DayOfWeek.MONDAY,
        )
        // Coming back to MONTH must restore LastDay, not reset to SameDay default.
        val (_, monthly2) = applyUnitTransition(
            previous = CustomRecurrenceUnit.WEEK,
            new = CustomRecurrenceUnit.MONTH,
            weekdays = weekdays1,
            monthlyPattern = monthly1,
            startDayOfWeek = DayOfWeek.MONDAY,
        )
        assertEquals(MonthlyPattern.LastDay, monthly2)
    }

    @Test
    fun `applyUnitTransition WEEK to MONTH to WEEK preserves multi-day BYDAY across two transitions`() {
        val initialDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val (weekdays1, monthly1) = applyUnitTransition(
            previous = CustomRecurrenceUnit.WEEK,
            new = CustomRecurrenceUnit.MONTH,
            weekdays = initialDays,
            monthlyPattern = null,
            startDayOfWeek = DayOfWeek.TUESDAY, // start day NOT in initialDays — was the bug trigger
        )
        val (weekdays2, _) = applyUnitTransition(
            previous = CustomRecurrenceUnit.MONTH,
            new = CustomRecurrenceUnit.WEEK,
            weekdays = weekdays1,
            monthlyPattern = monthly1,
            startDayOfWeek = DayOfWeek.TUESDAY,
        )
        assertEquals(initialDays, weekdays2)
    }

    // ==================== FrequencyOption.toFrequency() ====================

    @Test
    fun `toFrequency NEVER returns null`() {
        assertNull(FrequencyOption.NEVER.toFrequency())
    }

    @Test
    fun `toFrequency DAILY returns DAILY`() {
        assertEquals(RecurrenceFrequency.DAILY, FrequencyOption.DAILY.toFrequency())
    }

    @Test
    fun `toFrequency WEEKLY returns WEEKLY`() {
        assertEquals(RecurrenceFrequency.WEEKLY, FrequencyOption.WEEKLY.toFrequency())
    }

    @Test
    fun `toFrequency MONTHLY returns MONTHLY`() {
        assertEquals(RecurrenceFrequency.MONTHLY, FrequencyOption.MONTHLY.toFrequency())
    }

    @Test
    fun `toFrequency YEARLY returns YEARLY`() {
        assertEquals(RecurrenceFrequency.YEARLY, FrequencyOption.YEARLY.toFrequency())
    }

    @Test
    fun `toFrequency CUSTOM returns null`() {
        assertNull(FrequencyOption.CUSTOM.toFrequency())
    }

    // ==================== RecurrencePickerSelections.from() ====================

    @Test
    fun `from NONE produces NEVER option with safe defaults`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.NONE),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.NEVER, selections.frequencyOption)
        assertEquals(1, selections.interval)
    }

    @Test
    fun `from DAILY interval 1 produces DAILY preset with interval 1`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.DAILY, interval = 1),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.DAILY, selections.frequencyOption)
        assertEquals(1, selections.interval)
    }

    @Test
    fun `from WEEKLY interval 4 produces CUSTOM with interval 4 (no clamp on input)`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 4),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.CUSTOM, selections.frequencyOption)
        assertEquals(4, selections.interval)
        assertEquals(CustomRecurrenceUnit.WEEK, selections.customUnit)
    }

    @Test
    fun `from WEEKLY interval 200 preserves interval 200 verbatim (no clamp)`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 200),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.CUSTOM, selections.frequencyOption)
        assertEquals(
            "expected interval=200 preserved on input, got ${selections.interval}",
            200,
            selections.interval
        )
    }

    @Test
    fun `from MONTHLY interval 6 produces CUSTOM Month with interval 6`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.MONTHLY, interval = 6),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.CUSTOM, selections.frequencyOption)
        assertEquals(6, selections.interval)
        assertEquals(CustomRecurrenceUnit.MONTH, selections.customUnit)
    }

    @Test
    fun `from interval 0 canonicalises to interval 1`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 0),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(1, selections.interval)
    }

    @Test
    fun `from RecurrenceFrequency CUSTOM sentinel produces CUSTOM option`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.CUSTOM, interval = 1),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.CUSTOM, selections.frequencyOption)
    }

    @Test
    fun `from preserves weekdays and monthlyPattern from parsed`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val pattern = MonthlyPattern.LastDay
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = weekdays,
                monthlyPattern = pattern,
            ),
            startDayOfWeek = DayOfWeek.TUESDAY,
            startDayOfMonth = 15,
        )
        assertEquals(weekdays, selections.weekdays)
        assertEquals(pattern, selections.monthlyPattern)
    }

    @Test
    fun `from seeds weekdays to start day when parsed weekdays empty`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1),
            startDayOfWeek = DayOfWeek.TUESDAY,
            startDayOfMonth = 15,
        )
        assertEquals(setOf(DayOfWeek.TUESDAY), selections.weekdays)
    }

    @Test
    fun `from stores startDayOfWeek and startDayOfMonth on holder`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.WEEKLY, interval = 1),
            startDayOfWeek = DayOfWeek.WEDNESDAY,
            startDayOfMonth = 22,
        )
        assertEquals(DayOfWeek.WEDNESDAY, selections.startDayOfWeek)
        assertEquals(22, selections.startDayOfMonth)
    }

    // ==================== RecurrencePickerSelections.toRrule() ====================

    @Test
    fun `toRrule NEVER returns null`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.NONE),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertNull(selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule DAILY preset emits no INTERVAL token`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.DAILY, interval = 1),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals("FREQ=DAILY", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule WEEKLY preset emits no INTERVAL token`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 1,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule CUSTOM preserves interval 200 verbatim (no output clamp)`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 200,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(DayOfWeek.SUNDAY)
        assertTrue("expected INTERVAL=200, got $rrule", rrule!!.contains("INTERVAL=200"))
    }

    @Test
    fun `toRrule CUSTOM Day interval 4 emits FREQ DAILY INTERVAL 4`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(frequency = RecurrenceFrequency.DAILY, interval = 4),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals("FREQ=DAILY;INTERVAL=4", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule CUSTOM Month interval 6 SameDay emits FREQ MONTHLY INTERVAL 6 BYMONTHDAY`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 6,
                monthlyPattern = MonthlyPattern.SameDay(15),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(
            "FREQ=MONTHLY;INTERVAL=6;BYMONTHDAY=15",
            selections.toRrule(DayOfWeek.SUNDAY)
        )
    }

    @Test
    fun `toRrule CUSTOM Month with null monthlyPattern falls back to SameDay startDayOfMonth`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 3,
                monthlyPattern = null,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 22,
        ).copy(monthlyPattern = null)
        val rrule = selections.toRrule(DayOfWeek.SUNDAY)
        assertEquals("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=22", rrule)
    }

    // ==================== Chip detour: 200 survives Custom→Weekly→Custom ====================

    @Test
    fun `chip detour preserves interval 200 across Custom to Weekly to Custom`() {
        val initial = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 200,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val afterWeeklyTap = initial.copy(frequencyOption = FrequencyOption.WEEKLY)
        val afterCustomTap = afterWeeklyTap.copy(frequencyOption = FrequencyOption.CUSTOM)

        val rrule = afterCustomTap.toRrule(DayOfWeek.SUNDAY)
        assertTrue(
            "expected INTERVAL=200 preserved through chip detour, got $rrule",
            rrule!!.contains("INTERVAL=200")
        )
    }

    @Test
    fun `chip detour through parent recomposition loses interval without echo guard`() {
        // Simulates the picker's full lifecycle: holder emits a preset RRULE
        // → parent stores it → parent recomposes with selectedRrule=newRrule
        // → remember(parsed) re-keys → from(parsed) rebuilds the holder.
        // The .copy()-only test (above) misses this because it never re-runs
        // from(parsed). This is the surface where the chip-detour bug lives.
        val initial = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 200,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )

        // User taps Weekly chip → holder emits clean preset RRULE
        val afterWeeklyTap = initial.copy(frequencyOption = FrequencyOption.WEEKLY)
        val emittedAfterWeekly = afterWeeklyTap.toRrule(DayOfWeek.SUNDAY)!!

        // Parent recomposes with the emitted value → from(parsed) rebuilds the
        // holder. The naive (no-guard) reload path resets interval to 1.
        val reparsed = org.onekash.kashcal.domain.rrule.RruleBuilder.parseRrule(
            emittedAfterWeekly,
            defaultWeekday = DayOfWeek.MONDAY,
            defaultDayOfMonth = 15,
            defaultOrdinal = 3,
        )
        val rebuilt = RecurrencePickerSelections.from(
            parsed = reparsed,
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )

        // User taps Custom chip → if the rebuild happened, interval is now 1
        val afterCustomTap = rebuilt.copy(frequencyOption = FrequencyOption.CUSTOM)
        val finalRrule = afterCustomTap.toRrule(DayOfWeek.SUNDAY)!!

        // Documents the lossy-reload behavior: 200 is gone after the round-trip.
        // The fix is in the picker (self-echo guard skips the reload), not in
        // the holder. This test is a guard against accidentally fixing it
        // by changing toRrule() preset semantics — the compose tests that drive
        // the actual user surface and assert 200 SURVIVES are
        // chipDetour_preservesInterval200_throughWeeklyThenCustom in
        // RecurrencePickerChipDetourComposeTest (Robolectric, PR-gated) and
        // chipDetour_preservesInterval200_throughChipClicks in
        // RecurrencePickerComposeTest (on-device instrumentation).
        assertTrue(
            "lifecycle simulation: parent round-trip loses interval (fix lives in RecurrencePickerRow), got $finalRrule",
            !finalRrule.contains("INTERVAL=200"),
        )
    }

    // ==================== Stepper edit: 200 → 99 single-source semantics ====================

    @Test
    fun `stepper edit from 200 to 99 emits INTERVAL 99 not 200`() {
        val initial = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 200,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val afterStepperEdit = initial.copy(interval = 99)

        val rrule = afterStepperEdit.toRrule(DayOfWeek.SUNDAY)!!
        assertTrue("expected INTERVAL=99, got $rrule", rrule.contains("INTERVAL=99"))
        assertTrue(
            "expected no INTERVAL=200 after stepper edit, got $rrule",
            !rrule.contains("INTERVAL=200")
        )
    }

    @Test
    fun `non-stepper edit preserves stepper interval (no divergence)`() {
        val initial = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 200,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val afterWeekdayChange = initial.copy(weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))

        val rrule = afterWeekdayChange.toRrule(DayOfWeek.SUNDAY)!!
        assertTrue(
            "expected interval to stay at 200 with no UI divergence, got $rrule",
            rrule.contains("INTERVAL=200")
        )
        assertTrue(rrule.contains("BYDAY=MO,WE"))
    }

    // ==================== WKST round-trip preservation ====================

    @Test
    fun `toRrule preserves inbound WKST=SU over device wkst MONDAY for biweekly multi-day rule`() {
        // CalDAV-pulled rule with explicit WKST=SU. Opening on a Monday-week
        // device must emit WKST=SU on save, not silently rewrite to WKST=MO —
        // that would shift occurrences for biweekly multi-day rules where
        // Sunday and Monday land in different ISO weeks.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                wkst = DayOfWeek.SUNDAY,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = DayOfWeek.MONDAY)!!
        assertTrue("expected WKST=SU preserved over device MONDAY, got $rrule", rrule.contains("WKST=SU"))
    }

    @Test
    fun `toRrule falls back to device wkst when parsed wkst is null`() {
        // New rule (or rule that omitted WKST). Builder still applies the
        // emission gate (interval>=2 AND days.size>=2), so device wkst flows
        // through untouched here.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                wkst = null,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = DayOfWeek.SUNDAY)!!
        assertTrue("expected WKST=SU from device fallback, got $rrule", rrule.contains("WKST=SU"))
    }

    @Test
    fun `toRrule does not emit WKST when builder gate suppresses it (single-day BYDAY)`() {
        // RFC §3.3.10: WKST is only useful for WEEKLY rules with multi-day
        // BYDAY at interval>=2. Holder still stores parsedWkst, but the
        // builder drops it for single-day rules — verify that pass-through.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY),
                wkst = DayOfWeek.SUNDAY,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = DayOfWeek.MONDAY)!!
        assertTrue("expected no WKST for single-day rule, got $rrule", !rrule.contains("WKST="))
    }

    @Test
    fun `from stores parsedWkst on holder verbatim`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                wkst = DayOfWeek.SUNDAY,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(DayOfWeek.SUNDAY, selections.parsedWkst)
    }

    // ==================== WKST nullable deviceWkst (loaded-omitted preservation) ====================

    @Test
    fun `toRrule with parsedWkst=null and deviceWkst=null emits no WKST for biweekly multi-day rule`() {
        // Loaded rule that omitted WKST + caller (picker) signals "not a new
        // rule" by passing null. Builder gate would otherwise trigger; we
        // must respect the omission so RFC §3.3.10 default-MO anchoring is
        // preserved on save.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                wkst = null,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = null)!!
        assertTrue("expected no WKST when both parsed and device wkst are null, got $rrule", !rrule.contains("WKST="))
    }

    @Test
    fun `toRrule with parsedWkst=SUNDAY and deviceWkst=null still emits WKST=SU`() {
        // Explicit inbound WKST always wins; deviceWkst=null only matters
        // when parsedWkst is also null.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                wkst = DayOfWeek.SUNDAY,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = null)!!
        assertTrue("expected explicit parsedWkst to win, got $rrule", rrule.contains("WKST=SU"))
    }

    @Test
    fun `toRrule with parsedWkst=null and deviceWkst=SUNDAY emits WKST=SU for new rule`() {
        // New rule: caller passes the device wkst; builder gate triggers and
        // emits WKST. This is the existing seed-from-device behavior, now
        // gated on isNewRule rather than always-on.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                wkst = null,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = DayOfWeek.SUNDAY)!!
        assertTrue("expected WKST=SU for new rule on Sunday-week device, got $rrule", rrule.contains("WKST=SU"))
    }

    // ==================== Extra-token round-trip (BYMONTH / BYSETPOS / BYWEEKNO / BYYEARDAY) ====================

    @Test
    fun `from preserves extraTokens from parsed`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.CUSTOM,
                interval = 1,
                extraTokens = listOf("BYMONTH=1", "BYMONTHDAY=15"),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(listOf("BYMONTH=1", "BYMONTHDAY=15"), selections.extraTokens)
    }

    @Test
    fun `toRrule round-trips BYMONTH-bearing yearly rule verbatim`() {
        // CalDAV-pulled "every Jan 15" rule. parseRrule routes to CUSTOM and
        // captures BYMONTH=1. Emission must include BYMONTH=1 so a no-op save
        // doesn't silently rewrite to plain monthly.
        val selections = RecurrencePickerSelections.from(
            parsed = RruleBuilder.parseRrule(
                "FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15",
                DayOfWeek.MONDAY,
                15,
                3,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(DayOfWeek.MONDAY)!!
        assertTrue("expected FREQ=YEARLY, got $rrule", rrule.contains("FREQ=YEARLY"))
        assertTrue("expected BYMONTH=1, got $rrule", rrule.contains("BYMONTH=1"))
        assertTrue("expected BYMONTHDAY=15, got $rrule", rrule.contains("BYMONTHDAY=15"))
    }

    @Test
    fun `toRrule round-trips BYSETPOS rule verbatim`() {
        // "Last weekday of the month" via BYSETPOS=-1. Without preservation
        // the picker drops the qualifier and the rule degrades to plain
        // weekday-of-month recurrence.
        val selections = RecurrencePickerSelections.from(
            parsed = RruleBuilder.parseRrule(
                "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
                DayOfWeek.MONDAY,
                15,
                3,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(DayOfWeek.MONDAY)!!
        assertTrue("expected BYSETPOS=-1 preserved, got $rrule", rrule.contains("BYSETPOS=-1"))
    }

    @Test
    fun `toRrule with no extras emits no extra tokens`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(DayOfWeek.MONDAY)!!
        assertTrue("unexpected BYMONTH, got $rrule", !rrule.contains("BYMONTH="))
        assertTrue("unexpected BYSETPOS, got $rrule", !rrule.contains("BYSETPOS="))
    }

    @Test
    fun `toRrule with parsedWkst=null and deviceWkst=null emits no WKST for single-day rule (gate suppresses)`() {
        // Sanity: single-day BYDAY hits the builder gate regardless of args.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY),
                wkst = null,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        val rrule = selections.toRrule(deviceWkst = null)!!
        assertTrue("expected no WKST for single-day rule, got $rrule", !rrule.contains("WKST="))
    }

    // ==================== Monthly nth-weekday: last-weekday (ordinal -1) through the holder ====================

    @Test
    fun `toRrule MONTHLY preset with NthWeekday last Friday emits BYDAY -1FR`() {
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                monthlyPattern = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY),
            ),
            startDayOfWeek = DayOfWeek.SATURDAY,
            startDayOfMonth = 18,
        )
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toRrule CUSTOM Month interval 2 with NthWeekday last Friday emits canonical order`() {
        // "Last Friday of every 2 months" through the CUSTOM(month) path. Assert the
        // exact canonical token order the builder produces.
        val selections = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 2,
                monthlyPattern = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY),
            ),
            startDayOfWeek = DayOfWeek.FRIDAY,
            startDayOfMonth = 24,
        )
        assertEquals("FREQ=MONTHLY;INTERVAL=2;BYDAY=-1FR", selections.toRrule(DayOfWeek.SUNDAY))
    }

    @Test
    fun `ordinal switch 1st to Last to 2nd stays consistent through holder copies`() {
        // Simulates the user flipping the ordinal chip repeatedly. Each copy is
        // read back through toRrule; the last write wins with no residue.
        val base = RecurrencePickerSelections.from(
            parsed = ParsedRecurrence(
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                monthlyPattern = MonthlyPattern.NthWeekday(1, DayOfWeek.MONDAY),
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 5,
        )
        assertEquals(
            "FREQ=MONTHLY;BYDAY=1MO",
            base.toRrule(DayOfWeek.SUNDAY),
        )
        val toLast = base.copy(monthlyPattern = MonthlyPattern.NthWeekday(-1, DayOfWeek.MONDAY))
        assertEquals(
            "FREQ=MONTHLY;BYDAY=-1MO",
            toLast.toRrule(DayOfWeek.SUNDAY),
        )
        val toSecond = toLast.copy(monthlyPattern = MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY))
        assertEquals(
            "FREQ=MONTHLY;BYDAY=2MO",
            toSecond.toRrule(DayOfWeek.SUNDAY),
        )
    }

    @Test
    fun `from seeds monthlyPattern NthWeekday last Friday from parsed regardless of start weekday`() {
        // Holder-level guard: a parsed last-Friday rule reaches the holder
        // as NthWeekday(-1, FRIDAY) even though the start date is a Saturday.
        val selections = RecurrencePickerSelections.from(
            parsed = RruleBuilder.parseRrule(
                "FREQ=MONTHLY;BYDAY=-1FR",
                defaultWeekday = DayOfWeek.SATURDAY,
                defaultDayOfMonth = 18,
                defaultOrdinal = 3,
            ),
            startDayOfWeek = DayOfWeek.SATURDAY,
            startDayOfMonth = 18,
        )
        assertEquals(
            MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY),
            selections.monthlyPattern,
        )
    }

    @Test
    fun `BYSETPOS last-weekday rule routes to CUSTOM and round-trips without being hijacked`() {
        // FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1 ("last weekday"). The
        // picker must NOT interpret this as an nth-weekday selection; it routes to
        // CUSTOM via extraTokens and round-trips verbatim.
        val selections = RecurrencePickerSelections.from(
            parsed = RruleBuilder.parseRrule(
                "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
                DayOfWeek.MONDAY,
                15,
                3,
            ),
            startDayOfWeek = DayOfWeek.MONDAY,
            startDayOfMonth = 15,
        )
        assertEquals(FrequencyOption.CUSTOM, selections.frequencyOption)
        val rrule = selections.toRrule(DayOfWeek.MONDAY)!!
        assertTrue("expected BYSETPOS=-1 preserved, got $rrule", rrule.contains("BYSETPOS=-1"))
    }

    @Test
    fun `imported BYDAY -2FR second-to-last weekday round-trips through the holder verbatim`() {
        // The picker offers only 1st-4th + Last, so it can't author a
        // second-to-last (-2) ordinal, but a synced rule may carry one. It must
        // reach the holder as NthWeekday(-2, FRIDAY) and re-emit BYDAY=-2FR
        // unchanged — the value is preserved even though the selector has no chip
        // for it (the ordinal label degrades gracefully but the rule round-trips).
        val selections = RecurrencePickerSelections.from(
            parsed = RruleBuilder.parseRrule(
                "FREQ=MONTHLY;BYDAY=-2FR",
                DayOfWeek.SATURDAY,
                18,
                3,
            ),
            startDayOfWeek = DayOfWeek.SATURDAY,
            startDayOfMonth = 18,
        )
        assertEquals(
            MonthlyPattern.NthWeekday(-2, DayOfWeek.FRIDAY),
            selections.monthlyPattern,
        )
        assertEquals("FREQ=MONTHLY;BYDAY=-2FR", selections.toRrule(DayOfWeek.SUNDAY))
    }
}
