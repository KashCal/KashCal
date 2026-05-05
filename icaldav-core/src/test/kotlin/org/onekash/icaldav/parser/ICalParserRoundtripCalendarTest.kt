package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.Transparency
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for the calendar-level parse -> generate -> parse cycle.
 *
 * For each calendar-level property the parser populates, verify that emitting
 * it through generate(ICalCalendar) and re-parsing yields an equal value.
 *
 * IMAGE round-trip is not asserted here because ICalParser.parse does not
 * currently populate ICalCalendar.image (parser gap, tracked separately).
 * Generation of IMAGE is verified in ICalGeneratorCalendarTest.
 */
@DisplayName("ICalParser + ICalGenerator calendar-level round-trip")
class ICalParserRoundtripCalendarTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator(prodId = "-//RoundTrip//EN")

    private fun parseToCalendar(ics: String): ICalCalendar {
        val result = parser.parse(ics)
        assertTrue(result is ParseResult.Success, "Expected parse success, got: $result")
        return (result as ParseResult.Success).value
    }

    // ========== Per-property round-trip ==========

    @Nested
    @DisplayName("Calendar-level property survives round-trip")
    inner class PerPropertyRoundtrip {

        @Test
        fun `prodId survives`() {
            val cal = ICalCalendar(prodId = "-//Acme//Server 1.0//EN")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("-//Acme//Server 1.0//EN", re.prodId)
        }

        @Test
        fun `version survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", version = "2.0")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("2.0", re.version)
        }

        @Test
        fun `calscale survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", calscale = "GREGORIAN")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("GREGORIAN", re.calscale)
        }

        @Test
        fun `method survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", method = "REQUEST")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("REQUEST", re.method)
        }

        @Test
        fun `name survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Work Calendar")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("Work Calendar", re.name)
        }

        @Test
        fun `source survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", source = "https://example.com/cal.ics")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("https://example.com/cal.ics", re.source)
        }

        @Test
        fun `color survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", color = "crimson")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("crimson", re.color)
        }

        @Test
        fun `refreshInterval survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", refreshInterval = Duration.ofDays(1))
            val re = parseToCalendar(generator.generate(cal))
            assertEquals(Duration.ofDays(1), re.refreshInterval)
        }

        @Test
        fun `xWrCalname survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", xWrCalname = "Apple Calendar Name")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("Apple Calendar Name", re.xWrCalname)
        }

        @Test
        fun `xAppleCalendarColor survives`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", xAppleCalendarColor = "#112233FF")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("#112233FF", re.xAppleCalendarColor)
        }

        @Test
        fun `NAME with special characters survives escaping round-trip`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Work; Personal, Notes\\Path\nLine2")
            val re = parseToCalendar(generator.generate(cal))
            assertEquals("Work; Personal, Notes\\Path\nLine2", re.name)
        }
    }

    // ========== Component counts round-trip ==========

    @Nested
    @DisplayName("Component counts survive round-trip")
    inner class ComponentCountsRoundtrip {

        @Test
        fun `mixed event-todo-journal calendar round-trips component counts`() {
            val event = ICalEvent(
                uid = "ev-rt-1",
                importId = "ev-rt-1",
                summary = "E",
                description = null,
                location = null,
                dtStart = ICalDateTime.parse("20240306T120000Z"),
                dtEnd = ICalDateTime.parse("20240306T130000Z"),
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
                dtstamp = ICalDateTime.parse("20240101T000000Z"),
                lastModified = null,
                created = null,
                transparency = Transparency.OPAQUE,
                url = null,
                rawProperties = emptyMap()
            )
            val todo = ICalTodo(
                uid = "td-rt-1",
                summary = "T",
                status = TodoStatus.NEEDS_ACTION,
                dtstamp = ICalDateTime.parse("20240101T000000Z")
            )
            val journal = ICalJournal(
                uid = "jr-rt-1",
                summary = "J",
                status = JournalStatus.FINAL,
                dtstamp = ICalDateTime.parse("20240101T000000Z")
            )
            val cal = ICalCalendar(
                prodId = "-//RT//EN",
                events = listOf(event),
                todos = listOf(todo),
                journals = listOf(journal)
            )
            val re = parseToCalendar(generator.generate(cal))
            assertEquals(1, re.events.size)
            assertEquals(1, re.todos.size)
            assertEquals(1, re.journals.size)
        }
    }

    // ========== Full calendar fixture ==========

    @Nested
    @DisplayName("Full calendar with all properties")
    inner class FullCalendarRoundtrip {

        @Test
        fun `calendar with every parser-populated property round-trips`() {
            val cal = ICalCalendar(
                prodId = "-//Full//EN",
                version = "2.0",
                calscale = "GREGORIAN",
                method = "PUBLISH",
                name = "Full Calendar",
                source = "https://example.com/full.ics",
                color = "blue",
                refreshInterval = Duration.ofHours(12),
                xWrCalname = "Apple Full",
                xAppleCalendarColor = "#ABCDEFFF"
            )
            val re = parseToCalendar(generator.generate(cal))

            assertEquals(cal.prodId, re.prodId)
            assertEquals(cal.version, re.version)
            assertEquals(cal.calscale, re.calscale)
            assertEquals(cal.method, re.method)
            assertEquals(cal.name, re.name)
            assertEquals(cal.source, re.source)
            assertEquals(cal.color, re.color)
            assertEquals(cal.refreshInterval, re.refreshInterval)
            assertEquals(cal.xWrCalname, re.xWrCalname)
            assertEquals(cal.xAppleCalendarColor, re.xAppleCalendarColor)
        }
    }

    // ========== Effective accessors round-trip ==========

    @Nested
    @DisplayName("Effective accessor round-trip (RFC 7986 NAME preferred over X-WR-CALNAME)")
    inner class EffectiveAccessorRoundtrip {

        @Test
        fun `effectiveName prefers NAME over X-WR-CALNAME after round-trip`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "RFC 7986 Name", xWrCalname = "Fallback Name")
            val re = parseToCalendar(generator.generate(cal))
            assertNotNull(re.name)
            assertEquals("RFC 7986 Name", re.effectiveName)
        }
    }
}
