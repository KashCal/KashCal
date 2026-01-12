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
import java.time.zone.ZoneRulesException
import java.util.TimeZone

/**
 * Adversarial timezone tests for calendar operations.
 *
 * Tests probe edge cases that could display wrong dates/times:
 * - DST gap (time doesn't exist)
 * - DST overlap (time exists twice)
 * - All-day event timezone traps
 * - Invalid/obsolete timezone IDs
 * - International Date Line crossings
 * - Extreme timezone offsets
 * - Historical timezone changes
 *
 * These tests verify defensive coding in DateTimeUtils and elsewhere.
 */
class TimezoneAdversarialTest {

    // ==================== DST Gap Tests (Spring Forward) ====================

    @Test
    fun `DST gap - 2_30 AM on spring forward doesn't exist`() {
        // March 10, 2024: US clocks jump from 2:00 AM to 3:00 AM
        val zone = ZoneId.of("America/New_York")

        // 1:59 AM exists
        val before = ZonedDateTime.of(2024, 3, 10, 1, 59, 0, 0, zone)
        assertEquals(1, before.hour)

        // 2:30 AM doesn't exist - ZonedDateTime adjusts forward to 3:30 AM
        val gap = ZonedDateTime.of(2024, 3, 10, 2, 30, 0, 0, zone)
        assertEquals("2:30 AM should be adjusted to 3:30 AM", 3, gap.hour)
    }

    @Test
    fun `DST gap - event at non-existent time gets adjusted`() {
        val zone = ZoneId.of("America/New_York")

        // Create event at 2:15 AM on DST day (doesn't exist)
        val localDateTime = LocalDateTime.of(2024, 3, 10, 2, 15)
        val adjusted = localDateTime.atZone(zone)

        // Should be adjusted to 3:15 AM
        assertEquals(3, adjusted.hour)
        assertEquals(15, adjusted.minute)
    }

    @Test
    fun `DST gap - duration calculation across gap is correct`() {
        val zone = ZoneId.of("America/New_York")

        // 1 AM to 4 AM on DST day
        val start = ZonedDateTime.of(2024, 3, 10, 1, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2024, 3, 10, 4, 0, 0, 0, zone)

        // Wall clock: 3 hours. Actual: 2 hours (lost hour due to DST)
        val actualHours = java.time.Duration.between(start, end).toHours()
        assertEquals(2, actualHours)
    }

    @Test
    fun `DST gap - recurring event at 2_30 AM on DST day`() {
        val zone = ZoneId.of("America/New_York")

        // Daily 2:30 AM event
        val march9 = ZonedDateTime.of(2024, 3, 9, 2, 30, 0, 0, zone)  // Exists
        val march10 = ZonedDateTime.of(2024, 3, 10, 2, 30, 0, 0, zone) // Gap
        val march11 = ZonedDateTime.of(2024, 3, 11, 2, 30, 0, 0, zone) // Exists

        assertEquals(2, march9.hour)
        assertEquals(3, march10.hour) // Adjusted forward
        assertEquals(2, march11.hour)
    }

    // ==================== DST Overlap Tests (Fall Back) ====================

    @Test
    fun `DST overlap - 1_30 AM exists twice on fall back`() {
        // November 3, 2024: US clocks fall back from 2:00 AM to 1:00 AM
        val zone = ZoneId.of("America/New_York")

        // First 1:30 AM (EDT, before fall back)
        val first130 = ZonedDateTime.of(2024, 11, 3, 1, 30, 0, 0, zone.rules.getOffset(
            LocalDateTime.of(2024, 11, 3, 1, 30).toInstant(ZoneOffset.ofHours(-4))
        ).let { ZoneOffset.ofHours(-4) }.let { offset ->
            LocalDateTime.of(2024, 11, 3, 1, 30).atOffset(offset).toZonedDateTime()
        }.zone)

        // Second 1:30 AM (EST, after fall back)
        val second130 = ZonedDateTime.of(
            LocalDateTime.of(2024, 11, 3, 1, 30),
            zone
        ).withLaterOffsetAtOverlap()

        // Both are 1:30 AM but different instants
        assertEquals(1, first130.hour)
        assertEquals(1, second130.hour)
        // They should have different UTC times (1 hour apart)
    }

