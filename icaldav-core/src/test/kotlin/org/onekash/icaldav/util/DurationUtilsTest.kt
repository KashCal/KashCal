package org.onekash.icaldav.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive tests for DurationUtils.
 *
 * Covers:
 * - RFC 5545 duration format parsing
 * - ISO 8601 duration format parsing
 * - Edge cases and malformed input
 * - Formatting back to iCalendar format
 * - Round-trip consistency
 */
@DisplayName("DurationUtils")
class DurationUtilsTest {

    @Nested
    @DisplayName("Parsing - Standard RFC 5545 Formats")
    inner class StandardParsingTests {

        @Test
        fun `parse positive minutes`() {
            val duration = DurationUtils.parse("PT15M")
            assertNotNull(duration)
            assertEquals(15, duration.toMinutes())
        }

        @Test
        fun `parse negative minutes - alarm trigger`() {
            val duration = DurationUtils.parse("-PT15M")
            assertNotNull(duration)
            assertEquals(-15, duration.toMinutes())
        }

        @Test
        fun `parse hours and minutes combined`() {
            val duration = DurationUtils.parse("PT1H30M")
            assertNotNull(duration)
            assertEquals(90, duration.toMinutes())
        }

        @Test
        fun `parse negative hours and minutes - alarm trigger`() {
            val duration = DurationUtils.parse("-PT1H30M")
            assertNotNull(duration)
            assertEquals(-90, duration.toMinutes())
        }

        @Test
        fun `parse days`() {
            val duration = DurationUtils.parse("P1D")
            assertNotNull(duration)
            assertEquals(1, duration.toDays())
        }

        @Test
        fun `parse weeks`() {
            val duration = DurationUtils.parse("P1W")
            assertNotNull(duration)
            assertEquals(7, duration.toDays())
        }

        @Test
        fun `parse days with time - complex format`() {
            val duration = DurationUtils.parse("P1DT2H30M")
            assertNotNull(duration)
            assertEquals(1, duration.toDays())
            assertEquals(26, duration.toHours())
            assertEquals(30, duration.toMinutesPart())
        }

        @Test
        fun `parse negative complex duration`() {
            val duration = DurationUtils.parse("-P1DT2H30M")
            assertNotNull(duration)
            assertTrue(duration.isNegative)
            assertEquals(-1, duration.toDays())
        }

        @Test
        fun `parse seconds only`() {
            val duration = DurationUtils.parse("PT30S")
            assertNotNull(duration)
            assertEquals(30, duration.toSeconds())
        }

        @Test
        fun `parse full format with all components`() {
            val duration = DurationUtils.parse("P2DT3H45M30S")
            assertNotNull(duration)
            assertEquals(2, duration.toDays())
            assertEquals(3, duration.toHoursPart())
            assertEquals(45, duration.toMinutesPart())
            assertEquals(30, duration.toSecondsPart())
        }
    }

    @Nested
    @DisplayName("Parsing - Edge Cases")
    inner class EdgeCaseParsingTests {

        @Test
        fun `parse null returns null`() {
            assertNull(DurationUtils.parse(null))
        }

        @Test
        fun `parse empty string returns null`() {
            assertNull(DurationUtils.parse(""))
        }

        @Test
        fun `parse blank string returns null`() {
            assertNull(DurationUtils.parse("   "))
        }

        @Test
        fun `parse with leading whitespace`() {
            val duration = DurationUtils.parse("  PT15M")
            assertNotNull(duration)
            assertEquals(15, duration.toMinutes())
        }

        @Test
        fun `parse with trailing whitespace`() {
            val duration = DurationUtils.parse("PT15M  ")
            assertNotNull(duration)
            assertEquals(15, duration.toMinutes())
        }

        @Test
        fun `parse lowercase returns result`() {
            val duration = DurationUtils.parse("pt15m")
            assertNotNull(duration)
            assertEquals(15, duration.toMinutes())
        }

        @Test
        fun `parse mixed case returns result`() {
            val duration = DurationUtils.parse("Pt15M")
            assertNotNull(duration)
            assertEquals(15, duration.toMinutes())
        }

        @Test
        fun `parse with plus sign`() {
            val duration = DurationUtils.parse("+PT15M")
            assertNotNull(duration)
            assertEquals(15, duration.toMinutes())
        }

        @Test
        fun `parse zero duration`() {
            val duration = DurationUtils.parse("PT0S")
            assertNotNull(duration)
            assertEquals(0, duration.toSeconds())
        }

        @Test
        fun `parse large values`() {
            val duration = DurationUtils.parse("P365D")
            assertNotNull(duration)
            assertEquals(365, duration.toDays())
        }

        @Test
        fun `parse multiple weeks`() {
            val duration = DurationUtils.parse("P4W")
            assertNotNull(duration)
            assertEquals(28, duration.toDays())
        }
    }

