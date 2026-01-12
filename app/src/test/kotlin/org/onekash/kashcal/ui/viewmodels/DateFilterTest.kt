package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for DateFilter sealed class.
 * Tests time range computation for all filter types.
 */
class DateFilterTest {

    private val testZone = ZoneId.of("America/New_York")

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
        val duration = range.second - range.first
        // Should be ~24 hours minus 1ms
        assertTrue("Duration should be ~24 hours", duration in 86399000..86400000)
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
        val duration = range.second - range.first
        // Should be ~7 days minus 1ms
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        assertTrue("Duration should be ~7 days", duration in (sevenDaysMs - 1000)..(sevenDaysMs))
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
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L

        assertEquals(thisWeekRange.first + sevenDaysMs, nextWeekRange.first)
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
}
