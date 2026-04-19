package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for DateFilter sealed class.
 * Tests time range computation for all filter types.
 */
@RunWith(RobolectricTestRunner::class)
class DateFilterTest {

    private val testZone = ZoneId.of("America/New_York")

    // ==================== Upcoming Tests ====================

    @Test
    fun `Upcoming returns null time range`() {
        assertNull(DateFilter.Upcoming.getTimeRange(testZone))
    }

    @Test
    fun `Upcoming has correct display name`() {
        assertEquals("Upcoming", DateFilter.Upcoming.displayName)
    }

    @Test
    fun `Upcoming is not in presets`() {
        assertTrue("Upcoming should not be a selectable preset chip", !DateFilter.presets.contains(DateFilter.Upcoming))
    }

    // ==================== AnyTime Tests ====================

    @Test
    fun `AnyTime returns null time range`() {
        assertNull(DateFilter.AnyTime.getTimeRange(testZone))
    }

    @Test
    fun `AnyTime has correct display name`() {
        assertEquals("Any time", DateFilter.AnyTime.displayName)
    }

    // ==================== Today Tests ====================

    @Test
    fun `Today returns correct range`() {
        val range = DateFilter.Today.getTimeRange(testZone)!!
        val today = LocalDate.now(testZone)
        val expectedStart = today.atStartOfDay(testZone).toInstant().toEpochMilli()
        val expectedEnd = today.plusDays(1).atStartOfDay(testZone).toInstant().toEpochMilli() - 1

        assertEquals(expectedStart, range.first)
        assertEquals(expectedEnd, range.second)
    }

    @Test
    fun `Today range spans exactly one day`() {
        val range = DateFilter.Today.getTimeRange(testZone)!!
        // Use date comparison to handle DST transitions (spring-forward = 23h, fall-back = 25h)
        val startDate = Instant.ofEpochMilli(range.first).atZone(testZone).toLocalDate()
        val endDate = Instant.ofEpochMilli(range.second).atZone(testZone).toLocalDate()
        assertEquals("Today should span exactly 1 day", startDate, endDate)
    }

    // ==================== Tomorrow Tests ====================

    @Test
    fun `Tomorrow returns correct range`() {
        val range = DateFilter.Tomorrow.getTimeRange(testZone)!!
        val tomorrow = LocalDate.now(testZone).plusDays(1)
        val expectedStart = tomorrow.atStartOfDay(testZone).toInstant().toEpochMilli()

        assertEquals(expectedStart, range.first)
    }

    @Test
    fun `Tomorrow is after Today`() {
        val todayRange = DateFilter.Today.getTimeRange(testZone)!!
        val tomorrowRange = DateFilter.Tomorrow.getTimeRange(testZone)!!

        assertTrue("Tomorrow should start after today ends", tomorrowRange.first > todayRange.second)
    }

    // ==================== ThisWeek Tests ====================

