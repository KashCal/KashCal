package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ICalParticipant and related enums (RFC 9073).
 */
class ICalParticipantTest {

    @Nested
    inner class ParticipantTypeTests {

        @Test
        fun `fromString parses all types correctly`() {
            assertEquals(ParticipantType.INDIVIDUAL, ParticipantType.fromString("INDIVIDUAL"))
            assertEquals(ParticipantType.INDIVIDUAL, ParticipantType.fromString("individual"))
            assertEquals(ParticipantType.GROUP, ParticipantType.fromString("GROUP"))
            assertEquals(ParticipantType.RESOURCE, ParticipantType.fromString("RESOURCE"))
            assertEquals(ParticipantType.ROOM, ParticipantType.fromString("ROOM"))
        }

        @Test
        fun `fromString returns UNKNOWN for invalid values`() {
            assertEquals(ParticipantType.UNKNOWN, ParticipantType.fromString("INVALID"))
            assertEquals(ParticipantType.UNKNOWN, ParticipantType.fromString(null))
            assertEquals(ParticipantType.UNKNOWN, ParticipantType.fromString(""))
        }

        @Test
        fun `toICalString returns correct values`() {
            assertEquals("INDIVIDUAL", ParticipantType.INDIVIDUAL.toICalString())
            assertEquals("GROUP", ParticipantType.GROUP.toICalString())
            assertEquals("RESOURCE", ParticipantType.RESOURCE.toICalString())
            assertEquals("ROOM", ParticipantType.ROOM.toICalString())
            assertEquals("UNKNOWN", ParticipantType.UNKNOWN.toICalString())
        }
    }

    @Nested
    inner class ParticipantRoleTests {

        @Test
        fun `fromString parses all roles correctly`() {
            assertEquals(ParticipantRole.CHAIR, ParticipantRole.fromString("CHAIR"))
            assertEquals(ParticipantRole.ATTENDEE, ParticipantRole.fromString("ATTENDEE"))
            assertEquals(ParticipantRole.OPT_PARTICIPANT, ParticipantRole.fromString("OPT-PARTICIPANT"))
            assertEquals(ParticipantRole.NON_PARTICIPANT, ParticipantRole.fromString("NON-PARTICIPANT"))
            assertEquals(ParticipantRole.CONTACT, ParticipantRole.fromString("CONTACT"))
            assertEquals(ParticipantRole.INFORMATIONAL, ParticipantRole.fromString("INFORMATIONAL"))
        }

        @Test
        fun `fromString normalizes underscores and hyphens`() {
            assertEquals(ParticipantRole.OPT_PARTICIPANT, ParticipantRole.fromString("OPT_PARTICIPANT"))
            assertEquals(ParticipantRole.NON_PARTICIPANT, ParticipantRole.fromString("NON_PARTICIPANT"))
        }

        @Test
        fun `fromString returns ATTENDEE for invalid values`() {
            assertEquals(ParticipantRole.ATTENDEE, ParticipantRole.fromString("INVALID"))
            assertEquals(ParticipantRole.ATTENDEE, ParticipantRole.fromString(null))
            assertEquals(ParticipantRole.ATTENDEE, ParticipantRole.fromString(""))
        }

        @Test
        fun `toICalString returns correct values with hyphens`() {
            assertEquals("CHAIR", ParticipantRole.CHAIR.toICalString())
            assertEquals("ATTENDEE", ParticipantRole.ATTENDEE.toICalString())
            assertEquals("OPT-PARTICIPANT", ParticipantRole.OPT_PARTICIPANT.toICalString())
            assertEquals("NON-PARTICIPANT", ParticipantRole.NON_PARTICIPANT.toICalString())
            assertEquals("CONTACT", ParticipantRole.CONTACT.toICalString())
            assertEquals("INFORMATIONAL", ParticipantRole.INFORMATIONAL.toICalString())
        }
    }

    @Nested
    inner class SchedulingAgentTests {

        @Test
        fun `fromString parses all agents correctly`() {
            assertEquals(SchedulingAgent.SERVER, SchedulingAgent.fromString("SERVER"))
            assertEquals(SchedulingAgent.SERVER, SchedulingAgent.fromString("server"))
            assertEquals(SchedulingAgent.CLIENT, SchedulingAgent.fromString("CLIENT"))
            assertEquals(SchedulingAgent.NONE, SchedulingAgent.fromString("NONE"))
        }

        @Test
        fun `fromString returns SERVER for invalid values`() {
            assertEquals(SchedulingAgent.SERVER, SchedulingAgent.fromString("INVALID"))
            assertEquals(SchedulingAgent.SERVER, SchedulingAgent.fromString(null))
            assertEquals(SchedulingAgent.SERVER, SchedulingAgent.fromString(""))
        }

        @Test
        fun `toICalString returns correct values`() {
            assertEquals("SERVER", SchedulingAgent.SERVER.toICalString())
            assertEquals("CLIENT", SchedulingAgent.CLIENT.toICalString())
            assertEquals("NONE", SchedulingAgent.NONE.toICalString())
        }
    }

