package org.onekash.kashcal.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for Occurrence entity.
 *
 * Tests the toDayFormat helper and default values.
 */
class OccurrenceTest {

    // ========== toDayFormat Tests ==========

    @Test
    fun `toDayFormat converts epoch to YYYYMMDD`() {
        // December 25, 2024 at midnight UTC
        val date = LocalDate.of(2024, 12, 25)
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val result = Occurrence.toDayFormat(epochMillis)
        assertEquals(20241225, result)
    }

    @Test
    fun `toDayFormat handles January dates`() {
        val date = LocalDate.of(2024, 1, 1)
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val result = Occurrence.toDayFormat(epochMillis)
        assertEquals(20240101, result)
    }

    @Test
    fun `toDayFormat handles end of year`() {
        val date = LocalDate.of(2024, 12, 31)
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val result = Occurrence.toDayFormat(epochMillis)
        assertEquals(20241231, result)
    }

    @Test
    fun `toDayFormat handles leap year date`() {
        val date = LocalDate.of(2024, 2, 29) // 2024 is a leap year
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val result = Occurrence.toDayFormat(epochMillis)
        assertEquals(20240229, result)
    }

    @Test
    fun `toDayFormat handles time within day`() {
        // December 25, 2024 at 15:30:45 UTC
        val date = LocalDate.of(2024, 12, 25)
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() +
            (15 * 3600 + 30 * 60 + 45) * 1000L

        val result = Occurrence.toDayFormat(epochMillis)
        assertEquals(20241225, result) // Still same day
    }

    @Test
    fun `toDayFormat handles early epoch dates`() {
        // January 1, 1970 (Unix epoch)
        val result = Occurrence.toDayFormat(0L)
        assertEquals(19700101, result)
    }

    @Test
    fun `toDayFormat handles future dates`() {
        val date = LocalDate.of(2099, 6, 15)
        val epochMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val result = Occurrence.toDayFormat(epochMillis)
        assertEquals(20990615, result)
    }

    // ========== All-Day Event Timezone Tests ==========

    @Test
    fun `toDayFormat all-day event preserves UTC calendar date`() {
        // All-day events are stored as UTC midnight
        // Jan 6, 2026 00:00:00 UTC = 1767657600000 ms
        val jan6UtcMidnight = LocalDate.of(2026, 1, 6)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        // With isAllDay=true, should use UTC and get Jan 6
        val result = Occurrence.toDayFormat(jan6UtcMidnight, isAllDay = true)
        assertEquals("All-day event should preserve UTC date", 20260106, result)
    }

    @Test
    fun `toDayFormat all-day Dec 25 preserves date`() {
        // Christmas all-day event stored as Dec 25 00:00:00 UTC
        val dec25UtcMidnight = LocalDate.of(2024, 12, 25)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val result = Occurrence.toDayFormat(dec25UtcMidnight, isAllDay = true)
        assertEquals("Christmas should be Dec 25", 20241225, result)
    }

    @Test
    fun `toDayFormat all-day multi-day event calculates correct span`() {
        // Dec 24-26 all-day event (3 days)
        // Start: Dec 24 00:00:00 UTC
        // End: Dec 26 23:59:59 UTC (after RFC 5545 -1 adjustment)
        val startMs = LocalDate.of(2024, 12, 24)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val endMs = LocalDate.of(2024, 12, 26)
            .atStartOfDay(ZoneOffset.UTC)
            .plusSeconds(86400 - 1) // Dec 26 23:59:59 UTC
            .toInstant()
            .toEpochMilli()

        val startDay = Occurrence.toDayFormat(startMs, isAllDay = true)
        val endDay = Occurrence.toDayFormat(endMs, isAllDay = true)

        assertEquals("Start should be Dec 24", 20241224, startDay)
        assertEquals("End should be Dec 26", 20241226, endDay)
    }

    @Test
    fun `toDayFormat timed event uses default timezone behavior`() {
        // Timed events with isAllDay=false should use system default timezone
        // This test verifies the parameter works - actual timezone behavior
        // depends on system timezone
        val noonUtc = LocalDate.of(2024, 12, 25)
            .atStartOfDay(ZoneOffset.UTC)
            .plusHours(12) // 12:00 UTC
            .toInstant()
            .toEpochMilli()

        // With isAllDay=false, uses local timezone
        val result = Occurrence.toDayFormat(noonUtc, isAllDay = false)
        // Result depends on system timezone - just verify it returns a valid day
        assert(result in 20241224..20241226) { "Should be around Dec 25 depending on TZ" }
    }

    @Test
    fun `toDayFormat default parameter is false`() {
        // Verify backward compatibility - default should use local timezone
        val utcMidnight = LocalDate.of(2024, 12, 25)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        // Both calls should be equivalent
        val withoutParam = Occurrence.toDayFormat(utcMidnight)
        val withFalse = Occurrence.toDayFormat(utcMidnight, isAllDay = false)

        assertEquals("Default should equal explicit false", withoutParam, withFalse)
    }

    // ========== Default Value Tests ==========

    @Test
    fun `default isCancelled is false`() {
        val occurrence = createOccurrence()
        assertFalse(occurrence.isCancelled)
    }

    @Test
    fun `default exceptionEventId is null`() {
        val occurrence = createOccurrence()
        assertNull(occurrence.exceptionEventId)
    }

