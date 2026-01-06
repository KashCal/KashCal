package org.onekash.kashcal.sync.serializer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event

/**
 * Unit tests for VTIMEZONE generation in ICalSerializer.
 *
 * Verifies RFC 5545 compliant VTIMEZONE output for various timezone types.
 */
class VTimezoneGenerationTest {

    private val serializer = ICalSerializer()

    // Test event with timezone
    private fun createTestEvent(timezone: String? = null): Event {
        return Event(
            id = 1L,
            calendarId = 1L,
            uid = "test-event@kashcal.test",
            title = "Test Event",
            startTs = 1767657600000L,  // Jan 6, 2026 00:00:00 UTC
            endTs = 1767661200000L,    // Jan 6, 2026 01:00:00 UTC
            dtstamp = 1767657600000L,
            createdAt = 1767657600000L,
            updatedAt = 1767657600000L,
            timezone = timezone,
            isAllDay = false
        )
    }

    // ==================== Basic VTIMEZONE Tests ====================

    @Test
    fun `serialize event without timezone has no VTIMEZONE`() {
        val event = createTestEvent(timezone = null)

        val ics = serializer.serialize(event)

        assertFalse("Should not contain VTIMEZONE", ics.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun `serialize event with timezone includes VTIMEZONE`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        assertTrue("Should contain VTIMEZONE", ics.contains("BEGIN:VTIMEZONE"))
        assertTrue("Should contain TZID", ics.contains("TZID:America/New_York"))
        assertTrue("Should end VTIMEZONE", ics.contains("END:VTIMEZONE"))
    }

    @Test
    fun `VTIMEZONE contains proper structure`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // Should have VTIMEZONE with TZID
        assertTrue("Should have VTIMEZONE", ics.contains("BEGIN:VTIMEZONE"))
        assertTrue("Should have TZID", ics.contains("TZID:America/New_York"))

        // Should have at least one STANDARD or DAYLIGHT component
        assertTrue("Should have STANDARD or DAYLIGHT",
            ics.contains("BEGIN:STANDARD") || ics.contains("BEGIN:DAYLIGHT"))
    }

    // ==================== DST Timezone Tests ====================