    @Nested
    inner class ICalParticipantConstructorTests {

        @Test
        fun `create participant with minimal values`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com"
            )

            assertEquals("part-1", participant.uid)
            assertEquals("mailto:john@example.com", participant.calendarAddress)
            assertEquals(PartStat.NEEDS_ACTION, participant.participationStatus)
            assertEquals(setOf(ParticipantType.INDIVIDUAL), participant.participantTypes)
            assertEquals(setOf(ParticipantRole.ATTENDEE), participant.roles)
            assertEquals(SchedulingAgent.SERVER, participant.schedulingAgent)
            assertFalse(participant.rsvp)
        }

        @Test
        fun `create participant with all values`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com",
                name = "John Doe",
                participationStatus = PartStat.ACCEPTED,
                participantTypes = setOf(ParticipantType.INDIVIDUAL, ParticipantType.GROUP),
                roles = setOf(ParticipantRole.CHAIR, ParticipantRole.CONTACT),
                contact = "+1-555-1234",
                locationId = "loc-1",
                expectedDuration = Duration.ofHours(1),
                schedulingAgent = SchedulingAgent.CLIENT,
                rsvp = true,
                language = "en-US"
            )

            assertEquals("part-1", participant.uid)
            assertEquals("John Doe", participant.name)
            assertEquals(PartStat.ACCEPTED, participant.participationStatus)
            assertTrue(participant.participantTypes.contains(ParticipantType.GROUP))
            assertTrue(participant.roles.contains(ParticipantRole.CHAIR))
            assertEquals("+1-555-1234", participant.contact)
            assertEquals("loc-1", participant.locationId)
            assertEquals(Duration.ofHours(1), participant.expectedDuration)
            assertEquals(SchedulingAgent.CLIENT, participant.schedulingAgent)
            assertTrue(participant.rsvp)
            assertEquals("en-US", participant.language)
        }
    }

    @Nested
    inner class ICalParticipantHelperMethodsTests {

        @Test
        fun `hasAccepted returns true when ACCEPTED`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com",
                participationStatus = PartStat.ACCEPTED
            )

            assertTrue(participant.hasAccepted())
        }

        @Test
        fun `hasAccepted returns false when not ACCEPTED`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com",
                participationStatus = PartStat.TENTATIVE
            )

            assertFalse(participant.hasAccepted())
        }

        @Test
        fun `isChair returns true when CHAIR role present`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com",
                roles = setOf(ParticipantRole.CHAIR, ParticipantRole.ATTENDEE)
            )

            assertTrue(participant.isChair())
        }

        @Test
        fun `isChair returns false when CHAIR role not present`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com",
                roles = setOf(ParticipantRole.ATTENDEE)
            )

            assertFalse(participant.isChair())
        }

        @Test
        fun `email extracts from mailto address`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:john@example.com"
            )

            assertEquals("john@example.com", participant.email())
        }

        @Test
        fun `email handles uppercase MAILTO`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "MAILTO:jane@example.com"
            )

            assertEquals("jane@example.com", participant.email())
        }

        @Test
        fun `email handles plain email address`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "bob@example.com"
            )

            assertEquals("bob@example.com", participant.email())
        }

        @Test
        fun `email trims whitespace`() {
            val participant = ICalParticipant(
                uid = "part-1",
                calendarAddress = "mailto:  alice@example.com  "
            )

            assertEquals("alice@example.com", participant.email())
        }
    }

    @Nested
    inner class CompanionFactoryMethodsTests {

        @Test
        fun `fromEmail creates participant with mailto prefix`() {
            val participant = ICalParticipant.fromEmail(
                email = "john@example.com",
                name = "John Doe",
                uid = "part-123"
            )

            assertEquals("part-123", participant.uid)
            assertEquals("mailto:john@example.com", participant.calendarAddress)
            assertEquals("John Doe", participant.name)
        }

        @Test
        fun `fromEmail preserves existing mailto prefix`() {
            val participant = ICalParticipant.fromEmail(
                email = "mailto:john@example.com"
            )

            assertEquals("mailto:john@example.com", participant.calendarAddress)
        }
    }
}