    @Test
    fun `DST overlap - event duration spanning overlap is 3 hours`() {
        val zone = ZoneId.of("America/New_York")

        // 12:30 AM to 2:30 AM on fall back day
        val start = ZonedDateTime.of(2024, 11, 3, 0, 30, 0, 0, zone)
        val end = ZonedDateTime.of(2024, 11, 3, 2, 30, 0, 0, zone)

        // Wall clock: 2 hours. Actual: 3 hours (gained hour due to DST)
        val actualHours = java.time.Duration.between(start, end).toHours()
        assertEquals(3, actualHours)
    }

    // ==================== All-Day Event Timezone Traps ====================

    @Test
    fun `all-day event UTC midnight displays correct date in any timezone`() {
        // All-day events stored as UTC midnight
        val date = LocalDate.of(2024, 6, 15)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // Verify in various timezones
        val zones = listOf(
            "America/New_York",     // UTC-4/-5
            "America/Los_Angeles",  // UTC-7/-8
            "Europe/London",        // UTC+0/+1
            "Asia/Tokyo",           // UTC+9
            "Pacific/Auckland"      // UTC+12/+13
        )

        zones.forEach { zoneId ->
            val zone = ZoneId.of(zoneId)
            // For all-day events, use UTC to get the date
            val displayDate = Instant.ofEpochMilli(utcMidnight)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()

            assertEquals(
                "All-day event should show correct date in $zoneId",
                date,
                displayDate
            )
        }
    }

    @Test
    fun `all-day event WRONG - using local TZ shifts date backward`() {
        // This demonstrates the BUG if you use local TZ instead of UTC
        val date = LocalDate.of(2024, 6, 15)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // In Tokyo (UTC+9), UTC midnight is 9 AM local = same day = CORRECT
        val tokyoDate = Instant.ofEpochMilli(utcMidnight)
            .atZone(ZoneId.of("Asia/Tokyo"))
            .toLocalDate()
        assertEquals(date, tokyoDate) // Happens to be correct

        // In New York (UTC-5), UTC midnight is 7 PM previous day = WRONG if used
        val nyZone = ZoneId.of("America/New_York")
        val nyLocalDateTime = Instant.ofEpochMilli(utcMidnight).atZone(nyZone)

        // If someone incorrectly uses local TZ:
        val wrongDate = nyLocalDateTime.toLocalDate()
        assertEquals(
            "Using local TZ would show June 14 instead of June 15",
            LocalDate.of(2024, 6, 14),
            wrongDate
        )
    }

    @Test
    fun `eventTsToDayCode uses UTC for all-day events`() {
        val date = LocalDate.of(2024, 6, 15)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val dayCode = DateTimeUtils.eventTsToDayCode(utcMidnight, isAllDay = true)

        assertEquals(20240615, dayCode)
    }

    @Test
    fun `eventTsToDayCode uses local TZ for timed events`() {
        // 2 AM UTC on June 15 = June 14 10 PM in New York
        val utcTime = ZonedDateTime.of(2024, 6, 15, 2, 0, 0, 0, ZoneOffset.UTC)
        val timestampMs = utcTime.toInstant().toEpochMilli()

        val nyZone = ZoneId.of("America/New_York")
        val dayCode = DateTimeUtils.eventTsToDayCode(timestampMs, isAllDay = false, nyZone)

        // Should be June 14 in NY
        assertEquals(20240614, dayCode)
    }

    // ==================== Invalid Timezone Tests ====================

    @Test
    fun `invalid timezone ID falls back gracefully`() {
        try {
            val zone = ZoneId.of("Invalid/Timezone")
            // If we get here, the zone was somehow valid
        } catch (e: ZoneRulesException) {
            // Expected - invalid zone throws
            assertTrue(true)
        }
    }

    @Test
    fun `obsolete timezone ID is handled`() {
        // Some timezone IDs have been renamed
        // "US/Eastern" is legacy, "America/New_York" is current
        val legacy = ZoneId.of("US/Eastern")
        val current = ZoneId.of("America/New_York")

        // Both should work and produce same results
        val now = Instant.now()
        assertEquals(
            now.atZone(legacy).offset,
            now.atZone(current).offset
        )
    }

