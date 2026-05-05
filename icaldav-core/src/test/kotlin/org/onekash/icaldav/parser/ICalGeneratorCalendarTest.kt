package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalImage
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.ImageDisplay
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.Transparency
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ICalGenerator.generate(ICalCalendar) — the symmetric counterpart
 * to ICalParser.parse(String): ParseResult<ICalCalendar>.
 *
 * Covers calendar-level metadata emission (NAME, SOURCE, COLOR, REFRESH-INTERVAL,
 * X-WR-CALNAME, X-APPLE-CALENDAR-COLOR, IMAGE, METHOD), mixed-component bundling
 * (VEVENT + VTODO + VJOURNAL), VTIMEZONE collection across all component types,
 * PRODID precedence, property emission order, escaping/folding, and generateBatch
 * back-compat guarantee.
 */
@DisplayName("ICalGenerator calendar-level emission")
class ICalGeneratorCalendarTest {

    private val generator = ICalGenerator(prodId = "-//Test//Default//EN")
    private val parser = ICalParser()

    // ========== Fixtures ==========

    private fun newYorkEvent(uid: String = "ev-1"): ICalEvent {
        val zone = ZoneId.of("America/New_York")
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Event in NY",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 12, 0, 0, 0, zone)),
            dtEnd = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 13, 0, 0, 0, zone)),
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
    }

    private fun tokyoTodo(uid: String = "td-1"): ICalTodo {
        val zone = ZoneId.of("Asia/Tokyo")
        return ICalTodo(
            uid = uid,
            summary = "Task in Tokyo",
            status = TodoStatus.NEEDS_ACTION,
            dtstamp = ICalDateTime.parse("20240101T000000Z"),
            dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 9, 0, 0, 0, zone)),
            due = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 18, 0, 0, 0, zone))
        )
    }

    private fun londonJournal(uid: String = "jr-1"): ICalJournal {
        val zone = ZoneId.of("Europe/London")
        return ICalJournal(
            uid = uid,
            summary = "Journal from London",
            status = JournalStatus.FINAL,
            dtstamp = ICalDateTime.parse("20240101T000000Z"),
            dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 10, 0, 0, 0, zone))
        )
    }

    // ========== Calendar-level property emission ==========

    @Nested
    @DisplayName("Calendar-level property emission")
    inner class CalendarPropertyEmission {

        @Test
        fun `NAME emitted when set`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Work Calendar")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("NAME:Work Calendar"))
        }

        @Test
        fun `NAME absent when null`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = null)
            val ics = generator.generate(cal)
            assertFalse(ics.lineSequence().any { it.startsWith("NAME:") })
        }

        @Test
        fun `SOURCE emitted when set`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", source = "https://example.com/cal.ics")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("SOURCE:https://example.com/cal.ics"))
        }

        @Test
        fun `COLOR emitted when set`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", color = "crimson")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("COLOR:crimson"))
        }

        @Test
        fun `REFRESH-INTERVAL emitted with VALUE=DURATION parameter and ISO 8601 duration`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", refreshInterval = Duration.ofDays(1))
            val ics = generator.generate(cal)
            assertTrue(
                ics.contains("REFRESH-INTERVAL;VALUE=DURATION:P1D"),
                "Expected REFRESH-INTERVAL;VALUE=DURATION:P1D in:\n$ics"
            )
        }

        @Test
        fun `X-WR-CALNAME emitted when set`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", xWrCalname = "Apple Calendar Name")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("X-WR-CALNAME:Apple Calendar Name"))
        }

        @Test
        fun `X-APPLE-CALENDAR-COLOR emitted when set`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", xAppleCalendarColor = "#FF0000FF")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("X-APPLE-CALENDAR-COLOR:#FF0000FF"))
        }

        @Test
        fun `IMAGE emitted when set`() {
            val image = ICalImage(
                uri = "https://example.com/logo.png",
                display = ImageDisplay.BADGE,
                mediaType = "image/png",
                altText = null
            )
            val cal = ICalCalendar(prodId = "-//Cal//EN", image = image)
            val ics = generator.generate(cal)
            assertTrue(ics.contains("IMAGE;"))
            assertTrue(ics.contains("https://example.com/logo.png"))
        }

        @Test
        fun `METHOD emitted when set`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", method = "PUBLISH")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("METHOD:PUBLISH"))
        }

        @Test
        fun `METHOD absent when null`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", method = null)
            val ics = generator.generate(cal)
            assertFalse(ics.lineSequence().any { it.startsWith("METHOD:") })
        }
    }

    // ========== PRODID precedence ==========

    @Nested
    @DisplayName("PRODID precedence")
    inner class PRODIDPrecedence {

        @Test
        fun `calendar prodId overrides instance prodId`() {
            val gen = ICalGenerator(prodId = "-//Instance//EN")
            val cal = ICalCalendar(prodId = "-//Calendar//EN")
            val ics = gen.generate(cal)
            assertTrue(ics.contains("PRODID:-//Calendar//EN"))
            assertFalse(ics.contains("PRODID:-//Instance//EN"))
        }

        @Test
        fun `null calendar prodId falls back to instance prodId`() {
            val gen = ICalGenerator(prodId = "-//Instance//EN")
            val cal = ICalCalendar(prodId = null)
            val ics = gen.generate(cal)
            assertTrue(ics.contains("PRODID:-//Instance//EN"))
        }
    }

    // ========== Property emission order ==========

    @Nested
    @DisplayName("Property emission order (regression lock)")
    inner class PropertyEmissionOrder {

        @Test
        fun `order is VERSION PRODID CALSCALE METHOD calendar-props VTIMEZONE components`() {
            val cal = ICalCalendar(
                prodId = "-//Test//EN",
                method = "REQUEST",
                name = "Name",
                source = "https://example.com/cal.ics",
                color = "blue",
                refreshInterval = Duration.ofHours(6),
                xWrCalname = "X-Name",
                xAppleCalendarColor = "#112233FF",
                events = listOf(newYorkEvent())
            )
            val ics = generator.generate(cal)
            val lines = ics.lineSequence().toList()

            fun indexOfLinePrefix(prefix: String): Int =
                lines.indexOfFirst { it.startsWith(prefix) }

            val iVersion = indexOfLinePrefix("VERSION:")
            val iProdId = indexOfLinePrefix("PRODID:")
            val iCalscale = indexOfLinePrefix("CALSCALE:")
            val iMethod = indexOfLinePrefix("METHOD:")
            val iName = indexOfLinePrefix("NAME:")
            val iSource = indexOfLinePrefix("SOURCE:")
            val iColor = indexOfLinePrefix("COLOR:")
            val iRefresh = indexOfLinePrefix("REFRESH-INTERVAL")
            val iXWr = indexOfLinePrefix("X-WR-CALNAME:")
            val iXApple = indexOfLinePrefix("X-APPLE-CALENDAR-COLOR:")
            val iVtimezone = indexOfLinePrefix("BEGIN:VTIMEZONE")
            val iVevent = indexOfLinePrefix("BEGIN:VEVENT")

            val ordered = listOf(iVersion, iProdId, iCalscale, iMethod, iName, iSource, iColor, iRefresh, iXWr, iXApple, iVtimezone, iVevent)
            assertTrue(ordered.none { it == -1 }, "Missing expected line(s): $ordered")
            assertEquals(ordered.sorted(), ordered, "Lines not in expected order. Actual indices: $ordered")
        }

        @Test
        fun `CALSCALE defaults to GREGORIAN when not specified`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN") // calscale defaults to "GREGORIAN" in data class
            val ics = generator.generate(cal)
            assertTrue(ics.contains("CALSCALE:GREGORIAN"))
        }
    }

    // ========== Component mixing ==========

    @Nested
    @DisplayName("Mixed VEVENT/VTODO/VJOURNAL components")
    inner class ComponentMixing {

        @Test
        fun `empty calendar emits VCALENDAR with no components`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("BEGIN:VCALENDAR"))
            assertTrue(ics.contains("END:VCALENDAR"))
            assertFalse(ics.contains("BEGIN:VEVENT"))
            assertFalse(ics.contains("BEGIN:VTODO"))
            assertFalse(ics.contains("BEGIN:VJOURNAL"))
            assertFalse(ics.contains("BEGIN:VTIMEZONE"))
        }

        @Test
        fun `todo-only calendar emits VTODO`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", todos = listOf(tokyoTodo()))
            val ics = generator.generate(cal)
            assertTrue(ics.contains("BEGIN:VTODO"))
            assertTrue(ics.contains("UID:td-1"))
            assertFalse(ics.contains("BEGIN:VEVENT"))
            assertFalse(ics.contains("BEGIN:VJOURNAL"))
        }

        @Test
        fun `journal-only calendar emits VJOURNAL`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", journals = listOf(londonJournal()))
            val ics = generator.generate(cal)
            assertTrue(ics.contains("BEGIN:VJOURNAL"))
            assertTrue(ics.contains("UID:jr-1"))
            assertFalse(ics.contains("BEGIN:VEVENT"))
            assertFalse(ics.contains("BEGIN:VTODO"))
        }

        @Test
        fun `mixed components appear in events todos journals order`() {
            val cal = ICalCalendar(
                prodId = "-//Cal//EN",
                events = listOf(newYorkEvent()),
                todos = listOf(tokyoTodo()),
                journals = listOf(londonJournal())
            )
            val ics = generator.generate(cal)
            val iVevent = ics.indexOf("BEGIN:VEVENT")
            val iVtodo = ics.indexOf("BEGIN:VTODO")
            val iVjournal = ics.indexOf("BEGIN:VJOURNAL")
            assertTrue(iVevent > 0)
            assertTrue(iVtodo > iVevent)
            assertTrue(iVjournal > iVtodo)
        }
    }

    // ========== VTIMEZONE collection ==========

    @Nested
    @DisplayName("VTIMEZONE collection across component types")
    inner class VTimezoneCollection {

        @Test
        fun `TZID from todo-only calendar is emitted as VTIMEZONE`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", todos = listOf(tokyoTodo()))
            val ics = generator.generate(cal)
            assertTrue(ics.contains("BEGIN:VTIMEZONE"))
            assertTrue(ics.contains("TZID:Asia/Tokyo"))
        }

        @Test
        fun `TZID from journal-only calendar is emitted as VTIMEZONE`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", journals = listOf(londonJournal()))
            val ics = generator.generate(cal)
            assertTrue(ics.contains("BEGIN:VTIMEZONE"))
            assertTrue(ics.contains("TZID:Europe/London"))
        }

        @Test
        fun `shared TZID across event and todo is deduplicated`() {
            val zone = ZoneId.of("America/New_York")
            val sharedTodo = ICalTodo(
                uid = "td-shared",
                summary = "NY Task",
                status = TodoStatus.NEEDS_ACTION,
                dtstamp = ICalDateTime.parse("20240101T000000Z"),
                dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 7, 9, 0, 0, 0, zone))
            )
            val cal = ICalCalendar(
                prodId = "-//Cal//EN",
                events = listOf(newYorkEvent()),
                todos = listOf(sharedTodo)
            )
            val ics = generator.generate(cal)
            val nyCount = ics.split("TZID:America/New_York").size - 1
            assertEquals(1, nyCount, "Expected exactly one VTIMEZONE block for America/New_York")
        }

        @Test
        fun `multiple distinct TZIDs across component types all emitted`() {
            val cal = ICalCalendar(
                prodId = "-//Cal//EN",
                events = listOf(newYorkEvent()),
                todos = listOf(tokyoTodo()),
                journals = listOf(londonJournal())
            )
            val ics = generator.generate(cal)
            assertTrue(ics.contains("TZID:America/New_York"))
            assertTrue(ics.contains("TZID:Asia/Tokyo"))
            assertTrue(ics.contains("TZID:Europe/London"))
        }

        @Test
        fun `no VTIMEZONE emitted when includeVTimezone is false`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", events = listOf(newYorkEvent()))
            val ics = generator.generate(cal, includeVTimezone = false)
            assertFalse(ics.contains("BEGIN:VTIMEZONE"))
        }

        @Test
        fun `VTIMEZONE placed before first component`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", events = listOf(newYorkEvent()))
            val ics = generator.generate(cal)
            val iVtimezone = ics.indexOf("BEGIN:VTIMEZONE")
            val iVevent = ics.indexOf("BEGIN:VEVENT")
            assertTrue(iVtimezone > 0 && iVtimezone < iVevent, "VTIMEZONE ($iVtimezone) must precede VEVENT ($iVevent)")
        }
    }

    // ========== Escaping and folding ==========

    @Nested
    @DisplayName("Escaping and folding of calendar-level text")
    inner class EscapingAndFolding {

        @Test
        fun `NAME with semicolon is escaped`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Work; Personal")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("NAME:Work\\; Personal"))
        }

        @Test
        fun `NAME with comma is escaped`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Work, Personal")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("NAME:Work\\, Personal"))
        }

        @Test
        fun `NAME with backslash is escaped`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Path\\Here")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("NAME:Path\\\\Here"))
        }

        @Test
        fun `NAME with newline is escaped`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = "Line1\nLine2")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("NAME:Line1\\nLine2"))
        }

        @Test
        fun `long NAME is folded per RFC 5545 section 3 point 1`() {
            val longName = "A".repeat(200)
            val cal = ICalCalendar(prodId = "-//Cal//EN", name = longName)
            val ics = generator.generate(cal)

            val nameLine = ics.lineSequence().dropWhile { !it.startsWith("NAME:") }.first()
            // Folded lines have been split; the first physical line carrying NAME: must be <= 75 bytes
            assertTrue(
                nameLine.toByteArray(Charsets.UTF_8).size <= 75,
                "First physical line of NAME exceeds 75 octets: ${nameLine.toByteArray(Charsets.UTF_8).size}"
            )
            // At least one continuation line exists starting with a space
            val foldedLine = ics.lineSequence().firstOrNull { it.startsWith(" ") && it.length > 1 }
            assertTrue(foldedLine != null, "No continuation line with space prefix found for folded NAME")
        }

        @Test
        fun `X-WR-CALNAME with escape characters is escaped`() {
            val cal = ICalCalendar(prodId = "-//Cal//EN", xWrCalname = "X; Y, Z")
            val ics = generator.generate(cal)
            assertTrue(ics.contains("X-WR-CALNAME:X\\; Y\\, Z"))
        }
    }

    // ========== generateBatch back-compat ==========

    @Nested
    @DisplayName("generateBatch back-compat")
    inner class BatchBackCompat {

        @Test
        fun `generateBatch with METHOD produces same METHOD line as generate with calendar method PUBLISH`() {
            val events = listOf(newYorkEvent("bb-1"))
            val batchOutput = generator.generateBatch(events, includeMethod = true)
            val calOutput = generator.generate(
                ICalCalendar(prodId = "-//Test//Default//EN", method = "PUBLISH", events = events)
            )

            // Both must contain METHOD:PUBLISH exactly once
            assertEquals(
                batchOutput.split("METHOD:PUBLISH").size - 1,
                calOutput.split("METHOD:PUBLISH").size - 1
            )
            assertTrue(batchOutput.contains("METHOD:PUBLISH"))
            assertTrue(calOutput.contains("METHOD:PUBLISH"))
        }

        @Test
        fun `generateBatch emits CALSCALE GREGORIAN by default`() {
            val events = listOf(newYorkEvent("bb-2"))
            val ics = generator.generateBatch(events)
            assertTrue(ics.contains("CALSCALE:GREGORIAN"))
        }

        @Test
        fun `generateBatch dedup behavior preserved (same TZID two events one VTIMEZONE)`() {
            val ev1 = newYorkEvent("ev-a")
            val ev2 = newYorkEvent("ev-b")
            val ics = generator.generateBatch(listOf(ev1, ev2))
            val count = ics.split("TZID:America/New_York").size - 1
            assertEquals(1, count)
        }

        @Test
        fun `generateBatch with includeMethod false omits METHOD line`() {
            val events = listOf(newYorkEvent("bb-3"))
            val ics = generator.generateBatch(events, includeMethod = false)
            assertFalse(ics.lineSequence().any { it.startsWith("METHOD:") })
        }

        @Test
        fun `generateBatch with includeVTimezone false omits VTIMEZONE`() {
            val events = listOf(newYorkEvent("bb-4"))
            val ics = generator.generateBatch(events, includeVTimezone = false)
            assertFalse(ics.contains("BEGIN:VTIMEZONE"))
        }

        @Test
        fun `generateBatch output equals generate(ICalCalendar) for the equivalent calendar`() {
            // The delegation contract: generateBatch(events, includeMethod, includeVTimezone) must
            // produce byte-identical output to generate(ICalCalendar(prodId=null, method=..., events)).
            // DTSTAMP is normalized because it's regenerated from Instant.now() on each call.
            val gen = ICalGenerator(prodId = "-//Snapshot//Fixed//EN")
            val events = listOf(newYorkEvent("snap-1"))

            val viaBatch = gen.generateBatch(events, includeMethod = true, includeVTimezone = true)
            val viaCalendar = gen.generate(
                ICalCalendar(
                    prodId = null, // triggers fallback to instance prodId, matching generateBatch
                    method = "PUBLISH",
                    events = events
                ),
                includeVTimezone = true
            )

            val dtstampRegex = Regex("DTSTAMP:\\d{8}T\\d{6}Z")
            val normalizedBatch = viaBatch.replace(dtstampRegex, "DTSTAMP:NORMALIZED")
            val normalizedCalendar = viaCalendar.replace(dtstampRegex, "DTSTAMP:NORMALIZED")

            assertEquals(normalizedBatch, normalizedCalendar)
        }

        @Test
        fun `generateBatch pinned snapshot - regression lock`() {
            // Pins the current generateBatch output format. A failure here means the wire
            // format changed; either roll it back or update this snapshot deliberately.
            val gen = ICalGenerator(prodId = "-//Snapshot//Fixed//EN")
            val events = listOf(newYorkEvent("snap-pin"))

            val actual = gen.generateBatch(events, includeMethod = true, includeVTimezone = true)
            val normalized = actual.replace(Regex("DTSTAMP:\\d{8}T\\d{6}Z"), "DTSTAMP:NORMALIZED")

            // Header + VTIMEZONE body is generated from ZoneRules at runtime; assert only that
            // the outer envelope (header properties in order, VTIMEZONE placement, VEVENT body)
            // is what we expect. VTIMEZONE inner lines are covered by VTimezoneGeneratorTest.
            val lines = normalized.lineSequence().toList()
            val prefixOrder = listOf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Snapshot//Fixed//EN",
                "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH",
                "BEGIN:VTIMEZONE"
            )
            val headerIndices = prefixOrder.map { prefix -> lines.indexOfFirst { it == prefix } }
            assertTrue(headerIndices.none { it == -1 }, "Missing expected header line(s): $prefixOrder; got first 8 lines: ${lines.take(8)}")
            // Header lines must appear in order, contiguously starting at 0
            assertEquals(listOf(0, 1, 2, 3, 4), headerIndices.take(5), "First 5 header lines out of order or non-contiguous")
            // VTIMEZONE starts after METHOD
            assertTrue(headerIndices[5] > 4, "VTIMEZONE should come after METHOD")

            // VEVENT body appears after END:VTIMEZONE
            val endVtimezone = lines.indexOfFirst { it == "END:VTIMEZONE" }
            val beginVevent = lines.indexOfFirst { it == "BEGIN:VEVENT" }
            assertTrue(endVtimezone in 0 until beginVevent, "VEVENT must follow END:VTIMEZONE")

            // Event body pins the fields we emit
            assertTrue(lines.contains("UID:snap-pin"))
            assertTrue(lines.contains("DTSTAMP:NORMALIZED"))
            assertTrue(lines.contains("DTSTART;TZID=America/New_York:20240306T120000"))
            assertTrue(lines.contains("DTEND;TZID=America/New_York:20240306T130000"))
            assertTrue(lines.contains("SUMMARY:Event in NY"))
            assertTrue(lines.contains("STATUS:CONFIRMED"))
            assertTrue(lines.contains("SEQUENCE:0"))
            assertTrue(lines.contains("END:VEVENT"))
            assertEquals("END:VCALENDAR", lines.last { it.isNotBlank() })
        }
    }
}
