package org.onekash.kashcal.ui.components.weekview

import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId

class WeekViewUtilsDragTest {

    // ==================== DragState Tests ====================

    @Test
    fun `DragState Idle has correct defaults`() {
        val idle = WeekViewUtils.DragState.Idle
        assertFalse(idle.isDragging)
        assertNull(idle.draggedEvent)
        assertNull(idle.targetDate)
        assertEquals(0, idle.originalStartMinutes)
        assertEquals(0f, idle.currentOffsetX, 0.001f)
        assertEquals(0f, idle.currentOffsetY, 0.001f)
        assertEquals(0, idle.targetStartMinutes)
        assertEquals(0, idle.durationMinutes)
    }

    @Test
    fun `DragState copy with isDragging true`() {
        val active = WeekViewUtils.DragState.Idle.copy(
            isDragging = true,
            originalDate = LocalDate.of(2026, 4, 17),
            originalStartMinutes = 600,
            durationMinutes = 60
        )
        assertTrue(active.isDragging)
        assertEquals(LocalDate.of(2026, 4, 17), active.originalDate)
        assertEquals(600, active.originalStartMinutes)
        assertEquals(60, active.durationMinutes)
    }

    // ==================== minutesToTimeLabel Tests ====================

    @Test
    fun `minutesToTimeLabel midnight 12h`() {
        val result = WeekViewUtils.minutesToTimeLabel(0, is24Hour = false)
        assertEquals("12:00 AM", result)
    }

    @Test
    fun `minutesToTimeLabel midnight 24h`() {
        val result = WeekViewUtils.minutesToTimeLabel(0, is24Hour = true)
        assertEquals("0:00", result)
    }

    @Test
    fun `minutesToTimeLabel 10 15 AM in 12h`() {
        val result = WeekViewUtils.minutesToTimeLabel(10 * 60 + 15, is24Hour = false)
        assertEquals("10:15 AM", result)
    }

    @Test
    fun `minutesToTimeLabel 10 15 in 24h`() {
        val result = WeekViewUtils.minutesToTimeLabel(10 * 60 + 15, is24Hour = true)
        assertEquals("10:15", result)
    }

    @Test
    fun `minutesToTimeLabel noon 12h`() {
        val result = WeekViewUtils.minutesToTimeLabel(12 * 60, is24Hour = false)
        assertEquals("12:00 PM", result)
    }

    @Test
    fun `minutesToTimeLabel noon 24h`() {
        val result = WeekViewUtils.minutesToTimeLabel(12 * 60, is24Hour = true)
        assertEquals("12:00", result)
    }

    @Test
    fun `minutesToTimeLabel 11 45 PM in 12h`() {
        val result = WeekViewUtils.minutesToTimeLabel(23 * 60 + 45, is24Hour = false)
        assertEquals("11:45 PM", result)
    }

    @Test
    fun `minutesToTimeLabel 23 45 in 24h`() {
        val result = WeekViewUtils.minutesToTimeLabel(23 * 60 + 45, is24Hour = true)
        assertEquals("23:45", result)
    }

    @Test
    fun `minutesToTimeLabel 1 PM in 12h`() {
        val result = WeekViewUtils.minutesToTimeLabel(13 * 60, is24Hour = false)
        assertEquals("1:00 PM", result)
    }

    // ==================== calculateDragTarget Tests ====================

