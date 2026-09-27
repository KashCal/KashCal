package org.onekash.kashcal.ui.components.weekview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests [WeekViewUtils]: the day and week pagers, the day-strip render gate, visible and
 * loading date ranges, epoch-date conversion, week starts per first day of week, range, day
 * header, individual date, month-year, week-label and time-range formatting, quarter-hour
 * snapping, offset-to-time, weekend and day-index lookups, the initial scroll offset and
 * visible start hour, minutes-pixels conversion, pinch-zoom recentring and the all-day
 * overflow check.
 */
@RunWith(RobolectricTestRunner::class)
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

    // ==================== isSettledDayPage (strip render gate) ====================
    //
    // The DAY strip must not render off a stale WEEK-scale page or the uninitialized
    // default: both are far below any real day page and pageToDate() would map them to
    // dates millions of years off. These pin the day-scale vs week-scale boundary.

    @Test
    fun `isSettledDayPage is true for a real day page`() {
        assertTrue(WeekViewUtils.isSettledDayPage(WeekViewUtils.CENTER_DAY_PAGE))
        assertTrue(WeekViewUtils.isSettledDayPage(WeekViewUtils.CENTER_DAY_PAGE + 365))
        assertTrue(WeekViewUtils.isSettledDayPage(WeekViewUtils.CENTER_DAY_PAGE - 365))
    }

    @Test
    fun `isSettledDayPage is false for the uninitialized default`() {
        assertFalse(WeekViewUtils.isSettledDayPage(0))
    }

    @Test
    fun `isSettledDayPage is false for a stale week-scale page`() {
        // A WEEK->DAY switch can leave the shared position holding a week page
        // (near CENTER_WEEK_PAGE) that must not be read as a day page.
        assertFalse(WeekViewUtils.isSettledDayPage(WeekViewUtils.CENTER_WEEK_PAGE))
        assertFalse(WeekViewUtils.isSettledDayPage(0))
        assertFalse(WeekViewUtils.isSettledDayPage(WeekViewUtils.TOTAL_WEEK_PAGES))
    }

    @Test
    fun `isSettledDayPage boundary is just above the week-page range`() {
        assertFalse(WeekViewUtils.isSettledDayPage(WeekViewUtils.TOTAL_WEEK_PAGES))
        assertTrue(WeekViewUtils.isSettledDayPage(WeekViewUtils.TOTAL_WEEK_PAGES + 1))
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
        // Wednesday Feb 5, 2025. Its week starts Sunday Feb 2, so despite the test name no
        // month boundary is crossed.
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
        // The current year, so no year suffix is shown.
        val currentYear = LocalDate.now().year
        val start = LocalDate.of(currentYear, 1, 6)
        val end = LocalDate.of(currentYear, 1, 8)

        val result = WeekViewUtils.formatCompactRange(start, end)

        assertEquals("Jan 6-8", result)
    }

    @Test
    fun `formatCompactRange cross year shows both years`() {
        val start = LocalDate.of(2024, 12, 30)
        val end = LocalDate.of(2025, 1, 1)

        val result = WeekViewUtils.formatCompactRange(start, end)

        assertEquals("Dec 30, 2024 - Jan 1, 2025", result)
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
        // The day name varies by locale; only its length is checked.
        assertTrue(result.length > 2)
    }

    // ==================== Offset to Time Tests ====================

    @Test
    fun `offsetToTime at top returns midnight`() {
        val (hour, minute) = WeekViewUtils.offsetToTime(0f, 60f, snap = false)
        assertEquals(0, hour)
        assertEquals(0, minute)
    }

    @Test
    fun `offsetToTime with snap rounds to quarter hour`() {
        // 0:17 should snap to 0:15
        val minuteOffset = 17f / 60f * 60f  // 17 minutes into the grid
        val (hour, minute) = WeekViewUtils.offsetToTime(minuteOffset, 60f, snap = true)
        assertEquals(0, hour)
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
        // Jan 5 of the current year; its weekday varies by year and doesn't matter, since the
        // day index is added to whatever date weekStart holds.
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 5)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 0)

        assertEquals("Jan 5", result)
    }

    @Test
    fun `formatIndividualDate returns month day and year for different year`() {
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
        val weekStart = LocalDate.of(currentYear, 1, 5)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 3)  // Jan 5 + 3 days

        assertEquals("Jan 8", result)
    }

    @Test
    fun `formatIndividualDate returns correct date for day index 6`() {
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 5)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 6)  // Jan 5 + 6 days

        assertEquals("Jan 11", result)
    }

    @Test
    fun `formatIndividualDate handles month boundary`() {
        val currentYear = LocalDate.now().year
        val weekStart = LocalDate.of(currentYear, 1, 26)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = WeekViewUtils.formatIndividualDate(weekStart, 6)  // Jan 26 + 6 days = Feb 1

        assertEquals("Feb 1", result)
    }

    // ==================== Week Pager Tests ====================

    @Test
    fun `weekPageToStartDate for CENTER_WEEK_PAGE returns current week start (Sunday)`() {
        val today = LocalDate.now()
        val expectedWeekStart = WeekViewUtils.getWeekStart(today, java.util.Calendar.SUNDAY)
        val result = WeekViewUtils.weekPageToStartDate(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.SUNDAY,
            referenceDate = today
        )
        assertEquals(expectedWeekStart, result)
    }

    @Test
    fun `weekPageToStartDate for CENTER+1 returns next week start`() {
        val today = LocalDate.now()
        val expectedWeekStart = WeekViewUtils.getWeekStart(today, java.util.Calendar.SUNDAY).plusWeeks(1)
        val result = WeekViewUtils.weekPageToStartDate(
            WeekViewUtils.CENTER_WEEK_PAGE + 1,
            java.util.Calendar.SUNDAY,
            referenceDate = today
        )
        assertEquals(expectedWeekStart, result)
    }

    @Test
    fun `weekPageToStartDate for CENTER-1 returns previous week start`() {
        val today = LocalDate.now()
        val expectedWeekStart = WeekViewUtils.getWeekStart(today, java.util.Calendar.SUNDAY).minusWeeks(1)
        val result = WeekViewUtils.weekPageToStartDate(
            WeekViewUtils.CENTER_WEEK_PAGE - 1,
            java.util.Calendar.SUNDAY,
            referenceDate = today
        )
        assertEquals(expectedWeekStart, result)
    }

    @Test
    fun `weekPageToStartDate with Monday first returns Monday`() {
        val ref = LocalDate.of(2026, 3, 11) // Wednesday
        val result = WeekViewUtils.weekPageToStartDate(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.MONDAY,
            referenceDate = ref
        )
        assertEquals(LocalDate.of(2026, 3, 9), result) // Monday Mar 9
    }

    @Test
    fun `weekPageToStartDate with Saturday first returns Saturday`() {
        val ref = LocalDate.of(2026, 3, 11) // Wednesday
        val result = WeekViewUtils.weekPageToStartDate(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.SATURDAY,
            referenceDate = ref
        )
        assertEquals(LocalDate.of(2026, 3, 7), result) // Saturday Mar 7
    }

    @Test
    fun `weekPageToStartDate across year boundary`() {
        val ref = LocalDate.of(2026, 1, 1) // Thursday
        val result = WeekViewUtils.weekPageToStartDate(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.MONDAY,
            referenceDate = ref
        )
        assertEquals(LocalDate.of(2025, 12, 29), result) // Monday Dec 29
    }

    @Test
    fun `dateToWeekPage is inverse of weekPageToStartDate`() {
        val ref = LocalDate.of(2026, 3, 11)
        val firstDayOfWeek = java.util.Calendar.MONDAY

        for (offset in listOf(-10, -1, 0, 1, 10, 52)) {
            val page = WeekViewUtils.CENTER_WEEK_PAGE + offset
            val date = WeekViewUtils.weekPageToStartDate(page, firstDayOfWeek, ref)
            val roundTrip = WeekViewUtils.dateToWeekPage(date, firstDayOfWeek, ref)
            assertEquals("Round trip failed for offset $offset", page, roundTrip)
        }
    }

    @Test
    fun `dateToWeekPage for mid-week date returns same page as week start`() {
        val ref = LocalDate.of(2026, 3, 11) // Wednesday
        val firstDayOfWeek = java.util.Calendar.MONDAY

        val mondayPage = WeekViewUtils.dateToWeekPage(
            LocalDate.of(2026, 3, 9), firstDayOfWeek, ref
        ) // Monday
        val wednesdayPage = WeekViewUtils.dateToWeekPage(
            LocalDate.of(2026, 3, 11), firstDayOfWeek, ref
        ) // Wednesday
        val sundayPage = WeekViewUtils.dateToWeekPage(
            LocalDate.of(2026, 3, 15), firstDayOfWeek, ref
        ) // Sunday

        assertEquals(mondayPage, wednesdayPage)
        assertEquals(mondayPage, sundayPage)
    }

    // ==================== Week Range Formatting Tests ====================

    @Test
    fun `formatWeekRange same month`() {
        val ref = LocalDate.of(2026, 3, 11)
        val result = WeekViewUtils.formatWeekRange(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.MONDAY,
            referenceDate = ref
        )
        // Week of Mar 9-15, 2026 (Monday start)
        assertEquals("Mar 9 - 15, 2026", result)
    }

    @Test
    fun `formatWeekRange cross month`() {
        // Mar 30, 2026 is a Monday, so its Monday-start week is Mar 30 - Apr 5.
        val ref = LocalDate.of(2026, 3, 30)
        val result = WeekViewUtils.formatWeekRange(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.MONDAY,
            referenceDate = ref
        )
        assertEquals("Mar 30 - Apr 5, 2026", result)
    }

    @Test
    fun `formatWeekRange cross year`() {
        // Dec 29, 2025 is a Monday
        val ref = LocalDate.of(2025, 12, 31) // Wednesday
        val result = WeekViewUtils.formatWeekRange(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.MONDAY,
            referenceDate = ref
        )
        assertEquals("Dec 29, 2025 - Jan 4, 2026", result)
    }

    @Test
    fun `formatWeekRange sunday firstDayOfWeek starts on Sunday`() {
        // Mar 11, 2026 is a Wednesday; its Sunday-start week is Mar 8 - Mar 14.
        val ref = LocalDate.of(2026, 3, 11)
        val result = WeekViewUtils.formatWeekRange(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.SUNDAY,
            referenceDate = ref
        )
        assertEquals("Mar 8 - 14, 2026", result)
    }

    @Test
    fun `formatWeekRange different firstDayOfWeek produces different ranges`() {
        // Mar 11 2026 is a Wednesday
        val ref = LocalDate.of(2026, 3, 11)
        val mondayResult = WeekViewUtils.formatWeekRange(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.MONDAY,
            referenceDate = ref
        )
        val sundayResult = WeekViewUtils.formatWeekRange(
            WeekViewUtils.CENTER_WEEK_PAGE,
            java.util.Calendar.SUNDAY,
            referenceDate = ref
        )
        // Monday start: Mar 9-15, Sunday start: Mar 8-14
        assertNotEquals(mondayResult, sundayResult)
        assertEquals("Mar 9 - 15, 2026", mondayResult)
        assertEquals("Mar 8 - 14, 2026", sundayResult)
    }

    // ==================== formatMonthYear Tests ====================

    @Test
    fun `formatMonthYear produces abbreviated month name and year`() {
        val date = LocalDate.of(2026, 4, 15)
        val result = WeekViewUtils.formatMonthYear(date)
        assertTrue("Should match abbrev-month yyyy format, got: $result", result.matches(Regex("\\w+ \\d{4}")))
        assertFalse("Should not contain week number", result.contains("(W"))
        assertTrue("Should contain abbreviated month Apr for en-US, got: $result", result.contains("Apr"))
        assertFalse("Should not contain full month name April, got: $result", result.contains("April"))
    }

    // ==================== formatWeekLabel Tests ====================

    @Test
    fun `formatWeekLabel concatenates prefix and week number with no separator`() {
        val date = LocalDate.of(2026, 4, 15) // Wednesday, April 15, 2026
        val result = WeekViewUtils.formatWeekLabel(date, java.util.Calendar.MONDAY, prefix = "W")
        assertTrue("Should match 'W##' format with no space, got: $result", result.matches(Regex("W\\d+")))
    }

    @Test
    fun `formatWeekLabel respects prefix arg for localization`() {
        val date = LocalDate.of(2026, 4, 15)
        val result = WeekViewUtils.formatWeekLabel(date, java.util.Calendar.MONDAY, prefix = "Woche")
        assertTrue("Should use de-DE prefix without separator, got: $result", result.startsWith("Woche"))
        assertFalse("Should not insert space after prefix, got: $result", result.startsWith("Woche "))
    }

    @Test
    fun `formatWeekLabel week number is locale-aware not ISO-fixed`() {
        // Jan 1, 2026 is a Thursday, where a Sunday-start and a Monday-start week can number
        // differently. Both take minimalDaysInFirstWeek from the default locale; only the
        // first day differs. The test asserts only the W## shape of each, not their numbers.
        val date = LocalDate.of(2026, 1, 1)
        val sundayResult = WeekViewUtils.formatWeekLabel(date, java.util.Calendar.SUNDAY, prefix = "W")
        val mondayResult = WeekViewUtils.formatWeekLabel(date, java.util.Calendar.MONDAY, prefix = "W")
        assertTrue("Sunday-start should match 'W##', got: $sundayResult", sundayResult.matches(Regex("W\\d+")))
        assertTrue("Monday-start should match 'W##', got: $mondayResult", mondayResult.matches(Regex("W\\d+")))
    }

    // ==================== offsetToTime with startHour Tests ====================

    @Test
    fun `offsetToTime with startHour 0 at top returns midnight`() {
        val (hour, minute) = WeekViewUtils.offsetToTime(0f, 60f, snap = false, startHour = 0)
        assertEquals(0, hour)
        assertEquals(0, minute)
    }

    @Test
    fun `offsetToTime with explicit startHour 6 at top returns 6am`() {
        val (hour, minute) = WeekViewUtils.offsetToTime(0f, 60f, snap = false, startHour = 6)
        assertEquals(6, hour)
        assertEquals(0, minute)
    }

    @Test
    fun `offsetToTime with startHour 0 at 60px returns 1am`() {
        val (hour, minute) = WeekViewUtils.offsetToTime(60f, 60f, snap = false, startHour = 0)
        assertEquals(1, hour)
        assertEquals(0, minute)
    }

    // ==================== 24-Hour Format Tests ====================

    @Test
    fun `formatTimeRange with 24h pattern shows 24h format`() {
        // 14:00 - 15:30 UTC. formatTimeRange formats in the system zone, so the "14:00" and
        // "15:30" checks hold only when the JVM default zone is at UTC+0 on that date.
        val startTs = 1767657600000L + (14 * 60 * 60 * 1000)  // Jan 6, 2026 14:00 UTC
        val endTs = startTs + (90 * 60 * 1000)  // +90 minutes

        val result = WeekViewUtils.formatTimeRange(startTs, endTs, "HH:mm")

        assertTrue("Expected 24h format with 14:00, got: $result", result.contains("14:00"))
        assertTrue("Expected 24h format with 15:30, got: $result", result.contains("15:30"))
    }

    @Test
    fun `formatTimeRange with 12h pattern shows 12h format`() {
        // The same times with a 12h pattern; also depends on a UTC+0 default zone.
        val startTs = 1767657600000L + (14 * 60 * 60 * 1000)
        val endTs = startTs + (90 * 60 * 1000)

        val result = WeekViewUtils.formatTimeRange(startTs, endTs, "h:mma")

        assertTrue("Expected 12h format with 2:00, got: $result", result.contains("2:00"))
        assertTrue("Expected 12h format with pm, got: $result", result.lowercase().contains("pm"))
    }

    // ==================== resolveInitialScrollPx Tests (issue #188) ====================

    @Test
    fun `resolveInitialScrollPx returns savedPosition unchanged when positive`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 1234,
            hourHeightDp = 60f,
            density = 1.0f
        )
        assertEquals(1234, result)
    }

    @Test
    fun `resolveInitialScrollPx defaults to 6 AM at density 1x with 60dp hours`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 1.0f
        )
        assertEquals(360, result)  // 6 * 60 * 1.0
    }

    @Test
    fun `resolveInitialScrollPx scales with density 2x`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 2.0f
        )
        assertEquals(720, result)  // 6 * 60 * 2.0
    }

    @Test
    fun `resolveInitialScrollPx honors min zoom hour height of 30dp`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = WeekViewUtils.MIN_HOUR_HEIGHT_DP,
            density = 1.0f
        )
        assertEquals(180, result)  // 6 * 30 * 1.0
    }

    @Test
    fun `resolveInitialScrollPx honors max zoom hour height of 150dp`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = WeekViewUtils.MAX_HOUR_HEIGHT_DP,
            density = 1.0f
        )
        assertEquals(900, result)  // 6 * 150 * 1.0
    }

    @Test
    fun `resolveInitialScrollPx applies custom defaultHour override`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 1.0f,
            defaultHour = 8
        )
        assertEquals(480, result)  // 8 * 60 * 1.0
    }

    @Test
    fun `resolveInitialScrollPx handles fractional density for xxhdpi devices`() {
        // Many devices have a non-integer density (420 dpi is 2.625). 6 * 60 * 2.625 is
        // exactly 945, so this input doesn't exercise the toInt() truncation.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 2.625f
        )
        assertEquals(945, result)  // (6 * 60 * 2.625).toInt() == 945
    }

    @Test
    fun `resolveInitialScrollPx with defaultHour 0 returns 0`() {
        // Explicit opt-out path: a future caller can pass 0 to land at midnight.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 1.0f,
            defaultHour = 0
        )
        assertEquals(0, result)
    }

    @Test
    fun `resolveInitialScrollPx treats negative savedPosition as no-saved-value`() {
        // Pins the `savedPosition > 0` gate: a non-positive value takes the default-hour
        // branch. Scroll state never emits a negative value.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = -1,
            hourHeightDp = 60f,
            density = 1.0f
        )
        assertEquals(360, result)  // falls through to 6 AM default
    }

    // ==================== resolveVisibleStartHour Tests (issue #188) ====================

    @Test
    fun `resolveVisibleStartHour returns default hour when savedPosition is 0`() {
        // With no in-session scroll it returns DEFAULT_SCROLL_START_HOUR, the hour the grid
        // lands on when no scroll time is persisted either.
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 0,
            hourHeightPx = 60f
        )
        assertEquals(6, result)
    }

    @Test
    fun `resolveVisibleStartHour converts saved pixels to hour`() {
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 360,
            hourHeightPx = 60f,
            gridStartHour = 0
        )
        assertEquals(6, result)  // 360 / 60 + 0 = 6
    }

    @Test
    fun `resolveVisibleStartHour converts larger saved pixels to hour`() {
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 720,
            hourHeightPx = 60f,
            gridStartHour = 0
        )
        assertEquals(12, result)  // 720 / 60 + 0 = 12
    }

    @Test
    fun `resolveVisibleStartHour clamps computed hour to upper bound 23`() {
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 1440,
            hourHeightPx = 60f,
            gridStartHour = 0
        )
        assertEquals(23, result)  // 24 would be out of range, clamped to 23
    }

    @Test
    fun `resolveVisibleStartHour treats negative savedPosition as default`() {
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = -1,
            hourHeightPx = 60f
        )
        assertEquals(6, result)  // falls through to default
    }

    @Test
    fun `resolveVisibleStartHour honors custom gridStartHour offset`() {
        // If the grid ever starts past midnight (e.g. 6 AM grid with 60 px/hr),
        // scrolling 60 px means the visible top is hour 7.
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 60,
            hourHeightPx = 60f,
            gridStartHour = 6
        )
        assertEquals(7, result)  // 60 / 60 + 6 = 7
    }

    @Test
    fun `resolveVisibleStartHour handles fractional hourHeightPx from xxhdpi density`() {
        // 60 dp at density 2.625 (420 dpi) is 157.5 px/hr. At saved pixel 945, that's hour 6.
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 945,
            hourHeightPx = 157.5f,
            gridStartHour = 0
        )
        assertEquals(6, result)  // (945 / 157.5).toInt() + 0 = 6
    }

    @Test
    fun `resolveVisibleStartHour applies custom defaultHour override`() {
        val result = WeekViewUtils.resolveVisibleStartHour(
            savedPosition = 0,
            hourHeightPx = 60f,
            defaultHour = 8
        )
        assertEquals(8, result)
    }

    // ==================== Minutes <-> Pixels conversion (scroll restore) ====================

    @Test
    fun `pixelsToMinutesOfDay converts scroll offset to clock minutes`() {
        // 840 px at 60 px/hr = 14 hours = 14:00 = 840 minutes
        assertEquals(840, WeekViewUtils.pixelsToMinutesOfDay(840f, 60f))
    }

    @Test
    fun `pixelsToMinutesOfDay is independent of hour height`() {
        // The same clock time (12:00) at two zoom levels maps to the same minutes, though the
        // pixel offsets differ, so a restored scroll doesn't drift after a zoom change.
        val atNormalZoom = WeekViewUtils.pixelsToMinutesOfDay(720f, 60f)   // 12h * 60px
        val atMaxZoom = WeekViewUtils.pixelsToMinutesOfDay(1800f, 150f)    // 12h * 150px
        assertEquals(720, atNormalZoom)
        assertEquals(720, atMaxZoom)
    }

    @Test
    fun `pixelsToMinutesOfDay clamps to end of day`() {
        assertEquals(1439, WeekViewUtils.pixelsToMinutesOfDay(999_999f, 60f))
    }

    @Test
    fun `pixelsToMinutesOfDay clamps negative to start of day`() {
        assertEquals(0, WeekViewUtils.pixelsToMinutesOfDay(-50f, 60f))
    }

    @Test
    fun `pixelsToMinutesOfDay guards against non-positive hour height`() {
        // Degenerate hour height must not divide-by-zero or throw.
        assertEquals(0, WeekViewUtils.pixelsToMinutesOfDay(500f, 0f))
    }

    @Test
    fun `minutesOfDayToPixels converts clock minutes to scroll offset`() {
        // 14:00 (840 min) at 60 px/hr = 840 px
        assertEquals(840, WeekViewUtils.minutesOfDayToPixels(840, 60f))
    }

    @Test
    fun `minutesOfDayToPixels scales with hour height`() {
        // The same clock time at a different zoom gives different pixels.
        assertEquals(720, WeekViewUtils.minutesOfDayToPixels(720, 60f))
        assertEquals(1800, WeekViewUtils.minutesOfDayToPixels(720, 150f))
    }

    @Test
    fun `minutes and pixels round-trip within one minute at a given zoom`() {
        // A round trip through integer pixels loses under a minute: at 157.5 px/hr one minute
        // is about 2.6 px and both conversions truncate. The contract is a 1 minute tolerance.
        val hourHeightPx = 157.5f  // 60dp at density 2.625
        val originalMinutes = 555  // 09:15
        val px = WeekViewUtils.minutesOfDayToPixels(originalMinutes, hourHeightPx)
        val backToMinutes = WeekViewUtils.pixelsToMinutesOfDay(px.toFloat(), hourHeightPx)
        assertTrue(
            "round-trip $originalMinutes -> $px px -> $backToMinutes min drifted more than 1 minute",
            kotlin.math.abs(originalMinutes - backToMinutes) <= 1
        )
    }

    // ==================== resolveZoomScrollPx (pinch-zoom recentring) ====================

    @Test
    fun `resolveZoomScrollPx keeps the viewport-center clock time fixed when zooming in`() {
        // Density 2.625; 1000px viewport; grid centered on 12:00 before the zoom.
        val density = 2.625f
        val viewportHeightPx = 1000f
        val oldPx = 60f * density   // 157.5 px/hr
        val newPx = 120f * density  // 315 px/hr, a zoom-in
        val startScroll = 12f * oldPx - viewportHeightPx / 2f  // center = 12:00

        val result = WeekViewUtils.resolveZoomScrollPx(
            currentScrollPx = startScroll,
            viewportHeightPx = viewportHeightPx,
            oldHourHeightPx = oldPx,
            newHourHeightPx = newPx,
            totalHours = WeekViewUtils.TOTAL_HOURS
        )

        // The clock time under the viewport center must still be 12:00 after the zoom.
        val centerTimeHours = (result + viewportHeightPx / 2f) / newPx
        assertEquals(
            "zoom-in shifted the viewport-center clock time away from 12:00",
            12f, centerTimeHours, 0.02f
        )
    }

    @Test
    fun `resolveZoomScrollPx keeps the viewport-center clock time fixed when zooming out`() {
        val density = 2.625f
        val viewportHeightPx = 1000f
        val oldPx = 120f * density
        val newPx = 60f * density  // zoom-out
        val startScroll = 12f * oldPx - viewportHeightPx / 2f

        val result = WeekViewUtils.resolveZoomScrollPx(
            currentScrollPx = startScroll,
            viewportHeightPx = viewportHeightPx,
            oldHourHeightPx = oldPx,
            newHourHeightPx = newPx,
            totalHours = WeekViewUtils.TOTAL_HOURS
        )

        val centerTimeHours = (result + viewportHeightPx / 2f) / newPx
        assertEquals(12f, centerTimeHours, 0.02f)
    }

    @Test
    fun `resolveZoomScrollPx stays within the post-zoom range even with an upward pan`() {
        // Zoom in from the very bottom of the grid while the centroid drifts upward
        // (pan.y < 0). The folded target must not exceed the post-zoom scrollable max, or the
        // caller's wait for the grid to grow to this offset runs to its timeout before the
        // scroll.
        val density = 2.625f
        val viewportHeightPx = 1000f
        val oldPx = 60f * density   // 157.5
        val newPx = 120f * density  // 315
        val oldMaxScroll = oldPx * WeekViewUtils.TOTAL_HOURS - viewportHeightPx  // 2780
        val newMaxScroll = newPx * WeekViewUtils.TOTAL_HOURS - viewportHeightPx  // 6560

        val result = WeekViewUtils.resolveZoomScrollPx(
            currentScrollPx = oldMaxScroll,   // scrolled to the end of the day
            viewportHeightPx = viewportHeightPx,
            oldHourHeightPx = oldPx,
            newHourHeightPx = newPx,
            totalHours = WeekViewUtils.TOTAL_HOURS,
            panYPx = -500f                    // upward centroid drift
        )

        assertTrue(
            "target $result exceeded the post-zoom max $newMaxScroll",
            result <= newMaxScroll + 0.001f
        )
        assertTrue("target $result went negative", result >= 0f)
    }

    @Test
    fun `resolveZoomScrollPx folds pan into the recentred offset`() {
        val density = 2.625f
        val viewportHeightPx = 1000f
        val oldPx = 60f * density
        val newPx = 60f * density  // no zoom scale; isolate the pan contribution
        val startScroll = 12f * oldPx - viewportHeightPx / 2f

        val noPan = WeekViewUtils.resolveZoomScrollPx(
            currentScrollPx = startScroll, viewportHeightPx = viewportHeightPx,
            oldHourHeightPx = oldPx, newHourHeightPx = newPx, panYPx = 0f
        )
        val downPan = WeekViewUtils.resolveZoomScrollPx(
            currentScrollPx = startScroll, viewportHeightPx = viewportHeightPx,
            oldHourHeightPx = oldPx, newHourHeightPx = newPx, panYPx = 100f
        )
        // A downward pan (positive panYPx) lowers the scroll offset by the same px, so the
        // content follows the finger down.
        assertEquals(noPan - 100f, downPan, 0.001f)
    }

    @Test
    fun `resolveZoomScrollPx guards against non-positive old hour height`() {
        val result = WeekViewUtils.resolveZoomScrollPx(
            currentScrollPx = 500f,
            viewportHeightPx = 1000f,
            oldHourHeightPx = 0f,
            newHourHeightPx = 157.5f
        )
        assertEquals(500f, result, 0.001f)
    }

    // ==================== resolveInitialScrollPx savedMinutes branch ====================

    @Test
    fun `resolveInitialScrollPx restores savedMinutes on cold launch`() {
        // Cold launch: no in-session scroll (savedPosition 0) but a persisted clock time, so
        // the grid lands at that time, not the 6 AM default.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 1.0f,
            savedMinutes = 840  // 14:00
        )
        assertEquals(840, result)  // 840 min at 60 px/hr, density 1x
    }

    @Test
    fun `resolveInitialScrollPx restored minutes scale with density and hour height`() {
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 2.0f,
            savedMinutes = 720  // 12:00
        )
        assertEquals(1440, result)  // 720 min -> 12h * 60dp * 2.0 density
    }

    @Test
    fun `resolveInitialScrollPx prefers in-session pixels over savedMinutes`() {
        // A scroll made this session wins over the persisted cold-launch value.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 1234,
            hourHeightDp = 60f,
            density = 1.0f,
            savedMinutes = 840
        )
        assertEquals(1234, result)
    }

    @Test
    fun `resolveInitialScrollPx falls back to default hour when no saved minutes`() {
        // The savedMinutes sentinel (-1) and no in-session scroll give the 6 AM default, as on
        // a fresh install.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 1.0f,
            savedMinutes = -1
        )
        assertEquals(360, result)  // 6 AM default
    }

    @Test
    fun `resolveInitialScrollPx defaults savedMinutes to sentinel keeping legacy behavior`() {
        // A caller that doesn't pass savedMinutes gets the default hour.
        val result = WeekViewUtils.resolveInitialScrollPx(
            savedPosition = 0,
            hourHeightDp = 60f,
            density = 1.0f
        )
        assertEquals(360, result)
    }

    // ==================== All-Day Row Expand/Collapse Tests ====================

    @Test
    fun `anyAllDayColumnHasOverflowWhenCollapsed true only when a column exceeds one`() {
        // Nothing to expand: empty, or every column at most one event.
        assertFalse(WeekViewUtils.anyAllDayColumnHasOverflowWhenCollapsed(emptyList()))
        assertFalse(WeekViewUtils.anyAllDayColumnHasOverflowWhenCollapsed(listOf(0, 1, 1)))
        // At least one column with 2 or more events gives the toggle something to do.
        assertTrue(WeekViewUtils.anyAllDayColumnHasOverflowWhenCollapsed(listOf(1, 2, 0)))
        assertTrue(WeekViewUtils.anyAllDayColumnHasOverflowWhenCollapsed(listOf(5)))
    }
}
