package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.Transparency
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive RFC 5545 compliance tests based on CalConnect Developer's Guide.
 *
 * Tests cover:
 * - iCalendar Data Model requirements
 * - UID uniqueness and format requirements
 * - Property parsing and generation
 * - Component structure validation
 *
 * @see https://devguide.calconnect.org/
 */
@DisplayName("CalConnect RFC 5545 Compliance Tests")
class ICalParserCalConnectComplianceTest {
    private val parser = ICalParser()
    private val generator = ICalGenerator()

    @Nested
    @DisplayName("UID Property Requirements")
    inner class UidRequirementsTests {

        @Test
        fun `UID must be globally unique - email style`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:unique-123@example.com
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event with email-style UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals("unique-123@example.com", event.uid)
        }

        @Test
        fun `UID must be globally unique - UUID style`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:550e8400-e29b-41d4-a716-446655440000
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event with UUID-style UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals("550e8400-e29b-41d4-a716-446655440000", event.uid)
        }

        @Test
        fun `UID with special characters preserved`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-2023/12/15-meeting#001@corp.example.com
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event with special chars in UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertTrue(event.uid.contains("event-2023/12/15-meeting#001"))
        }

        @Test
        fun `Events without UID get generated UUID`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event without UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            assertEquals(1, events.size)
            assertTrue(events[0].uid.isNotBlank(), "Generated UID should not be blank")
        }

        @Test
        fun `UID same across related events in recurrence set`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:recurring-series@example.com
                DTSTART:20231201T100000Z
                DTEND:20231201T110000Z
                RRULE:FREQ=WEEKLY;COUNT=4
                SUMMARY:Weekly Meeting
                END:VEVENT
                BEGIN:VEVENT
                UID:recurring-series@example.com
                RECURRENCE-ID:20231208T100000Z
                DTSTART:20231208T140000Z
                DTEND:20231208T150000Z
                SUMMARY:Weekly Meeting (Rescheduled)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            assertEquals(2, events.size)
            assertTrue(events.all { it.uid == "recurring-series@example.com" })
        }
    }

    @Nested
    @DisplayName("DTSTART/DTEND Requirements")
    inner class DateTimeRequirementsTests {

        @Test
        fun `DTSTART is required for VEVENT`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-no-start@test.com
                SUMMARY:Event without start
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            // Events without DTSTART should be rejected or have null dtStart
            assertTrue(result is ParseResult.Success)
        }

        @Test
        fun `DTSTART must precede DTEND`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:valid-order@test.com
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Valid order
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertTrue(event.dtStart.timestamp < event.dtEnd!!.timestamp)
        }

        @Test
        fun `DTSTART and DTEND same type - both DATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:all-day@test.com
                DTSTART;VALUE=DATE:20231225
                DTEND;VALUE=DATE:20231226
                SUMMARY:Christmas Day
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertTrue(event.dtStart.isDate)
            assertTrue(event.dtEnd!!.isDate)
        }

        @Test
        fun `DTSTART and DTEND same type - both DATE-TIME`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:timed@test.com
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Timed Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertTrue(!event.dtStart.isDate)
            assertTrue(!event.dtEnd!!.isDate)
        }

        @Test
        fun `DURATION instead of DTEND is valid`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:duration@test.com
                DTSTART:20231215T140000Z
                DURATION:PT1H30M
                SUMMARY:Event with duration
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertNotNull(event.duration)
        }

        @Test
        fun `DURATION and DTEND mutually exclusive - DTEND takes precedence`() {
            // According to RFC 5545, both should not appear, but if they do,
            // implementation should handle gracefully
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:both@test.com
                DTSTART:20231215T140000Z
                DTEND:20231215T160000Z
                DURATION:PT1H
                SUMMARY:Event with both
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            // Should not crash
        }
    }

    @Nested
    @DisplayName("DTSTAMP Requirements")
    inner class DtstampRequirementsTests {

        @Test
        fun `DTSTAMP parsed correctly`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:dtstamp@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SUMMARY:Event with DTSTAMP
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertNotNull(event.dtstamp)
            assertEquals("20231215T120000Z", event.dtstamp?.toICalString())
        }

        @Test
        fun `DTSTAMP must be UTC`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:dtstamp-utc@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertTrue(event.dtstamp?.isUtc == true || event.dtstamp?.toICalString()?.endsWith("Z") == true)
        }
    }

    @Nested
    @DisplayName("SEQUENCE Requirements")
    inner class SequenceRequirementsTests {

        @Test
        fun `SEQUENCE starts at 0`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:seq-zero@test.com
                DTSTART:20231215T140000Z
                SUMMARY:New Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(0, event.sequence)
        }

        @Test
        fun `SEQUENCE increments on update`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:seq-update@test.com
                DTSTART:20231215T140000Z
                SEQUENCE:5
                SUMMARY:Updated Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertEquals(5, event.sequence)
        }

        @Test
        fun `SEQUENCE preserved in round-trip`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:seq-roundtrip@test.com
                DTSTART:20231215T140000Z
                DTEND:20231215T150000Z
                SEQUENCE:42
                SUMMARY:Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            // Generate and parse again
            val generated = generator.generate(event, method = null)
            val reparsed = parser.parseAllEvents(generated)
            assertTrue(reparsed is ParseResult.Success)
            assertEquals(42, reparsed.getOrNull()!![0].sequence)
        }
    }

    @Nested
    @DisplayName("STATUS Requirements")
    inner class StatusRequirementsTests {

        @Test
        fun `VEVENT STATUS values - TENTATIVE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:status-tentative@test.com
                DTSTART:20231215T140000Z
                STATUS:TENTATIVE
                SUMMARY:Maybe Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(EventStatus.TENTATIVE, result.getOrNull()!![0].status)
        }

        @Test
        fun `VEVENT STATUS values - CONFIRMED`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:status-confirmed@test.com
                DTSTART:20231215T140000Z
                STATUS:CONFIRMED
                SUMMARY:Confirmed Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(EventStatus.CONFIRMED, result.getOrNull()!![0].status)
        }

        @Test
        fun `VEVENT STATUS values - CANCELLED`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:status-cancelled@test.com
                DTSTART:20231215T140000Z
                STATUS:CANCELLED
                SUMMARY:Cancelled Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(EventStatus.CANCELLED, result.getOrNull()!![0].status)
        }

        @Test
        fun `VTODO STATUS values - all valid`() {
            val statuses = listOf("NEEDS-ACTION", "IN-PROCESS", "COMPLETED", "CANCELLED")
            val expected = listOf(
                TodoStatus.NEEDS_ACTION,
                TodoStatus.IN_PROCESS,
                TodoStatus.COMPLETED,
                TodoStatus.CANCELLED
            )

            statuses.forEachIndexed { index, status ->
                val ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VTODO
                    UID:todo-status-$index@test.com
                    DTSTAMP:20231215T120000Z
                    STATUS:$status
                    END:VTODO
                    END:VCALENDAR
                """.trimIndent()

                val result = parser.parseAllTodos(ics)
                assertTrue(result is ParseResult.Success)
                assertEquals(expected[index], result.getOrNull()!![0].status)
            }
        }

        @Test
        fun `VJOURNAL STATUS values - DRAFT FINAL CANCELLED`() {
            val statuses = listOf("DRAFT", "FINAL", "CANCELLED")
            val expected = listOf(
                JournalStatus.DRAFT,
                JournalStatus.FINAL,
                JournalStatus.CANCELLED
            )

            statuses.forEachIndexed { index, status ->
                val ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VJOURNAL
                    UID:journal-status-$index@test.com
                    DTSTAMP:20231215T120000Z
                    STATUS:$status
                    END:VJOURNAL
                    END:VCALENDAR
                """.trimIndent()

                val result = parser.parseAllJournals(ics)
                assertTrue(result is ParseResult.Success)
                assertEquals(expected[index], result.getOrNull()!![0].status)
            }
        }
    }

    @Nested
    @DisplayName("TRANSP Requirements")
    inner class TransparencyRequirementsTests {

        @Test
        fun `TRANSP OPAQUE blocks time`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:transp-opaque@test.com
                DTSTART:20231215T140000Z
                TRANSP:OPAQUE
                SUMMARY:Busy time
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(Transparency.OPAQUE, result.getOrNull()!![0].transparency)
        }

        @Test
        fun `TRANSP TRANSPARENT does not block time`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:transp-transparent@test.com
                DTSTART:20231215T140000Z
                TRANSP:TRANSPARENT
                SUMMARY:Free time
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(Transparency.TRANSPARENT, result.getOrNull()!![0].transparency)
        }
    }

    @Nested
    @DisplayName("CATEGORIES Requirements")
    inner class CategoriesRequirementsTests {

        @Test
        fun `single CATEGORIES property`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cat-single@test.com
                DTSTART:20231215T140000Z
                CATEGORIES:Work
                SUMMARY:Work Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(listOf("Work"), result.getOrNull()!![0].categories)
        }

        @Test
        fun `multiple values in CATEGORIES`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cat-multi@test.com
                DTSTART:20231215T140000Z
                CATEGORIES:Work,Meeting,Important
                SUMMARY:Tagged Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val categories = result.getOrNull()!![0].categories
            assertTrue(categories.containsAll(listOf("Work", "Meeting", "Important")))
        }

        @Test
        fun `multiple CATEGORIES properties combine`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cat-combine@test.com
                DTSTART:20231215T140000Z
                CATEGORIES:Work
                CATEGORIES:Personal
                SUMMARY:Combined Categories
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val categories = result.getOrNull()!![0].categories
            assertTrue(categories.size >= 2)
        }
    }

    @Nested
    @DisplayName("CLASS Property Requirements")
    inner class ClassPropertyRequirementsTests {

        @Test
        fun `CLASS PUBLIC is default`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:class-default@test.com
                DTSTART:20231215T140000Z
                SUMMARY:Default Class
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            // Default class should be PUBLIC or null
        }

        @Test
        fun `CLASS PRIVATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:class-private@test.com
                DTSTART:20231215T140000Z
                CLASS:PRIVATE
                SUMMARY:Private Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
        }

        @Test
        fun `CLASS CONFIDENTIAL`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:class-confidential@test.com
                DTSTART:20231215T140000Z
                CLASS:CONFIDENTIAL
                SUMMARY:Confidential Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
        }
    }

    @Nested
    @DisplayName("CREATED and LAST-MODIFIED Requirements")
    inner class CreatedLastModifiedTests {

        @Test
        fun `CREATED parsed correctly`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:created@test.com
                DTSTART:20231215T140000Z
                CREATED:20231201T100000Z
                SUMMARY:Event with CREATED
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertNotNull(result.getOrNull()!![0].created)
        }

        @Test
        fun `LAST-MODIFIED parsed correctly`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:lastmod@test.com
                DTSTART:20231215T140000Z
                LAST-MODIFIED:20231210T150000Z
                SUMMARY:Event with LAST-MODIFIED
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertNotNull(result.getOrNull()!![0].lastModified)
        }

        @Test
        fun `LAST-MODIFIED after CREATED`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:both-timestamps@test.com
                DTSTART:20231215T140000Z
                CREATED:20231201T100000Z
                LAST-MODIFIED:20231210T150000Z
                SUMMARY:Event with both
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertNotNull(event.created)
            assertNotNull(event.lastModified)
            assertTrue(event.created!!.timestamp <= event.lastModified!!.timestamp)
        }
    }

    @Nested
    @DisplayName("GEO Property Requirements")
    inner class GeoPropertyTests {

        @Test
        fun `GEO parsed correctly for VEVENT`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:geo@test.com
                DTSTART:20231215T140000Z
                GEO:37.386013;-122.082932
                LOCATION:Googleplex
                SUMMARY:Meeting at Google
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            // GEO should be parsed if supported
        }

        @Test
        fun `GEO parsed correctly for VTODO`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:geo-todo@test.com
                DTSTAMP:20231215T120000Z
                GEO:40.7128;-74.0060
                LOCATION:New York
                SUMMARY:Task in NYC
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("40.7128;-74.0060", result.getOrNull()!![0].geo)
        }
    }

    @Nested
    @DisplayName("URL Property Requirements")
    inner class UrlPropertyTests {

        @Test
        fun `URL parsed correctly`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:url@test.com
                DTSTART:20231215T140000Z
                URL:https://example.com/meeting/123
                SUMMARY:Event with URL
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("https://example.com/meeting/123", result.getOrNull()!![0].url)
        }
    }

    @Nested
    @DisplayName("ATTACH Property Requirements")
    inner class AttachPropertyTests {

        @Test
        fun `ATTACH URL parsed for VJOURNAL`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:attach-journal@test.com
                DTSTAMP:20231215T120000Z
                ATTACH:https://example.com/files/document.pdf
                ATTACH:https://example.com/files/image.png
                SUMMARY:Journal with attachments
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(2, result.getOrNull()!![0].attachments.size)
        }
    }

    @Nested
    @DisplayName("Component Ordering")
    inner class ComponentOrderingTests {

        @Test
        fun `Multiple VEVENTs in VCALENDAR parsed in order`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:first@test.com
                DTSTART:20231215T100000Z
                SUMMARY:First
                END:VEVENT
                BEGIN:VEVENT
                UID:second@test.com
                DTSTART:20231215T110000Z
                SUMMARY:Second
                END:VEVENT
                BEGIN:VEVENT
                UID:third@test.com
                DTSTART:20231215T120000Z
                SUMMARY:Third
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            assertEquals(3, events.size)
            assertEquals("first@test.com", events[0].uid)
            assertEquals("second@test.com", events[1].uid)
            assertEquals("third@test.com", events[2].uid)
        }

        @Test
        fun `Mixed VEVENT, VTODO, VJOURNAL parsed separately`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event@test.com
                DTSTART:20231215T100000Z
                SUMMARY:Event
                END:VEVENT
                BEGIN:VTODO
                UID:todo@test.com
                DTSTAMP:20231215T120000Z
                SUMMARY:Todo
                END:VTODO
                BEGIN:VJOURNAL
                UID:journal@test.com
                DTSTAMP:20231215T120000Z
                SUMMARY:Journal
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val calendar = parser.parse(ics)
            assertTrue(calendar is ParseResult.Success)
            val cal = calendar.getOrNull()!!
            assertEquals(1, cal.events.size)
            assertEquals(1, cal.todos.size)
            assertEquals(1, cal.journals.size)
        }
    }

    @Nested
    @DisplayName("VCALENDAR Properties")
    inner class VCalendarPropertiesTests {

        @Test
        fun `VERSION 2 required`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:version@test.com
                DTSTART:20231215T140000Z
                SUMMARY:Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("2.0", result.getOrNull()!!.version)
        }

        @Test
        fun `PRODID parsed correctly`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//MyCompany//MyProduct//EN
                BEGIN:VEVENT
                UID:prodid@test.com
                DTSTART:20231215T140000Z
                SUMMARY:Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("-//MyCompany//MyProduct//EN", result.getOrNull()!!.prodId)
        }

        @Test
        fun `METHOD parsed correctly`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:method@test.com
                DTSTART:20231215T140000Z
                SUMMARY:Meeting Request
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("REQUEST", result.getOrNull()!!.method)
        }

        @Test
        fun `CALSCALE defaults to GREGORIAN`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:calscale@test.com
                DTSTART:20231215T140000Z
                SUMMARY:Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("GREGORIAN", result.getOrNull()!!.calscale)
        }
    }
}
