package org.onekash.icaldav.parser

import org.onekash.icaldav.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import java.time.Duration

/**
 * Tests for DURATION property parsing and floating time handling.
 *
 * RFC 5545 Section 3.8.2.5: DURATION property
 * RFC 5545 Section 3.3.5: DATE-TIME with no timezone = floating
 */
@DisplayName("ICalParser Duration and Floating Time Tests")
class ICalParserDurationTest {

    private val parser = ICalParser()

    @Nested
    @DisplayName("DURATION Property Parsing")
    inner class DurationPropertyTests {

        @Test
        fun `parse event with DURATION instead of DTEND`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration-event-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DURATION:PT1H30M
                SUMMARY:90 Minute Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            assertEquals(1, events.size)

            val event = events[0]
            assertNull(event.dtEnd, "Event with DURATION should not have dtEnd")
            assertNotNull(event.duration, "Event should have duration parsed")
            assertEquals(Duration.ofMinutes(90), event.duration)

            // effectiveEnd should calculate from duration
            val effectiveEnd = event.effectiveEnd()
            assertNotNull(effectiveEnd)
        }

        @Test
        fun `parse event with DURATION in hours only`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration-hours
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                DURATION:PT2H
                SUMMARY:Two Hour Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Duration.ofHours(2), event.duration)
        }

        @Test
        fun `parse event with DURATION in minutes only`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration-minutes
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                DURATION:PT45M
                SUMMARY:45 Minute Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Duration.ofMinutes(45), event.duration)
        }

        @Test
        fun `parse event with DURATION in days`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration-days
                DTSTAMP:20231215T100000Z
                DTSTART;VALUE=DATE:20231215
                DURATION:P3D
                SUMMARY:Three Day Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Duration.ofDays(3), event.duration)
            assertTrue(event.isAllDay)
        }

        @Test
        fun `parse event with DURATION in weeks`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration-weeks
                DTSTAMP:20231215T100000Z
                DTSTART;VALUE=DATE:20231215
                DURATION:P2W
                SUMMARY:Two Week Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Duration.ofDays(14), event.duration)
        }

        @Test
        fun `parse event with complex DURATION P1DT2H30M`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration-complex
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                DURATION:P1DT2H30M
                SUMMARY:Complex Duration Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            val expectedDuration = Duration.ofDays(1).plusHours(2).plusMinutes(30)
            assertEquals(expectedDuration, event.duration)
        }

        @Test
        fun `parse recurring event with DURATION`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:recurring-duration
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                DURATION:PT1H
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Daily One Hour Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Duration.ofHours(1), event.duration)
            assertNotNull(event.rrule)
            assertEquals(Frequency.DAILY, event.rrule!!.freq)
        }

        @Test
        fun `DURATION zero is valid (instant event)`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:instant-event
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                DURATION:PT0S
                SUMMARY:Instant Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(Duration.ZERO, event.duration)
        }
    }

    @Nested
    @DisplayName("Floating Time (No Timezone)")
    inner class FloatingTimeTests {

        @Test
        fun `parse event with floating time - no TZID no Z suffix`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:floating-event-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000
                DTEND:20231215T150000
                SUMMARY:Floating Time Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            // Floating time: isUtc=false, timezone may be null or system default
            // The key distinction is isUtc=false (vs UTC which has isUtc=true)
            assertFalse(event.dtStart.isUtc, "Floating time should not be marked as UTC")
            assertFalse(event.isAllDay)
        }

        @Test
        fun `parse event with UTC time - Z suffix`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:utc-event-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:UTC Time Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertTrue(event.dtStart.isUtc, "Z suffix should mark as UTC")
        }

        @Test
        fun `parse event with explicit timezone - TZID parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:tz-event-1
                DTSTAMP:20231215T100000Z
                DTSTART;TZID=America/New_York:20231215T140000
                DTEND;TZID=America/New_York:20231215T150000
                SUMMARY:New York Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertNotNull(event.dtStart.timezone, "Should have timezone from TZID")
            assertEquals("America/New_York", event.dtStart.timezone?.id)
            assertFalse(event.dtStart.isUtc)
        }

        @Test
        fun `parse recurring event with floating time`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:floating-recurring
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T090000
                DTEND:20231215T100000
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Daily Floating Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            // Floating time: isUtc=false
            assertFalse(event.dtStart.isUtc, "Floating time should not be marked as UTC")
            assertNotNull(event.rrule)
        }

        @Test
        fun `floating vs UTC times are distinct`() {
            val floatingIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:compare-floating
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000
                SUMMARY:Floating
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val utcIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:compare-utc
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:UTC
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val floatingResult = parser.parseAllEvents(floatingIcal)
            val utcResult = parser.parseAllEvents(utcIcal)

            assertTrue(floatingResult is ParseResult.Success)
            assertTrue(utcResult is ParseResult.Success)

            val floatingEvent = floatingResult.getOrNull()!![0]
            val utcEvent = utcResult.getOrNull()!![0]

            // Same datetime string, but different interpretations
            assertFalse(floatingEvent.dtStart.isUtc)
            assertTrue(utcEvent.dtStart.isUtc)
        }
    }

    @Nested
    @DisplayName("DURATION with DTEND Conflict")
    inner class DurationDtEndConflictTests {

        @Test
        fun `when both DURATION and DTEND present - DTEND takes precedence per RFC 5545`() {
            // RFC 5545 says these are mutually exclusive, but we should handle gracefully
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:both-present
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                DTEND:20231215T120000Z
                DURATION:PT1H
                SUMMARY:Conflict Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            // DTEND should be present (takes precedence)
            assertNotNull(event.dtEnd)
        }
    }

    @Nested
    @DisplayName("Negative DURATION (for VALARM)")
    inner class NegativeDurationTests {

        @Test
        fun `negative DURATION in VALARM is valid`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:alarm-event
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event With Alarm
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT30M
                DESCRIPTION:30 minutes before
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.alarms.size)
            val alarm = event.alarms[0]
            assertEquals(-30, alarm.trigger!!.toMinutes())
        }
    }
}
