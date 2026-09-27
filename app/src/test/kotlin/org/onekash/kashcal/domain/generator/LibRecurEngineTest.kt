package org.onekash.kashcal.domain.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke tests for [LibRecurEngine.expandToTimestamps], the test-only lib-recur oracle: basic
 * expansion, empty and malformed rules, EXDATE and RDATE, and quirks (a), (b), (d) and (e).
 * The parity tests compare it against the production [IcalDavRRuleEngine].
 */
class LibRecurEngineTest {

    // 2024-01-01 00:00:00 UTC
    private val baseStart = 1704067200000L
    private val oneYearMs = 365L * 24 * 3600 * 1000

    @Test
    fun `daily for 5 occurrences produces 5 timestamps`() {
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        assertEquals(5, result.size)
        // 5 consecutive days
        for (i in 0 until 5) {
            assertEquals(baseStart + i * 86400L * 1000, result[i])
        }
    }

    @Test
    fun `weekly BYDAY=MO,WE,FR for 6 occurrences`() {
        // 2024-01-01 was a Monday
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=6",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + 30L * 86400 * 1000,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        assertEquals(6, result.size)
    }

    @Test
    fun `empty rrule returns empty list`() {
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `malformed rrule returns empty list without throwing`() {
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "GARBAGE",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `COUNT+UNTIL both present — CRITICAL quirk (b) — UNTIL stripped, COUNT wins`() {
        // lib-recur rejects a rule with both, which would expand to nothing, so the engine
        // strips UNTIL when COUNT is present.
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=3;UNTIL=20241231T000000Z",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        // 3 from COUNT, not 0.
        assertEquals(3, result.size)
    }

    @Test
    fun `EXDATE removes a specific occurrence`() {
        val day2 = baseStart + 86400L * 1000
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = day2.toString() // milliseconds format
        )
        assertEquals(4, result.size)
        assertTrue(result.none { it == day2 })
    }

    @Test
    fun `RDATE adds an occurrence not in RRULE`() {
        val extra = baseStart + 10L * 86400 * 1000 // day 10
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = extra.toString(),
            exdateStrings = null
        )
        assertEquals(6, result.size)
        assertTrue(result.contains(extra))
    }

    @Test
    fun `all-day event — CRITICAL quirk (a) — uses UTC regardless of timezone`() {
        // All-day events are stored at UTC midnight; honoring a zone west of UTC would shift
        // dates back a day.
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            rangeEndMs = baseStart + oneYearMs,
            timezone = "America/Los_Angeles", // -08:00, but must be ignored for all-day
            isAllDay = true,
            rdateStrings = null,
            exdateStrings = null
        )
        assertEquals(3, result.size)
        // The first occurrence is at UTC midnight, not shifted by the Los Angeles offset.
        assertEquals(baseStart, result[0])
    }

    @Test
    fun `MAX_ITERATIONS safety — CRITICAL quirk (e) — unbounded rule is capped`() {
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=SECONDLY", // No COUNT or UNTIL: unbounded
            dtstartMs = baseStart,
            rangeStartMs = baseStart,
            // A year of SECONDLY is over 31M iterations uncapped.
            rangeEndMs = baseStart + oneYearMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        // Must not hang, and returns at most MAX_ITERATIONS (10,000) results.
        assertTrue("Result size should be bounded by MAX_ITERATIONS", result.size <= 10_000)
    }

    @Test
    fun `FastForwarded boundary — CRITICAL quirk (d) — range far after DTSTART`() {
        // The range starts 2 years after DTSTART. Without FastForwarded, lib-recur would
        // iterate every daily occurrence up to the range start; with it, iteration starts 30
        // days before. Asserts only the count and bounds in the range, and no crash.
        val rangeStart = baseStart + 2L * 365 * 86400 * 1000
        val rangeEnd = rangeStart + 10L * 86400 * 1000
        val result = LibRecurEngine.expandToTimestamps(
            rrule = "FREQ=DAILY",
            dtstartMs = baseStart,
            rangeStartMs = rangeStart,
            rangeEndMs = rangeEnd,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null
        )
        assertEquals(10, result.size)
        // The first occurrence is at or after rangeStart.
        assertTrue(result.first() >= rangeStart)
        // The last occurrence is before rangeEnd.
        assertTrue(result.last() < rangeEnd)
    }
}
