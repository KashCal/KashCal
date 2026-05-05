package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.TodoStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive tests for VTODO parsing per RFC 5545 Section 3.6.2.
 */
@DisplayName("ICalParser VTODO Tests")
class ICalParserVTodoTest {
    private val parser = ICalParser()

    @Nested
    @DisplayName("Basic VTODO Parsing")
    inner class BasicParsingTests {

        @Test
        fun `parse simple VTODO with minimal properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:simple-todo-123
                DTSTAMP:20231215T120000Z
                SUMMARY:Simple Task
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)

            val todos = result.getOrNull()!!
            assertEquals(1, todos.size)

            val todo = todos[0]
            assertEquals("simple-todo-123", todo.uid)
            assertEquals("Simple Task", todo.summary)
            assertEquals(TodoStatus.NEEDS_ACTION, todo.status)  // Default
            assertEquals(0, todo.priority)  // Default
            assertEquals(0, todo.percentComplete)  // Default
        }

        @Test
        fun `parse VTODO with all text properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:full-text-todo
                DTSTAMP:20231215T120000Z
                SUMMARY:Full Text Task
                DESCRIPTION:This is a detailed description of the task.\nWith multiple lines.
                LOCATION:Conference Room A
                URL:https://example.com/task/123
                GEO:37.386013;-122.082932
                CLASS:CONFIDENTIAL
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)

