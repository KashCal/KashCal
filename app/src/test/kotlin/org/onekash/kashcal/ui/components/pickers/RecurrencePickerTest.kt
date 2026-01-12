package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.RruleBuilder
import java.time.DayOfWeek

/**
 * Unit tests for RecurrencePicker component.
 * Tests RRULE building logic and frequency option mapping.
 */
class RecurrencePickerTest {

    // ==================== FrequencyOption Tests ====================

    @Test
    fun `FrequencyOption NEVER maps to RecurrenceFrequency NONE`() {
        assertEquals(RecurrenceFrequency.NONE, FrequencyOption.NEVER.freq)
    }

    @Test
    fun `FrequencyOption DAILY maps to RecurrenceFrequency DAILY`() {
        assertEquals(RecurrenceFrequency.DAILY, FrequencyOption.DAILY.freq)
    }

    @Test
    fun `FrequencyOption WEEKLY maps to RecurrenceFrequency WEEKLY`() {
        assertEquals(RecurrenceFrequency.WEEKLY, FrequencyOption.WEEKLY.freq)
    }

    @Test
    fun `FrequencyOption BIWEEKLY maps to WEEKLY with interval 2`() {
        assertEquals(RecurrenceFrequency.WEEKLY, FrequencyOption.BIWEEKLY.freq)
        assertEquals(2, FrequencyOption.BIWEEKLY.interval)
    }

    @Test
    fun `FrequencyOption MONTHLY maps to RecurrenceFrequency MONTHLY`() {
        assertEquals(RecurrenceFrequency.MONTHLY, FrequencyOption.MONTHLY.freq)
    }

    @Test
    fun `FrequencyOption QUARTERLY maps to MONTHLY with interval 3`() {
        assertEquals(RecurrenceFrequency.MONTHLY, FrequencyOption.QUARTERLY.freq)
        assertEquals(3, FrequencyOption.QUARTERLY.interval)
    }

    @Test
    fun `FrequencyOption YEARLY maps to RecurrenceFrequency YEARLY`() {
        assertEquals(RecurrenceFrequency.YEARLY, FrequencyOption.YEARLY.freq)
    }

    // ==================== RRULE Building Tests ====================

    @Test
    fun `daily frequency builds correct RRULE`() {
        val rrule = RruleBuilder.daily()
        assertEquals("FREQ=DAILY", rrule)
    }

