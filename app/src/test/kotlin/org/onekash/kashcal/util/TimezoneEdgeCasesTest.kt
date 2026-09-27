package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pins the java.time zone arithmetic calendar code relies on; no test calls app code.
 *
 * Covers DST durations and offsets, one instant shown in several zones, all-day dates at UTC
 * midnight, day codes, midnight boundaries, a UTC round-trip, floating time, fixed wall times
 * on dates either side of a DST change or at month ends (no recurrence is expanded), the date
 * line and the year boundary.
 */
class TimezoneEdgeCasesTest {

    // ==================== DST Transition Tests ====================

    @Test
    fun `event during spring forward DST transition`() {
        // March 10, 2024: 2:00 AM EST becomes 3:00 AM EDT.
        val zone = ZoneId.of("America/New_York")

        val beforeDst = ZonedDateTime.of(2024, 3, 10, 1, 30, 0, 0, zone)
        val afterDst = ZonedDateTime.of(2024, 3, 10, 3, 30, 0, 0, zone)

        // 1:30 AM to 3:30 AM spans the skipped hour: 1 hour elapsed, not 2.
        val durationHours = java.time.Duration.between(beforeDst, afterDst).toHours()
        assertEquals(1, durationHours)
    }

    @Test
    fun `event during fall back DST transition`() {
        // November 3, 2024: 2:00 AM EDT becomes 1:00 AM EST.
        val zone = ZoneId.of("America/New_York")

        val beforeFallback = ZonedDateTime.of(2024, 11, 3, 0, 30, 0, 0, zone)
        val afterFallback = ZonedDateTime.of(2024, 11, 3, 2, 30, 0, 0, zone)

        // 12:30 AM to 2:30 AM repeats an hour: 3 hours elapsed.
        val durationHours = java.time.Duration.between(beforeFallback, afterFallback).toHours()
        assertEquals(3, durationHours)
    }

    @Test
    fun `recurring event time stays consistent across DST`() {
        // 9 AM local on either side of the DST change.
        val zone = ZoneId.of("America/New_York")

        val beforeDst = ZonedDateTime.of(2024, 3, 9, 9, 0, 0, 0, zone)
        val afterDst = ZonedDateTime.of(2024, 3, 11, 9, 0, 0, 0, zone)

        assertEquals(9, beforeDst.hour)
        assertEquals(9, afterDst.hour)

        // The UTC hours differ by 1.
        val beforeUtcHour = beforeDst.withZoneSameInstant(ZoneOffset.UTC).hour
        val afterUtcHour = afterDst.withZoneSameInstant(ZoneOffset.UTC).hour
        assertEquals(1, beforeUtcHour - afterUtcHour) // EST is UTC-5, EDT is UTC-4
    }

    // ==================== Cross-Timezone Tests ====================

    @Test
    fun `event created in one timezone displayed in another`() {
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val tokyoTime = ZonedDateTime.of(2024, 6, 15, 10, 0, 0, 0, tokyoZone)

        val nyZone = ZoneId.of("America/New_York")
        val nyTime = tokyoTime.withZoneSameInstant(nyZone)

        // 10 AM June 15 in Tokyo (UTC+9) is 9 PM June 14 in NY (EDT, UTC-4), 13 hours apart.
        assertEquals(15, tokyoTime.dayOfMonth)
        assertEquals(14, nyTime.dayOfMonth)
        assertEquals(21, nyTime.hour)
    }

    @Test
    fun `event spans multiple days in different timezone`() {
        val laZone = ZoneId.of("America/Los_Angeles")
        val londonZone = ZoneId.of("Europe/London")

        val laTime = ZonedDateTime.of(2024, 6, 15, 23, 0, 0, 0, laZone)

        // 11 PM June 15 in LA (PDT, UTC-7) is 7 AM June 16 in London (BST, UTC+1).
        val londonTime = laTime.withZoneSameInstant(londonZone)

        assertEquals(16, londonTime.dayOfMonth)
        assertEquals(7, londonTime.hour)
    }

