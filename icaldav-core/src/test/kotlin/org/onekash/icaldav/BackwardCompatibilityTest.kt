package org.onekash.icaldav

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.parser.VTimezoneGenerator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Backward Compatibility Tests
 *
 * These tests ensure that existing code continues to work after the
 * new feature implementations. They verify:
 * - Existing constructors work with minimal parameters
 * - Default values are correct
 * - Parsing of event-only calendars works unchanged
 * - Generation of events works unchanged
 */
@DisplayName("Backward Compatibility Tests")
class BackwardCompatibilityTest {

    @Nested
    @DisplayName("ICalTodo Backward Compatibility")
    inner class ICalTodoTests {

        @Test
        fun `ICalTodo can be constructed with only UID`() {
            // Original minimal constructor should still work
            val todo = ICalTodo(uid = "test-123")

            assertEquals("test-123", todo.uid)
            assertEquals(TodoStatus.NEEDS_ACTION, todo.status)
            assertEquals(0, todo.priority)
            assertEquals(0, todo.percentComplete)
        }

        @Test
        fun `ICalTodo original 7 properties have correct defaults`() {
            val todo = ICalTodo(
                uid = "test-456",
                summary = "Test Task"
            )

            // Original properties
            assertEquals("test-456", todo.uid)
            assertEquals("Test Task", todo.summary)
            assertEquals(null, todo.description)
            assertEquals(null, todo.due)
            assertEquals(0, todo.percentComplete)
            assertEquals(TodoStatus.NEEDS_ACTION, todo.status)
            assertEquals(0, todo.priority)

            // New properties should have defaults
            assertEquals("", todo.importId)
            assertEquals(null, todo.dtStart)
            assertEquals(null, todo.completed)
            assertEquals(0, todo.sequence)
            assertEquals(null, todo.dtstamp)
            assertEquals(null, todo.created)
            assertEquals(null, todo.lastModified)
            assertEquals(null, todo.location)
            assertEquals(emptyList<String>(), todo.categories)
            assertEquals(null, todo.organizer)
            assertEquals(emptyList<Attendee>(), todo.attendees)
            assertEquals(emptyList<ICalAlarm>(), todo.alarms)
            assertEquals(null, todo.rrule)
            assertEquals(null, todo.recurrenceId)
        }

        @Test
        fun `TodoStatus enum values unchanged`() {
            // Verify all original status values exist
            assertEquals(TodoStatus.NEEDS_ACTION, TodoStatus.fromString("NEEDS-ACTION"))
            assertEquals(TodoStatus.IN_PROCESS, TodoStatus.fromString("IN-PROCESS"))
            assertEquals(TodoStatus.COMPLETED, TodoStatus.fromString("COMPLETED"))
            assertEquals(TodoStatus.CANCELLED, TodoStatus.fromString("CANCELLED"))
        }
    }

