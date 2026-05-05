package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.WeekdayNum
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive tests for VTODO generation per RFC 5545 Section 3.6.2.
 */
@DisplayName("ICalGenerator VTODO Tests")
class ICalGeneratorVTodoTest {
    private val generator = ICalGenerator()
    private val parser = ICalParser()

    private fun createBasicTodo(
        uid: String = "test-todo-123",
        summary: String? = "Test Task",
        status: TodoStatus = TodoStatus.NEEDS_ACTION
    ): ICalTodo {
        return ICalTodo(
            uid = uid,
            summary = summary,
            status = status,
            dtstamp = ICalDateTime.parse("20231215T120000Z")
        )
    }

    @Nested
    @DisplayName("Basic VTODO Generation")
    inner class BasicGenerationTests {

        @Test
        fun `generate minimal VTODO`() {
            val todo = createBasicTodo()

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("BEGIN:VCALENDAR"))
            assertTrue(ics.contains("VERSION:2.0"))
            assertTrue(ics.contains("BEGIN:VTODO"))
            assertTrue(ics.contains("UID:test-todo-123"))
            assertTrue(ics.contains("END:VTODO"))
            assertTrue(ics.contains("END:VCALENDAR"))
        }

        @Test
        fun `generate VTODO with summary`() {
            val todo = createBasicTodo(summary = "My Important Task")

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("SUMMARY:My Important Task"))
        }

        @Test
        fun `generate VTODO escapes special characters in summary`() {
            val todo = createBasicTodo(summary = "Task with, comma and; semicolon")

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("SUMMARY:Task with\\, comma and\\; semicolon"))
        }

        @Test
        fun `generate VTODO with description`() {
            val todo = ICalTodo(
                uid = "desc-todo",
                summary = "Task",
                description = "This is a detailed description",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("DESCRIPTION:This is a detailed description"))
        }

        @Test
        fun `generate VTODO with multiline description`() {
            val todo = ICalTodo(
                uid = "multiline-todo",
                summary = "Task",
                description = "Line1\nLine2\nLine3",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("DESCRIPTION:Line1\\nLine2\\nLine3"))
        }
    }

    @Nested
    @DisplayName("Status Generation")
    inner class StatusGenerationTests {

        @Test
        fun `generate VTODO with NEEDS-ACTION status`() {
            val todo = createBasicTodo(status = TodoStatus.NEEDS_ACTION)

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("STATUS:NEEDS-ACTION"))
        }

        @Test
        fun `generate VTODO with IN-PROCESS status`() {
            val todo = createBasicTodo(status = TodoStatus.IN_PROCESS)

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("STATUS:IN-PROCESS"))
        }

        @Test
        fun `generate VTODO with COMPLETED status`() {
            val todo = ICalTodo(
                uid = "completed-todo",
                summary = "Done Task",
                status = TodoStatus.COMPLETED,
                completed = ICalDateTime.parse("20231220T150000Z"),
                percentComplete = 100,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("STATUS:COMPLETED"))
            assertTrue(ics.contains("COMPLETED:20231220T150000Z"))
            assertTrue(ics.contains("PERCENT-COMPLETE:100"))
        }

        @Test
        fun `generate VTODO with CANCELLED status`() {
            val todo = createBasicTodo(status = TodoStatus.CANCELLED)

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("STATUS:CANCELLED"))
        }
    }

    @Nested
    @DisplayName("DateTime Generation")
    inner class DateTimeGenerationTests {

        @Test
        fun `generate VTODO with DTSTART`() {
            val todo = ICalTodo(
                uid = "dtstart-todo",
                summary = "Task",
                dtStart = ICalDateTime.parse("20231216T090000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("DTSTART:20231216T090000Z"))
        }

        @Test
        fun `generate VTODO with DUE`() {
            val todo = ICalTodo(
                uid = "due-todo",
                summary = "Task",
                due = ICalDateTime.parse("20231220T170000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("DUE:20231220T170000Z"))
        }

        @Test
        fun `generate VTODO with all datetime properties`() {
            val todo = ICalTodo(
                uid = "all-dt-todo",
                summary = "Task",
                dtStart = ICalDateTime.parse("20231216T090000Z"),
                due = ICalDateTime.parse("20231220T170000Z"),
                completed = ICalDateTime.parse("20231219T150000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z"),
                created = ICalDateTime.parse("20231210T080000Z"),
                lastModified = ICalDateTime.parse("20231218T140000Z")
            )

            val ics = generator.generate(todo, preserveDtstamp = true)

            assertTrue(ics.contains("DTSTART:20231216T090000Z"))
            assertTrue(ics.contains("DUE:20231220T170000Z"))
            assertTrue(ics.contains("COMPLETED:20231219T150000Z"))
            assertTrue(ics.contains("DTSTAMP:20231215T120000Z"))
            assertTrue(ics.contains("CREATED:20231210T080000Z"))
            assertTrue(ics.contains("LAST-MODIFIED:20231218T140000Z"))
        }
    }

    @Nested
    @DisplayName("Numeric Properties Generation")
    inner class NumericPropertiesTests {

        @Test
        fun `generate VTODO with priority`() {
            val todo = ICalTodo(
                uid = "priority-todo",
                summary = "High Priority Task",
                priority = 1,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("PRIORITY:1"))
        }

        @Test
        fun `generate VTODO does not include priority 0`() {
            val todo = ICalTodo(
                uid = "no-priority-todo",
                summary = "Normal Task",
                priority = 0,  // Undefined priority
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertFalse(ics.contains("PRIORITY:0"))
        }

        @Test
        fun `generate VTODO with percent complete`() {
            val todo = ICalTodo(
                uid = "percent-todo",
                summary = "In Progress Task",
                percentComplete = 75,
                status = TodoStatus.IN_PROCESS,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("PERCENT-COMPLETE:75"))
        }

        @Test
        fun `generate VTODO does not include percent complete 0`() {
            val todo = ICalTodo(
                uid = "no-percent-todo",
                summary = "Not Started",
                percentComplete = 0,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertFalse(ics.contains("PERCENT-COMPLETE:0"))
        }

        @Test
        fun `generate VTODO with sequence`() {
            val todo = ICalTodo(
                uid = "sequence-todo",
                summary = "Updated Task",
                sequence = 5,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("SEQUENCE:5"))
        }
    }

    @Nested
    @DisplayName("Categories Generation")
    inner class CategoriesGenerationTests {

        @Test
        fun `generate VTODO with single category`() {
            val todo = ICalTodo(
                uid = "single-cat-todo",
                summary = "Work Task",
                categories = listOf("Work"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("CATEGORIES:Work"))
        }

        @Test
        fun `generate VTODO with multiple categories`() {
            val todo = ICalTodo(
                uid = "multi-cat-todo",
                summary = "Complex Task",
                categories = listOf("Work", "Urgent", "Project-X"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("CATEGORIES:Work,Urgent,Project-X"))
        }

        @Test
        fun `generate VTODO with empty categories omits property`() {
            val todo = ICalTodo(
                uid = "no-cat-todo",
                summary = "Task",
                categories = emptyList(),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertFalse(ics.contains("CATEGORIES:"))
        }
    }

    @Nested
    @DisplayName("Location and Text Properties")
    inner class LocationTextPropertiesTests {

        @Test
        fun `generate VTODO with location`() {
            val todo = ICalTodo(
                uid = "location-todo",
                summary = "Office Task",
                location = "Conference Room A",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("LOCATION:Conference Room A"))
        }

        @Test
        fun `generate VTODO with URL`() {
            val todo = ICalTodo(
                uid = "url-todo",
                summary = "Task with Link",
                url = "https://example.com/task/123",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("URL:https://example.com/task/123"))
        }

        @Test
        fun `generate VTODO with GEO`() {
            val todo = ICalTodo(
                uid = "geo-todo",
                summary = "Location Task",
                geo = "37.386013;-122.082932",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("GEO:37.386013;-122.082932"))
        }

        @Test
        fun `generate VTODO with CLASS`() {
            val todo = ICalTodo(
                uid = "class-todo",
                summary = "Confidential Task",
                classification = "CONFIDENTIAL",
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("CLASS:CONFIDENTIAL"))
        }
    }

    @Nested
    @DisplayName("Organizer and Attendees Generation")
    inner class OrganizerAttendeesTests {

        @Test
        fun `generate VTODO with organizer`() {
            val todo = ICalTodo(
                uid = "org-todo",
                summary = "Assigned Task",
                organizer = Organizer(
                    email = "manager@example.com",
                    name = "John Manager",
                    sentBy = null
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("ORGANIZER"))
            assertTrue(ics.contains("manager@example.com"))
            assertTrue(ics.contains("John Manager"))
        }

        @Test
        fun `generate VTODO with attendees`() {
            val todo = ICalTodo(
                uid = "attendee-todo",
                summary = "Team Task",
                attendees = listOf(
                    Attendee(
                        email = "alice@example.com",
                        name = "Alice",
                        partStat = PartStat.ACCEPTED,
                        role = AttendeeRole.REQ_PARTICIPANT,
                        rsvp = true
                    ),
                    Attendee(
                        email = "bob@example.com",
                        name = "Bob",
                        partStat = PartStat.NEEDS_ACTION,
                        role = AttendeeRole.OPT_PARTICIPANT,
                        rsvp = false
                    )
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("ATTENDEE"))
            assertTrue(ics.contains("alice@example.com"))
            assertTrue(ics.contains("bob@example.com"))
        }
    }

    @Nested
    @DisplayName("Recurrence Generation")
    inner class RecurrenceGenerationTests {

        @Test
        fun `generate recurring VTODO with RRULE`() {
            val todo = ICalTodo(
                uid = "recurring-todo",
                summary = "Weekly Task",
                dtStart = ICalDateTime.parse("20231215T090000Z"),
                rrule = RRule(
                    freq = Frequency.WEEKLY,
                    interval = 1,
                    byDay = listOf(WeekdayNum(java.time.DayOfWeek.MONDAY))
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("RRULE:FREQ=WEEKLY"))
        }

        @Test
        fun `generate modified instance with RECURRENCE-ID`() {
            val todo = ICalTodo(
                uid = "recurring-todo",
                summary = "Modified Instance",
                recurrenceId = ICalDateTime.parse("20231218T090000Z"),
                dtStart = ICalDateTime.parse("20231218T100000Z"),
                due = ICalDateTime.parse("20231218T180000Z"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("RECURRENCE-ID:20231218T090000Z"))
            assertFalse(ics.contains("RRULE:"))  // Instances don't have RRULE
        }
    }

    @Nested
    @DisplayName("iTIP Method Generation")
    inner class ITipMethodTests {

        @Test
        fun `generate VTODO with METHOD REQUEST`() {
            val todo = createBasicTodo()

            val ics = generator.generate(todo, ITipMethod.REQUEST)

            assertTrue(ics.contains("METHOD:REQUEST"))
        }

        @Test
        fun `generate VTODO with METHOD CANCEL`() {
            val todo = createBasicTodo()

            val ics = generator.generate(todo, ITipMethod.CANCEL)

            assertTrue(ics.contains("METHOD:CANCEL"))
        }

        @Test
        fun `generate VTODO without METHOD`() {
            val todo = createBasicTodo()

            val ics = generator.generate(todo, null)

            assertFalse(ics.contains("METHOD:"))
        }
    }

    @Nested
    @DisplayName("Round-Trip Tests")
    inner class RoundTripTests {

        @Test
        fun `round-trip minimal VTODO`() {
            val original = ICalTodo(
                uid = "roundtrip-todo-1",
                summary = "Test Task",
                status = TodoStatus.NEEDS_ACTION,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(original, method = null)
            val parsed = parser.parseAllTodos(ics).getOrNull()!![0]

            assertEquals(original.uid, parsed.uid)
            assertEquals(original.summary, parsed.summary)
            assertEquals(original.status, parsed.status)
        }

        @Test
        fun `round-trip complex VTODO`() {
            val original = ICalTodo(
                uid = "roundtrip-todo-2",
                summary = "Complex Task",
                description = "Detailed description",
                location = "Office",
                due = ICalDateTime.parse("20231220T170000Z"),
                dtStart = ICalDateTime.parse("20231216T090000Z"),
                priority = 1,
                percentComplete = 50,
                status = TodoStatus.IN_PROCESS,
                sequence = 3,
                categories = listOf("Work", "Urgent"),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(original, method = null)
            val parsed = parser.parseAllTodos(ics).getOrNull()!![0]

            assertEquals(original.uid, parsed.uid)
            assertEquals(original.summary, parsed.summary)
            assertEquals(original.description, parsed.description)
            assertEquals(original.location, parsed.location)
            assertEquals(original.priority, parsed.priority)
            assertEquals(original.percentComplete, parsed.percentComplete)
            assertEquals(original.status, parsed.status)
            assertEquals(original.sequence, parsed.sequence)
            assertEquals(original.categories, parsed.categories)
        }

        @Test
        fun `round-trip VTODO with organizer and attendees`() {
            val original = ICalTodo(
                uid = "roundtrip-todo-3",
                summary = "Team Task",
                organizer = Organizer(
                    email = "manager@example.com",
                    name = "Manager",
                    sentBy = null
                ),
                attendees = listOf(
                    Attendee(
                        email = "worker@example.com",
                        name = "Worker",
                        partStat = PartStat.ACCEPTED,
                        role = AttendeeRole.REQ_PARTICIPANT,
                        rsvp = false
                    )
                ),
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(original, method = null)
            val parsed = parser.parseAllTodos(ics).getOrNull()!![0]

            assertEquals(original.organizer?.email, parsed.organizer?.email)
            assertEquals(1, parsed.attendees.size)
            assertEquals(original.attendees[0].email, parsed.attendees[0].email)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `generate VTODO with null summary`() {
            val todo = ICalTodo(
                uid = "no-summary-todo",
                summary = null,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("UID:no-summary-todo"))
            assertFalse(ics.contains("SUMMARY:"))
        }

        @Test
        fun `generate VTODO with all null optional properties`() {
            val todo = ICalTodo(uid = "minimal-todo")

            val ics = generator.generate(todo, method = null)

            assertTrue(ics.contains("BEGIN:VTODO"))
            assertTrue(ics.contains("UID:minimal-todo"))
            assertTrue(ics.contains("END:VTODO"))
        }

        @Test
        fun `generate VTODO with long description uses folding`() {
            val longDesc = "A".repeat(200)
            val todo = ICalTodo(
                uid = "long-desc-todo",
                description = longDesc,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val ics = generator.generate(todo, method = null)

            // The description should be folded per RFC 5545
            assertTrue(ics.contains("DESCRIPTION:"))
        }
    }
}
