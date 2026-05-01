package org.onekash.icaldav.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.onekash.icaldav.model.ParseResult
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests comparing SimpleTimeZoneRegistry vs TimeZoneRegistryImpl.
 *
 * These tests verify that both registries produce equivalent parsing results
 * for real-world iCalendar data from various sources.
 */
@DisplayName("ICalParser Registry Integration Tests")
class ICalParserRegistryIntegrationTest {

    private val simpleParser = ICalParser()  // Uses SimpleTimeZoneRegistry by default
    private val fullParser = ICalParser.createWithFullRegistry()  // Uses TimeZoneRegistryImpl

    @Nested
    @DisplayName("Real-World iCalendar Data")
    inner class RealWorldDataTests {

        @Test
        @DisplayName("iCloud-style event with VTIMEZONE")
        fun `parse iCloud-style event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Apple Inc.//Mac OS X 10.15.7//EN
                CALSCALE:GREGORIAN
                BEGIN:VTIMEZONE
                TZID:America/Chicago
                BEGIN:DAYLIGHT
                TZOFFSETFROM:-0600
                RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
                DTSTART:20070311T020000
                TZNAME:CDT
                TZOFFSETTO:-0500
                END:DAYLIGHT
                BEGIN:STANDARD
                TZOFFSETFROM:-0500
                RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
                DTSTART:20071104T020000
                TZNAME:CST
                TZOFFSETTO:-0600
                END:STANDARD
                END:VTIMEZONE
                BEGIN:VEVENT
                DTEND;TZID=America/Chicago:20260106T150000
                TRANSP:OPAQUE
                UID:4C2E5A8D-1234-5678-9ABC-DEF012345678
                DTSTAMP:20251221T153656Z
                LOCATION:Conference Room A
                DESCRIPTION:Quarterly review meeting
                STATUS:CONFIRMED
                SEQUENCE:0
                SUMMARY:Q1 Planning Meeting
                DTSTART;TZID=America/Chicago:20260106T140000
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals(simpleEvent.uid, fullEvent.uid)
            assertEquals(simpleEvent.summary, fullEvent.summary)
            assertEquals(simpleEvent.location, fullEvent.location)
            assertEquals(simpleEvent.dtStart.timezone?.id, fullEvent.dtStart.timezone?.id)
        }

        @Test
        @DisplayName("Google Calendar event with TZID only")
        fun `parse Google Calendar style event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Google Inc//Google Calendar 70.9054//EN
                CALSCALE:GREGORIAN
                METHOD:PUBLISH
                BEGIN:VEVENT
                DTSTART;TZID=Europe/London:20260215T100000
                DTEND;TZID=Europe/London:20260215T110000
                DTSTAMP:20260122T100000Z
                UID:google-calendar-test@google.com
                CREATED:20260120T090000Z
                DESCRIPTION:Team standup
                LAST-MODIFIED:20260121T100000Z
                SEQUENCE:0
                STATUS:CONFIRMED
                SUMMARY:Daily Standup
                TRANSP:OPAQUE
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals("Europe/London", simpleEvent.dtStart.timezone?.id)
            assertEquals("Europe/London", fullEvent.dtStart.timezone?.id)
        }

