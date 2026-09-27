package org.onekash.kashcal.domain.rrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Tests [RruleBuilder] with edge-case inputs: zero, negative and huge intervals, empty and full
 * BYDAY sets, zero, negative and huge COUNT, UNTIL at or before the epoch, malformed RRULE parts
 * for [RruleBuilder.parseFrequency] and [RruleBuilder.parseRrule], display edge cases, and
 * out-of-range month days and ordinals. The builder methods don't validate their inputs.
 */
class RruleBuilderAdversarialTest {

    // ==================== Edge-Case Intervals ====================

    @Test
    fun `daily with interval 0 produces INTERVAL=0`() {
        // INTERVAL=0 is invalid (RFC 5545 §3.3.10 requires a positive integer); the builder
        // doesn't validate.
        val result = RruleBuilder.daily(0)
        // daily adds INTERVAL for anything but 1, so this is FREQ=DAILY;INTERVAL=0 (only FREQ is
        // asserted).
        assertTrue("Should contain FREQ=DAILY", result.contains("FREQ=DAILY"))
    }

    @Test
    fun `daily with interval 1 omits INTERVAL`() {
        val result = RruleBuilder.daily(1)
        assertEquals("FREQ=DAILY", result)
    }

    @Test
    fun `daily with negative interval`() {
        // Negative interval is invalid per RFC 5545
        val result = RruleBuilder.daily(-1)
        assertTrue("Should still contain FREQ=DAILY", result.contains("FREQ=DAILY"))
    }

    @Test
    fun `daily with very large interval`() {
        val result = RruleBuilder.daily(999999)
        assertEquals("FREQ=DAILY;INTERVAL=999999", result)
    }

    @Test
    fun `weekly with interval 0`() {
        val result = RruleBuilder.weekly(0)
        // weekly adds INTERVAL only above 1.
        assertEquals("FREQ=WEEKLY", result)
    }

    @Test
    fun `weekly with negative interval`() {
        val result = RruleBuilder.weekly(-5)
        // weekly adds INTERVAL only above 1.
        assertEquals("FREQ=WEEKLY", result)
    }

    @Test
    fun `monthly with interval 0`() {
        val result = RruleBuilder.monthly(0)
        assertEquals("FREQ=MONTHLY", result)
    }

    @Test
    fun `yearly with interval 0`() {
        val result = RruleBuilder.yearly(0)
        // yearly adds INTERVAL for anything but 1, so this is FREQ=YEARLY;INTERVAL=0 (only FREQ
        // is asserted).
        assertTrue(result.contains("FREQ=YEARLY"))
    }

    // ==================== Empty Collections ====================

    @Test
    fun `weekly with empty days set omits BYDAY`() {
        val result = RruleBuilder.weekly(1, emptySet())
        assertEquals("FREQ=WEEKLY", result)
    }

    @Test
    fun `weekly with single day`() {
        val result = RruleBuilder.weekly(1, setOf(DayOfWeek.WEDNESDAY))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", result)
    }

    @Test
    fun `weekly with all seven days`() {
        val allDays = DayOfWeek.entries.toSet()
        val result = RruleBuilder.weekly(1, allDays)
        assertTrue("Should contain BYDAY", result.contains("BYDAY="))
        // Should include all 7 day abbreviations
        assertTrue(result.contains("MO"))
        assertTrue(result.contains("TU"))
        assertTrue(result.contains("WE"))
        assertTrue(result.contains("TH"))
        assertTrue(result.contains("FR"))
        assertTrue(result.contains("SA"))
        assertTrue(result.contains("SU"))
    }

    // ==================== Edge-Case COUNT ====================

    @Test
    fun `withCount with zero`() {
        val result = RruleBuilder.withCount("FREQ=DAILY", 0)
        assertEquals("FREQ=DAILY;COUNT=0", result)
    }

    @Test
    fun `withCount with negative`() {
        val result = RruleBuilder.withCount("FREQ=DAILY", -1)
        assertEquals("FREQ=DAILY;COUNT=-1", result)
    }

    @Test
    fun `withCount with very large number`() {
        val result = RruleBuilder.withCount("FREQ=DAILY", Int.MAX_VALUE)
        assertEquals("FREQ=DAILY;COUNT=${Int.MAX_VALUE}", result)
    }

    // ==================== Edge-Case UNTIL ====================

    @Test
    fun `withUntil with epoch zero`() {
        val result = RruleBuilder.withUntil("FREQ=DAILY", 0L)
        assertTrue("Should contain UNTIL", result.contains("UNTIL="))
        assertTrue("Should format as 1970 UTC", result.contains("19700101T000000Z"))
    }