    @Test
    fun `weekly frequency with days builds correct RRULE`() {
        val rrule = RruleBuilder.weekly(days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertTrue(rrule.contains("FREQ=WEEKLY"))
        assertTrue(rrule.contains("MO"))
        assertTrue(rrule.contains("FR"))
    }

    @Test
    fun `biweekly builds RRULE with interval 2`() {
        val rrule = RruleBuilder.weekly(interval = 2)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", rrule)
    }

    @Test
    fun `monthly with day of month builds correct RRULE`() {
        val rrule = RruleBuilder.monthly(dayOfMonth = 15)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", rrule)
    }

    @Test
    fun `monthly last day builds correct RRULE`() {
        val rrule = RruleBuilder.monthlyLastDay()
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", rrule)
    }

    @Test
    fun `monthly nth weekday builds correct RRULE`() {
        val rrule = RruleBuilder.monthlyNthWeekday(2, DayOfWeek.TUESDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", rrule)
    }

    @Test
    fun `quarterly builds RRULE with interval 3`() {
        val rrule = RruleBuilder.monthly(interval = 3, dayOfMonth = 1)
        assertEquals("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=1", rrule)
    }

    @Test
    fun `yearly builds correct RRULE`() {
        val rrule = RruleBuilder.yearly()
        assertEquals("FREQ=YEARLY", rrule)
    }

    // ==================== End Condition Tests ====================

    @Test
    fun `withCount appends COUNT to RRULE`() {
        val base = RruleBuilder.daily()
        val rrule = RruleBuilder.withCount(base, 10)
        assertEquals("FREQ=DAILY;COUNT=10", rrule)
    }

    @Test
    fun `withUntil appends UNTIL to RRULE`() {
        val base = RruleBuilder.weekly()
        val untilMillis = 1767657600000L // Jan 6, 2026 UTC
        val rrule = RruleBuilder.withUntil(base, untilMillis)
        assertTrue(rrule.contains("UNTIL="))
        assertTrue(rrule.contains("20260106"))
    }

    // ==================== MonthlyPattern Tests ====================

    @Test
    fun `MonthlyPattern SameDay stores correct day`() {
        val pattern = MonthlyPattern.SameDay(15)
        assertEquals(15, pattern.dayOfMonth)
    }

    @Test
    fun `MonthlyPattern LastDay is singleton`() {
        val p1 = MonthlyPattern.LastDay
        val p2 = MonthlyPattern.LastDay
        assertEquals(p1, p2)
    }

    @Test
    fun `MonthlyPattern NthWeekday stores ordinal and weekday`() {
        val pattern = MonthlyPattern.NthWeekday(3, DayOfWeek.WEDNESDAY)
        assertEquals(3, pattern.ordinal)
        assertEquals(DayOfWeek.WEDNESDAY, pattern.weekday)
    }

    @Test
    fun `MonthlyPattern NthWeekday supports last weekday ordinal`() {
        val pattern = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY)
        assertEquals(-1, pattern.ordinal)
        assertEquals(DayOfWeek.FRIDAY, pattern.weekday)
    }

    // ==================== EndCondition Tests ====================

    @Test
    fun `EndCondition Never is singleton`() {
        val e1 = EndCondition.Never
        val e2 = EndCondition.Never
        assertEquals(e1, e2)
    }

    @Test
    fun `EndCondition Count stores count value`() {
        val condition = EndCondition.Count(5)
        assertEquals(5, condition.count)
    }

    @Test
    fun `EndCondition Until stores dateMillis`() {
        val millis = 1767657600000L
        val condition = EndCondition.Until(millis)
        assertEquals(millis, condition.dateMillis)
    }

    // ==================== RRULE Parsing Tests ====================

    @Test
    fun `parseRrule null returns NONE frequency`() {
        val parsed = RruleBuilder.parseRrule(null, DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.NONE, parsed.frequency)
    }

    @Test
    fun `parseRrule empty returns NONE frequency`() {
        val parsed = RruleBuilder.parseRrule("", DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.NONE, parsed.frequency)
    }

    @Test
    fun `parseRrule extracts daily frequency`() {
        val parsed = RruleBuilder.parseRrule("FREQ=DAILY", DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
    }

    @Test
    fun `parseRrule extracts weekly frequency with days`() {
        val parsed = RruleBuilder.parseRrule("FREQ=WEEKLY;BYDAY=MO,WE,FR", DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), parsed.weekdays)
    }

    @Test
    fun `parseRrule extracts interval`() {
        val parsed = RruleBuilder.parseRrule("FREQ=WEEKLY;INTERVAL=2", DayOfWeek.MONDAY, 1, 1)
        assertEquals(2, parsed.interval)
    }

    @Test
    fun `parseRrule extracts COUNT end condition`() {
        val parsed = RruleBuilder.parseRrule("FREQ=DAILY;COUNT=10", DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.endCondition is EndCondition.Count)
        assertEquals(10, (parsed.endCondition as EndCondition.Count).count)
    }

    @Test
    fun `parseRrule extracts UNTIL end condition`() {
        val parsed = RruleBuilder.parseRrule("FREQ=WEEKLY;UNTIL=20260106T000000Z", DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.endCondition is EndCondition.Until)
    }

    @Test
    fun `parseRrule extracts monthly BYMONTHDAY pattern`() {
        val parsed = RruleBuilder.parseRrule("FREQ=MONTHLY;BYMONTHDAY=15", DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.MONTHLY, parsed.frequency)
        assertTrue(parsed.monthlyPattern is MonthlyPattern.SameDay)
        assertEquals(15, (parsed.monthlyPattern as MonthlyPattern.SameDay).dayOfMonth)
    }

    @Test
    fun `parseRrule extracts monthly last day pattern`() {
        val parsed = RruleBuilder.parseRrule("FREQ=MONTHLY;BYMONTHDAY=-1", DayOfWeek.MONDAY, 1, 1)
        assertEquals(MonthlyPattern.LastDay, parsed.monthlyPattern)
    }

    @Test
    fun `parseRrule extracts monthly Nth weekday pattern`() {
        val parsed = RruleBuilder.parseRrule("FREQ=MONTHLY;BYDAY=2TU", DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.monthlyPattern is MonthlyPattern.NthWeekday)
        val pattern = parsed.monthlyPattern as MonthlyPattern.NthWeekday
        assertEquals(2, pattern.ordinal)
        assertEquals(DayOfWeek.TUESDAY, pattern.weekday)
    }

    // ==================== Round-Trip Tests ====================

    @Test
    fun `weekly with days round-trip`() {
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val rrule = RruleBuilder.weekly(days = days)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertEquals(days, parsed.weekdays)
    }

    @Test
    fun `monthly NthWeekday round-trip`() {
        val rrule = RruleBuilder.monthlyNthWeekday(3, DayOfWeek.WEDNESDAY)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.monthlyPattern is MonthlyPattern.NthWeekday)
        val pattern = parsed.monthlyPattern as MonthlyPattern.NthWeekday
        assertEquals(3, pattern.ordinal)
        assertEquals(DayOfWeek.WEDNESDAY, pattern.weekday)
    }

