package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Timezone-specific tests for VALUE=DATE handling.
 *
 * RFC 5545: DATE values are calendar dates without time zone.
 * These tests verify that VALUE=DATE produces consistent results
 * regardless of system timezone.
 *
 * Key invariant: "20260123" should always represent Jan 23, 2026
 * as a calendar date, not a moment in time shifted by timezone.
 */
@DisplayName("ICalDateTime VALUE=DATE Timezone Tests")
class ICalDateTimeTimezoneTest {

    // Jan 23, 2026 00:00:00 UTC in milliseconds
    // Calculated: LocalDate.of(2026, 1, 23).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val jan23_2026_utc_midnight = LocalDate.of(2026, 1, 23)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    @Test
    @DisplayName("VALUE=DATE should produce UTC midnight timestamp")
    fun `parse DATE produces UTC midnight timestamp`() {
        val dt = ICalDateTime.parse("20260123")

        // The timestamp should be UTC midnight for Jan 23
        val expectedUtcMidnight = jan23_2026_utc_midnight
        assertEquals(expectedUtcMidnight, dt.timestamp,
            "DATE value should be stored as UTC midnight. " +
            "Expected: $expectedUtcMidnight (${Instant.ofEpochMilli(expectedUtcMidnight)}), " +
            "Got: ${dt.timestamp} (${Instant.ofEpochMilli(dt.timestamp)})")
    }

    @Test
    @DisplayName("VALUE=DATE should be marked as UTC (isUtc=true)")
    fun `parse DATE sets isUtc true`() {
        val dt = ICalDateTime.parse("20260123")

        assertTrue(dt.isUtc,
            "DATE values should be stored as UTC (isUtc=true) since they represent " +
            "calendar dates, not moments in local time")
    }

    @Test
    @DisplayName("VALUE=DATE should have null timezone (treated as UTC)")
    fun `parse DATE has null timezone`() {
        val dt = ICalDateTime.parse("20260123")

        assertNull(dt.timezone,
            "DATE values should have null timezone (UTC) to ensure consistent " +
            "calendar date across all devices regardless of local timezone")
    }

    @Test
    @DisplayName("VALUE=DATE toLocalDate should return calendar date regardless of stored TZ")
    fun `toLocalDate returns correct calendar date for DATE`() {
        val dt = ICalDateTime.parse("20260123")
        val localDate = dt.toLocalDate()

        assertEquals(LocalDate.of(2026, 1, 23), localDate,
            "toLocalDate() should return the calendar date from the ICS, not shifted by timezone")
    }

    @Test
    @DisplayName("VALUE=DATE toDayCode should return original date string")
    fun `toDayCode returns original date for DATE`() {
        val dt = ICalDateTime.parse("20260123")
        assertEquals("20260123", dt.toDayCode(),
            "toDayCode() should return the original calendar date")
    }

    @Test
    @DisplayName("VALUE=DATE toICalString should return original date")
    fun `toICalString preserves DATE value`() {
        val dt = ICalDateTime.parse("20260123")
        assertEquals("20260123", dt.toICalString(),
            "toICalString() should return the original DATE value for round-trip")
    }

    @Test
    @DisplayName("VALUE=DATE round-trip should preserve exact date")
    fun `round trip preserves DATE exactly`() {
        val original = "20260123"
        val dt = ICalDateTime.parse(original)
        val output = dt.toICalString()
        assertEquals(original, output, "DATE should survive round-trip exactly")
    }

    @Test
    @DisplayName("Multi-day all-day event: DTEND exclusive should work correctly")
    fun `multi-day all-day event DTEND is exclusive`() {
        // 2-day event: Jan 23-24 (DTEND=25 is exclusive per RFC 5545)
        val dtStart = ICalDateTime.parse("20260123")
        val dtEnd = ICalDateTime.parse("20260125")

        val startDate = dtStart.toLocalDate()
        val endDate = dtEnd.toLocalDate()

        assertEquals(LocalDate.of(2026, 1, 23), startDate)
        assertEquals(LocalDate.of(2026, 1, 25), endDate)  // Exclusive end

        // Duration calculation: Jan 23, 24 = 2 days
        // (Application code subtracts 1 day from exclusive end)
    }

    @Test
    @DisplayName("EXDATE with VALUE=DATE should use UTC")
    fun `EXDATE DATE produces UTC timestamp`() {
        // EXDATE with VALUE=DATE should produce same UTC-based timestamp
        val exdate = ICalDateTime.parse("20260123")
        assertEquals(jan23_2026_utc_midnight, exdate.timestamp,
            "EXDATE DATE should be UTC midnight")
    }

