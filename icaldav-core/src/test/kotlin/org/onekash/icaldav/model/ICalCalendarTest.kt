package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import java.time.Duration

/**
 * Comprehensive tests for ICalCalendar model per RFC 5545 and RFC 7986.
 *
 * Tests cover:
 * - Calendar construction
 * - RFC 5545 properties (PRODID, VERSION, CALSCALE, METHOD)
 * - RFC 7986 extensions (NAME, SOURCE, COLOR, REFRESH-INTERVAL, IMAGE)
 * - Non-standard properties (X-WR-CALNAME, X-APPLE-CALENDAR-COLOR)
 * - Component access (events, todos, journals)
 * - Edge cases
 */
class ICalCalendarTest {

    // ==================== Test Data Helpers ====================

    private fun createMinimalCalendar(): ICalCalendar {
        return ICalCalendar(prodId = "-//Test//EN")
    }

    private fun createTestEvent(uid: String = "event-1"): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Test Event",
            description = null,
            location = null,
            dtStart = ICalDateTime.parse("20231215T100000Z"),
            dtEnd = ICalDateTime.parse("20231215T110000Z"),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun createTestTodo(uid: String = "todo-1"): ICalTodo {
        return ICalTodo(
            uid = uid,
            summary = "Test Task",
            status = TodoStatus.NEEDS_ACTION
        )
    }

    private fun createTestJournal(uid: String = "journal-1"): ICalJournal {
        return ICalJournal(
            uid = uid,
            summary = "Test Journal",
            status = JournalStatus.DRAFT
        )
    }

    // ==================== Basic Construction Tests ====================

    @Nested
    inner class BasicConstructionTests {

        @Test
        fun `create minimal calendar`() {
            val calendar = createMinimalCalendar()

            assertEquals("-//Test//EN", calendar.prodId)
            assertEquals("2.0", calendar.version)
            assertEquals("GREGORIAN", calendar.calscale)
            assertNull(calendar.method)
            assertTrue(calendar.events.isEmpty())
            assertTrue(calendar.todos.isEmpty())
            assertTrue(calendar.journals.isEmpty())
        }

        @Test
        fun `create calendar with factory method`() {
            val calendar = ICalCalendar.create(
                prodId = "-//MyApp//Calendar//EN",
                name = "My Calendar"
            )

            assertEquals("-//MyApp//Calendar//EN", calendar.prodId)
            assertEquals("My Calendar", calendar.name)
        }

        @Test
        fun `create calendar with default prodId`() {
            val calendar = ICalCalendar.create()
            assertEquals("-//iCalDAV//EN", calendar.prodId)
        }

        @Test
        fun `create calendar with version`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                version = "2.0"
            )

