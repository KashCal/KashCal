package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import java.time.Duration
import java.time.ZoneId

/**
 * Comprehensive tests for ICalEvent model.
 *
 * Tests cover:
 * - Event construction and validation
 * - ImportId generation and parsing
 * - Effective end time calculation
 * - Recurrence detection
 * - Modified instance detection
 * - Enum parsing (EventStatus, Transparency, Classification)
 */
class ICalEventTest {

    // ==================== Test Data Helpers ====================

    private fun createMinimalEvent(
        uid: String = "test-uid-123",
        summary: String? = "Test Event",
        dtStart: ICalDateTime = ICalDateTime.parse("20231215T100000Z")
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = null,
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
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun createEventWithEnd(
        dtStart: ICalDateTime,
        dtEnd: ICalDateTime
    ): ICalEvent {
        return createMinimalEvent(dtStart = dtStart).copy(dtEnd = dtEnd)
    }

    private fun createEventWithDuration(
        dtStart: ICalDateTime,
        duration: Duration
    ): ICalEvent {
        return createMinimalEvent(dtStart = dtStart).copy(duration = duration)
    }

    // ==================== ImportId Tests ====================

    @Nested
    inner class ImportIdTests {

        @Test
        fun `generateImportId without recurrenceId returns uid`() {
            val importId = ICalEvent.generateImportId("test-uid-123", null)
            assertEquals("test-uid-123", importId)
        }

        @Test
        fun `generateImportId with recurrenceId includes RECID`() {
            val recurrenceId = ICalDateTime.parse("20231215T100000Z")
            val importId = ICalEvent.generateImportId("test-uid-123", recurrenceId)
            assertEquals("test-uid-123:RECID:20231215T100000Z", importId)
        }

        @Test
        fun `parseImportId without RECID returns uid and null`() {
            val (uid, recid) = ICalEvent.parseImportId("test-uid-123")
            assertEquals("test-uid-123", uid)
            assertNull(recid)
        }

        @Test
        fun `parseImportId with RECID extracts both parts`() {
            val (uid, recid) = ICalEvent.parseImportId("test-uid-123:RECID:20231215T100000Z")
            assertEquals("test-uid-123", uid)
            assertEquals("20231215T100000Z", recid)
        }

        @Test
        fun `parseImportId handles complex UIDs with special characters`() {
            val (uid, recid) = ICalEvent.parseImportId("uid-with-dashes_and_underscores@domain.com")
            assertEquals("uid-with-dashes_and_underscores@domain.com", uid)
            assertNull(recid)
        }

        @Test
        fun `parseImportId handles iCloud-style UIDs`() {
            val icloudUid = "1A2B3C4D-5E6F-7890-ABCD-EF1234567890"
            val (uid, recid) = ICalEvent.parseImportId("$icloudUid:RECID:20231215T100000Z")
            assertEquals(icloudUid, uid)
            assertEquals("20231215T100000Z", recid)
        }

        @Test
        fun `round-trip importId generation and parsing`() {
            val originalUid = "test-uid-123"
            val originalRecurrenceId = ICalDateTime.parse("20231215T100000Z")

            val importId = ICalEvent.generateImportId(originalUid, originalRecurrenceId)
            val (parsedUid, parsedRecid) = ICalEvent.parseImportId(importId)

            assertEquals(originalUid, parsedUid)
            assertEquals(originalRecurrenceId.toICalString(), parsedRecid)
        }
    }

    // ==================== Effective End Time Tests ====================

    @Nested
    inner class EffectiveEndTests {

        @Test
        fun `effectiveEnd returns dtEnd when present`() {
            val dtStart = ICalDateTime.parse("20231215T100000Z")
            val dtEnd = ICalDateTime.parse("20231215T110000Z")
            val event = createEventWithEnd(dtStart, dtEnd)

            assertEquals(dtEnd, event.effectiveEnd())
        }

        @Test
        fun `effectiveEnd calculates from duration when dtEnd absent`() {
            val dtStart = ICalDateTime.parse("20231215T100000Z")
            val duration = Duration.ofHours(2)
            val event = createEventWithDuration(dtStart, duration)

            val effectiveEnd = event.effectiveEnd()
            val expectedEnd = dtStart.timestamp + duration.toMillis()

            assertEquals(expectedEnd, effectiveEnd.timestamp)
        }

        @Test
        fun `effectiveEnd adds 24 hours for all-day event without end`() {
            val dtStart = ICalDateTime.parse("20231215") // All-day
            val event = createMinimalEvent(dtStart = dtStart).copy(isAllDay = true)

            val effectiveEnd = event.effectiveEnd()
            val expectedEnd = dtStart.timestamp + 86400000L // 24 hours

            assertEquals(expectedEnd, effectiveEnd.timestamp)
            assertTrue(effectiveEnd.isDate)
        }

        @Test
        fun `effectiveEnd returns dtStart for instant event`() {
            val dtStart = ICalDateTime.parse("20231215T100000Z")
            val event = createMinimalEvent(dtStart = dtStart) // No dtEnd, no duration

            assertEquals(dtStart, event.effectiveEnd())
        }

        @Test
        fun `effectiveEnd preserves timezone from dtStart`() {
            val dtStart = ICalDateTime.parse("20231215T100000", "America/New_York")
            val duration = Duration.ofHours(1)
            val event = createEventWithDuration(dtStart, duration)

            val effectiveEnd = event.effectiveEnd()
            assertEquals(ZoneId.of("America/New_York"), effectiveEnd.timezone)
        }
    }

    // ==================== Recurrence Detection Tests ====================

    @Nested
    inner class RecurrenceTests {

        @Test
        fun `isRecurring returns false for single event`() {
            val event = createMinimalEvent()
            assertFalse(event.isRecurring())
        }

        @Test
        fun `isRecurring returns true when rrule present`() {
            val rrule = RRule(freq = Frequency.DAILY, interval = 1)
            val event = createMinimalEvent().copy(rrule = rrule)
            assertTrue(event.isRecurring())
        }

        @Test
        fun `isRecurring returns true when rdates present`() {
            val rdates = listOf(ICalDateTime.parse("20231216T100000Z"))
            val event = createMinimalEvent().copy(rdates = rdates)
            assertTrue(event.isRecurring())
        }

        @Test
        fun `isRecurring returns true when both rrule and rdates present`() {
            val rrule = RRule(freq = Frequency.DAILY, interval = 1)
            val rdates = listOf(ICalDateTime.parse("20231216T100000Z"))
            val event = createMinimalEvent().copy(rrule = rrule, rdates = rdates)
            assertTrue(event.isRecurring())
        }
    }

    // ==================== Modified Instance Tests ====================

    @Nested
    inner class ModifiedInstanceTests {

        @Test
        fun `isModifiedInstance returns false when recurrenceId is null`() {
            val event = createMinimalEvent()
            assertFalse(event.isModifiedInstance())
        }

        @Test
        fun `isModifiedInstance returns true when recurrenceId is set`() {
            val recurrenceId = ICalDateTime.parse("20231215T100000Z")
            val event = createMinimalEvent().copy(recurrenceId = recurrenceId)
            assertTrue(event.isModifiedInstance())
        }

        @Test
        fun `masterUid returns uid regardless of recurrenceId`() {
            val event = createMinimalEvent(uid = "master-uid")
            assertEquals("master-uid", event.masterUid())

            val modifiedInstance = event.copy(
                recurrenceId = ICalDateTime.parse("20231215T100000Z")
            )
            assertEquals("master-uid", modifiedInstance.masterUid())
        }
    }

    // ==================== EventStatus Tests ====================

    @Nested
    inner class EventStatusTests {

        @Test
        fun `fromString parses CONFIRMED`() {
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString("CONFIRMED"))
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString("confirmed"))
        }

