package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests the ranges [DateFilter.getTimeRange] returns in New York: [DateFilter.ThisWeek] and
 * [DateFilter.ThisMonth] from today's date, [DateFilter.SingleDay] and [DateFilter.CustomRange]
 * from picked days, a Tokyo and New York comparison, and the first-day-of-week option of
 * [DateFilter.ThisWeek].
 */
@RunWith(RobolectricTestRunner::class)
class DateFilterTest {

    private val testZone = ZoneId.of("America/New_York")

    // ==================== ThisWeek Tests ====================

    @Test
    fun `ThisWeek starts on Sunday`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone)
        val today = LocalDate.now(testZone)
        val daysSinceSunday = today.dayOfWeek.value % 7
        val sunday = today.minusDays(daysSinceSunday.toLong())
        val expectedStart = sunday.atStartOfDay(testZone).toInstant().toEpochMilli()

        assertEquals(expectedStart, range.first)
    }

    @Test
    fun `ThisWeek spans 7 days`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone)
        // Compare dates so a DST change inside the week doesn't matter.
        val startDate = Instant.ofEpochMilli(range.first).atZone(testZone).toLocalDate()
        val endDate = Instant.ofEpochMilli(range.second).atZone(testZone).toLocalDate()
        assertEquals("Week should span 7 days", startDate.plusDays(6), endDate)
    }

    // ==================== ThisMonth Tests ====================

    @Test
    fun `ThisMonth starts on first day`() {
        val range = DateFilter.ThisMonth.getTimeRange(testZone)
        val today = LocalDate.now(testZone)
        val firstOfMonth = today.withDayOfMonth(1)
        val expectedStart = firstOfMonth.atStartOfDay(testZone).toInstant().toEpochMilli()

        assertEquals(expectedStart, range.first)
    }

    @Test
    fun `ThisMonth ends on last day`() {
        val range = DateFilter.ThisMonth.getTimeRange(testZone)
        val today = LocalDate.now(testZone)
        val lastOfMonth = today.withDayOfMonth(today.lengthOfMonth())
        val expectedEnd = lastOfMonth.plusDays(1).atStartOfDay(testZone).toInstant().toEpochMilli() - 1

        assertEquals(expectedEnd, range.second)
    }

    // ==================== SingleDay Tests ====================

    @Test
    fun `SingleDay returns single day range`() {
        val dateMs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val filter = DateFilter.SingleDay(dateMs)
        val range = filter.getTimeRange(testZone)

        // One day minus 1 ms (Dec 31 in New York).
        val duration = range.second - range.first
        assertTrue("Duration should be ~24 hours", duration in 86399000..86400000)
    }

    // ==================== CustomRange Tests ====================

    @Test
    fun `CustomRange returns correct range`() {
        val startMs = 1704067200000L // Jan 1, 2024
        val endMs = 1704326400000L   // Jan 4, 2024
        val filter = DateFilter.CustomRange(startMs, endMs)
        val range = filter.getTimeRange(testZone)

        // In New York the two instants fall on Dec 31 and Jan 3; the range covers both whole days.
        assertTrue("Range start should be <= startMs", range.first <= startMs)
        assertTrue("Range end should be >= endMs", range.second >= endMs)
    }

    @Test
    fun `CustomRange handles same day as single day`() {
        val dateMs = 1704067200000L
        val filter = DateFilter.CustomRange(dateMs, dateMs)
        val range = filter.getTimeRange(testZone)

        // One day minus 1 ms.
        val duration = range.second - range.first
        assertTrue("Same start/end should be single day", duration in 86399000..86400000)
    }

    // ==================== Timezone Tests ====================

    @Test
    fun `different timezones produce different ranges`() {
        val nyZone = ZoneId.of("America/New_York")
        val tokyoZone = ZoneId.of("Asia/Tokyo")

        val filter = DateFilter.SingleDay(1704067200000L) // Jan 1, 2024 00:00 UTC
        val nyRange = filter.getTimeRange(nyZone)
        val tokyoRange = filter.getTimeRange(tokyoZone)

        // The instant falls on Dec 31 in New York and Jan 1 in Tokyo, and each day starts at
        // its own zone's midnight.
        assertTrue("Different timezones should have different start times", nyRange.first != tokyoRange.first)
    }

    // ==================== First Day of Week Tests ====================

    @Test
    fun `ThisWeek with monday first starts on monday`() {
        val range = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.MONDAY)
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
        val range = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.SATURDAY)
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
        val range = DateFilter.ThisWeek.getTimeRange(testZone, java.util.Calendar.SUNDAY)
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
    fun `ThisWeek spans 7 days regardless of first day`() {
        // A week with a DST change is 167 or 169 hours, so compare dates, not durations.
        fun verifySevenDaySpan(firstDay: Int, label: String) {
            val range = DateFilter.ThisWeek.getTimeRange(testZone, firstDay)
            val startDate = java.time.Instant.ofEpochMilli(range.first)
                .atZone(testZone).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(range.second)
                .atZone(testZone).toLocalDate()
            // The end is inclusive (23:59:59.999), so it falls on the sixth day after the start.
            assertEquals("$label week should span 7 days", startDate.plusDays(6), endDate)
        }

        verifySevenDaySpan(java.util.Calendar.SUNDAY, "Sunday-first")
        verifySevenDaySpan(java.util.Calendar.MONDAY, "Monday-first")
        verifySevenDaySpan(java.util.Calendar.SATURDAY, "Saturday-first")
    }
}
