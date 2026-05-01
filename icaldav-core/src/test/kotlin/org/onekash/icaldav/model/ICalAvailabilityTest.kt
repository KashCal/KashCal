package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import java.time.Duration
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ICalAvailability, AvailableSlot, and BusyType (RFC 7953).
 */
class ICalAvailabilityTest {

    private val utc = ZoneId.of("UTC")

    @Nested
    inner class BusyTypeTests {

        @Test
        fun `fromString parses all types correctly`() {
            assertEquals(BusyType.BUSY, BusyType.fromString("BUSY"))
            assertEquals(BusyType.BUSY, BusyType.fromString("busy"))
            assertEquals(BusyType.BUSY_UNAVAILABLE, BusyType.fromString("BUSY-UNAVAILABLE"))
            assertEquals(BusyType.BUSY_UNAVAILABLE, BusyType.fromString("busy-unavailable"))
            assertEquals(BusyType.BUSY_TENTATIVE, BusyType.fromString("BUSY-TENTATIVE"))
        }

        @Test
        fun `fromString normalizes underscores and hyphens`() {
            assertEquals(BusyType.BUSY_UNAVAILABLE, BusyType.fromString("BUSY_UNAVAILABLE"))
            assertEquals(BusyType.BUSY_TENTATIVE, BusyType.fromString("BUSY_TENTATIVE"))
        }

        @Test
        fun `fromString returns BUSY_UNAVAILABLE for invalid values`() {
            assertEquals(BusyType.BUSY_UNAVAILABLE, BusyType.fromString("INVALID"))
            assertEquals(BusyType.BUSY_UNAVAILABLE, BusyType.fromString(null))
            assertEquals(BusyType.BUSY_UNAVAILABLE, BusyType.fromString(""))
        }

        @Test
        fun `toICalString returns correct values with hyphens`() {
            assertEquals("BUSY", BusyType.BUSY.toICalString())
            assertEquals("BUSY-UNAVAILABLE", BusyType.BUSY_UNAVAILABLE.toICalString())
            assertEquals("BUSY-TENTATIVE", BusyType.BUSY_TENTATIVE.toICalString())
        }
    }

    @Nested
    inner class AvailableSlotTests {

        @Test
        fun `effectiveEnd returns dtEnd when present`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)
            val end = ICalDateTime.fromTimestamp(2000000L, utc, false)

            val slot = AvailableSlot(
                dtStart = start,
                dtEnd = end
            )

            assertEquals(end, slot.effectiveEnd())
        }

        @Test
        fun `effectiveEnd calculates from duration when dtEnd null`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)

            val slot = AvailableSlot(
                dtStart = start,
                duration = Duration.ofHours(2)
            )

            val effectiveEnd = slot.effectiveEnd()
            assertEquals(start.timestamp + Duration.ofHours(2).toMillis(), effectiveEnd.timestamp)
        }

        @Test
        fun `effectiveEnd returns dtStart when both dtEnd and duration null`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)

            val slot = AvailableSlot(dtStart = start)

            assertEquals(start, slot.effectiveEnd())
        }

        @Test
        fun `isRecurring returns true when rrule present`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)
            val rrule = RRule(freq = Frequency.WEEKLY)

            val slot = AvailableSlot(
                dtStart = start,
                rrule = rrule
            )

            assertTrue(slot.isRecurring())
        }

        @Test
        fun `isRecurring returns false when rrule null`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)

            val slot = AvailableSlot(dtStart = start)

            assertFalse(slot.isRecurring())
        }

        @Test
        fun `oneTime factory creates simple slot`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)
            val end = ICalDateTime.fromTimestamp(2000000L, utc, false)

            val slot = AvailableSlot.oneTime(start, end, "Office Hours")

            assertEquals(start, slot.dtStart)
            assertEquals(end, slot.dtEnd)
            assertEquals("Office Hours", slot.summary)
            assertFalse(slot.isRecurring())
        }
    }

    @Nested
    inner class ICalAvailabilityConstructorTests {

        @Test
        fun `create availability with uid only`() {
            val availability = ICalAvailability(uid = "avail-1")

            assertEquals("avail-1", availability.uid)
            assertEquals(BusyType.BUSY_UNAVAILABLE, availability.busyType)
            assertEquals(0, availability.priority)
            assertEquals(0, availability.sequence)
            assertTrue(availability.available.isEmpty())
            assertTrue(availability.categories.isEmpty())
        }

        @Test
        fun `create availability with all values`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)
            val end = ICalDateTime.fromTimestamp(2000000L, utc, false)
            val slot = AvailableSlot(
                dtStart = start,
                dtEnd = end,
                summary = "Work hours"
            )

            val availability = ICalAvailability(
                uid = "avail-1",
                dtStart = start,
                dtEnd = end,
                summary = "Weekly Availability",
                organizer = "mailto:john@example.com",
                priority = 5,
                available = listOf(slot),
                busyType = BusyType.BUSY_TENTATIVE,
                categories = listOf("work", "meetings"),
                sequence = 3
            )

            assertEquals("avail-1", availability.uid)
            assertEquals(start, availability.dtStart)
            assertEquals(end, availability.dtEnd)
            assertEquals("Weekly Availability", availability.summary)
            assertEquals("mailto:john@example.com", availability.organizer)
            assertEquals(5, availability.priority)
            assertEquals(1, availability.available.size)
            assertEquals(BusyType.BUSY_TENTATIVE, availability.busyType)
            assertEquals(listOf("work", "meetings"), availability.categories)
            assertEquals(3, availability.sequence)
        }
    }

    @Nested
    inner class ICalAvailabilityHelperMethodsTests {

        @Test
        fun `hasAvailableSlots returns true when slots present`() {
            val start = ICalDateTime.fromTimestamp(1000000L, utc, false)
            val slot = AvailableSlot(dtStart = start)

            val availability = ICalAvailability(
                uid = "avail-1",
                available = listOf(slot)
            )

            assertTrue(availability.hasAvailableSlots())
        }

        @Test
        fun `hasAvailableSlots returns false when no slots`() {
            val availability = ICalAvailability(uid = "avail-1")

            assertFalse(availability.hasAvailableSlots())
        }
    }

    @Nested
    inner class CompanionFactoryMethodsTests {

        @Test
        fun `workingHours creates default availability`() {
            val availability = ICalAvailability.workingHours(
                uid = "avail-123",
                summary = "Business Hours"
            )

            assertEquals("avail-123", availability.uid)
            assertEquals("Business Hours", availability.summary)
            assertEquals(BusyType.BUSY_UNAVAILABLE, availability.busyType)
        }
    }
}
