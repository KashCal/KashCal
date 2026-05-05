package org.onekash.icaldav.model

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ICalDateTimeTest {

    @AfterEach
    fun tearDown() {
        ICalDateTime.customTimezoneResolver = null
    }

    @Test
    fun `parse UTC datetime`() {
        val dt = ICalDateTime.parse("20231215T140000Z")

        assertTrue(dt.isUtc)
        assertFalse(dt.isDate)

        val zdt = dt.toZonedDateTime()
        assertEquals(2023, zdt.year)
        assertEquals(12, zdt.monthValue)
        assertEquals(15, zdt.dayOfMonth)
        assertEquals(14, zdt.hour)
        assertEquals(0, zdt.minute)
    }

    @Test
    fun `parse local datetime with timezone`() {
        val dt = ICalDateTime.parse("20231215T090000", "America/New_York")

        assertEquals(ZoneId.of("America/New_York"), dt.timezone)
        assertFalse(dt.isDate)

        val zdt = dt.toZonedDateTime()
        assertEquals(9, zdt.hour)
    }

    @Test
    fun `parse all-day date`() {
        val dt = ICalDateTime.parse("20231215")

        assertTrue(dt.isDate)

        val zdt = dt.toZonedDateTime()
        assertEquals(2023, zdt.year)
        assertEquals(12, zdt.monthValue)
        assertEquals(15, zdt.dayOfMonth)
        assertEquals(0, zdt.hour)  // All-day starts at midnight
    }

    @Test
    fun `parse floating datetime`() {
        val dt = ICalDateTime.parse("20231215T140000")

        assertNotNull(dt.timezone)  // Uses system default
        assertFalse(dt.isDate)
        assertFalse(dt.isUtc)
    }

    @Test
    fun `toDayCode returns correct format`() {
        val dt = ICalDateTime.parse("20231215T140000Z")
        assertEquals("20231215", dt.toDayCode())

        val dt2 = ICalDateTime.parse("20240101")
        assertEquals("20240101", dt2.toDayCode())
    }

    @Test
    fun `toICalString for UTC datetime`() {
        val dt = ICalDateTime.parse("20231215T140000Z")
        assertEquals("20231215T140000Z", dt.toICalString())
    }

    @Test
    fun `toICalString for all-day date`() {
        val dt = ICalDateTime.parse("20231215")
        assertEquals("20231215", dt.toICalString())
    }

    @Test
    fun `fromZonedDateTime creates correct instance`() {
        val zdt = java.time.ZonedDateTime.of(2023, 12, 15, 14, 30, 0, 0, ZoneId.of("UTC"))
        val dt = ICalDateTime.fromZonedDateTime(zdt, false)

        assertEquals(ZoneId.of("UTC"), dt.timezone)
        assertFalse(dt.isDate)

        val roundTripped = dt.toZonedDateTime()
        assertEquals(zdt.toInstant().toEpochMilli(), roundTripped.toInstant().toEpochMilli())
    }

    @Test
    fun `fromZonedDateTime for all-day event`() {
        val zdt = java.time.ZonedDateTime.of(2023, 12, 15, 0, 0, 0, 0, ZoneId.systemDefault())
        val dt = ICalDateTime.fromZonedDateTime(zdt, true)

        assertTrue(dt.isDate)
        assertEquals("20231215", dt.toICalString())
    }

    @Test
    fun `timezone conversion preserves instant`() {
        // Parse UTC time
        val dt = ICalDateTime.parse("20231215T140000Z")
        val utcInstant = dt.timestamp

        // Create same instant in different timezone (should be same underlying time)
        val nyZdt = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(utcInstant),
            ZoneId.of("America/New_York")
        )
        val dtNY = ICalDateTime.fromZonedDateTime(nyZdt, false)

        // Timestamps should be equal
        assertEquals(dt.timestamp, dtNY.timestamp)
    }

    // ==================== Windows Timezone Resolution (Issue #45) ====================

    @Test
    fun `parse resolves standard IANA timezone`() {
        val dt = ICalDateTime.parse("20260115T140000", "America/New_York")
        // Jan 15 2026 is winter → EST (UTC-5), 2:00 PM EST = 19:00 UTC
        val expectedMs = LocalDateTime.of(2026, 1, 15, 19, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMs, dt.timestamp)
    }

    @Test
    fun `parse resolves Windows timezone via properties file`() {
        // "Eastern Standard Time" is a Windows timezone name, not a valid IANA ID
        val dt = ICalDateTime.parse("20260115T140000", "Eastern Standard Time")
        // Should resolve via msTimezoneNames → US/Eastern → UTC-5
        val expectedMs = LocalDateTime.of(2026, 1, 15, 19, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMs, dt.timestamp,
            "Eastern Standard Time should resolve to UTC-5, not system default")
    }

    @Test
    fun `parse resolves India Standard Time`() {
        val dt = ICalDateTime.parse("20260115T143000", "India Standard Time")
        // 2:30 PM IST (UTC+5:30) = 09:00 UTC
        val expectedMs = LocalDateTime.of(2026, 1, 15, 9, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMs, dt.timestamp,
            "India Standard Time should resolve to UTC+5:30")
    }

    @Test
    fun `parse resolves W Europe Standard Time`() {
        val dt = ICalDateTime.parse("20260115T140000", "W. Europe Standard Time")
        // Jan 15 2026 is winter → CET (UTC+1), 2:00 PM CET = 13:00 UTC
        val expectedMs = LocalDateTime.of(2026, 1, 15, 13, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMs, dt.timestamp,
            "W. Europe Standard Time should resolve to UTC+1")
    }

    @Test
    fun `parse falls back to system default for unknown timezone`() {
        val dt = ICalDateTime.parse("20260115T140000", "Nonexistent/Zone")
        // Should use system default — verify it doesn't throw
        assertNotNull(dt.timezone)
        assertEquals(ZoneId.systemDefault(), dt.timezone)
    }

    @Test
    fun `customTimezoneResolver is called before properties file`() {
        // Set a custom resolver that overrides "India Standard Time" → Asia/Tokyo
        ICalDateTime.customTimezoneResolver = { tzid ->
            if (tzid == "India Standard Time") ZoneId.of("Asia/Tokyo") else null
        }
        val dt = ICalDateTime.parse("20260115T140000", "India Standard Time")
        // 2:00 PM JST (UTC+9) = 05:00 UTC (custom resolver wins over properties file)
        val expectedMs = LocalDateTime.of(2026, 1, 15, 5, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMs, dt.timestamp,
            "Custom resolver should take priority over properties file")
    }

    @Test
    fun `customTimezoneResolver returning null falls through to properties file`() {
        // Resolver returns null for everything — properties file should handle it
        ICalDateTime.customTimezoneResolver = { null }
        val dt = ICalDateTime.parse("20260115T140000", "India Standard Time")
        // 2:30 PM IST test with 2:00 PM input → check UTC+5:30 conversion
        val expectedMs = LocalDateTime.of(2026, 1, 15, 8, 30)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedMs, dt.timestamp,
            "Should fall through to properties file when resolver returns null")
    }

    @Test
    fun `timezoneAliases map loads entries from classpath`() {
        // ical4j's msTimezoneNames has ~99 entries, ~5 have invalid IANA IDs
        assertTrue(ICalDateTime.timezoneAliases.size > 90,
            "Expected >90 valid timezone aliases, got ${ICalDateTime.timezoneAliases.size}")
    }

    @Test
    fun `timezoneAliases map is case insensitive`() {
        // TreeMap with CASE_INSENSITIVE_ORDER should match regardless of case
        assertNotNull(ICalDateTime.timezoneAliases["Eastern Standard Time"])
        assertNotNull(ICalDateTime.timezoneAliases["eastern standard time"])
        assertNotNull(ICalDateTime.timezoneAliases["EASTERN STANDARD TIME"])
    }

    @Test
    fun `timezoneAliases excludes entries with invalid IANA targets`() {
        // US/Hwaii is a typo in msTimezoneNames — should be filtered out
        assertFalse(ICalDateTime.timezoneAliases.containsValue("US/Hwaii"),
            "US/Hwaii is an invalid IANA ID and should be excluded")
        assertFalse(ICalDateTime.timezoneAliases.containsValue("Austraila/Perth"),
            "Austraila/Perth is a typo and should be excluded")
    }
}
