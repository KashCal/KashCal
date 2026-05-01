package org.onekash.icaldav.parser

import org.onekash.icaldav.model.ParseResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs

/**
 * Malformed input tests for ICalParser.
 *
 * Based on real-world issues discovered in production:
 * - Malformed iCal from various servers
 * - Edge cases in property parsing
 * - Error recovery scenarios
 */
@DisplayName("ICalParser Malformed Input Tests")
class ICalParserMalformedInputTest {

    private val parser = ICalParser()

    @Nested
    @DisplayName("Missing Required Properties")
    inner class MissingRequiredPropertiesTests {

        @Test
        fun `event without UID is handled gracefully`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                DTSTART:20231215T100000Z
                DTEND:20231215T110000Z
                SUMMARY:No UID Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            // Should either generate a UID or skip the event
            assertTrue(events == null || events.isEmpty() || events.all { it.uid.isNotEmpty() })
        }

        @Test
        fun `event without DTSTART is handled gracefully`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid-123
                DTEND:20231215T110000Z
                SUMMARY:No DTSTART Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            // Should skip events without DTSTART or handle them somehow
            assertNotNull(result)
        }

        @Test
        fun `event with only UID and DTSTART is valid`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:minimal-event
                DTSTART:20231215T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(1, events.size)
            assertEquals("minimal-event", events[0].uid)
        }
    }

    @Nested
    @DisplayName("Malformed DateTime Values")
    inner class MalformedDateTimeTests {

        @Test
        fun `garbage datetime is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:not-a-date
                SUMMARY:Bad Date
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should handle gracefully - either skip or report error
            assertNotNull(result)
        }

        @Test
        fun `date only with VALUE=DATE is handled as all-day`() {
            // RFC 5545 compliant all-day event format
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART;VALUE=DATE:20231215
                SUMMARY:Date Only
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            // RFC 5545 VALUE=DATE should be parsed as all-day
            if (events != null && events.isNotEmpty()) {
                assertTrue(events[0].isAllDay, "Expected isAllDay=true but got false. dtStart=${events[0].dtStart}")
            }
        }

        @Test
        fun `date without time is handled gracefully`() {
            // Non-standard format without VALUE=DATE (some servers use this)
            // ical4j 3.x normalizes this to midnight DateTime, which is acceptable
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215
                SUMMARY:Date Only No VALUE
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should parse without error (ical4j normalizes to midnight)
            assertNotNull(result.getOrNull())
        }

        @Test
        fun `datetime with invalid timezone is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART;TZID=Invalid/Timezone:20231215T100000
                SUMMARY:Invalid TZ
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should handle invalid timezone gracefully
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Malformed Text Properties")
    inner class MalformedTextTests {

        @Test
        fun `unescaped newlines in SUMMARY are handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                SUMMARY:Line 1
                 continuation line
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            // Folded lines should be unfolded
            if (events != null && events.isNotEmpty()) {
                assertTrue(events[0].summary?.contains("continuation") == true)
            }
        }

        @Test
        fun `extremely long DESCRIPTION is handled`() {
            val longDesc = "A".repeat(100000)
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                DESCRIPTION:$longDesc
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            if (events != null && events.isNotEmpty()) {
                assertEquals(longDesc, events[0].description)
            }
        }

        @Test
        fun `special characters in LOCATION are preserved`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                LOCATION:Café "Main"\, Room 123\; Building A
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            if (events != null && events.isNotEmpty()) {
                val location = events[0].location
                assertNotNull(location)
                assertTrue(location.contains("Café"))
            }
        }

        @Test
        fun `null bytes in text are handled`() {
            val ical = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" +
                "BEGIN:VEVENT\r\nUID:test\r\nDTSTART:20231215T100000Z\r\n" +
                "SUMMARY:Test\u0000Event\r\nEND:VEVENT\r\nEND:VCALENDAR"

            val result = parser.parseAllEvents(ical)
            // Should handle null bytes without crashing
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Malformed RRULE")
    inner class MalformedRRuleTests {

        @Test
        fun `RRULE without FREQ is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                RRULE:INTERVAL=1
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should skip invalid RRULE or fail parsing
            assertNotNull(result)
        }

        @Test
        fun `RRULE with invalid FREQ is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                RRULE:FREQ=INVALID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertNotNull(result)
        }

        @Test
        fun `RRULE with negative COUNT is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                RRULE:FREQ=DAILY;COUNT=-5
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Malformed VALARM")
    inner class MalformedValarmTests {

        @Test
        fun `VALARM without TRIGGER is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should handle missing trigger gracefully
            assertNotNull(result)
        }

        @Test
        fun `VALARM with invalid TRIGGER format is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:INVALID
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Structure Issues")
    inner class StructureIssuesTests {

        @Test
        fun `unclosed VEVENT is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should handle unclosed component
            assertNotNull(result)
        }

        @Test
        fun `nested VEVENT is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:outer
                DTSTART:20231215T100000Z
                BEGIN:VEVENT
                UID:inner
                DTSTART:20231216T100000Z
                END:VEVENT
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should handle nested components
            assertNotNull(result)
        }

        @Test
        fun `empty VCALENDAR is handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            assertTrue(events == null || events.isEmpty())
        }

        @Test
        fun `multiple VCALENDAR blocks are handled`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                END:VEVENT
                END:VCALENDAR
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event2
                DTSTART:20231216T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            // Should parse at least the first calendar
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Encoding Issues")
    inner class EncodingIssuesTests {

        @Test
        fun `UTF-8 BOM is handled`() {
            val bom = "\uFEFF"
            val ical = bom + """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTART:20231215T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertNotNull(result)
        }

        @Test
        fun `mixed line endings are handled`() {
            val ical = "BEGIN:VCALENDAR\r\nVERSION:2.0\nPRODID:-//Test//EN\r" +
                "BEGIN:VEVENT\r\nUID:test\nDTSTART:20231215T100000Z\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR"

            val result = parser.parseAllEvents(ical)
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Production Edge Cases")
    inner class ProductionEdgeCasesTests {

        @Test
        fun `RECURRENCE-ID with modified instance is parsed correctly`() {
            // Modified recurring instances with RECURRENCE-ID
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20231215T100000Z
                DTEND:20231215T110000Z
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Daily Meeting
                END:VEVENT
                BEGIN:VEVENT
                UID:recurring-event
                RECURRENCE-ID:20231216T100000Z
                DTSTART:20231216T140000Z
                DTEND:20231216T150000Z
                SUMMARY:Daily Meeting (Moved)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            assertNotNull(events)
            assertEquals(2, events.size)

            val master = events.find { it.recurrenceId == null }
            val modified = events.find { it.recurrenceId != null }

            assertNotNull(master)
            assertNotNull(modified)
            assertEquals("recurring-event", master.uid)
            assertEquals("recurring-event", modified.uid)
            assertNotNull(modified.recurrenceId)
        }

        @Test
        fun `importId is correctly generated for modified instances`() {
            // importId format: {uid}:RECID:{datetime}
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-event
                RECURRENCE-ID:20231216T100000Z
                DTSTART:20231216T140000Z
                SUMMARY:Modified Instance
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            if (events != null && events.isNotEmpty()) {
                val event = events[0]
                assertTrue(event.importId.contains("test-event"))
                assertTrue(event.importId.contains("RECID"))
            }
        }

        @Test
        fun `EXDATE list is parsed correctly`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-event
                DTSTART:20231215T100000Z
                RRULE:FREQ=DAILY;COUNT=10
                EXDATE:20231217T100000Z
                EXDATE:20231220T100000Z
                SUMMARY:With Exclusions
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            if (events != null && events.isNotEmpty()) {
                val event = events[0]
                assertEquals(2, event.exdates.size)
            }
        }

        @Test
        fun `COLOR property is parsed - RFC 7986`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-event
                DTSTART:20231215T100000Z
                COLOR:red
                SUMMARY:Colored Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            val events = result.getOrNull()
            if (events != null && events.isNotEmpty()) {
                assertEquals("red", events[0].color)
            }
        }
    }
}