    @Test
    fun `with COUNT round-trip`() {
        val rrule = RruleBuilder.withCount(RruleBuilder.daily(), 15)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.endCondition is EndCondition.Count)
        assertEquals(15, (parsed.endCondition as EndCondition.Count).count)
    }

    // ==================== Display Format Tests ====================

    @Test
    fun `formatForDisplay null returns Does not repeat`() {
        assertEquals("Does not repeat", RruleBuilder.formatForDisplay(null))
    }

    @Test
    fun `formatForDisplay empty returns Does not repeat`() {
        assertEquals("Does not repeat", RruleBuilder.formatForDisplay(""))
    }

    @Test
    fun `formatForDisplay FREQ DAILY returns Daily`() {
        assertEquals("Daily", RruleBuilder.formatForDisplay("FREQ=DAILY"))
    }

    @Test
    fun `formatForDisplay FREQ WEEKLY returns Weekly`() {
        assertEquals("Weekly", RruleBuilder.formatForDisplay("FREQ=WEEKLY"))
    }

    @Test
    fun `formatForDisplay biweekly returns Biweekly`() {
        assertEquals("Biweekly", RruleBuilder.formatForDisplay("FREQ=WEEKLY;INTERVAL=2"))
    }

    @Test
    fun `formatForDisplay FREQ MONTHLY returns Monthly`() {
        assertEquals("Monthly", RruleBuilder.formatForDisplay("FREQ=MONTHLY"))
    }

    @Test
    fun `formatForDisplay quarterly returns Quarterly`() {
        assertEquals("Quarterly", RruleBuilder.formatForDisplay("FREQ=MONTHLY;INTERVAL=3"))
    }

    @Test
    fun `formatForDisplay FREQ YEARLY returns Yearly`() {
        assertEquals("Yearly", RruleBuilder.formatForDisplay("FREQ=YEARLY"))
    }

    @Test
    fun `formatForDisplay with COUNT shows times`() {
        val result = RruleBuilder.formatForDisplay("FREQ=DAILY;COUNT=10")
        assertTrue(result.contains("10 times"))
    }

    @Test
    fun `formatForDisplay with UNTIL shows date`() {
        val result = RruleBuilder.formatForDisplay("FREQ=WEEKLY;UNTIL=20260106T000000Z")
        assertTrue(result.contains("until"))
    }
}
