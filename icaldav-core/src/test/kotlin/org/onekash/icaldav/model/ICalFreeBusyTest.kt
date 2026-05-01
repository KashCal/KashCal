package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for ICalFreeBusy model per RFC 5545.
 *
 * Tests cover:
 * - FreeBusy construction
 * - FreeBusyPeriod construction and validation
 * - FreeBusyType enum parsing
 * - Edge cases and validation
 */
class ICalFreeBusyTest {

    // ==================== Test Data Helpers ====================

    private fun createFreeBusy(
        uid: String = "fb-test-123",
        dtstart: ICalDateTime = ICalDateTime.parse("20231215T090000Z"),
        dtend: ICalDateTime = ICalDateTime.parse("20231215T170000Z")
    ): ICalFreeBusy {
        return ICalFreeBusy(
            uid = uid,
            dtstamp = ICalDateTime.parse("20231214T120000Z"),
            dtstart = dtstart,
            dtend = dtend
        )
    }

    // ==================== Basic Construction Tests ====================

    @Nested
    inner class BasicConstructionTests {

        @Test
        fun `create minimal free busy`() {
            val freeBusy = createFreeBusy()

            assertEquals("fb-test-123", freeBusy.uid)
            assertNotNull(freeBusy.dtstamp)
            assertNotNull(freeBusy.dtstart)
            assertNotNull(freeBusy.dtend)
            assertTrue(freeBusy.freeBusyPeriods.isEmpty())
            assertNull(freeBusy.organizer)
            assertTrue(freeBusy.attendees.isEmpty())
        }

        @Test
        fun `create free busy with organizer`() {
            val organizer = Organizer(
                email = "organizer@example.com",
                name = "Meeting Organizer",
                sentBy = null
            )
            val freeBusy = createFreeBusy().copy(organizer = organizer)

            assertNotNull(freeBusy.organizer)
            assertEquals("organizer@example.com", freeBusy.organizer?.email)
        }

        @Test
        fun `create free busy with attendees`() {
            val attendees = listOf(
                Attendee(
                    email = "user1@example.com",
                    name = "User 1",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )
            val freeBusy = createFreeBusy().copy(attendees = attendees)

            assertEquals(1, freeBusy.attendees.size)
            assertEquals("user1@example.com", freeBusy.attendees[0].email)
        }

        @Test
        fun `create free busy with busy periods`() {
            val periods = listOf(
                FreeBusyPeriod(
                    start = ICalDateTime.parse("20231215T100000Z"),
                    end = ICalDateTime.parse("20231215T110000Z"),
                    type = FreeBusyType.BUSY
                ),
                FreeBusyPeriod(
                    start = ICalDateTime.parse("20231215T140000Z"),
                    end = ICalDateTime.parse("20231215T150000Z"),
                    type = FreeBusyType.BUSY_TENTATIVE
                )
            )
            val freeBusy = createFreeBusy().copy(freeBusyPeriods = periods)

            assertEquals(2, freeBusy.freeBusyPeriods.size)
            assertEquals(FreeBusyType.BUSY, freeBusy.freeBusyPeriods[0].type)
            assertEquals(FreeBusyType.BUSY_TENTATIVE, freeBusy.freeBusyPeriods[1].type)
        }
    }

    // ==================== FreeBusyPeriod Tests ====================

    @Nested
    inner class FreeBusyPeriodTests {

        @Test
        fun `create busy period with default type`() {
            val period = FreeBusyPeriod(
                start = ICalDateTime.parse("20231215T100000Z"),
                end = ICalDateTime.parse("20231215T110000Z")
            )

            assertEquals(FreeBusyType.BUSY, period.type) // Default
        }

        @Test
        fun `create busy period with explicit type`() {
            val period = FreeBusyPeriod(
                start = ICalDateTime.parse("20231215T100000Z"),
                end = ICalDateTime.parse("20231215T110000Z"),
                type = FreeBusyType.BUSY_UNAVAILABLE
            )

            assertEquals(FreeBusyType.BUSY_UNAVAILABLE, period.type)
        }

        @Test
        fun `create free period`() {
            val period = FreeBusyPeriod(
                start = ICalDateTime.parse("20231215T120000Z"),
                end = ICalDateTime.parse("20231215T130000Z"),
                type = FreeBusyType.FREE
            )

            assertEquals(FreeBusyType.FREE, period.type)
        }

        @Test
        fun `periods preserve timezone`() {
            val start = ICalDateTime.parse("20231215T100000", "America/New_York")
            val end = ICalDateTime.parse("20231215T110000", "America/New_York")
            val period = FreeBusyPeriod(start = start, end = end)

            assertEquals(start.timezone, period.start.timezone)
            assertEquals(end.timezone, period.end.timezone)
        }
    }

    // ==================== FreeBusyType Tests ====================

    @Nested
    inner class FreeBusyTypeTests {

        @Test
        fun `fromString parses FREE`() {
            assertEquals(FreeBusyType.FREE, FreeBusyType.fromString("FREE"))
            assertEquals(FreeBusyType.FREE, FreeBusyType.fromString("free"))
        }

        @Test
        fun `fromString parses BUSY`() {
            assertEquals(FreeBusyType.BUSY, FreeBusyType.fromString("BUSY"))
            assertEquals(FreeBusyType.BUSY, FreeBusyType.fromString("busy"))
        }

        @Test
        fun `fromString parses BUSY-UNAVAILABLE`() {
            assertEquals(FreeBusyType.BUSY_UNAVAILABLE, FreeBusyType.fromString("BUSY-UNAVAILABLE"))
            assertEquals(FreeBusyType.BUSY_UNAVAILABLE, FreeBusyType.fromString("busy-unavailable"))
        }

        @Test
        fun `fromString parses BUSY-TENTATIVE`() {
            assertEquals(FreeBusyType.BUSY_TENTATIVE, FreeBusyType.fromString("BUSY-TENTATIVE"))
            assertEquals(FreeBusyType.BUSY_TENTATIVE, FreeBusyType.fromString("busy-tentative"))
        }

        @Test
        fun `fromString defaults to BUSY for unknown values`() {
            assertEquals(FreeBusyType.BUSY, FreeBusyType.fromString("UNKNOWN"))
            assertEquals(FreeBusyType.BUSY, FreeBusyType.fromString(""))
        }

        @Test
        fun `value property returns correct string`() {
            assertEquals("FREE", FreeBusyType.FREE.value)
            assertEquals("BUSY", FreeBusyType.BUSY.value)
            assertEquals("BUSY-UNAVAILABLE", FreeBusyType.BUSY_UNAVAILABLE.value)
            assertEquals("BUSY-TENTATIVE", FreeBusyType.BUSY_TENTATIVE.value)
        }
    }

    // ==================== Complex FreeBusy Scenarios ====================

    @Nested
    inner class ComplexScenarios {

        @Test
        fun `free busy with multiple period types`() {
            val periods = listOf(
                FreeBusyPeriod(
                    start = ICalDateTime.parse("20231215T090000Z"),
                    end = ICalDateTime.parse("20231215T100000Z"),
                    type = FreeBusyType.BUSY
                ),
                FreeBusyPeriod(
                    start = ICalDateTime.parse("20231215T100000Z"),
                    end = ICalDateTime.parse("20231215T110000Z"),
                    type = FreeBusyType.BUSY_TENTATIVE
                ),
                FreeBusyPeriod(
                    start = ICalDateTime.parse("20231215T110000Z"),
                    end = ICalDateTime.parse("20231215T120000Z"),
                    type = FreeBusyType.FREE
                ),
                FreeBusyPeriod(
                    start = ICalDateTime.parse("20231215T140000Z"),
                    end = ICalDateTime.parse("20231215T170000Z"),
                    type = FreeBusyType.BUSY_UNAVAILABLE
                )
            )

            val freeBusy = createFreeBusy().copy(freeBusyPeriods = periods)

            assertEquals(4, freeBusy.freeBusyPeriods.size)

            // Verify different types
            val byType = freeBusy.freeBusyPeriods.groupBy { it.type }
            assertEquals(1, byType[FreeBusyType.BUSY]?.size)
            assertEquals(1, byType[FreeBusyType.BUSY_TENTATIVE]?.size)
            assertEquals(1, byType[FreeBusyType.FREE]?.size)
            assertEquals(1, byType[FreeBusyType.BUSY_UNAVAILABLE]?.size)
        }

        @Test
        fun `free busy request with organizer and multiple attendees`() {
            val organizer = Organizer(
                email = "organizer@example.com",
                name = "Meeting Organizer",
                sentBy = null
            )
            val attendees = listOf(
                Attendee(
                    email = "user1@example.com",
                    name = "User 1",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                ),
                Attendee(
                    email = "user2@example.com",
                    name = "User 2",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                ),
                Attendee(
                    email = "room1@example.com",
                    name = "Conference Room 1",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.NON_PARTICIPANT,
                    rsvp = false,
                    cutype = CUType.ROOM
                )
            )

            val freeBusy = ICalFreeBusy(
                uid = "fb-request-123",
                dtstamp = ICalDateTime.parse("20231214T120000Z"),
                dtstart = ICalDateTime.parse("20231215T090000Z"),
                dtend = ICalDateTime.parse("20231215T170000Z"),
                organizer = organizer,
                attendees = attendees
            )

            assertEquals("organizer@example.com", freeBusy.organizer?.email)
            assertEquals(3, freeBusy.attendees.size)
            assertEquals(CUType.ROOM, freeBusy.attendees[2].cutype)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    inner class EdgeCases {

        @Test
        fun `free busy with same start and end time`() {
            // Zero-duration query range
            val time = ICalDateTime.parse("20231215T100000Z")
            val freeBusy = ICalFreeBusy(
                uid = "fb-instant",
                dtstamp = time,
                dtstart = time,
                dtend = time
            )

            assertEquals(freeBusy.dtstart, freeBusy.dtend)
        }

        @Test
        fun `free busy period with same start and end`() {
            // Zero-duration busy period (instant event)
            val time = ICalDateTime.parse("20231215T100000Z")
            val period = FreeBusyPeriod(
                start = time,
                end = time,
                type = FreeBusyType.BUSY
            )

            assertEquals(period.start, period.end)
        }

        @Test
        fun `free busy spanning multiple days`() {
            val freeBusy = ICalFreeBusy(
                uid = "fb-multiday",
                dtstamp = ICalDateTime.parse("20231214T120000Z"),
                dtstart = ICalDateTime.parse("20231215T000000Z"),
                dtend = ICalDateTime.parse("20231217T235959Z")
            )

            // Verify span
            val durationMs = freeBusy.dtend.timestamp - freeBusy.dtstart.timestamp
            assertTrue(durationMs > 2 * 24 * 60 * 60 * 1000) // More than 2 days
        }

        @Test
        fun `free busy with all-day boundaries`() {
            val freeBusy = ICalFreeBusy(
                uid = "fb-allday",
                dtstamp = ICalDateTime.parse("20231214"),
                dtstart = ICalDateTime.parse("20231215"),
                dtend = ICalDateTime.parse("20231216")
            )

            assertTrue(freeBusy.dtstart.isDate)
            assertTrue(freeBusy.dtend.isDate)
        }

        @Test
        fun `data class equality`() {
            val fb1 = createFreeBusy(uid = "test-1")
            val fb2 = createFreeBusy(uid = "test-1")

            assertEquals(fb1, fb2)
        }

        @Test
        fun `data class copy`() {
            val original = createFreeBusy()
            val copy = original.copy(uid = "new-uid")

            assertNotEquals(original.uid, copy.uid)
            assertEquals("new-uid", copy.uid)
        }

        @Test
        fun `period equality`() {
            val period1 = FreeBusyPeriod(
                start = ICalDateTime.parse("20231215T100000Z"),
                end = ICalDateTime.parse("20231215T110000Z"),
                type = FreeBusyType.BUSY
            )
            val period2 = FreeBusyPeriod(
                start = ICalDateTime.parse("20231215T100000Z"),
                end = ICalDateTime.parse("20231215T110000Z"),
                type = FreeBusyType.BUSY
            )

            assertEquals(period1, period2)
        }
    }
}
