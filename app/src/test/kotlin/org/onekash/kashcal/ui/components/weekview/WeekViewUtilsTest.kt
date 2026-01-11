package org.onekash.kashcal.ui.components.weekview

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for WeekViewUtils.
 * Tests week calculations, range formatting, time snapping, and event clamping.
 */
class WeekViewUtilsTest {

    // ==================== Week Calculation Tests ====================

    @Test
    fun `getWeekStart returns Sunday for mid-week date`() {
        // Wednesday Jan 8, 2025
        val wednesday = LocalDate.of(2025, 1, 8)
        val weekStart = WeekViewUtils.getWeekStart(wednesday)

        // Should return Sunday Jan 5, 2025
        assertEquals(LocalDate.of(2025, 1, 5), weekStart)
    }

    @Test
    fun `getWeekStart returns same day for Sunday`() {
        // Sunday Jan 5, 2025
        val sunday = LocalDate.of(2025, 1, 5)
        val weekStart = WeekViewUtils.getWeekStart(sunday)

        assertEquals(sunday, weekStart)
    }

    @Test
    fun `getWeekStart handles month boundary`() {
        // Wednesday Feb 5, 2025 - week starts in January
        val feb5 = LocalDate.of(2025, 2, 5)
        val weekStart = WeekViewUtils.getWeekStart(feb5)

        // Should return Sunday Feb 2, 2025
        assertEquals(LocalDate.of(2025, 2, 2), weekStart)
    }

    @Test
    fun `getWeekStart handles year boundary`() {
        // Wednesday Jan 1, 2025 - week starts in December 2024
        val jan1 = LocalDate.of(2025, 1, 1)
        val weekStart = WeekViewUtils.getWeekStart(jan1)

        // Should return Sunday Dec 29, 2024
        assertEquals(LocalDate.of(2024, 12, 29), weekStart)
    }

    @Test
    fun `getWeekStart handles Saturday`() {
        // Saturday Jan 11, 2025
        val saturday = LocalDate.of(2025, 1, 11)
        val weekStart = WeekViewUtils.getWeekStart(saturday)

        // Should return Sunday Jan 5, 2025
        assertEquals(LocalDate.of(2025, 1, 5), weekStart)
    }

    // ==================== Range Formatting Tests ====================

    @Test
    fun `formatCompactRange same month shows month and days`() {
        // Use current year so it doesn't show year suffix
        val currentYear = LocalDate.now().year
        val start = LocalDate.of(currentYear, 1, 6)
        val end = LocalDate.of(currentYear, 1, 8)

        val result = WeekViewUtils.formatCompactRange(start, end)

        assertEquals("Jan 6-8", result)
    }

    @Test
    fun `formatCompactRange cross month shows Dec 30 - Jan 1`() {
        val start = LocalDate.of(2024, 12, 30)
        val end = LocalDate.of(2025, 1, 1)

        val result = WeekViewUtils.formatCompactRange(start, end)

        // Cross-year always shows year
        assertTrue(result.contains("Dec 30"))
        assertTrue(result.contains("Jan 1"))
        assertTrue(result.contains("2025"))
    }

    @Test
    fun `formatCompactRange shows year when not current year`() {
        val start = LocalDate.of(2027, 6, 15)
        val end = LocalDate.of(2027, 6, 17)

        val result = WeekViewUtils.formatCompactRange(start, end)

        assertTrue(result.contains("2027"))
    }

    // ==================== Time Snapping Tests ====================

    @Test
    fun `snapToQuarterHour rounds 7 to 0`() {
        val result = WeekViewUtils.snapToQuarterHour(7)
        assertEquals(0, result)
    }

    @Test
    fun `snapToQuarterHour rounds 8 to 15`() {
        val result = WeekViewUtils.snapToQuarterHour(8)
        assertEquals(15, result)
    }

    @Test
    fun `snapToQuarterHour rounds 23 to 30`() {
        val result = WeekViewUtils.snapToQuarterHour(23)
        assertEquals(30, result)
    }