    @Test
    @DisplayName("RECURRENCE-ID with VALUE=DATE should use UTC")
    fun `RECURRENCE_ID DATE produces UTC timestamp`() {
        val recurrenceId = ICalDateTime.parse("20260123")
        assertEquals(jan23_2026_utc_midnight, recurrenceId.timestamp,
            "RECURRENCE-ID DATE should be UTC midnight")
    }

    @Test
    @DisplayName("Different dates should produce different UTC timestamps")
    fun `different dates produce different timestamps`() {
        val jan23 = ICalDateTime.parse("20260123")
        val jan24 = ICalDateTime.parse("20260124")

        val expectedDiff = 24 * 60 * 60 * 1000L  // 1 day in ms
        assertEquals(expectedDiff, jan24.timestamp - jan23.timestamp,
            "Consecutive dates should differ by exactly 24 hours")
    }

    @Test
    @DisplayName("Year boundary dates work correctly")
    fun `year boundary dates work correctly`() {
        val dec31 = ICalDateTime.parse("20251231")
        val jan01 = ICalDateTime.parse("20260101")

        assertEquals(LocalDate.of(2025, 12, 31), dec31.toLocalDate())
        assertEquals(LocalDate.of(2026, 1, 1), jan01.toLocalDate())

        val expectedDiff = 24 * 60 * 60 * 1000L
        assertEquals(expectedDiff, jan01.timestamp - dec31.timestamp)
    }

    @Test
    @DisplayName("Leap year date works correctly")
    fun `leap year Feb 29 works correctly`() {
        val feb29 = ICalDateTime.parse("20240229")  // 2024 is leap year
        assertEquals(LocalDate.of(2024, 2, 29), feb29.toLocalDate())
        assertEquals("20240229", feb29.toDayCode())
        assertEquals("20240229", feb29.toICalString())
    }

    @Test
    @DisplayName("fromLocalDate should produce UTC midnight for all-day events")
    fun `fromLocalDate produces UTC for date operations`() {
        val date = LocalDate.of(2026, 1, 23)
        // Note: fromLocalDate currently takes timezone param, but for DATE values
        // the result should be usable for UTC-based operations
        val dt = ICalDateTime.fromLocalDate(date, ZoneOffset.UTC)

        assertEquals(jan23_2026_utc_midnight, dt.timestamp,
            "fromLocalDate with UTC should produce UTC midnight")
        assertTrue(dt.isDate)
    }

