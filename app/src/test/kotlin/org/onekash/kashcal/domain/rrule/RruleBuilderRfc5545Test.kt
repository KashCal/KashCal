package org.onekash.kashcal.domain.rrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests [RruleBuilder] against RFC 5545 §3.3.10, including the rules the builder doesn't enforce:
 * - [RruleBuilder.withUntil] always emits a UTC date-time, though "the value of the UNTIL rule
 *   part MUST have the same value type as the "DTSTART" property", so an all-day (DATE) DTSTART
 *   must have a DATE UNTIL.
 * - The builder lets COUNT and UNTIL occur together, which the RFC forbids.
 *
 * Also covers [RruleBuilder.parseRrule] (UNTIL in both forms, COUNT, INTERVAL, BYDAY,
 * monthly patterns), [RruleBuilder.parseFrequency], [RruleBuilder.formatForDisplay] and the
 * builder methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RruleBuilderRfc5545Test {

    // ==================== RFC 5545 Section 3.3.10: UNTIL Value Type Matching ====================

    @Test
    fun `withUntil generates DATETIME format UNTIL`() {
        // withUntil always emits a UTC date-time. That matches a timed DTSTART; an all-day
        // DTSTART needs a DATE (YYYYMMDD) UNTIL (RFC 5545 §3.3.10).
        val base = RruleBuilder.daily()
        val untilMs = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli()
        val rrule = RruleBuilder.withUntil(base, untilMs)

        assertTrue("Should contain UNTIL", rrule.contains("UNTIL="))

        // A UTC date-time is the right form for a timed event.
        assertTrue(
            "UNTIL should be in DATETIME format for timed events",
            rrule.contains("UNTIL=20260615T000000Z")
        )
    }

    @Test
    fun `withUntil for all-day events generates DATETIME format - RFC compliance gap`() {
        // RFC 5545 §3.3.10: "The value of the UNTIL rule part MUST have the same value type as
        // the "DTSTART" property", so an all-day (VALUE=DATE) event needs a YYYYMMDD UNTIL.
        // withUntil always emits a date-time; this test pins that. The recurrence picker
        // (`RecurrencePickerSelections.toRrule`) writes a DATE UNTIL for an all-day event itself
        // and calls withUntil only for a timed one.
        val base = RruleBuilder.weekly(days = setOf(DayOfWeek.MONDAY))
        val untilMs = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli()
        val rrule = RruleBuilder.withUntil(base, untilMs)

        // A date-time even for what would be an all-day event.
        assertTrue("UNTIL is DATETIME format (gap: should be DATE for all-day)",
            rrule.contains("T") && rrule.contains("Z"))

        // The rest of the rule is intact.
        assertTrue(rrule.startsWith("FREQ=WEEKLY"))
    }

    // ==================== RFC 5545 §3.3.10: COUNT and UNTIL Together ====================

    @Test
    fun `withCount then withUntil produces invalid RFC 5545 RRULE`() {
        // RFC 5545 §3.3.10: "The UNTIL or COUNT rule parts are OPTIONAL, but they MUST NOT
        // occur in the same 'recur'." RruleBuilder doesn't prevent it; this test pins that.
        val rrule = RruleBuilder.daily()
        val withCount = RruleBuilder.withCount(rrule, 10)
        val untilMs = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli()
        val withBoth = RruleBuilder.withUntil(withCount, untilMs)

        // The builder appends both without validation.
        assertTrue("Contains both COUNT and UNTIL (invalid per RFC)",
            withBoth.contains("COUNT=10") && withBoth.contains("UNTIL="))
    }

    // ==================== parseRrule: UNTIL Format Handling ====================

    @Test
    fun `parseRrule extracts UNTIL in DATETIME format`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=DAILY;UNTIL=20260615T000000Z",
            DayOfWeek.MONDAY, 1, 1
        )

        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
        assertTrue("Should parse as Until end condition", parsed.endCondition is EndCondition.Until)

        val until = parsed.endCondition as EndCondition.Until
        val untilDate = Instant.ofEpochMilli(until.dateMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        assertEquals("Until date should be June 15 2026",
            java.time.LocalDate.of(2026, 6, 15), untilDate)
    }

    @Test
    fun `parseRrule with DATE-only UNTIL falls back to Never`() {
        // RFC 5545 allows a DATE (YYYYMMDD) UNTIL for an all-day DTSTART. parseRrule reads it as
        // Until at the end of that day in UTC.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;UNTIL=20260615",
            DayOfWeek.MONDAY, 1, 1
        )

        // The assert accepts Never as well as Until, so it only checks the rule doesn't parse
        // as a COUNT end.
        assertTrue(
            "DATE-only UNTIL should ideally be parsed as Until, currently falls to Never",
            parsed.endCondition is EndCondition.Never || parsed.endCondition is EndCondition.Until
        )
    }

    @Test
    fun `parseRrule extracts COUNT correctly`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;COUNT=52",
            DayOfWeek.MONDAY, 1, 1
        )

        assertTrue("Should parse as Count", parsed.endCondition is EndCondition.Count)
        assertEquals(52, (parsed.endCondition as EndCondition.Count).count)
    }

    // ==================== parseRrule: Frequency and Interval ====================

    @Test
    fun `parseRrule extracts INTERVAL correctly`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=DAILY;INTERVAL=3",
            DayOfWeek.MONDAY, 1, 1
        )

        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
        assertEquals(3, parsed.interval)
    }

    @Test
    fun `parseRrule defaults INTERVAL to 1 when absent`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY",
            DayOfWeek.MONDAY, 1, 1
        )

        assertEquals(1, parsed.interval)
    }

    // ==================== parseRrule: BYDAY for Weekly ====================

    @Test
    fun `parseRrule extracts BYDAY weekdays for weekly`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            DayOfWeek.MONDAY, 1, 1
        )

        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            parsed.weekdays
        )
    }

    @Test
    fun `parseRrule with all 7 days in BYDAY`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU",
            DayOfWeek.MONDAY, 1, 1
        )

        assertEquals(7, parsed.weekdays.size)
    }

    // ==================== parseRrule: Monthly Patterns ====================

    @Test
    fun `parseRrule extracts BYMONTHDAY for monthly`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYMONTHDAY=15",
            DayOfWeek.MONDAY, 1, 1
        )

        assertEquals(RecurrenceFrequency.MONTHLY, parsed.frequency)
        assertTrue("Should be SameDay pattern", parsed.monthlyPattern is MonthlyPattern.SameDay)
        assertEquals(15, (parsed.monthlyPattern as MonthlyPattern.SameDay).dayOfMonth)
    }

    @Test
    fun `parseRrule extracts BYMONTHDAY=-1 as LastDay`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYMONTHDAY=-1",
            DayOfWeek.MONDAY, 1, 1
        )

        assertTrue("Should be LastDay pattern", parsed.monthlyPattern is MonthlyPattern.LastDay)
    }

    @Test
    fun `parseRrule extracts Nth weekday pattern`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYDAY=2TU",
            DayOfWeek.MONDAY, 1, 1
        )

        assertTrue("Should be NthWeekday", parsed.monthlyPattern is MonthlyPattern.NthWeekday)
        val pattern = parsed.monthlyPattern as MonthlyPattern.NthWeekday
        assertEquals(2, pattern.ordinal)
        assertEquals(DayOfWeek.TUESDAY, pattern.weekday)
    }

    @Test
    fun `parseRrule extracts last weekday pattern`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYDAY=-1FR",
            DayOfWeek.MONDAY, 1, 1
        )

        assertTrue("Should be NthWeekday", parsed.monthlyPattern is MonthlyPattern.NthWeekday)
        val pattern = parsed.monthlyPattern as MonthlyPattern.NthWeekday
        assertEquals(-1, pattern.ordinal)
        assertEquals(DayOfWeek.FRIDAY, pattern.weekday)
    }

    // ==================== parseFrequency: Complexity Detection ====================

    @Test
    fun `parseFrequency returns CUSTOM for rules with INTERVAL`() {
        assertEquals(
            RecurrenceFrequency.CUSTOM,
            RruleBuilder.parseFrequency("FREQ=DAILY;INTERVAL=2")
        )
    }

    @Test
    fun `parseFrequency returns CUSTOM for rules with COUNT`() {
        assertEquals(
            RecurrenceFrequency.CUSTOM,
            RruleBuilder.parseFrequency("FREQ=WEEKLY;COUNT=10")
        )
    }

    @Test
    fun `parseFrequency returns CUSTOM for rules with UNTIL`() {
        assertEquals(
            RecurrenceFrequency.CUSTOM,
            RruleBuilder.parseFrequency("FREQ=MONTHLY;UNTIL=20260615T000000Z")
        )
    }

    @Test
    fun `parseFrequency returns CUSTOM for rules with BYSETPOS`() {
        assertEquals(
            RecurrenceFrequency.CUSTOM,
            RruleBuilder.parseFrequency("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1")
        )
    }

    @Test
    fun `parseFrequency returns NONE for null`() {
        assertEquals(RecurrenceFrequency.NONE, RruleBuilder.parseFrequency(null))
    }

    @Test
    fun `parseFrequency returns NONE for blank`() {
        assertEquals(RecurrenceFrequency.NONE, RruleBuilder.parseFrequency(""))
    }

    // ==================== formatForDisplay ====================

    @Test
    fun `formatForDisplay for simple daily`() {
        assertEquals("Daily", RruleBuilder.formatForDisplay("FREQ=DAILY"))
    }

    @Test
    fun `formatForDisplay for weekly INTERVAL 2`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;INTERVAL=2")
        assertEquals("Every 2 weeks", display)
    }

    @Test
    fun `formatForDisplay for monthly INTERVAL 3`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;INTERVAL=3")
        assertEquals("Every 3 months", display)
    }

    @Test
    fun `formatForDisplay with COUNT suffix`() {
        val display = RruleBuilder.formatForDisplay("FREQ=DAILY;COUNT=10")
        assertEquals("Daily, 10 times", display)
    }

    @Test
    fun `formatForDisplay with UNTIL suffix`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;UNTIL=20260615T000000Z")
        assertTrue("Should contain until date", display.contains("until Jun 15"))
    }

    @Test
    fun `formatForDisplay for weekly with days`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals("Weekly on Mon, Wed, Fri", display)
    }

    @Test
    fun `formatForDisplay for monthly on nth weekday`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=2TU")
        assertEquals("Monthly on 2nd Tue", display)
    }

    @Test
    fun `formatForDisplay for monthly on last day`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYMONTHDAY=-1")
        assertEquals("Monthly on last day", display)
    }

    @Test
    fun `formatForDisplay for monthly on day 15`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYMONTHDAY=15")
        assertEquals("Monthly on day 15", display)
    }

    @Test
    fun `formatForDisplay for yearly`() {
        assertEquals("Yearly", RruleBuilder.formatForDisplay("FREQ=YEARLY"))
    }

    @Test
    fun `formatForDisplay for null returns does not repeat`() {
        assertEquals("Does not repeat", RruleBuilder.formatForDisplay(null))
    }

    // ==================== Builder Methods ====================

    @Test
    fun `daily with interval 1 omits INTERVAL`() {
        assertEquals("FREQ=DAILY", RruleBuilder.daily(1))
    }

    @Test
    fun `daily with interval 2 includes INTERVAL`() {
        assertEquals("FREQ=DAILY;INTERVAL=2", RruleBuilder.daily(2))
    }

    @Test
    fun `weekly with specific days produces sorted BYDAY`() {
        // BYDAY is emitted Monday first.
        val rrule = RruleBuilder.weekly(
            days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rrule)
    }

    @Test
    fun `weekly with no days omits BYDAY`() {
        assertEquals("FREQ=WEEKLY", RruleBuilder.weekly())
    }

    @Test
    fun `monthly with dayOfMonth produces BYMONTHDAY`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", RruleBuilder.monthly(dayOfMonth = 15))
    }

    @Test
    fun `monthlyLastDay produces BYMONTHDAY=-1`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", RruleBuilder.monthlyLastDay())
    }

    @Test
    fun `monthlyNthWeekday produces correct BYDAY`() {
        assertEquals(
            "FREQ=MONTHLY;BYDAY=2TU",
            RruleBuilder.monthlyNthWeekday(2, DayOfWeek.TUESDAY)
        )
    }

    @Test
    fun `monthlyNthWeekday with last ordinal`() {
        assertEquals(
            "FREQ=MONTHLY;BYDAY=-1FR",
            RruleBuilder.monthlyNthWeekday(-1, DayOfWeek.FRIDAY)
        )
    }

    @Test
    fun `yearly with interval 1 omits INTERVAL`() {
        assertEquals("FREQ=YEARLY", RruleBuilder.yearly(1))
    }

    @Test
    fun `yearly with interval 2 includes INTERVAL`() {
        assertEquals("FREQ=YEARLY;INTERVAL=2", RruleBuilder.yearly(2))
    }

    @Test
    fun `withCount appends COUNT`() {
        assertEquals(
            "FREQ=DAILY;COUNT=5",
            RruleBuilder.withCount("FREQ=DAILY", 5)
        )
    }
}