    @Test
    fun `snapToQuarterHour keeps 0 as 0`() {
        val result = WeekViewUtils.snapToQuarterHour(0)
        assertEquals(0, result)
    }

    @Test
    fun `snapToQuarterHour keeps 15 as 15`() {
        val result = WeekViewUtils.snapToQuarterHour(15)
        assertEquals(15, result)
    }

    @Test
    fun `snapToQuarterHour rounds 37 to 30`() {
        val result = WeekViewUtils.snapToQuarterHour(37)
        assertEquals(30, result)
    }

    @Test
    fun `snapToQuarterHour rounds 53 to 60`() {
        // 53 + 7 = 60, 60 / 15 * 15 = 60
        val result = WeekViewUtils.snapToQuarterHour(53)
        assertEquals(60, result)
    }

    @Test
    fun `snapToQuarterHour rounds 59 to 60`() {
        val result = WeekViewUtils.snapToQuarterHour(59)
        assertEquals(60, result)
    }

    // ==================== Event Clamping Tests ====================

    @Test
    fun `clamp event starting at 5am to 6am with indicator`() {
        // 5:00 AM = 300 minutes
        val result = WeekViewUtils.clampToVisibleRange(
            startMinutes = 300,
            endMinutes = 480  // 8:00 AM
        )

        assertEquals(360, result.displayStartMinutes)  // 6:00 AM
        assertEquals(480, result.displayEndMinutes)    // 8:00 AM unchanged
        assertTrue(result.clampedStart)
        assertFalse(result.clampedEnd)
        assertEquals(300, result.originalStartMinutes)
    }

    @Test
    fun `clamp event ending at midnight to 11pm with indicator`() {
        // Event from 10pm to midnight
        val result = WeekViewUtils.clampToVisibleRange(
            startMinutes = 22 * 60,  // 10:00 PM = 1320
            endMinutes = 24 * 60     // 12:00 AM = 1440
        )

        assertEquals(1320, result.displayStartMinutes)  // 10:00 PM unchanged
        assertEquals(1380, result.displayEndMinutes)    // 11:00 PM (clamped)
        assertFalse(result.clampedStart)
        assertTrue(result.clampedEnd)
        assertEquals(1440, result.originalEndMinutes)
    }

    @Test
    fun `event within range not clamped`() {
        // Event from 9am to 5pm
        val result = WeekViewUtils.clampToVisibleRange(
            startMinutes = 9 * 60,   // 540
            endMinutes = 17 * 60     // 1020
        )

        assertEquals(540, result.displayStartMinutes)
        assertEquals(1020, result.displayEndMinutes)
        assertFalse(result.clampedStart)
        assertFalse(result.clampedEnd)
    }

    @Test
    fun `event spanning entire day clamped both ends`() {
        // Event from midnight to midnight (all day)
        val result = WeekViewUtils.clampToVisibleRange(
            startMinutes = 0,
            endMinutes = 24 * 60
        )

        assertEquals(360, result.displayStartMinutes)   // 6:00 AM
        assertEquals(1380, result.displayEndMinutes)    // 11:00 PM
        assertTrue(result.clampedStart)
        assertTrue(result.clampedEnd)
    }

    // ==================== Time Formatting Tests ====================

    @Test
    fun `formatClampIndicatorTime formats 5am correctly`() {
        val result = WeekViewUtils.formatClampIndicatorTime(5 * 60)
        assertEquals("5am", result)
    }

    @Test
    fun `formatClampIndicatorTime formats 5_30am correctly`() {
        val result = WeekViewUtils.formatClampIndicatorTime(5 * 60 + 30)
        assertEquals("5:30am", result)
    }

    @Test
    fun `formatClampIndicatorTime formats midnight correctly`() {
        val result = WeekViewUtils.formatClampIndicatorTime(0)
        assertEquals("12am", result)
    }

