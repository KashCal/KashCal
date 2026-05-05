package org.onekash.kashcal.ui.components.weekview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
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

    // ==================== groupEventsByDate Tests (Multi-day expansion) ====================

    @Test
    fun `groupEventsByDate expands multi-day event to all days`() {
        // 3-day event: Jan 15-17
        val jan15 = LocalDate.of(2026, 1, 15)
        val jan17 = LocalDate.of(2026, 1, 17)

        val event = createTestEvent(id = 100, title = "Conference", isAllDay = false)
        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = jan15.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            endTs = jan17.atTime(17, 0).atZone(zone).toInstant().toEpochMilli(),
            startDay = 20260115,
            endDay = 20260117,
            isCancelled = false
        )

        val grouped = groupEventsByDate(listOf(occurrence), listOf(event))

        // Should appear on all 3 days
        assertEquals(3, grouped.size)
        assertTrue(grouped.containsKey(jan15))
        assertTrue(grouped.containsKey(LocalDate.of(2026, 1, 16)))
        assertTrue(grouped.containsKey(jan17))

        // Each day should have the same event
        assertEquals("Conference", grouped[jan15]?.first()?.first?.title)
        assertEquals("Conference", grouped[LocalDate.of(2026, 1, 16)]?.first()?.first?.title)
        assertEquals("Conference", grouped[jan17]?.first()?.first?.title)
    }

    @Test
    fun `groupEventsByDate handles single-day event`() {
        val jan15 = LocalDate.of(2026, 1, 15)

        val event = createTestEvent(id = 100, title = "Meeting", isAllDay = false)
        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = jan15.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            endTs = jan15.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            startDay = 20260115,
            endDay = 20260115,
            isCancelled = false
        )

        val grouped = groupEventsByDate(listOf(occurrence), listOf(event))

        assertEquals(1, grouped.size)
        assertTrue(grouped.containsKey(jan15))
    }

    @Test
    fun `groupEventsByDate uses UTC for all-day events via startDay`() {
        // All-day on Jan 15 stored as Jan 15 00:00 UTC
        // In negative UTC offset (e.g., PST = UTC-8), local time would be Jan 14 4PM
        // But startDay is pre-calculated with UTC, so it should be 20260115
        val jan15 = LocalDate.of(2026, 1, 15)

        val event = createTestEvent(id = 100, title = "Holiday", isAllDay = true)
        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = jan15.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endTs = jan15.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 86400000 - 1,
            startDay = 20260115, // Pre-calculated as UTC
            endDay = 20260115,
            isCancelled = false
        )

        val grouped = groupEventsByDate(listOf(occurrence), listOf(event))

        // Should be Jan 15, not Jan 14 (regardless of local timezone)
        assertEquals(1, grouped.size)
        assertTrue(grouped.containsKey(jan15))
    }

    @Test
    fun `groupEventsByDate handles month boundary multi-day event`() {
        // Event spanning Jan 30 - Feb 2
        val event = createTestEvent(id = 100, title = "Workshop", isAllDay = false)
        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = now,
            endTs = now + 4 * 86400000,
            startDay = 20260130,
            endDay = 20260202,
            isCancelled = false
        )

        val grouped = groupEventsByDate(listOf(occurrence), listOf(event))

        assertEquals(4, grouped.size)
        assertTrue(grouped.containsKey(LocalDate.of(2026, 1, 30)))
        assertTrue(grouped.containsKey(LocalDate.of(2026, 1, 31)))
        assertTrue(grouped.containsKey(LocalDate.of(2026, 2, 1)))
        assertTrue(grouped.containsKey(LocalDate.of(2026, 2, 2)))
    }

    @Test
    fun `groupEventsByDate uses exceptionEventId when present`() {
        val jan15 = LocalDate.of(2026, 1, 15)

        val masterEvent = createTestEvent(id = 100, title = "Master Event")
        val exceptionEvent = createTestEvent(id = 101, title = "Modified Exception")

        val occurrence = Occurrence(
            eventId = 100,
            calendarId = testCalendarId,
            startTs = jan15.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            endTs = jan15.atTime(11, 0).atZone(zone).toInstant().toEpochMilli(),
            startDay = 20260115,
            endDay = 20260115,
            isCancelled = false,
            exceptionEventId = 101
        )

        val grouped = groupEventsByDate(listOf(occurrence), listOf(masterEvent, exceptionEvent))

        assertEquals(1, grouped.size)
        // Should use exception event, not master
        assertEquals("Modified Exception", grouped[jan15]?.first()?.first?.title)
    }

    // ==================== Helper: groupEventsByDate ====================

    /**
     * Copy of groupEventsByDate from WeekViewContent for testing.
     * Uses pre-calculated startDay/endDay and expands multi-day events.
     */
    private fun groupEventsByDate(
        occurrences: List<Occurrence>,
        events: List<Event>
    ): Map<LocalDate, List<Pair<Event, Occurrence>>> {
        val eventMap = events.associateBy { it.id }
        val result = mutableMapOf<LocalDate, MutableList<Pair<Event, Occurrence>>>()

        for (occurrence in occurrences) {
            val eventId = occurrence.exceptionEventId ?: occurrence.eventId
            val event = eventMap[eventId] ?: continue

            // Expand multi-day events to all days they span
            var currentDay = occurrence.startDay
            while (currentDay <= occurrence.endDay) {
                val date = DayPagerUtils.dayCodeToLocalDate(currentDay)
                result.getOrPut(date) { mutableListOf() }.add(event to occurrence)
                currentDay = Occurrence.incrementDayCode(currentDay)
            }
        }
        return result
    }
}
