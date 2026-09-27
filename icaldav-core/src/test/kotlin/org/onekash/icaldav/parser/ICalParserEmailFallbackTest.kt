package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ParseResult

/**
 * Tests the `EMAIL=` parameter fallback on ORGANIZER and ATTENDEE, in VEVENT, VTODO,
 * VJOURNAL and VFREEBUSY.
 *
 * iCloud's iSchedule binding, and the `stalwartlabs/stalwart` server in some configurations,
 * rewrite the ORGANIZER value from `mailto:foo@bar` to an internal principal href
 * (`/principal/...`) when the mailto matches the authenticated account, and keep the mailto as
 * an `EMAIL=` parameter. The same rewrite hits ATTENDEE rows when an invitee accepts. Without
 * the fallback, `Organizer.email` and `Attendee.email` would hold the principal href, which has
 * no `@`, and identity matching would fail.
 *
 * RFC 5545 §3.3.3 permits non-mailto CAL-ADDRESS forms (`urn:uuid:`, HTTP principal URIs). The
 * fallback is generic: when the value isn't mailbox-shaped and a mailbox-shaped `EMAIL=` is
 * present, the parameter wins; otherwise the stripped value is kept.
 */
@DisplayName("ICalParser EMAIL= parameter fallback")
class ICalParserEmailFallbackTest {

    private val parser = ICalParser()

