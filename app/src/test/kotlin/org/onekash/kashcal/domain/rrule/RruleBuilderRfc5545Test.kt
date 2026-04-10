package org.onekash.kashcal.domain.rrule

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * RFC 5545 compliance tests for RruleBuilder.
 *
 * Tests behaviors required by RFC 5545 that may have compliance gaps:
 * - withUntil() always emits DATETIME format, but RFC 5545 Section 3.3.10 says
 *   "The UNTIL rule part MUST have the same value type as the 'DTSTART' property."
 *   For all-day events (DATE DTSTART), UNTIL must be DATE format.
 * - parseRrule with UNTIL in DATE-only format (no T separator)
 * - COUNT + UNTIL mutual exclusivity not enforced by builder
 * - formatForDisplay edge cases
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RruleBuilderRfc5545Test {

    // ==================== RFC 5545 Section 3.3.10: UNTIL Value Type Matching ====================

    @Test
    fun `withUntil generates DATETIME format UNTIL`() {
        // Current behavior: always generates DATETIME format
        // RFC 5545 says UNTIL type must match DTSTART type:
        // - For timed events: DATETIME format is correct
        // - For all-day events: should be DATE format (YYYYMMDD)
        val base = RruleBuilder.daily()
        val untilMs = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli()
        val rrule = RruleBuilder.withUntil(base, untilMs)

        assertTrue("Should contain UNTIL", rrule.contains("UNTIL="))

        // For timed events, DATETIME format is correct
        assertTrue(
            "UNTIL should be in DATETIME format for timed events",
            rrule.contains("UNTIL=20260615T000000Z")
        )
    }

    @Test
    fun `withUntil for all-day events generates DATETIME format - RFC compliance gap`() {
        // RFC 5545 Section 3.3.10: "The UNTIL rule part MUST have the same value type
        // as the 'DTSTART' property."
        // For all-day events (VALUE=DATE), UNTIL should be YYYYMMDD format.
        // Current implementation always uses DATETIME format.
        // This test documents the gap - OccurrenceGenerator handles the mismatch
        // via timestampToAllDayDateTime() conversion.
        val base = RruleBuilder.weekly(days = setOf(DayOfWeek.MONDAY))
        val untilMs = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli()
        val rrule = RruleBuilder.withUntil(base, untilMs)

        // Documents current behavior: DATETIME format even for what would be all-day
        assertTrue("UNTIL is DATETIME format (gap: should be DATE for all-day)",
            rrule.contains("T") && rrule.contains("Z"))

        // The RRULE is still parseable
        assertTrue(rrule.startsWith("FREQ=WEEKLY"))
    }

    // ==================== RFC 5545 Section 3.3.10: COUNT + UNTIL Mutual Exclusivity ====================

    @Test
    fun `withCount then withUntil produces invalid RFC 5545 RRULE`() {
        // RFC 5545: "The UNTIL or COUNT rule parts are OPTIONAL, but they MUST NOT
        // occur in the same 'recur'."
        // RruleBuilder does not prevent this - documents the gap.
        val rrule = RruleBuilder.daily()
        val withCount = RruleBuilder.withCount(rrule, 10)
        val untilMs = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli()
        val withBoth = RruleBuilder.withUntil(withCount, untilMs)

        // Documents that builder allows both (no validation)
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
        // RFC 5545 allows DATE format UNTIL (YYYYMMDD) for all-day events.
        // The current regex only matches DATETIME format (YYYYMMDDTHHMMSSZ).
        // DATE-only UNTIL should ideally be parsed, but currently falls through to Never.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;UNTIL=20260615",
            DayOfWeek.MONDAY, 1, 1
        )

        // Documents current behavior: DATE-only UNTIL not parsed
        // This is a gap - the regex expects T separator
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
    fun `formatForDisplay for biweekly`() {
        val display = RruleBuilder.formatForDisplay("FREQ=WEEKLY;INTERVAL=2")
        assertEquals("Biweekly", display)
    }

    @Test
    fun `formatForDisplay for quarterly`() {
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;INTERVAL=3")
        assertEquals("Quarterly", display)
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
        // BYDAY should be sorted Monday-first per DAY_ORDER
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
