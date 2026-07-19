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
 * Round-trip and adversarial conformance tests for [RruleBuilder].
 *
 * Sister files cover unit behavior ([RruleBuilderTest], [RruleBuilderRfc5545Test])
 * and bad-input edges ([RruleBuilderAdversarialTest]). This file targets the
 * gaps those don't cover:
 *
 * 1. **Build → parse parity**: every public builder output must re-parse
 *    losslessly into the structural state that produced it. Catches drift
 *    between the two paths if either evolves.
 * 2. **CalDAV server fixtures**: rules verbatim from real iCloud / Nextcloud /
 *    Radicale / Baikal / Stalwart / SoGo / Zoho integration test bodies, so
 *    the parser is exercised against shapes the wire actually delivers.
 * 3. **Token-order and shape robustness**: parser uses `Regex.find` and
 *    `String.contains`, so it should be order-independent — assert that.
 * 4. **Documented gaps**: WKST is build-only (parser doesn't extract);
 *    parser is case-sensitive; conflicting BYMONTHDAY+BYDAY uses NthWeekday
 *    branch first. Pinning these as tests keeps a future refactor honest.
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
        // Single days, common pairs, weekday set, all-7
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

    // ==================== Monthly nth-weekday: exact BYDAY string over the bounded space ====================

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
        // Bounded, fully enumerable space: {1,2,3,4,-1} x 7 weekdays. Exhaustive
        // enumeration beats random fuzzing here — it guarantees coverage. Asserts
        // the EXACT BYDAY token (e.g. -1 -> "-1FR", 2 -> "2MO") and that parseRrule
        // returns the identical NthWeekday.
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
        // The CUSTOM(month) path threads interval. Assert the builder's REAL token
        // order (FREQ, then INTERVAL, then BYDAY) rather than guessing it.
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
        // A "last Friday" rule opened on an event whose
        // start date is Saturday the 18th (which is the 3rd Saturday) must parse
        // to NthWeekday(-1, FRIDAY) — the rule wins, NOT the start-date-derived
        // SATURDAY / ordinal 3.
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
        // Mirror for the by-date branch: BYMONTHDAY=9 must win over the start
        // date's day-of-month (18).
        val parsed = RruleBuilder.parseRrule(
            "FREQ=MONTHLY;BYMONTHDAY=9",
            defaultWeekday = DayOfWeek.SATURDAY,
            defaultDayOfMonth = 18,
            defaultOrdinal = 3,
        )
        assertEquals(MonthlyPattern.SameDay(9), parsed.monthlyPattern)
    }

    // ==================== Adversarial: 5FR (real but rare) round-trips uncoerced ====================

    @Test
    fun `monthly BYDAY 5FR round-trips verbatim and is not coerced to Last or 4th`() {
        // A "5th Friday" rule is valid but rare. The picker offers only 1st-4th +
        // Last, so it can't select it — but importing one must NOT silently coerce
        // it to -1FR or 4FR. parse->build preserves BYDAY=5FR.
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
        // Feb 29 2028 (leap), end-of-year, far future. All converted via the same
        // formatter the parser uses, so they must round-trip exactly.
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
    // Verbatim shapes seen on the wire from server integration test bodies
    // (Stalwart/SoGo/Zoho integration tests). If a parser change starts
    // dropping data on these, sync regresses.

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
        // The flagship round-trip: this is the rule shape that motivated the
        // INTERVAL>1 fix. Server emits it, app parses, app re-emits, app saves.
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
        // Common server-emitted form: "first Monday every two months"
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
        // RFC 5545 §3.3.10 ABNF allows any order for rule parts. Parser uses
        // contains/find, so position doesn't matter — pin that.
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
        // RFC 5545 §3.8.8 allows X- experimental tokens. Our parser's regex
        // approach naturally ignores them — confirm.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;X-MICROSOFT-RSCID=foo;BYDAY=TU",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.WEEKLY, parsed.frequency)
        assertEquals(setOf(DayOfWeek.TUESDAY), parsed.weekdays)
    }

    // ==================== Documented Behavioral Gaps ====================
    // These tests pin current behavior; if a future refactor changes them,
    // the change becomes visible at review time.

    @Test
    fun `parser is case-sensitive — lowercase freq is not recognized`() {
        // Lowercase tokens fall through to NONE because contains() is
        // case-sensitive. Real-world CalDAV servers always emit uppercase
        // per RFC, so this is acceptable; pin it so a future Locale-aware
        // change is intentional.
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
        // RFC 5545 §3.3.10 says WKST has no effect on weekly rules with
        // interval=1; the builder's gate suppresses it. So a parse of the
        // built rule sees no WKST token and reports null. Pin the contract:
        // ParsedRecurrence.wkst null doesn't necessarily mean "device wkst"
        // — it can also mean "WKST has no effect here, builder elided it".
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
        // RFC permits both, semantics being intersection. Picker can't model
        // both at once (MonthlyPattern is a sealed choice), so the parser's
        // when{} prefers BYDAY-ordinal. Pin the priority so a refactor
        // doesn't silently flip it.
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
        // Server emitting two FREQ tokens is malformed; parser's when{} is
        // ordered DAILY/WEEKLY/MONTHLY/YEARLY, so first match wins regardless
        // of order in the rule string. Pinned for awareness.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=WEEKLY;FREQ=DAILY",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(RecurrenceFrequency.DAILY, parsed.frequency)
    }

    @Test
    fun `parseRrule with negative INTERVAL falls back to default 1`() {
        // INTERVAL_REGEX is \d+ which doesn't match the minus sign, so the
        // regex misses entirely and interval defaults to 1. RFC says
        // INTERVAL must be ≥1, so accepting this gracefully is correct.
        val parsed = RruleBuilder.parseRrule(
            "FREQ=DAILY;INTERVAL=-5",
            DayOfWeek.MONDAY, 1, 1
        )
        assertEquals(1, parsed.interval)
    }

    // ==================== Display Path: Boundary Ordinals ====================

    @Test
    fun `formatForDisplay renders 5th-weekday via ordinalNth template`() {
        // 5th Monday only exists in months with 5 Mondays. RFC permits 1..5.
        // Display path's `else` branch uses the "%dth" template.
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=5MO")
        assertTrue("expected '5th' in display, got: $display", display.contains("5th"))
    }

    @Test
    fun `formatForDisplay treats negative ordinal -2 as the nth template not last`() {
        // Only -1 is mapped to "last"; -2 (second-to-last) falls through to
        // the nth template. Pin the boundary.
        val display = RruleBuilder.formatForDisplay("FREQ=MONTHLY;BYDAY=-2FR")
        assertFalse("'last' must not match -2: $display", display.contains("last Fri"))
    }

    // ==================== withUntil → parser symmetry ====================

    @Test
    fun `withUntil emits exactly what parser's UNTIL_FULL_REGEX matches`() {
        // Builder uses pattern "yyyyMMdd'T'HHmmss'Z'"; parser regex is
        // \d{8}T\d{6}Z?. The Z is mandatory in the builder output. Verify
        // the parser accepts what the builder writes for an arbitrary instant.
        val instant = Instant.parse("2027-07-04T13:00:00Z").toEpochMilli()
        val rrule = RruleBuilder.withUntil("FREQ=WEEKLY", instant)
        val parsed = RruleBuilder.parseRrule(rrule, DayOfWeek.MONDAY, 1, 1)
        assertTrue(parsed.endCondition is EndCondition.Until)
        assertEquals(instant, (parsed.endCondition as EndCondition.Until).dateMillis)
    }

    @Test
    fun `parser drops UNTIL without Z suffix despite regex tolerance — pins gap`() {
        // UNTIL_FULL_REGEX is \d{8}T\d{6}Z? — Z is optional in the *regex*,
        // so the rule looks parseable. But the LocalDateTime formatter is
        // "yyyyMMdd'T'HHmmss'Z'" with Z as a literal, so without-Z input
        // throws and the catch yields Never. RFC 5545 mandates Z for UTC
        // datetime, so most servers comply, but a tolerant parser would
        // accept this. Pinning the gap so a future fix is intentional.
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
        // Recently-added: FREQ=WEEKLY;UNTIL=20260106 (no T). Should resolve to
        // 2026-01-06 23:59:59 UTC so the rule includes occurrences ON Jan 6.
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
        // Java's default DateTimeFormatter uses ResolverStyle.SMART, which
        // accepts Feb 30 in a non-leap year by clamping to the last valid
        // day of February (Feb 28 in 2026). The catch{} only fires for
        // STRICT mode. Document the behavior so a switch to STRICT (which
        // would fall back to Never) is intentional.
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
        // The date-value regex is broader (\d{8}) and would also match the
        // first 8 chars of a datetime string. The parser short-circuits the
        // date branch when the datetime branch matched. Pin that ordering.
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