    @Test
    fun `ThisWeek starts on Sunday`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone)!!
        val today = LocalDate.now(testZone)
        val daysSinceSunday = today.dayOfWeek.value % 7
        val sunday = today.minusDays(daysSinceSunday.toLong())
        val expectedStart = sunday.atStartOfDay(testZone).toInstant().toEpochMilli()

        assertEquals(expectedStart, range.first)
    }

    @Test
    fun `ThisWeek spans 7 days`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone)!!
        // Use date comparison to handle DST transitions
        val startDate = Instant.ofEpochMilli(range.first).atZone(testZone).toLocalDate()
        val endDate = Instant.ofEpochMilli(range.second).atZone(testZone).toLocalDate()
        assertEquals("Week should span 7 days", startDate.plusDays(6), endDate)
    }

    // ==================== NextWeek Tests ====================

    @Test
    fun `NextWeek is after ThisWeek`() {
        val thisWeekRange = DateFilter.ThisWeek.getTimeRange(testZone)!!
        val nextWeekRange = DateFilter.NextWeek.getTimeRange(testZone)!!

        assertTrue("NextWeek should start after ThisWeek ends", nextWeekRange.first > thisWeekRange.second)
    }

    @Test
    fun `NextWeek starts exactly 7 days after ThisWeek start`() {
        val thisWeekRange = DateFilter.ThisWeek.getTimeRange(testZone)!!
        val nextWeekRange = DateFilter.NextWeek.getTimeRange(testZone)!!
        // Use date comparison to handle DST transitions
        val thisWeekStart = Instant.ofEpochMilli(thisWeekRange.first).atZone(testZone).toLocalDate()
        val nextWeekStart = Instant.ofEpochMilli(nextWeekRange.first).atZone(testZone).toLocalDate()

        assertEquals(thisWeekStart.plusDays(7), nextWeekStart)
    }

    // ==================== ThisMonth Tests ====================

    @Test
    fun `ThisMonth starts on first day`() {
        val range = DateFilter.ThisMonth.getTimeRange(testZone)!!
        val today = LocalDate.now(testZone)
        val firstOfMonth = today.withDayOfMonth(1)
        val expectedStart = firstOfMonth.atStartOfDay(testZone).toInstant().toEpochMilli()

        assertEquals(expectedStart, range.first)
    }

    @Test
    fun `ThisMonth ends on last day`() {
        val range = DateFilter.ThisMonth.getTimeRange(testZone)!!
        val today = LocalDate.now(testZone)
        val lastOfMonth = today.withDayOfMonth(today.lengthOfMonth())
        val expectedEnd = lastOfMonth.plusDays(1).atStartOfDay(testZone).toInstant().toEpochMilli() - 1

        assertEquals(expectedEnd, range.second)
    }

    // ==================== NextMonth Tests ====================

    @Test
    fun `NextMonth is after ThisMonth`() {
        val thisMonthRange = DateFilter.ThisMonth.getTimeRange(testZone)!!
        val nextMonthRange = DateFilter.NextMonth.getTimeRange(testZone)!!

        assertTrue("NextMonth should start after ThisMonth ends", nextMonthRange.first > thisMonthRange.second)
    }

    // ==================== SingleDay Tests ====================

    @Test
    fun `SingleDay returns single day range`() {
        val dateMs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val filter = DateFilter.SingleDay(dateMs)
        val range = filter.getTimeRange(testZone)!!

        // Range should span exactly one day minus 1ms
        val duration = range.second - range.first
        assertTrue("Duration should be ~24 hours", duration in 86399000..86400000)
    }

    @Test
    fun `SingleDay has formatted display name`() {
        val dateMs = 1704067200000L // Jan 1, 2024 UTC
        val filter = DateFilter.SingleDay(dateMs)

        // Display name should be like "Jan 1"
        assertTrue("Display name should contain month", filter.displayName.contains("Jan"))
    }

    // ==================== CustomRange Tests ====================

    @Test
    fun `CustomRange returns correct range`() {
        val startMs = 1704067200000L // Jan 1, 2024
        val endMs = 1704326400000L   // Jan 4, 2024
        val filter = DateFilter.CustomRange(startMs, endMs)
        val range = filter.getTimeRange(testZone)!!

        // Start should be beginning of Jan 1
        // End should be end of Jan 4
        assertTrue("Range start should be <= startMs", range.first <= startMs)
        assertTrue("Range end should be >= endMs", range.second >= endMs)
    }

    @Test
    fun `CustomRange has formatted display name`() {
        val startMs = 1704067200000L // Jan 1, 2024
        val endMs = 1704326400000L   // Jan 4, 2024
        val filter = DateFilter.CustomRange(startMs, endMs)

        // Display name should be like "Jan 1 - Jan 4"
        assertTrue("Display name should contain dash", filter.displayName.contains("-"))
    }

    @Test
    fun `CustomRange handles same day as single day`() {
        val dateMs = 1704067200000L
        val filter = DateFilter.CustomRange(dateMs, dateMs)
        val range = filter.getTimeRange(testZone)!!

        // Should span exactly one day
        val duration = range.second - range.first
        assertTrue("Same start/end should be single day", duration in 86399000..86400000)
    }

    // ==================== Presets Tests ====================

    @Test
    fun `presets contains expected filters`() {
        assertEquals(6, DateFilter.presets.size)
        assertTrue(DateFilter.presets.contains(DateFilter.AnyTime))
        assertTrue(DateFilter.presets.contains(DateFilter.Today))
        assertTrue(DateFilter.presets.contains(DateFilter.Tomorrow))
        assertTrue(DateFilter.presets.contains(DateFilter.ThisWeek))
        assertTrue(DateFilter.presets.contains(DateFilter.NextWeek))
        assertTrue(DateFilter.presets.contains(DateFilter.ThisMonth))
    }

    @Test
    fun `all preset filters have non-null display names`() {
        DateFilter.presets.forEach { filter ->
            assertNotNull("${filter::class.simpleName} should have display name", filter.displayName)
            assertTrue("${filter::class.simpleName} display name should not be empty", filter.displayName.isNotEmpty())
        }
    }

    // ==================== Timezone Tests ====================

    @Test
    fun `different timezones produce different ranges`() {
        val nyZone = ZoneId.of("America/New_York")
        val tokyoZone = ZoneId.of("Asia/Tokyo")

        val nyRange = DateFilter.Today.getTimeRange(nyZone)!!
        val tokyoRange = DateFilter.Today.getTimeRange(tokyoZone)!!

        // Tokyo is ahead of NY, so their "today" starts at different UTC times
        assertTrue("Different timezones should have different start times", nyRange.first != tokyoRange.first)
    }

    // ==================== First Day of Week Tests ====================

    @Test
    fun `ThisWeek with monday first starts on monday`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.MONDAY)!!
        val weekStartDate = java.time.Instant.ofEpochMilli(range.first)
            .atZone(testZone)
            .toLocalDate()

        assertEquals(
            "ThisWeek with Monday-first should start on Monday",
            java.time.DayOfWeek.MONDAY,
            weekStartDate.dayOfWeek
        )
    }

    @Test
    fun `ThisWeek with saturday first starts on saturday`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.SATURDAY)!!
        val weekStartDate = java.time.Instant.ofEpochMilli(range.first)
            .atZone(testZone)
            .toLocalDate()

        assertEquals(
            "ThisWeek with Saturday-first should start on Saturday",
            java.time.DayOfWeek.SATURDAY,
            weekStartDate.dayOfWeek
        )
    }

    @Test
    fun `ThisWeek with sunday first starts on sunday`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.SUNDAY)!!
        val weekStartDate = java.time.Instant.ofEpochMilli(range.first)
            .atZone(testZone)
            .toLocalDate()

        assertEquals(
            "ThisWeek with Sunday-first should start on Sunday",
            java.time.DayOfWeek.SUNDAY,
            weekStartDate.dayOfWeek
        )
    }

    @Test
    fun `NextWeek with monday first starts on monday`() {
        val range = DateFilter.NextWeek.getTimeRange(testZone, java.util.Calendar.MONDAY)!!
        val weekStartDate = java.time.Instant.ofEpochMilli(range.first)
            .atZone(testZone)
            .toLocalDate()

        assertEquals(
            "NextWeek with Monday-first should start on Monday",
            java.time.DayOfWeek.MONDAY,
            weekStartDate.dayOfWeek
        )
    }

    @Test
    fun `NextWeek with saturday first starts on saturday`() {
        val range = DateFilter.NextWeek.getTimeRange(testZone, java.util.Calendar.SATURDAY)!!
        val weekStartDate = java.time.Instant.ofEpochMilli(range.first)
            .atZone(testZone)
            .toLocalDate()

        assertEquals(
            "NextWeek with Saturday-first should start on Saturday",
            java.time.DayOfWeek.SATURDAY,
            weekStartDate.dayOfWeek
        )
    }

    @Test
    fun `NextWeek with monday first starts 7 days after ThisWeek with monday first`() {
        val thisWeekRange = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.MONDAY)!!
        val nextWeekRange = DateFilter.NextWeek.getTimeRange(testZone, java.util.Calendar.MONDAY)!!

        // Convert to LocalDate to compare dates (avoids DST millisecond issues)
        val thisWeekStart = java.time.Instant.ofEpochMilli(thisWeekRange.first)
            .atZone(testZone).toLocalDate()
        val nextWeekStart = java.time.Instant.ofEpochMilli(nextWeekRange.first)
            .atZone(testZone).toLocalDate()

        assertEquals(thisWeekStart.plusDays(7), nextWeekStart)
    }

    @Test
    fun `ThisWeek spans 7 days regardless of first day`() {
        // During DST transitions, 7 calendar days may be 167 or 169 hours in milliseconds
        // Use date comparison instead of millisecond duration
        fun verifySevenDaySpan(firstDay: Int, label: String) {
            val range = DateFilter.ThisWeek.getTimeRange(testZone, firstDay)!!
            val startDate = java.time.Instant.ofEpochMilli(range.first)
                .atZone(testZone).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(range.second)
                .atZone(testZone).toLocalDate()
            // End is inclusive (23:59:59.999), so endDate should be startDate + 6 days
            assertEquals("$label week should span 7 days", startDate.plusDays(6), endDate)
        }

        verifySevenDaySpan(java.util.Calendar.SUNDAY, "Sunday-first")
        verifySevenDaySpan(java.util.Calendar.MONDAY, "Monday-first")
        verifySevenDaySpan(java.util.Calendar.SATURDAY, "Saturday-first")
    }
}