        @Test
        fun `fromString parses TENTATIVE`() {
            assertEquals(EventStatus.TENTATIVE, EventStatus.fromString("TENTATIVE"))
            assertEquals(EventStatus.TENTATIVE, EventStatus.fromString("tentative"))
        }

        @Test
        fun `fromString parses CANCELLED`() {
            assertEquals(EventStatus.CANCELLED, EventStatus.fromString("CANCELLED"))
            assertEquals(EventStatus.CANCELLED, EventStatus.fromString("cancelled"))
        }

        @Test
        fun `fromString defaults to CONFIRMED for unknown values`() {
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString(null))
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString(""))
            assertEquals(EventStatus.CONFIRMED, EventStatus.fromString("UNKNOWN"))
        }

        @Test
        fun `toICalString returns correct format`() {
            assertEquals("CONFIRMED", EventStatus.CONFIRMED.toICalString())
            assertEquals("TENTATIVE", EventStatus.TENTATIVE.toICalString())
            assertEquals("CANCELLED", EventStatus.CANCELLED.toICalString())
        }
    }

    // ==================== Transparency Tests ====================

    @Nested
    inner class TransparencyTests {

        @Test
        fun `fromString parses OPAQUE`() {
            assertEquals(Transparency.OPAQUE, Transparency.fromString("OPAQUE"))
            assertEquals(Transparency.OPAQUE, Transparency.fromString("opaque"))
        }

        @Test
        fun `fromString parses TRANSPARENT`() {
            assertEquals(Transparency.TRANSPARENT, Transparency.fromString("TRANSPARENT"))
            assertEquals(Transparency.TRANSPARENT, Transparency.fromString("transparent"))
        }

        @Test
        fun `fromString defaults to OPAQUE for unknown values`() {
            assertEquals(Transparency.OPAQUE, Transparency.fromString(null))
            assertEquals(Transparency.OPAQUE, Transparency.fromString(""))
            assertEquals(Transparency.OPAQUE, Transparency.fromString("UNKNOWN"))
        }

        @Test
        fun `toICalString returns correct format`() {
            assertEquals("OPAQUE", Transparency.OPAQUE.toICalString())
            assertEquals("TRANSPARENT", Transparency.TRANSPARENT.toICalString())
        }
    }

    // ==================== Classification Tests ====================

    @Nested
    inner class ClassificationTests {

        @Test
        fun `fromString parses PUBLIC`() {
            assertEquals(Classification.PUBLIC, Classification.fromString("PUBLIC"))
            assertEquals(Classification.PUBLIC, Classification.fromString("public"))
        }

        @Test
        fun `fromString parses PRIVATE`() {
            assertEquals(Classification.PRIVATE, Classification.fromString("PRIVATE"))
            assertEquals(Classification.PRIVATE, Classification.fromString("private"))
        }

        @Test
        fun `fromString parses CONFIDENTIAL`() {
            assertEquals(Classification.CONFIDENTIAL, Classification.fromString("CONFIDENTIAL"))
            assertEquals(Classification.CONFIDENTIAL, Classification.fromString("confidential"))
        }

        @Test
        fun `fromString returns null for unknown values`() {
            assertNull(Classification.fromString(null))
            assertNull(Classification.fromString(""))
            assertNull(Classification.fromString("UNKNOWN"))
        }

        @Test
        fun `toICalString returns correct format`() {
            assertEquals("PUBLIC", Classification.PUBLIC.toICalString())
            assertEquals("PRIVATE", Classification.PRIVATE.toICalString())
            assertEquals("CONFIDENTIAL", Classification.CONFIDENTIAL.toICalString())
        }
    }

    // ==================== PartStat Tests ====================

    @Nested
    inner class PartStatTests {

        @Test
        fun `fromString parses all valid values`() {
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString("NEEDS-ACTION"))
            assertEquals(PartStat.ACCEPTED, PartStat.fromString("ACCEPTED"))
            assertEquals(PartStat.DECLINED, PartStat.fromString("DECLINED"))
            assertEquals(PartStat.TENTATIVE, PartStat.fromString("TENTATIVE"))
            assertEquals(PartStat.DELEGATED, PartStat.fromString("DELEGATED"))
        }

        @Test
        fun `fromString is case insensitive`() {
            assertEquals(PartStat.ACCEPTED, PartStat.fromString("accepted"))
            assertEquals(PartStat.DECLINED, PartStat.fromString("Declined"))
        }

        @Test
        fun `fromString defaults to NEEDS_ACTION`() {
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString(null))
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString(""))
            assertEquals(PartStat.NEEDS_ACTION, PartStat.fromString("UNKNOWN"))
        }

        @Test
        fun `toICalString uses hyphens`() {
            assertEquals("NEEDS-ACTION", PartStat.NEEDS_ACTION.toICalString())
        }
    }

    // ==================== AttendeeRole Tests ====================

    @Nested
    inner class AttendeeRoleTests {

        @Test
        fun `fromString parses all valid values`() {
            assertEquals(AttendeeRole.CHAIR, AttendeeRole.fromString("CHAIR"))
            assertEquals(AttendeeRole.REQ_PARTICIPANT, AttendeeRole.fromString("REQ-PARTICIPANT"))
            assertEquals(AttendeeRole.OPT_PARTICIPANT, AttendeeRole.fromString("OPT-PARTICIPANT"))
            assertEquals(AttendeeRole.NON_PARTICIPANT, AttendeeRole.fromString("NON-PARTICIPANT"))
        }

        @Test
        fun `fromString defaults to REQ_PARTICIPANT`() {
            assertEquals(AttendeeRole.REQ_PARTICIPANT, AttendeeRole.fromString(null))
            assertEquals(AttendeeRole.REQ_PARTICIPANT, AttendeeRole.fromString(""))
        }

        @Test
        fun `toICalString uses hyphens`() {
            assertEquals("REQ-PARTICIPANT", AttendeeRole.REQ_PARTICIPANT.toICalString())
            assertEquals("OPT-PARTICIPANT", AttendeeRole.OPT_PARTICIPANT.toICalString())
            assertEquals("NON-PARTICIPANT", AttendeeRole.NON_PARTICIPANT.toICalString())
        }
    }

    // ==================== CUType Tests ====================

    @Nested
    inner class CUTypeTests {

        @Test
        fun `fromString parses all valid values`() {
            assertEquals(CUType.INDIVIDUAL, CUType.fromString("INDIVIDUAL"))
            assertEquals(CUType.GROUP, CUType.fromString("GROUP"))
            assertEquals(CUType.RESOURCE, CUType.fromString("RESOURCE"))
            assertEquals(CUType.ROOM, CUType.fromString("ROOM"))
            assertEquals(CUType.UNKNOWN, CUType.fromString("UNKNOWN"))
        }

        @Test
        fun `fromString defaults to INDIVIDUAL`() {
            assertEquals(CUType.INDIVIDUAL, CUType.fromString(null))
            assertEquals(CUType.INDIVIDUAL, CUType.fromString(""))
            assertEquals(CUType.INDIVIDUAL, CUType.fromString("INVALID"))
        }
    }

    // ==================== Data Class Equality Tests ====================

    @Nested
    inner class EqualityTests {

        @Test
        fun `events with same data are equal`() {
            val event1 = createMinimalEvent()
            val event2 = createMinimalEvent()
            assertEquals(event1, event2)
        }

        @Test
        fun `events with different UIDs are not equal`() {
            val event1 = createMinimalEvent(uid = "uid-1")
            val event2 = createMinimalEvent(uid = "uid-2")
            assertNotEquals(event1, event2)
        }

        @Test
        fun `copy creates equal event`() {
            val original = createMinimalEvent()
            val copy = original.copy()
            assertEquals(original, copy)
        }

        @Test
        fun `copy with changes creates different event`() {
            val original = createMinimalEvent()
            val modified = original.copy(summary = "Modified Title")
            assertNotEquals(original, modified)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    inner class EdgeCases {

        @Test
        fun `event with empty summary`() {
            val event = createMinimalEvent(summary = "")
            assertEquals("", event.summary)
        }

        @Test
        fun `event with null optional fields`() {
            val event = createMinimalEvent(summary = null)
            assertNull(event.summary)
            assertNull(event.description)
            assertNull(event.location)
            assertNull(event.dtEnd)
            assertNull(event.duration)
            assertNull(event.rrule)
            assertNull(event.organizer)
        }

        @Test
        fun `event with all RFC 7986 properties`() {
            val event = createMinimalEvent().copy(
                images = listOf(ICalImage(uri = "https://example.com/image.png")),
                conferences = listOf(ICalConference(uri = "https://zoom.us/j/123")),
                color = "crimson"
            )
            assertEquals(1, event.images.size)
            assertEquals(1, event.conferences.size)
            assertEquals("crimson", event.color)
        }

        @Test
        fun `event with all RFC 9253 properties`() {
            val event = createMinimalEvent().copy(
                links = listOf(ICalLink(uri = "https://example.com")),
                relations = listOf(ICalRelation(uid = "related-uid"))
            )
            assertEquals(1, event.links.size)
            assertEquals(1, event.relations.size)
        }

        @Test
        fun `event with multiple attendees`() {
            val attendees = listOf(
                Attendee(
                    email = "user1@example.com",
                    name = "User 1",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = true
                ),
                Attendee(
                    email = "user2@example.com",
                    name = "User 2",
                    partStat = PartStat.TENTATIVE,
                    role = AttendeeRole.OPT_PARTICIPANT,
                    rsvp = false
                )
            )
            val event = createMinimalEvent().copy(attendees = attendees)
            assertEquals(2, event.attendees.size)
        }

        @Test
        fun `event with organizer`() {
            val organizer = Organizer(
                email = "organizer@example.com",
                name = "Event Organizer",
                sentBy = null
            )
            val event = createMinimalEvent().copy(organizer = organizer)
            assertNotNull(event.organizer)
            assertEquals("organizer@example.com", event.organizer?.email)
        }

        @Test
        fun `event with rawProperties preserves unknown properties`() {
            val rawProps = mapOf(
                "X-CUSTOM-PROP" to "custom value",
                "X-ANOTHER" to "another value"
            )
            val event = createMinimalEvent().copy(rawProperties = rawProps)
            assertEquals(2, event.rawProperties.size)
            assertEquals("custom value", event.rawProperties["X-CUSTOM-PROP"])
        }
    }
}