    @Test
    fun `formatClampIndicatorTime formats noon correctly`() {
        val result = WeekViewUtils.formatClampIndicatorTime(12 * 60)
        assertEquals("12pm", result)
    }

    @Test
    fun `formatClampIndicatorTime formats 11_45pm correctly`() {
        val result = WeekViewUtils.formatClampIndicatorTime(23 * 60 + 45)
        assertEquals("11:45pm", result)
    }

    // ==================== Weekend Detection Tests ====================

    @Test
    fun `isWeekend returns true for Saturday`() {
        val saturday = LocalDate.of(2025, 1, 11)  // Saturday
        assertTrue(WeekViewUtils.isWeekend(saturday))
    }

    @Test
    fun `isWeekend returns true for Sunday`() {
        val sunday = LocalDate.of(2025, 1, 12)  // Sunday
        assertTrue(WeekViewUtils.isWeekend(sunday))
    }

    @Test
    fun `isWeekend returns false for Monday`() {
        val monday = LocalDate.of(2025, 1, 13)  // Monday
        assertFalse(WeekViewUtils.isWeekend(monday))
    }

    @Test
    fun `isWeekend returns false for Wednesday`() {
        val wednesday = LocalDate.of(2025, 1, 8)  // Wednesday
        assertFalse(WeekViewUtils.isWeekend(wednesday))
    }

    // ==================== Day Header Formatting Tests ====================

    @Test
    fun `formatDayHeader includes day name and number`() {
        val monday = LocalDate.of(2025, 1, 6)  // Monday Jan 6
        val result = WeekViewUtils.formatDayHeader(monday)

        assertTrue(result.contains("6"))
        // Day name varies by locale, just check it has some content
        assertTrue(result.length > 2)
    }

    // ==================== Offset to Time Tests ====================

    @Test
    fun `offsetToTime at top returns 6am`() {
        val (hour, minute) = WeekViewUtils.offsetToTime(0f, 60f, snap = false)
        assertEquals(6, hour)
        assertEquals(0, minute)
    }

    @Test
    fun `offsetToTime with snap rounds to quarter hour`() {
        // 6:17 should snap to 6:15
        val minuteOffset = 17f / 60f * 60f  // 17 minutes into the grid
        val (hour, minute) = WeekViewUtils.offsetToTime(minuteOffset, 60f, snap = true)
        assertEquals(6, hour)
        assertEquals(15, minute)
    }

    // ==================== Day Index Tests ====================

    @Test
    fun `getDayIndex returns 0 for Sunday`() {
        // Sunday Jan 5, 2025 at noon
        val sundayNoon = LocalDate.of(2025, 1, 5)
            .atStartOfDay(ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()
        val weekStart = WeekViewUtils.getWeekStartMs(sundayNoon)

        val dayIndex = WeekViewUtils.getDayIndex(sundayNoon, weekStart)
        assertEquals(0, dayIndex)
    }

    @Test
    fun `getDayIndex returns 3 for Wednesday`() {
        // Wednesday Jan 8, 2025 at noon
        val wednesdayNoon = LocalDate.of(2025, 1, 8)
            .atStartOfDay(ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()
        val weekStart = WeekViewUtils.getWeekStartMs(wednesdayNoon)

        val dayIndex = WeekViewUtils.getDayIndex(wednesdayNoon, weekStart)
        assertEquals(3, dayIndex)
    }

    @Test
    fun `getDayIndex returns 6 for Saturday`() {
        // Saturday Jan 11, 2025 at noon
        val saturdayNoon = LocalDate.of(2025, 1, 11)
            .atStartOfDay(ZoneId.systemDefault())
            .plusHours(12)
            .toInstant()
            .toEpochMilli()
        val weekStart = WeekViewUtils.getWeekStartMs(saturdayNoon)

        val dayIndex = WeekViewUtils.getDayIndex(saturdayNoon, weekStart)
        assertEquals(6, dayIndex)
    }
}