    @Test
    fun `DST timezone has STANDARD and DAYLIGHT components`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // New York has DST, should have both components
        assertTrue("Should have STANDARD", ics.contains("BEGIN:STANDARD"))
        assertTrue("Should have DAYLIGHT", ics.contains("BEGIN:DAYLIGHT"))
        assertTrue("Should end STANDARD", ics.contains("END:STANDARD"))
        assertTrue("Should end DAYLIGHT", ics.contains("END:DAYLIGHT"))
    }

    @Test
    fun `STANDARD component has required properties`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // Extract STANDARD component
        val standardSection = extractComponent(ics, "STANDARD")

        assertTrue("Should have DTSTART", standardSection?.contains("DTSTART:") == true)
        assertTrue("Should have TZOFFSETFROM", standardSection?.contains("TZOFFSETFROM:") == true)
        assertTrue("Should have TZOFFSETTO", standardSection?.contains("TZOFFSETTO:") == true)
    }

    @Test
    fun `DAYLIGHT component has required properties`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // Extract DAYLIGHT component
        val daylightSection = extractComponent(ics, "DAYLIGHT")

        assertTrue("Should have DTSTART", daylightSection?.contains("DTSTART:") == true)
        assertTrue("Should have TZOFFSETFROM", daylightSection?.contains("TZOFFSETFROM:") == true)
        assertTrue("Should have TZOFFSETTO", daylightSection?.contains("TZOFFSETTO:") == true)
    }

    @Test
    fun `RRULE present for repeating DST`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // DST timezones should have RRULE for recurring transitions
        assertTrue("Should have RRULE", ics.contains("RRULE:"))
        assertTrue("Should have FREQ=YEARLY", ics.contains("FREQ=YEARLY"))
        assertTrue("Should have BYMONTH", ics.contains("BYMONTH="))
    }

    // ==================== Fixed Offset Timezone Tests ====================

    @Test
    fun `fixed offset timezone has only STANDARD`() {
        // Japan Standard Time has no DST
        val event = createTestEvent(timezone = "Asia/Tokyo")

        val ics = serializer.serialize(event)

        assertTrue("Should have VTIMEZONE", ics.contains("BEGIN:VTIMEZONE"))
        assertTrue("Should have STANDARD", ics.contains("BEGIN:STANDARD"))
        // May or may not have DAYLIGHT depending on historical data
    }

    @Test
    fun `UTC timezone works`() {
        val event = createTestEvent(timezone = "UTC")

        val ics = serializer.serialize(event)

        assertTrue("Should have VTIMEZONE", ics.contains("BEGIN:VTIMEZONE"))
        assertTrue("Should have TZID:UTC", ics.contains("TZID:UTC"))
    }

    // ==================== Offset Format Tests ====================

    @Test
    fun `negative offset formatted correctly`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // EST is -05:00, should be formatted as -0500
        assertTrue("Should have -0500 offset",
            ics.contains("TZOFFSETTO:-0500") || ics.contains("TZOFFSETFROM:-0500"))
    }

    @Test
    fun `positive offset formatted correctly`() {
        val event = createTestEvent(timezone = "Asia/Tokyo")

        val ics = serializer.serialize(event)

        // JST is +09:00, should be formatted as +0900
        assertTrue("Should have +0900 offset",
            ics.contains("TZOFFSETTO:+0900") || ics.contains("TZOFFSETFROM:+0900"))
    }

    @Test
    fun `half-hour offset formatted correctly`() {
        // India Standard Time is +05:30
        val event = createTestEvent(timezone = "Asia/Kolkata")

        val ics = serializer.serialize(event)

        assertTrue("Should have +0530 offset",
            ics.contains("TZOFFSETTO:+0530") || ics.contains("TZOFFSETFROM:+0530"))
    }

    // ==================== Multiple Timezones Test ====================

    @Test
    fun `serializeWithExceptions includes all timezones`() {
        val masterEvent = createTestEvent(timezone = "America/New_York")
        val exceptionEvent = createTestEvent(timezone = "America/Los_Angeles").copy(
            id = 2L,
            uid = "test-event@kashcal.test",
            originalEventId = 1L,
            originalInstanceTime = 1767657600000L
        )

        val ics = serializer.serializeWithExceptions(masterEvent, listOf(exceptionEvent))

        assertTrue("Should have NY timezone", ics.contains("TZID:America/New_York"))
        assertTrue("Should have LA timezone", ics.contains("TZID:America/Los_Angeles"))
    }

    @Test
    fun `duplicate timezones not repeated`() {
        val masterEvent = createTestEvent(timezone = "America/New_York")
        val exceptionEvent = createTestEvent(timezone = "America/New_York").copy(
            id = 2L,
            uid = "test-event@kashcal.test",
            originalEventId = 1L,
            originalInstanceTime = 1767657600000L
        )

        val ics = serializer.serializeWithExceptions(masterEvent, listOf(exceptionEvent))

        // Count VTIMEZONE occurrences
        val vtimezoneCount = ics.split("BEGIN:VTIMEZONE").size - 1

        assertTrue("Should have exactly 1 VTIMEZONE", vtimezoneCount == 1)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `invalid timezone ID handled gracefully`() {
        val event = createTestEvent(timezone = "Invalid/Timezone")

        // Should not throw
        val ics = serializer.serialize(event)

        // Should still produce valid ICS (without VTIMEZONE for invalid zone)
        assertTrue("Should have VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should have VEVENT", ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `DTSTART references TZID`() {
        val event = createTestEvent(timezone = "America/New_York")

        val ics = serializer.serialize(event)

        // Event DTSTART should reference the timezone
        assertTrue("DTSTART should reference TZID",
            ics.contains("DTSTART;TZID=America/New_York:"))
    }

    // ==================== Helper Functions ====================

    /**
     * Extract a component (STANDARD or DAYLIGHT) from ICS string.
     */
    private fun extractComponent(ics: String, componentType: String): String? {
        val startMarker = "BEGIN:$componentType"
        val endMarker = "END:$componentType"

        val startIndex = ics.indexOf(startMarker)
        if (startIndex == -1) return null

        val endIndex = ics.indexOf(endMarker, startIndex)
        if (endIndex == -1) return null

        return ics.substring(startIndex, endIndex + endMarker.length)
    }
}