    @Nested
    @DisplayName("ICalJournal Backward Compatibility")
    inner class ICalJournalTests {

        @Test
        fun `ICalJournal can be constructed with only UID`() {
            val journal = ICalJournal(uid = "journal-123")

            assertEquals("journal-123", journal.uid)
            assertEquals(JournalStatus.DRAFT, journal.status)
            assertEquals("", journal.importId)
        }

        @Test
        fun `JournalStatus enum values correct`() {
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString("DRAFT"))
            assertEquals(JournalStatus.FINAL, JournalStatus.fromString("FINAL"))
            assertEquals(JournalStatus.CANCELLED, JournalStatus.fromString("CANCELLED"))
            assertEquals(JournalStatus.DRAFT, JournalStatus.fromString(null))
        }
    }

    @Nested
    @DisplayName("ICalCalendar Backward Compatibility")
    inner class ICalCalendarTests {

        @Test
        fun `parse event-only calendar returns empty todos and journals`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-123
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                SUMMARY:Test Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parser = ICalParser()
            val result = parser.parse(ics)

            assertTrue(result is ParseResult.Success)
            val calendar = result.getOrNull()!!

            assertEquals(1, calendar.events.size)
            assertEquals(0, calendar.todos.size)
            assertEquals(0, calendar.journals.size)
        }

        @Test
        fun `todos list defaults to empty`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                END:VCALENDAR
            """.trimIndent()

            val parser = ICalParser()
            val result = parser.parse(ics)

            assertTrue(result is ParseResult.Success)
            val calendar = result.getOrNull()!!

            assertEquals(emptyList<ICalTodo>(), calendar.todos)
            assertEquals(emptyList<ICalJournal>(), calendar.journals)
        }
    }

    @Nested
    @DisplayName("ICalParser Backward Compatibility")
    inner class ICalParserTests {

        @Test
        fun `parseAllEvents still works unchanged`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event-456
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                DTEND:20231220T110000Z
                SUMMARY:Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parser = ICalParser()
            val result = parser.parseAllEvents(ics)

            assertTrue(result is ParseResult.Success)
            val events = result.getOrNull()!!
            assertEquals(1, events.size)
            assertEquals("event-456", events[0].uid)
            assertEquals("Test Event", events[0].summary)
        }

        @Test
        fun `parseWithMethod still works unchanged`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:method-event
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                SUMMARY:Meeting Request
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parser = ICalParser()
            val result = parser.parseWithMethod(ics)

            assertTrue(result is ParseResult.Success)
            val parsed = result.getOrNull()!!
            assertEquals(ITipMethod.REQUEST, parsed.method)
            assertEquals(1, parsed.events.size)
        }
    }

    @Nested
    @DisplayName("ICalGenerator Backward Compatibility")
    inner class ICalGeneratorTests {

        @Test
        fun `generate event still works unchanged`() {
            val event = ICalEvent(
                uid = "gen-event-123",
                importId = "gen-event-123",
                summary = "Generated Event",
                description = null,
                location = null,
                dtStart = ICalDateTime.parse("20231220T100000Z"),
                dtEnd = ICalDateTime.parse("20231220T110000Z"),
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
                dtstamp = ICalDateTime.parse("20231215T120000Z"),
                lastModified = null,
                created = null,
                transparency = Transparency.OPAQUE,
                url = null,
                rawProperties = emptyMap()
            )

            val generator = ICalGenerator()
            val ics = generator.generate(event, method = null)

            assertTrue(ics.contains("BEGIN:VCALENDAR"))
            assertTrue(ics.contains("BEGIN:VEVENT"))
            assertTrue(ics.contains("UID:gen-event-123"))
            assertTrue(ics.contains("SUMMARY:Generated Event"))
            assertTrue(ics.contains("END:VEVENT"))
            assertTrue(ics.contains("END:VCALENDAR"))
        }

        @Test
        fun `generate event with method still works`() {
            val event = ICalEvent(
                uid = "method-gen-event",
                importId = "method-gen-event",
                summary = "Test",
                description = null,
                location = null,
                dtStart = ICalDateTime.parse("20231220T100000Z"),
                dtEnd = null,
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
                dtstamp = ICalDateTime.parse("20231215T120000Z"),
                lastModified = null,
                created = null,
                transparency = Transparency.OPAQUE,
                url = null,
                rawProperties = emptyMap()
            )

            val generator = ICalGenerator()
            val ics = generator.generate(event, ITipMethod.REQUEST)

            assertTrue(ics.contains("METHOD:REQUEST"))
        }
    }

    @Nested
    @DisplayName("VTimezoneGenerator Backward Compatibility")
    inner class VTimezoneGeneratorTests {

        @Test
        fun `default constructor still works`() {
            // Critical: VTimezoneGenerator() must work without parameters
            val generator = VTimezoneGenerator()

            assertNotNull(generator)
        }

        @Test
        fun `generate timezone still produces valid output`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate("America/New_York")

            assertTrue(result.contains("BEGIN:VTIMEZONE"))
            assertTrue(result.contains("TZID:America/New_York"))
            assertTrue(result.contains("END:VTIMEZONE"))
        }

        @Test
        fun `generate set of timezones still works`() {
            val generator = VTimezoneGenerator()

            val result = generator.generate(setOf("America/New_York", "Europe/London"))

            assertTrue(result.contains("TZID:America/New_York"))
            assertTrue(result.contains("TZID:Europe/London"))
        }

        @Test
        fun `UTC still returns empty`() {
            val generator = VTimezoneGenerator()

            assertEquals("", generator.generate("UTC"))
            assertEquals("", generator.generate("Etc/UTC"))
            assertEquals("", generator.generate("GMT"))
        }
    }

    @Nested
    @DisplayName("Round-Trip Backward Compatibility")
    inner class RoundTripTests {

        @Test
        fun `parse and generate event produces same result`() {
            val originalIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-event
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                DTEND:20231220T110000Z
                SUMMARY:Round Trip Test
                STATUS:CONFIRMED
                SEQUENCE:0
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val parser = ICalParser()
            val generator = ICalGenerator()

            val parsed = parser.parseAllEvents(originalIcs).getOrNull()!![0]
            val regenerated = generator.generate(parsed, method = null)
            val reparsed = parser.parseAllEvents(regenerated).getOrNull()!![0]

            assertEquals(parsed.uid, reparsed.uid)
            assertEquals(parsed.summary, reparsed.summary)
            assertEquals(parsed.status, reparsed.status)
        }
    }

    @Nested
    @DisplayName("API Surface Backward Compatibility")
    inner class ApiSurfaceTests {

        @Test
        fun `ICalDateTime parse still works`() {
            val dt = ICalDateTime.parse("20231215T120000Z")

            assertNotNull(dt)
            assertTrue(dt.isUtc)
        }

        @Test
        fun `EventStatus enum unchanged`() {
            assertEquals(EventStatus.TENTATIVE, EventStatus.fromString("TENTATIVE"))
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString("CONFIRMED"))
            assertEquals(EventStatus.CANCELLED, EventStatus.fromString("CANCELLED"))
        }

        @Test
        fun `PartStat enum unchanged`() {
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString("NEEDS-ACTION"))
            assertEquals(PartStat.ACCEPTED, PartStat.fromString("ACCEPTED"))
            assertEquals(PartStat.DECLINED, PartStat.fromString("DECLINED"))
            assertEquals(PartStat.TENTATIVE, PartStat.fromString("TENTATIVE"))
        }

        @Test
        fun `ITipMethod enum unchanged`() {
            assertEquals(ITipMethod.PUBLISH, ITipMethod.fromString("PUBLISH"))
            assertEquals(ITipMethod.REQUEST, ITipMethod.fromString("REQUEST"))
            assertEquals(ITipMethod.REPLY, ITipMethod.fromString("REPLY"))
            assertEquals(ITipMethod.ADD, ITipMethod.fromString("ADD"))
            assertEquals(ITipMethod.CANCEL, ITipMethod.fromString("CANCEL"))
            assertEquals(ITipMethod.REFRESH, ITipMethod.fromString("REFRESH"))
            assertEquals(ITipMethod.COUNTER, ITipMethod.fromString("COUNTER"))
            assertEquals(ITipMethod.DECLINECOUNTER, ITipMethod.fromString("DECLINECOUNTER"))
        }
    }
}