    @Test
    fun `default id is 0`() {
        val occurrence = createOccurrence()
        assertEquals(0L, occurrence.id)
    }

    // ========== Field Tests ==========

    @Test
    fun `all fields are set correctly`() {
        val occurrence = Occurrence(
            id = 1L,
            eventId = 100L,
            calendarId = 10L,
            startTs = 1000L,
            endTs = 2000L,
            startDay = 20241225,
            endDay = 20241225,
            isCancelled = true,
            exceptionEventId = 200L
        )

        assertEquals(1L, occurrence.id)
        assertEquals(100L, occurrence.eventId)
        assertEquals(10L, occurrence.calendarId)
        assertEquals(1000L, occurrence.startTs)
        assertEquals(2000L, occurrence.endTs)
        assertEquals(20241225, occurrence.startDay)
        assertEquals(20241225, occurrence.endDay)
        assertEquals(true, occurrence.isCancelled)
        assertEquals(200L, occurrence.exceptionEventId)
    }

    @Test
    fun `multi-day occurrence has different start and end days`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227 // 3-day event
        )

        assertEquals(20241225, occurrence.startDay)
        assertEquals(20241227, occurrence.endDay)
    }

    // ========== Multi-Day Helper Tests ==========

    @Test
    fun `isMultiDay returns false for single-day event`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241225
        )
        assertFalse(occurrence.isMultiDay)
    }

    @Test
    fun `isMultiDay returns true for multi-day event`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227
        )
        assert(occurrence.isMultiDay)
    }

    @Test
    fun `totalDays returns 1 for single-day event`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241225
        )
        assertEquals(1, occurrence.totalDays)
    }

    @Test
    fun `totalDays returns 3 for 3-day event`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227 // Dec 25, 26, 27 = 3 days
        )
        assertEquals(3, occurrence.totalDays)
    }

    @Test
    fun `totalDays handles month boundary`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241230,
            endDay = 20250102 // Dec 30, 31, Jan 1, 2 = 4 days
        )
        assertEquals(4, occurrence.totalDays)
    }

    @Test
    fun `getDayNumber returns 0 for date before event`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227
        )
        assertEquals(0, occurrence.getDayNumber(20241224))
    }

    @Test
    fun `getDayNumber returns 0 for date after event`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227
        )
        assertEquals(0, occurrence.getDayNumber(20241228))
    }

    @Test
    fun `getDayNumber returns correct day for first day`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227
        )
        assertEquals(1, occurrence.getDayNumber(20241225))
    }

    @Test
    fun `getDayNumber returns correct day for middle day`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227
        )
        assertEquals(2, occurrence.getDayNumber(20241226))
    }

    @Test
    fun `getDayNumber returns correct day for last day`() {
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = 0L,
            endTs = 0L,
            startDay = 20241225,
            endDay = 20241227
        )
        assertEquals(3, occurrence.getDayNumber(20241227))
    }

    @Test
    fun `calculateDaysBetween returns 0 for same day`() {
        assertEquals(0, Occurrence.calculateDaysBetween(20241225, 20241225))
    }

    @Test
    fun `calculateDaysBetween returns correct count for adjacent days`() {
        assertEquals(1, Occurrence.calculateDaysBetween(20241225, 20241226))
    }

    @Test
    fun `calculateDaysBetween returns correct count for 3-day span`() {
        assertEquals(2, Occurrence.calculateDaysBetween(20241225, 20241227))
    }

    @Test
    fun `calculateDaysBetween handles month boundary`() {
        // Dec 30 to Jan 2 = 3 days between
        assertEquals(3, Occurrence.calculateDaysBetween(20241230, 20250102))
    }

    @Test
    fun `calculateDaysBetween handles year boundary`() {
        // Dec 31 to Jan 1 = 1 day between
        assertEquals(1, Occurrence.calculateDaysBetween(20241231, 20250101))
    }

    @Test
    fun `dayFormatToCalendar parses correctly`() {
        val cal = Occurrence.dayFormatToCalendar(20241225)
        assertEquals(2024, cal.get(java.util.Calendar.YEAR))
        assertEquals(11, cal.get(java.util.Calendar.MONTH)) // 0-indexed
        assertEquals(25, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `dayFormatToCalendar handles January`() {
        val cal = Occurrence.dayFormatToCalendar(20250101)
        assertEquals(2025, cal.get(java.util.Calendar.YEAR))
        assertEquals(0, cal.get(java.util.Calendar.MONTH)) // January = 0
        assertEquals(1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `incrementDayCode increments day correctly`() {
        assertEquals(20241226, Occurrence.incrementDayCode(20241225))
    }

    @Test
    fun `incrementDayCode handles month boundary`() {
        assertEquals(20250101, Occurrence.incrementDayCode(20241231))
    }

    @Test
    fun `incrementDayCode handles February in leap year`() {
        assertEquals(20240229, Occurrence.incrementDayCode(20240228))
    }

    @Test
    fun `incrementDayCode handles February in non-leap year`() {
        assertEquals(20250301, Occurrence.incrementDayCode(20250228))
    }

    // ========== Helper ==========

    private fun createOccurrence() = Occurrence(
        eventId = 1L,
        calendarId = 1L,
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        startDay = 20241225,
        endDay = 20241225
    )
}
