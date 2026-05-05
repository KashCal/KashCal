package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult

/**
 * Unit tests for RDATE (Recurrence Date) parsing.
 *
 * RFC 5545 Section 3.8.5.2 defines RDATE as additional dates/times
 * for recurring events beyond what RRULE generates.
 */
@DisplayName("RDATE Parsing Tests")
class ICalParserRdateTest {

    private val parser = ICalParser()

    @Nested
    @DisplayName("Single RDATE")
    inner class SingleRdate {

        @Test
        fun `parse single RDATE with UTC timestamp`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-single@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with single RDATE
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(1, event.rdates.size)
            assertEquals("20260120T100000Z", event.rdates[0].toICalString())
        }

        @Test
        fun `parse single RDATE with TZID`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-tzid@test.com
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=America/New_York:20260115T100000
                DTEND;TZID=America/New_York:20260115T110000
                SUMMARY:Event with RDATE TZID
                RDATE;TZID=America/New_York:20260120T100000
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(1, event.rdates.size)
            assertNotNull(event.rdates[0].timezone)
            assertEquals("America/New_York", event.rdates[0].timezone?.id)
        }

        @Test
        fun `parse RDATE with VALUE=DATE (all-day)`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-date@test.com
                DTSTAMP:20260101T000000Z
                DTSTART;VALUE=DATE:20260115
                DTEND;VALUE=DATE:20260116
                SUMMARY:All-day event with RDATE
                RDATE;VALUE=DATE:20260120
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(1, event.rdates.size)
            assertTrue(event.rdates[0].isDate)
            assertEquals("20260120", event.rdates[0].toICalString())
        }
    }

    @Nested
    @DisplayName("Multiple RDATEs")
    inner class MultipleRdates {

        @Test
        fun `parse comma-separated RDATEs in single property`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-comma@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with comma-separated RDATEs
                RDATE:20260120T100000Z,20260125T100000Z,20260130T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(3, event.rdates.size)
            assertEquals("20260120T100000Z", event.rdates[0].toICalString())
            assertEquals("20260125T100000Z", event.rdates[1].toICalString())
            assertEquals("20260130T100000Z", event.rdates[2].toICalString())
        }

        @Test
        fun `parse multiple RDATE properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-multi@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with multiple RDATE properties
                RDATE:20260120T100000Z
                RDATE:20260125T100000Z
                RDATE:20260130T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(3, event.rdates.size)
        }

        @Test
        fun `parse multiple RDATE properties with different TZIDs`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-multi-tz@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with RDATE in different timezones
                RDATE;TZID=America/New_York:20260120T100000
                RDATE;TZID=Europe/London:20260125T150000
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(2, event.rdates.size)
            assertEquals("America/New_York", event.rdates[0].timezone?.id)
            assertEquals("Europe/London", event.rdates[1].timezone?.id)
        }
    }

    @Nested
    @DisplayName("RDATE with RRULE and EXDATE")
    inner class RdateWithRecurrence {

        @Test
        fun `parse event with RRULE and RDATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-rrule@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Weekly event with extra RDATE
                RRULE:FREQ=WEEKLY;COUNT=4
                RDATE:20260210T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertNotNull(event.rrule)
            assertEquals(1, event.rdates.size)
            assertEquals("20260210T100000Z", event.rdates[0].toICalString())
        }

        @Test
        fun `parse event with RRULE, EXDATE, and RDATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-rrule-exdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Weekly event with EXDATE and RDATE
                RRULE:FREQ=WEEKLY;COUNT=4
                EXDATE:20260122T100000Z
                RDATE:20260210T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertNotNull(event.rrule)
            assertEquals(1, event.exdates.size)
            assertEquals(1, event.rdates.size)
        }

        @Test
        fun `RDATE-only event (no RRULE)`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-only@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with RDATE only (no RRULE)
                RDATE:20260120T100000Z,20260125T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertNull(event.rrule)
            assertEquals(2, event.rdates.size)
            assertTrue(event.isRecurring(), "Event with RDATE should be recurring")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        fun `event without RDATE has empty list`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:no-rdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event without RDATE
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertTrue(event.rdates.isEmpty())
            assertFalse(event.isRecurring())
        }

        @Test
        fun `VALUE=PERIOD is skipped (not supported)`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-period@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with PERIOD RDATE
                RDATE;VALUE=PERIOD:20260120T100000Z/20260120T120000Z
                RDATE:20260125T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            // PERIOD is skipped, only the second RDATE is parsed
            assertEquals(1, event.rdates.size)
            assertEquals("20260125T100000Z", event.rdates[0].toICalString())
        }

        @Test
        fun `malformed RDATE is suppressed and event still parses`() {
            // With suppressInvalidProperties, malformed RDATE is skipped
            // and the event parses without RDATE (better than dropping the entire event)
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-malformed@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Event with malformed RDATE
                RDATE:INVALID,20260125T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            assertEquals(1, events.size, "Event should parse with malformed RDATE suppressed")
            assertEquals("rdate-malformed@test.com", events[0].uid)
        }
    }

    @Nested
    @DisplayName("Round-trip Tests")
    inner class RoundTrip {

        private val generator = ICalGenerator()

        @Test
        fun `RDATE survives parse-generate-parse round-trip`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-roundtrip@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Round-trip test
                RDATE:20260120T100000Z,20260125T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result1 = parser.parseAllEvents(ics)
            assertTrue(result1 is ParseResult.Success)
            val event1 = (result1 as ParseResult.Success).value.first()
            assertEquals(2, event1.rdates.size)

            // Generate
            val generated = generator.generate(event1, method = null)
            assertTrue(generated.contains("RDATE:"))

            // Parse again
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = (result2 as ParseResult.Success).value.first()

            assertEquals(event1.rdates.size, event2.rdates.size)
            assertEquals(event1.rdates[0].timestamp, event2.rdates[0].timestamp)
            assertEquals(event1.rdates[1].timestamp, event2.rdates[1].timestamp)
        }
    }
}
