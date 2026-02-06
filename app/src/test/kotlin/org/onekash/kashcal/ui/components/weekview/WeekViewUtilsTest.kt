package org.onekash.kashcal.ui.components.weekview

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for WeekViewUtils.
 * Tests week calculations, range formatting, time snapping, event clamping,
 * and infinite day pager functions.
 */
class WeekViewUtilsTest {

    // ==================== Day Pager Tests ====================

    @Test
    fun `pageToDate returns today for CENTER_DAY_PAGE`() {
        val today = LocalDate.now()
        val result = WeekViewUtils.pageToDate(WeekViewUtils.CENTER_DAY_PAGE)
        assertEquals(today, result)
    }

    @Test
    fun `pageToDate returns tomorrow for CENTER_DAY_PAGE + 1`() {
        val tomorrow = LocalDate.now().plusDays(1)
        val result = WeekViewUtils.pageToDate(WeekViewUtils.CENTER_DAY_PAGE + 1)
        assertEquals(tomorrow, result)
    }

    @Test
    fun `pageToDate returns yesterday for CENTER_DAY_PAGE - 1`() {
        val yesterday = LocalDate.now().minusDays(1)
        val result = WeekViewUtils.pageToDate(WeekViewUtils.CENTER_DAY_PAGE - 1)
        assertEquals(yesterday, result)
    }

    @Test
    fun `pageToDate handles large positive offset`() {
        val futureDate = LocalDate.now().plusDays(365)
        val result = WeekViewUtils.pageToDate(WeekViewUtils.CENTER_DAY_PAGE + 365)
        assertEquals(futureDate, result)
    }

    @Test
    fun `pageToDate handles large negative offset`() {
        val pastDate = LocalDate.now().minusDays(365)
        val result = WeekViewUtils.pageToDate(WeekViewUtils.CENTER_DAY_PAGE - 365)
        assertEquals(pastDate, result)
    }

    @Test
    fun `dateToPage returns CENTER_DAY_PAGE for today`() {
        val today = LocalDate.now()
        val result = WeekViewUtils.dateToPage(today)
        assertEquals(WeekViewUtils.CENTER_DAY_PAGE, result)
    }

    @Test
    fun `dateToPage returns CENTER_DAY_PAGE + 1 for tomorrow`() {
        val tomorrow = LocalDate.now().plusDays(1)
        val result = WeekViewUtils.dateToPage(tomorrow)
        assertEquals(WeekViewUtils.CENTER_DAY_PAGE + 1, result)
    }

    @Test
    fun `dateToPage returns CENTER_DAY_PAGE - 1 for yesterday`() {
        val yesterday = LocalDate.now().minusDays(1)
        val result = WeekViewUtils.dateToPage(yesterday)
        assertEquals(WeekViewUtils.CENTER_DAY_PAGE - 1, result)
    }

    @Test
    fun `pageToDate and dateToPage are inverse operations`() {
        // Test various dates
        val testDates = listOf(
            LocalDate.now(),
            LocalDate.now().plusDays(100),
            LocalDate.now().minusDays(100),
            LocalDate.now().plusDays(1000),
            LocalDate.now().minusDays(1000)
        )

        for (date in testDates) {
            val page = WeekViewUtils.dateToPage(date)
            val roundTrip = WeekViewUtils.pageToDate(page)
            assertEquals("Round trip failed for $date", date, roundTrip)
        }
    }

    @Test
    fun `getVisibleDateRange returns 3 consecutive days`() {
        val (start, end) = WeekViewUtils.getVisibleDateRange(WeekViewUtils.CENTER_DAY_PAGE)

        val today = LocalDate.now()
        assertEquals(today, start)
        assertEquals(today.plusDays(2), end)
    }

    @Test
    fun `getVisibleDateRange respects visibleDays parameter`() {
        val (start, end) = WeekViewUtils.getVisibleDateRange(
            WeekViewUtils.CENTER_DAY_PAGE,
            visibleDays = 5
        )

        val today = LocalDate.now()
        assertEquals(today, start)
        assertEquals(today.plusDays(4), end)
    }

