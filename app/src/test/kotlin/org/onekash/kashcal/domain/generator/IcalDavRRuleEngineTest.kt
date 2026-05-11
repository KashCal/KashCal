package org.onekash.kashcal.domain.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Smoke-level tests for the new ical4j-backed expansion engine. Contract
 * match with [LibRecurEngine.expandToTimestamps] is the primary requirement;
 * real coverage lives at the OccurrenceGenerator layer (260 tests) and in
 * the parity harness.
 */
class IcalDavRRuleEngineTest {

    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    private fun et(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ETZ).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `null rrule returns empty`() {
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = null,
            dtstartMs = et(2025, 1, 1),
            rangeStartMs = et(2025, 1, 1),
            rangeEndMs = et(2025, 2, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `blank rrule returns empty`() {
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "",
            dtstartMs = et(2025, 1, 1),
            rangeStartMs = et(2025, 1, 1),
            rangeEndMs = et(2025, 2, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `FREQ=DAILY COUNT=3 returns 3 timestamps at dtstart and daily offsets`() {
        val dtstart = et(2025, 5, 1, 10)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = dtstart,
            rangeStartMs = et(2025, 5, 1, 0),
            rangeEndMs = et(2025, 5, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(3, result.size)
        assertEquals(dtstart, result[0])
        assertEquals(dtstart + 86400_000L, result[1])
        assertEquals(dtstart + 2 * 86400_000L, result[2])
    }

    @Test
    fun `FREQ=WEEKLY BYDAY=MO COUNT=3 returns Mondays`() {
        val mondayJan6 = et(2025, 1, 6, 9)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
            dtstartMs = mondayJan6,
            rangeStartMs = et(2025, 1, 1),
            rangeEndMs = et(2025, 2, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(3, result.size)
        assertEquals(mondayJan6, result[0])
        assertEquals(mondayJan6 + 7 * 86400_000L, result[1])
        assertEquals(mondayJan6 + 14 * 86400_000L, result[2])
    }

    @Test
    fun `malformed RRULE returns empty, not exception`() {
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "THIS IS NOT AN RRULE",
            dtstartMs = et(2025, 5, 1),
            rangeStartMs = et(2025, 5, 1),
            rangeEndMs = et(2025, 6, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertTrue("malformed rule should degrade to empty expansion", result.isEmpty())
    }

    @Test
    fun `filter boundary — DTSTART before rangeStart is excluded`() {
        // DTSTART in January; rangeStart in March. Filter-to-range should
        // drop DTSTART itself but keep the March+ occurrences.
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=MONTHLY;COUNT=5",
            dtstartMs = et(2025, 1, 15, 10),
            rangeStartMs = et(2025, 3, 1),
            rangeEndMs = et(2025, 6, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertTrue("no timestamp before range start",
            result.all { it >= et(2025, 3, 1) })
        assertTrue("no timestamp at or after range end",
            result.all { it < et(2025, 6, 1) })
    }

    @Test
    fun `filter boundary — occurrence at or after rangeEnd is excluded`() {
        // Daily with COUNT=10 starting Jan 1, rangeEnd Jan 6 exclusive.
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=10",
            dtstartMs = et(2025, 1, 1, 10),
            rangeStartMs = et(2025, 1, 1),
            rangeEndMs = et(2025, 1, 6),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals("rangeEnd is exclusive: Jan 6 must be dropped", 5, result.size)
    }

    @Test
    fun `QUIRK b smoke — COUNT+UNTIL returns COUNT occurrences`() {
        val dtstart = et(2025, 5, 1, 10)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=3;UNTIL=20000101T000000Z",
            dtstartMs = dtstart,
            rangeStartMs = et(2025, 5, 1, 0),
            rangeEndMs = et(2025, 5, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals("quirk b: UNTIL stripped, COUNT wins", 3, result.size)
    }

    @Test
    fun `QUIRK g smoke — DATE-format EXDATE excludes the timed occurrence`() {
        // 5 daily, EXDATE 20250703 → should yield 4 timestamps (July 3 excluded).
        val dtstart = et(2025, 7, 1, 10)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = dtstart,
            rangeStartMs = et(2025, 7, 1, 0),
            rangeEndMs = et(2025, 7, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = "20250703",
        )
        assertEquals(4, result.size)
        assertTrue("July 3 must be absent after quirk-g exclusion",
            result.none { it == et(2025, 7, 3, 10) })
    }

    @Test
    fun `BYHOUR smoke — FREQ=DAILY BYHOUR=9,12,15 COUNT=6 returns 3 per day`() {
        // Validates that the icaldav-core BYHOUR fix (927cf8b5) is exercised
        // via the production engine.
        val dtstart = et(2025, 1, 1, 9)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;BYHOUR=9,12,15;COUNT=6",
            dtstartMs = dtstart,
            rangeStartMs = et(2025, 1, 1),
            rangeEndMs = et(2025, 1, 10),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals("BYHOUR regression guard (927cf8b5): 3-per-day × 2 days = 6", 6, result.size)
        val hours = result.map { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ETZ).hour }
        assertEquals(listOf(9, 12, 15, 9, 12, 15), hours)
    }

    // -------------------------------------------------------------
    // Issue #214 — fortnightly Sun/Tue/Thu starting on Sunday
    // https://github.com/KashCal/KashCal/issues/214
    //
    // User intent: bi-weekly recurring event on Sun/Tue/Thu, starting on
    // Sunday. Expected pattern is Sun/Tue/Thu in week N, skip week N+1,
    // Sun/Tue/Thu in week N+2.
    //
    // Bug surface: when RruleBuilder emits no WKST (current behavior), ical4j
    // defaults to WKST=MO per RFC 5545 §3.3.10. With INTERVAL=2 and DTSTART
    // on Sunday, the Sunday's MO-anchored week ends on that Sunday, so week 1
    // yields only Sun (Tue/Thu fall before DTSTART). Then INTERVAL=2 skips
    // week 2 entirely. Week 3 yields Tue/Thu/Sun together — overlapping the
    // user's second (Mon/Wed) fortnight on the same iso week.
    //
    // Test pair: lock both behaviors in.
    //   (a) WKST omitted → currently produces the buggy pattern. Asserting
    //       the buggy timestamps documents the engine's contract; the
    //       follow-up fix lives upstream in RruleBuilder/RecurrenceRule.
    //   (b) WKST=SU explicit → produces the user's expected pattern. This
    //       is the ground truth the fix should match.
    //
    // Anchor: Sun May 4 2025, America/New_York. EDT throughout the window.
    // -------------------------------------------------------------

    @Test
    fun `issue 214 — biweekly SuTuTh on Sunday with WKST=SU yields user's expected pattern`() {
        // Ground truth: with WKST=SU, week 1 = Sun May 4 .. Sat May 10.
        // BYDAY hits Sun 4, Tue 6, Thu 8. Skip week 2. Week 3 = Sun May 18 ..
        // Sat May 24: Sun 18, Tue 20, Thu 22.
        val sunMay4 = et(2025, 5, 4, 9)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU,TH;WKST=SU;COUNT=6",
            dtstartMs = sunMay4,
            rangeStartMs = et(2025, 5, 1),
            rangeEndMs = et(2025, 6, 1),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(
            listOf(
                et(2025, 5, 4, 9),  // Sun
                et(2025, 5, 6, 9),  // Tue
                et(2025, 5, 8, 9),  // Thu
                et(2025, 5, 18, 9), // Sun
                et(2025, 5, 20, 9), // Tue
                et(2025, 5, 22, 9), // Thu
            ),
            result,
        )
    }

    @Test
    fun `issue 214 — biweekly SuTuTh on Sunday with WKST default (MO) skips same-week TuTh — buggy contract`() {
        // Documents the buggy ical4j behavior when WKST is omitted (defaults
        // to MO). With WKST=MO, week 1 = Mon Apr 28 .. Sun May 4. BYDAY
        // candidates Tue Apr 29 + Thu May 1 + Sun May 4, but Apr 29 / May 1
        // are < DTSTART so they're filtered out. Skip week 2. Week 3 = Mon
        // May 12 .. Sun May 18: Tue May 13, Thu May 15, Sun May 18. Skip
        // week 4. Week 5 = Mon May 26 .. Sun Jun 1: Tue May 27, Thu May 29,
        // Sun Jun 1.
        //
        // This is what the user sees in production today. The fix is to make
        // RruleBuilder emit WKST=SU (or first-day-of-week from locale) so
        // SU groups with the surrounding TU/TH/etc. — NOT to change this
        // engine. If this test starts failing because the engine default
        // changed, the upstream fix needs to land alongside it.
        val sunMay4 = et(2025, 5, 4, 9)
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU,TH;COUNT=7",
            dtstartMs = sunMay4,
            rangeStartMs = et(2025, 5, 1),
            rangeEndMs = et(2025, 6, 5),
            timezone = "America/New_York",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals(
            listOf(
                et(2025, 5, 4, 9),  // Sun (week 1 — alone, Tue/Thu fell before DTSTART)
                et(2025, 5, 13, 9), // Tue (week 3)
                et(2025, 5, 15, 9), // Thu (week 3)
                et(2025, 5, 18, 9), // Sun (week 3)
                et(2025, 5, 27, 9), // Tue (week 5)
                et(2025, 5, 29, 9), // Thu (week 5)
                et(2025, 6, 1, 9),  // Sun (week 5)
            ),
            result,
        )
    }

    @Test
    fun `all-day WEEKLY BYDAY=MO stays on Monday regardless of timezone`() {
        // Validates quirk (a) handling — all-day forces UTC.
        val result = IcalDavRRuleEngine.expandToTimestamps(
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
            dtstartMs = utcMidnight(2025, 1, 6), // Monday
            rangeStartMs = utcMidnight(2025, 1, 1),
            rangeEndMs = utcMidnight(2025, 2, 1),
            timezone = "America/Chicago", // non-UTC, would shift date without quirk a
            isAllDay = true,
            rdateStrings = null,
            exdateStrings = null,
        )
        assertEquals("quirk (a): all-day must ignore non-UTC TZID", 3, result.size)
        assertEquals(utcMidnight(2025, 1, 6), result[0])
        assertEquals(utcMidnight(2025, 1, 13), result[1])
        assertEquals(utcMidnight(2025, 1, 20), result[2])
    }
}
