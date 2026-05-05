package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.ParseResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive tests for VJOURNAL parsing per RFC 5545 Section 3.6.3.
 */
@DisplayName("ICalParser VJOURNAL Tests")
class ICalParserVJournalTest {
    private val parser = ICalParser()

    @Nested
    @DisplayName("Basic VJOURNAL Parsing")
    inner class BasicParsingTests {

        @Test
        fun `parse simple VJOURNAL with minimal properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:simple-journal-123
                DTSTAMP:20231215T120000Z
                SUMMARY:Daily Log
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)

            val journals = result.getOrNull()!!
            assertEquals(1, journals.size)

            val journal = journals[0]
            assertEquals("simple-journal-123", journal.uid)
            assertEquals("Daily Log", journal.summary)
            assertEquals(JournalStatus.DRAFT, journal.status)  // Default
        }

        @Test
        fun `parse VJOURNAL with all text properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:full-text-journal
                DTSTAMP:20231215T120000Z
                SUMMARY:Project Notes
                DESCRIPTION:Today I worked on the CalDAV implementation.\nMade good progress.
                URL:https://example.com/journal/123
                CLASS:PRIVATE
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)

            val journal = result.getOrNull()!![0]
            assertEquals("full-text-journal", journal.uid)
            assertEquals("Project Notes", journal.summary)
            assertTrue(journal.description?.contains("CalDAV implementation") == true)
            assertEquals("https://example.com/journal/123", journal.url)
            assertEquals("PRIVATE", journal.classification)
        }

        @Test
        fun `parse VJOURNAL with escaped characters`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:escaped-journal
                DTSTAMP:20231215T120000Z
                SUMMARY:Notes\, with special chars\; here
                DESCRIPTION:Line1\nLine2\nLine3
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)

            val journal = result.getOrNull()!![0]
            assertEquals("Notes, with special chars; here", journal.summary)
            assertTrue(journal.description?.contains("\n") == true)
        }
    }

    @Nested
    @DisplayName("VJOURNAL Status Parsing")
    inner class StatusParsingTests {

        @Test
        fun `parse VJOURNAL with DRAFT status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:draft-journal
                DTSTAMP:20231215T120000Z
                STATUS:DRAFT
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]
            assertEquals(JournalStatus.DRAFT, journal.status)
        }

        @Test
        fun `parse VJOURNAL with FINAL status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:final-journal
                DTSTAMP:20231215T120000Z
                STATUS:FINAL
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]
            assertEquals(JournalStatus.FINAL, journal.status)
        }

        @Test
        fun `parse VJOURNAL with CANCELLED status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:cancelled-journal
                DTSTAMP:20231215T120000Z
                STATUS:CANCELLED
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]
            assertEquals(JournalStatus.CANCELLED, journal.status)
        }

        @Test
        fun `parse VJOURNAL with unknown status defaults to DRAFT`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:unknown-status-journal
                DTSTAMP:20231215T120000Z
                STATUS:UNKNOWN
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]
            assertEquals(JournalStatus.DRAFT, journal.status)
        }
    }

    @Nested
    @DisplayName("VJOURNAL DateTime Parsing")
    inner class DateTimeParsingTests {

        @Test
        fun `parse VJOURNAL with all datetime properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:datetime-journal
                DTSTAMP:20231215T120000Z
                DTSTART:20231215T000000Z
                CREATED:20231210T080000Z
                LAST-MODIFIED:20231218T140000Z
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertNotNull(journal.dtstamp)
            assertNotNull(journal.dtStart)
            assertNotNull(journal.created)
            assertNotNull(journal.lastModified)

            assertEquals("20231215T120000Z", journal.dtstamp?.toICalString())
            assertEquals("20231215T000000Z", journal.dtStart?.toICalString())
        }

        @Test
        fun `parse VJOURNAL with DATE-only DTSTART`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:date-only-journal
                DTSTAMP:20231215T120000Z
                DTSTART;VALUE=DATE:20231215
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertNotNull(journal.dtStart)
            assertTrue(journal.dtStart!!.isDate)
        }
    }

    @Nested
    @DisplayName("VJOURNAL Categories Parsing")
    inner class CategoriesParsingTests {

        @Test
        fun `parse VJOURNAL with single category`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:single-cat-journal
                DTSTAMP:20231215T120000Z
                CATEGORIES:Personal
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]
            assertEquals(listOf("Personal"), journal.categories)
        }

        @Test
        fun `parse VJOURNAL with multiple categories`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:multi-cat-journal
                DTSTAMP:20231215T120000Z
                CATEGORIES:Work,Daily,Progress
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]
            assertEquals(3, journal.categories.size)
            assertTrue(journal.categories.contains("Work"))
            assertTrue(journal.categories.contains("Daily"))
            assertTrue(journal.categories.contains("Progress"))
        }
    }

    @Nested
    @DisplayName("VJOURNAL Organizer and Attendees")
    inner class OrganizerAttendeesTests {

        @Test
        fun `parse VJOURNAL with organizer`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:org-journal
                DTSTAMP:20231215T120000Z
                ORGANIZER;CN=John Doe:mailto:john@example.com
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertNotNull(journal.organizer)
            assertEquals("john@example.com", journal.organizer?.email)
            assertEquals("John Doe", journal.organizer?.name)
        }

        @Test
        fun `parse VJOURNAL with attendees`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:attendee-journal
                DTSTAMP:20231215T120000Z
                ATTENDEE;CN=Alice:mailto:alice@example.com
                ATTENDEE;CN=Bob:mailto:bob@example.com
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertEquals(2, journal.attendees.size)
            assertTrue(journal.attendees.any { it.email == "alice@example.com" })
            assertTrue(journal.attendees.any { it.email == "bob@example.com" })
        }
    }

    @Nested
    @DisplayName("VJOURNAL Attachments")
    inner class AttachmentsTests {

        @Test
        fun `parse VJOURNAL with attachment URL`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:attach-journal
                DTSTAMP:20231215T120000Z
                ATTACH:https://example.com/files/document.pdf
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertEquals(1, journal.attachments.size)
            assertEquals("https://example.com/files/document.pdf", journal.attachments[0])
        }

        @Test
        fun `parse VJOURNAL with multiple attachments`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:multi-attach-journal
                DTSTAMP:20231215T120000Z
                ATTACH:https://example.com/files/doc1.pdf
                ATTACH:https://example.com/files/doc2.pdf
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertEquals(2, journal.attachments.size)
        }
    }

    @Nested
    @DisplayName("VJOURNAL Recurrence")
    inner class RecurrenceTests {

        @Test
        fun `parse recurring VJOURNAL with RRULE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:recurring-journal
                DTSTAMP:20231215T120000Z
                DTSTART:20231215T000000Z
                RRULE:FREQ=DAILY;COUNT=30
                SUMMARY:Daily Log
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertTrue(journal.isRecurring())
            assertNotNull(journal.rrule)
            assertEquals(Frequency.DAILY, journal.rrule?.freq)
        }

        @Test
        fun `parse modified instance with RECURRENCE-ID`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:recurring-journal
                DTSTAMP:20231215T120000Z
                RECURRENCE-ID:20231218T000000Z
                SUMMARY:Modified Daily Log
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertTrue(journal.isModifiedInstance())
            assertNotNull(journal.recurrenceId)
            assertNull(journal.rrule)
        }

        @Test
        fun `importId includes RECURRENCE-ID for modified instances`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:recurring-journal-456
                DTSTAMP:20231215T120000Z
                RECURRENCE-ID:20231218T000000Z
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            val journal = result.getOrNull()!![0]

            assertTrue(journal.importId.contains("recurring-journal-456"))
            assertTrue(journal.importId.contains("RECID"))
        }
    }

    @Nested
    @DisplayName("Multiple VJOURNALs")
    inner class MultipleJournalsTests {

        @Test
        fun `parse calendar with multiple VJOURNALs`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:journal-1
                DTSTAMP:20231215T120000Z
                SUMMARY:Day 1
                END:VJOURNAL
                BEGIN:VJOURNAL
                UID:journal-2
                DTSTAMP:20231216T120000Z
                SUMMARY:Day 2
                END:VJOURNAL
                BEGIN:VJOURNAL
                UID:journal-3
                DTSTAMP:20231217T120000Z
                SUMMARY:Day 3
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)

            val journals = result.getOrNull()!!
            assertEquals(3, journals.size)
        }

        @Test
        fun `parse calendar with mixed components`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-1
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                SUMMARY:Meeting
                END:VEVENT
                BEGIN:VTODO
                UID:todo-1
                DTSTAMP:20231215T120000Z
                SUMMARY:Task
                END:VTODO
                BEGIN:VJOURNAL
                UID:journal-1
                DTSTAMP:20231215T120000Z
                SUMMARY:Log
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val journalResult = parser.parseAllJournals(ics)
            assertEquals(1, journalResult.getOrNull()!!.size)

            val todoResult = parser.parseAllTodos(ics)
            assertEquals(1, todoResult.getOrNull()!!.size)

            val eventResult = parser.parseAllEvents(ics)
            assertEquals(1, eventResult.getOrNull()!!.size)
        }
    }

    @Nested
    @DisplayName("ICalCalendar Integration")
    inner class ICalCalendarIntegrationTests {

        @Test
        fun `parse() populates journals list in ICalCalendar`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:cal-journal-1
                DTSTAMP:20231215T120000Z
                SUMMARY:Journal 1
                END:VJOURNAL
                BEGIN:VJOURNAL
                UID:cal-journal-2
                DTSTAMP:20231215T120000Z
                SUMMARY:Journal 2
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)

            val calendar = result.getOrNull()!!
            assertEquals(2, calendar.journals.size)
            assertEquals("cal-journal-1", calendar.journals[0].uid)
            assertEquals("cal-journal-2", calendar.journals[1].uid)
        }
    }

    @Nested
    @DisplayName("JournalStatus Enum")
    inner class JournalStatusEnumTests {

        @Test
        fun `JournalStatus toICalString produces correct values`() {
            assertEquals("DRAFT", JournalStatus.DRAFT.toICalString())
            assertEquals("FINAL", JournalStatus.FINAL.toICalString())
            assertEquals("CANCELLED", JournalStatus.CANCELLED.toICalString())
        }

        @Test
        fun `JournalStatus fromString parses all values`() {
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString("DRAFT"))
            assertEquals(JournalStatus.FINAL, JournalStatus.fromString("FINAL"))
            assertEquals(JournalStatus.CANCELLED, JournalStatus.fromString("CANCELLED"))
        }

        @Test
        fun `JournalStatus fromString is case-insensitive`() {
            assertEquals(JournalStatus.FINAL, JournalStatus.fromString("final"))
            assertEquals(JournalStatus.FINAL, JournalStatus.fromString("Final"))
            assertEquals(JournalStatus.CANCELLED, JournalStatus.fromString("cancelled"))
        }

        @Test
        fun `JournalStatus fromString returns DRAFT for unknown`() {
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString("UNKNOWN"))
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString(null))
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `parse VJOURNAL without UID gets generated UUID`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                DTSTAMP:20231215T120000Z
                SUMMARY:No UID Journal
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)
            val journals = result.getOrNull()!!
            assertEquals(1, journals.size)
            assertTrue(journals[0].uid.isNotBlank(), "Generated UID should not be blank")
        }

        @Test
        fun `parse empty calendar returns empty list`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllJournals(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(0, result.getOrNull()!!.size)
        }
    }
}
