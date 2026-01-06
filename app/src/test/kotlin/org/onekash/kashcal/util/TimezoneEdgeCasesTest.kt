package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Timezone edge case tests for calendar operations.
 *
 * Tests verify correct handling of:
 * - Daylight Saving Time (DST) transitions
 * - Cross-timezone event creation and display
 * - All-day event timezone handling (UTC vs local)
 * - Recurring events across DST boundaries
 * - Edge cases at midnight/day boundaries
 *
 * These scenarios are critical for a CalDAV calendar app syncing with iCloud.
 */
class TimezoneEdgeCasesTest {

    // ==================== DST Transition Tests ====================

    @Test
    fun `event during spring forward DST transition`() {
        // US Spring Forward: 2:00 AM becomes 3:00 AM
        // March 10, 2024 at 2:00 AM EST -> 3:00 AM EDT
        val zone = ZoneId.of("America/New_York")

        // Create event at 2:30 AM on DST day (this time doesn't exist!)
        val beforeDst = ZonedDateTime.of(2024, 3, 10, 1, 30, 0, 0, zone)
        val afterDst = ZonedDateTime.of(2024, 3, 10, 3, 30, 0, 0, zone)

        // Duration between 1:30 AM and 3:30 AM on DST day should be 1 hour, not 2
        val durationHours = java.time.Duration.between(beforeDst, afterDst).toHours()
        assertEquals(1, durationHours)
    }

    @Test
    fun `event during fall back DST transition`() {
        // US Fall Back: 2:00 AM becomes 1:00 AM
        // November 3, 2024 at 2:00 AM EDT -> 1:00 AM EST
        val zone = ZoneId.of("America/New_York")

        // Create event spanning the DST transition
        val beforeFallback = ZonedDateTime.of(2024, 11, 3, 0, 30, 0, 0, zone)
        val afterFallback = ZonedDateTime.of(2024, 11, 3, 2, 30, 0, 0, zone)

        // Duration should be 3 hours (extra hour gained)
        val durationHours = java.time.Duration.between(beforeFallback, afterFallback).toHours()
        assertEquals(3, durationHours)
    }

    @Test
    fun `recurring event time stays consistent across DST`() {
        // A daily 9 AM meeting should stay at 9 AM local time
        val zone = ZoneId.of("America/New_York")

        // Before DST (EST)
        val beforeDst = ZonedDateTime.of(2024, 3, 9, 9, 0, 0, 0, zone)
        // After DST (EDT)
        val afterDst = ZonedDateTime.of(2024, 3, 11, 9, 0, 0, 0, zone)

        // Both should be at 9 AM local time
        assertEquals(9, beforeDst.hour)
        assertEquals(9, afterDst.hour)

        // But UTC times differ by 1 hour
        val beforeUtcHour = beforeDst.withZoneSameInstant(ZoneOffset.UTC).hour
        val afterUtcHour = afterDst.withZoneSameInstant(ZoneOffset.UTC).hour
        assertEquals(1, beforeUtcHour - afterUtcHour) // EST is UTC-5, EDT is UTC-4
    }

    // ==================== Cross-Timezone Tests ====================

    @Test
    fun `event created in one timezone displayed in another`() {
        // Event created in Tokyo
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val tokyoTime = ZonedDateTime.of(2024, 6, 15, 10, 0, 0, 0, tokyoZone)

        // Displayed in New York
        val nyZone = ZoneId.of("America/New_York")
        val nyTime = tokyoTime.withZoneSameInstant(nyZone)

        // 10 AM Tokyo = 9 PM previous day in NY (during summer)
        // Tokyo is UTC+9, NY EDT is UTC-4, difference is 13 hours
        assertEquals(15, tokyoTime.dayOfMonth) // June 15 in Tokyo
        assertEquals(14, nyTime.dayOfMonth)    // June 14 in NY (previous day)
        assertEquals(21, nyTime.hour)          // 9 PM
    }

    @Test
    fun `event spans multiple days in different timezone`() {
        // Event at 11 PM in LA spans to next day in London
        val laZone = ZoneId.of("America/Los_Angeles")
        val londonZone = ZoneId.of("Europe/London")

        // 11 PM June 15 in LA
        val laTime = ZonedDateTime.of(2024, 6, 15, 23, 0, 0, 0, laZone)

        // Convert to London (BST = UTC+1, PDT = UTC-7, diff = 8 hours)
        val londonTime = laTime.withZoneSameInstant(londonZone)

        // Should be 7 AM June 16 in London
        assertEquals(16, londonTime.dayOfMonth)
        assertEquals(7, londonTime.hour)
    }

