package org.onekash.kashcal.domain.rrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Unit tests for RruleBuilder.
 * Verifies RFC 5545 RRULE generation and parsing.
 */
class RruleBuilderTest {

    // ==================== Building RRULE Strings ====================

    @Test
    fun `daily returns simple FREQ DAILY`() {
        assertEquals("FREQ=DAILY", RruleBuilder.daily())
    }

    @Test
    fun `daily with interval includes INTERVAL`() {
        assertEquals("FREQ=DAILY;INTERVAL=3", RruleBuilder.daily(3))
    }

    @Test
    fun `weekly returns simple FREQ WEEKLY`() {
        assertEquals("FREQ=WEEKLY", RruleBuilder.weekly())
    }

    @Test
    fun `weekly with interval includes INTERVAL`() {
        assertEquals("FREQ=WEEKLY;INTERVAL=2", RruleBuilder.weekly(2))
    }

    @Test
    fun `weekly with days includes BYDAY sorted`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val result = RruleBuilder.weekly(1, days)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", result)
    }

    @Test
    fun `weekly with interval and days`() {
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val result = RruleBuilder.weekly(2, days)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH", result)
    }

    @Test
    fun `monthly returns simple FREQ MONTHLY`() {
        assertEquals("FREQ=MONTHLY", RruleBuilder.monthly())
    }

    @Test
    fun `monthly with day of month includes BYMONTHDAY`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", RruleBuilder.monthly(dayOfMonth = 15))
    }

    @Test
    fun `monthly with interval and day`() {
        assertEquals("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=1", RruleBuilder.monthly(3, 1))
    }

    @Test
    fun `monthlyLastDay includes BYMONTHDAY negative one`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", RruleBuilder.monthlyLastDay())
    }

    @Test
    fun `monthlyNthWeekday generates correct BYDAY`() {
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", RruleBuilder.monthlyNthWeekday(2, DayOfWeek.TUESDAY))
    }

    @Test
    fun `monthlyNthWeekday last weekday generates negative ordinal`() {
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", RruleBuilder.monthlyNthWeekday(-1, DayOfWeek.FRIDAY))
    }

    @Test
    fun `yearly returns simple FREQ YEARLY`() {
        assertEquals("FREQ=YEARLY", RruleBuilder.yearly())
    }

    @Test
    fun `yearly with interval includes INTERVAL`() {
        assertEquals("FREQ=YEARLY;INTERVAL=2", RruleBuilder.yearly(2))
    }

    @Test
    fun `withCount appends COUNT`() {
        assertEquals("FREQ=DAILY;COUNT=10", RruleBuilder.withCount("FREQ=DAILY", 10))
    }

    @Test
    fun `withUntil appends UNTIL in UTC format`() {
        // Jan 6, 2026 00:00:00 UTC
        val untilMillis = 1767657600000L
        val result = RruleBuilder.withUntil("FREQ=WEEKLY", untilMillis)
        assertTrue("Should contain UNTIL", result.contains("UNTIL="))
        assertTrue("Should have UTC timestamp", result.contains("20260106T000000Z"))
    }

    // ==================== Parsing Frequency ====================

    @Test
    fun `parseFrequency null returns NONE`() {
        assertEquals(RecurrenceFrequency.NONE, RruleBuilder.parseFrequency(null))
    }

    @Test
    fun `parseFrequency empty returns NONE`() {
        assertEquals(RecurrenceFrequency.NONE, RruleBuilder.parseFrequency(""))
    }

    @Test
    fun `parseFrequency simple DAILY returns DAILY`() {
        assertEquals(RecurrenceFrequency.DAILY, RruleBuilder.parseFrequency("FREQ=DAILY"))
    }

    @Test
    fun `parseFrequency simple WEEKLY returns WEEKLY`() {
        assertEquals(RecurrenceFrequency.WEEKLY, RruleBuilder.parseFrequency("FREQ=WEEKLY"))
    }

    @Test
    fun `parseFrequency with INTERVAL returns CUSTOM`() {
        assertEquals(RecurrenceFrequency.CUSTOM, RruleBuilder.parseFrequency("FREQ=DAILY;INTERVAL=2"))
    }

    @Test
    fun `parseFrequency with COUNT returns CUSTOM`() {
        assertEquals(RecurrenceFrequency.CUSTOM, RruleBuilder.parseFrequency("FREQ=WEEKLY;COUNT=10"))
    }

    @Test
    fun `parseFrequency with UNTIL returns CUSTOM`() {
        assertEquals(RecurrenceFrequency.CUSTOM, RruleBuilder.parseFrequency("FREQ=MONTHLY;UNTIL=20260106T000000Z"))
    }

    // ==================== Parsing Full RRULE ====================

    @Test
    fun `parseRrule null returns default ParsedRecurrence`() {
        val result = RruleBuilder.parseRrule(null, DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.NONE, result.frequency)
        assertEquals(1, result.interval)
        assertTrue(result.weekdays.isEmpty())
        assertNull(result.monthlyPattern)
        assertEquals(EndCondition.Never, result.endCondition)
    }

    @Test
    fun `parseRrule extracts frequency`() {
        val result = RruleBuilder.parseRrule("FREQ=WEEKLY", DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.WEEKLY, result.frequency)
    }

    @Test
    fun `parseRrule extracts interval`() {
        val result = RruleBuilder.parseRrule("FREQ=DAILY;INTERVAL=3", DayOfWeek.MONDAY, 1, 1)
        assertEquals(3, result.interval)
    }

    @Test
    fun `parseRrule extracts weekdays`() {
        val result = RruleBuilder.parseRrule("FREQ=WEEKLY;BYDAY=MO,WE,FR", DayOfWeek.MONDAY, 1, 1)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), result.weekdays)
    }

    @Test
    fun `parseRrule extracts monthly SameDay pattern`() {
        val result = RruleBuilder.parseRrule("FREQ=MONTHLY;BYMONTHDAY=15", DayOfWeek.MONDAY, 1, 1)
        assertEquals(MonthlyPattern.SameDay(15), result.monthlyPattern)
    }

    @Test
    fun `parseRrule extracts monthly LastDay pattern`() {
        val result = RruleBuilder.parseRrule("FREQ=MONTHLY;BYMONTHDAY=-1", DayOfWeek.MONDAY, 1, 1)
        assertEquals(MonthlyPattern.LastDay, result.monthlyPattern)
    }

    @Test
    fun `parseRrule extracts monthly NthWeekday pattern`() {
        val result = RruleBuilder.parseRrule("FREQ=MONTHLY;BYDAY=2TU", DayOfWeek.MONDAY, 1, 1)
        assertEquals(MonthlyPattern.NthWeekday(2, DayOfWeek.TUESDAY), result.monthlyPattern)
    }

    @Test
    fun `parseRrule extracts monthly last weekday pattern`() {
        val result = RruleBuilder.parseRrule("FREQ=MONTHLY;BYDAY=-1FR", DayOfWeek.MONDAY, 1, 1)
        assertEquals(MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY), result.monthlyPattern)
    }

    @Test
    fun `parseRrule extracts COUNT end condition`() {
        val result = RruleBuilder.parseRrule("FREQ=DAILY;COUNT=10", DayOfWeek.MONDAY, 1, 1)
        assertEquals(EndCondition.Count(10), result.endCondition)
    }

    @Test
    fun `parseRrule extracts UNTIL end condition`() {
        val result = RruleBuilder.parseRrule("FREQ=WEEKLY;UNTIL=20260106T000000Z", DayOfWeek.MONDAY, 1, 1)
        assertTrue(result.endCondition is EndCondition.Until)
        val until = result.endCondition as EndCondition.Until
        // Jan 6, 2026 00:00:00 UTC
        assertEquals(1767657600000L, until.dateMillis)
    }

    // ==================== Round-Trip Tests ====================

    @Test
    fun `daily roundtrip`() {
        val rrule = RruleBuilder.daily(2)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
        assertEquals(2, parsed.interval)
    }

    @Test
    fun `weekly with days roundtrip`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val rrule = RruleBuilder.weekly(1, days)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(days, parsed.weekdays)
    }

    @Test
    fun `monthly NthWeekday roundtrip`() {
        val rrule = RruleBuilder.monthlyNthWeekday(3, DayOfWeek.WEDNESDAY)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertEquals(RecurrenceFrequency.MONTHLY, parsed.frequency)
        assertEquals(MonthlyPattern.NthWeekday(3, DayOfWeek.WEDNESDAY), parsed.monthlyPattern)
    }

    // ==================== Display Formatting ====================

    @Test
    fun `formatForDisplay null returns Does not repeat`() {
        assertEquals("Does not repeat", RruleBuilder.formatForDisplay(null))
    }

    @Test
    fun `formatForDisplay empty returns Does not repeat`() {
        assertEquals("Does not repeat", RruleBuilder.formatForDisplay(""))
    }

    @Test
    fun `formatForDisplay daily`() {
        assertEquals("Daily", RruleBuilder.formatForDisplay("FREQ=DAILY"))
    }

    @Test
    fun `formatForDisplay daily with interval`() {
        assertEquals("Every 3 days", RruleBuilder.formatForDisplay("FREQ=DAILY;INTERVAL=3"))
    }

    @Test
    fun `formatForDisplay weekly`() {
        assertEquals("Weekly", RruleBuilder.formatForDisplay("FREQ=WEEKLY"))
    }

    @Test
    fun `formatForDisplay biweekly`() {
        assertEquals("Biweekly", RruleBuilder.formatForDisplay("FREQ=WEEKLY;INTERVAL=2"))
    }

    @Test
    fun `formatForDisplay weekly with days`() {
        val result = RruleBuilder.formatForDisplay("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals("Weekly on Mon, Wed, Fri", result)
    }

    @Test
    fun `formatForDisplay monthly`() {
        assertEquals("Monthly", RruleBuilder.formatForDisplay("FREQ=MONTHLY"))
    }

    @Test
    fun `formatForDisplay quarterly`() {
        assertEquals("Quarterly", RruleBuilder.formatForDisplay("FREQ=MONTHLY;INTERVAL=3"))
    }

    @Test
    fun `formatForDisplay monthly on day`() {
        assertEquals("Monthly on day 15", RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYMONTHDAY=15"))
    }

    @Test
    fun `formatForDisplay monthly on last day`() {
        assertEquals("Monthly on last day", RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYMONTHDAY=-1"))
    }

    @Test
    fun `formatForDisplay monthly on 2nd Tuesday`() {
        assertEquals("Monthly on 2nd Tue", RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=2TU"))
    }

    @Test
    fun `formatForDisplay monthly on last Friday`() {
        assertEquals("Monthly on last Fri", RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=-1FR"))
    }

    @Test
    fun `formatForDisplay yearly`() {
        assertEquals("Yearly", RruleBuilder.formatForDisplay("FREQ=YEARLY"))
    }

    @Test
    fun `formatForDisplay with COUNT`() {
        assertEquals("Daily, 10 times", RruleBuilder.formatForDisplay("FREQ=DAILY;COUNT=10"))
    }

    @Test
    fun `formatForDisplay with UNTIL`() {
        val result = RruleBuilder.formatForDisplay("FREQ=WEEKLY;UNTIL=20260106T000000Z")
        assertTrue("Should contain until: $result", result.contains("until"))
        assertTrue("Should contain date: $result", result.contains("01/06/2026"))
    }

    // ==================== Day Abbreviation ====================

    @Test
    fun `toDayAbbrev converts all days correctly`() {
        assertEquals("MO", RruleBuilder.toDayAbbrev(DayOfWeek.MONDAY))
        assertEquals("TU", RruleBuilder.toDayAbbrev(DayOfWeek.TUESDAY))
        assertEquals("WE", RruleBuilder.toDayAbbrev(DayOfWeek.WEDNESDAY))
        assertEquals("TH", RruleBuilder.toDayAbbrev(DayOfWeek.THURSDAY))
        assertEquals("FR", RruleBuilder.toDayAbbrev(DayOfWeek.FRIDAY))
        assertEquals("SA", RruleBuilder.toDayAbbrev(DayOfWeek.SATURDAY))
        assertEquals("SU", RruleBuilder.toDayAbbrev(DayOfWeek.SUNDAY))
    }
}
