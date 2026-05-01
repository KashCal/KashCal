package org.onekash.icaldav.parser

import org.onekash.icaldav.model.*
import org.onekash.icaldav.recurrence.RRuleExpander
import org.onekash.icaldav.recurrence.TimeRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests to identify gaps between KashCal and iCalDAV library.
 *
 * These tests document expected behavior based on KashCal's production-tested
 * implementations. Failures indicate features that need to be ported.
 *
 * Gap Categories:
 * 1. All-day UTC handling in RRULE expansion
 * 2. CLASS property parsing
 * 3. X-* property preservation
 * 4. VALARM improvements
 * 5. RECURRENCE-ID format handling
 */
@DisplayName("KashCal Gap Analysis Tests")
class ICalParserKashCalGapsTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()

    // ==================== 1. All-Day UTC Handling ====================

    @Test
    fun `all-day recurring event in UTC+12 timezone should not shift dates`() {
        // Critical test: All-day event starting Jan 1 should stay Jan 1
        // regardless of device timezone
        val originalTz = TimeZone.getDefault()
        try {
            // Set device to Auckland (UTC+12/+13)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:allday-utc-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260101
                DTEND;VALUE=DATE:20260102
                SUMMARY:New Year Day
                RRULE:FREQ=YEARLY;COUNT=5
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            // Expand for 5 years
            val range = TimeRange(
                ZonedDateTime.of(2025, 12, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant(),
                ZonedDateTime.of(2031, 12, 31, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant()
            )

            val occurrences = expander.expand(event, range)

            assertEquals(5, occurrences.size, "Should have 5 yearly occurrences")

            // Critical: Each occurrence should be on Jan 1, not Dec 31 (shifted)
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.all { it.endsWith("0101") },
                "All occurrences should be Jan 1, got: $dayCodes")

        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day recurring event in UTC-10 timezone should not shift dates`() {
        val originalTz = TimeZone.getDefault()
        try {
            // Set device to Honolulu (UTC-10)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:allday-honolulu@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260315
                DTEND;VALUE=DATE:20260316
                SUMMARY:Event on 15th
                RRULE:FREQ=MONTHLY;COUNT=3
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val range = TimeRange(
                ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant(),
                ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant()
            )

            val occurrences = expander.expand(event, range)

            // Should be March 15, April 15, May 15
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.all { it.endsWith("15") },
                "All occurrences should be on 15th, got: $dayCodes")

        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ==================== 2. CLASS Property ====================

    @Test
    fun `parse CLASS property PUBLIC`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:class-public@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Public Event
            CLASS:PUBLIC
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Check if CLASS is parsed into classification field
        assertEquals(Classification.PUBLIC, event.classification,
            "CLASS:PUBLIC should be parsed into classification field")
    }

    @Test
    fun `parse CLASS property PRIVATE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:class-private@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Private Event
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(Classification.PRIVATE, event.classification,
            "CLASS:PRIVATE should be parsed into classification field")
    }

    @Test
    fun `parse CLASS property CONFIDENTIAL`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:class-conf@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Confidential Event
            CLASS:CONFIDENTIAL
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(Classification.CONFIDENTIAL, event.classification,
            "CLASS:CONFIDENTIAL should be parsed into classification field")
    }

    // ==================== 3. X-* Property Preservation ====================

    @Test
    fun `parse X-APPLE-STRUCTURED-LOCATION`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:x-apple-loc@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Apple Location Event
            LOCATION:Apple Park
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=Apple Park:geo:37.334722,-122.008889
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // X-* properties should be preserved for round-trip
        assertTrue(event.rawProperties.any { it.key.startsWith("X-APPLE") },
            "X-APPLE-STRUCTURED-LOCATION should be preserved in rawProperties")
    }

    @Test
    fun `parse X-GOOGLE-CONFERENCE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:x-google-conf@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Google Meet Event
            X-GOOGLE-CONFERENCE:https://meet.google.com/abc-defg-hij
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        val googleConf = event.rawProperties["X-GOOGLE-CONFERENCE"]
        assertEquals("https://meet.google.com/abc-defg-hij", googleConf,
            "X-GOOGLE-CONFERENCE should be preserved")
    }

    @Test
    fun `parse multiple X-* properties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-x@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Multi X Event
            X-CUSTOM-PROP1:value1
            X-CUSTOM-PROP2:value2
            X-CUSTOM-PROP3:value3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        val xProps = event.rawProperties.filterKeys { it.startsWith("X-") }
        assertEquals(3, xProps.size, "Should preserve all 3 X-* properties")
    }

    // ==================== 4. VALARM Improvements ====================

    @Test
    fun `parse VALARM with PT duration trigger`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:valarm-pt@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Event with PT alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.alarms.isNotEmpty(), "Should parse VALARM")
        val alarm = event.alarms.first()
        assertNotNull(alarm.trigger, "Alarm should have trigger")
        assertEquals(-30 * 60L, alarm.trigger?.seconds,
            "Trigger should be -30 minutes (-1800 seconds)")
    }

    @Test
    fun `parse VALARM with P duration trigger (days)`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:valarm-p@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Event with P alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.alarms.isNotEmpty(), "Should parse VALARM")
        val alarm = event.alarms.first()
        assertEquals(-24 * 60 * 60L, alarm.trigger?.seconds,
            "Trigger should be -1 day (-86400 seconds)")
    }

    @Test
    fun `parse VALARM with absolute DATE-TIME trigger`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:valarm-abs@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Event with absolute alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME:20260115T130000Z
            DESCRIPTION:Absolute reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.alarms.isNotEmpty(), "Should parse absolute VALARM")
        val alarm = event.alarms.first()
        assertNotNull(alarm.triggerAbsolute,
            "Alarm should have absolute trigger datetime")
    }

    @Test
    fun `parse multiple VALARMs`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:valarm-multi@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Event with multiple alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals(3, event.alarms.size, "Should parse all 3 VALARMs")
    }

    // ==================== 5. RECURRENCE-ID Format Handling ====================

    @Test
    fun `parse RECURRENCE-ID with UTC datetime`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-utc@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260101T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Master
            END:VEVENT
            BEGIN:VEVENT
            UID:recid-utc@test.com
            DTSTAMP:20251221T153656Z
            RECURRENCE-ID:20260103T100000Z
            DTSTART:20260103T140000Z
            SUMMARY:Exception UTC
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val events = (result as ParseResult.Success).value

        val exception = events.find { it.recurrenceId != null }
        assertNotNull(exception, "Should parse exception event")
        assertEquals("20260103", exception!!.recurrenceId?.toDayCode(),
            "RECURRENCE-ID should parse to Jan 3")
    }

    @Test
    fun `parse RECURRENCE-ID with local datetime and TZID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-tzid@test.com
            DTSTAMP:20251221T153656Z
            DTSTART;TZID=America/New_York:20260101T100000
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Master
            END:VEVENT
            BEGIN:VEVENT
            UID:recid-tzid@test.com
            DTSTAMP:20251221T153656Z
            RECURRENCE-ID;TZID=America/New_York:20260103T100000
            DTSTART;TZID=America/New_York:20260103T140000
            SUMMARY:Exception TZID
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val events = (result as ParseResult.Success).value

        val exception = events.find { it.recurrenceId != null }
        assertNotNull(exception, "Should parse exception with TZID")
        assertEquals("20260103", exception!!.recurrenceId?.toDayCode())
    }

    @Test
    fun `parse RECURRENCE-ID with VALUE=DATE for all-day`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-date@test.com
            DTSTAMP:20251221T153656Z
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:All-Day Master
            END:VEVENT
            BEGIN:VEVENT
            UID:recid-date@test.com
            DTSTAMP:20251221T153656Z
            RECURRENCE-ID;VALUE=DATE:20260103
            DTSTART;VALUE=DATE:20260104
            DTEND;VALUE=DATE:20260105
            SUMMARY:All-Day Exception (moved)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val events = (result as ParseResult.Success).value

        val exception = events.find { it.recurrenceId != null }
        assertNotNull(exception, "Should parse all-day exception")
        assertTrue(exception!!.recurrenceId?.isDate == true,
            "RECURRENCE-ID should be DATE type")
        assertEquals("20260103", exception.recurrenceId?.toDayCode())
    }

    // ==================== Round-Trip Tests ====================

    @Test
    fun `X-* properties survive round-trip`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-x@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Round Trip X
            X-CUSTOM-TEST:test-value-123
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Generate ICS
        val generator = org.onekash.icaldav.parser.ICalGenerator()
        val generated = generator.generate(event, method = null)

        // Re-parse
        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)
        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        // X-CUSTOM-TEST should survive round-trip
        val customValue = roundTrippedEvent.rawProperties["X-CUSTOM-TEST"]
        assertEquals("test-value-123", customValue,
            "X-CUSTOM-TEST should survive round-trip")
    }

    @Test
    fun `CLASS property survives round-trip`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip-class@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:Round Trip Class
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        val generator = org.onekash.icaldav.parser.ICalGenerator()
        val generated = generator.generate(event, method = null)

        val reparsed = parser.parseAllEvents(generated)
        assertTrue(reparsed is ParseResult.Success)
        val roundTrippedEvent = (reparsed as ParseResult.Success).value.first()

        assertEquals(Classification.PRIVATE, roundTrippedEvent.classification,
            "CLASS:PRIVATE should survive round-trip")
    }
}
