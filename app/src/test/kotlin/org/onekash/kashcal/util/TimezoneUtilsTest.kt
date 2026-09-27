package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Tests [TimezoneUtils]: the zone list, search, abbreviations, offsets from the device zone,
 * time conversion, zone info, the device zone, validity, the local-time preview, the
 * [TimezoneUtils.TimezoneInfo] fields, and DST, no-DST, UTC and unusual-input edge cases.
 *
 * No zone is set, so tests that name the device zone depend on the host's; several of them
 * assert only a format or non-null.
 */
class TimezoneUtilsTest {

    // ==================== getAvailableTimezones Tests ====================

    @Test
    fun `getAvailableTimezones returns non-empty list`() {
        val timezones = TimezoneUtils.getAvailableTimezones()

        assertTrue("Should have many timezones", timezones.size > 100)
    }

    @Test
    fun `getAvailableTimezones includes common timezones`() {
        val timezones = TimezoneUtils.getAvailableTimezones()
        val zoneIds = timezones.map { it.zoneId }

        assertTrue("Should include America/New_York", "America/New_York" in zoneIds)
        assertTrue("Should include America/Los_Angeles", "America/Los_Angeles" in zoneIds)
        assertTrue("Should include Europe/London", "Europe/London" in zoneIds)
        assertTrue("Should include Asia/Tokyo", "Asia/Tokyo" in zoneIds)
    }

    @Test
    fun `getAvailableTimezones excludes deprecated zones`() {
        val timezones = TimezoneUtils.getAvailableTimezones()
        val zoneIds = timezones.map { it.zoneId }

        assertFalse("Should not include Etc/ zones", zoneIds.any { it.startsWith("Etc/") })
        assertFalse("Should not include SystemV/ zones", zoneIds.any { it.startsWith("SystemV/") })
    }

    @Test
    fun `getAvailableTimezones sorted by offset`() {
        val timezones = TimezoneUtils.getAvailableTimezones()

        val tokyoIndex = timezones.indexOfFirst { it.zoneId == "Asia/Tokyo" }
        val londonIndex = timezones.indexOfFirst { it.zoneId == "Europe/London" }
        val newYorkIndex = timezones.indexOfFirst { it.zoneId == "America/New_York" }

        // Sorted by UTC offset: New York, then London, then Tokyo.
        assertTrue("New York should come before London", newYorkIndex < londonIndex)
        assertTrue("London should come before Tokyo", londonIndex < tokyoIndex)
    }

    // ==================== searchTimezones Tests ====================

    @Test
    fun `searchTimezones empty query returns empty list`() {
        val results = TimezoneUtils.searchTimezones("")
        assertTrue("Empty query should return empty list", results.isEmpty())
    }

    @Test
    fun `searchTimezones blank query returns empty list`() {
        val results = TimezoneUtils.searchTimezones("   ")
        assertTrue("Blank query should return empty list", results.isEmpty())
    }

    @Test
    fun `searchTimezones by city name`() {
        val results = TimezoneUtils.searchTimezones("Tokyo")

        assertTrue("Should find Tokyo", results.any { it.zoneId == "Asia/Tokyo" })
    }

    @Test
    fun `searchTimezones by partial city name`() {
        val results = TimezoneUtils.searchTimezones("tok")

        assertTrue("Should find Tokyo with partial match", results.any { it.zoneId == "Asia/Tokyo" })
    }

    @Test
    fun `searchTimezones case insensitive`() {
        val results = TimezoneUtils.searchTimezones("TOKYO")

        assertTrue("Should find Tokyo case-insensitive", results.any { it.zoneId == "Asia/Tokyo" })
    }

    @Test
    fun `searchTimezones by region`() {
        val results = TimezoneUtils.searchTimezones("America")

        assertTrue("Should find American timezones", results.isNotEmpty())
        assertTrue("All results should be America/", results.all { it.zoneId.startsWith("America/") })
    }

    @Test
    fun `searchTimezones by zone ID`() {
        val results = TimezoneUtils.searchTimezones("asia/tok")

        assertTrue("Should find Tokyo by zone ID", results.any { it.zoneId == "Asia/Tokyo" })
    }

    @Test
    fun `searchTimezones limited to 10 results`() {
        // "a" matches far more than 10 zones.
        val results = TimezoneUtils.searchTimezones("a")

        assertTrue("Should return at most 10 results", results.size <= 10)
    }

    @Test
    fun `searchTimezones by abbreviation`() {
        // Abbreviations can be ambiguous, so this asserts only that "PST" finds a match.
        val results = TimezoneUtils.searchTimezones("PST")

        assertTrue("Should find timezones matching PST", results.isNotEmpty())
    }

    @Test
    fun `searchTimezones New York with space`() {
        val results = TimezoneUtils.searchTimezones("new york")

        assertTrue("Should find New York", results.any { it.zoneId == "America/New_York" })
    }

    // ==================== getAbbreviation Tests ====================

    @Test
    fun `getAbbreviation returns short form`() {
        val abbrev = TimezoneUtils.getAbbreviation("America/New_York")

        // EST or EDT, depending on the time of year.
        assertTrue("Should be EST or EDT", abbrev == "EST" || abbrev == "EDT")
    }