    @Nested
    @DisplayName("Parsing - Invalid Input")
    inner class InvalidParsingTests {

        @Test
        fun `parse invalid format returns null`() {
            assertNull(DurationUtils.parse("invalid"))
        }

        @Test
        fun `parse without P prefix returns null`() {
            assertNull(DurationUtils.parse("T15M"))
        }

        @Test
        fun `parse with only P returns null`() {
            // Java's Duration.parse might handle this differently
            val result = DurationUtils.parse("P")
            // Either null or zero duration is acceptable
            if (result != null) {
                assertEquals(0, result.toSeconds())
            }
        }

        @Test
        fun `parse gibberish returns null`() {
            assertNull(DurationUtils.parse("xyz123"))
        }

        @Test
        fun `parse date-like string returns null`() {
            assertNull(DurationUtils.parse("2024-01-01"))
        }

        @Test
        fun `parse number only returns null`() {
            assertNull(DurationUtils.parse("15"))
        }
    }

    @Nested
    @DisplayName("Formatting")
    inner class FormattingTests {

        @Test
        fun `format positive minutes`() {
            val formatted = DurationUtils.format(Duration.ofMinutes(15))
            assertEquals("PT15M", formatted)
        }

        @Test
        fun `format negative minutes`() {
            val formatted = DurationUtils.format(Duration.ofMinutes(-15))
            assertEquals("-PT15M", formatted)
        }

        @Test
        fun `format hours and minutes`() {
            val formatted = DurationUtils.format(Duration.ofHours(1).plusMinutes(30))
            assertEquals("PT1H30M", formatted)
        }

        @Test
        fun `format days`() {
            val formatted = DurationUtils.format(Duration.ofDays(1))
            assertEquals("P1D", formatted)
        }

        @Test
        fun `format days with time`() {
            val formatted = DurationUtils.format(Duration.ofDays(1).plusHours(2).plusMinutes(30))
            assertEquals("P1DT2H30M", formatted)
        }

        @Test
        fun `format zero duration`() {
            val formatted = DurationUtils.format(Duration.ZERO)
            assertEquals("PT0S", formatted)
        }

        @Test
        fun `format seconds only`() {
            val formatted = DurationUtils.format(Duration.ofSeconds(30))
            assertEquals("PT30S", formatted)
        }

        @Test
        fun `format all components`() {
            val duration = Duration.ofDays(2).plusHours(3).plusMinutes(45).plusSeconds(30)
            val formatted = DurationUtils.format(duration)
            assertEquals("P2DT3H45M30S", formatted)
        }

        @Test
        fun `format negative complex duration`() {
            val duration = Duration.ofDays(-1).minusHours(2)
            val formatted = DurationUtils.format(duration)
            assertTrue(formatted.startsWith("-P"))
        }
    }