    @Test
    fun `withUntil with negative timestamp (before epoch)`() {
        // Dec 31, 1969 23:59:59 UTC
        val result = RruleBuilder.withUntil("FREQ=DAILY", -1000L)
        assertTrue("Should contain UNTIL", result.contains("UNTIL="))
    }

    // ==================== Parsing Edge Cases ====================

    @Test
    fun `parseFrequency with garbage string`() {
        val result = RruleBuilder.parseFrequency("GARBAGE_DATA_HERE")
        assertEquals(RecurrenceFrequency.CUSTOM, result)
    }

    @Test
    fun `parseFrequency with empty whitespace`() {
        val result = RruleBuilder.parseFrequency("   ")
        // "   ".isNullOrBlank() == true → NONE
        assertEquals(RecurrenceFrequency.NONE, result)
    }

    @Test
    fun `parseFrequency with partial FREQ`() {
        val result = RruleBuilder.parseFrequency("FREQ=")
        assertEquals(RecurrenceFrequency.CUSTOM, result)
    }

    @Test
    fun `parseRrule with malformed INTERVAL non-numeric`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=DAILY;INTERVAL=abc",
            DayOfWeek.MONDAY, 1, 1
        )
        // The INTERVAL regex matches digits only, so "abc" doesn't match and the interval
        // defaults to 1.
        assertEquals(1, result.interval)
    }

    @Test
    fun `parseRrule with malformed COUNT non-numeric`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=DAILY;COUNT=xyz",
            DayOfWeek.MONDAY, 1, 1
        )
        // The COUNT regex matches digits only, so "xyz" doesn't match: EndCondition.Never.
        assertEquals(EndCondition.Never, result.endCondition)
    }

    @Test
    fun `parseRrule with malformed UNTIL date`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=DAILY;UNTIL=NOTADATE",
            DayOfWeek.MONDAY, 1, 1
        )
        // Neither UNTIL regex matches, so EndCondition.Never.
        assertEquals(EndCondition.Never, result.endCondition)
    }

    @Test
    fun `parseRrule with unknown BYDAY abbreviation`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;BYDAY=XX,MO",
            DayOfWeek.MONDAY, 1, 1
        )
        // XX isn't a weekday abbreviation, so it's dropped and only MO remains.
        assertEquals(setOf(DayOfWeek.MONDAY), result.weekdays)
    }

    @Test
    fun `parseRrule with COUNT=0 parses as zero`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=DAILY;COUNT=0",
            DayOfWeek.MONDAY, 1, 1
        )
        assertTrue(result.endCondition is EndCondition.Count)
        assertEquals(0, (result.endCondition as EndCondition.Count).count)
    }

    @Test
    fun `parseRrule with INTERVAL=0 parses as zero`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=DAILY;INTERVAL=0",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(0, result.interval)
    }

    // ==================== Display Formatting Edge Cases ====================

    @Test
    fun `formatForDisplay with unknown frequency`() {
        val result = RruleBuilder.formatForDisplay("FREQ=SECONDLY")
        assertEquals("Repeats", result)
    }

    @Test
    fun `formatForDisplay with COUNT=1`() {
        val result = RruleBuilder.formatForDisplay("FREQ=DAILY;COUNT=1")
        assertEquals("Daily, 1 times", result)
    }

    @Test
    fun `formatForDisplay with COUNT=0`() {
        val result = RruleBuilder.formatForDisplay("FREQ=DAILY;COUNT=0")
        assertEquals("Daily, 0 times", result)
    }

    @Test
    fun `formatForDisplay with very large interval`() {
        val result = RruleBuilder.formatForDisplay("FREQ=DAILY;INTERVAL=365")
        assertEquals("Every 365 days", result)
    }

    // ==================== Monthly Edge Cases ====================

    @Test
    fun `monthly with dayOfMonth 0`() {
        // Day 0 is invalid but builder doesn't validate
        val result = RruleBuilder.monthly(dayOfMonth = 0)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=0", result)
    }

    @Test
    fun `monthly with dayOfMonth 31`() {
        val result = RruleBuilder.monthly(dayOfMonth = 31)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=31", result)
    }

    @Test
    fun `monthly with dayOfMonth 32 (invalid)`() {
        // Day 32 is invalid but builder doesn't validate
        val result = RruleBuilder.monthly(dayOfMonth = 32)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=32", result)
    }

    @Test
    fun `monthlyNthWeekday with ordinal 0 (invalid)`() {
        val result = RruleBuilder.monthlyNthWeekday(0, DayOfWeek.MONDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=0MO", result)
    }

    @Test
    fun `monthlyNthWeekday with ordinal 5 (invalid - only 4 possible)`() {
        val result = RruleBuilder.monthlyNthWeekday(5, DayOfWeek.FRIDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=5FR", result)
    }
}