            val todo = result.getOrNull()!![0]
            assertEquals("full-text-todo", todo.uid)
            assertEquals("Full Text Task", todo.summary)
            assertTrue(todo.description?.contains("detailed description") == true)
            assertEquals("Conference Room A", todo.location)
            assertEquals("https://example.com/task/123", todo.url)
            assertEquals("37.386013;-122.082932", todo.geo)
            assertEquals("CONFIDENTIAL", todo.classification)
        }

        @Test
        fun `parse VTODO with escaped characters`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:escaped-todo
                DTSTAMP:20231215T120000Z
                SUMMARY:Task with\, comma and\; semicolon
                DESCRIPTION:Line1\nLine2\nLine3
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)

            val todo = result.getOrNull()!![0]
            assertEquals("Task with, comma and; semicolon", todo.summary)
            assertTrue(todo.description?.contains("\n") == true)
        }
    }

    @Nested
    @DisplayName("VTODO Status Parsing")
    inner class StatusParsingTests {

        @Test
        fun `parse VTODO with NEEDS-ACTION status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:needs-action-todo
                DTSTAMP:20231215T120000Z
                STATUS:NEEDS-ACTION
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(TodoStatus.NEEDS_ACTION, todo.status)
        }

        @Test
        fun `parse VTODO with IN-PROCESS status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:in-process-todo
                DTSTAMP:20231215T120000Z
                STATUS:IN-PROCESS
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(TodoStatus.IN_PROCESS, todo.status)
        }

        @Test
        fun `parse VTODO with COMPLETED status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:completed-todo
                DTSTAMP:20231215T120000Z
                STATUS:COMPLETED
                COMPLETED:20231220T150000Z
                PERCENT-COMPLETE:100
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(TodoStatus.COMPLETED, todo.status)
            assertNotNull(todo.completed)
            assertEquals(100, todo.percentComplete)
        }

        @Test
        fun `parse VTODO with CANCELLED status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:cancelled-todo
                DTSTAMP:20231215T120000Z
                STATUS:CANCELLED
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(TodoStatus.CANCELLED, todo.status)
        }

        @Test
        fun `parse VTODO with unknown status defaults to NEEDS-ACTION`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:unknown-status-todo
                DTSTAMP:20231215T120000Z
                STATUS:UNKNOWN-STATUS
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(TodoStatus.NEEDS_ACTION, todo.status)
        }
    }

    @Nested
    @DisplayName("VTODO DateTime Parsing")
    inner class DateTimeParsingTests {

        @Test
        fun `parse VTODO with all datetime properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:datetime-todo
                DTSTAMP:20231215T120000Z
                DTSTART:20231216T090000Z
                DUE:20231220T170000Z
                COMPLETED:20231219T150000Z
                CREATED:20231210T080000Z
                LAST-MODIFIED:20231218T140000Z
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertNotNull(todo.dtstamp)
            assertNotNull(todo.dtStart)
            assertNotNull(todo.due)
            assertNotNull(todo.completed)
            assertNotNull(todo.created)
            assertNotNull(todo.lastModified)

            assertEquals("20231215T120000Z", todo.dtstamp?.toICalString())
            assertEquals("20231216T090000Z", todo.dtStart?.toICalString())
            assertEquals("20231220T170000Z", todo.due?.toICalString())
        }

        @Test
        fun `parse VTODO with timezone DTSTART`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:tz-todo
                DTSTAMP:20231215T120000Z
                DTSTART;TZID=America/New_York:20231216T090000
                DUE;TZID=America/New_York:20231220T170000
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertNotNull(todo.dtStart)
            assertNotNull(todo.due)
        }

        @Test
        fun `parse VTODO with DATE-only DUE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:date-only-todo
                DTSTAMP:20231215T120000Z
                DUE;VALUE=DATE:20231220
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertNotNull(todo.due)
            assertTrue(todo.due!!.isDate)
        }
    }

    @Nested
    @DisplayName("VTODO Numeric Properties")
    inner class NumericPropertiesTests {

        @Test
        fun `parse VTODO with priority`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:priority-todo
                DTSTAMP:20231215T120000Z
                PRIORITY:1
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(1, todo.priority)  // Highest priority
        }

        @Test
        fun `parse VTODO with percent complete`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:percent-todo
                DTSTAMP:20231215T120000Z
                PERCENT-COMPLETE:75
                STATUS:IN-PROCESS
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(75, todo.percentComplete)
        }

        @Test
        fun `parse VTODO with sequence`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:sequence-todo
                DTSTAMP:20231215T120000Z
                SEQUENCE:5
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(5, todo.sequence)
        }
    }

    @Nested
    @DisplayName("VTODO Categories Parsing")
    inner class CategoriesParsingTests {

        @Test
        fun `parse VTODO with single category`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:single-cat-todo
                DTSTAMP:20231215T120000Z
                CATEGORIES:Work
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(listOf("Work"), todo.categories)
        }

        @Test
        fun `parse VTODO with multiple categories`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:multi-cat-todo
                DTSTAMP:20231215T120000Z
                CATEGORIES:Work,Urgent,Project-X
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(3, todo.categories.size)
            assertTrue(todo.categories.contains("Work"))
            assertTrue(todo.categories.contains("Urgent"))
            assertTrue(todo.categories.contains("Project-X"))
        }

        @Test
        fun `parse VTODO with multiple CATEGORIES properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:multi-line-cat-todo
                DTSTAMP:20231215T120000Z
                CATEGORIES:Work,Urgent
                CATEGORIES:Personal
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]
            assertEquals(3, todo.categories.size)
        }
    }

    @Nested
    @DisplayName("VTODO Organizer and Attendees")
    inner class OrganizerAttendeesTests {

        @Test
        fun `parse VTODO with organizer`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:org-todo
                DTSTAMP:20231215T120000Z
                ORGANIZER;CN=John Manager:mailto:john@example.com
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertNotNull(todo.organizer)
            assertEquals("john@example.com", todo.organizer?.email)
            assertEquals("John Manager", todo.organizer?.name)
        }

        @Test
        fun `parse VTODO with attendees`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:attendee-todo
                DTSTAMP:20231215T120000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice@example.com
                ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=OPT-PARTICIPANT:mailto:bob@example.com
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertEquals(2, todo.attendees.size)

            val alice = todo.attendees.find { it.email == "alice@example.com" }
            assertNotNull(alice)
            assertEquals("Alice", alice?.name)
            assertEquals(PartStat.ACCEPTED, alice?.partStat)
            assertEquals(AttendeeRole.REQ_PARTICIPANT, alice?.role)

            val bob = todo.attendees.find { it.email == "bob@example.com" }
            assertNotNull(bob)
            assertEquals(PartStat.NEEDS_ACTION, bob?.partStat)
        }
    }

    @Nested
    @DisplayName("VTODO Alarms (VALARM)")
    inner class AlarmParsingTests {

        @Test
        fun `parse VTODO with display alarm`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:alarm-todo
                DTSTAMP:20231215T120000Z
                DUE:20231220T170000Z
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:Task due in 15 minutes
                END:VALARM
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertEquals(1, todo.alarms.size)
            val alarm = todo.alarms[0]
            assertEquals(AlarmAction.DISPLAY, alarm.action)
        }

        @Test
        fun `parse VTODO with multiple alarms`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:multi-alarm-todo
                DTSTAMP:20231215T120000Z
                DUE:20231220T170000Z
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT1H
                DESCRIPTION:1 hour warning
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:15 minute warning
                END:VALARM
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertEquals(2, todo.alarms.size)
        }
    }

    @Nested
    @DisplayName("VTODO Recurrence")
    inner class RecurrenceTests {

        @Test
        fun `parse recurring VTODO with RRULE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:recurring-todo
                DTSTAMP:20231215T120000Z
                DTSTART:20231215T090000Z
                DUE:20231215T170000Z
                RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
                SUMMARY:Recurring Task
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertTrue(todo.isRecurring())
            assertNotNull(todo.rrule)
            assertEquals(Frequency.WEEKLY, todo.rrule?.freq)
        }

        @Test
        fun `parse modified instance with RECURRENCE-ID`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:recurring-todo
                DTSTAMP:20231215T120000Z
                RECURRENCE-ID:20231218T090000Z
                DTSTART:20231218T100000Z
                DUE:20231218T180000Z
                SUMMARY:Modified Instance
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertTrue(todo.isModifiedInstance())
            assertNotNull(todo.recurrenceId)
            assertNull(todo.rrule)  // Modified instances don't have RRULE
        }

        @Test
        fun `importId includes RECURRENCE-ID for modified instances`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:recurring-todo-456
                DTSTAMP:20231215T120000Z
                RECURRENCE-ID:20231218T090000Z
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            val todo = result.getOrNull()!![0]

            assertTrue(todo.importId.contains("recurring-todo-456"))
            assertTrue(todo.importId.contains("RECID"))
            assertTrue(todo.importId.contains("20231218T090000Z"))
        }
    }

    @Nested
    @DisplayName("Multiple VTODOs")
    inner class MultipleTodosTests {

        @Test
        fun `parse calendar with multiple VTODOs`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:todo-1
                DTSTAMP:20231215T120000Z
                SUMMARY:First Task
                PRIORITY:1
                END:VTODO
                BEGIN:VTODO
                UID:todo-2
                DTSTAMP:20231215T120000Z
                SUMMARY:Second Task
                PRIORITY:5
                END:VTODO
                BEGIN:VTODO
                UID:todo-3
                DTSTAMP:20231215T120000Z
                SUMMARY:Third Task
                PRIORITY:9
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)

            val todos = result.getOrNull()!!
            assertEquals(3, todos.size)
            assertEquals("todo-1", todos[0].uid)
            assertEquals("todo-2", todos[1].uid)
            assertEquals("todo-3", todos[2].uid)
        }

        @Test
        fun `parse calendar with mixed VEVENT and VTODO`() {
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
                END:VCALENDAR
            """.trimIndent()

            // Parse todos
            val todoResult = parser.parseAllTodos(ics)
            assertTrue(todoResult is ParseResult.Success)
            assertEquals(1, todoResult.getOrNull()!!.size)

            // Parse events
            val eventResult = parser.parseAllEvents(ics)
            assertTrue(eventResult is ParseResult.Success)
            assertEquals(1, eventResult.getOrNull()!!.size)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `parse VTODO without UID gets generated UUID`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                DTSTAMP:20231215T120000Z
                SUMMARY:No UID Task
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)
            val todos = result.getOrNull()!!
            assertEquals(1, todos.size)
            assertTrue(todos[0].uid.isNotBlank(), "Generated UID should not be blank")
        }

        @Test
        fun `parse VTODO without UID alongside valid VTODO both parse`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:valid-todo
                DTSTAMP:20231215T120000Z
                SUMMARY:Valid Task
                END:VTODO
                BEGIN:VTODO
                SUMMARY:No UID Task
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)

            val todos = result.getOrNull()!!
            assertEquals(2, todos.size)
            assertTrue(todos.any { it.uid == "valid-todo" })
            assertTrue(todos.any { it.uid != "valid-todo" && it.uid.isNotBlank() })
        }

        @Test
        fun `parse empty calendar returns empty list`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllTodos(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(0, result.getOrNull()!!.size)
        }
    }

    @Nested
    @DisplayName("ICalCalendar Integration")
    inner class ICalCalendarIntegrationTests {

        @Test
        fun `parse() populates todos list in ICalCalendar`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:cal-todo-1
                DTSTAMP:20231215T120000Z
                SUMMARY:Calendar Task 1
                END:VTODO
                BEGIN:VTODO
                UID:cal-todo-2
                DTSTAMP:20231215T120000Z
                SUMMARY:Calendar Task 2
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)

            val calendar = result.getOrNull()!!
            assertEquals(2, calendar.todos.size)
            assertEquals("cal-todo-1", calendar.todos[0].uid)
            assertEquals("cal-todo-2", calendar.todos[1].uid)
        }

        @Test
        fun `parse() handles calendar with events and todos`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                NAME:Mixed Calendar
                BEGIN:VEVENT
                UID:event-1
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                SUMMARY:Event
                END:VEVENT
                BEGIN:VTODO
                UID:todo-1
                DTSTAMP:20231215T120000Z
                SUMMARY:Task
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)

            val calendar = result.getOrNull()!!
            assertEquals(1, calendar.events.size)
            assertEquals(1, calendar.todos.size)
            assertEquals("Mixed Calendar", calendar.name)
        }
    }

    @Nested
    @DisplayName("TodoStatus Enum")
    inner class TodoStatusEnumTests {

        @Test
        fun `TodoStatus toICalString produces correct values`() {
            assertEquals("NEEDS-ACTION", TodoStatus.NEEDS_ACTION.toICalString())
            assertEquals("IN-PROCESS", TodoStatus.IN_PROCESS.toICalString())
            assertEquals("COMPLETED", TodoStatus.COMPLETED.toICalString())
            assertEquals("CANCELLED", TodoStatus.CANCELLED.toICalString())
        }

        @Test
        fun `TodoStatus fromString parses all values`() {
            assertEquals(TodoStatus.NEEDS_ACTION, TodoStatus.fromString("NEEDS-ACTION"))
            assertEquals(TodoStatus.IN_PROCESS, TodoStatus.fromString("IN-PROCESS"))
            assertEquals(TodoStatus.COMPLETED, TodoStatus.fromString("COMPLETED"))
            assertEquals(TodoStatus.CANCELLED, TodoStatus.fromString("CANCELLED"))
        }

        @Test
        fun `TodoStatus fromString is case-insensitive`() {
            assertEquals(TodoStatus.COMPLETED, TodoStatus.fromString("completed"))
            assertEquals(TodoStatus.COMPLETED, TodoStatus.fromString("Completed"))
            assertEquals(TodoStatus.IN_PROCESS, TodoStatus.fromString("in-process"))
        }
    }
}
