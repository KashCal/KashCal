package org.onekash.kashcal.domain.rrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

/**
 * Tests [RruleBuilder]: building, WKST emission, parsing (frequency, full rule, extra BY*
 * parts), round trips, display, day abbreviations and the end date in the summary.
 */
@RunWith(RobolectricTestRunner::class)
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

    // ==================== WKST Emission (#214) ====================
    // RFC 5545 §3.3.10: WKST "is significant when a WEEKLY "RRULE" has an interval greater
    // than 1, and a BYDAY rule part is specified". The builder emits it only when
    // interval >= 2 and days.size >= 2.

    @Test
    fun `weekly with biweekly multi-day BYDAY and WKST=SU emits WKST`() {
        // BYDAY is emitted Monday first.
        val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val result = RruleBuilder.weekly(2, days, wkst = DayOfWeek.SUNDAY)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH,SU;WKST=SU", result)
    }

    @Test
    fun `weekly with biweekly multi-day BYDAY and WKST=MO emits WKST`() {
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val result = RruleBuilder.weekly(2, days, wkst = DayOfWeek.MONDAY)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;WKST=MO", result)
    }

    @Test
    fun `weekly with biweekly multi-day BYDAY and WKST=SA emits WKST`() {
        val days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val result = RruleBuilder.weekly(2, days, wkst = DayOfWeek.SATURDAY)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,SU;WKST=SA", result)
    }

    @Test
    fun `weekly interval=1 does not emit WKST even with wkst arg`() {
        val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val result = RruleBuilder.weekly(1, days, wkst = DayOfWeek.SUNDAY)
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH,SU", result)
    }

    @Test
    fun `weekly empty days does not emit WKST even with wkst arg`() {
        val result = RruleBuilder.weekly(2, emptySet(), wkst = DayOfWeek.SUNDAY)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", result)
    }

    @Test
    fun `weekly single-day BYDAY does not emit WKST even with wkst arg`() {
        // WKST can't change a single-day rule whose day is DTSTART's. The gate is safe for
        // KashCal because all callers keep DTSTART's day in BYDAY.
        val days = setOf(DayOfWeek.MONDAY)
        val result = RruleBuilder.weekly(2, days, wkst = DayOfWeek.SUNDAY)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", result)
    }

    @Test
    fun `weekly with wkst=null preserves pre-WKST output (back-compat)`() {
        val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val result = RruleBuilder.weekly(2, days, wkst = null)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH,SU", result)
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

    @Test
    fun `parseRrule extracts date-value UNTIL end condition`() {
        // RFC 5545 §3.3.10 allows UNTIL to be a DATE value (no T...Z). Servers pair this form
        // with a VALUE=DATE DTSTART.
        val result = RruleBuilder.parseRrule("FREQ=WEEKLY;UNTIL=20260106", DayOfWeek.MONDAY, 1, 1)
        assertTrue("expected Until, got ${result.endCondition}", result.endCondition is EndCondition.Until)
    }

    // ========== Extra-token preservation (BYMONTH, BYWEEKNO, BYYEARDAY, BYSETPOS) ==========

    @Test
    fun `parseRrule captures BYMONTH and BYMONTHDAY as extras for yearly rule`() {
        // The picker models BYMONTHDAY only for monthly rules, so on FREQ=YEARLY it is an extra
        // alongside BYMONTH. Both must round-trip verbatim to keep "every Jan 15".
        val result = RruleBuilder.parseRrule(
            "FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15", DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(listOf("BYMONTH=1", "BYMONTHDAY=15"), result.extraTokens)
    }

    @Test
    fun `parseRrule captures BYSETPOS as extra token`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(listOf("BYSETPOS=-1"), result.extraTokens)
    }

    @Test
    fun `parseRrule captures BYWEEKNO and BYYEARDAY as extra tokens`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=YEARLY;BYWEEKNO=1,2;BYYEARDAY=100", DayOfWeek.MONDAY, 1, 1
        )
        assertTrue("expected BYWEEKNO, got ${result.extraTokens}", "BYWEEKNO=1,2" in result.extraTokens)
        assertTrue("expected BYYEARDAY, got ${result.extraTokens}", "BYYEARDAY=100" in result.extraTokens)
    }

    @Test
    fun `parseRrule with no extra tokens returns empty list`() {
        val result = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(emptyList<String>(), result.extraTokens)
    }

    @Test
    fun `parseRrule preserves frequency for BYMONTH-bearing rule (routing to CUSTOM happens at option layer)`() {
        // parseRrule keeps the source FREQ so mapFrequencyToCustomUnit can pick the unit (YEAR
        // for FREQ=YEARLY). The switch to FrequencyOption.CUSTOM for a rule with extras belongs
        // to selectInitialFrequencyOption, not the parser.
        val result = RruleBuilder.parseRrule(
            "FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15", DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.YEARLY, result.frequency)
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
    fun `formatForDisplay weekly with INTERVAL 2`() {
        assertEquals("Every 2 weeks", RruleBuilder.formatForDisplay("FREQ=WEEKLY;INTERVAL=2"))
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
    fun `formatForDisplay monthly with INTERVAL 3`() {
        assertEquals("Every 3 months", RruleBuilder.formatForDisplay("FREQ=MONTHLY;INTERVAL=3"))
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
    fun `formatForDisplay monthly with ordinal-less BYDAY does not render 0th`() {
        // FREQ=MONTHLY;BYDAY=MO is RFC-valid ('every Monday of every month'). The display's
        // nth-weekday regex needs a digit, so this doesn't render as 'Monthly on 0th Mon'.
        val result = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=MO")
        assertTrue("must not render 0th: $result", !result.contains("0th") && !result.contains("0 "))
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
        assertTrue("Should contain date: $result", result.contains("until Jan 6"))
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

    // ==================== End date in the summary ====================
    //
    // A date-time UNTIL is an instant (RFC 5545 section 3.3.10); the summary names
    // the date it falls on in the event's zone. A date UNTIL is shown as written.

    private val english = RruleDisplayStrings.english()
    private val losAngeles = java.time.ZoneId.of("America/Los_Angeles")

    @Test
    fun `an end written in UTC is shown on the date it falls on in the event's zone`() {
        assertEquals(
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261231", english),
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20270101T075959Z", english, untilZone = losAngeles),
        )
    }

    @Test
    fun `the RFC daily-until example is shown ending on its last occurrence day`() {
        // RFC 5545 section 3.8.5.3: DTSTART 09:00 New York, UNTIL=19971224T000000Z, last occurrence
        // Dec 23.
        assertEquals(
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=19971223", english),
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=19971224T000000Z", english, untilZone = java.time.ZoneId.of("America/New_York")),
        )
    }

    @Test
    fun `without an event zone the summary shows the UTC date as before`() {
        assertEquals(
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20270101", english),
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20270101T075959Z", english),
        )
    }

    @Test
    fun `a date-only end is shown as written whatever the zone`() {
        assertEquals(
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261231", english),
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261231", english, untilZone = java.time.ZoneId.of("Asia/Tokyo")),
        )
    }

    @Test
    fun `the split summary shows the end in the event's zone too`() {
        val expected = RruleBuilder.formatForDisplayParts("FREQ=WEEKLY;UNTIL=20261231", english)
        val actual = RruleBuilder.formatForDisplayParts("FREQ=WEEKLY;UNTIL=20270101T075959Z", english, untilZone = losAngeles)
        assertEquals(expected, actual)
    }

    @Test
    fun `a leap-second end time is read as second 59`() {
        // RFC 5545 section 3.3.12: without leap-second support, second 60 SHOULD be read as 59.
        assertEquals(
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261231", english),
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20270101T075960Z", english, untilZone = losAngeles),
        )
    }

    @Test
    fun `an end time that can't be read falls back to its date`() {
        assertEquals(
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261231", english),
            RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261231T256000Z", english, untilZone = losAngeles),
        )
    }

    @Test
    fun `an end date that can't exist is left out of the summary`() {
        val noEnd = RruleBuilder.formatForDisplay("FREQ=DAILY", english)
        assertEquals(noEnd, RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20260230", english))
        assertEquals(noEnd, RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20261332T120000Z", english, untilZone = losAngeles))
        assertNull(RruleBuilder.formatForDisplayParts("FREQ=DAILY;UNTIL=20260230", english).second)
        // The same impossible date with a time is left out too, not moved to Feb 28.
        assertEquals(noEnd, RruleBuilder.formatForDisplay("FREQ=DAILY;UNTIL=20260230T120000Z", english, untilZone = losAngeles))
    }
}