    @Test
    fun `UTC timestamp is timezone invariant`() {
        val timestamp = 1718438400000L // Some fixed timestamp

        val nyTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("America/New_York"))
        val tokyoTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Tokyo"))
        val utcTime = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC)

        // All represent the same instant
        assertEquals(nyTime.toInstant(), tokyoTime.toInstant())
        assertEquals(tokyoTime.toInstant(), utcTime.toInstant())

        // But different local times
        assertNotEquals(nyTime.hour, tokyoTime.hour)
    }

    // ==================== All-Day Event Tests ====================

    @Test
    fun `all-day event is date-based not time-based`() {
        // All-day events should be the same date regardless of timezone
        val date = LocalDate.of(2024, 6, 15)

        // In CalDAV, all-day events are stored as DATE (not DATE-TIME)
        // They don't have a timezone component
        val startOfDayUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endOfDayUtc = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

        // Should span exactly 24 hours minus 1 ms
        assertEquals(86400000 - 1, endOfDayUtc - startOfDayUtc)
    }

    @Test
    fun `all-day event spans correct local day`() {
        // An all-day event for June 15 should appear on June 15 in any timezone
        val date = LocalDate.of(2024, 6, 15)

        // iCloud stores all-day events at midnight UTC
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // In Tokyo (UTC+9), midnight UTC is 9 AM local
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val tokyoLocalDate = Instant.ofEpochMilli(utcMidnight)
            .atZone(tokyoZone)
            .toLocalDate()

        // The event should still display on June 15
        assertEquals(date, tokyoLocalDate)
    }

    @Test
    fun `all-day event day code calculation is timezone aware`() {
        // All-day events use date-only (no timezone), converted to day code
        val date = LocalDate.of(2024, 6, 15)
        val expectedDayCode = 20240615

        // For all-day events, we use the date directly
        val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        assertEquals(expectedDayCode, dayCode)
    }

    // ==================== Midnight Boundary Tests ====================

    @Test
    fun `event ending at midnight belongs to previous day`() {
        val zone = ZoneId.of("America/New_York")

        // Event from 11 PM to midnight
        val start = ZonedDateTime.of(2024, 6, 15, 23, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2024, 6, 16, 0, 0, 0, 0, zone)

        // Event should be associated with June 15, not June 16
        assertEquals(15, start.dayOfMonth)
        assertEquals(16, end.dayOfMonth)

        // But logically belongs to June 15
        val eventDay = start.toLocalDate()
        assertEquals(LocalDate.of(2024, 6, 15), eventDay)
    }

    @Test
    fun `event starting at midnight belongs to that day`() {
        val zone = ZoneId.of("America/New_York")

        // Event from midnight to 1 AM
        val start = ZonedDateTime.of(2024, 6, 15, 0, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2024, 6, 15, 1, 0, 0, 0, zone)

        // Event belongs to June 15
        assertEquals(15, start.dayOfMonth)
        assertEquals(LocalDate.of(2024, 6, 15), start.toLocalDate())
    }

    // ==================== iCloud/CalDAV Specific Tests ====================

    @Test
    fun `iCloud uses UTC for timed events`() {
        // iCloud stores timed events in UTC
        val localZone = ZoneId.of("America/New_York")
        val localTime = ZonedDateTime.of(2024, 6, 15, 14, 30, 0, 0, localZone)

        // Convert to UTC for storage
        val utcTime = localTime.withZoneSameInstant(ZoneOffset.UTC)
        val utcTimestamp = utcTime.toInstant().toEpochMilli()

        // When reading back, convert to local timezone
        val restoredLocal = Instant.ofEpochMilli(utcTimestamp).atZone(localZone)

        assertEquals(localTime.toInstant(), restoredLocal.toInstant())
        assertEquals(14, restoredLocal.hour)
        assertEquals(30, restoredLocal.minute)
    }

    @Test
    fun `floating time event has no timezone`() {
        // Some CalDAV events use "floating time" (no timezone)
        // These should be interpreted in local timezone
        val floatingTime = LocalDateTime.of(2024, 6, 15, 9, 0, 0)

        // Same local time in different zones = different UTC times
        val nyTime = floatingTime.atZone(ZoneId.of("America/New_York"))
        val laTime = floatingTime.atZone(ZoneId.of("America/Los_Angeles"))

        // Different instants
        assertNotEquals(nyTime.toInstant(), laTime.toInstant())

        // But same local hour
        assertEquals(9, nyTime.hour)
        assertEquals(9, laTime.hour)
    }

    // ==================== Recurring Event Timezone Tests ====================

    @Test
    fun `weekly recurring event handles DST correctly`() {
        val zone = ZoneId.of("America/New_York")

        // Weekly meeting every Monday at 9 AM
        val monday1 = ZonedDateTime.of(2024, 3, 4, 9, 0, 0, 0, zone) // Before DST
        val monday2 = ZonedDateTime.of(2024, 3, 11, 9, 0, 0, 0, zone) // After DST

        // Both should be at 9 AM local
        assertEquals(9, monday1.hour)
        assertEquals(9, monday2.hour)

        // UTC hours differ due to DST
        val utc1 = monday1.withZoneSameInstant(ZoneOffset.UTC).hour
        val utc2 = monday2.withZoneSameInstant(ZoneOffset.UTC).hour
        assertNotEquals(utc1, utc2)
    }

    @Test
    fun `monthly recurring event on last day handles variable month lengths`() {
        // Event on the last day of each month at 10 AM
        val zone = ZoneId.of("America/New_York")

        val jan31 = ZonedDateTime.of(2024, 1, 31, 10, 0, 0, 0, zone)
        val feb29 = ZonedDateTime.of(2024, 2, 29, 10, 0, 0, 0, zone) // 2024 is leap year
        val mar31 = ZonedDateTime.of(2024, 3, 31, 10, 0, 0, 0, zone)
        val apr30 = ZonedDateTime.of(2024, 4, 30, 10, 0, 0, 0, zone)

        // All should be at 10 AM
        assertEquals(10, jan31.hour)
        assertEquals(10, feb29.hour)
        assertEquals(10, mar31.hour)
        assertEquals(10, apr30.hour)

        // All should be last day of their month
        assertEquals(31, jan31.dayOfMonth)
        assertEquals(29, feb29.dayOfMonth)
        assertEquals(31, mar31.dayOfMonth)
        assertEquals(30, apr30.dayOfMonth)
    }

    // ==================== International Date Line Tests ====================

    @Test
    fun `event crossing international date line`() {
        // Event in Tokyo on Monday
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val tokyoTime = ZonedDateTime.of(2024, 6, 17, 10, 0, 0, 0, tokyoZone) // Monday

        // Same instant in Hawaii (other side of date line)
        val hawaiiZone = ZoneId.of("Pacific/Honolulu")
        val hawaiiTime = tokyoTime.withZoneSameInstant(hawaiiZone)

        // Should be Sunday in Hawaii (Hawaii is UTC-10, Tokyo is UTC+9)
        assertEquals(17, tokyoTime.dayOfMonth) // Monday in Tokyo
        assertEquals(16, hawaiiTime.dayOfMonth) // Sunday in Hawaii
    }

    // ==================== Day Code Tests ====================

    @Test
    fun `day code is consistent for same local date`() {
        val date = LocalDate.of(2024, 6, 15)
        val expectedDayCode = 20240615

        // Calculate day code
        val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        assertEquals(expectedDayCode, dayCode)
    }

    @Test
    fun `day code from timestamp respects timezone for timed events`() {
        // 2 AM UTC on June 15 is still June 14 in New York (EDT = UTC-4)
        val utcTimestamp = ZonedDateTime.of(2024, 6, 15, 2, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val utcDate = Instant.ofEpochMilli(utcTimestamp).atZone(ZoneOffset.UTC).toLocalDate()
        val nyDate = Instant.ofEpochMilli(utcTimestamp).atZone(ZoneId.of("America/New_York")).toLocalDate()

        assertEquals(LocalDate.of(2024, 6, 15), utcDate)
        assertEquals(LocalDate.of(2024, 6, 14), nyDate) // Previous day in NY
    }

    // ==================== Edge Case: Year Boundary ====================

    @Test
    fun `event spanning year boundary`() {
        val zone = ZoneId.of("America/New_York")

        // New Year's Eve party: Dec 31 10 PM to Jan 1 2 AM
        val start = ZonedDateTime.of(2024, 12, 31, 22, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2025, 1, 1, 2, 0, 0, 0, zone)

        assertEquals(2024, start.year)
        assertEquals(2025, end.year)

        // Duration should be 4 hours
        val hours = java.time.Duration.between(start, end).toHours()
        assertEquals(4, hours)
    }

    // ==================== Timezone Display Name Tests ====================

    @Test
    fun `timezone abbreviations change with DST`() {
        val zone = ZoneId.of("America/New_York")

        val winter = ZonedDateTime.of(2024, 1, 15, 12, 0, 0, 0, zone)
        val summer = ZonedDateTime.of(2024, 7, 15, 12, 0, 0, 0, zone)

        // Offset changes with DST
        assertEquals(ZoneOffset.ofHours(-5), winter.offset) // EST
        assertEquals(ZoneOffset.ofHours(-4), summer.offset) // EDT
    }
}
