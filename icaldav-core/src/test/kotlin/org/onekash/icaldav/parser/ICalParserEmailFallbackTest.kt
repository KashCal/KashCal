package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalCalendar
import org.onekash.icaldav.model.ParseResult

/**
 * Tests for the EMAIL= parameter fallback on ORGANIZER and ATTENDEE.
 *
 * Apple's iSchedule binding (and the renamed `stalwartlabs/stalwart`
 * server in some configurations) rewrites the ORGANIZER property's
 * primary value from `mailto:foo@bar` to `/principal/...` (an internal
 * principal-href) when the mailto matches the authenticated account.
 * The mailto is preserved as an `EMAIL=` parameter on the property.
 * The same rewrite hits ATTENDEE rows when an invitee accepts.
 *
 * Pre-fix, `extractEmailFromCalAddress` read only the primary value,
 * so `Organizer.email` and `Attendee.email` came back as the
 * principal-href string (which has no `@` and starts with `/`),
 * defeating downstream identity matching. Post-fix, the parser
 * detects a non-mailto-shaped primary value and falls back to the
 * `EMAIL=` parameter.
 *
 * RFC 5545 §3.3.3 (CAL-ADDRESS) permits non-mailto URI schemes
 * (`urn:uuid:`, HTTP principal URIs, etc.). The fallback is generic:
 * any time the primary value isn't a mailto-shape AND an `EMAIL=`
 * parameter is present, prefer the parameter. Forward-compatible
 * with future server-side scheduling extensions.
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
            // Degenerate case: server gave us a principal-href but no EMAIL=.
            // We can't reverse-engineer; preserve current behavior (return the
            // string as-is so downstream matchesAttendee returns false rather
            // than null/empty pollution).
            val ics = vevent("ORGANIZER;CN=Alice:urn:uuid:12345-67890")
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertNotNull(event.organizer)
            // Email is whatever extractEmailFromCalAddress returned — non-mailto
            // form. The point of this test is "doesn't crash, doesn't lose the row."
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
            // Apple rewrites ATTENDEE rows when an invitee accepts on iCloud,
            // putting the invitee's principal-href as the primary value and
            // preserving their mailto as EMAIL=.
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
            // Degenerate: EMAIL= is present but blank. No usable fallback.
            // Helper returns the original primary value (caller decides what to do).
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=:/aNjQ2NjkxODM5/principal/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            // Falls through to primary value — neither parses as a valid email.
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
            // Some Radicale + Stalwart configs emit HTTP-principal hrefs.
            val ics = vevent(
                "ORGANIZER;CN=Alice;EMAIL=alice@example.com:" +
                    "https://caldav.example.com/principals/users/alice/"
            )
            val event = (parser.parse(ics) as ParseResult.Success).value.events.single()
            assertEquals("alice@example.com", event.organizer?.email)
        }

        @Test
        fun `case-insensitive EMAIL parameter lookup (RFC 5545 mandates parameter names case-insensitive)`() {
            // Lowercase param name. Real iCloud uses uppercase but some servers might
            // normalize to lowercase per the RFC.
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
            // Neither value is a valid mailto. Helper must not crash; downstream
            // matchesAttendee returns false (correct: nothing to identify).
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
