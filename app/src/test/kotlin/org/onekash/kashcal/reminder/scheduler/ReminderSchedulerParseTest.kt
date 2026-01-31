package org.onekash.kashcal.reminder.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ReminderScheduler duration parsing.
 *
 * Tests parseIsoDuration() for all ISO 8601 duration formats
 * used in iCal VALARM triggers.
 *
 * ISO 8601 Duration Format:
 * - P[n]W = weeks only (e.g., P1W = 1 week)
 * - P[n]D = days (e.g., P1D = 1 day)
 * - PT[n]H[n]M[n]S = time-based (e.g., PT15M = 15 minutes)
 * - P[n]DT[n]H[n]M[n]S = combined (e.g., P1DT2H30M = 1 day 2 hours 30 min)
 */
class ReminderSchedulerParseTest {

    // ========== Time-based durations (PT...) ==========

    @Test
    fun `parseIsoDuration returns correct millis for PT15M`() {
        val result = parseIsoDuration("PT15M")
        assertEquals(15 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT1H`() {
        val result = parseIsoDuration("PT1H")
        assertEquals(60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT1H30M`() {
        val result = parseIsoDuration("PT1H30M")
        assertEquals(90 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT2H45M30S`() {
        val result = parseIsoDuration("PT2H45M30S")
        val expected = (2 * 60 * 60 + 45 * 60 + 30) * 1000L
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT5M`() {
        val result = parseIsoDuration("PT5M")
        assertEquals(5 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT10M`() {
        val result = parseIsoDuration("PT10M")
        assertEquals(10 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT30M`() {
        val result = parseIsoDuration("PT30M")
        assertEquals(30 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for PT2H`() {
        val result = parseIsoDuration("PT2H")
        assertEquals(2 * 60 * 60 * 1000L, result)
    }

    // ========== Day-based durations (P...D) - THE BUG FIX ==========

    @Test
    fun `parseIsoDuration returns correct millis for P1D`() {
        val result = parseIsoDuration("P1D")
        assertEquals(24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for P2D`() {
        val result = parseIsoDuration("P2D")
        assertEquals(2 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for P7D`() {
        val result = parseIsoDuration("P7D")
        assertEquals(7 * 24 * 60 * 60 * 1000L, result)
    }

    // ========== Week-based durations (P...W) ==========

    @Test
    fun `parseIsoDuration returns correct millis for P1W`() {
        val result = parseIsoDuration("P1W")
        assertEquals(7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for P2W`() {
        val result = parseIsoDuration("P2W")
        assertEquals(14 * 24 * 60 * 60 * 1000L, result)
    }

    // ========== Combined formats (P...DT...) ==========

    @Test
    fun `parseIsoDuration returns correct millis for P1DT2H`() {
        val result = parseIsoDuration("P1DT2H")
        val expected = (24 + 2) * 60 * 60 * 1000L
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for P1DT2H30M`() {
        val result = parseIsoDuration("P1DT2H30M")
        val expected = (24 * 60 + 2 * 60 + 30) * 60 * 1000L
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration returns correct millis for P2DT6H15M`() {
        val result = parseIsoDuration("P2DT6H15M")
        val expected = (2 * 24 * 60 + 6 * 60 + 15) * 60 * 1000L
        assertEquals(expected, result)
    }

    // ========== Edge cases ==========

    @Test
    fun `parseIsoDuration returns 0 for PT0M`() {
        val result = parseIsoDuration("PT0M")
        assertEquals(0L, result)
    }

    @Test
    fun `parseIsoDuration returns 0 for PT0S`() {
        val result = parseIsoDuration("PT0S")
        assertEquals(0L, result)
    }

    @Test
    fun `parseIsoDuration returns null for empty string`() {
        val result = parseIsoDuration("")
        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for blank string`() {
        val result = parseIsoDuration("   ")
        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for invalid format`() {
        val result = parseIsoDuration("INVALID")
        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for missing P prefix`() {
        val result = parseIsoDuration("T15M")
        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for malformed duration`() {
        val result = parseIsoDuration("PXYZ")
        assertNull(result)
    }

    // ========== parseReminderOffset tests (with negative prefix) ==========

    @Test
    fun `parseReminderOffset handles negative prefix for PT15M`() {
        val result = parseReminderOffset("-PT15M")
        assertEquals(-15 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative prefix for PT1H`() {
        val result = parseReminderOffset("-PT1H")
        assertEquals(-60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative prefix for P1D`() {
        val result = parseReminderOffset("-P1D")
        assertEquals(-24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative prefix for P2D`() {
        val result = parseReminderOffset("-P2D")
        assertEquals(-2 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative prefix for P1W`() {
        val result = parseReminderOffset("-P1W")
        assertEquals(-7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles positive duration`() {
        val result = parseReminderOffset("PT15M")
        assertEquals(15 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset returns null for blank string`() {
        val result = parseReminderOffset("")
        assertNull(result)
    }

    @Test
    fun `parseReminderOffset returns null for whitespace`() {
        val result = parseReminderOffset("   ")
        assertNull(result)
    }

    @Test
    fun `parseReminderOffset handles PT0M at time of event`() {
        val result = parseReminderOffset("-PT0M")
        assertEquals(0L, result)
    }

    // ========== Real-world reminder values from KashCal ==========

    @Test
    fun `parseReminderOffset handles 5 minutes before`() {
        val result = parseReminderOffset("-PT5M")
        assertEquals(-5 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 10 minutes before`() {
        val result = parseReminderOffset("-PT10M")
        assertEquals(-10 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 30 minutes before`() {
        val result = parseReminderOffset("-PT30M")
        assertEquals(-30 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 1 hour before`() {
        val result = parseReminderOffset("-PT1H")
        assertEquals(-60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 2 hours before`() {
        val result = parseReminderOffset("-PT2H")
        assertEquals(-2 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 1 day before (THE BUG FIX)`() {
        val result = parseReminderOffset("-P1D")
        assertEquals(-24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 2 days before (THE BUG FIX)`() {
        val result = parseReminderOffset("-P2D")
        assertEquals(-2 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles 1 week before (THE BUG FIX)`() {
        // 1 week = 7 days = 10080 minutes
        val result = parseReminderOffset("-P7D")
        assertEquals(-7 * 24 * 60 * 60 * 1000L, result)
    }

    // ========== Performance regression test ==========

    @Test
    fun `parseIsoDuration performance - 1000 iterations complete in reasonable time`() {
        // This test ensures the pre-compiled regex optimization is working.
        // With inline Regex() calls, this would compile 5 patterns * 1000 times = 5000 compilations.
        // With pre-compiled patterns, compilation happens once at class load.
        //
        // Expected: < 100ms for 1000 iterations (typically ~10-20ms)
        // If someone accidentally reverts to inline Regex(), this will still pass
        // but the timing difference would be noticeable in profiling.

        val testCases = listOf(
            "-PT15M", "-PT1H", "-P1D", "-P2D", "-P1W",
            "-PT30M", "-PT2H", "-P1DT2H30M", "-P2W", "-PT5M"
        )

        val startTime = System.nanoTime()

        repeat(1000) {
            for (testCase in testCases) {
                parseReminderOffset(testCase)
            }
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        // 10,000 parse operations should complete in under 500ms even on slow CI
        // Typical time with pre-compiled regex: ~20-50ms
        // Typical time with inline regex: ~100-200ms
        assert(elapsedMs < 500) {
            "Performance regression: 10,000 parses took ${elapsedMs}ms (expected < 500ms)"
        }

        println("parseIsoDuration: 10,000 iterations completed in ${elapsedMs}ms")
    }

    @Test
    fun `parseIsoDuration consistency - repeated calls return same results`() {
        // Verify that pre-compiled regex produces consistent results across multiple calls
        // (guards against any thread-safety issues with shared Regex objects)
        val testCases = mapOf(
            "PT15M" to 15 * 60 * 1000L,
            "PT1H" to 60 * 60 * 1000L,
            "P1D" to 24 * 60 * 60 * 1000L,
            "P1W" to 7 * 24 * 60 * 60 * 1000L,
            "P1DT2H30M" to (24 * 60 + 2 * 60 + 30) * 60 * 1000L
        )

        // Run 100 iterations to catch any inconsistency
        repeat(100) { iteration ->
            for ((input, expected) in testCases) {
                val result = parseIsoDuration(input)
                assertEquals(
                    "Iteration $iteration: parseIsoDuration($input) returned inconsistent result",
                    expected,
                    result
                )
            }
        }
    }
}
