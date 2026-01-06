package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import java.time.DayOfWeek

/**
 * Unit tests for RRULE builder utility.
 *
 * RFC 5545 RRULE format: "FREQ=frequency;...additional params"
 */
class RruleBuilderTest {

    // ========== Basic Frequency Tests ==========

    @Test
    fun `daily generates FREQ=DAILY`() {
        val rrule = RruleBuilder.daily()
        assertEquals("FREQ=DAILY", rrule)
    }

    @Test
    fun `daily with interval 2 generates correct rrule`() {
        val rrule = RruleBuilder.daily(interval = 2)
        assertEquals("FREQ=DAILY;INTERVAL=2", rrule)
    }

    @Test
    fun `weekly generates FREQ=WEEKLY`() {
        val rrule = RruleBuilder.weekly()
        assertEquals("FREQ=WEEKLY", rrule)
    }

    @Test
    fun `weekly with single day generates BYDAY`() {
        val rrule = RruleBuilder.weekly(days = setOf(DayOfWeek.MONDAY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rrule)
    }

    @Test
    fun `weekly with multiple days generates sorted BYDAY`() {
        val rrule = RruleBuilder.weekly(days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rrule)
    }

    @Test
    fun `weekly with all weekdays generates correct BYDAY`() {
        val rrule = RruleBuilder.weekly(days = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        ))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rrule)
    }

    @Test
    fun `weekly biweekly generates INTERVAL=2`() {
        val rrule = RruleBuilder.weekly(interval = 2, days = setOf(DayOfWeek.TUESDAY))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", rrule)
    }

    @Test
    fun `monthly generates FREQ=MONTHLY`() {
        val rrule = RruleBuilder.monthly()
        assertEquals("FREQ=MONTHLY", rrule)
    }

    @Test
    fun `monthly on specific day generates BYMONTHDAY`() {
        val rrule = RruleBuilder.monthly(dayOfMonth = 15)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", rrule)
    }

    @Test
    fun `monthly on last day generates BYMONTHDAY=-1`() {
        val rrule = RruleBuilder.monthly(dayOfMonth = -1)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", rrule)
    }

    @Test
    fun `yearly generates FREQ=YEARLY`() {
        val rrule = RruleBuilder.yearly()
        assertEquals("FREQ=YEARLY", rrule)
    }

    @Test
    fun `yearly with interval generates INTERVAL`() {
        val rrule = RruleBuilder.yearly(interval = 2)
        assertEquals("FREQ=YEARLY;INTERVAL=2", rrule)
    }

    // ========== COUNT Limit Tests ==========

    @Test
    fun `with count adds COUNT parameter`() {
        val base = RruleBuilder.daily()
        val rrule = RruleBuilder.withCount(base, 10)
        assertEquals("FREQ=DAILY;COUNT=10", rrule)
    }

    @Test
    fun `with count on weekly adds COUNT`() {
        val base = RruleBuilder.weekly(days = setOf(DayOfWeek.MONDAY))
        val rrule = RruleBuilder.withCount(base, 5)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=5", rrule)
    }

    // ========== UNTIL Date Tests ==========

    @Test
    fun `with until adds UNTIL parameter in UTC format`() {
        val base = RruleBuilder.daily()
        // Dec 31, 2024 23:59:59 UTC
        val untilMillis = 1735689599000L
        val rrule = RruleBuilder.withUntil(base, untilMillis)
        assertTrue("Should contain UNTIL", rrule.contains("UNTIL="))
        // Format should be YYYYMMDDTHHMMSSZ
        assertTrue("Should be UTC format", rrule.contains("Z"))
    }

    // ========== Parsing Tests ==========

    @Test
    fun `parse daily rrule returns correct frequency`() {
        val frequency = RruleBuilder.parseFrequency("FREQ=DAILY")
        assertEquals(RecurrenceFrequency.DAILY, frequency)
    }

    @Test
    fun `parse weekly rrule returns correct frequency`() {
        val frequency = RruleBuilder.parseFrequency("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals(RecurrenceFrequency.WEEKLY, frequency)
    }

    @Test
    fun `parse monthly rrule returns correct frequency`() {
        val frequency = RruleBuilder.parseFrequency("FREQ=MONTHLY;BYMONTHDAY=15")
        assertEquals(RecurrenceFrequency.MONTHLY, frequency)
    }

    @Test
    fun `parse yearly rrule returns correct frequency`() {
        val frequency = RruleBuilder.parseFrequency("FREQ=YEARLY")
        assertEquals(RecurrenceFrequency.YEARLY, frequency)
    }

    @Test
    fun `parse null rrule returns NONE`() {
        val frequency = RruleBuilder.parseFrequency(null)
        assertEquals(RecurrenceFrequency.NONE, frequency)
    }

    @Test
    fun `parse complex rrule returns CUSTOM`() {
        // Interval or BYSETPOS makes it custom
        val frequency = RruleBuilder.parseFrequency("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO")
        assertEquals(RecurrenceFrequency.CUSTOM, frequency)
    }

    @Test
    fun `parse rrule with count returns CUSTOM`() {
        val frequency = RruleBuilder.parseFrequency("FREQ=DAILY;COUNT=10")
        assertEquals(RecurrenceFrequency.CUSTOM, frequency)
    }

    // ========== Display String Tests ==========

    @Test
    fun `format daily for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=DAILY")
        assertEquals("Daily", display)
    }

    @Test
    fun `format weekly for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY")
        assertEquals("Weekly", display)
    }

    @Test
    fun `format weekly with days for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals("Weekly on Mon, Wed, Fri", display)
    }

    @Test
    fun `format monthly for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY")
        assertEquals("Monthly", display)
    }

    @Test
    fun `format yearly for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=YEARLY")
        assertEquals("Yearly", display)
    }

    @Test
    fun `format null returns Does not repeat`() {
        val display = RruleBuilder.formatForDisplay(null)
        assertEquals("Does not repeat", display)
    }

    // ========== Day Abbreviation Tests ==========

    @Test
    fun `day abbreviation mapping is correct`() {
        assertEquals("SU", RruleBuilder.toDayAbbrev(DayOfWeek.SUNDAY))
        assertEquals("MO", RruleBuilder.toDayAbbrev(DayOfWeek.MONDAY))
        assertEquals("TU", RruleBuilder.toDayAbbrev(DayOfWeek.TUESDAY))
        assertEquals("WE", RruleBuilder.toDayAbbrev(DayOfWeek.WEDNESDAY))
        assertEquals("TH", RruleBuilder.toDayAbbrev(DayOfWeek.THURSDAY))
        assertEquals("FR", RruleBuilder.toDayAbbrev(DayOfWeek.FRIDAY))
        assertEquals("SA", RruleBuilder.toDayAbbrev(DayOfWeek.SATURDAY))
    }

    // ========== Monthly Nth Weekday Tests ==========

    @Test
    fun `monthly second Tuesday generates BYDAY=2TU`() {
        val rrule = RruleBuilder.monthlyNthWeekday(2, DayOfWeek.TUESDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", rrule)
    }

    @Test
    fun `monthly first Monday generates BYDAY=1MO`() {
        val rrule = RruleBuilder.monthlyNthWeekday(1, DayOfWeek.MONDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", rrule)
    }

    @Test
    fun `monthly last Friday generates BYDAY=-1FR`() {
        val rrule = RruleBuilder.monthlyNthWeekday(-1, DayOfWeek.FRIDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", rrule)
    }

    @Test
    fun `monthly third Wednesday generates BYDAY=3WE`() {
        val rrule = RruleBuilder.monthlyNthWeekday(3, DayOfWeek.WEDNESDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=3WE", rrule)
    }

    @Test
    fun `monthly fourth Thursday generates BYDAY=4TH`() {
        val rrule = RruleBuilder.monthlyNthWeekday(4, DayOfWeek.THURSDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=4TH", rrule)
    }

    // ========== Weekend Tests ==========

    @Test
    fun `weekly weekends generates BYDAY=SA,SU`() {
        val rrule = RruleBuilder.weekly(days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        // Days sorted Monday-first: SA,SU
        assertEquals("FREQ=WEEKLY;BYDAY=SA,SU", rrule)
    }

    @Test
    fun `every other weekend generates interval and days`() {
        val rrule = RruleBuilder.weekly(interval = 2, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        // Days sorted Monday-first: SA,SU
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,SU", rrule)
    }

    // ========== Invalid Input Handling ==========

    @Test
    fun `parseFrequency handles empty string`() {
        val frequency = RruleBuilder.parseFrequency("")
        assertEquals(RecurrenceFrequency.NONE, frequency)
    }

    @Test
    fun `parseFrequency handles malformed rrule`() {
        val frequency = RruleBuilder.parseFrequency("NOT_A_RRULE")
        // Unrecognized patterns return CUSTOM (not NONE)
        assertEquals(RecurrenceFrequency.CUSTOM, frequency)
    }

    @Test
    fun `parseFrequency handles rrule without FREQ`() {
        val frequency = RruleBuilder.parseFrequency("BYDAY=MO;COUNT=5")
        // No FREQ present returns CUSTOM (unrecognized pattern)
        assertEquals(RecurrenceFrequency.CUSTOM, frequency)
    }

    // ========== Display Format Edge Cases ==========

    @Test
    fun `format biweekly for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;INTERVAL=2")
        assertTrue(display.contains("Every 2 weeks") || display.contains("Biweekly") || display.contains("Custom"))
    }

    @Test
    fun `format monthly with day for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYMONTHDAY=15")
        assertEquals("Monthly on day 15", display)
    }

    @Test
    fun `format monthly Nth weekday for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=2TU")
        // Implementation uses abbreviated day name "Tue" not full "Tuesday"
        assertEquals("Monthly on 2nd Tue", display)
    }

    @Test
    fun `format with count for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=DAILY;COUNT=10")
        assertTrue(display.contains("10 times") || display.contains("Custom"))
    }

    @Test
    fun `format with until for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;UNTIL=20241231T235959Z")
        assertTrue(display.contains("until") || display.contains("Custom") || display.contains("Weekly"))
    }

    // ========== Round-Trip Tests ==========

    @Test
    fun `daily round-trips correctly`() {
        val original = RruleBuilder.daily()
        val frequency = RruleBuilder.parseFrequency(original)
        assertEquals(RecurrenceFrequency.DAILY, frequency)
    }

    @Test
    fun `weekly round-trips correctly`() {
        val original = RruleBuilder.weekly()
        val frequency = RruleBuilder.parseFrequency(original)
        assertEquals(RecurrenceFrequency.WEEKLY, frequency)
    }

    @Test
    fun `monthly round-trips correctly`() {
        val original = RruleBuilder.monthly()
        val frequency = RruleBuilder.parseFrequency(original)
        assertEquals(RecurrenceFrequency.MONTHLY, frequency)
    }

    @Test
    fun `yearly round-trips correctly`() {
        val original = RruleBuilder.yearly()
        val frequency = RruleBuilder.parseFrequency(original)
        assertEquals(RecurrenceFrequency.YEARLY, frequency)
    }

    // ========== Monthly Last Day Tests ==========

    @Test
    fun `monthlyLastDay generates BYMONTHDAY=-1`() {
        val rrule = RruleBuilder.monthlyLastDay()
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", rrule)
    }

    @Test
    fun `monthlyLastDay with interval generates correct rrule`() {
        val rrule = RruleBuilder.monthlyLastDay(interval = 2)
        assertEquals("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=-1", rrule)
    }

    // ========== Combined Complex Rules ==========

    @Test
    fun `weekly with interval 3 and multiple days`() {
        val rrule = RruleBuilder.weekly(interval = 3, days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))
        assertEquals("FREQ=WEEKLY;INTERVAL=3;BYDAY=TU,TH", rrule)
    }

    @Test
    fun `monthly with count generates correct rrule`() {
        val base = RruleBuilder.monthly(dayOfMonth = 1)
        val rrule = RruleBuilder.withCount(base, 12)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1;COUNT=12", rrule)
    }

    @Test
    fun `yearly with until generates correct rrule`() {
        val base = RruleBuilder.yearly()
        val rrule = RruleBuilder.withUntil(base, 1735689599000L)
        assertTrue(rrule.startsWith("FREQ=YEARLY;UNTIL="))
    }

    // ========== Daily Interval Tests ==========

    @Test
    fun `daily with interval 1 has no INTERVAL`() {
        val rrule = RruleBuilder.daily(interval = 1)
        assertEquals("FREQ=DAILY", rrule)
    }

    @Test
    fun `daily with interval 7 generates correct rrule`() {
        val rrule = RruleBuilder.daily(interval = 7)
        assertEquals("FREQ=DAILY;INTERVAL=7", rrule)
    }

    // ========== Weekly Edge Cases ==========

    @Test
    fun `weekly with empty days set has no BYDAY`() {
        val rrule = RruleBuilder.weekly(days = emptySet())
        assertEquals("FREQ=WEEKLY", rrule)
    }

    @Test
    fun `weekly with all seven days`() {
        val rrule = RruleBuilder.weekly(days = setOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
        ))
        // Days sorted Monday-first: MO,TU,WE,TH,FR,SA,SU
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU", rrule)
    }

    // ========== Count Edge Cases ==========

    @Test
    fun `with count 1 generates valid rrule`() {
        val base = RruleBuilder.daily()
        val rrule = RruleBuilder.withCount(base, 1)
        assertEquals("FREQ=DAILY;COUNT=1", rrule)
    }

    @Test
    fun `with count 365 generates valid rrule`() {
        val base = RruleBuilder.daily()
        val rrule = RruleBuilder.withCount(base, 365)
        assertEquals("FREQ=DAILY;COUNT=365", rrule)
    }

    // ========== Format Display Complex ==========

    @Test
    fun `format monthly last day for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYMONTHDAY=-1")
        assertTrue(display.contains("last") || display.contains("Monthly"))
    }

    @Test
    fun `format weekdays for display`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        assertTrue(display.contains("Mon") || display.contains("weekday"))
    }

    @Test
    fun `format empty rrule returns Does not repeat`() {
        val display = RruleBuilder.formatForDisplay("")
        assertEquals("Does not repeat", display)
    }
}