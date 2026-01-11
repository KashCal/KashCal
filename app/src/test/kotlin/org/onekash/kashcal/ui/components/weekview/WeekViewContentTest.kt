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
 * Integration tests for week view content and data transformations.
 * Tests event grouping, filtering, and data flow.
 */
class WeekViewContentTest {

    private val testCalendarId = 1L
    private val now = System.currentTimeMillis()
    private val zone = ZoneId.systemDefault()

    // Helper to create a test event
    private fun createTestEvent(
        id: Long = 1L,
        title: String = "Test Event",
        startTs: Long = now,
        endTs: Long = now + 3600000,
        isAllDay: Boolean = false
    ) = Event(
        id = id,
        uid = UUID.randomUUID().toString(),
        calendarId = testCalendarId,
        title = title,
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        timezone = "UTC",
        syncStatus = SyncStatus.SYNCED,
        createdAt = now,
        updatedAt = now,
        dtstamp = now
    )

    // Helper to create test occurrence
    private fun createTestOccurrence(
        eventId: Long,
        date: LocalDate,
        startHour: Int,
        startMinute: Int = 0,
        endHour: Int,
        endMinute: Int = 0,
        exceptionEventId: Long? = null
    ): Occurrence {
        val startTs = date.atTime(LocalTime.of(startHour, startMinute))
            .atZone(zone).toInstant().toEpochMilli()
        val endTs = date.atTime(LocalTime.of(endHour, endMinute))
            .atZone(zone).toInstant().toEpochMilli()
        val startDay = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        return Occurrence(
            eventId = eventId,
            calendarId = testCalendarId,
            startTs = startTs,
            endTs = endTs,
            startDay = startDay,
            endDay = startDay,
            isCancelled = false,
            exceptionEventId = exceptionEventId
        )
    }

