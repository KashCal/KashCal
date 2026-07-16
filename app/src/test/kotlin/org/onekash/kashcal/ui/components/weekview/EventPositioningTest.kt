package org.onekash.kashcal.ui.components.weekview

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
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

    // Helper to create test occurrence (supports cross-midnight via endDate)
    private fun createTestOccurrence(
        eventId: Long = 1L,
        startHour: Int,
        startMinute: Int = 0,
        endHour: Int,
        endMinute: Int = 0,
        date: LocalDate = LocalDate.now(),
        endDate: LocalDate = date
    ): Occurrence {
        val zone = ZoneId.systemDefault()
        val startTs = date.atTime(LocalTime.of(startHour, startMinute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val endTs = endDate.atTime(LocalTime.of(endHour, endMinute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val startDay = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        val endDayCode = endDate.year * 10000 + endDate.monthValue * 100 + endDate.dayOfMonth

        return Occurrence(
            eventId = eventId,
            calendarId = testCalendarId,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = endDayCode,
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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // Should be at least MIN_EVENT_HEIGHT (20dp)
        assertTrue(pos.height.value >= 20f)
    }

    @Test
    fun `zero-duration event renders at minimum height`() {
        // A point-in-time event (start == end) must still be visible and tappable.
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 9,
            startMinute = 0,
            endHour = 9,
            endMinute = 0
        )

        val events = listOf(toDisplayEvent(event, occurrence))
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // Positioned at 9am (540dp) with the minimum visual height floor.
        assertEquals(540f, pos.topOffset.value, 1f)
        assertEquals(20f, pos.height.value, 1f)
    }

    @Test
    fun `zero-duration event overlapping a timed event packs side by side`() {
        // A zero-duration event sharing a start minute with a timed event must not
        // draw full-width on top of it — it packs into a neighbouring slot.
        val zeroDur = toDisplayEvent(
            createTestEvent(id = 1, title = "Point"),
            createTestOccurrence(eventId = 1, startHour = 9, startMinute = 0, endHour = 9, endMinute = 0)
        )
        val timed = toDisplayEvent(
            createTestEvent(id = 2, title = "Meeting"),
            createTestOccurrence(eventId = 2, startHour = 9, endHour = 10)
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(zeroDur, timed), date = LocalDate.now(), dayIndex = 0
        )

        assertEquals(2, positioned.size)
        positioned.forEach { pos ->
            assertEquals(2, pos.overlapTotal)
            assertEquals(0.5f, pos.widthFraction, 0.01f)
        }
        assertEquals(2, positioned.map { it.leftFraction }.toSet().size)
    }

    @Test
    fun `zero-duration event abutting a prior event does not overlap it`() {
        // An event ending at 9:00 and a point event at 9:00 merely touch — they stack.
        val earlier = toDisplayEvent(
            createTestEvent(id = 1, title = "Earlier"),
            createTestOccurrence(eventId = 1, startHour = 8, endHour = 9)
        )
        val zeroDur = toDisplayEvent(
            createTestEvent(id = 2, title = "Point"),
            createTestOccurrence(eventId = 2, startHour = 9, startMinute = 0, endHour = 9, endMinute = 0)
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(earlier, zeroDur), date = LocalDate.now(), dayIndex = 0
        )

        assertEquals(2, positioned.size)
        positioned.forEach { pos ->
            assertEquals(1, pos.overlapTotal)
            assertEquals(1.0f, pos.widthFraction, 0.01f)
        }
    }

    @Test
    fun `sub-minute event renders at minimum height`() {
        // A positive-but-sub-minute event (e.g. a 40-second synced event) truncates
        // to a single minute; it must still be visible, not dropped.
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now()
        val startTs = date.atTime(9, 0, 10).atZone(zone).toInstant().toEpochMilli()
        val endTs = date.atTime(9, 0, 50).atZone(zone).toInstant().toEpochMilli()
        val startDay = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        val event = createTestEvent(id = 1, startTs = startTs, endTs = endTs)
        val occurrence = Occurrence(
            eventId = 1,
            calendarId = testCalendarId,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = startDay,
            isCancelled = false,
            exceptionEventId = null
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(toDisplayEvent(event, occurrence)), date = date, dayIndex = 0
        )

        assertEquals(1, positioned.size)
        assertEquals(540f, positioned[0].topOffset.value, 1f)
        assertEquals(20f, positioned[0].height.value, 1f)
    }

    @Test
    fun `two short events shorter than min height pack side by side`() {
        // Two 5-minute events at 9:00-9:05 and 9:05-9:10 each render floored to 20dp
        // (covering ~20 min of screen), so their drawn blocks overlap — they must
        // pack into neighbouring slots, not stack on top of each other.
        val first = toDisplayEvent(
            createTestEvent(id = 1, title = "First"),
            createTestOccurrence(eventId = 1, startHour = 9, startMinute = 0, endHour = 9, endMinute = 5)
        )
        val second = toDisplayEvent(
            createTestEvent(id = 2, title = "Second"),
            createTestOccurrence(eventId = 2, startHour = 9, startMinute = 5, endHour = 9, endMinute = 10)
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(first, second), date = LocalDate.now(), dayIndex = 0
        )

        assertEquals(2, positioned.size)
        positioned.forEach { pos ->
            assertEquals(2, pos.overlapTotal)
            assertEquals(0.5f, pos.widthFraction, 0.01f)
        }
        assertEquals(2, positioned.map { it.leftFraction }.toSet().size)
    }

    @Test
    fun `back-to-back 30-minute meetings stay full-width at min zoom`() {
        // At min zoom (30dp/hr) a 30-min block floors to 20dp, covering ~40 min of
        // screen. The overlap window must NOT inflate to match, or two back-to-back
        // 30-min meetings would force into half-width columns. They should stay
        // full-width stacked — the user zooms in or switches view to see detail.
        val first = toDisplayEvent(
            createTestEvent(id = 1, title = "First"),
            createTestOccurrence(eventId = 1, startHour = 9, startMinute = 0, endHour = 9, endMinute = 30)
        )
        val second = toDisplayEvent(
            createTestEvent(id = 2, title = "Second"),
            createTestOccurrence(eventId = 2, startHour = 9, startMinute = 30, endHour = 10, endMinute = 0)
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(first, second), date = LocalDate.now(), dayIndex = 0, hourHeight = 30.dp
        )

        assertEquals(2, positioned.size)
        positioned.forEach { pos ->
            assertEquals(1, pos.overlapTotal)
            assertEquals(1.0f, pos.widthFraction, 0.01f)
        }
    }

    @Test
    fun `zero-duration event outside the visible grid is dropped`() {
        // A point event at 3am with a grid starting at 6am is off-screen and must
        // not be pinned to the grid edge.
        val event = createTestEvent(id = 1)
        val occurrence = createTestOccurrence(
            eventId = 1,
            startHour = 3,
            startMinute = 0,
            endHour = 3,
            endMinute = 0
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(toDisplayEvent(event, occurrence)),
            date = LocalDate.now(),
            dayIndex = 0,
            startHour = 6,
            endHour = 24
        )

        assertTrue(positioned.isEmpty())
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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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

        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(emptyList(), date = LocalDate.now(), dayIndex = 0)
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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0, hourHeight = 90.dp)

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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0, hourHeight = 30.dp)

        assertEquals(1, positioned.size)
        val pos = positioned[0]

        // topOffset: 9 hours * 30dp = 270dp
        assertEquals(270f, pos.topOffset.value, 1f)
        // height: 1 hour * 30dp = 30dp (above MIN_EVENT_HEIGHT)
        assertEquals(30f, pos.height.value, 1f)
    }

    // ==================== Cross-Midnight Event Tests ====================

    @Test
    fun `cross-midnight event visible on start day from 22h to midnight`() {
        val startDate = LocalDate.of(2026, 4, 15)
        val endDate = LocalDate.of(2026, 4, 16)
        val event = createTestEvent(id = 1, title = "Late Night")
        val occ = createTestOccurrence(
            eventId = 1, startHour = 22, date = startDate,
            endHour = 4, endDate = endDate
        )
        val events = listOf(toDisplayEvent(event, occ))
        val positioned = WeekViewUtils.positionEventsForDay(events, date = startDate, dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals(1320, positioned[0].startMinutes)
        assertEquals(1440, positioned[0].endMinutes)
        assertEquals(120f, positioned[0].height.value, 1f)
    }

    @Test
    fun `cross-midnight event visible on end day from midnight to 4am`() {
        val startDate = LocalDate.of(2026, 4, 15)
        val endDate = LocalDate.of(2026, 4, 16)
        val event = createTestEvent(id = 1, title = "Late Night")
        val occ = createTestOccurrence(
            eventId = 1, startHour = 22, date = startDate,
            endHour = 4, endDate = endDate
        )
        val events = listOf(toDisplayEvent(event, occ))
        val positioned = WeekViewUtils.positionEventsForDay(events, date = endDate, dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals(0, positioned[0].startMinutes)
        assertEquals(240, positioned[0].endMinutes)
        assertEquals(0f, positioned[0].topOffset.value, 0.1f)
    }

    @Test
    fun `same-day event unchanged by date-aware clamping`() {
        val date = LocalDate.of(2026, 4, 15)
        val event = createTestEvent(id = 1)
        val occ = createTestOccurrence(eventId = 1, startHour = 9, endHour = 10, date = date)
        val events = listOf(toDisplayEvent(event, occ))
        val positioned = WeekViewUtils.positionEventsForDay(events, date = date, dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals(540, positioned[0].startMinutes)
        assertEquals(600, positioned[0].endMinutes)
    }

    @Test
    fun `multi-day event renders full bar on middle day`() {
        val startDate = LocalDate.of(2026, 4, 14)
        val middleDate = LocalDate.of(2026, 4, 15)
        val endDate = LocalDate.of(2026, 4, 16)
        val event = createTestEvent(id = 1, title = "Conference")
        val occ = createTestOccurrence(
            eventId = 1, startHour = 20, date = startDate,
            endHour = 10, endDate = endDate
        )
        val events = listOf(toDisplayEvent(event, occ))
        val positioned = WeekViewUtils.positionEventsForDay(events, date = middleDate, dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals(0, positioned[0].startMinutes)
        assertEquals(1440, positioned[0].endMinutes)
    }

    @Test
    fun `event ending exactly at midnight shows only on start day`() {
        val startDate = LocalDate.of(2026, 4, 15)
        val endDate = LocalDate.of(2026, 4, 16)
        val event = createTestEvent(id = 1, title = "Until Midnight")
        val occ = createTestOccurrence(
            eventId = 1, startHour = 22, date = startDate,
            endHour = 0, endMinute = 0, endDate = endDate
        )

        val startDayPositioned = WeekViewUtils.positionEventsForDay(
            listOf(toDisplayEvent(event, occ)), date = startDate, dayIndex = 0
        )
        assertEquals(1, startDayPositioned.size)
        assertEquals(1320, startDayPositioned[0].startMinutes)
        assertEquals(1440, startDayPositioned[0].endMinutes)

        val endDayPositioned = WeekViewUtils.positionEventsForDay(
            listOf(toDisplayEvent(event, occ)), date = endDate, dayIndex = 0
        )
        assertEquals(0, endDayPositioned.size)
    }

    // ==================== Regression: Issue #175 ====================

    @Test
    fun `long event with two disjoint shorter events all get slots`() {
        // Regression for github.com/KashCal/KashCal/issues/175 (positioning layer)
        // Event 1: 11:00-23:00 (spans most of the day)
        // Event 2: 13:00-14:00 (inside event 1, disjoint from event 3)
        // Event 3: 15:00-16:00 (inside event 1, disjoint from event 2)
        // Expected: all three positioned — event 2 and event 3 share a slot next to event 1.
        val date = LocalDate.now()
        val e1 = toDisplayEvent(
            createTestEvent(id = 1, title = "Event 1"),
            createTestOccurrence(eventId = 1, startHour = 11, endHour = 23, date = date)
        )
        val e2 = toDisplayEvent(
            createTestEvent(id = 2, title = "Event 2"),
            createTestOccurrence(eventId = 2, startHour = 13, endHour = 14, date = date)
        )
        val e3 = toDisplayEvent(
            createTestEvent(id = 3, title = "Event 3"),
            createTestOccurrence(eventId = 3, startHour = 15, endHour = 16, date = date)
        )

        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(e1, e2, e3), date = date, dayIndex = 0
        )

        assertEquals(3, positioned.size)

        val byTitle = positioned.associateBy { it.displayEvent.title }
        val p1 = byTitle["Event 1"]!!
        val p2 = byTitle["Event 2"]!!
        val p3 = byTitle["Event 3"]!!

        // Two slots total: long event in one, both short events share the other.
        assertEquals(2, p1.overlapTotal)
        assertEquals(2, p2.overlapTotal)
        assertEquals(2, p3.overlapTotal)

        // Long event owns slot 0, short events share slot 1.
        assertEquals(0, p1.overlapIndex)
        assertEquals(1, p2.overlapIndex)
        assertEquals(1, p3.overlapIndex)

        // Half-width side by side.
        assertEquals(0.5f, p1.widthFraction, 0.01f)
        assertEquals(0.5f, p2.widthFraction, 0.01f)
        assertEquals(0.5f, p3.widthFraction, 0.01f)
    }

    @Test
    fun `groupForDisplay shows all events that fit within slot cap with no overflow`() {
        // Regression for github.com/KashCal/KashCal/issues/175 (display layer)
        // With MAX_VISIBLE_OVERLAP = 2, three events fitting in 2 slots must ALL be visible.
        val date = LocalDate.now()
        val positioned = WeekViewUtils.positionEventsForDay(
            listOf(
                toDisplayEvent(
                    createTestEvent(id = 1, title = "Event 1"),
                    createTestOccurrence(eventId = 1, startHour = 11, endHour = 23, date = date)
                ),
                toDisplayEvent(
                    createTestEvent(id = 2, title = "Event 2"),
                    createTestOccurrence(eventId = 2, startHour = 13, endHour = 14, date = date)
                ),
                toDisplayEvent(
                    createTestEvent(id = 3, title = "Event 3"),
                    createTestOccurrence(eventId = 3, startHour = 15, endHour = 16, date = date)
                )
            ),
            date = date, dayIndex = 0
        )

        val (visible, overflow) = WeekViewUtils.groupForDisplay(positioned)

        assertEquals("All three events fit in 2 slots; no overflow expected", 0, overflow)
        assertEquals(3, visible.size)
        val titles = visible.map { it.displayEvent.title }.toSet()
        assertEquals(setOf("Event 1", "Event 2", "Event 3"), titles)
    }

    @Test
    fun `groupForDisplay overflows events in slots beyond cap`() {
        // Four events fully overlapping at 9-10am: 4 slots required, cap is 2.
        // Events in slots 0-1 visible, events in slots 2-3 go to overflow.
        val date = LocalDate.now()
        val positioned = WeekViewUtils.positionEventsForDay(
            (1..4).map { id ->
                toDisplayEvent(
                    createTestEvent(id = id.toLong(), title = "Event $id"),
                    createTestOccurrence(eventId = id.toLong(), startHour = 9, endHour = 10, date = date)
                )
            },
            date = date, dayIndex = 0
        )

        val (visible, overflow) = WeekViewUtils.groupForDisplay(positioned)

        assertEquals(2, overflow)
        assertEquals(2, visible.size)
        visible.forEach { pos ->
            assertTrue("Visible events must be in slots below the cap",
                pos.overlapIndex < WeekViewUtils.MAX_VISIBLE_OVERLAP)
        }
    }

    // ==================== Per-call maxVisibleOverlap cap ====================

    @Test
    fun `groupForDisplay with cap=5 shows all 5 fully overlapping events with no overflow`() {
        val date = LocalDate.now()
        val positioned = WeekViewUtils.positionEventsForDay(
            (1..5).map { id ->
                toDisplayEvent(
                    createTestEvent(id = id.toLong(), title = "Event $id"),
                    createTestOccurrence(eventId = id.toLong(), startHour = 9, endHour = 10, date = date)
                )
            },
            date = date,
            dayIndex = 0,
            maxVisibleOverlap = 5,
        )

        positioned.forEach { pos ->
            assertEquals(5, pos.overlapTotal)
            assertEquals(0.2f, pos.widthFraction, 0.01f)
        }

        val (visible, overflow) = WeekViewUtils.groupForDisplay(positioned, maxVisibleOverlap = 5)
        assertEquals(0, overflow)
        assertEquals(5, visible.size)
    }

    @Test
    fun `visible events fill column width when cluster exceeds cap`() {
        // Regression for github.com/KashCal/KashCal/issues/256
        // 8 events fully overlapping at 9-10am with default cap=2: only 2 events render,
        // and they must split the column 50/50 — not stay narrow at 1/8 each.
        val date = LocalDate.now()
        val positioned = WeekViewUtils.positionEventsForDay(
            (1..8).map { id ->
                toDisplayEvent(
                    createTestEvent(id = id.toLong(), title = "Event $id"),
                    createTestOccurrence(eventId = id.toLong(), startHour = 9, endHour = 10, date = date)
                )
            },
            date = date,
            dayIndex = 0,
        )

        val (visible, overflow) = WeekViewUtils.groupForDisplay(positioned)

        assertEquals(2, visible.size)
        assertEquals(6, overflow)

        // Visible events split the day column evenly: 1/cap each.
        visible.forEach { pos ->
            assertEquals(0.5f, pos.widthFraction, 0.01f)
        }

        // Two visible events sit at fractions 0.0 and 0.5 — no empty space at the right.
        val leftFractions = visible.map { it.leftFraction }.sorted()
        assertEquals(0.0f, leftFractions[0], 0.01f)
        assertEquals(0.5f, leftFractions[1], 0.01f)
    }

    @Test
    fun `groupForDisplay with cap=2 on 5-cluster shows 2 visible plus 3 overflow`() {
        val date = LocalDate.now()
        val positioned = WeekViewUtils.positionEventsForDay(
            (1..5).map { id ->
                toDisplayEvent(
                    createTestEvent(id = id.toLong(), title = "Event $id"),
                    createTestOccurrence(eventId = id.toLong(), startHour = 9, endHour = 10, date = date)
                )
            },
            date = date,
            dayIndex = 0,
        )

        val (visible, overflow) = WeekViewUtils.groupForDisplay(positioned, maxVisibleOverlap = 2)
        assertEquals(3, overflow)
        assertEquals(2, visible.size)
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
        val positioned = WeekViewUtils.positionEventsForDay(events, date = LocalDate.now(), dayIndex = 0)

        assertEquals(1, positioned.size)
        assertEquals("Exception Event", positioned[0].displayEvent.title)
    }
}
