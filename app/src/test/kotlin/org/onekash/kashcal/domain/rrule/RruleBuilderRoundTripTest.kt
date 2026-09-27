package org.onekash.kashcal.domain.rrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests [RruleBuilder] round trips and parser edge cases. [RruleBuilderTest] and
 * [RruleBuilderRfc5545Test] cover unit behavior, [RruleBuilderAdversarialTest] bad inputs.
 *
 * 1. Build then parse: each builder's output (daily, weekly, monthly in all three patterns,
 *    yearly, COUNT, UNTIL, WKST) parses back to the values that built it, a 5th-weekday rule
 *    isn't coerced, and a parsed rule wins over the start-date defaults.
 * 2. Server fixtures: rules from the Zoho, Stalwart and SOGo integration test bodies, plus
 *    weekly-interval and monthly nth-weekday shapes.
 * 3. Token order: the parser uses `Regex.find` and `String.contains`, so rule-part order,
 *    a trailing semicolon and an unknown part don't change the result.
 * 4. Pinned behavior: the parser is case-sensitive, reads WKST (null when absent or when the
 *    builder dropped it from an interval-1 rule), NthWeekday wins over BYMONTHDAY, the
 *    first FREQ in DAILY, WEEKLY, MONTHLY, YEARLY order wins, a negative INTERVAL reads as 1,
 *    display of ordinals past 4 and below -1, and the date-time and date UNTIL forms.
 */
class RruleBuilderRoundTripTest {

    // ==================== Build → Parse Parity ====================

    @Test
    fun `daily roundtrip preserves frequency and interval`() {
        for (interval in listOf(1, 2, 3, 7, 99, 365)) {
            val rrule = RruleBuilder.daily(interval)
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertEquals("daily($interval).freq", RecurrenceFrequency.DAILY, parsed.frequency)
            assertEquals("daily($interval).interval", interval, parsed.interval)
        }
    }

    @Test
    fun `weekly roundtrip preserves frequency interval and weekdays for all subsets`() {
        // A single day, a weekend pair, MO/WE/FR, the work week, all 7.
        val cases = listOf(
            setOf(DayOfWeek.MONDAY),
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                  DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            DayOfWeek.entries.toSet(),
        )
        for (days in cases) {
            for (interval in listOf(1, 2, 4, 99)) {
                val rrule = RruleBuilder.weekly(interval, days)
                val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
                assertEquals("weekly($interval, $days).interval", interval, parsed.interval)
                assertEquals("weekly($interval, $days).weekdays", days, parsed.weekdays)
            }
        }
    }

    @Test
    fun `monthly SameDay roundtrip preserves dayOfMonth at every boundary`() {
        for (dom in listOf(1, 15, 28, 29, 30, 31)) {
            val rrule = RruleBuilder.monthly(1, dom)
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertEquals(MonthlyPattern.SameDay(dom), parsed.monthlyPattern)
        }
    }

    @Test
    fun `monthly LastDay roundtrip preserves LastDay across intervals`() {
        for (interval in listOf(1, 2, 3, 6, 12)) {
            val rrule = RruleBuilder.monthlyLastDay(interval)
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertEquals(MonthlyPattern.LastDay, parsed.monthlyPattern)
            assertEquals(interval, parsed.interval)
        }
    }

    @Test
    fun `monthly NthWeekday roundtrip preserves ordinal and weekday for 1st through 4th and last`() {
        val ordinals = listOf(1, 2, 3, 4, -1)
        for (ordinal in ordinals) {
            for (day in DayOfWeek.entries) {
                val rrule = RruleBuilder.monthlyNthWeekday(ordinal, day)
                val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
                assertEquals(
                    "monthlyNthWeekday($ordinal, $day)",
                    MonthlyPattern.NthWeekday(ordinal, day),
                    parsed.monthlyPattern
                )
            }
        }
    }

    @Test
    fun `yearly roundtrip preserves interval`() {
        for (interval in listOf(1, 2, 4, 10)) {
            val rrule = RruleBuilder.yearly(interval)
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertEquals(RecurrenceFrequency.YEARLY, parsed.frequency)
            assertEquals(interval, parsed.interval)
        }
    }

    // ==================== Monthly nth-weekday: exact BYDAY string ====================

