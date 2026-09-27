package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent

/**
 * Tests that properties the library doesn't model come back exactly as read. They must: RFC
 * 5545 §3.2.20 requires applications to preserve x-name and iana-token value data they don't
 * recognize without interpreting it. §3.6.1 lets most of these properties repeat, so they are
 * kept as the original content lines, in order.
 */
@DisplayName("Unknown property lines round trip")
class ICalUnknownPropertyLinesTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    /** Shapes seen on real server events that the rawProperties map alone can't write back. */
    private val unknownLines = listOf(
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"10600 N Tantau Ave\\nCupertino, CA 95014\";X-APPLE-RADIUS=70;X-TITLE=\"Apple Park: Visitor Center\":geo:37.332,-122.005",
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:1.0,2.0",
        "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY:mail\\,msg-1234\\;part\\=2",
        "X-MICROSOFT-LOCATIONS:[{\"DisplayName\":\"Room 1\\, Floor 2\"}]",
        "COMMENT:line one\\nline two\\, with comma",
        "RESOURCES:Projector",
        "RESOURCES:Whiteboard",
        "ATTACH;FMTTYPE=application/pdf:https://example.test/a.pdf",
        "ATTACH;FMTTYPE=application/pdf:https://example.test/b.pdf",
        "X-PROBE:first",
        "X-PROBE:second",
        "X-LINK;VALUE=URI:https://example.test/a\\b",
        "X-EMPTY:",
    )

    private fun calendar(vararg components: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN") +
            components.toList() + listOf("END:VCALENDAR", "")).joinToString("\r\n")

    private fun vevent(uid: String, summary: String, lines: List<String>, recurrenceId: String? = null, rrule: String? = null) =
        (listOfNotNull(
            "BEGIN:VEVENT",
            "UID:$uid",
            "DTSTAMP:20260101T000000Z",
            recurrenceId?.let { "RECURRENCE-ID:$it" },
            "DTSTART:${recurrenceId ?: "20260105T100000Z"}",
            "DURATION:PT1H",
            rrule?.let { "RRULE:$it" },
            "SUMMARY:$summary",
        ) + lines + "END:VEVENT").joinToString("\r\n")

    private fun unfold(ics: String) = ics.replace(Regex("\r?\n[ \t]"), "")

    /** Top-level lines of each component named [type], nested components skipped. */
    private fun blocks(ics: String, type: String = "VEVENT"): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (line in unfold(ics).split("\r\n", "\n")) {
            when {
                current == null && line == "BEGIN:$type" -> { current = mutableListOf(); depth = 0 }
                current == null -> Unit
                line == "END:$type" && depth == 0 -> { out += current; current = null }
                line.startsWith("BEGIN:") -> depth++
                line.startsWith("END:") -> depth--
                depth == 0 -> current += line
            }
        }
        return out
    }

    private fun unknownIn(block: List<String>, expected: List<String>) = block.filter { it in expected }

    @Test
    fun `every unknown line survives parse and generate unchanged and in order`() {
        val ics = calendar(vevent("probe-1@example.test", "Probe", unknownLines))
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()

        assertEquals(unknownLines, event.unknownPropertyLines)
        val out = generator.generate(event)
        assertEquals(unknownLines, unknownIn(blocks(out).single(), unknownLines))
    }

    @Test
    fun `each unknown line is written exactly once`() {
        val ics = calendar(vevent("probe-2@example.test", "Probe", unknownLines))
        val out = generator.generate(parser.parseAllEvents(ics).getOrNull()!!.single())
        val block = blocks(out).single()
        val unknownNames = setOf("X-APPLE-STRUCTURED-LOCATION", "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY",
            "X-MICROSOFT-LOCATIONS", "COMMENT", "RESOURCES", "ATTACH", "X-PROBE", "X-LINK", "X-EMPTY")
        val written = block.filter { it.split(':', ';').first() in unknownNames }
        assertEquals(unknownLines, written)
    }

    @Test
    fun `a long unknown line is read unfolded and folded again on output`() {
        val long = "X-LONG:" + "a".repeat(100) + "\\, " + "b".repeat(60)
        val folded = long.substring(0, 70) + "\r\n " + long.substring(70)
        val ics = calendar(vevent("probe-3@example.test", "Probe", listOf(folded)))
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals(listOf(long), event.unknownPropertyLines)

        val out = generator.generate(event)
        assertTrue(out.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals(listOf(long), unknownIn(blocks(out).single(), listOf(long)))
    }

    @Test
    fun `a series and its changed occurrence each keep their own lines`() {
        val seriesLines = listOf("COMMENT:series\\nnote", "X-SERIES:1")
        val occurrenceLines = listOf("COMMENT:occurrence only", "X-OCCURRENCE:1")
        val ics = calendar(
            vevent("probe-4@example.test", "Changed", occurrenceLines, recurrenceId = "20260112T100000Z"),
            vevent("probe-4@example.test", "Series", seriesLines, rrule = "FREQ=WEEKLY;COUNT=3"),
        )
        val events = parser.parseAllEvents(ics).getOrNull()!!
        assertEquals(occurrenceLines, events.single { it.recurrenceId != null }.unknownPropertyLines)
        assertEquals(seriesLines, events.single { it.recurrenceId == null }.unknownPropertyLines)

        val out = generator.generate(ICalCalendar(prodId = null, events = events))
        val outBlocks = blocks(out)
        assertEquals(occurrenceLines, unknownIn(outBlocks[0], seriesLines + occurrenceLines))
        assertEquals(seriesLines, unknownIn(outBlocks[1], seriesLines + occurrenceLines))
    }

    @Test
    fun `lines in an alarm, a time zone or the calendar itself are not taken into the event`() {
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "X-CALENDAR-LEVEL:yes",
            "BEGIN:VTIMEZONE", "TZID:Europe/Berlin", "X-LIC-LOCATION:Europe/Berlin",
            "BEGIN:STANDARD", "DTSTART:19701025T030000", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:probe-5@example.test", "DTSTAMP:20260101T000000Z",
            "DTSTART;TZID=Europe/Berlin:20260105T100000", "DURATION:PT1H", "SUMMARY:Probe",
            "X-EVENT-LEVEL:yes",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "DESCRIPTION:Alarm", "X-ALARM-LEVEL:yes", "END:VALARM",
            "END:VEVENT", "END:VCALENDAR", ""
        ).joinToString("\r\n")
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals(listOf("X-EVENT-LEVEL:yes"), event.unknownPropertyLines)
    }

    @Test
    fun `modelled properties never become unknown lines and names match in any case`() {
        val ics = calendar(
            listOf(
                "BEGIN:VEVENT", "uid:probe-6@example.test", "DTSTAMP:20260101T000000Z",
                "DTSTART:20260105T100000Z", "DURATION:PT1H", "summary:lower case name",
                "ATTENDEE;CN=Guest:mailto:guest@example.test", "x-lower-case:kept as written",
                "END:VEVENT"
            ).joinToString("\r\n")
        )
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals(listOf("x-lower-case:kept as written"), event.unknownPropertyLines)
    }

    @Test
    fun `an event with only the property map is written exactly as before`() {
        val event = ICalEvent(
            uid = "probe-7@example.test", importId = "probe-7@example.test", summary = "Map only",
            description = null, location = null,
            dtStart = ICalDateTime.parse("20260105T100000Z"), dtEnd = ICalDateTime.parse("20260105T110000Z"),
            duration = null, isAllDay = false, status = EventStatus.CONFIRMED, sequence = 0, rrule = null,
            exdates = emptyList(), recurrenceId = null, alarms = emptyList(), categories = emptyList(),
            organizer = null, attendees = emptyList(), color = null, dtstamp = null, lastModified = null,
            created = null, transparency = org.onekash.icaldav.model.Transparency.OPAQUE, url = null,
            rawProperties = mapOf("X-MAP-ONLY" to "value", "X-WITH-PARAM;P=1" to "v")
        )
        val block = blocks(generator.generate(event)).single()
        assertTrue(block.contains("X-MAP-ONLY:value"))
        assertTrue(block.contains("X-WITH-PARAM;P=1:v"))
    }

    @Test
    fun `the parsed property map is unchanged`() {
        val ics = calendar(vevent("probe-8@example.test", "Probe", listOf("X-CUSTOM:custom value", "X-EMPTY:")))
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals(mapOf("X-CUSTOM" to "custom value"), event.rawProperties)
    }

    @Test
    fun `whole-calendar parse entry points carry the lines too`() {
        val ics = calendar(vevent("probe-9@example.test", "Probe", unknownLines))
        assertEquals(unknownLines, parser.parse(ics).getOrNull()!!.events.single().unknownPropertyLines)
        assertEquals(unknownLines, parser.parseWithMethod(ics).getOrNull()!!.events.single().unknownPropertyLines)
    }

    @Test
    fun `to-dos and journal entries keep their unknown lines`() {
        val todoLines = listOf("COMMENT:todo\\nnote", "X-TODO:1", "X-TODO:2")
        val journalLines = listOf("COMMENT:journal\\, note", "X-JOURNAL;P=\"a:b\":1")
        val ics = calendar(
            (listOf("BEGIN:VTODO", "UID:todo-1@example.test", "DTSTAMP:20260101T000000Z", "SUMMARY:Todo") +
                todoLines + "END:VTODO").joinToString("\r\n"),
            (listOf("BEGIN:VJOURNAL", "UID:journal-1@example.test", "DTSTAMP:20260101T000000Z", "SUMMARY:Journal") +
                journalLines + "END:VJOURNAL").joinToString("\r\n"),
        )
        val todo = parser.parseAllTodos(ics).getOrNull()!!.single()
        val journal = parser.parseAllJournals(ics).getOrNull()!!.single()
        assertEquals(todoLines, todo.unknownPropertyLines)
        assertEquals(journalLines, journal.unknownPropertyLines)

        assertEquals(todoLines, unknownIn(blocks(generator.generate(todo), "VTODO").single(), todoLines))
        assertEquals(journalLines, unknownIn(blocks(generator.generate(journal), "VJOURNAL").single(), journalLines))
    }

    @Test
    fun `a component the parser rejects does not shift its lines onto the next one`() {
        // No DTSTART and no DTEND: ical4j keeps the component, the parser drops it.
        val rejected = listOf("BEGIN:VEVENT", "UID:rejected@example.test", "DTSTAMP:20260101T000000Z",
            "SUMMARY:No start", "X-REJECTED:1", "END:VEVENT").joinToString("\r\n")
        val ics = calendar(rejected, vevent("kept@example.test", "Kept", listOf("X-KEPT:1")))
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals("kept@example.test", event.uid)
        assertEquals(listOf("X-KEPT:1"), event.unknownPropertyLines)
    }

    @Test
    fun `when the scan and the parser disagree on the components no lines are attached`() {
        // ical4j builds one calendar from two concatenated VCALENDARs; the scan
        // sees both, so the counts differ and the parse falls back to the map.
        val ics = calendar(vevent("first@example.test", "First", listOf("X-FIRST:1"))) +
            calendar(vevent("second@example.test", "Second", listOf("X-SECOND:1")))
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals(emptyList<String>(), event.unknownPropertyLines)
        val ownKey = if (event.uid == "first@example.test") "X-FIRST" else "X-SECOND"
        assertEquals(mapOf(ownKey to "1"), event.rawProperties)
    }

    @Test
    fun `a component whose lines cannot be matched falls back to the property map`() {
        // Without a UID the parser invents one, so the scanned block cannot be
        // matched to the parsed component with confidence.
        val ics = calendar(
            listOf("BEGIN:VEVENT", "DTSTAMP:20260101T000000Z", "DTSTART:20260105T100000Z",
                "DURATION:PT1H", "SUMMARY:No uid", "X-CUSTOM:value", "END:VEVENT").joinToString("\r\n")
        )
        val event = parser.parseAllEvents(ics).getOrNull()!!.single()
        assertEquals(emptyList<String>(), event.unknownPropertyLines)
        assertTrue(blocks(generator.generate(event)).single().contains("X-CUSTOM:value"))
    }
}
