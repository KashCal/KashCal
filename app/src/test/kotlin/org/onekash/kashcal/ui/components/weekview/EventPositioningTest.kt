package org.onekash.kashcal.ui.components.weekview

import org.junit.Test
import org.junit.Assert.*
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.model.DisplayEvent
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

    // Helper to wrap Event + Occurrence into DisplayEvent.Room
    private fun toDisplayEvent(event: Event, occurrence: Occurrence): DisplayEvent.Room {
        return DisplayEvent.Room(event = event, occurrence = occurrence, calendar = null)
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

        val events = listOf(toDisplayEvent(event, occurrence))
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

        val events = listOf(toDisplayEvent(event, occurrence))
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

        val events = listOf(toDisplayEvent(event, occurrence))
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

        val events = listOf(toDisplayEvent(event1, occ1), toDisplayEvent(event2, occ2))
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
            toDisplayEvent(
                createTestEvent(id = id.toLong(), title = "Event $id"),
                createTestOccurrence(eventId = id.toLong(), startHour = 9, endHour = 10)
            )
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

        val events = listOf(toDisplayEvent(event1, occ1), toDisplayEvent(event2, occ2))
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

        val events = listOf(toDisplayEvent(event1, occ1), toDisplayEvent(event2, occ2))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(2, positioned.size)

        // Should be stacked due to overlap
        positioned.forEach { pos ->
            assertEquals(2, pos.overlapTotal)
        }
    }

    // ==================== Positioning Tests (0-24h Grid) ====================

    @Test
    fun `event at 5am positioned correctly in 24h grid`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 5,
            endHour = 8
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 5 hours * 60dp = 300dp
        assertEquals(300f, pos.topOffset.value, 1f)
        // height: 3 hours * 60dp = 180dp
        assertEquals(180f, pos.height.value, 1f)
    }

    @Test
    fun `event at 11pm positioned correctly in 24h grid`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 22,
            endHour = 23,
            endMinute = 59
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 22 hours * 60dp = 1320dp
        assertEquals(1320f, pos.topOffset.value, 1f)
    }

    @Test
    fun `event within mid-day positioned correctly`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            endHour = 17
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 9 hours * 60dp = 540dp
        assertEquals(540f, pos.topOffset.value, 1f)
        // height: 8 hours * 60dp = 480dp
        assertEquals(480f, pos.height.value, 1f)
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

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // 6am is 6 hours into the grid: 6 * 60dp = 360dp
        assertEquals(360f, pos.topOffset.value, 0.1f)
    }

    @Test
    fun `event at exact 11pm end`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 22,
            endHour = 23
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
    }

    @Test
    fun `position 3am event at correct offset`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 3,
            endHour = 5
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 3 hours * 60dp = 180dp
        assertEquals(180f, pos.topOffset.value, 1f)
        // height: 2 hours * 60dp = 120dp
        assertEquals(120f, pos.height.value, 1f)
    }

    @Test
    fun `position 11pm event not filtered out`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 23,
            endHour = 23,
            endMinute = 59
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
    }

    @Test
    fun `position midnight event at top of grid`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 0,
            endHour = 1
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 0 hours from startHour=0 → 0dp
        assertEquals(0f, pos.topOffset.value, 0.1f)
    }

    @Test
    fun `3am event positioned with default args`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 3,
            endHour = 5
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        // 3am-5am is within 0-24h range → positioned (not filtered out)
        assertEquals(1, positioned.size)
    }

    // ==================== Custom Hour Height Tests (Pinch-to-Zoom) ====================

    @Test
    fun `position event with 90dp hour height scales topOffset and height`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            endHour = 11
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0, hourHeight = 90.dp)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 9 hours * 90dp = 810dp
        assertEquals(810f, pos.topOffset.value, 1f)
        // height: 2 hours * 90dp = 180dp
        assertEquals(180f, pos.height.value, 1f)
    }

    @Test
    fun `position event with 30dp min hour height`() {
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            endHour = 10
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0, hourHeight = 30.dp)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 9 hours * 30dp = 270dp
        assertEquals(270f, pos.topOffset.value, 1f)
        // height: 1 hour * 30dp = 30dp (above MIN_EVENT_HEIGHT)
        assertEquals(30f, pos.height.value, 1f)
    }

    // ==================== Exception Event Tests ====================

    @Test
    fun `uses display event data correctly`() {
        val exceptionEvent = createTestEvent(id = 101, title = "Exception Event")

        val date = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val startTs = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val endTs = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val startDay = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = startDay,
            isCancelled = false,
            exceptionEventId = 101
        )

        val events = listOf(toDisplayEvent(exceptionEvent, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals("Exception Event", positioned[0].displayEvent.title)
    }
}
