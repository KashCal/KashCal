package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Scheduling Parameter Parsing Tests")
class SchedulingParserTest {
    private val parser = ICalParser()

    @Nested
    @DisplayName("METHOD Parsing Tests")
    inner class MethodParsingTests {
        @Test
        fun `parses METHOD REQUEST from VCALENDAR`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                DTSTAMP:20231215T120000Z
                SUMMARY:Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseWithMethod(ics)
            assertTrue(result is ParseResult.Success)
            val parseResult = result.getOrNull()
            assertNotNull(parseResult)
            assertEquals(ITipMethod.REQUEST, parseResult.method)
            assertEquals(1, parseResult.events.size)
        }

        @Test
        fun `parses METHOD REPLY from VCALENDAR`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                METHOD:REPLY
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseWithMethod(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(ITipMethod.REPLY, result.getOrNull()?.method)
        }

        @Test
        fun `parses METHOD CANCEL from VCALENDAR`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                METHOD:CANCEL
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                STATUS:CANCELLED
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseWithMethod(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(ITipMethod.CANCEL, result.getOrNull()?.method)
        }

        @Test
        fun `parseWithMethod returns null method when not present`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseWithMethod(ics)
            assertTrue(result is ParseResult.Success)
            assertNull(result.getOrNull()?.method)
        }
    }

    @Nested
    @DisplayName("ATTENDEE Scheduling Parameters")
    inner class AttendeeSchedulingParamsTests {
        @Test
        fun `parses SCHEDULE-AGENT on ATTENDEE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;SCHEDULE-AGENT=CLIENT:mailto:test@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals(1, event.attendees.size)
            assertEquals(ScheduleAgent.CLIENT, event.attendees[0].scheduleAgent)
        }

        @Test
        fun `parses SCHEDULE-STATUS on ATTENDEE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;SCHEDULE-STATUS="2.0;Success":mailto:test@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals(1, event.attendees.size)
            assertNotNull(event.attendees[0].scheduleStatus)
            assertEquals("2.0", event.attendees[0].scheduleStatus!![0].code)
        }

        @Test
        fun `parses SCHEDULE-FORCE-SEND on ATTENDEE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;SCHEDULE-FORCE-SEND=REQUEST:mailto:test@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals(ScheduleForceSend.REQUEST, event.attendees[0].scheduleForceSend)
        }

        @Test
        fun `parses SENT-BY on ATTENDEE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;SENT-BY="mailto:assistant@example.com":mailto:test@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals("assistant@example.com", event.attendees[0].sentBy)
        }
    }

    @Nested
    @DisplayName("RFC 5545 ATTENDEE Parameters")
    inner class Rfc5545AttendeeParamsTests {
        @Test
        fun `parses CUTYPE on ATTENDEE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;CUTYPE=ROOM:mailto:room@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals(CUType.ROOM, event.attendees[0].cutype)
        }

        @Test
        fun `parses DELEGATED-TO list`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;DELEGATED-TO="mailto:delegate@example.com":mailto:test@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals(listOf("delegate@example.com"), event.attendees[0].delegatedTo)
        }

        @Test
        fun `parses DELEGATED-FROM list`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ATTENDEE;DELEGATED-FROM="mailto:boss@example.com":mailto:delegate@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertEquals(listOf("boss@example.com"), event.attendees[0].delegatedFrom)
        }
    }

    @Nested
    @DisplayName("ORGANIZER Scheduling Parameters")
    inner class OrganizerSchedulingParamsTests {
        @Test
        fun `parses SCHEDULE-AGENT on ORGANIZER`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ORGANIZER;SCHEDULE-AGENT=NONE:mailto:organizer@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertNotNull(event.organizer)
            assertEquals(ScheduleAgent.NONE, event.organizer!!.scheduleAgent)
        }

        @Test
        fun `parses SCHEDULE-STATUS on ORGANIZER`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                ORGANIZER;SCHEDULE-STATUS="2.0;Delivered":mailto:organizer@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()?.firstOrNull()
            assertNotNull(event)
            assertNotNull(event.organizer?.scheduleStatus)
            assertEquals("2.0", event.organizer!!.scheduleStatus!![0].code)
        }
    }

    @Nested
    @DisplayName("VFREEBUSY Parsing Tests")
    inner class VFreeBusyParsingTests {
        @Test
        fun `parses basic VFREEBUSY`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                METHOD:REPLY
                BEGIN:VFREEBUSY
                UID:fb-123
                DTSTART:20231215T000000Z
                DTEND:20231222T000000Z
                DTSTAMP:20231215T120000Z
                ORGANIZER:mailto:organizer@example.com
                ATTENDEE:mailto:attendee@example.com
                FREEBUSY;FBTYPE=BUSY:20231215T090000Z/20231215T100000Z
                FREEBUSY;FBTYPE=BUSY-TENTATIVE:20231216T140000Z/20231216T150000Z
                END:VFREEBUSY
                END:VCALENDAR
            """.trimIndent()

            val freeBusy = parser.parseFreeBusy(ics)
            assertNotNull(freeBusy)
            assertEquals("fb-123", freeBusy.uid)
            assertNotNull(freeBusy.organizer)
            assertEquals("organizer@example.com", freeBusy.organizer!!.email)
            assertEquals(1, freeBusy.attendees.size)
            assertEquals(2, freeBusy.freeBusyPeriods.size)
        }

        @Test
        fun `parses FREEBUSY periods with different types`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VFREEBUSY
                UID:fb-123
                DTSTART:20231215T000000Z
                DTEND:20231222T000000Z
                DTSTAMP:20231215T120000Z
                FREEBUSY;FBTYPE=BUSY:20231215T090000Z/20231215T100000Z
                FREEBUSY;FBTYPE=BUSY-TENTATIVE:20231216T140000Z/20231216T150000Z
                FREEBUSY;FBTYPE=BUSY-UNAVAILABLE:20231217T090000Z/20231217T120000Z
                END:VFREEBUSY
                END:VCALENDAR
            """.trimIndent()

            val freeBusy = parser.parseFreeBusy(ics)
            assertNotNull(freeBusy)
            assertEquals(3, freeBusy.freeBusyPeriods.size)
            assertEquals(FreeBusyType.BUSY, freeBusy.freeBusyPeriods[0].type)
            assertEquals(FreeBusyType.BUSY_TENTATIVE, freeBusy.freeBusyPeriods[1].type)
            assertEquals(FreeBusyType.BUSY_UNAVAILABLE, freeBusy.freeBusyPeriods[2].type)
        }

        @Test
        fun `returns null for ICS without VFREEBUSY`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-123
                DTSTART:20231215T140000Z
                DTSTAMP:20231215T120000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val freeBusy = parser.parseFreeBusy(ics)
            assertNull(freeBusy)
        }
    }
}
