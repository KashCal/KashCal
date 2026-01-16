package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for DateTimeUtils.
 *
 * These tests verify the core bug fix: all-day events stored as UTC midnight
 * must use UTC for date calculations to preserve the calendar date.
 */
class DateTimeUtilsTest {

    // ==================== Core Bug Fix Tests ====================

    @Test
    fun `all-day event at UTC midnight preserves date in negative offset timezone`() {
        // THE BUG: Jan 6 00:00 UTC displayed as Jan 5 in America/New_York (UTC-5)
        val jan6MidnightUtc = 1767657600000L  // Jan 6, 2026 00:00:00 UTC

        // All-day should use UTC, ignoring local timezone
        val date = DateTimeUtils.eventTsToLocalDate(
            jan6MidnightUtc,
            isAllDay = true,
            localZone = ZoneId.of("America/New_York")  // UTC-5
        )

        assertEquals(LocalDate.of(2026, 1, 6), date)  // Must be Jan 6, not Jan 5
    }

    @Test
    fun `all-day event preserves UTC calendar date`() {
        // Jan 6, 2026 00:00:00 UTC
        val jan6Utc = 1767657600000L

        val date = DateTimeUtils.eventTsToLocalDate(jan6Utc, isAllDay = true)

        assertEquals(LocalDate.of(2026, 1, 6), date)
    }

    @Test
    fun `all-day single day not multi-day`() {
        // Jan 6 00:00:00 UTC to Jan 6 23:59:59 UTC (single day)
        val startTs = 1767657600000L
        val endTs = 1767743999000L

        val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay = true)

