package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.RRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive tests for VJOURNAL generation per RFC 5545 Section 3.6.3.
 */
@DisplayName("ICalGenerator VJOURNAL Tests")
class ICalGeneratorVJournalTest {
    private val generator = ICalGenerator()
    private val parser = ICalParser()

    private fun createBasicJournal(
        uid: String = "test-journal-123",
        summary: String? = "Test Journal",
        status: JournalStatus = JournalStatus.DRAFT
    ): ICalJournal {
        return ICalJournal(
            uid = uid,
            summary = summary,
            status = status,
            dtstamp = ICalDateTime.parse("20231215T120000Z")
        )
    }

    @Nested
    @DisplayName("Basic VJOURNAL Generation")
    inner class BasicGenerationTests {

        @Test
        fun `generate minimal VJOURNAL`() {
            val journal = createBasicJournal()

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("BEGIN:VCALENDAR"))
            assertTrue(ics.contains("VERSION:2.0"))
            assertTrue(ics.contains("BEGIN:VJOURNAL"))
            assertTrue(ics.contains("UID:test-journal-123"))
            assertTrue(ics.contains("END:VJOURNAL"))
            assertTrue(ics.contains("END:VCALENDAR"))
        }

        @Test
        fun `generate VJOURNAL with summary`() {
            val journal = createBasicJournal(summary = "Daily Progress Report")

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("SUMMARY:Daily Progress Report"))
        }

        @Test
        fun `generate VJOURNAL escapes special characters`() {
            val journal = createBasicJournal(summary = "Notes, with special; chars")

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("SUMMARY:Notes\\, with special\\; chars"))
        }

        @Test
        fun `generate VJOURNAL with description`() {
            val journal = ICalJournal(
                uid = "desc-journal",
                summary = "Journal",
                description = "Detailed notes for the day",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("DESCRIPTION:Detailed notes for the day"))
        }

        @Test
        fun `generate VJOURNAL with multiline description`() {
            val journal = ICalJournal(
                uid = "multiline-journal",
                summary = "Journal",
                description = "Line1\nLine2\nLine3",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("DESCRIPTION:Line1\\nLine2\\nLine3"))
        }
    }

    @Nested
    @DisplayName("Status Generation")
    inner class StatusGenerationTests {

        @Test
        fun `generate VJOURNAL with DRAFT status`() {
            val journal = createBasicJournal(status = JournalStatus.DRAFT)

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("STATUS:DRAFT"))
        }

        @Test
        fun `generate VJOURNAL with FINAL status`() {
            val journal = createBasicJournal(status = JournalStatus.FINAL)

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("STATUS:FINAL"))
        }

        @Test
        fun `generate VJOURNAL with CANCELLED status`() {
            val journal = createBasicJournal(status = JournalStatus.CANCELLED)

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("STATUS:CANCELLED"))
        }
    }

    @Nested
    @DisplayName("DateTime Generation")
    inner class DateTimeGenerationTests {

        @Test
        fun `generate VJOURNAL with DTSTART`() {
            val journal = ICalJournal(
                uid = "dtstart-journal",
                summary = "Journal",
                dtStart = ICalDateTime.parse("20231215T000000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("DTSTART:20231215T000000Z"))
        }

        @Test
        fun `generate VJOURNAL with all datetime properties`() {
            val journal = ICalJournal(
                uid = "all-dt-journal",
                summary = "Journal",
                dtStart = ICalDateTime.parse("20231215T000000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z"),
                created = ICalDateTime.parse("20231210T080000Z"),
                lastModified = ICalDateTime.parse("20231218T140000Z")
            )

            val ics = generator.generate(journal, preserveDtstamp = true)

            assertTrue(ics.contains("DTSTART:20231215T000000Z"))
            assertTrue(ics.contains("DTSTAMP:20231215T120000Z"))
            assertTrue(ics.contains("CREATED:20231210T080000Z"))
            assertTrue(ics.contains("LAST-MODIFIED:20231218T140000Z"))
        }
    }

    @Nested
    @DisplayName("Categories Generation")
    inner class CategoriesGenerationTests {

        @Test
        fun `generate VJOURNAL with single category`() {
            val journal = ICalJournal(
                uid = "single-cat-journal",
                summary = "Journal",
                categories = listOf("Personal"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("CATEGORIES:Personal"))
        }

        @Test
        fun `generate VJOURNAL with multiple categories`() {
            val journal = ICalJournal(
                uid = "multi-cat-journal",
                summary = "Journal",
                categories = listOf("Work", "Daily", "Progress"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("CATEGORIES:Work,Daily,Progress"))
        }

        @Test
        fun `generate VJOURNAL with empty categories omits property`() {
            val journal = ICalJournal(
                uid = "no-cat-journal",
                summary = "Journal",
                categories = emptyList(),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertFalse(ics.contains("CATEGORIES:"))
        }
    }

    @Nested
    @DisplayName("Organizer and Attendees Generation")
    inner class OrganizerAttendeesTests {

        @Test
        fun `generate VJOURNAL with organizer`() {
            val journal = ICalJournal(
                uid = "org-journal",
                summary = "Journal",
                organizer = Organizer(
                    email = "author@example.com",
                    name = "Journal Author",
                    sentBy = null
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("ORGANIZER"))
            assertTrue(ics.contains("author@example.com"))
        }

        @Test
        fun `generate VJOURNAL with attendees`() {
            val journal = ICalJournal(
                uid = "attendee-journal",
                summary = "Team Journal",
                attendees = listOf(
                    Attendee(
                        email = "alice@example.com",
                        name = "Alice",
                        partStat = PartStat.ACCEPTED,
                        role = AttendeeRole.REQ_PARTICIPANT,
                        rsvp = false
                    )
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("ATTENDEE"))
            assertTrue(ics.contains("alice@example.com"))
        }
    }

    @Nested
    @DisplayName("Attachments Generation")
    inner class AttachmentsGenerationTests {

        @Test
        fun `generate VJOURNAL with attachment`() {
            val journal = ICalJournal(
                uid = "attach-journal",
                summary = "Journal",
                attachments = listOf("https://example.com/files/document.pdf"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("ATTACH:https://example.com/files/document.pdf"))
        }

        @Test
        fun `generate VJOURNAL with multiple attachments`() {
            val journal = ICalJournal(
                uid = "multi-attach-journal",
                summary = "Journal",
                attachments = listOf(
                    "https://example.com/files/doc1.pdf",
                    "https://example.com/files/doc2.pdf"
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            val attachCount = ics.split("ATTACH:").size - 1
            assertEquals(2, attachCount)
        }
    }

    @Nested
    @DisplayName("Recurrence Generation")
    inner class RecurrenceGenerationTests {

        @Test
        fun `generate recurring VJOURNAL with RRULE`() {
            val journal = ICalJournal(
                uid = "recurring-journal",
                summary = "Daily Log",
                dtStart = ICalDateTime.parse("20231215T000000Z"),
                rrule = RRule(
                    freq = Frequency.DAILY,
                    count = 30
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("RRULE:FREQ=DAILY"))
        }

        @Test
        fun `generate modified instance with RECURRENCE-ID`() {
            val journal = ICalJournal(
                uid = "recurring-journal",
                summary = "Modified Log",
                recurrenceId = ICalDateTime.parse("20231218T000000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("RECURRENCE-ID:20231218T000000Z"))
            assertFalse(ics.contains("RRULE:"))
        }
    }

    @Nested
    @DisplayName("Text Properties Generation")
    inner class TextPropertiesTests {

        @Test
        fun `generate VJOURNAL with URL`() {
            val journal = ICalJournal(
                uid = "url-journal",
                summary = "Journal",
                url = "https://example.com/journal/123",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("URL:https://example.com/journal/123"))
        }

        @Test
        fun `generate VJOURNAL with CLASS`() {
            val journal = ICalJournal(
                uid = "class-journal",
                summary = "Private Journal",
                classification = "PRIVATE",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("CLASS:PRIVATE"))
        }

        @Test
        fun `generate VJOURNAL with SEQUENCE`() {
            val journal = ICalJournal(
                uid = "sequence-journal",
                summary = "Updated Journal",
                sequence = 3,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("SEQUENCE:3"))
        }
    }

    @Nested
    @DisplayName("iTIP Method Generation")
    inner class ITipMethodTests {

        @Test
        fun `generate VJOURNAL with METHOD PUBLISH`() {
            val journal = createBasicJournal()

            val ics = generator.generate(journal, ITipMethod.PUBLISH)

            assertTrue(ics.contains("METHOD:PUBLISH"))
        }

        @Test
        fun `generate VJOURNAL without METHOD`() {
            val journal = createBasicJournal()

            val ics = generator.generate(journal, null)

            assertFalse(ics.contains("METHOD:"))
        }
    }

    @Nested
    @DisplayName("Round-Trip Tests")
    inner class RoundTripTests {

        @Test
        fun `round-trip minimal VJOURNAL`() {
            val original = ICalJournal(
                uid = "roundtrip-journal-1",
                summary = "Test Journal",
                status = JournalStatus.DRAFT,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(original, method = null)
            val parsed = parser.parseAllJournals(ics).getOrNull()!![0]

            assertEquals(original.uid, parsed.uid)
            assertEquals(original.summary, parsed.summary)
            assertEquals(original.status, parsed.status)
        }

        @Test
        fun `round-trip complex VJOURNAL`() {
            val original = ICalJournal(
                uid = "roundtrip-journal-2",
                summary = "Complex Journal",
                description = "Detailed description",
                dtStart = ICalDateTime.parse("20231215T000000Z"),
                status = JournalStatus.FINAL,
                sequence = 2,
                categories = listOf("Work", "Progress"),
                url = "https://example.com/journal",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(original, method = null)
            val parsed = parser.parseAllJournals(ics).getOrNull()!![0]

            assertEquals(original.uid, parsed.uid)
            assertEquals(original.summary, parsed.summary)
            assertEquals(original.description, parsed.description)
            assertEquals(original.status, parsed.status)
            assertEquals(original.sequence, parsed.sequence)
            assertEquals(original.categories, parsed.categories)
            assertEquals(original.url, parsed.url)
        }

        @Test
        fun `round-trip VJOURNAL with attachments`() {
            val original = ICalJournal(
                uid = "roundtrip-journal-3",
                summary = "Journal with Attachments",
                attachments = listOf(
                    "https://example.com/file1.pdf",
                    "https://example.com/file2.pdf"
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(original, method = null)
            val parsed = parser.parseAllJournals(ics).getOrNull()!![0]

            assertEquals(2, parsed.attachments.size)
            assertEquals(original.attachments, parsed.attachments)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `generate VJOURNAL with null summary`() {
            val journal = ICalJournal(
                uid = "no-summary-journal",
                summary = null,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("UID:no-summary-journal"))
            assertFalse(ics.contains("SUMMARY:"))
        }

        @Test
        fun `generate VJOURNAL with minimal properties`() {
            val journal = ICalJournal(uid = "minimal-journal")

            val ics = generator.generate(journal, method = null)

            assertTrue(ics.contains("BEGIN:VJOURNAL"))
            assertTrue(ics.contains("UID:minimal-journal"))
            assertTrue(ics.contains("END:VJOURNAL"))
        }
    }
}