        @Test
        @DisplayName("Outlook event with Windows timezone name")
        fun `parse Outlook event with Windows timezone`() {
            // Outlook sometimes uses Windows timezone names
            // Both registries should handle this (or fail gracefully)
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Microsoft Corporation//Outlook 16.0//EN
                BEGIN:VTIMEZONE
                TZID:Pacific Standard Time
                BEGIN:STANDARD
                DTSTART:16011104T020000
                RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11
                TZOFFSETFROM:-0700
                TZOFFSETTO:-0800
                END:STANDARD
                BEGIN:DAYLIGHT
                DTSTART:16010311T020000
                RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3
                TZOFFSETFROM:-0800
                TZOFFSETTO:-0700
                END:DAYLIGHT
                END:VTIMEZONE
                BEGIN:VEVENT
                DTSTART;TZID="Pacific Standard Time":20260310T090000
                DTEND;TZID="Pacific Standard Time":20260310T100000
                DTSTAMP:20260122T100000Z
                UID:outlook-test@microsoft.com
                SUMMARY:West Coast Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            // Both should parse successfully (embedded VTIMEZONE provides the rules)
            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)
        }

        @Test
        @DisplayName("TripIt all-day travel event")
        fun `parse TripIt all-day event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//TripIt//Test//EN
                BEGIN:VEVENT
                UID:tripit-hotel@example.com
                DTSTAMP:20251224T143330Z
                DTSTART;VALUE=DATE:20251225
                DTEND;VALUE=DATE:20251227
                SUMMARY:Holiday Hotel Stay
                LOCATION:San Francisco, CA
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertTrue(simpleEvent.isAllDay)
            assertTrue(fullEvent.isAllDay)
            assertEquals("20251225", simpleEvent.dtStart.toDayCode())
            assertEquals("20251225", fullEvent.dtStart.toDayCode())
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("event with no timezone (floating time)")
        fun `parse floating time event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:floating@example.com
                DTSTAMP:20260122T100000Z
                DTSTART:20260115T140000
                DTEND:20260115T150000
                SUMMARY:Local Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals(simpleEvent.summary, fullEvent.summary)
        }

        @Test
        @DisplayName("recurring event with EXDATE")
        fun `parse recurring event with EXDATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:recurring@example.com
                DTSTAMP:20260122T100000Z
                DTSTART;TZID=America/New_York:20260101T100000
                DTEND;TZID=America/New_York:20260101T110000
                RRULE:FREQ=WEEKLY;COUNT=10
                EXDATE;TZID=America/New_York:20260108T100000
                SUMMARY:Weekly Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals(1, simpleEvent.exdates.size)
            assertEquals(1, fullEvent.exdates.size)
            assertEquals(simpleEvent.exdates[0].timezone?.id, fullEvent.exdates[0].timezone?.id)
        }

        @Test
        @DisplayName("event with multiple attendees")
        fun `parse event with attendees`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:meeting@example.com
                DTSTAMP:20260122T100000Z
                DTSTART:20260115T140000Z
                DTEND:20260115T150000Z
                SUMMARY:Team Meeting
                ORGANIZER;CN=John Doe:mailto:john@example.com
                ATTENDEE;CN=Jane Smith;PARTSTAT=ACCEPTED:mailto:jane@example.com
                ATTENDEE;CN=Bob Wilson;PARTSTAT=TENTATIVE:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals(2, simpleEvent.attendees.size)
            assertEquals(2, fullEvent.attendees.size)
            assertEquals(simpleEvent.organizer?.email, fullEvent.organizer?.email)
        }

        @Test
        @DisplayName("event in extreme timezone (UTC+12)")
        fun `parse event in Pacific Auckland timezone`() {
            val originalTz = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))

                val ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VEVENT
                    UID:auckland@example.com
                    DTSTAMP:20260122T100000Z
                    DTSTART;VALUE=DATE:20260315
                    DTEND;VALUE=DATE:20260316
                    SUMMARY:Auckland Event
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()

                val simpleResult = simpleParser.parseAllEvents(ics)
                val fullResult = fullParser.parseAllEvents(ics)

                val simpleEvent = (simpleResult as ParseResult.Success).value[0]
                val fullEvent = (fullResult as ParseResult.Success).value[0]

                // Both should preserve the correct date regardless of device timezone
                assertEquals("20260315", simpleEvent.dtStart.toDayCode())
                assertEquals("20260315", fullEvent.dtStart.toDayCode())
            } finally {
                TimeZone.setDefault(originalTz)
            }
        }

        @Test
        @DisplayName("Asia/Shanghai timezone (UTC+8, no DST)")
        fun `parse event in Asia Shanghai timezone`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:shanghai@example.com
                DTSTAMP:20260122T100000Z
                DTSTART;TZID=Asia/Shanghai:20260501T090000
                DTEND;TZID=Asia/Shanghai:20260501T100000
                SUMMARY:Shanghai Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllEvents(ics)
            val fullResult = fullParser.parseAllEvents(ics)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals("Asia/Shanghai", simpleEvent.dtStart.timezone?.id)
            assertEquals("Asia/Shanghai", fullEvent.dtStart.timezone?.id)
        }
    }

    @Nested
    @DisplayName("VTODO and VJOURNAL")
    inner class OtherComponentTests {

        @Test
        @DisplayName("parse VTODO with both registries")
        fun `parse VTODO`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:todo@example.com
                DTSTAMP:20260122T100000Z
                DUE;TZID=America/New_York:20260130T170000
                SUMMARY:Complete report
                STATUS:IN-PROCESS
                PERCENT-COMPLETE:50
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllTodos(ics)
            val fullResult = fullParser.parseAllTodos(ics)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleTodo = (simpleResult as ParseResult.Success).value[0]
            val fullTodo = (fullResult as ParseResult.Success).value[0]

            assertEquals(simpleTodo.uid, fullTodo.uid)
            assertEquals(simpleTodo.due?.timezone?.id, fullTodo.due?.timezone?.id)
        }

        @Test
        @DisplayName("parse VJOURNAL with both registries")
        fun `parse VJOURNAL`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:journal@example.com
                DTSTAMP:20260122T100000Z
                DTSTART;VALUE=DATE:20260122
                SUMMARY:Daily Journal
                DESCRIPTION:Today was productive.
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val simpleResult = simpleParser.parseAllJournals(ics)
            val fullResult = fullParser.parseAllJournals(ics)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleJournal = (simpleResult as ParseResult.Success).value[0]
            val fullJournal = (fullResult as ParseResult.Success).value[0]

            assertEquals(simpleJournal.uid, fullJournal.uid)
            assertEquals(simpleJournal.summary, fullJournal.summary)
        }
    }
}