    @Test
    fun `calculateDragTarget finger at first column midpoint returns first date`() {
        val dates = listOf(
            LocalDate.of(2026, 4, 13), // Mon
            LocalDate.of(2026, 4, 14), // Tue
            LocalDate.of(2026, 4, 15)  // Wed
        )
        val columnWidth = 100f
        val hourHeightPx = 60f

        val (date, minutes) = WeekViewUtils.calculateDragTarget(
            fingerX = 50f,  // midpoint of first column
            fingerY = 0f,
            columnWidth = columnWidth,
            visibleDates = dates,
            hourHeightPx = hourHeightPx,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(dates[0], date)
        assertEquals(0, minutes)
    }

    @Test
    fun `calculateDragTarget finger at second column returns second date`() {
        val dates = listOf(
            LocalDate.of(2026, 4, 13),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 4, 15)
        )
        val columnWidth = 100f

        val (date, _) = WeekViewUtils.calculateDragTarget(
            fingerX = 150f,  // midpoint of second column
            fingerY = 0f,
            columnWidth = columnWidth,
            visibleDates = dates,
            hourHeightPx = 60f,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(dates[1], date)
    }

    @Test
    fun `calculateDragTarget finger at last column returns last date`() {
        val dates = listOf(
            LocalDate.of(2026, 4, 13),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 4, 15)
        )
        val columnWidth = 100f

        val (date, _) = WeekViewUtils.calculateDragTarget(
            fingerX = 250f,  // midpoint of last column
            fingerY = 0f,
            columnWidth = columnWidth,
            visibleDates = dates,
            hourHeightPx = 60f,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(dates[2], date)
    }

    @Test
    fun `calculateDragTarget finger y maps to correct snapped minutes`() {
        val dates = listOf(LocalDate.of(2026, 4, 14))
        val hourHeightPx = 60f

        // 2.5 hours down = 150 minutes, snapped to 150 (already on quarter hour)
        val (_, minutes) = WeekViewUtils.calculateDragTarget(
            fingerX = 50f,
            fingerY = 150f,  // 2.5 hours * 60px/hour
            columnWidth = 100f,
            visibleDates = dates,
            hourHeightPx = hourHeightPx,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(150, minutes)
    }

    @Test
    fun `calculateDragTarget snaps to 15 minute intervals`() {
        val dates = listOf(LocalDate.of(2026, 4, 14))
        val hourHeightPx = 60f

        // 73 minutes → snaps to 75 (1:15)
        val yOffset = 73f / 60f * hourHeightPx
        val (_, minutes) = WeekViewUtils.calculateDragTarget(
            fingerX = 50f,
            fingerY = yOffset,
            columnWidth = 100f,
            visibleDates = dates,
            hourHeightPx = hourHeightPx,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(75, minutes)
    }

    @Test
    fun `calculateDragTarget accounts for scroll offset`() {
        val dates = listOf(LocalDate.of(2026, 4, 14))
        val hourHeightPx = 60f

        // Scrolled 120px (2 hours) + finger at y=60 (1 hour) = 3 hours = 180 min
        val (_, minutes) = WeekViewUtils.calculateDragTarget(
            fingerX = 50f,
            fingerY = 60f,
            columnWidth = 100f,
            visibleDates = dates,
            hourHeightPx = hourHeightPx,
            scrollOffsetPx = 120,
            startHour = 0
        )

        assertEquals(180, minutes)
    }

    @Test
    fun `calculateDragTarget clamps negative x to first column`() {
        val dates = listOf(
            LocalDate.of(2026, 4, 13),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 4, 15)
        )

        val (date, _) = WeekViewUtils.calculateDragTarget(
            fingerX = -10f,
            fingerY = 0f,
            columnWidth = 100f,
            visibleDates = dates,
            hourHeightPx = 60f,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(dates[0], date)
    }

    @Test
    fun `calculateDragTarget clamps oversized x to last column`() {
        val dates = listOf(
            LocalDate.of(2026, 4, 13),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 4, 15)
        )

        val (date, _) = WeekViewUtils.calculateDragTarget(
            fingerX = 500f,  // well past all columns
            fingerY = 0f,
            columnWidth = 100f,
            visibleDates = dates,
            hourHeightPx = 60f,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(dates[2], date)
    }

    @Test
    fun `calculateDragTarget clamps minutes to non-negative`() {
        val dates = listOf(LocalDate.of(2026, 4, 14))

        val (_, minutes) = WeekViewUtils.calculateDragTarget(
            fingerX = 50f,
            fingerY = -100f,  // above the grid
            columnWidth = 100f,
            visibleDates = dates,
            hourHeightPx = 60f,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(0, minutes)
    }

    @Test
    fun `calculateDragTarget with 7 day week view maps all columns`() {
        val dates = (0L..6L).map { LocalDate.of(2026, 4, 12).plusDays(it) }
        val columnWidth = 50f

        // Finger at midpoint of column 4 (Thursday Apr 16)
        val (date, _) = WeekViewUtils.calculateDragTarget(
            fingerX = 225f,  // column 4 midpoint: 4 * 50 + 25
            fingerY = 0f,
            columnWidth = columnWidth,
            visibleDates = dates,
            hourHeightPx = 60f,
            scrollOffsetPx = 0,
            startHour = 0
        )

        assertEquals(LocalDate.of(2026, 4, 16), date)
    }

    // ==================== clampDragStartMinutes Tests ====================

    @Test
    fun `clampDragStartMinutes no clamping needed`() {
        // 2 PM + 60 min = 3 PM, well within bounds
        val result = WeekViewUtils.clampDragStartMinutes(14 * 60, 60)
        assertEquals(14 * 60, result)
    }

    @Test
    fun `clampDragStartMinutes clamps when end exceeds midnight`() {
        // 23:00 + 120 min = 25:00, must clamp start so end <= 23:59
        val result = WeekViewUtils.clampDragStartMinutes(23 * 60, 120)
        // Max start = 24*60 - 1 - 120 = 1319 = 21:59
        assertEquals(24 * 60 - 1 - 120, result)
    }

    @Test
    fun `clampDragStartMinutes at exact boundary`() {
        // 23:00 + 59 min = 23:59, exactly at boundary
        val result = WeekViewUtils.clampDragStartMinutes(23 * 60, 59)
        assertEquals(23 * 60, result)
    }

    @Test
    fun `clampDragStartMinutes 15 min event at 23 45`() {
        // 23:45 + 15 min = 24:00, needs to clamp
        // Max start = 1439 - 15 = 1424 = 23:44
        val result = WeekViewUtils.clampDragStartMinutes(23 * 60 + 45, 15)
        assertEquals(24 * 60 - 1 - 15, result)
    }

    @Test
    fun `clampDragStartMinutes zero duration no clamp`() {
        val result = WeekViewUtils.clampDragStartMinutes(23 * 60 + 59, 0)
        assertEquals(23 * 60 + 59, result)
    }

    @Test
    fun `clampDragStartMinutes non-negative start`() {
        val result = WeekViewUtils.clampDragStartMinutes(0, 60)
        assertEquals(0, result)
    }

    // ==================== calculateNewTimestamps Tests ====================

    @Test
    fun `calculateNewTimestamps correct UTC millis for known date`() {
        val date = LocalDate.of(2026, 4, 17)
        val startMinutes = 10 * 60 + 30  // 10:30 AM
        val durationMinutes = 60

        val (startTs, endTs) = WeekViewUtils.calculateNewTimestamps(date, startMinutes, durationMinutes)

        // Verify duration is preserved
        assertEquals(durationMinutes * 60 * 1000L, endTs - startTs)

        // Verify the start time maps back to the expected local time
        val zone = ZoneId.systemDefault()
        val resultTime = java.time.Instant.ofEpochMilli(startTs).atZone(zone)
        assertEquals(2026, resultTime.year)
        assertEquals(4, resultTime.monthValue)
        assertEquals(17, resultTime.dayOfMonth)
        assertEquals(10, resultTime.hour)
        assertEquals(30, resultTime.minute)
    }

    @Test
    fun `calculateNewTimestamps midnight start`() {
        val date = LocalDate.of(2026, 1, 1)
        val startMinutes = 0
        val durationMinutes = 30

        val (startTs, endTs) = WeekViewUtils.calculateNewTimestamps(date, startMinutes, durationMinutes)

        assertEquals(durationMinutes * 60 * 1000L, endTs - startTs)

        val zone = ZoneId.systemDefault()
        val resultTime = java.time.Instant.ofEpochMilli(startTs).atZone(zone)
        assertEquals(0, resultTime.hour)
        assertEquals(0, resultTime.minute)
    }

    @Test
    fun `calculateNewTimestamps end of day`() {
        val date = LocalDate.of(2026, 4, 17)
        val startMinutes = 23 * 60 + 30  // 11:30 PM
        val durationMinutes = 29  // ends 11:59 PM

        val (startTs, endTs) = WeekViewUtils.calculateNewTimestamps(date, startMinutes, durationMinutes)

        assertEquals(durationMinutes * 60 * 1000L, endTs - startTs)

        val zone = ZoneId.systemDefault()
        val resultTime = java.time.Instant.ofEpochMilli(startTs).atZone(zone)
        assertEquals(23, resultTime.hour)
        assertEquals(30, resultTime.minute)
    }

    @Test
    fun `calculateNewTimestamps preserves duration exactly`() {
        val date = LocalDate.of(2026, 6, 15)
        val startMinutes = 9 * 60 + 15  // 9:15 AM
        val durationMinutes = 90  // 1.5 hours

        val (startTs, endTs) = WeekViewUtils.calculateNewTimestamps(date, startMinutes, durationMinutes)

        assertEquals(90L * 60 * 1000, endTs - startTs)
    }
}
