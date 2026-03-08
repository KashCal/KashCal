package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Tests for RruleUtils RRULE UNTIL manipulation.
 *
 * Covers:
 * - Adding UNTIL to simple RRULE
 * - Replacing existing UNTIL
 * - Replacing COUNT with UNTIL
 * - DateTime format for timed events
 * - Date-only format for all-day events (RFC 5545 §3.3.10)
 * - COUNT + all-day combinatorial edge case
 */
class RruleUtilsTest {

    // Fixed timestamp: 2026-01-15 10:00:00 UTC
    private val jan15_10am_utc: Long = run {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.JANUARY, 15, 10, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    @Test
    fun `addUntilToRrule adds UNTIL to simple RRULE`() {
        val result = RruleUtils.addUntilToRrule("FREQ=WEEKLY", jan15_10am_utc)
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `addUntilToRrule replaces existing UNTIL`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;UNTIL=20250101T000000Z", jan15_10am_utc
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `addUntilToRrule replaces COUNT with UNTIL`() {
        val result = RruleUtils.addUntilToRrule("FREQ=WEEKLY;COUNT=10", jan15_10am_utc)
        assertEquals("FREQ=WEEKLY;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `formatUntilDate formats datetime for timed events`() {
        val result = RruleUtils.formatUntilDate(jan15_10am_utc, isAllDay = false)
        assertEquals("20260115T100000Z", result)
    }

    @Test
    fun `formatUntilDate formats date-only for all-day events`() {
        val result = RruleUtils.formatUntilDate(jan15_10am_utc, isAllDay = true)
        assertEquals("20260115", result)
    }

    @Test
    fun `addUntilToRrule uses date-only format for all-day`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;BYDAY=MO", jan15_10am_utc, isAllDay = true
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260115", result)
    }

    @Test
    fun `addUntilToRrule replaces COUNT with date-only UNTIL for all-day`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;COUNT=10", jan15_10am_utc, isAllDay = true
        )
        assertEquals("FREQ=WEEKLY;UNTIL=20260115", result)
    }

    @Test
    fun `addUntilToRrule handles UNTIL with BYDAY`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20250601T000000Z", jan15_10am_utc
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20260115T100000Z", result)
    }

    @Test
    fun `addUntilToRrule handles COUNT in middle of RRULE`() {
        val result = RruleUtils.addUntilToRrule(
            "FREQ=DAILY;COUNT=5;INTERVAL=2", jan15_10am_utc
        )
        // COUNT removed, UNTIL appended
        assertTrue(result.contains("UNTIL=20260115T100000Z"))
        assertFalse(result.contains("COUNT"))
    }
}
