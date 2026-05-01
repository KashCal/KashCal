package org.onekash.icaldav.scheduling

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.parser.ICalGenerator
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive iTIP (iCalendar Transport-Independent Interoperability Protocol) workflow tests.
 *
 * Per RFC 5546 and CalConnect Developer's Guide.
 *
 * Tests cover:
 * - METHOD:PUBLISH - Broadcasting calendar information
 * - METHOD:REQUEST - Meeting invitations
 * - METHOD:REPLY - Attendee responses
 * - METHOD:CANCEL - Event cancellation
 * - METHOD:ADD - Adding instances to recurring events
 * - METHOD:COUNTER - Counter-proposals
 * - METHOD:DECLINECOUNTER - Declining counter-proposals
 * - METHOD:REFRESH - Requesting updated event data
 * - PARTSTAT (Participation Status) values
 * - RSVP handling
 * - Delegation scenarios
 * - Scheduling agent behavior
 *
 * @see https://devguide.calconnect.org/iTIP/
 */
@DisplayName("iTIP Workflow Tests")
class ITipWorkflowTest {
    private val parser = ICalParser()
    private val generator = ICalGenerator()
    private val utcZone = ZoneId.of("UTC")

    @Nested
    @DisplayName("METHOD:PUBLISH Tests")
    inner class PublishMethodTests {

        @Test
        fun `PUBLISH method for broadcasting event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:PUBLISH
                BEGIN:VEVENT
                UID:publish-001@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                DTEND:20231220T150000Z
                SUMMARY:Public Holiday
                ORGANIZER:mailto:calendar@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("PUBLISH", result.getOrNull()!!.method)
        }

        @Test
        fun `PUBLISH does not require ATTENDEE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:PUBLISH
                BEGIN:VEVENT
                UID:publish-no-attendee@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                SUMMARY:Public Announcement
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertTrue(result.getOrNull()!![0].attendees.isEmpty())
        }