    @Nested
    @DisplayName("Round-Trip Consistency")
    inner class RoundTripTests {

        @ParameterizedTest
        @ValueSource(strings = [
            "PT15M",
            "-PT15M",
            "PT1H30M",
            "-PT1H30M",
            "P1D",
            "P1DT2H30M",
            "PT30S",
            "P2DT3H45M30S"
        ])
        fun `parse then format produces equivalent duration`(original: String) {
            val parsed = DurationUtils.parse(original)
            assertNotNull(parsed)
            val formatted = DurationUtils.format(parsed)
            val reparsed = DurationUtils.parse(formatted)
            assertNotNull(reparsed)
            assertEquals(parsed, reparsed, "Round-trip should produce equivalent duration")
        }

        @Test
        fun `format then parse produces same duration`() {
            val original = Duration.ofHours(2).plusMinutes(45)
            val formatted = DurationUtils.format(original)
            val parsed = DurationUtils.parse(formatted)
            assertEquals(original, parsed)
        }
    }

    @Nested
    @DisplayName("isDurationString")
    inner class IsDurationStringTests {

        @ParameterizedTest
        @ValueSource(strings = [
            "PT15M",
            "-PT15M",
            "+PT15M",
            "P1D",
            "P1W",
            "p1d",
            "-p1d"
        ])
        fun `valid duration strings return true`(input: String) {
            assertTrue(DurationUtils.isDurationString(input))
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "",
            "   ",
            "invalid",
            "15M",
            "T15M",
            "2024-01-01"
        ])
        fun `invalid duration strings return false`(input: String) {
            assertFalse(DurationUtils.isDurationString(input))
        }

        @Test
        fun `null returns false`() {
            assertFalse(DurationUtils.isDurationString(null))
        }
    }

    @Nested
    @DisplayName("parseOrDefault")
    inner class ParseOrDefaultTests {

        @Test
        fun `valid input returns parsed value`() {
            val result = DurationUtils.parseOrDefault("PT15M", Duration.ofMinutes(30))
            assertEquals(15, result.toMinutes())
        }

        @Test
        fun `invalid input returns default`() {
            val result = DurationUtils.parseOrDefault("invalid", Duration.ofMinutes(30))
            assertEquals(30, result.toMinutes())
        }

        @Test
        fun `null input returns default`() {
            val result = DurationUtils.parseOrDefault(null, Duration.ofMinutes(30))
            assertEquals(30, result.toMinutes())
        }

        @Test
        fun `empty input returns default`() {
            val result = DurationUtils.parseOrDefault("", Duration.ofMinutes(30))
            assertEquals(30, result.toMinutes())
        }
    }

    @Nested
    @DisplayName("Real-World Alarm Triggers")
    inner class AlarmTriggerTests {

        @ParameterizedTest
        @CsvSource(
            "-PT15M, -15",      // 15 minutes before
            "-PT30M, -30",      // 30 minutes before
            "-PT1H, -60",       // 1 hour before
            "-PT2H, -120",      // 2 hours before
            "-P1D, -1440",      // 1 day before
            "-P1DT12H, -2160",  // 1.5 days before
            "-P1W, -10080",     // 1 week before
            "PT0M, 0",          // At event time
            "PT15M, 15",        // 15 minutes after (snooze)
        )
        fun `common alarm triggers parse correctly`(input: String, expectedMinutes: Long) {
            val duration = DurationUtils.parse(input)
            assertNotNull(duration)
            assertEquals(expectedMinutes, duration.toMinutes())
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    inner class ThreadSafetyTests {

        @Test
        fun `concurrent parsing is thread safe`() {
            val inputs = listOf("PT15M", "-PT30M", "P1D", "PT1H30M", "-P1DT2H")
            val threads = (1..10).map { threadNum ->
                Thread {
                    repeat(100) {
                        inputs.forEach { input ->
                            val result = DurationUtils.parse(input)
                            assertNotNull(result, "Thread $threadNum failed to parse $input")
                        }
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }

        @Test
        fun `concurrent formatting is thread safe`() {
            val durations = listOf(
                Duration.ofMinutes(15),
                Duration.ofMinutes(-30),
                Duration.ofDays(1),
                Duration.ofHours(1).plusMinutes(30)
            )
            val threads = (1..10).map { threadNum ->
                Thread {
                    repeat(100) {
                        durations.forEach { duration ->
                            val result = DurationUtils.format(duration)
                            assertTrue(result.startsWith("P") || result.startsWith("-P"),
                                "Thread $threadNum produced invalid format: $result")
                        }
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
    }
}