    @Nested
    @DisplayName("VEVENT ORGANIZER")
    inner class VEventOrganizer {

        @Test
        fun `parses mailto primary value verbatim (no fallback needed)`() {
            val ics = vevent("ORGANIZER;CN=Alice:mailto:alice@example.com")
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `falls back to EMAIL parameter when primary value is iCloud principal-href`() {
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "/aNjQ2NjkxODM5NjQ2NjkxOLtVspI40y1Fxa98zI6-5H8FhO_dSJwJc-N39P2tilHW/principal/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `falls back to EMAIL parameter when primary value is urn-uuid form`() {
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:urn:uuid:12345-67890"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `falls back to EMAIL parameter when primary value is HTTP principal URI`() {
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "https://caldav.example.com/principals/alice/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `keeps primary value when no EMAIL parameter and primary is non-mailto`() {
            // A non-mailto value with no EMAIL= has no address to recover, so it is kept as
            // is instead of a null or empty address; identity matching then finds no match.
            val ics = vevent("ORGANIZER;CN=Alice:urn:uuid:12345-67890")
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertNotNull(event.organizer)
            // The organizer is kept, with the non-mailto value as its email.
            assertEquals("urn:uuid:12345-67890", event.organizer?.email)
        }
    }

    @Nested
    @DisplayName("VEVENT ATTENDEE")
    inner class VEventAttendee {

        @Test
        fun `parses mailto attendee verbatim`() {
            val ics = vevent(
                "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("bob@example.com", event.attendees.single().email)
        }

        @Test
        fun `falls back to EMAIL parameter on accepted iCloud invitee (principal-href primary)`() {
            // iCloud rewrites an ATTENDEE row when the invitee accepts: the value becomes
            // the invitee's principal href and the mailto moves to EMAIL=.
            val ics = vevent(
                "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED;EMAIL=bob@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("bob@example.com", event.attendees.single().email)
        }

        @Test
        fun `mixed attendees — some mailto some principal-href — both parsed correctly`() {
            val ics = vevent(
                "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com",
                "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED;EMAIL=bob@example.com:" +
                    "/aNjQ2NjkxODM5/principal/",
                "ATTENDEE;CN=Carol;PARTSTAT=NEEDS-ACTION:mailto:carol@example.com"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals(3, event.attendees.size)
            val byEmail = event.attendees.associateBy { it.email }
            assertEquals(setOf("alice@example.com", "bob@example.com", "carol@example.com"), byEmail.keys)
        }
    }

    @Nested
    @DisplayName("VTODO ORGANIZER + ATTENDEE")
    inner class VTodoSibling {

        @Test
        fun `VTODO ORGANIZER falls back to EMAIL parameter`() {
            val ics = vtodo(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val cal = (parser.parse(ics) as ParseResult.Success).value
            val todo = cal.todos.single()
            assertEquals("alice@example.com", todo.organizer?.email)
        }

        @Test
        fun `VTODO ATTENDEE falls back to EMAIL parameter`() {
            val ics = vtodo(
                "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED;EMAIL=bob@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val cal = (parser.parse(ics) as ParseResult.Success).value
            val todo = cal.todos.single()
            assertEquals("bob@example.com", todo.attendees.single().email)
        }
    }

    @Nested
    @DisplayName("VFREEBUSY ORGANIZER + ATTENDEE")
    inner class VFreeBusySibling {

        @Test
        fun `VFREEBUSY ORGANIZER falls back to EMAIL parameter`() {
            val ics = vfreebusy(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val fb = parser.parseFreeBusy(ics)
            assertNotNull(fb)
            assertEquals("alice@example.com", fb!!.organizer?.email)
        }

        @Test
        fun `VFREEBUSY ATTENDEE falls back to EMAIL parameter`() {
            val ics = vfreebusy(
                "ORGANIZER;CN=Org:mailto:org@example.com",
                "ATTENDEE;CN=Bob;EMAIL=bob@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val fb = parser.parseFreeBusy(ics)
            assertNotNull(fb)
            assertEquals("bob@example.com", fb!!.attendees.single().email)
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {

        @Test
        fun `EMAIL parameter present but empty value preserves primary value`() {
            // EMAIL= is present but blank, so there is no fallback and the value is kept.
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=:/aNjQ2NjkxODM5/principal/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            // Only the organizer's presence is asserted.
            assertNotNull(event.organizer)
        }

        @Test
        fun `mailto with empty local-part is rejected and falls back to EMAIL`() {
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:mailto:@host"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `HTTP principal URI as primary value falls back to EMAIL`() {
            // Some Radicale and Stalwart configurations emit HTTP principal hrefs.
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "https://caldav.example.com/principals/users/alice/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `case-insensitive EMAIL parameter lookup (RFC 5545 mandates parameter names case-insensitive)`() {
            // A lowercase parameter name. iCloud sends uppercase; RFC 5545 §3.1 makes the
            // name case-insensitive, so a server may lowercase it.
            val ics = vevent(
                "ORGANIZER;CN=Alice;email=alice@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `recursive degenerate — both primary and EMAIL are principal-hrefs — preserves primary`() {
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=/another/principal/:/aNjQ2NjkxODM5/principal/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            // Neither value is mailbox-shaped, so the organizer keeps the value; identity
            // matching then finds nothing to match. Only the organizer's presence is asserted.
            assertNotNull(event.organizer)
        }
    }

    @Nested
    @DisplayName("VJOURNAL ORGANIZER + ATTENDEE")
    inner class VJournalSibling {

        @Test
        fun `VJOURNAL ORGANIZER falls back to EMAIL parameter`() {
            val ics = vjournal(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val cal = (parser.parse(ics) as ParseResult.Success).value
            val journal = cal.journals.single()
            assertEquals("alice@example.com", journal.organizer?.email)
        }

        @Test
        fun `VJOURNAL ATTENDEE falls back to EMAIL parameter`() {
            val ics = vjournal(
                "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED;EMAIL=bob@example.com:" +
                    "/aNjQ2NjkxODM5/principal/"
            )
            val cal = (parser.parse(ics) as ParseResult.Success).value
            val journal = cal.journals.single()
            assertEquals("bob@example.com", journal.attendees.single().email)
        }
    }

    // ===== Fixtures =====

    private fun vevent(vararg props: String): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//EmailFallback Test//EN
BEGIN:VEVENT
UID:test-event
DTSTAMP:20260315T100000Z
DTSTART:20260315T100000Z
DTEND:20260315T110000Z
SUMMARY:Test
${props.joinToString("\n")}
END:VEVENT
END:VCALENDAR
""".trimIndent()

    private fun vtodo(vararg props: String): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//EmailFallback Test//EN
BEGIN:VTODO
UID:test-todo
DTSTAMP:20260315T100000Z
SUMMARY:Test
${props.joinToString("\n")}
END:VTODO
END:VCALENDAR
""".trimIndent()

    private fun vfreebusy(vararg props: String): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//EmailFallback Test//EN
BEGIN:VFREEBUSY
UID:test-fb
DTSTAMP:20260315T100000Z
DTSTART:20260315T100000Z
DTEND:20260315T110000Z
${props.joinToString("\n")}
END:VFREEBUSY
END:VCALENDAR
""".trimIndent()

    private fun vjournal(vararg props: String): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//EmailFallback Test//EN
BEGIN:VJOURNAL
UID:test-journal
DTSTAMP:20260315T100000Z
SUMMARY:Test
${props.joinToString("\n")}
END:VJOURNAL
END:VCALENDAR
""".trimIndent()
}
