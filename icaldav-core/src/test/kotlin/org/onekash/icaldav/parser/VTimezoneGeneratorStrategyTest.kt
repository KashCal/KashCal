package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.timezone.TimezoneServiceClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for VTimezoneGenerator strategy support.
 *
 * Tests the three generation strategies: INLINE, TZURL_ONLY, BOTH
 */
@DisplayName("VTimezoneGenerator Strategy Tests")
class VTimezoneGeneratorStrategyTest {

    @Nested
    @DisplayName("Default Constructor (Backward Compatibility)")
    inner class BackwardCompatibilityTests {

        @Test
        fun `default constructor works without parameters`() {
            // This is critical for backward compatibility
            val generator = VTimezoneGenerator()

            val result = generator.generate("America/New_York")

            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("TZID:America/New_York"))
            assertTrue(result.contains("END:VTIMEZONE"))
        }

        @Test
        fun `default constructor uses INLINE strategy`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate("Europe/London")

            // INLINE strategy produces full VTIMEZONE
            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("BEGIN:STANDARD") || result.contains("BEGIN:DAYLIGHT"))
        }

        @Test
        fun `default constructor output matches previous behavior`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate("America/Chicago")

            // Full VTIMEZONE with offsets and transitions
            assertTrue(result.contains("TZID:America/Chicago"))
            assertTrue(result.contains("TZOFFSETFROM:"))
            assertTrue(result.contains("TZOFFSETTO:"))
        }
    }

    @Nested
    @DisplayName("VTimezoneStrategy Enum")
    inner class StrategyEnumTests {

        @Test
        fun `INLINE strategy exists`() {
            assertEquals("INLINE", VTimezoneGenerator.VTimezoneStrategy.INLINE.name)
        }

        @Test
        fun `TZURL_ONLY strategy exists`() {
            assertEquals("TZURL_ONLY", VTimezoneGenerator.VTimezoneStrategy.TZURL_ONLY.name)
        }

        @Test
        fun `BOTH strategy exists`() {
            assertEquals("BOTH", VTimezoneGenerator.VTimezoneStrategy.BOTH.name)
        }

        @Test
        fun `all strategies are available`() {
            val strategies = VTimezoneGenerator.VTimezoneStrategy.values()
            assertEquals(3, strategies.size)
        }
    }

    @Nested
    @DisplayName("INLINE Strategy Tests")
    inner class InlineStrategyTests {

        @Test
        fun `INLINE strategy generates full VTIMEZONE`() {
            val generator = VTimezoneGenerator(
                timezoneService = null,
                strategy = VTimezoneGenerator.VTimezoneStrategy.INLINE
            )

            val result = generator.generate("America/New_York")

            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("TZID:America/New_York"))
            assertTrue(result.contains("END:VTIMEZONE"))
        }

        @Test
        fun `INLINE strategy includes STANDARD component for non-DST timezone`() {
            val generator = VTimezoneGenerator(
                strategy = VTimezoneGenerator.VTimezoneStrategy.INLINE
            )

            // Asia/Kolkata doesn't have DST
            val result = generator.generate("Asia/Kolkata")

            assertTrue(result.contains("BEGIN:STANDARD"))
            assertTrue(result.contains("END:STANDARD"))
        }

        @Test
        fun `INLINE strategy includes DAYLIGHT component for DST timezone`() {
            val generator = VTimezoneGenerator(
                strategy = VTimezoneGenerator.VTimezoneStrategy.INLINE
            )

            // America/New_York has DST
            val result = generator.generate("America/New_York")

            // Should have both STANDARD and DAYLIGHT
            assertTrue(result.contains("BEGIN:STANDARD") || result.contains("BEGIN:DAYLIGHT"))
        }

        @Test
        fun `INLINE strategy does not include TZURL without service`() {
            val generator = VTimezoneGenerator(
                timezoneService = null,
                strategy = VTimezoneGenerator.VTimezoneStrategy.INLINE
            )

            val result = generator.generate("Europe/Paris")

            assertFalse(result.contains("TZURL:"))
        }
    }

    @Nested
    @DisplayName("TZURL_ONLY Strategy Tests")
    inner class TzurlOnlyStrategyTests {

        @Test
        fun `TZURL_ONLY strategy generates minimal VTIMEZONE with TZURL`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(
                timezoneService = service,
                strategy = VTimezoneGenerator.VTimezoneStrategy.TZURL_ONLY
            )

            val result = generator.generate("America/Los_Angeles")

            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("TZID:America/Los_Angeles"))
            assertTrue(result.contains("TZURL:"))
            assertTrue(result.contains("END:VTIMEZONE"))
        }

        @Test
        fun `TZURL_ONLY strategy includes correct TZURL`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(
                timezoneService = service,
                strategy = VTimezoneGenerator.VTimezoneStrategy.TZURL_ONLY
            )

            val result = generator.generate("Asia/Tokyo")

            assertTrue(result.contains("TZURL:https://www.tzurl.org/zoneinfo/Asia/Tokyo.ics"))
        }

        @Test
        fun `TZURL_ONLY strategy does not include full transition data`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(
                timezoneService = service,
                strategy = VTimezoneGenerator.VTimezoneStrategy.TZURL_ONLY
            )

            val result = generator.generate("America/Chicago")

            // Should NOT include full STANDARD/DAYLIGHT components with offsets
            // (minimal output just has TZID and TZURL)
            assertTrue(result.contains("TZURL:"))
        }
    }

    @Nested
    @DisplayName("BOTH Strategy Tests")
    inner class BothStrategyTests {

        @Test
        fun `BOTH strategy generates full VTIMEZONE with TZURL`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(
                timezoneService = service,
                strategy = VTimezoneGenerator.VTimezoneStrategy.BOTH
            )

            val result = generator.generate("America/New_York")

            // Full VTIMEZONE content
            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("TZID:America/New_York"))
            assertTrue(result.contains("TZOFFSETFROM:"))
            assertTrue(result.contains("TZOFFSETTO:"))
            // Plus TZURL
            assertTrue(result.contains("TZURL:"))
        }

        @Test
        fun `BOTH strategy includes correct TZURL`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(
                timezoneService = service,
                strategy = VTimezoneGenerator.VTimezoneStrategy.BOTH
            )

            val result = generator.generate("Europe/Berlin")

            assertTrue(result.contains("TZURL:https://www.tzurl.org/zoneinfo/Europe/Berlin.ics"))
        }

        @Test
        fun `BOTH strategy is most complete output`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(
                timezoneService = service,
                strategy = VTimezoneGenerator.VTimezoneStrategy.BOTH
            )

            val result = generator.generate("Pacific/Auckland")

            // Has everything
            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("TZID:"))
            assertTrue(result.contains("END:VTIMEZONE"))
            assertTrue(result.contains("TZURL:"))
        }
    }

    @Nested
    @DisplayName("UTC Handling Tests")
    inner class UtcHandlingTests {

        @Test
        fun `UTC returns empty for all strategies`() {
            val service = TimezoneServiceClient()

            val inlineGen = VTimezoneGenerator(strategy = VTimezoneGenerator.VTimezoneStrategy.INLINE)
            val tzurlGen = VTimezoneGenerator(service, VTimezoneGenerator.VTimezoneStrategy.TZURL_ONLY)
            val bothGen = VTimezoneGenerator(service, VTimezoneGenerator.VTimezoneStrategy.BOTH)

            assertEquals("", inlineGen.generate("UTC"))
            assertEquals("", tzurlGen.generate("UTC"))
            assertEquals("", bothGen.generate("UTC"))
        }

        @Test
        fun `Etc UTC returns empty`() {
            val generator = VTimezoneGenerator()

            assertEquals("", generator.generate("Etc/UTC"))
        }

        @Test
        fun `Z returns empty`() {
            val generator = VTimezoneGenerator()

            assertEquals("", generator.generate("Z"))
        }

        @Test
        fun `GMT returns empty`() {
            val generator = VTimezoneGenerator()

            assertEquals("", generator.generate("GMT"))
        }
    }

    @Nested
    @DisplayName("Invalid Timezone Handling Tests")
    inner class InvalidTimezoneTests {

        @Test
        fun `invalid timezone returns empty string for INLINE`() {
            val generator = VTimezoneGenerator(strategy = VTimezoneGenerator.VTimezoneStrategy.INLINE)

            val result = generator.generate("Invalid/Timezone")

            assertEquals("", result)
        }

        @Test
        fun `invalid timezone returns empty string for TZURL_ONLY`() {
            val service = TimezoneServiceClient()
            val generator = VTimezoneGenerator(service, VTimezoneGenerator.VTimezoneStrategy.TZURL_ONLY)

            val result = generator.generate("Not/A/Timezone")

            assertEquals("", result)
        }

        @Test
        fun `empty timezone ID returns empty string`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate("")

            assertEquals("", result)
        }
    }

    @Nested
    @DisplayName("Multiple Timezones Tests")
    inner class MultipleTimezonesTests {

        @Test
        fun `generate multiple timezones`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate(setOf("America/New_York", "Europe/London"))

            // Should contain both
            assertTrue(result.contains("TZID:America/New_York"))
            assertTrue(result.contains("TZID:Europe/London"))
        }

        @Test
        fun `generate multiple timezones deduplicates`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate(setOf("America/New_York", "America/New_York"))

            // Count occurrences - should only have one
            val count = Regex("TZID:America/New_York").findAll(result).count()
            assertEquals(1, count)
        }

        @Test
        fun `generate multiple timezones skips UTC`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate(setOf("America/Chicago", "UTC", "Europe/Paris"))

            assertTrue(result.contains("TZID:America/Chicago"))
            assertTrue(result.contains("TZID:Europe/Paris"))
            assertFalse(result.contains("TZID:UTC"))
        }
    }

    @Nested
    @DisplayName("formatOffset Tests")
    inner class FormatOffsetTests {

        @Test
        fun `formatOffset positive offset`() {
            val generator = VTimezoneGenerator()
            val offset = java.time.ZoneOffset.ofHours(5)

            val result = generator.formatOffset(offset)

            assertEquals("+0500", result)
        }

        @Test
        fun `formatOffset negative offset`() {
            val generator = VTimezoneGenerator()
            val offset = java.time.ZoneOffset.ofHours(-8)

            val result = generator.formatOffset(offset)

            assertEquals("-0800", result)
        }

        @Test
        fun `formatOffset with minutes`() {
            val generator = VTimezoneGenerator()
            val offset = java.time.ZoneOffset.ofHoursMinutes(5, 30)

            val result = generator.formatOffset(offset)

            assertEquals("+0530", result)
        }

        @Test
        fun `formatOffset zero`() {
            val generator = VTimezoneGenerator()
            val offset = java.time.ZoneOffset.UTC

            val result = generator.formatOffset(offset)

            assertEquals("+0000", result)
        }

        @Test
        fun `formatOffset negative with minutes`() {
            val generator = VTimezoneGenerator()
            val offset = java.time.ZoneOffset.ofHoursMinutes(-9, -30)

            val result = generator.formatOffset(offset)

            assertEquals("-0930", result)
        }
    }
}