        assertFalse(isMultiDay)
    }

    @Test
    fun `all-day two day event is multi-day`() {
        // Jan 6 00:00:00 UTC to Jan 7 23:59:59 UTC
        val startTs = 1767657600000L
        val endTs = 1767830399000L

        val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay = true)

        assertTrue(isMultiDay)
        assertEquals(2, DateTimeUtils.calculateTotalDays(startTs, endTs, isAllDay = true))
    }

    // ==================== Timed Event Tests ====================

    @Test
    fun `timed event respects local timezone for display`() {
        // 9 AM EST = 14:00 UTC (Jan 6, 2026)
        val jan6_9amEst = 1767708000000L

        val date = DateTimeUtils.eventTsToLocalDate(
            jan6_9amEst,
            isAllDay = false,
            localZone = ZoneId.of("America/New_York")
        )

        assertEquals(LocalDate.of(2026, 1, 6), date)
    }

    @Test
    fun `timed event crossing midnight shows as multi-day`() {
        // Jan 6, 2026 11:00 PM EST = Jan 7, 2026 04:00 UTC
        // = 1767657600 (Jan 6 00:00 UTC) + 28h = 1767657600000 + 28*3600*1000
        val startTs = 1767657600000L + (23 * 3600 * 1000)  // Jan 6 23:00 UTC
        // Jan 7, 2026 02:00 AM EST = Jan 7, 2026 07:00 UTC
        val endTs = 1767657600000L + (31 * 3600 * 1000)  // Jan 7 07:00 UTC

        val isMultiDay = DateTimeUtils.spansMultipleDays(
            startTs, endTs,
            isAllDay = false,
            localZone = ZoneId.of("America/New_York")
        )

        // In New York time: Jan 6 18:00 to Jan 7 02:00 - crosses midnight
        assertTrue(isMultiDay)
    }

    @Test
    fun `timed event uses local timezone`() {
        // Jan 6, 2026 14:00:00 UTC (9 AM EST)
        val timedTs = 1767708000000L

        // With EST (UTC-5), this is Jan 6 9:00 AM local
        val date = DateTimeUtils.eventTsToLocalDate(
            timedTs,
            isAllDay = false,
            localZone = ZoneId.of("America/New_York")
        )

        assertEquals(LocalDate.of(2026, 1, 6), date)
    }

    // ==================== Day Code Format Tests ====================

    @Test
    fun `eventTsToDayCode returns correct format`() {
        // Jan 6, 2026 00:00:00 UTC
        val jan6Utc = 1767657600000L

        val dayCode = DateTimeUtils.eventTsToDayCode(jan6Utc, isAllDay = true)

        assertEquals(20260106, dayCode)
    }

    @Test
    fun `eventTsToDayCode uses UTC for all-day`() {
        // Jan 6, 2026 00:00:00 UTC - in EST this would be Jan 5
        val jan6Utc = 1767657600000L

        val dayCode = DateTimeUtils.eventTsToDayCode(
            jan6Utc,
            isAllDay = true,
            localZone = ZoneId.of("America/New_York")
        )

        assertEquals(20260106, dayCode)  // Should be Jan 6, not Jan 5
    }

    // ==================== Total Days Calculation Tests ====================

    @Test
    fun `calculateTotalDays returns 1 for single day event`() {
        // Single day: Jan 6 00:00 to Jan 6 23:59 UTC
        val startTs = 1767657600000L
        val endTs = 1767743999000L

        val totalDays = DateTimeUtils.calculateTotalDays(startTs, endTs, isAllDay = true)

        assertEquals(1, totalDays)
    }

    @Test
    fun `calculateTotalDays returns 3 for three day event`() {
        // Jan 6 to Jan 8 (3 days)
        val startTs = 1767657600000L  // Jan 6 00:00 UTC
        // Jan 8 00:00 UTC = Jan 6 + 2 days = 1767657600000 + 2*86400*1000
        val endTs = 1767657600000L + (2 * 86400 * 1000)  // Jan 8 00:00 UTC

        val totalDays = DateTimeUtils.calculateTotalDays(startTs, endTs, isAllDay = true)

        assertEquals(3, totalDays)
    }

    // ==================== Current Day Calculation Tests ====================

    @Test
    fun `calculateCurrentDay returns 1 for first day`() {
        val startTs = 1767657600000L  // Jan 6 00:00 UTC
        val selectedTs = 1767657600000L  // Jan 6

        val currentDay = DateTimeUtils.calculateCurrentDay(startTs, selectedTs, isAllDay = true)

        assertEquals(1, currentDay)
    }

    @Test
    fun `calculateCurrentDay returns 2 for second day`() {
        val startTs = 1767657600000L  // Jan 6 00:00 UTC
        val selectedTs = 1767744000000L  // Jan 7 00:00 UTC

        val currentDay = DateTimeUtils.calculateCurrentDay(startTs, selectedTs, isAllDay = true)

        assertEquals(2, currentDay)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `DST spring forward handled correctly`() {
        // March 10, 2024 2:30 AM UTC - DST transition day
        val marchDst = 1710043800000L  // Mar 10 2024 02:30 UTC

        val date = DateTimeUtils.eventTsToLocalDate(
            marchDst,
            isAllDay = false,
            localZone = ZoneId.of("America/New_York")
        )

        // 02:30 UTC = 21:30 EST (Mar 9) - still Mar 9 in New York
        assertEquals(LocalDate.of(2024, 3, 9), date)
    }

    @Test
    fun `leap year February 29 handled`() {
        val feb29_2024 = 1709164800000L  // Feb 29, 2024 00:00:00 UTC

        val date = DateTimeUtils.eventTsToLocalDate(feb29_2024, isAllDay = true)

        assertEquals(LocalDate.of(2024, 2, 29), date)
    }

    @Test
    fun `epoch boundary handled`() {
        val epoch = 0L  // Jan 1, 1970 00:00:00 UTC

        val date = DateTimeUtils.eventTsToLocalDate(epoch, isAllDay = true)

        assertEquals(LocalDate.of(1970, 1, 1), date)
    }

    @Test
    fun `far future date handled`() {
        // Dec 31, 2099 00:00:00 UTC
        val farFuture = 4102358400000L

        val date = DateTimeUtils.eventTsToLocalDate(farFuture, isAllDay = true)

        assertEquals(LocalDate.of(2099, 12, 31), date)
    }

    // ==================== Positive Offset Timezone Tests ====================

    @Test
    fun `all-day event at UTC midnight in positive offset timezone`() {
        // Jan 6 00:00 UTC in Tokyo (UTC+9) would be Jan 6 09:00 local
        val jan6MidnightUtc = 1767657600000L

        val date = DateTimeUtils.eventTsToLocalDate(
            jan6MidnightUtc,
            isAllDay = true,
            localZone = ZoneId.of("Asia/Tokyo")
        )

        // All-day should still use UTC, so Jan 6
        assertEquals(LocalDate.of(2026, 1, 6), date)
    }

    @Test
    fun `timed event in positive offset timezone`() {
        // Jan 6 00:00 UTC = Jan 6 09:00 Tokyo
        val jan6MidnightUtc = 1767657600000L

        val date = DateTimeUtils.eventTsToLocalDate(
            jan6MidnightUtc,
            isAllDay = false,
            localZone = ZoneId.of("Asia/Tokyo")
        )

        // Timed uses local TZ, so Jan 6 in Tokyo
        assertEquals(LocalDate.of(2026, 1, 6), date)
    }

    // ==================== ZonedDateTime Tests ====================

    @Test
    fun `eventTsToZonedDateTime returns UTC for all-day`() {
        val ts = 1767657600000L  // Jan 6, 2026 00:00:00 UTC

        val zdt = DateTimeUtils.eventTsToZonedDateTime(ts, isAllDay = true)

        assertEquals("Z", zdt.zone.id)  // UTC
        assertEquals(6, zdt.dayOfMonth)
    }

    @Test
    fun `eventTsToZonedDateTime returns local zone for timed`() {
        val ts = 1767657600000L
        val zone = ZoneId.of("America/New_York")

        val zdt = DateTimeUtils.eventTsToZonedDateTime(ts, isAllDay = false, localZone = zone)

        assertEquals("America/New_York", zdt.zone.id)
    }

    // ==================== Format Event Date Tests ====================

    @Test
    fun `formatEventDate all-day uses UTC for correct date`() {
        // THE BUG FIX TEST: Jan 6 00:00 UTC should show Jan 6, not Jan 5
        val jan6MidnightUtc = 1767657600000L  // Jan 6, 2026 00:00:00 UTC

        val result = DateTimeUtils.formatEventDate(
            jan6MidnightUtc,
            isAllDay = true,
            pattern = "MMM d, yyyy",
            localZone = ZoneId.of("America/New_York")  // UTC-5, would show Jan 5 if using local
        )

        // Must contain "Jan 6" not "Jan 5"
        assertTrue("Expected Jan 6 but got: $result", result.contains("Jan 6"))
    }

    @Test
    fun `formatEventDate timed uses local timezone`() {
        // Jan 6 14:00 UTC = 9 AM EST
        val jan6_2pmUtc = 1767708000000L

        val result = DateTimeUtils.formatEventDate(
            jan6_2pmUtc,
            isAllDay = false,
            pattern = "MMM d, yyyy",
            localZone = ZoneId.of("America/New_York")
        )

        assertTrue("Expected Jan 6 but got: $result", result.contains("Jan 6"))
    }

    @Test
    fun `formatEventDate all-day in negative offset shows correct date`() {
        // Dec 25 00:00 UTC - in Central time (UTC-6) this would be Dec 24 6 PM
        // Christmas Day should still show as Dec 25 for all-day events
        val dec25MidnightUtc = 1735084800000L  // Dec 25, 2024 00:00:00 UTC

        val result = DateTimeUtils.formatEventDate(
            dec25MidnightUtc,
            isAllDay = true,
            pattern = "MMM d",
            localZone = ZoneId.of("America/Chicago")  // UTC-6
        )

        assertTrue("Christmas should be Dec 25, not Dec 24. Got: $result", result.contains("Dec 25"))
    }

    @Test
    fun `formatEventDate all-day in positive offset shows correct date`() {
        // Dec 25 00:00 UTC in Tokyo (UTC+9) would be Dec 25 9 AM local
        val dec25MidnightUtc = 1735084800000L

        val result = DateTimeUtils.formatEventDate(
            dec25MidnightUtc,
            isAllDay = true,
            pattern = "MMM d",
            localZone = ZoneId.of("Asia/Tokyo")
        )

        assertTrue("Expected Dec 25, got: $result", result.contains("Dec 25"))
    }

    @Test
    fun `formatEventDate default pattern includes day of week`() {
        val jan6MidnightUtc = 1767657600000L  // Jan 6, 2026 is a Tuesday

        val result = DateTimeUtils.formatEventDate(jan6MidnightUtc, isAllDay = true)

        assertTrue("Expected day of week in result: $result", result.contains("Tue"))
    }

    // ==================== Format Event Date Short Tests ====================

    @Test
    fun `formatEventDateShort returns short format`() {
        val jan6MidnightUtc = 1767657600000L

        val result = DateTimeUtils.formatEventDateShort(jan6MidnightUtc, isAllDay = true)

        // Should be like "Tue, Jan 6" without the year
        assertTrue("Expected short format without year: $result",
            result.contains("Tue") && result.contains("Jan") && !result.contains("2026"))
    }

    @Test
    fun `formatEventDateShort all-day uses UTC`() {
        // THE BUG FIX: Same test for short format
        val jan6MidnightUtc = 1767657600000L

        val result = DateTimeUtils.formatEventDateShort(
            jan6MidnightUtc,
            isAllDay = true,
            localZone = ZoneId.of("America/New_York")
        )

        assertTrue("Expected Jan 6 in short format: $result", result.contains("Jan 6"))
    }

    // ==================== Format Event Time Tests ====================

    @Test
    fun `formatEventTime returns empty for all-day`() {
        val ts = 1767657600000L

        val result = DateTimeUtils.formatEventTime(ts, isAllDay = true)

        assertEquals("", result)
    }

    @Test
    fun `formatEventTime returns formatted time for timed event`() {
        // Jan 6, 2026 14:30:00 UTC = 9:30 AM EST
        val ts = 1767709800000L

        val result = DateTimeUtils.formatEventTime(
            ts,
            isAllDay = false,
            localZone = ZoneId.of("America/New_York")
        )

        assertTrue("Expected 9:30 AM, got: $result", result.contains("9:30") && result.contains("AM"))
    }

    @Test
    fun `formatEventTime uses 12-hour format by default`() {
        // Jan 6, 2026 20:00:00 UTC = 3 PM EST (15:00)
        // 1767657600000 (Jan 6 00:00 UTC) + 20 hours = 1767729600000
        val ts = 1767729600000L

        val result = DateTimeUtils.formatEventTime(
            ts,
            isAllDay = false,
            localZone = ZoneId.of("America/New_York")
        )

        assertTrue("Expected PM time format: $result", result.contains("PM"))
    }

    @Test
    fun `formatEventTime custom pattern works`() {
        // 14:30 UTC
        val ts = 1767709800000L

        val result = DateTimeUtils.formatEventTime(
            ts,
            isAllDay = false,
            pattern = "HH:mm",
            localZone = ZoneId.of("UTC")
        )

        assertEquals("14:30", result)
    }

    // ==================== Integration Tests: Real-World TripIt Scenario ====================

    @Test
    fun `TripIt ICS all-day event displays correct date`() {
        // Simulates TripIt ICS feed: all-day event stored as UTC midnight
        // User in Central time (UTC-6) should see correct date

        // Flight on December 25, 2024 - stored as VALUE=DATE in ICS = Dec 25 00:00 UTC
        val tripItFlightDate = 1735084800000L  // Dec 25, 2024 00:00:00 UTC

        // Verify date formatting shows Dec 25
        val dateDisplay = DateTimeUtils.formatEventDateShort(
            tripItFlightDate,
            isAllDay = true,
            localZone = ZoneId.of("America/Chicago")  // Central time
        )

        assertTrue("TripIt flight should show Dec 25, not Dec 24. Got: $dateDisplay",
            dateDisplay.contains("Dec 25"))

        // Verify day code is 20241225
        val dayCode = DateTimeUtils.eventTsToDayCode(
            tripItFlightDate,
            isAllDay = true,
            localZone = ZoneId.of("America/Chicago")
        )

        assertEquals("Day code should be Dec 25", 20241225, dayCode)
    }

    @Test
    fun `multi-day TripIt trip shows correct date range`() {
        // Hotel stay: Dec 25-27, 2024
        val checkIn = 1735084800000L   // Dec 25, 2024 00:00:00 UTC
        val checkOut = 1735257600000L  // Dec 27, 2024 00:00:00 UTC

        val startDate = DateTimeUtils.formatEventDateShort(checkIn, isAllDay = true)
        val endDate = DateTimeUtils.formatEventDateShort(checkOut, isAllDay = true)

        assertTrue("Check-in should be Dec 25: $startDate", startDate.contains("Dec 25"))
        assertTrue("Check-out should be Dec 27: $endDate", endDate.contains("Dec 27"))

        val totalDays = DateTimeUtils.calculateTotalDays(checkIn, checkOut, isAllDay = true)
        assertEquals("Hotel stay should be 3 days", 3, totalDays)
    }

    // ==================== UTC Conversion Functions Tests ====================

    @Test
    fun `localDateToUtcMidnight converts local date to UTC midnight`() {
        // User picks Jan 6 in date picker (local time Chicago, UTC-6)
        // Jan 6, 2026 00:00:00 Chicago = Jan 6, 2026 06:00:00 UTC
        val jan6MidnightChicago = 1767679200000L  // Jan 6, 2026 06:00:00 UTC (= Jan 6 00:00 Chicago)

        val utcMidnight = DateTimeUtils.localDateToUtcMidnight(
            jan6MidnightChicago,
            ZoneId.of("America/Chicago")
        )

        // Result should be Jan 6 00:00 UTC, not Jan 6 06:00 UTC
        val expectedJan6UtcMidnight = 1767657600000L  // Jan 6, 2026 00:00:00 UTC
        assertEquals(expectedJan6UtcMidnight, utcMidnight)
    }

    @Test
    fun `localDateToUtcMidnight with positive offset timezone`() {
        // User picks Jan 6 in Tokyo (UTC+9)
        // Jan 6, 2026 00:00:00 Tokyo = Jan 5, 2026 15:00:00 UTC
        val jan6MidnightTokyo = 1767625200000L  // Jan 5, 2026 15:00:00 UTC (= Jan 6 00:00 Tokyo)

        val utcMidnight = DateTimeUtils.localDateToUtcMidnight(
            jan6MidnightTokyo,
            ZoneId.of("Asia/Tokyo")
        )

        // Result should be Jan 6 00:00 UTC
        val expectedJan6UtcMidnight = 1767657600000L  // Jan 6, 2026 00:00:00 UTC
        assertEquals(expectedJan6UtcMidnight, utcMidnight)
    }

    @Test
    fun `localDateToUtcMidnight preserves calendar date`() {
        // The key invariant: the calendar date should be preserved
        // regardless of the local timezone offset

        val testCases = listOf(
            "America/New_York" to 1767675600000L,   // Jan 6 05:00 UTC = Jan 6 00:00 EST
            "America/Chicago" to 1767679200000L,    // Jan 6 06:00 UTC = Jan 6 00:00 CST
            "America/Los_Angeles" to 1767686400000L, // Jan 6 08:00 UTC = Jan 6 00:00 PST
            "Europe/London" to 1767657600000L,       // Jan 6 00:00 UTC = Jan 6 00:00 London
            "Asia/Tokyo" to 1767625200000L           // Jan 5 15:00 UTC = Jan 6 00:00 Tokyo
        )

        val expectedJan6UtcMidnight = 1767657600000L

        for ((timezone, localMidnight) in testCases) {
            val result = DateTimeUtils.localDateToUtcMidnight(localMidnight, ZoneId.of(timezone))
            assertEquals(
                "Timezone $timezone: local midnight should convert to UTC midnight of same date",
                expectedJan6UtcMidnight,
                result
            )
        }
    }

    @Test
    fun `utcMidnightToLocalDate converts back to local date`() {
        // Jan 6 00:00 UTC should display as Jan 6 in local date picker
        val jan6UtcMidnight = 1767657600000L  // Jan 6, 2026 00:00:00 UTC

        val localDate = DateTimeUtils.utcMidnightToLocalDate(
            jan6UtcMidnight,
            ZoneId.of("America/Chicago")
        )

        // In Chicago, Jan 6 starts at Jan 6 00:00 Chicago = Jan 6 06:00 UTC
        val expectedJan6ChicagoMidnight = 1767679200000L
        assertEquals(expectedJan6ChicagoMidnight, localDate)
    }

    @Test
    fun `utcMidnightToLocalDate roundtrips correctly`() {
        // Roundtrip: local → UTC → local should give same calendar date
        val testTimezones = listOf(
            "America/New_York",
            "America/Chicago",
            "America/Los_Angeles",
            "Europe/London",
            "Asia/Tokyo"
        )

        val originalLocalMidnight = 1767679200000L  // Some arbitrary local midnight

        for (timezone in testTimezones) {
            val zone = ZoneId.of(timezone)

            // Get the calendar date from original timestamp
            val originalDate = DateTimeUtils.eventTsToLocalDate(originalLocalMidnight, false, zone)

            // Convert to UTC midnight
            val utcMidnight = DateTimeUtils.localDateToUtcMidnight(originalLocalMidnight, zone)

            // Convert back to local
            val backToLocal = DateTimeUtils.utcMidnightToLocalDate(utcMidnight, zone)

            // Verify calendar date is preserved
            val resultDate = DateTimeUtils.eventTsToLocalDate(backToLocal, false, zone)
            val utcDate = DateTimeUtils.eventTsToLocalDate(utcMidnight, true, zone)

            assertEquals(
                "Timezone $timezone: UTC midnight should represent same calendar date",
                originalDate,
                utcDate
            )
        }
    }

    @Test
    fun `utcMidnightToEndOfDay returns last millisecond of day`() {
        val jan6UtcMidnight = 1767657600000L  // Jan 6, 2026 00:00:00 UTC

        val endOfDay = DateTimeUtils.utcMidnightToEndOfDay(jan6UtcMidnight)

        // End of Jan 6 should be Jan 6 23:59:59.999 UTC
        // = midnight + 24 hours - 1 ms
        val expected = jan6UtcMidnight + (24 * 60 * 60 * 1000) - 1
        assertEquals(expected, endOfDay)

        // Verify it's still the same calendar day in UTC
        val endDate = DateTimeUtils.eventTsToLocalDate(endOfDay, isAllDay = true)
        assertEquals(LocalDate.of(2026, 1, 6), endDate)
    }

    @Test
    fun `utcMidnightToEndOfDay does not bleed into next day`() {
        val jan6UtcMidnight = 1767657600000L

        val endOfDay = DateTimeUtils.utcMidnightToEndOfDay(jan6UtcMidnight)
        val nextDay = endOfDay + 1

        // End of day should be Jan 6
        val endDayCode = DateTimeUtils.eventTsToDayCode(endOfDay, isAllDay = true)
        assertEquals(20260106, endDayCode)

        // Next millisecond should be Jan 7
        val nextDayCode = DateTimeUtils.eventTsToDayCode(nextDay, isAllDay = true)
        assertEquals(20260107, nextDayCode)
    }

    // ==================== Event Form Edit Mode Scenario Test ====================

    @Test
    fun `event form edit mode loads correct date for all-day event`() {
        // Simulates EventFormSheet loading an all-day event for editing
        // Event stored as UTC midnight should display correct date in local picker

        val storedUtcMidnight = 1767657600000L  // Jan 6, 2026 00:00:00 UTC
        val localZone = ZoneId.of("America/Chicago")

        // When loading for edit, convert to local for date picker
        val displayTs = DateTimeUtils.utcMidnightToLocalDate(storedUtcMidnight, localZone)

        // The date picker should show Jan 6
        val displayDate = DateTimeUtils.eventTsToLocalDate(displayTs, isAllDay = false, localZone)
        assertEquals(LocalDate.of(2026, 1, 6), displayDate)
    }

    @Test
    fun `event form save converts local date to UTC for all-day event`() {
        // Simulates HomeViewModel.saveEvent() for all-day events
        // User picks date in local picker, should be stored as UTC midnight

        val localZone = ZoneId.of("America/Chicago")
        // User picks Jan 6 in Chicago date picker
        val pickedLocalMidnight = 1767679200000L  // Jan 6, 2026 06:00:00 UTC (= Jan 6 00:00 Chicago)

        // Convert to UTC midnight for storage
        val storageTs = DateTimeUtils.localDateToUtcMidnight(pickedLocalMidnight, localZone)

        // Verify stored as Jan 6 00:00 UTC
        assertEquals(1767657600000L, storageTs)

        // Verify day code calculation uses UTC and gives correct date
        val dayCode = DateTimeUtils.eventTsToDayCode(storageTs, isAllDay = true)
        assertEquals(20260106, dayCode)
    }

    @Test
    fun `all-day event roundtrip through form preserves date`() {
        // Complete roundtrip: stored UTC → edit form → save → stored UTC
        val localZone = ZoneId.of("America/Chicago")

        // 1. Original stored UTC midnight
        val originalStoredTs = 1767657600000L  // Jan 6, 2026 00:00:00 UTC

        // 2. Load for edit (convert to local for date picker)
        val displayTs = DateTimeUtils.utcMidnightToLocalDate(originalStoredTs, localZone)

        // 3. User makes no changes, saves
        val newStoredTs = DateTimeUtils.localDateToUtcMidnight(displayTs, localZone)

        // 4. Verify timestamp unchanged
        assertEquals(originalStoredTs, newStoredTs)

        // 5. Verify display date preserved
        val originalDate = DateTimeUtils.eventTsToLocalDate(originalStoredTs, isAllDay = true)
        val newDate = DateTimeUtils.eventTsToLocalDate(newStoredTs, isAllDay = true)
        assertEquals(originalDate, newDate)
    }

    // ==================== Format Relative Time Tests ====================

    @Test
    fun `formatRelativeTime just now for recent timestamp`() {
        val now = System.currentTimeMillis()
        val result = DateTimeUtils.formatRelativeTime(now)
        assertEquals("Just now", result)
    }

    @Test
    fun `formatRelativeTime shows minutes ago`() {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        val result = DateTimeUtils.formatRelativeTime(fiveMinutesAgo)
        assertEquals("5 minutes ago", result)
    }

    @Test
    fun `formatRelativeTime shows singular minute`() {
        val oneMinuteAgo = System.currentTimeMillis() - (1 * 60 * 1000)
        val result = DateTimeUtils.formatRelativeTime(oneMinuteAgo)
        assertEquals("1 minute ago", result)
    }

    @Test
    fun `formatRelativeTime shows hours ago`() {
        val threeHoursAgo = System.currentTimeMillis() - (3 * 60 * 60 * 1000)
        val result = DateTimeUtils.formatRelativeTime(threeHoursAgo)
        assertEquals("3 hours ago", result)
    }

    @Test
    fun `formatRelativeTime shows days ago`() {
        val twoDaysAgo = System.currentTimeMillis() - (2 * 24 * 60 * 60 * 1000)
        val result = DateTimeUtils.formatRelativeTime(twoDaysAgo)
        assertEquals("2 days ago", result)
    }

    @Test
    fun `formatRelativeTime shows date for old timestamps`() {
        val twoWeeksAgo = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000)
        val result = DateTimeUtils.formatRelativeTime(twoWeeksAgo)
        // Should show date like "Dec 20" instead of "14 days ago"
        assertTrue("Should show month format, got: $result",
            result.matches(Regex("[A-Z][a-z]{2} \\d{1,2}")))
    }

    // ==================== Format Reminder Label Tests ====================

    @Test
    fun `formatReminderLabel timed event shows correct labels`() {
        assertEquals("No reminder", DateTimeUtils.formatReminderLabel(-1, isAllDay = false))
        assertEquals("At time of event", DateTimeUtils.formatReminderLabel(0, isAllDay = false))
        assertEquals("5 minutes before", DateTimeUtils.formatReminderLabel(5, isAllDay = false))
        assertEquals("15 minutes before", DateTimeUtils.formatReminderLabel(15, isAllDay = false))
        assertEquals("1 hour before", DateTimeUtils.formatReminderLabel(60, isAllDay = false))
        assertEquals("2 hours before", DateTimeUtils.formatReminderLabel(120, isAllDay = false))
    }

    @Test
    fun `formatReminderLabel all-day event shows correct labels`() {
        assertEquals("No reminder", DateTimeUtils.formatReminderLabel(-1, isAllDay = true))
        assertEquals("9 AM day of event", DateTimeUtils.formatReminderLabel(540, isAllDay = true))
        assertEquals("1 day before", DateTimeUtils.formatReminderLabel(1440, isAllDay = true))
        assertEquals("2 days before", DateTimeUtils.formatReminderLabel(2880, isAllDay = true))
        assertEquals("1 week before", DateTimeUtils.formatReminderLabel(10080, isAllDay = true))
    }

    @Test
    fun `formatReminderLabel unknown value shows minutes`() {
        assertEquals("999 minutes", DateTimeUtils.formatReminderLabel(999, isAllDay = false))
        assertEquals("999 minutes", DateTimeUtils.formatReminderLabel(999, isAllDay = true))
    }

    // ==================== Format Reminder Short Tests ====================

    @Test
    fun `formatReminderShort returns correct abbreviations`() {
        assertEquals("Off", DateTimeUtils.formatReminderShort(-1))
        assertEquals("At event", DateTimeUtils.formatReminderShort(0))
        assertEquals("5m", DateTimeUtils.formatReminderShort(5))
        assertEquals("15m", DateTimeUtils.formatReminderShort(15))
        assertEquals("1h", DateTimeUtils.formatReminderShort(60))
        assertEquals("9AM", DateTimeUtils.formatReminderShort(540))
        assertEquals("1d", DateTimeUtils.formatReminderShort(1440))
        assertEquals("1w", DateTimeUtils.formatReminderShort(10080))
    }

    @Test
    fun `formatReminderShort unknown value shows minutes`() {
        assertEquals("999m", DateTimeUtils.formatReminderShort(999))
    }

    @Test
    fun `formatReminderShort handles arbitrary hour values from external calendars`() {
        // iCloud can set reminders like -PT15H (15 hours = 900 minutes)
        assertEquals("15h", DateTimeUtils.formatReminderShort(900))
        assertEquals("2h", DateTimeUtils.formatReminderShort(120))
        assertEquals("12h", DateTimeUtils.formatReminderShort(720))
        assertEquals("3d", DateTimeUtils.formatReminderShort(4320))  // 3 days
        assertEquals("2w", DateTimeUtils.formatReminderShort(20160)) // 2 weeks
    }

    // ==================== Format Sync Interval Tests ====================

    @Test
    fun `formatSyncInterval returns correct labels`() {
        assertEquals("1 hour", DateTimeUtils.formatSyncInterval(1 * 60 * 60 * 1000L))
        assertEquals("6 hours", DateTimeUtils.formatSyncInterval(6 * 60 * 60 * 1000L))
        assertEquals("12 hours", DateTimeUtils.formatSyncInterval(12 * 60 * 60 * 1000L))
        assertEquals("24 hours", DateTimeUtils.formatSyncInterval(24 * 60 * 60 * 1000L))
        assertEquals("Manual only", DateTimeUtils.formatSyncInterval(Long.MAX_VALUE))
    }

    @Test
    fun `formatSyncInterval unknown value shows hours`() {
        assertEquals("48 hours", DateTimeUtils.formatSyncInterval(48 * 60 * 60 * 1000L))
    }

    // ==================== Format Event Date Time Tests ====================

    @Test
    fun `formatEventDateTime all-day single day`() {
        val jan6MidnightUtc = 1767657600000L
        val jan6EndOfDay = jan6MidnightUtc + (24 * 60 * 60 * 1000) - 1

        val result = DateTimeUtils.formatEventDateTime(jan6MidnightUtc, jan6EndOfDay, isAllDay = true)

        assertTrue("Should contain 'All day': $result", result.contains("All day"))
        assertTrue("Should contain date: $result", result.contains("Jan"))
        assertFalse("Should not contain arrow: $result", result.contains("\u2192"))
    }

    @Test
    fun `formatEventDateTime all-day multi-day`() {
        val jan6MidnightUtc = 1767657600000L
        val jan8MidnightUtc = jan6MidnightUtc + (2 * 24 * 60 * 60 * 1000)

        val result = DateTimeUtils.formatEventDateTime(jan6MidnightUtc, jan8MidnightUtc, isAllDay = true)

        assertTrue("Should contain 'All day': $result", result.contains("All day"))
        assertTrue("Should contain arrow: $result", result.contains("\u2192"))
    }

    @Test
    fun `formatEventDateTime timed event`() {
        // Jan 6 14:00 to 15:00 UTC
        val startTs = 1767708000000L
        val endTs = startTs + (60 * 60 * 1000)

        val result = DateTimeUtils.formatEventDateTime(startTs, endTs, isAllDay = false)

        assertTrue("Should contain time range: $result", result.contains("-"))
        assertFalse("Should not contain 'All day': $result", result.contains("All day"))
    }

    // ==================== Format Time Tests ====================

    @Test
    fun `formatTime returns 12-hour format`() {
        val result = DateTimeUtils.formatTime(14, 30)
        assertTrue("Should be PM: $result", result.contains("PM") || result.contains("pm"))
        assertTrue("Should have 2:30: $result", result.contains("2:30"))
    }

    @Test
    fun `formatTime midnight`() {
        val result = DateTimeUtils.formatTime(0, 0)
        assertTrue("Should be 12:00 AM: $result", result.contains("12:00") && (result.contains("AM") || result.contains("am")))
    }

    @Test
    fun `formatTime noon`() {
        val result = DateTimeUtils.formatTime(12, 0)
        assertTrue("Should be 12:00 PM: $result", result.contains("12:00") && (result.contains("PM") || result.contains("pm")))
    }

    // ==================== First Day of Week Tests ====================

    @Test
    fun `getOrderedDaysOfWeek_sunday returns Sunday first`() {
        val result = DateTimeUtils.getOrderedDaysOfWeek(java.util.Calendar.SUNDAY)
        assertEquals(java.time.DayOfWeek.SUNDAY, result[0])
        assertEquals(java.time.DayOfWeek.MONDAY, result[1])
        assertEquals(java.time.DayOfWeek.SATURDAY, result[6])
    }

    @Test
    fun `getOrderedDaysOfWeek_monday returns Monday first`() {
        val result = DateTimeUtils.getOrderedDaysOfWeek(java.util.Calendar.MONDAY)
        assertEquals(java.time.DayOfWeek.MONDAY, result[0])
        assertEquals(java.time.DayOfWeek.TUESDAY, result[1])
        assertEquals(java.time.DayOfWeek.SUNDAY, result[6])
    }

    @Test
    fun `getOrderedDaysOfWeek_saturday returns Saturday first`() {
        val result = DateTimeUtils.getOrderedDaysOfWeek(java.util.Calendar.SATURDAY)
        assertEquals(java.time.DayOfWeek.SATURDAY, result[0])
        assertEquals(java.time.DayOfWeek.SUNDAY, result[1])
        assertEquals(java.time.DayOfWeek.FRIDAY, result[6])
    }

    @Test
    fun `resolveFirstDayOfWeek_explicit returns same value`() {
        assertEquals(java.util.Calendar.SUNDAY, DateTimeUtils.resolveFirstDayOfWeek(java.util.Calendar.SUNDAY))
        assertEquals(java.util.Calendar.MONDAY, DateTimeUtils.resolveFirstDayOfWeek(java.util.Calendar.MONDAY))
        assertEquals(java.util.Calendar.SATURDAY, DateTimeUtils.resolveFirstDayOfWeek(java.util.Calendar.SATURDAY))
    }

    @Test
    fun `getLocaleFirstDayOfWeek returns valid DayOfWeek`() {
        val result = DateTimeUtils.getLocaleFirstDayOfWeek()
        // Result should be a valid DayOfWeek (not null)
        assertNotNull(result)
        assertTrue(result in java.time.DayOfWeek.values())
    }

    @Test
    fun `getFirstDayOffset_jan2026_sunday first`() {
        // Jan 1, 2026 is a Thursday
        val calendar = java.util.Calendar.getInstance().apply { set(2026, 0, 1) }
        val offset = DateTimeUtils.getFirstDayOffset(calendar, java.util.Calendar.SUNDAY)
        // Thursday is the 5th day when Sunday is first (Sun=0, Mon=1, Tue=2, Wed=3, Thu=4)
        assertEquals(4, offset)
    }

    @Test
    fun `getFirstDayOffset_jan2026_monday first`() {
        // Jan 1, 2026 is a Thursday
        val calendar = java.util.Calendar.getInstance().apply { set(2026, 0, 1) }
        val offset = DateTimeUtils.getFirstDayOffset(calendar, java.util.Calendar.MONDAY)
        // Thursday is the 4th day when Monday is first (Mon=0, Tue=1, Wed=2, Thu=3)
        assertEquals(3, offset)
    }

    @Test
    fun `getFirstDayOffset_jan2026_saturday first`() {
        // Jan 1, 2026 is a Thursday
        val calendar = java.util.Calendar.getInstance().apply { set(2026, 0, 1) }
        val offset = DateTimeUtils.getFirstDayOffset(calendar, java.util.Calendar.SATURDAY)
        // Thursday is the 6th day when Saturday is first (Sat=0, Sun=1, Mon=2, Tue=3, Wed=4, Thu=5)
        assertEquals(5, offset)
    }

    @Test
    fun `getDayOfWeekOffset_wednesday_sunday first`() {
        // Jan 15, 2026 is a Thursday (let's use a Wednesday instead: Jan 14, 2026)
        val date = LocalDate.of(2026, 1, 14) // Wednesday
        val offset = DateTimeUtils.getDayOfWeekOffset(date, java.util.Calendar.SUNDAY)
        // Wednesday is the 4th day when Sunday is first (Sun=0, Mon=1, Tue=2, Wed=3)
        assertEquals(3, offset)
    }

    @Test
    fun `getDayOfWeekOffset_wednesday_monday first`() {
        val date = LocalDate.of(2026, 1, 14) // Wednesday
        val offset = DateTimeUtils.getDayOfWeekOffset(date, java.util.Calendar.MONDAY)
        // Wednesday is the 3rd day when Monday is first (Mon=0, Tue=1, Wed=2)
        assertEquals(2, offset)
    }

    @Test
    fun `getDayOfWeekOffset_sunday_sunday first`() {
        val date = LocalDate.of(2026, 1, 11) // Sunday
        val offset = DateTimeUtils.getDayOfWeekOffset(date, java.util.Calendar.SUNDAY)
        // Sunday is the first day (offset = 0) when Sunday is first
        assertEquals(0, offset)
    }

    @Test
    fun `getDayOfWeekOffset_sunday_monday first`() {
        val date = LocalDate.of(2026, 1, 11) // Sunday
        val offset = DateTimeUtils.getDayOfWeekOffset(date, java.util.Calendar.MONDAY)
        // Sunday is the last day (offset = 6) when Monday is first
        assertEquals(6, offset)
    }

    @Test
    fun `getDayOfWeekOffset_saturday_saturday first`() {
        val date = LocalDate.of(2026, 1, 10) // Saturday
        val offset = DateTimeUtils.getDayOfWeekOffset(date, java.util.Calendar.SATURDAY)
        // Saturday is the first day (offset = 0) when Saturday is first
        assertEquals(0, offset)
    }
}