    @Test
    @DisplayName("Consistency: parse and fromLocalDate(UTC) should match")
    fun `parse and fromLocalDate UTC produce same timestamp`() {
        val date = LocalDate.of(2026, 1, 23)
        val fromParse = ICalDateTime.parse("20260123")
        val fromLocalDate = ICalDateTime.fromLocalDate(date, ZoneOffset.UTC)

        assertEquals(fromLocalDate.timestamp, fromParse.timestamp,
            "parse(DATE) and fromLocalDate(UTC) should produce same timestamp")
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Very old date parses correctly")
    fun `old date parses correctly`() {
        val dt = ICalDateTime.parse("19700101")  // Unix epoch
        assertEquals(LocalDate.of(1970, 1, 1), dt.toLocalDate())
        assertEquals("19700101", dt.toDayCode())
    }

    @Test
    @DisplayName("Far future date parses correctly")
    fun `future date parses correctly`() {
        val dt = ICalDateTime.parse("21001231")
        assertEquals(LocalDate.of(2100, 12, 31), dt.toLocalDate())
        assertEquals("21001231", dt.toDayCode())
    }

    // ========== fromLocalDate() Factory Method Tests ==========

    @Test
    @DisplayName("fromLocalDate with UTC+12 should preserve calendar date")
    fun `fromLocalDate UTC+12 preserves calendar date`() {
        // Timezone ahead of UTC: Jan 23 00:00 UTC+12 = Jan 22 12:00 UTC
        // toLocalDate() should still return Jan 23 (the intended date)
        val date = LocalDate.of(2026, 1, 23)
        val utcPlus12 = ZoneId.of("Pacific/Auckland")  // UTC+12/+13
        val dt = ICalDateTime.fromLocalDate(date, utcPlus12)

        assertTrue(dt.isDate, "fromLocalDate should set isDate=true")
        assertEquals(LocalDate.of(2026, 1, 23), dt.toLocalDate(),
            "toLocalDate() should return the intended calendar date Jan 23, " +
            "not the UTC-shifted date. For all-day events, the calendar date " +
            "is more important than the exact UTC moment.")
        assertEquals("20260123", dt.toDayCode(),
            "toDayCode() should return intended date regardless of timezone")
        assertEquals("20260123", dt.toICalString(),
            "toICalString() should return intended date for ICS output")
    }

    @Test
    @DisplayName("fromLocalDate with UTC-10 should preserve calendar date")
    fun `fromLocalDate UTC-10 preserves calendar date`() {
        // Timezone behind UTC: Jan 23 00:00 UTC-10 = Jan 23 10:00 UTC
        // toLocalDate() should still return Jan 23
        val date = LocalDate.of(2026, 1, 23)
        val utcMinus10 = ZoneId.of("Pacific/Honolulu")  // UTC-10
        val dt = ICalDateTime.fromLocalDate(date, utcMinus10)

        assertTrue(dt.isDate)
        assertEquals(LocalDate.of(2026, 1, 23), dt.toLocalDate(),
            "toLocalDate() should return Jan 23 regardless of negative offset")
        assertEquals("20260123", dt.toDayCode())
        assertEquals("20260123", dt.toICalString())
    }

    @Test
    @DisplayName("fromLocalDate round-trip should preserve date for any timezone")
    fun `fromLocalDate round trip preserves date for all timezones`() {
        val date = LocalDate.of(2026, 1, 23)
        val timezones = listOf(
            ZoneOffset.UTC,
            ZoneId.of("America/New_York"),     // UTC-5
            ZoneId.of("America/Los_Angeles"),  // UTC-8
            ZoneId.of("Pacific/Honolulu"),     // UTC-10
            ZoneId.of("Europe/London"),        // UTC+0/+1
            ZoneId.of("Europe/Paris"),         // UTC+1/+2
            ZoneId.of("Asia/Tokyo"),           // UTC+9
            ZoneId.of("Pacific/Auckland")      // UTC+12/+13
        )

        for (tz in timezones) {
            val dt = ICalDateTime.fromLocalDate(date, tz)
            assertEquals(date, dt.toLocalDate(),
                "fromLocalDate($date, $tz).toLocalDate() should return $date")
            assertEquals("20260123", dt.toICalString(),
                "fromLocalDate($date, $tz).toICalString() should return 20260123")
        }
    }

    // ========== fromTimestamp() with isDate=true Tests ==========

    @Test
    @DisplayName("fromTimestamp with UTC midnight should produce correct DATE")
    fun `fromTimestamp UTC midnight produces correct DATE`() {
        // This is how KashCal creates all-day events
        val utcMidnight = LocalDate.of(2026, 1, 23)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val dt = ICalDateTime.fromTimestamp(utcMidnight, null, isDate = true)

        assertEquals(LocalDate.of(2026, 1, 23), dt.toLocalDate())
        assertEquals("20260123", dt.toICalString())
    }

    @Test
    @DisplayName("fromTimestamp with non-UTC midnight should still work for DATE")
    fun `fromTimestamp non-UTC midnight works for DATE`() {
        // If someone passes a local midnight timestamp, the result depends
        // on how toLocalDate() interprets it. For DATE values, we use UTC.
        // So passing local Jan 23 00:00 EST (Jan 23 05:00 UTC) should give Jan 23.
        val estMidnight = LocalDate.of(2026, 1, 23)
            .atStartOfDay(ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

        val dt = ICalDateTime.fromTimestamp(estMidnight, ZoneId.of("America/New_York"), isDate = true)

        // toLocalDate() uses UTC for isDate=true, so Jan 23 05:00 UTC → Jan 23
        assertEquals(LocalDate.of(2026, 1, 23), dt.toLocalDate(),
            "For DATE values, toLocalDate() should use UTC. " +
            "Jan 23 00:00 EST = Jan 23 05:00 UTC → Jan 23 in UTC")
        assertEquals("20260123", dt.toICalString())
    }

    // ========== fromZonedDateTime() with isDate=true Tests ==========

    @Test
    @DisplayName("fromZonedDateTime preserves date for all-day events")
    fun `fromZonedDateTime preserves date for DATE`() {
        // Create a ZonedDateTime in UTC+12
        val zdt = LocalDate.of(2026, 1, 23)
            .atStartOfDay(ZoneId.of("Pacific/Auckland"))

        val dt = ICalDateTime.fromZonedDateTime(zdt, isDate = true)

        assertTrue(dt.isDate)
        assertEquals(LocalDate.of(2026, 1, 23), dt.toLocalDate(),
            "fromZonedDateTime should preserve the calendar date, not the UTC moment")
        assertEquals("20260123", dt.toICalString())
    }
}
