package org.onekash.kashcal.ui.components.weekview

import org.junit.Test
import org.junit.Assert.*
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Unit tests for event positioning in week view.
 * Tests overlap detection, stacking, and visual positioning.
 */
class EventPositioningTest {

    private val testCalendarId = 1L
    private val now = System.currentTimeMillis()

    // Helper to create a test event
    private fun createTestEvent(
        id: Long = 1L,
        title: String = "Test Event",
        startTs: Long = now,
        endTs: Long = now + 3600000
    ) = Event(
        id = id,
        uid = UUID.randomUUID().toString(),
        calendarId = testCalendarId,
        title = title,
        startTs = startTs,
        endTs = endTs,
        timezone = "UTC",
        syncStatus = SyncStatus.SYNCED,
        createdAt = now,
        updatedAt = now,
        dtstamp = now
    )

    // Helper to create test occurrence
    private fun createTestOccurrence(
        eventId: Long = 1L,
        startHour: Int,
        startMinute: Int = 0,
        endHour: Int,
        endMinute: Int = 0,
        date: LocalDate = LocalDate.now()
    ): Occurrence {
        val zone = ZoneId.systemDefault()
        val startTs = date.atTime(LocalTime.of(startHour, startMinute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val endTs = date.atTime(LocalTime.of(endHour, endMinute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val startDay = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        return Occurrence(
            eventId = eventId,
            calendarId = testCalendarId,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = startDay,
            isCancelled = false,
            exceptionEventId = null
        )
    }

    // ==================== Single Event Tests ====================

    @Test
    fun `position single event at 9am`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            endHour = 10
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // Should have full width (no overlap)
        assertEquals(1.0f, pos.widthFraction, 0.01f)
        assertEquals(0.0f, pos.leftFraction, 0.01f)
        assertEquals(0, pos.overlapIndex)
        assertEquals(1, pos.overlapTotal)
    }

    @Test
    fun `position event height matches duration`() {
        val event = createTestEvent(id = 1)
        // 2 hour event (9am - 11am)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            endHour = 11
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // 2 hours * 60dp default height = 120dp
        assertEquals(120f, pos.height.value, 1f)
    }

    @Test
    fun `minimum height for short events`() {
        val event = createTestEvent(id = 1)
        // 15 minute event
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            startMinute = 0,
            endHour = 9,
            endMinute = 15
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // Should be at least MIN_EVENT_HEIGHT (20dp)
        assertTrue(pos.height.value >= 20f)
    }

    // ==================== Overlap Tests ====================

    @Test
    fun `two overlapping events stack with offset`() {
        val event1 = createTestEvent(id = 1, title = "Event 1")
        val event2 = createTestEvent(id = 2, title = "Event 2")

        // Both events 9am - 10am (fully overlapping)
        val occ1 = createTestOccurrence(eventId = 1, startHour = 9, endHour = 10)
        val occ2 = createTestOccurrence(eventId = 2, startHour = 9, endHour = 10)

        val events = listOf(event1 to occ1, event2 to occ2)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(2, positioned.size)

        // Both should have width < 1.0 (sharing space)
        positioned.forEach { pos ->
            assertTrue("Width should be less than 1.0 for overlapping events",
                pos.widthFraction < 1.0f)
            assertEquals(2, pos.overlapTotal)
        }

        // Should have different left positions
        val leftPositions = positioned.map { it.leftFraction }.toSet()
        assertEquals(2, leftPositions.size)
    }

    @Test
    fun `three overlapping events shows 3 in overlap total`() {
        val events = (1..3).map { id ->
            createTestEvent(id = id.toLong(), title = "Event $id") to
            createTestOccurrence(eventId = id.toLong(), startHour = 9, endHour = 10)
        }

        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        // All 3 events should be in the result
        assertEquals(3, positioned.size)

        // Check overlap totals reflect 3 events
        positioned.forEach { pos ->
            assertEquals(3, pos.overlapTotal)
        }
    }

    @Test
    fun `non-overlapping events have full width`() {
        val event1 = createTestEvent(id = 1)
        val event2 = createTestEvent(id = 2)

        // Non-overlapping: 9-10am and 11am-12pm
        val occ1 = createTestOccurrence(eventId = 1, startHour = 9, endHour = 10)
        val occ2 = createTestOccurrence(eventId = 2, startHour = 11, endHour = 12)

        val events = listOf(event1 to occ1, event2 to occ2)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(2, positioned.size)

        // Both should have full width (no overlap)
        positioned.forEach { pos ->
            assertEquals(1.0f, pos.widthFraction, 0.01f)
            assertEquals(0.0f, pos.leftFraction, 0.01f)
            assertEquals(1, pos.overlapTotal)
        }
    }

    @Test
    fun `partial overlap creates stacking`() {
        val event1 = createTestEvent(id = 1)
        val event2 = createTestEvent(id = 2)

        // Partially overlapping: 9-11am and 10am-12pm
        val occ1 = createTestOccurrence(eventId = 1, startHour = 9, endHour = 11)
        val occ2 = createTestOccurrence(eventId = 2, startHour = 10, endHour = 12)

        val events = listOf(event1 to occ1, event2 to occ2)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(2, positioned.size)

        // Should be stacked due to overlap
        positioned.forEach { pos ->
            assertEquals(2, pos.overlapTotal)
        }
    }

    // ==================== Clamping Tests ====================

    @Test
    fun `event starting before 6am is clamped`() {
        val event = createTestEvent(id = 1)
        // 5am - 8am (starts before visible range)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 5,
            endHour = 8
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        assertTrue("Should be clamped at start", pos.clampedStart)
        assertFalse("Should not be clamped at end", pos.clampedEnd)

        // Original start should be preserved for indicator
        assertEquals(5 * 60, pos.originalStartMinutes)
    }

    @Test
    fun `event ending after 11pm is clamped`() {
        val event = createTestEvent(id = 1)
        // 10pm - 11:59pm (ends after visible range of 11pm)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 22,
            endHour = 23,
            endMinute = 59
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        assertFalse("Should not be clamped at start", pos.clampedStart)
        // End at 23:59 is clamped since visible range ends at 23:00
        assertTrue("Should be clamped at end", pos.clampedEnd)
    }

    @Test
    fun `event within range not clamped`() {
        val event = createTestEvent(id = 1)
        // 9am - 5pm (within visible range)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            endHour = 17
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        assertFalse("Should not be clamped at start", pos.clampedStart)
        assertFalse("Should not be clamped at end", pos.clampedEnd)
    }

    // ==================== Empty and Edge Cases ====================

    @Test
    fun `empty event list returns empty`() {
        val positioned = WeekViewUtils.positionEventsForDay(emptyList(), dayIndex = 0)
        assertTrue(positioned.isEmpty())
    }

    @Test
    fun `event at exact 6am start`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 6,
            endHour = 7
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // Should start at top of grid (0dp offset)
        assertEquals(0f, pos.topOffset.value, 0.1f)
        assertFalse(pos.clampedStart)
    }

    @Test
    fun `event at exact 11pm end`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 22,
            endHour = 23
        )

        val events = listOf(event to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        assertFalse(pos.clampedEnd)
    }

    // ==================== Exception Event Tests ====================

    @Test
    fun `uses exception event when present`() {
        val masterEvent = createTestEvent(id = 100, title = "Master Event")
        val exceptionEvent = createTestEvent(id = 101, title = "Exception Event")

        val date = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val startTs = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val endTs = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val startDay = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        // Occurrence links to master but has exception
        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = startDay,
            isCancelled = false,
            exceptionEventId = 101  // Points to exception
        )

        // Pass exception event (which should be used for display)
        val events = listOf(exceptionEvent to occurrence)
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals(exceptionEvent, positioned[0].event)
    }
}