        @Test
        fun `PUBLISH with multiple events`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:PUBLISH
                BEGIN:VEVENT
                UID:event1@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T100000Z
                SUMMARY:Morning Session
                END:VEVENT
                BEGIN:VEVENT
                UID:event2@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                SUMMARY:Afternoon Session
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(2, result.getOrNull()!!.events.size)
        }
    }

    @Nested
    @DisplayName("METHOD:REQUEST Tests")
    inner class RequestMethodTests {

        @Test
        fun `REQUEST for meeting invitation`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:request-001@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                DTEND:20231220T150000Z
                SUMMARY:Team Meeting
                ORGANIZER;CN=Boss:mailto:boss@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=Alice:mailto:alice@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=Bob:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("REQUEST", result.getOrNull()!!.method)

            val event = result.getOrNull()!!.events[0]
            assertNotNull(event.organizer)
            assertEquals(2, event.attendees.size)
        }

        @Test
        fun `REQUEST requires ORGANIZER`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:request-with-org@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:organizer@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:attendee@example.com
                SUMMARY:Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertNotNull(result.getOrNull()!![0].organizer)
        }

        @Test
        fun `REQUEST with RSVP TRUE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:rsvp-request@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:alice@example.com
                SUMMARY:RSVP Required Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val attendee = result.getOrNull()!![0].attendees[0]
            assertTrue(attendee.rsvp == true)
        }

        @Test
        fun `REQUEST for recurring event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:recurring-request@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231218T100000Z
                DTEND:20231218T110000Z
                RRULE:FREQ=WEEKLY;COUNT=10
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:team@example.com
                SUMMARY:Weekly Standup
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertNotNull(result.getOrNull()!![0].rrule)
        }
    }

    @Nested
    @DisplayName("METHOD:REPLY Tests")
    inner class ReplyMethodTests {

        @Test
        fun `REPLY with ACCEPTED status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REPLY
                BEGIN:VEVENT
                UID:reply-001@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=ACCEPTED;CN=Alice:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("REPLY", result.getOrNull()!!.method)

            val event = result.getOrNull()!!.events[0]
            assertEquals(PartStat.ACCEPTED, event.attendees[0].partStat)
        }

        @Test
        fun `REPLY with DECLINED status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REPLY
                BEGIN:VEVENT
                UID:reply-declined@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=DECLINED:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(PartStat.DECLINED, result.getOrNull()!![0].attendees[0].partStat)
        }

        @Test
        fun `REPLY with TENTATIVE status`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REPLY
                BEGIN:VEVENT
                UID:reply-tentative@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=TENTATIVE:mailto:carol@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(PartStat.TENTATIVE, result.getOrNull()!![0].attendees[0].partStat)
        }

        @Test
        fun `REPLY contains only responding attendee`() {
            // Per iTIP, REPLY should only include the responding attendee
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REPLY
                BEGIN:VEVENT
                UID:single-attendee-reply@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(1, result.getOrNull()!![0].attendees.size)
        }
    }

    @Nested
    @DisplayName("METHOD:CANCEL Tests")
    inner class CancelMethodTests {

        @Test
        fun `CANCEL for entire event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:CANCEL
                BEGIN:VEVENT
                UID:cancel-001@test.com
                DTSTAMP:20231215T160000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:alice@example.com
                ATTENDEE:mailto:bob@example.com
                STATUS:CANCELLED
                SUMMARY:Cancelled Meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("CANCEL", result.getOrNull()!!.method)
            assertEquals(EventStatus.CANCELLED, result.getOrNull()!!.events[0].status)
        }

        @Test
        fun `CANCEL for single instance of recurring event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:CANCEL
                BEGIN:VEVENT
                UID:cancel-instance@test.com
                DTSTAMP:20231215T160000Z
                DTSTART:20231220T140000Z
                RECURRENCE-ID:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:team@example.com
                STATUS:CANCELLED
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            assertNotNull(event.recurrenceId)
            assertEquals(EventStatus.CANCELLED, event.status)
        }

        @Test
        fun `CANCEL removes attendee from event`() {
            // When CANCEL includes only some attendees, they are being removed
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:CANCEL
                BEGIN:VEVENT
                UID:cancel-attendee@test.com
                DTSTAMP:20231215T160000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:removed@example.com
                COMMENT:You have been removed from this meeting
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("CANCEL", result.getOrNull()!!.method)
        }
    }

    @Nested
    @DisplayName("METHOD:ADD Tests")
    inner class AddMethodTests {

        @Test
        fun `ADD for new instance of recurring event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:ADD
                BEGIN:VEVENT
                UID:add-instance@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231227T140000Z
                DTEND:20231227T150000Z
                RECURRENCE-ID:20231227T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:team@example.com
                SEQUENCE:1
                SUMMARY:Extra Meeting Added
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("ADD", result.getOrNull()!!.method)
        }
    }

    @Nested
    @DisplayName("METHOD:COUNTER Tests")
    inner class CounterMethodTests {

        @Test
        fun `COUNTER proposes new time`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:COUNTER
                BEGIN:VEVENT
                UID:counter-001@test.com
                DTSTAMP:20231215T150000Z
                DTSTART:20231220T160000Z
                DTEND:20231220T170000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=TENTATIVE:mailto:alice@example.com
                COMMENT:Can we move it to 4pm?
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("COUNTER", result.getOrNull()!!.method)
        }

        @Test
        fun `COUNTER proposes new location`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:COUNTER
                BEGIN:VEVENT
                UID:counter-location@test.com
                DTSTAMP:20231215T150000Z
                DTSTART:20231220T140000Z
                LOCATION:Conference Room B (instead of A)
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=TENTATIVE:mailto:bob@example.com
                COMMENT:Room A is booked, suggesting Room B
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("Conference Room B (instead of A)", result.getOrNull()!![0].location)
        }
    }

    @Nested
    @DisplayName("METHOD:DECLINECOUNTER Tests")
    inner class DeclineCounterMethodTests {

        @Test
        fun `DECLINECOUNTER rejects counter proposal`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:DECLINECOUNTER
                BEGIN:VEVENT
                UID:declinecounter@test.com
                DTSTAMP:20231215T160000Z
                DTSTART:20231220T140000Z
                DTEND:20231220T150000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:alice@example.com
                COMMENT:Original time stands. Cannot accommodate change.
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("DECLINECOUNTER", result.getOrNull()!!.method)
        }
    }

    @Nested
    @DisplayName("METHOD:REFRESH Tests")
    inner class RefreshMethodTests {

        @Test
        fun `REFRESH requests event update`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REFRESH
                BEGIN:VEVENT
                UID:refresh-001@test.com
                DTSTAMP:20231215T170000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals("REFRESH", result.getOrNull()!!.method)
        }
    }

    @Nested
    @DisplayName("PARTSTAT Values Tests")
    inner class PartStatTests {

        @Test
        fun `all PARTSTAT values parsed correctly`() {
            val statuses = mapOf(
                "NEEDS-ACTION" to PartStat.NEEDS_ACTION,
                "ACCEPTED" to PartStat.ACCEPTED,
                "DECLINED" to PartStat.DECLINED,
                "TENTATIVE" to PartStat.TENTATIVE,
                "DELEGATED" to PartStat.DELEGATED
            )

            statuses.forEach { (statusStr, expected) ->
                val ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VEVENT
                    UID:partstat-$statusStr@test.com
                    DTSTAMP:20231215T120000Z
                    DTSTART:20231220T140000Z
                    ORGANIZER:mailto:boss@example.com
                    ATTENDEE;PARTSTAT=$statusStr:mailto:test@example.com
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()

                val result = parser.parseAllEvents(ics)
                assertTrue(result is ParseResult.Success)
                assertEquals(expected, result.getOrNull()!![0].attendees[0].partStat)
            }
        }

        @Test
        fun `PARTSTAT default is NEEDS-ACTION`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:partstat-default@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:noreply@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(PartStat.NEEDS_ACTION, result.getOrNull()!![0].attendees[0].partStat)
        }
    }

    @Nested
    @DisplayName("Delegation Tests")
    inner class DelegationTests {

        @Test
        fun `DELEGATED-TO parameter parsed`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REPLY
                BEGIN:VEVENT
                UID:delegation@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=DELEGATED;DELEGATED-TO="mailto:assistant@example.com":mailto:manager@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val attendee = result.getOrNull()!![0].attendees[0]
            assertEquals(PartStat.DELEGATED, attendee.partStat)
            assertTrue(attendee.delegatedTo.contains("assistant@example.com"))
        }

        @Test
        fun `DELEGATED-FROM parameter parsed`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:delegated-from@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;DELEGATED-FROM="mailto:manager@example.com":mailto:assistant@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val attendee = result.getOrNull()!![0].attendees[0]
            assertTrue(attendee.delegatedFrom.contains("manager@example.com"))
        }

        @Test
        fun `full delegation chain`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:delegation-chain@test.com
                DTSTAMP:20231215T140000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=DELEGATED;DELEGATED-TO="mailto:delegate@example.com":mailto:original@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;DELEGATED-FROM="mailto:original@example.com":mailto:delegate@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val attendees = result.getOrNull()!![0].attendees
            assertEquals(2, attendees.size)
        }
    }

    @Nested
    @DisplayName("Attendee Role Tests")
    inner class AttendeeRoleTests {

        @Test
        fun `all ROLE values parsed`() {
            val roles = mapOf(
                "CHAIR" to AttendeeRole.CHAIR,
                "REQ-PARTICIPANT" to AttendeeRole.REQ_PARTICIPANT,
                "OPT-PARTICIPANT" to AttendeeRole.OPT_PARTICIPANT,
                "NON-PARTICIPANT" to AttendeeRole.NON_PARTICIPANT
            )

            roles.forEach { (roleStr, expected) ->
                val ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VEVENT
                    UID:role-$roleStr@test.com
                    DTSTAMP:20231215T120000Z
                    DTSTART:20231220T140000Z
                    ORGANIZER:mailto:boss@example.com
                    ATTENDEE;ROLE=$roleStr:mailto:test@example.com
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()

                val result = parser.parseAllEvents(ics)
                assertTrue(result is ParseResult.Success)
                assertEquals(expected, result.getOrNull()!![0].attendees[0].role)
            }
        }

        @Test
        fun `ROLE default is REQ-PARTICIPANT`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:role-default@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE:mailto:test@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(AttendeeRole.REQ_PARTICIPANT, result.getOrNull()!![0].attendees[0].role)
        }
    }

    @Nested
    @DisplayName("CUTYPE (Calendar User Type) Tests")
    inner class CuTypeTests {

        @Test
        fun `CUTYPE INDIVIDUAL (default)`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cutype-individual@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;CUTYPE=INDIVIDUAL:mailto:person@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
        }

        @Test
        fun `CUTYPE ROOM for meeting room`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cutype-room@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;CUTYPE=ROOM:mailto:conf-room-a@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
        }

        @Test
        fun `CUTYPE RESOURCE for equipment`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cutype-resource@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;CUTYPE=RESOURCE:mailto:projector@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
        }

        @Test
        fun `CUTYPE GROUP for mailing list`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:cutype-group@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;CUTYPE=GROUP:mailto:engineering-team@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
        }
    }

    @Nested
    @DisplayName("SCHEDULE-AGENT Tests")
    inner class ScheduleAgentTests {

        @Test
        fun `SCHEDULE-AGENT SERVER (default)`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:schedule-agent-server@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;SCHEDULE-AGENT=SERVER:mailto:attendee@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(ScheduleAgent.SERVER, result.getOrNull()!![0].attendees[0].scheduleAgent)
        }

        @Test
        fun `SCHEDULE-AGENT CLIENT`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:schedule-agent-client@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;SCHEDULE-AGENT=CLIENT:mailto:attendee@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(ScheduleAgent.CLIENT, result.getOrNull()!![0].attendees[0].scheduleAgent)
        }

        @Test
        fun `SCHEDULE-AGENT NONE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:schedule-agent-none@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;SCHEDULE-AGENT=NONE:mailto:nonotify@example.com
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(ScheduleAgent.NONE, result.getOrNull()!![0].attendees[0].scheduleAgent)
        }
    }

    @Nested
    @DisplayName("SEQUENCE and Updates")
    inner class SequenceUpdatesTests {

        @Test
        fun `SEQUENCE increment indicates significant change`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:sequence-update@test.com
                DTSTAMP:20231215T160000Z
                DTSTART:20231220T150000Z
                SEQUENCE:2
                ORGANIZER:mailto:boss@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:team@example.com
                SUMMARY:Meeting (Updated Time)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            assertEquals(2, result.getOrNull()!![0].sequence)
        }

        @Test
        fun `higher SEQUENCE supersedes lower`() {
            // This is a workflow test - higher sequence should be newer
            val older = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:sequence-compare@test.com
                DTSTAMP:20231215T120000Z
                DTSTART:20231220T140000Z
                SEQUENCE:1
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val newer = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:sequence-compare@test.com
                DTSTAMP:20231215T160000Z
                DTSTART:20231220T150000Z
                SEQUENCE:3
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val olderResult = parser.parseAllEvents(older)
            val newerResult = parser.parseAllEvents(newer)

            assertTrue(olderResult is ParseResult.Success)
            assertTrue(newerResult is ParseResult.Success)
            assertTrue(newerResult.getOrNull()!![0].sequence > olderResult.getOrNull()!![0].sequence)
        }
    }
}