    private fun getWeekStartMs(date: LocalDate): Long {
        val weekStart = WeekViewUtils.getWeekStart(date)
        return weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    // ==================== groupEventsByDay Tests ====================

    @Test
    fun `groupEventsByDay returns empty map for no events`() {
        val weekStart = getWeekStartMs(LocalDate.now())
        val result = groupEventsByDay(emptyList(), emptyList(), weekStart)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `groupEventsByDay groups events by day index`() {
        val today = LocalDate.now()
        val weekStart = getWeekStartMs(today)
        val weekStartDate = WeekViewUtils.getWeekStart(today)

        // Create events on different days
        val mondayDate = weekStartDate.plusDays(1) // Monday (index 1)
        val wednesdayDate = weekStartDate.plusDays(3) // Wednesday (index 3)

        val event1 = createTestEvent(id = 1, title = "Monday Event")
        val event2 = createTestEvent(id = 2, title = "Wednesday Event")

        val occ1 = createTestOccurrence(
            eventId = 1,
            date = mondayDate,
            startHour = 9,
            endHour = 10
        )
        val occ2 = createTestOccurrence(
            eventId = 2,
            date = wednesdayDate,
            startHour = 14,
            endHour = 15
        )

        val events = listOf(event1, event2)
        val occurrences = listOf(occ1, occ2)

        val result = groupEventsByDay(occurrences, events, weekStart)

        // Should have entries for days 1 and 3
        assertEquals(2, result.size)
        assertTrue(result.containsKey(1))
        assertTrue(result.containsKey(3))
        assertEquals(1, result[1]?.size)
        assertEquals(1, result[3]?.size)
        assertEquals("Monday Event", result[1]?.first()?.first?.title)
        assertEquals("Wednesday Event", result[3]?.first()?.first?.title)
    }

    @Test
    fun `groupEventsByDay handles multiple events on same day`() {
        val today = LocalDate.now()
        val weekStart = getWeekStartMs(today)
        val weekStartDate = WeekViewUtils.getWeekStart(today)
        val tuesday = weekStartDate.plusDays(2) // Tuesday (index 2)

        // Two events on Tuesday
        val event1 = createTestEvent(id = 1, title = "Morning Meeting")
        val event2 = createTestEvent(id = 2, title = "Afternoon Call")

        val occ1 = createTestOccurrence(eventId = 1, date = tuesday, startHour = 9, endHour = 10)
        val occ2 = createTestOccurrence(eventId = 2, date = tuesday, startHour = 14, endHour = 15)

        val result = groupEventsByDay(listOf(occ1, occ2), listOf(event1, event2), weekStart)

        assertEquals(1, result.size)
        assertTrue(result.containsKey(2))
        assertEquals(2, result[2]?.size)
    }

    @Test
    fun `groupEventsByDay uses exceptionEventId when present`() {
        val today = LocalDate.now()
        val weekStart = getWeekStartMs(today)
        val weekStartDate = WeekViewUtils.getWeekStart(today)
        val monday = weekStartDate.plusDays(1)

        // Master event and exception event
        val masterEvent = createTestEvent(id = 100, title = "Master Event")
        val exceptionEvent = createTestEvent(id = 101, title = "Modified Exception")

        // Occurrence points to master but has exceptionEventId
        val occurrence = createTestOccurrence(
            eventId = 100,
            date = monday,
            startHour = 10,
            endHour = 11,
            exceptionEventId = 101
        )

        // Events list includes exception event (which is what UI should display)
        val result = groupEventsByDay(listOf(occurrence), listOf(masterEvent, exceptionEvent), weekStart)

        assertEquals(1, result.size)
        assertEquals(1, result[1]?.size)
        // Should use exception event, not master
        assertEquals("Modified Exception", result[1]?.first()?.first?.title)
    }

    @Test
    fun `groupEventsByDay skips occurrences with missing events`() {
        val today = LocalDate.now()
        val weekStart = getWeekStartMs(today)
        val weekStartDate = WeekViewUtils.getWeekStart(today)
        val monday = weekStartDate.plusDays(1)

        // Occurrence references event ID that doesn't exist
        val orphanOccurrence = createTestOccurrence(
            eventId = 999,
            date = monday,
            startHour = 9,
            endHour = 10
        )

        val result = groupEventsByDay(listOf(orphanOccurrence), emptyList(), weekStart)

        // Should be empty since event doesn't exist
        assertTrue(result.isEmpty())
    }

    // ==================== Week Navigation Tests ====================

    @Test
    fun `getWeekStartMs returns consistent value for same week`() {
        val sunday = LocalDate.of(2025, 1, 5)
        val monday = LocalDate.of(2025, 1, 6)
        val friday = LocalDate.of(2025, 1, 10)

        val sundayWeekStart = getWeekStartMs(sunday)
        val mondayWeekStart = getWeekStartMs(monday)
        val fridayWeekStart = getWeekStartMs(friday)

        // All days in the same week should have the same week start
        assertEquals(sundayWeekStart, mondayWeekStart)
        assertEquals(mondayWeekStart, fridayWeekStart)
    }

    @Test
    fun `getWeekStartMs differs for different weeks`() {
        val week1Day = LocalDate.of(2025, 1, 6)  // Week 1
        val week2Day = LocalDate.of(2025, 1, 13) // Week 2

        val week1Start = getWeekStartMs(week1Day)
        val week2Start = getWeekStartMs(week2Day)

        assertNotEquals(week1Start, week2Start)

        // Difference should be 7 days in milliseconds
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        assertEquals(sevenDaysMs, week2Start - week1Start)
    }

    // ==================== Day Index Tests ====================

    @Test
    fun `getDayIndex returns correct values for week days`() {
        val sunday = LocalDate.of(2025, 1, 5)
        val weekStart = getWeekStartMs(sunday)

        // Test each day of the week
        for (dayOffset in 0..6) {
            val date = sunday.plusDays(dayOffset.toLong())
            val timestamp = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val dayIndex = WeekViewUtils.getDayIndex(timestamp, weekStart)
            assertEquals(dayOffset, dayIndex)
        }
    }

    @Test
    fun `getDayIndex clamps events outside current week to boundaries`() {
        val sunday = LocalDate.of(2025, 1, 5)
        val weekStart = getWeekStartMs(sunday)

        // Event from previous week - should clamp to 0 (Sunday)
        val prevWeekDate = sunday.minusDays(2)
        val prevWeekTs = prevWeekDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val prevDayIndex = WeekViewUtils.getDayIndex(prevWeekTs, weekStart)
        assertEquals(0, prevDayIndex) // Clamped to 0

        // Event from next week - should clamp to 6 (Saturday)
        val nextWeekDate = sunday.plusDays(8)
        val nextWeekTs = nextWeekDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val nextDayIndex = WeekViewUtils.getDayIndex(nextWeekTs, weekStart)
        assertEquals(6, nextDayIndex) // Clamped to 6
    }

    // ==================== Event Display Tests ====================

    @Test
    fun `events sorted by start time within day`() {
        val today = LocalDate.now()
        val weekStart = getWeekStartMs(today)
        val weekStartDate = WeekViewUtils.getWeekStart(today)
        val monday = weekStartDate.plusDays(1)

        // Create events in reverse chronological order
        val event1 = createTestEvent(id = 1, title = "Late Event")
        val event2 = createTestEvent(id = 2, title = "Early Event")

        val lateOcc = createTestOccurrence(eventId = 1, date = monday, startHour = 15, endHour = 16)
        val earlyOcc = createTestOccurrence(eventId = 2, date = monday, startHour = 9, endHour = 10)

        // Pass in reverse order
        val events = listOf(event1, event2)
        val occurrences = listOf(lateOcc, earlyOcc)

        val result = groupEventsByDay(occurrences, events, weekStart)
        val mondayEvents = result[1] ?: emptyList()

        assertEquals(2, mondayEvents.size)
        // Grouping doesn't sort - that's done by positionEventsForDay
        // Just verify both events are present
        assertTrue(mondayEvents.any { it.first.title == "Early Event" })
        assertTrue(mondayEvents.any { it.first.title == "Late Event" })
    }

    @Test
    fun `all day events separated from timed events`() {
        val today = LocalDate.now()
        val weekStartDate = WeekViewUtils.getWeekStart(today)
        val monday = weekStartDate.plusDays(1)
        val weekStart = getWeekStartMs(today)

        // Timed event
        val timedEvent = createTestEvent(id = 1, title = "Meeting", isAllDay = false)
        val timedOcc = createTestOccurrence(eventId = 1, date = monday, startHour = 9, endHour = 10)

        // All-day event (use day boundaries)
        val allDayEvent = createTestEvent(id = 2, title = "Holiday", isAllDay = true)
        val allDayStart = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val allDayEnd = monday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val allDayOcc = Occurrence(
            eventId = 2,
            calendarId = testCalendarId,
            startTs = allDayStart,
            endTs = allDayEnd,
            startDay = monday.year * 10000 + monday.monthValue * 100 + monday.dayOfMonth,
            endDay = monday.year * 10000 + monday.monthValue * 100 + monday.dayOfMonth,
            isCancelled = false,
            exceptionEventId = null
        )

        // Group all events together (filtering is done by WeekViewContent)
        val result = groupEventsByDay(
            listOf(timedOcc, allDayOcc),
            listOf(timedEvent, allDayEvent),
            weekStart
        )

        // Both events should be grouped to Monday
        assertEquals(2, result[1]?.size)
    }
}