    @Test
    fun `getAbbreviation for UTC`() {
        val abbrev = TimezoneUtils.getAbbreviation("UTC")

        assertEquals("UTC", abbrev)
    }

    @Test
    fun `getAbbreviation at specific instant winter`() {
        // Jan 15, 2025, 00:00 UTC: EST.
        val winterInstant = Instant.ofEpochMilli(1736899200000L)

        val abbrev = TimezoneUtils.getAbbreviation("America/New_York", winterInstant)

        assertEquals("EST", abbrev)
    }

    @Test
    fun `getAbbreviation at specific instant summer`() {
        // July 15, 2025, 00:00 UTC: EDT.
        val summerInstant = Instant.ofEpochMilli(1752537600000L)

        val abbrev = TimezoneUtils.getAbbreviation("America/New_York", summerInstant)

        assertEquals("EDT", abbrev)
    }

    @Test
    fun `getAbbreviation invalid zone returns fallback`() {
        val abbrev = TimezoneUtils.getAbbreviation("Invalid/Zone")

        assertNotNull("Should return fallback", abbrev)
    }

    @Test
    fun `getAbbreviation for Japan (no DST)`() {
        val abbrev = TimezoneUtils.getAbbreviation("Asia/Tokyo")

        assertEquals("JST", abbrev)
    }

    // ==================== getOffsetFromDevice Tests ====================

    @Test
    fun `getOffsetFromDevice returns Device for same zone`() {
        val deviceZone = ZoneId.systemDefault().id
        val offset = TimezoneUtils.getOffsetFromDevice(deviceZone)

        assertEquals("Device", offset)
    }

    @Test
    fun `getOffsetFromDevice returns offset string`() {
        // Depends on the device zone, so only the format is checked, and nothing when the
        // result is "Device".
        val offset = TimezoneUtils.getOffsetFromDevice("Asia/Tokyo")

        if (offset != "Device") {
            // "+9h", "-5h" or "+5:30".
            assertTrue("Should contain h or colon", offset.contains("h") || offset.contains(":"))
        }
    }

    @Test
    fun `getOffsetFromDevice handles invalid zone`() {
        val offset = TimezoneUtils.getOffsetFromDevice("Invalid/Zone")

        assertEquals("?", offset)
    }

    @Test
    fun `getOffsetFromDevice handles half-hour offsets`() {
        // India is UTC+5:30. Asserts only that the result isn't null, never the half hour.
        val offset = TimezoneUtils.getOffsetFromDevice("Asia/Kolkata")

        if (offset != "Device") {
            assertNotNull(offset)
        }
    }

    // ==================== convertTime Tests ====================

    @Test
    fun `convertTime same timezone returns same time`() {
        val epochMs = System.currentTimeMillis()

        val result = TimezoneUtils.convertTime(epochMs, "America/New_York", "America/New_York")

        assertEquals(epochMs, result)
    }

    @Test
    fun `convertTime null zones use device timezone`() {
        val epochMs = System.currentTimeMillis()

        val result = TimezoneUtils.convertTime(epochMs, null, null)

        assertEquals(epochMs, result)
    }

    @Test
    fun `convertTime between different timezones`() {
        // Jan 6, 2026, 16:40 UTC (11:40 AM EST).
        val nyTime = 1767717600000L

        val tokyoTime = TimezoneUtils.convertTime(nyTime, "America/New_York", "Asia/Tokyo")

        // The conversion keeps the instant.
        assertEquals("Same instant should produce same timestamp", nyTime, tokyoTime)
    }

    @Test
    fun `convertTime preserves instant`() {
        // Jan 6, 2026, 00:00 UTC.
        val originalMs = 1767657600000L

        val result = TimezoneUtils.convertTime(originalMs, "UTC", "America/New_York")

        assertEquals("Instant should be preserved", originalMs, result)
    }

    // ==================== getTimezoneInfo Tests ====================

    @Test
    fun `getTimezoneInfo returns info for valid zone`() {
        val info = TimezoneUtils.getTimezoneInfo("America/New_York")

        assertNotNull("Should return info for valid zone", info)
        assertEquals("America/New_York", info?.zoneId)
        assertEquals("New York", info?.displayName)
    }

    @Test
    fun `getTimezoneInfo returns null for invalid zone`() {
        val info = TimezoneUtils.getTimezoneInfo("Invalid/Zone")

        assertNull("Should return null for invalid zone", info)
    }

    @Test
    fun `getTimezoneInfo display name overrides work`() {
        val info = TimezoneUtils.getTimezoneInfo("Asia/Kolkata")

        assertNotNull(info)
        // The display name override replaces "Kolkata".
        assertEquals("Mumbai", info?.displayName)
    }

    // ==================== getDeviceTimezone Tests ====================

    @Test
    fun `getDeviceTimezone returns valid zone ID`() {
        val deviceZone = TimezoneUtils.getDeviceTimezone()

        assertTrue("Should return valid zone ID", deviceZone.contains("/") || deviceZone == "UTC")
        assertTrue("Should be a known zone", TimezoneUtils.isValidTimezone(deviceZone))
    }