    /** RFC 5545 day abbreviation for each DayOfWeek, for exact-string assertions. */
    private fun abbrev(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    @Test
    fun `monthly nth-weekday emits exact BYDAY and round-trips for every ordinal x weekday`() {
        // Enumerates the whole space, {1,2,3,4,-1} x 7 weekdays, so every case is covered.
        // Asserts the exact BYDAY token (e.g. -1 -> "-1FR", 2 -> "2MO") and that parseRrule
        // returns the same NthWeekday.
        for (ordinal in listOf(1, 2, 3, 4, -1)) {
            for (day in DayOfWeek.entries) {
                val prefix = if (ordinal == -1) "-1" else ordinal.toString()
                val expected = "FREQ=MONTHLY;BYDAY=$prefix${abbrev(day)}"
                val rrule = RruleBuilder.monthlyNthWeekday(ordinal, day)
                assertEquals("emit($ordinal, $day)", expected, rrule)

                val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
                assertEquals(
                    "parse($rrule)",
                    MonthlyPattern.NthWeekday(ordinal, day),
                    parsed.monthlyPattern,
                )
            }
        }
    }

    @Test
    fun `monthly nth-weekday with interval emits canonical FREQ INTERVAL BYDAY order and round-trips`() {
        // The picker's Custom month unit passes the interval through. Asserts the builder's
        // token order: FREQ, then INTERVAL, then BYDAY.
        for (ordinal in listOf(1, 2, 3, 4, -1)) {
            for (day in DayOfWeek.entries) {
                val prefix = if (ordinal == -1) "-1" else ordinal.toString()
                val expected = "FREQ=MONTHLY;INTERVAL=2;BYDAY=$prefix${abbrev(day)}"
                val rrule = RruleBuilder.monthlyNthWeekday(ordinal, day, interval = 2)
                assertEquals("emit($ordinal, $day, interval=2)", expected, rrule)

                val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
                assertEquals(2, parsed.interval)
                assertEquals(
                    MonthlyPattern.NthWeekday(ordinal, day),
                    parsed.monthlyPattern,
                )
            }
        }
    }

    // ============ Parsed rule wins over start-date-derived defaults ============

    @Test
    fun `parseRrule BYDAY -1FR yields last Friday even when start is Saturday the 18th third occurrence`() {
        // A "last Friday" rule opened on an event starting Saturday the 18th (the 3rd
        // Saturday) parses to NthWeekday(-1, FRIDAY): the rule wins over the start-date
        // defaults SATURDAY and ordinal 3.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYDAY=-1FR",
            defaultWeekday = DayOfWeek.SATURDAY,
            defaultDayOfMonth = 18,
            defaultOrdinal = 3,
        )
        assertEquals(MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY), parsed.monthlyPattern)
    }

    @Test
    fun `parseRrule BYDAY 2MO yields second Monday regardless of Saturday start`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYDAY=2MO",
            defaultWeekday = DayOfWeek.SATURDAY,
            defaultDayOfMonth = 18,
            defaultOrdinal = 3,
        )
        assertEquals(MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY), parsed.monthlyPattern)
    }

    @Test
    fun `parseRrule BYMONTHDAY wins over start-date defaults`() {
        // The same for the by-date branch: BYMONTHDAY=9 wins over the start date's day (18).
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYMONTHDAY=9",
            defaultWeekday = DayOfWeek.SATURDAY,
            defaultDayOfMonth = 18,
            defaultOrdinal = 3,
        )
        assertEquals(MonthlyPattern.SameDay(9), parsed.monthlyPattern)
    }

    // ==================== Adversarial: 5FR round-trips uncoerced ====================

    @Test
    fun `monthly BYDAY 5FR round-trips verbatim and is not coerced to Last or 4th`() {
        // The picker offers only 1st-4th and last, so it can't select a "5th Friday", but an
        // imported one must not be silently coerced to -1FR or 4FR. Parse then build keeps
        // BYDAY=5FR.
        val parsed = RruleBuilder.parseRrule("FREQ=MONTHLY;BYDAY=5FR", DayOfWeek.MONDAY, 1, 1)
        assertEquals(MonthlyPattern.NthWeekday(5, DayOfWeek.FRIDAY), parsed.monthlyPattern)
        val rebuilt = RruleBuilder.monthlyNthWeekday(5, DayOfWeek.FRIDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=5FR", rebuilt)
    }

    @Test
    fun `withCount roundtrip preserves count across boundaries`() {
        for (count in listOf(1, 2, 10, 52, 365, Int.MAX_VALUE)) {
            val rrule = RruleBuilder.withCount("FREQ=DAILY", count)
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertTrue("count=$count not parsed as Count", parsed.endCondition is EndCondition.Count)
            assertEquals(count, (parsed.endCondition as EndCondition.Count).count)
        }
    }

    @Test
    fun `withUntil roundtrip preserves UTC instant across leap-year and end-of-year`() {
        // Feb 29 2028 (leap), end of year, far future. The builder and parser use the same
        // pattern, so each must round-trip to the millisecond.
        val cases = listOf(
            "2028-02-29T12:34:56Z",
            "2026-12-31T23:59:59Z",
            "2099-01-01T00:00:00Z",
        )
        for (iso in cases) {
            val original = Instant.parse(iso).toEpochMilli()
            val rrule = RruleBuilder.withUntil("FREQ=DAILY", original)
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertTrue("UNTIL=$iso not Until", parsed.endCondition is EndCondition.Until)
            assertEquals("UNTIL=$iso millis drift", original,
                (parsed.endCondition as EndCondition.Until).dateMillis)
        }
    }

    // ==================== Real CalDAV Server Fixtures ====================
    // Rule shapes from the server integration test bodies (Stalwart, SOGo, Zoho). If a parser
    // change starts dropping data on these, sync regresses.

    @Test
    fun `Zoho weekly with BYDAY and COUNT roundtrips`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            parsed.weekdays
        )
        assertTrue(parsed.endCondition is EndCondition.Count)
        assertEquals(10, (parsed.endCondition as EndCondition.Count).count)
    }

    @Test
    fun `Zoho monthly with BYMONTHDAY and COUNT roundtrips`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYMONTHDAY=15;COUNT=6",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.MONTHLY, parsed.frequency)
        assertEquals(MonthlyPattern.SameDay(15), parsed.monthlyPattern)
        assertEquals(EndCondition.Count(6), parsed.endCondition)
    }

    @Test
    fun `Stalwart and SoGo simple weekly with COUNT roundtrips`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;COUNT=4",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(EndCondition.Count(4), parsed.endCondition)
    }

    @Test
    fun `Zoho yearly with COUNT roundtrips`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=YEARLY;COUNT=3",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.YEARLY, parsed.frequency)
        assertEquals(EndCondition.Count(3), parsed.endCondition)
    }

    @Test
    fun `iCloud-style weekly with INTERVAL=4 BYDAY=MO roundtrips with interval and weekday intact`() {
        // A server-sent rule the app parses, re-emits and saves; the interval and weekday must
        // survive.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;INTERVAL=4;BYDAY=MO",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(4, parsed.interval)
        assertEquals(setOf(DayOfWeek.MONDAY), parsed.weekdays)

        val rebuilt = RruleBuilder.weekly(parsed.interval, parsed.weekdays)
        assertEquals("FREQ=WEEKLY;INTERVAL=4;BYDAY=MO", rebuilt)
    }

    @Test
    fun `Zoho-style monthly with INTERVAL and BYDAY 1MO roundtrips`() {
        // A common server-emitted form: "first Monday every two months".
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;INTERVAL=2;BYDAY=1MO",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(2, parsed.interval)
        assertEquals(MonthlyPattern.NthWeekday(1, DayOfWeek.MONDAY), parsed.monthlyPattern)
    }

    // ==================== Token-Order Independence ====================

    @Test
    fun `parser is independent of FREQ position in token list`() {
        // RFC 5545 §3.3.10: "Compliant applications MUST accept rule parts ordered in any
        // sequence". The parser uses contains/find, so position doesn't matter.
        val parsed = RruleBuilder.parseRrule(
            "INTERVAL=3;BYDAY=MO,WE;FREQ=WEEKLY;COUNT=12",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(3, parsed.interval)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), parsed.weekdays)
        assertEquals(EndCondition.Count(12), parsed.endCondition)
    }

    @Test
    fun `parser handles trailing semicolon`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=DAILY;INTERVAL=2;",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
        assertEquals(2, parsed.interval)
    }

    @Test
    fun `parser ignores unknown extension tokens`() {
        // The recur grammar (RFC 5545 §3.3.10) has no X- rule part; §3.8.8.2 X- names are for
        // properties. The parser's regexes ignore an unknown part anyway.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;X-MICROSOFT-RSCID=foo;BYDAY=TU",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(setOf(DayOfWeek.TUESDAY), parsed.weekdays)
    }

    // ==================== Pinned Parser Behavior ====================
    // A refactor that changes any of these fails here.

    @Test
    fun `parser is case-sensitive — lowercase freq is not recognized`() {
        // Lowercase tokens fall through to NONE because contains() is case-sensitive, though
        // RFC 5545 §3.1 makes enumerated values case-insensitive. Real-world CalDAV servers
        // always emit uppercase, so this is acceptable; pinned so a case-insensitive change is
        // deliberate.
        val parsed = RruleBuilder.parseRrule(
            "freq=daily",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.NONE, parsed.frequency)
    }

    @Test
    fun `parseRrule extracts WKST=SU into ParsedRecurrence wkst`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;WKST=SU",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(DayOfWeek.SUNDAY, parsed.wkst)
    }

    @Test
    fun `parseRrule reports null wkst when rule omits WKST`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE",
            DayOfWeek.MONDAY, 1, 1
        )
        assertNull(parsed.wkst)
    }

    @Test
    fun `WKST round-trip preserves all 7 days through build then parse`() {
        for (day in DayOfWeek.entries) {
            val rrule = RruleBuilder.weekly(
                interval = 2,
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                wkst = day,
            )
            val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
            assertEquals("WKST=$day round-trip", day, parsed.wkst)
        }
    }

    @Test
    fun `WKST is dropped from emission when interval lt 2 — round-trip yields null wkst`() {
        // RFC 5545 §3.3.10 makes WKST significant for a WEEKLY rule only with an interval above
        // 1 and a BYDAY, so the builder leaves it out here and the parse reports null. A null
        // ParsedRecurrence.wkst can mean the builder dropped a WKST that had no effect, not
        // only that the rule never had one.
        val rrule = RruleBuilder.weekly(
            interval = 1,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            wkst = DayOfWeek.SUNDAY,
        )
        assertFalse("interval=1 must not emit WKST: $rrule", rrule.contains("WKST="))
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertNull(parsed.wkst)
    }

    @Test
    fun `parseRrule with both BYMONTHDAY and BYDAY-ordinal — NthWeekday wins`() {
        // RFC 5545 allows both (BYDAY then limits the BYMONTHDAY days). MonthlyPattern holds
        // one choice, so the parser prefers the BYDAY ordinal; pinned so a refactor doesn't
        // silently flip it.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYMONTHDAY=15;BYDAY=2TU",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(
            MonthlyPattern.NthWeekday(2, DayOfWeek.TUESDAY),
            parsed.monthlyPattern
        )
    }

    @Test
    fun `parseRrule with multiple FREQ tokens — DAILY wins over WEEKLY by order`() {
        // Two FREQ parts are malformed (RFC 5545 §3.3.10: FREQ "MUST NOT occur more than
        // once"). The parser checks DAILY, WEEKLY, MONTHLY, YEARLY in that order, so the first
        // in that order wins whatever the rule string's order.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;FREQ=DAILY",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
    }

    @Test
    fun `parseRrule with negative INTERVAL falls back to default 1`() {
        // The INTERVAL regex is \d+, which doesn't match the minus sign, so the interval
        // defaults to 1. RFC 5545 requires a positive INTERVAL, so 1 is a safe reading.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=DAILY;INTERVAL=-5",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(1, parsed.interval)
    }

    // ==================== Display Path: Boundary Ordinals ====================

    @Test
    fun `formatForDisplay renders 5th-weekday via ordinalNth template`() {
        // A 5th Monday exists only in months with five Mondays. Ordinals past the four named
        // ones use the "%dth" template.
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=5MO")
        assertTrue("expected '5th' in display, got: $display", display.contains("5th"))
    }

    @Test
    fun `formatForDisplay treats negative ordinal -2 as the nth template not last`() {
        // Only -1 maps to "last"; -2 (second-to-last) falls through to the nth template.
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=-2FR")
        assertFalse("'last' must not match -2: $display", display.contains("last Fri"))
    }

    // ==================== withUntil → parser symmetry ====================

    @Test
    fun `withUntil emits exactly what parser's UNTIL_FULL_REGEX matches`() {
        // The builder's pattern is "yyyyMMdd'T'HHmmss'Z'", so its output always ends in Z;
        // the parser's regex is \d{8}T\d{6}Z?. The parser reads back what the builder writes.
        val instant = Instant.parse("2027-07-04T13:00:00Z").toEpochMilli()
        val rrule = RruleBuilder.withUntil("FREQ=WEEKLY", instant)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.endCondition is EndCondition.Until)
        assertEquals(instant, (parsed.endCondition as EndCondition.Until).dateMillis)
    }

    @Test
    fun `parser drops UNTIL without Z suffix despite regex tolerance — pins gap`() {
        // The regex \d{8}T\d{6}Z? makes Z optional, but the formatter "yyyyMMdd'T'HHmmss'Z'"
        // has Z as a literal, so input without it throws and the catch gives Never. RFC 5545
        // §3.3.10 allows a date-time UNTIL without Z only for a floating DTSTART, so most
        // servers send Z, but a tolerant parser would accept this; pinned so a fix is deliberate.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;UNTIL=20260615T120000",
            DayOfWeek.MONDAY, 1, 1
        )
        assertNotNull(parsed.endCondition)
        assertEquals(
            "without-Z UNTIL currently falls to Never (regex matches, formatter rejects)",
            EndCondition.Never,
            parsed.endCondition
        )
    }

    // ==================== Date-value UNTIL adversarial ====================

    @Test
    fun `date-value UNTIL anchors to end-of-day UTC so the named day is included`() {
        // FREQ=WEEKLY;UNTIL=20260106 (no T) resolves to 2026-01-06 23:59:59 UTC, so the rule
        // includes occurrences on Jan 6.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;UNTIL=20260106",
            DayOfWeek.MONDAY, 1, 1
        )
        assertTrue(parsed.endCondition is EndCondition.Until)
        val until = parsed.endCondition as EndCondition.Until
        val expected = LocalDate.of(2026, 1, 6)
            .atTime(23, 59, 59)
            .atZone(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, until.dateMillis)
    }

    @Test
    fun `date-value UNTIL on leap-day Feb 29 parses as that exact day`() {
        val parsed = RruleBuilder.parseRrule(
            "FREQ=YEARLY;UNTIL=20280229",
            DayOfWeek.MONDAY, 1, 1
        )
        assertTrue(parsed.endCondition is EndCondition.Until)
        val until = parsed.endCondition as EndCondition.Until
        val parsedDate = Instant.ofEpochMilli(until.dateMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        assertEquals(LocalDate.of(2028, 2, 29), parsedDate)
    }

    @Test
    fun `date-value UNTIL on impossible day Feb 30 silently clamps to Feb 28 — pins SMART resolver`() {
        // The formatter's default ResolverStyle.SMART accepts Feb 30 by clamping to the last
        // day of February (Feb 28 in 2026), so the catch doesn't fire. Pinned so a switch to
        // STRICT, which would give Never, is deliberate.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=YEARLY;UNTIL=20260230",
            DayOfWeek.MONDAY, 1, 1
        )
        assertTrue(parsed.endCondition is EndCondition.Until)
        val resolvedDate = Instant.ofEpochMilli((parsed.endCondition as EndCondition.Until).dateMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 2, 28), resolvedDate)
    }

    @Test
    fun `datetime UNTIL takes priority over date-value UNTIL when both forms match`() {
        // The date regex (\d{8}) also matches the first 8 characters of a date-time, so the
        // parser tries it only when the date-time regex didn't match.
        val instant = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
        val parsed = RruleBuilder.parseRrule(
            "FREQ=DAILY;UNTIL=20260615T120000Z",
            DayOfWeek.MONDAY, 1, 1
        )
        assertTrue(parsed.endCondition is EndCondition.Until)
        // Should be the datetime, not midnight or end-of-day.
        assertEquals(instant, (parsed.endCondition as EndCondition.Until).dateMillis)
    }
}