    @Test
    fun `getLoadingDateRange includes buffer days`() {
        val (start, end) = WeekViewUtils.getLoadingDateRange(
            WeekViewUtils.CENTER_DAY_PAGE,
            visibleDays = 3,
            bufferDays = 7
        )

        val today = LocalDate.now()
        assertEquals(today.minusDays(7), start)
        assertEquals(today.plusDays(9), end)  // 2 visible + 7 buffer
    }

    @Test
    fun `dateToEpochMs and epochMsToDate are inverse operations`() {
        val testDates = listOf(
            LocalDate.now(),
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2024, 12, 31),
            LocalDate.of(2030, 6, 15)
        )

        for (date in testDates) {
            val epochMs = WeekViewUtils.dateToEpochMs(date)
            val roundTrip = WeekViewUtils.epochMsToDate(epochMs)
            assertEquals("Round trip failed for $date", date, roundTrip)
        }
    }

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

    // ==================== First Day of Week Tests ====================

    @Test
    fun `getWeekStart wednesday with sunday first returns sunday`() {
        // Wednesday Jan 21, 2026
        val wednesday = LocalDate.of(2026, 1, 21)
        val weekStart = WeekViewUtils.getWeekStart(wednesday, java.util.Calendar.SUNDAY)

        // Should return Sunday Jan 18, 2026
        assertEquals(LocalDate.of(2026, 1, 18), weekStart)
    }

    @Test
    fun `getWeekStart wednesday with monday first returns monday`() {
        // Wednesday Jan 21, 2026
        val wednesday = LocalDate.of(2026, 1, 21)
        val weekStart = WeekViewUtils.getWeekStart(wednesday, java.util.Calendar.MONDAY)

        // Should return Monday Jan 19, 2026
        assertEquals(LocalDate.of(2026, 1, 19), weekStart)
    }

    @Test
    fun `getWeekStart sunday with sunday first returns same sunday`() {
        // Sunday Jan 18, 2026
        val sunday = LocalDate.of(2026, 1, 18)
        val weekStart = WeekViewUtils.getWeekStart(sunday, java.util.Calendar.SUNDAY)

        // Should return same Sunday Jan 18, 2026
        assertEquals(LocalDate.of(2026, 1, 18), weekStart)
    }

    @Test
    fun `getWeekStart sunday with monday first returns previous monday`() {
        // Sunday Jan 18, 2026
        val sunday = LocalDate.of(2026, 1, 18)
        val weekStart = WeekViewUtils.getWeekStart(sunday, java.util.Calendar.MONDAY)

        // Should return Monday Jan 12, 2026 (previous week's Monday)
        assertEquals(LocalDate.of(2026, 1, 12), weekStart)
    }

    @Test
    fun `getWeekStart saturday with saturday first returns same saturday`() {
        // Saturday Jan 17, 2026
        val saturday = LocalDate.of(2026, 1, 17)
        val weekStart = WeekViewUtils.getWeekStart(saturday, java.util.Calendar.SATURDAY)

        // Should return same Saturday Jan 17, 2026
        assertEquals(LocalDate.of(2026, 1, 17), weekStart)
    }

    @Test
    fun `getWeekStart friday with saturday first returns previous saturday`() {
        // Friday Jan 16, 2026
        val friday = LocalDate.of(2026, 1, 16)
        val weekStart = WeekViewUtils.getWeekStart(friday, java.util.Calendar.SATURDAY)

        // Should return Saturday Jan 10, 2026
        assertEquals(LocalDate.of(2026, 1, 10), weekStart)
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

    // ==================== Individual Date Formatting Tests ====================

    @Test
    fun `formatIndividualDate returns month and day for current year`() {
        // Use a date in the current year
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 5)  // Sunday Jan 5
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 0)  // Day 0 = Sunday

        assertEquals("Jan 5", result)
    }

    @Test
    fun `formatIndividualDate returns month day and year for different year`() {
        // Use a date in a future year
        val weekStart = LocalDate.of(2027, 6, 13)  // Sunday Jun 13, 2027
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 0)

        assertTrue(result.contains("Jun"))
        assertTrue(result.contains("13"))
        assertTrue(result.contains("2027"))
    }

    @Test
    fun `formatIndividualDate returns correct date for day index 3`() {
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 5)  // Sunday Jan 5
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 3)  // Day 3 = Wednesday

        assertEquals("Jan 8", result)
    }

    @Test
    fun `formatIndividualDate returns correct date for day index 6`() {
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 5)  // Sunday Jan 5
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 6)  // Day 6 = Saturday

        assertEquals("Jan 11", result)
    }

    @Test
    fun `formatIndividualDate handles month boundary`() {
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 26)  // Sunday Jan 26
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 6)  // Day 6 = Saturday Feb 1

        assertEquals("Feb 1", result)
    }

    // ==================== 24-Hour Format Tests ====================

    @Test
    fun `formatTimeRange with 24h pattern shows 24h format`() {
        // 2:00 PM - 3:30 PM UTC
        val startTs = 1767657600000L + (14 * 60 * 60 * 1000)  // Jan 6, 2026 14:00 UTC
        val endTs = startTs + (90 * 60 * 1000)  // +90 minutes

        val result = WeekViewUtils.formatTimeRange(startTs, endTs, "HH:mm")

        assertTrue("Expected 24h format with 14:00, got: $result", result.contains("14:00"))
        assertTrue("Expected 24h format with 15:30, got: $result", result.contains("15:30"))
    }

    @Test
    fun `formatTimeRange with 12h pattern shows 12h format`() {
        // Same times as above, but with 12h pattern
        val startTs = 1767657600000L + (14 * 60 * 60 * 1000)
        val endTs = startTs + (90 * 60 * 1000)

        val result = WeekViewUtils.formatTimeRange(startTs, endTs, "h:mma")

        assertTrue("Expected 12h format with 2:00, got: $result", result.contains("2:00"))
        assertTrue("Expected 12h format with pm, got: $result", result.lowercase().contains("pm"))
    }

    @Test
    fun `formatOverflowTime with 24h pattern shows 24h format`() {
        // 5:00 AM UTC
        val fiveAm = 1767657600000L + (5 * 60 * 60 * 1000)

        val result = WeekViewUtils.formatOverflowTime(fiveAm, "HH:mm")

        assertEquals("05:00", result)
    }

    @Test
    fun `formatOverflowTime with 12h pattern shows 12h format`() {
        val fiveAm = 1767657600000L + (5 * 60 * 60 * 1000)

        val result = WeekViewUtils.formatOverflowTime(fiveAm, "h:mma")

        assertEquals("5:00am", result)
    }

    @Test
    fun `formatClampIndicatorTime 24h format midnight`() {
        val result = WeekViewUtils.formatClampIndicatorTime(0, is24Hour = true)
        assertEquals("00:00", result)
    }

    @Test
    fun `formatClampIndicatorTime 24h format noon`() {
        val result = WeekViewUtils.formatClampIndicatorTime(12 * 60, is24Hour = true)
        assertEquals("12:00", result)
    }

    @Test
    fun `formatClampIndicatorTime 24h format 5am`() {
        val result = WeekViewUtils.formatClampIndicatorTime(5 * 60, is24Hour = true)
        assertEquals("05:00", result)
    }

    @Test
    fun `formatClampIndicatorTime 24h format 11_45pm`() {
        val result = WeekViewUtils.formatClampIndicatorTime(23 * 60 + 45, is24Hour = true)
        assertEquals("23:45", result)
    }

    @Test
    fun `formatClampIndicatorTime 12h format preserves existing behavior`() {
        // Verify backward compatibility - default (is24Hour=false) unchanged
        assertEquals("5am", WeekViewUtils.formatClampIndicatorTime(5 * 60, is24Hour = false))
        assertEquals("12am", WeekViewUtils.formatClampIndicatorTime(0, is24Hour = false))
        assertEquals("12pm", WeekViewUtils.formatClampIndicatorTime(12 * 60, is24Hour = false))
    }
}