    // ==================== isValidTimezone Tests ====================

    @Test
    fun `isValidTimezone returns true for valid zones`() {
        assertTrue(TimezoneUtils.isValidTimezone("America/New_York"))
        assertTrue(TimezoneUtils.isValidTimezone("Europe/London"))
        assertTrue(TimezoneUtils.isValidTimezone("Asia/Tokyo"))
        assertTrue(TimezoneUtils.isValidTimezone("UTC"))
    }

    @Test
    fun `isValidTimezone returns false for invalid zones`() {
        assertFalse(TimezoneUtils.isValidTimezone("Invalid/Zone"))
        assertFalse(TimezoneUtils.isValidTimezone(""))
        assertFalse(TimezoneUtils.isValidTimezone("NotATimezone"))
    }

    // ==================== formatLocalTimePreview Tests ====================

    @Test
    fun `formatLocalTimePreview returns null for same timezone`() {
        val deviceZone = ZoneId.systemDefault().id
        val result = TimezoneUtils.formatLocalTimePreview(
            System.currentTimeMillis(),
            deviceZone
        )

        assertNull("Should return null for same timezone", result)
    }

    @Test
    fun `formatLocalTimePreview returns null for null timezone`() {
        val result = TimezoneUtils.formatLocalTimePreview(
            System.currentTimeMillis(),
            null
        )

        assertNull("Should return null for null timezone", result)
    }

    @Test
    fun `formatLocalTimePreview includes time and abbreviation`() {
        // Returns early, passing without asserting, when the device zone is Tokyo.
        if (ZoneId.systemDefault().id == "Asia/Tokyo") return

        // Jan 6, 2026, 10:00 UTC.
        val eventTimeMs = 1767693600000L

        val result = TimezoneUtils.formatLocalTimePreview(eventTimeMs, "Asia/Tokyo")

        assertNotNull("Should return preview for different timezone", result)
        // Only AM or PM is checked, not the abbreviation.
        assertTrue("Should contain time format",
            result!!.contains("AM") || result.contains("PM") ||
            result.contains("am") || result.contains("pm"))
    }

    @Test
    fun `formatLocalTimePreview shows next day indicator`() {
        // Despite the name, no next-day suffix is asserted. The event is at Jan 6, 2026, 23:00
        // UTC in the UTC zone; the result is null when the device zone ID is "UTC", and
        // otherwise only a ":" is checked.
        val lateNightUtc = 1767740400000L

        val result = TimezoneUtils.formatLocalTimePreview(lateNightUtc, "UTC")

        if (result != null) {
            assertTrue("Should contain time", result.contains(":"))
        }
    }

    // ==================== TimezoneInfo Data Class Tests ====================

    @Test
    fun `TimezoneInfo has all required fields`() {
        val info = TimezoneUtils.TimezoneInfo(
            zoneId = "America/New_York",
            abbreviation = "EST",
            displayName = "New York",
            offsetFromDevice = "+5h"
        )

        assertEquals("America/New_York", info.zoneId)
        assertEquals("EST", info.abbreviation)
        assertEquals("New York", info.displayName)
        assertEquals("+5h", info.offsetFromDevice)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles DST transitions`() {
        // US DST 2026: March 8 at 2:00 AM local becomes 3:00 AM. 06:00 UTC is 1 AM EST;
        // 08:00 UTC is 4 AM EDT.
        val beforeDst = Instant.ofEpochMilli(1772949600000L)
        val afterDst = Instant.ofEpochMilli(1772956800000L)

        val abbrevBefore = TimezoneUtils.getAbbreviation("America/New_York", beforeDst)
        val abbrevAfter = TimezoneUtils.getAbbreviation("America/New_York", afterDst)

        assertEquals("EST", abbrevBefore)
        assertEquals("EDT", abbrevAfter)
    }

    @Test
    fun `handles timezones without DST`() {
        // Arizona doesn't observe DST
        // July 15 and January 15, 2025.
        val summerInstant = Instant.ofEpochMilli(1752537600000L)
        val winterInstant = Instant.ofEpochMilli(1736899200000L)

        val summerAbbrev = TimezoneUtils.getAbbreviation("America/Phoenix", summerInstant)
        val winterAbbrev = TimezoneUtils.getAbbreviation("America/Phoenix", winterInstant)

        assertEquals("MST", summerAbbrev)
        assertEquals("MST", winterAbbrev)
    }

    @Test
    fun `handles UTC offset timezones`() {
        assertTrue(TimezoneUtils.isValidTimezone("UTC"))

        val abbrev = TimezoneUtils.getAbbreviation("UTC")
        assertEquals("UTC", abbrev)
    }

    @Test
    fun `search handles unicode characters`() {
        // Asserts only that unicode input doesn't crash; a match isn't required.
        val results = TimezoneUtils.searchTimezones("東京")
        assertNotNull(results)
    }

    @Test
    fun `search handles special characters`() {
        // Asserts only that a hyphen doesn't crash.
        val results = TimezoneUtils.searchTimezones("New-York")
        assertNotNull(results)
    }
}