            assertEquals("2.0", calendar.version)
        }

        @Test
        fun `create calendar with calscale`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                calscale = "GREGORIAN"
            )

            assertEquals("GREGORIAN", calendar.calscale)
        }

        @Test
        fun `create calendar with method`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                method = "REQUEST"
            )

            assertEquals("REQUEST", calendar.method)
        }
    }

    // ==================== RFC 7986 Extension Tests ====================

    @Nested
    inner class Rfc7986ExtensionTests {

        @Test
        fun `calendar with NAME property`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                name = "Work Calendar"
            )

            assertEquals("Work Calendar", calendar.name)
            assertEquals("Work Calendar", calendar.effectiveName)
        }

        @Test
        fun `calendar with SOURCE property`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                source = "https://example.com/calendar.ics"
            )

            assertEquals("https://example.com/calendar.ics", calendar.source)
        }

        @Test
        fun `calendar with COLOR property`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                color = "crimson"
            )

            assertEquals("crimson", calendar.color)
            assertEquals("crimson", calendar.effectiveColor)
        }

        @Test
        fun `calendar with REFRESH-INTERVAL property`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                refreshInterval = Duration.ofDays(1)
            )

            assertEquals(Duration.ofDays(1), calendar.refreshInterval)
        }

        @Test
        fun `calendar with IMAGE property`() {
            val image = ICalImage(uri = "https://example.com/icon.png")
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                image = image
            )

            assertNotNull(calendar.image)
            assertEquals("https://example.com/icon.png", calendar.image?.uri)
        }
    }

    // ==================== Non-Standard Property Tests ====================

    @Nested
    inner class NonStandardPropertyTests {

        @Test
        fun `calendar with X-WR-CALNAME`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                xWrCalname = "Personal Calendar"
            )

            assertEquals("Personal Calendar", calendar.xWrCalname)
        }

        @Test
        fun `calendar with X-APPLE-CALENDAR-COLOR`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                xAppleCalendarColor = "#FF0000"
            )

            assertEquals("#FF0000", calendar.xAppleCalendarColor)
        }
    }

    // ==================== Effective Name/Color Tests ====================

    @Nested
    inner class EffectivePropertyTests {

        @Test
        fun `effectiveName prefers NAME over X-WR-CALNAME`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                name = "RFC 7986 Name",
                xWrCalname = "Legacy Name"
            )

            assertEquals("RFC 7986 Name", calendar.effectiveName)
        }

        @Test
        fun `effectiveName falls back to X-WR-CALNAME`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                name = null,
                xWrCalname = "Legacy Name"
            )

            assertEquals("Legacy Name", calendar.effectiveName)
        }

        @Test
        fun `effectiveName returns null when both absent`() {
            val calendar = createMinimalCalendar()
            assertNull(calendar.effectiveName)
        }

        @Test
        fun `effectiveColor prefers COLOR over X-APPLE-CALENDAR-COLOR`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                color = "#00FF00",
                xAppleCalendarColor = "#FF0000"
            )

            assertEquals("#00FF00", calendar.effectiveColor)
        }

        @Test
        fun `effectiveColor falls back to X-APPLE-CALENDAR-COLOR`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                color = null,
                xAppleCalendarColor = "#FF0000"
            )

            assertEquals("#FF0000", calendar.effectiveColor)
        }

        @Test
        fun `effectiveColor returns null when both absent`() {
            val calendar = createMinimalCalendar()
            assertNull(calendar.effectiveColor)
        }
    }

    // ==================== Component Access Tests ====================

    @Nested
    inner class ComponentAccessTests {

        @Test
        fun `calendar with events`() {
            val events = listOf(createTestEvent("event-1"), createTestEvent("event-2"))
            val calendar = createMinimalCalendar().copy(events = events)

            assertEquals(2, calendar.events.size)
            assertTrue(calendar.hasEvents())
        }

        @Test
        fun `calendar with todos`() {
            val todos = listOf(createTestTodo("todo-1"), createTestTodo("todo-2"))
            val calendar = createMinimalCalendar().copy(todos = todos)

            assertEquals(2, calendar.todos.size)
            assertTrue(calendar.hasTodos())
        }

        @Test
        fun `calendar with journals`() {
            val journals = listOf(createTestJournal("journal-1"))
            val calendar = createMinimalCalendar().copy(journals = journals)

            assertEquals(1, calendar.journals.size)
            assertTrue(calendar.hasJournals())
        }

        @Test
        fun `calendar with no events returns false for hasEvents`() {
            val calendar = createMinimalCalendar()
            assertFalse(calendar.hasEvents())
        }

        @Test
        fun `calendar with no todos returns false for hasTodos`() {
            val calendar = createMinimalCalendar()
            assertFalse(calendar.hasTodos())
        }

        @Test
        fun `calendar with no journals returns false for hasJournals`() {
            val calendar = createMinimalCalendar()
            assertFalse(calendar.hasJournals())
        }

        @Test
        fun `componentCount returns sum of all components`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                events = listOf(createTestEvent("e1"), createTestEvent("e2")),
                todos = listOf(createTestTodo("t1")),
                journals = listOf(createTestJournal("j1"), createTestJournal("j2"))
            )

            assertEquals(5, calendar.componentCount)
        }

        @Test
        fun `componentCount returns zero for empty calendar`() {
            val calendar = createMinimalCalendar()
            assertEquals(0, calendar.componentCount)
        }
    }

    // ==================== iTIP Method Tests ====================

    @Nested
    inner class MethodTests {

        @Test
        fun `calendar with REQUEST method`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                method = "REQUEST"
            )
            assertEquals("REQUEST", calendar.method)
        }

        @Test
        fun `calendar with REPLY method`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                method = "REPLY"
            )
            assertEquals("REPLY", calendar.method)
        }

        @Test
        fun `calendar with CANCEL method`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                method = "CANCEL"
            )
            assertEquals("CANCEL", calendar.method)
        }

        @Test
        fun `calendar with PUBLISH method`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                method = "PUBLISH"
            )
            assertEquals("PUBLISH", calendar.method)
        }

        @Test
        fun `calendar without method`() {
            val calendar = createMinimalCalendar()
            assertNull(calendar.method)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    inner class EdgeCases {

        @Test
        fun `calendar with null prodId`() {
            val calendar = ICalCalendar(prodId = null)
            assertNull(calendar.prodId)
        }

        @Test
        fun `calendar with all optional fields`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                version = "2.0",
                calscale = "GREGORIAN",
                method = "PUBLISH",
                name = "Full Calendar",
                source = "https://example.com/cal.ics",
                color = "blue",
                refreshInterval = Duration.ofHours(6),
                xWrCalname = "Legacy Name",
                xAppleCalendarColor = "#0000FF",
                image = ICalImage(uri = "https://example.com/icon.png"),
                events = listOf(createTestEvent()),
                todos = listOf(createTestTodo()),
                journals = listOf(createTestJournal())
            )

            assertNotNull(calendar.prodId)
            assertNotNull(calendar.name)
            assertNotNull(calendar.source)
            assertNotNull(calendar.color)
            assertNotNull(calendar.refreshInterval)
            assertNotNull(calendar.image)
            assertEquals(3, calendar.componentCount)
        }

        @Test
        fun `data class equality`() {
            val cal1 = ICalCalendar(prodId = "-//Test//EN", name = "Test")
            val cal2 = ICalCalendar(prodId = "-//Test//EN", name = "Test")

            assertEquals(cal1, cal2)
        }

        @Test
        fun `data class copy`() {
            val original = ICalCalendar(prodId = "-//Test//EN")
            val modified = original.copy(name = "New Name")

            assertNull(original.name)
            assertEquals("New Name", modified.name)
        }

        @Test
        fun `calendar with empty events list`() {
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                events = emptyList()
            )

            assertTrue(calendar.events.isEmpty())
            assertFalse(calendar.hasEvents())
        }

        @Test
        fun `calendar with multiple events of same type`() {
            val events = (1..10).map { createTestEvent("event-$it") }
            val calendar = ICalCalendar(
                prodId = "-//Test//EN",
                events = events
            )

            assertEquals(10, calendar.events.size)
        }
    }

    // ==================== ICalTodo in Calendar Tests ====================

    @Nested
    inner class ICalTodoTests {

        @Test
        fun `todo with all basic properties`() {
            val todo = ICalTodo(
                uid = "todo-123",
                summary = "Complete report",
                description = "Finish the quarterly report",
                due = ICalDateTime.parse("20231220T170000Z"),
                status = TodoStatus.IN_PROCESS,
                priority = 1,
                percentComplete = 50
            )

            assertEquals("todo-123", todo.uid)
            assertEquals("Complete report", todo.summary)
            assertEquals(TodoStatus.IN_PROCESS, todo.status)
            assertEquals(1, todo.priority)
            assertEquals(50, todo.percentComplete)
        }

        @Test
        fun `todo isOverdue returns true for past due date`() {
            val pastDue = ICalDateTime.fromTimestamp(
                System.currentTimeMillis() - 86400000, // Yesterday
                null,
                false
            )
            val todo = ICalTodo(
                uid = "todo-1",
                due = pastDue,
                status = TodoStatus.NEEDS_ACTION
            )

            assertTrue(todo.isOverdue())
        }

        @Test
        fun `todo isOverdue returns false for completed task`() {
            val pastDue = ICalDateTime.fromTimestamp(
                System.currentTimeMillis() - 86400000,
                null,
                false
            )
            val todo = ICalTodo(
                uid = "todo-1",
                due = pastDue,
                status = TodoStatus.COMPLETED
            )

            assertFalse(todo.isOverdue())
        }

        @Test
        fun `todo isOverdue returns false when no due date`() {
            val todo = ICalTodo(
                uid = "todo-1",
                status = TodoStatus.NEEDS_ACTION
            )

            assertFalse(todo.isOverdue())
        }

        @Test
        fun `todo isRecurring returns true with rrule`() {
            val todo = ICalTodo(
                uid = "todo-1",
                rrule = RRule(freq = Frequency.WEEKLY)
            )

            assertTrue(todo.isRecurring())
        }

        @Test
        fun `TodoStatus fromString parses all values`() {
            assertEquals(TodoStatus.NEEDS_ACTION, TodoStatus.fromString("NEEDS-ACTION"))
            assertEquals(TodoStatus.IN_PROCESS, TodoStatus.fromString("IN-PROCESS"))
            assertEquals(TodoStatus.COMPLETED, TodoStatus.fromString("COMPLETED"))
            assertEquals(TodoStatus.CANCELLED, TodoStatus.fromString("CANCELLED"))
        }

        @Test
        fun `TodoStatus toICalString uses hyphens`() {
            assertEquals("NEEDS-ACTION", TodoStatus.NEEDS_ACTION.toICalString())
            assertEquals("IN-PROCESS", TodoStatus.IN_PROCESS.toICalString())
        }
    }

    // ==================== ICalJournal in Calendar Tests ====================

    @Nested
    inner class ICalJournalTests {

        @Test
        fun `journal with all basic properties`() {
            val journal = ICalJournal(
                uid = "journal-123",
                summary = "Meeting Notes",
                description = "Discussed Q4 planning...",
                dtStart = ICalDateTime.parse("20231215"),
                status = JournalStatus.FINAL
            )

            assertEquals("journal-123", journal.uid)
            assertEquals("Meeting Notes", journal.summary)
            assertEquals(JournalStatus.FINAL, journal.status)
        }

        @Test
        fun `journal isRecurring returns true with rrule`() {
            val journal = ICalJournal(
                uid = "journal-1",
                rrule = RRule(freq = Frequency.DAILY)
            )

            assertTrue(journal.isRecurring())
        }

        @Test
        fun `journal isModifiedInstance returns true with recurrenceId`() {
            val journal = ICalJournal(
                uid = "journal-1",
                recurrenceId = ICalDateTime.parse("20231215")
            )

            assertTrue(journal.isModifiedInstance())
        }

        @Test
        fun `JournalStatus fromString parses all values`() {
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString("DRAFT"))
            assertEquals(JournalStatus.FINAL, JournalStatus.fromString("FINAL"))
            assertEquals(JournalStatus.CANCELLED, JournalStatus.fromString("CANCELLED"))
        }

        @Test
        fun `JournalStatus defaults to DRAFT`() {
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString(null))
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString("UNKNOWN"))
        }

        @Test
        fun `journal generateImportId without recurrenceId`() {
            val importId = ICalJournal.generateImportId("journal-123", null)
            assertEquals("journal-123", importId)
        }

        @Test
        fun `journal generateImportId with recurrenceId`() {
            val recurrenceId = ICalDateTime.parse("20231215")
            val importId = ICalJournal.generateImportId("journal-123", recurrenceId)
            assertEquals("journal-123:RECID:20231215", importId)
        }
    }
}