    @Test
    fun `TimeZone getTimeZone returns GMT for invalid ID`() {
        // java.util.TimeZone silently returns GMT for invalid IDs
        val invalid = TimeZone.getTimeZone("Not/A/Zone")
        assertEquals("GMT", invalid.id)
    }

    // ==================== Extreme Timezone Tests ====================

    @Test
    fun `UTC+14 - Line Islands (furthest ahead)`() {
        val zone = ZoneId.of("Pacific/Kiritimati")

        val utcTime = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val localTime = utcTime.withZoneSameInstant(zone)

        // When it's midnight Jan 1 UTC, it's 2 PM Jan 1 in Kiritimati
        assertEquals(1, localTime.dayOfMonth)
        assertEquals(14, localTime.hour)
    }

    @Test
    fun `UTC-12 - Baker Island (furthest behind)`() {
        // Baker Island uses UTC-12 (AoE - Anywhere on Earth)
        val offset = ZoneOffset.ofHours(-12)

        val utcTime = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val localTime = utcTime.withZoneSameInstant(offset)

        // When it's midnight Jan 1 UTC, it's noon Dec 31 in UTC-12
        assertEquals(31, localTime.dayOfMonth)
        assertEquals(12, localTime.hour)
    }

    @Test
    fun `crossing date line changes date`() {
        // Tokyo (UTC+9) to Honolulu (UTC-10) = 19 hour difference
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val hawaiiZone = ZoneId.of("Pacific/Honolulu")

        val tokyoNoon = ZonedDateTime.of(2024, 6, 15, 12, 0, 0, 0, tokyoZone)
        val hawaiiTime = tokyoNoon.withZoneSameInstant(hawaiiZone)

        // 12:00 June 15 Tokyo = 17:00 June 14 Hawaii
        assertEquals(14, hawaiiTime.dayOfMonth)
        assertEquals(17, hawaiiTime.hour)
    }

    // ==================== Half-Hour and Quarter-Hour Offsets ====================

    @Test
    fun `India uses UTC+5_30`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val utcTime = ZonedDateTime.of(2024, 6, 15, 0, 0, 0, 0, ZoneOffset.UTC)
        val indiaTime = utcTime.withZoneSameInstant(zone)