    @Test
    fun `UTC timestamp is timezone invariant`() {
        // 2024-06-15 08:00 UTC.
        val timestamp = 1718438400000L

        val nyTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("America/New_York"))
        val tokyoTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Tokyo"))
        val utcTime = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC)

        // One instant, different local hours.
        assertEquals(nyTime.toInstant(), tokyoTime.toInstant())
        assertEquals(tokyoTime.toInstant(), utcTime.toInstant())

        assertNotEquals(nyTime.hour, tokyoTime.hour)
    }

    // ==================== All-Day Event Tests ====================

    @Test
    fun `all-day event is date-based not time-based`() {
        val date = LocalDate.of(2024, 6, 15)

        // In CalDAV an all-day event is a DATE with no timezone. Its UTC day here runs from
        // midnight to 1 ms before the next midnight.
        val startOfDayUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endOfDayUtc = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

        assertEquals(86400000 - 1, endOfDayUtc - startOfDayUtc)
    }

    @Test
    fun `all-day event spans correct local day`() {
        // Only Tokyo is checked; a zone behind UTC would read June 14.
        val date = LocalDate.of(2024, 6, 15)

        // iCloud stores all-day events at midnight UTC
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // In Tokyo (UTC+9), midnight UTC is 9 AM local
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val tokyoLocalDate = Instant.ofEpochMilli(utcMidnight)
            .atZone(tokyoZone)
            .toLocalDate()

        assertEquals(date, tokyoLocalDate)
    }

    @Test
    fun `all-day event day code calculation is timezone aware`() {
        // Computes the day code from the date alone, with no zone and no app code.
        val date = LocalDate.of(2024, 6, 15)
        val expectedDayCode = 20240615

        val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        assertEquals(expectedDayCode, dayCode)
    }

    // ==================== Midnight Boundary Tests ====================

    @Test
    fun `event ending at midnight belongs to previous day`() {
        val zone = ZoneId.of("America/New_York")

        // 11 PM to midnight: the end's wall date is June 16, and the test takes the event's
        // day from its start. The app's end-day rule is [DateTimeUtils.eventTsToEndDayCode],
        // not called here.
        val start = ZonedDateTime.of(2024, 6, 15, 23, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2024, 6, 16, 0, 0, 0, 0, zone)

        assertEquals(15, start.dayOfMonth)
        assertEquals(16, end.dayOfMonth)

        val eventDay = start.toLocalDate()
        assertEquals(LocalDate.of(2024, 6, 15), eventDay)
    }

    @Test
    fun `event starting at midnight belongs to that day`() {
        val zone = ZoneId.of("America/New_York")

        val start = ZonedDateTime.of(2024, 6, 15, 0, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2024, 6, 15, 1, 0, 0, 0, zone)

        assertEquals(15, start.dayOfMonth)
        assertEquals(LocalDate.of(2024, 6, 15), start.toLocalDate())
    }

    // ==================== iCloud/CalDAV Specific Tests ====================

    @Test
    fun `iCloud uses UTC for timed events`() {
        // iCloud stores timed events in UTC
        val localZone = ZoneId.of("America/New_York")
        val localTime = ZonedDateTime.of(2024, 6, 15, 14, 30, 0, 0, localZone)

        val utcTime = localTime.withZoneSameInstant(ZoneOffset.UTC)
        val utcTimestamp = utcTime.toInstant().toEpochMilli()

        // Stored as UTC and read back in the local zone.
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

        // The same wall time in two zones is two instants.
        val nyTime = floatingTime.atZone(ZoneId.of("America/New_York"))
        val laTime = floatingTime.atZone(ZoneId.of("America/Los_Angeles"))

        assertNotEquals(nyTime.toInstant(), laTime.toInstant())

        assertEquals(9, nyTime.hour)
        assertEquals(9, laTime.hour)
    }

    // ==================== Recurring Event Timezone Tests ====================

    @Test
    fun `weekly recurring event handles DST correctly`() {
        val zone = ZoneId.of("America/New_York")

        // Mondays at 9 AM before and after the DST change: same local hour, different UTC hour.
        val monday1 = ZonedDateTime.of(2024, 3, 4, 9, 0, 0, 0, zone)
        val monday2 = ZonedDateTime.of(2024, 3, 11, 9, 0, 0, 0, zone)

        assertEquals(9, monday1.hour)
        assertEquals(9, monday2.hour)

        val utc1 = monday1.withZoneSameInstant(ZoneOffset.UTC).hour
        val utc2 = monday2.withZoneSameInstant(ZoneOffset.UTC).hour
        assertNotEquals(utc1, utc2)
    }

    @Test
    fun `monthly recurring event on last day handles variable month lengths`() {
        // The last day of four months at 10 AM, written out by hand; 2024 is a leap year.
        val zone = ZoneId.of("America/New_York")

        val jan31 = ZonedDateTime.of(2024, 1, 31, 10, 0, 0, 0, zone)
        val feb29 = ZonedDateTime.of(2024, 2, 29, 10, 0, 0, 0, zone)
        val mar31 = ZonedDateTime.of(2024, 3, 31, 10, 0, 0, 0, zone)
        val apr30 = ZonedDateTime.of(2024, 4, 30, 10, 0, 0, 0, zone)

        assertEquals(10, jan31.hour)
        assertEquals(10, feb29.hour)
        assertEquals(10, mar31.hour)
        assertEquals(10, apr30.hour)

        assertEquals(31, jan31.dayOfMonth)
        assertEquals(29, feb29.dayOfMonth)
        assertEquals(31, mar31.dayOfMonth)
        assertEquals(30, apr30.dayOfMonth)
    }

    // ==================== International Date Line Tests ====================

    @Test
    fun `event crossing international date line`() {
        // 10 AM Monday June 17 in Tokyo (UTC+9) is Sunday June 16 in Hawaii (UTC-10), across
        // the date line.
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val tokyoTime = ZonedDateTime.of(2024, 6, 17, 10, 0, 0, 0, tokyoZone)

        val hawaiiZone = ZoneId.of("Pacific/Honolulu")
        val hawaiiTime = tokyoTime.withZoneSameInstant(hawaiiZone)

        assertEquals(17, tokyoTime.dayOfMonth)
        assertEquals(16, hawaiiTime.dayOfMonth)
    }

    // ==================== Day Code Tests ====================

    @Test
    fun `day code is consistent for same local date`() {
        val date = LocalDate.of(2024, 6, 15)
        val expectedDayCode = 20240615

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
        assertEquals(LocalDate.of(2024, 6, 14), nyDate)
    }

    // ==================== Edge Case: Year Boundary ====================

    @Test
    fun `event spanning year boundary`() {
        val zone = ZoneId.of("America/New_York")

        // Dec 31 10 PM to Jan 1 2 AM: 4 hours.
        val start = ZonedDateTime.of(2024, 12, 31, 22, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2025, 1, 1, 2, 0, 0, 0, zone)

        assertEquals(2024, start.year)
        assertEquals(2025, end.year)

        val hours = java.time.Duration.between(start, end).toHours()
        assertEquals(4, hours)
    }

    // ==================== Timezone Display Name Tests ====================

    @Test
    fun `timezone abbreviations change with DST`() {
        val zone = ZoneId.of("America/New_York")

        val winter = ZonedDateTime.of(2024, 1, 15, 12, 0, 0, 0, zone)
        val summer = ZonedDateTime.of(2024, 7, 15, 12, 0, 0, 0, zone)

        // Only the offsets are asserted, not the abbreviations.
        assertEquals(ZoneOffset.ofHours(-5), winter.offset) // EST
        assertEquals(ZoneOffset.ofHours(-4), summer.offset) // EDT
    }
}
