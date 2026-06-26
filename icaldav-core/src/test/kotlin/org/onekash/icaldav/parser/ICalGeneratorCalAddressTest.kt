package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.Transparency
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * RFC 5545 §3.3.3: a CAL-ADDRESS is any URI, not only `mailto:`. Servers emit
 * `urn:uuid:...` and principal hrefs (`/.../principal/`) as ORGANIZER/ATTENDEE
 * values. The parser strips a leading `mailto:` and stores the value bare, so
 * the generator must re-prepend `mailto:` ONLY for mailbox-shaped values and
 * pass any other URI form through verbatim.
 *
 * Without that guard a `urn:uuid:` attendee round-trips to the invalid
 * `mailto:urn:uuid:...`, which strict servers reject and which never matches
 * the original principal on the next pull.
 */
class ICalGeneratorCalAddressTest {

    private val generator = ICalGenerator()
    private val parser = ICalParser()

    private fun eventWith(organizer: Organizer, attendees: List<Attendee>): ICalEvent {
        val zone = ZoneId.of("America/New_York")
        val start = ZonedDateTime.of(2026, 1, 15, 14, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 1, 15, 15, 0, 0, 0, zone)
        return ICalEvent(
            uid = "cal-address@example.test",
            importId = "cal-address@example.test",
            summary = "Sync",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromZonedDateTime(start, false),
            dtEnd = ICalDateTime.fromZonedDateTime(end, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            rdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = organizer,
            attendees = attendees,
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun attendee(email: String) = Attendee(
        email = email,
        name = "Alice",
        partStat = PartStat.NEEDS_ACTION,
        role = AttendeeRole.REQ_PARTICIPANT,
        rsvp = true
    )

    @Test
    fun `urn-uuid attendee is emitted verbatim, not double-prefixed with mailto`() {
        val ics = generator.generate(
            eventWith(
                organizer = Organizer(email = "boss@example.test", name = "Boss", sentBy = null),
                attendees = listOf(attendee("urn:uuid:0c3f2d4e-9b1a-4f6e-8a2b-1c2d3e4f5061"))
            ),
            method = null
        )
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLine = unfolded.lines().first { it.startsWith("ATTENDEE") }
        assertTrue(
            attendeeLine.endsWith(":urn:uuid:0c3f2d4e-9b1a-4f6e-8a2b-1c2d3e4f5061"),
            "urn:uuid CAL-ADDRESS must be emitted verbatim, got: $attendeeLine"
        )
        assertFalse(
            attendeeLine.contains("mailto:urn:uuid:"),
            "must not double-prefix a non-mailto address, got: $attendeeLine"
        )
    }

    @Test
    fun `urn-uuid organizer is emitted verbatim, not double-prefixed with mailto`() {
        val ics = generator.generate(
            eventWith(
                organizer = Organizer(
                    email = "urn:uuid:11112222-3333-4444-5555-666677778888",
                    name = "Boss",
                    sentBy = null
                ),
                attendees = listOf(attendee("alice@example.test"))
            ),
            method = null
        )
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val organizerLine = unfolded.lines().first { it.startsWith("ORGANIZER") }
        assertTrue(
            organizerLine.endsWith(":urn:uuid:11112222-3333-4444-5555-666677778888"),
            "urn:uuid ORGANIZER must be emitted verbatim, got: $organizerLine"
        )
        assertFalse(
            organizerLine.contains("mailto:urn:uuid:"),
            "must not double-prefix a non-mailto ORGANIZER, got: $organizerLine"
        )
    }

    @Test
    fun `principal-href attendee is emitted verbatim`() {
        val ics = generator.generate(
            eventWith(
                organizer = Organizer(email = "boss@example.test", name = "Boss", sentBy = null),
                attendees = listOf(attendee("/646691839/principal/"))
            ),
            method = null
        )
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLine = unfolded.lines().first { it.startsWith("ATTENDEE") }
        assertTrue(
            attendeeLine.endsWith(":/646691839/principal/"),
            "principal href must be emitted verbatim, got: $attendeeLine"
        )
        assertFalse(
            attendeeLine.contains("mailto:/646691839"),
            "must not prefix a principal href with mailto:, got: $attendeeLine"
        )
    }

    @Test
    fun `mailbox-shaped attendee still gets the mailto prefix`() {
        val ics = generator.generate(
            eventWith(
                organizer = Organizer(email = "boss@example.test", name = "Boss", sentBy = null),
                attendees = listOf(attendee("alice@example.test"))
            ),
            method = null
        )
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLine = unfolded.lines().first { it.startsWith("ATTENDEE") }
        assertTrue(
            attendeeLine.endsWith(":mailto:alice@example.test"),
            "a plain email must still be emitted as mailto:, got: $attendeeLine"
        )
    }

    @Test
    fun `an already-mailto-prefixed value is not double-prefixed`() {
        // Defensive: a caller that stored the value with its scheme intact must
        // not get mailto:mailto:.
        val ics = generator.generate(
            eventWith(
                organizer = Organizer(email = "boss@example.test", name = "Boss", sentBy = null),
                attendees = listOf(attendee("mailto:alice@example.test"))
            ),
            method = null
        )
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLine = unfolded.lines().first { it.startsWith("ATTENDEE") }
        assertFalse(
            attendeeLine.contains("mailto:mailto:"),
            "must not produce mailto:mailto:, got: $attendeeLine"
        )
        assertTrue(
            attendeeLine.endsWith(":mailto:alice@example.test"),
            "exactly one mailto: prefix, got: $attendeeLine"
        )
    }

    @Test
    fun `urn-uuid attendee round-trips through parse and generate`() {
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip@example.test
            DTSTAMP:20260101T100000Z
            DTSTART:20260301T100000Z
            DTEND:20260301T110000Z
            SUMMARY:Round trip
            ORGANIZER:mailto:boss@example.test
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:urn:uuid:abcd1234-0000-0000-0000-00000000aaaa
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val parsed = parser.parseAllEvents(serverIcs).getOrNull()!!.first()
        val regenerated = generator.generate(parsed, method = null)
        val unfolded = regenerated.replace(Regex("""\r?\n[ \t]"""), "")
        val attendeeLine = unfolded.lines().first { it.startsWith("ATTENDEE") }
        assertTrue(
            attendeeLine.endsWith(":urn:uuid:abcd1234-0000-0000-0000-00000000aaaa"),
            "urn:uuid attendee must survive parse->generate verbatim, got: $attendeeLine"
        )
        assertFalse(attendeeLine.contains("mailto:urn:uuid:"))
    }
}