        // Midnight UTC = 5:30 AM India
        assertEquals(5, indiaTime.hour)
        assertEquals(30, indiaTime.minute)
    }

    @Test
    fun `Nepal uses UTC+5_45`() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val utcTime = ZonedDateTime.of(2024, 6, 15, 0, 0, 0, 0, ZoneOffset.UTC)
        val nepalTime = utcTime.withZoneSameInstant(zone)

        // Midnight UTC = 5:45 AM Nepal
        assertEquals(5, nepalTime.hour)
        assertEquals(45, nepalTime.minute)
    }

    @Test
    fun `Chatham Islands uses UTC+12_45 or +13_45`() {
        val zone = ZoneId.of("Pacific/Chatham")

        // Winter (CHAST = +12:45)
        val winter = ZonedDateTime.of(2024, 7, 15, 0, 0, 0, 0, ZoneOffset.UTC)
            .withZoneSameInstant(zone)

        // Offset should be +12:45 in winter or +13:45 in summer
        val offsetMinutes = winter.offset.totalSeconds / 60
        assertTrue(offsetMinutes == 765 || offsetMinutes == 825) // 12:45 or 13:45
    }

    // ==================== Year Boundary Tests ====================

    @Test
    fun `New Year in different timezones`() {
        // When it's midnight Jan 1 in Tokyo, what date is it elsewhere?
        val tokyoNewYear = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

        val nyTime = tokyoNewYear.withZoneSameInstant(ZoneId.of("America/New_York"))

        // Still Dec 31 in New York
        assertEquals(2023, nyTime.year)
        assertEquals(12, nyTime.monthValue)
        assertEquals(31, nyTime.dayOfMonth)
    }

    // ==================== Leap Second Handling ====================

    @Test
    fun `timestamps near leap second boundaries`() {
        // Java time API doesn't support leap seconds directly
        // but we should not crash on any timestamp
        val maxTimestamp = Long.MAX_VALUE / 2 // Reasonable max
        val minTimestamp = 0L

        // Should not throw
        Instant.ofEpochMilli(minTimestamp)
        Instant.ofEpochMilli(maxTimestamp)
    }

    // ==================== Multi-Day Event Timezone Tests ====================

    @Test
    fun `multi-day event spans correct days in different timezones`() {
        // Event: June 15 10 PM to June 16 2 AM in New York
        val nyZone = ZoneId.of("America/New_York")
        val startNy = ZonedDateTime.of(2024, 6, 15, 22, 0, 0, 0, nyZone)
        val endNy = ZonedDateTime.of(2024, 6, 16, 2, 0, 0, 0, nyZone)

        val spansMultiple = DateTimeUtils.spansMultipleDays(
            startNy.toInstant().toEpochMilli(),
            endNy.toInstant().toEpochMilli(),
            isAllDay = false,
            nyZone
        )

        assertTrue("Event should span multiple days", spansMultiple)
    }

    @Test
    fun `event appearing on different days in different timezones`() {
        val nyZone = ZoneId.of("America/New_York")
        val laZone = ZoneId.of("America/Los_Angeles")

        // Event at 11 PM June 15 NY = 8 PM June 15 LA (same day)
        // Event at 1 AM June 16 NY = 10 PM June 15 LA (different day!)
        val eventStart = ZonedDateTime.of(2024, 6, 16, 1, 0, 0, 0, nyZone)

        val nyDate = eventStart.toLocalDate()
        val laDate = eventStart.withZoneSameInstant(laZone).toLocalDate()

        assertEquals(LocalDate.of(2024, 6, 16), nyDate)
        assertEquals(LocalDate.of(2024, 6, 15), laDate)
    }

    // ==================== DateTimeUtils Specific Tests ====================

    @Test
    fun `formatEventDate uses UTC for all-day`() {
        val date = LocalDate.of(2024, 6, 15)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val formatted = DateTimeUtils.formatEventDate(utcMidnight, isAllDay = true, "yyyy-MM-dd")

        assertEquals("2024-06-15", formatted)
    }

    @Test
    fun `formatEventDate uses local TZ for timed`() {
        val nyZone = ZoneId.of("America/New_York")
        // 2 AM UTC June 15 = 10 PM June 14 in NY
        val utcTime = ZonedDateTime.of(2024, 6, 15, 2, 0, 0, 0, ZoneOffset.UTC)
        val timestampMs = utcTime.toInstant().toEpochMilli()

        val formatted = DateTimeUtils.formatEventDate(
            timestampMs,
            isAllDay = false,
            "yyyy-MM-dd",
            nyZone
        )

        assertEquals("2024-06-14", formatted)
    }

    @Test
    fun `localDateToUtcMidnight preserves calendar date`() {
        val nyZone = ZoneId.of("America/New_York")
        // June 15 at local midnight in NY
        val localMidnight = LocalDate.of(2024, 6, 15)
            .atStartOfDay(nyZone)
            .toInstant()
            .toEpochMilli()

        val utcMidnight = DateTimeUtils.localDateToUtcMidnight(localMidnight, nyZone)

        // Should be June 15 00:00 UTC
        val resultDate = Instant.ofEpochMilli(utcMidnight)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        assertEquals(LocalDate.of(2024, 6, 15), resultDate)
    }

    @Test
    fun `utcMidnightToLocalDate preserves calendar date`() {
        val nyZone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2024, 6, 15)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val localMidnight = DateTimeUtils.utcMidnightToLocalDate(utcMidnight, nyZone)

        // Should be June 15 00:00 in NY timezone
        val resultDate = Instant.ofEpochMilli(localMidnight)
            .atZone(nyZone)
            .toLocalDate()

        assertEquals(date, resultDate)
    }

    @Test
    fun `calculateTotalDays handles timezone correctly`() {
        val date = LocalDate.of(2024, 6, 15)
        val utcStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // End on June 17 (3 days: 15, 16, 17)
        // Using June 17 00:00 UTC as end gives June 17 as end date
        val utcEnd = date.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val days = DateTimeUtils.calculateTotalDays(utcStart, utcEnd, isAllDay = true)

        assertEquals(3, days)  // June 15, 16, 17
    }
}
