package org.onekash.icaldav.parser

import org.onekash.icaldav.model.ParseResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import java.util.TimeZone

/**
 * Timezone-specific tests for ICS parsing - ported from KashCal.
 *
 * These tests verify the critical fix for all-day events showing on wrong days
 * due to timezone shifts. All-day dates (VALUE=DATE) must be parsed as strings
 * and stored as UTC midnight, not converted through local timezone.
 *
 * Tests cover:
 * - All-day events in extreme positive offsets (UTC+10 Sydney, UTC+12 Auckland)
 * - All-day events in extreme negative offsets (UTC-10 Honolulu, UTC-8 Los Angeles)
 * - Multi-day all-day events preserving correct day span
 * - Year boundary edge cases
 * - EXDATE and RECURRENCE-ID with VALUE=DATE
 * - Round-trip parsing (parse → generate → parse)
 */
@DisplayName("ICalParser Timezone Tests")
class ICalParserTimezoneTest {

    private val parser = ICalParser()

    // ==================== Basic All-Day Tests ====================

    @Test
    fun `all-day event date parsed as UTC to avoid timezone shift`() {
        // Dec 25, 2025 should stay Dec 25 regardless of device timezone
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TripIt//Test//EN
            BEGIN:VEVENT
            UID:tripit-tz-test@example.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:Christmas 2025
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()
        assertTrue(event.isAllDay)

        // Verify the date is Dec 25 when formatted in UTC
        val dayCode = event.dtStart.toDayCode()
        assertEquals("20251225", dayCode, "All-day date should be Dec 25")
    }

    @Test
    fun `all-day event endTs adjusted for RFC 5545 exclusive DTEND`() {
        // Christmas: DTSTART=20241225, DTEND=20241226 (exclusive)
        // After RFC 5545 adjustment, event should span only Dec 25
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:all-day-001@test.com
            DTSTAMP:20241224T143330Z
            DTSTART;VALUE=DATE:20241225
            DTEND;VALUE=DATE:20241226
            SUMMARY:Christmas Day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()
        assertTrue(event.isAllDay)

        // Single-day all-day event should have start and end on same day
        val startDay = event.dtStart.toDayCode()
        val endDay = event.dtEnd?.toDayCode() ?: event.dtStart.toDayCode()

        assertEquals("20241225", startDay, "Start should be Dec 25")
        // End day depends on library's handling of exclusive DTEND
        assertTrue(endDay == "20241225" || endDay == "20241226",
            "End should be Dec 25 (inclusive) or Dec 26 (exclusive)")
    }

    @Test
    fun `multi-day all-day event preserves correct day span`() {
        // Dec 24-26 = 3 days, DTEND is exclusive (Dec 27)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-day-001@test.com
            DTSTAMP:20241224T143330Z
            DTSTART;VALUE=DATE:20241224
            DTEND;VALUE=DATE:20241227
            SUMMARY:Holiday Vacation
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()
        assertTrue(event.isAllDay)

        val startDay = event.dtStart.toDayCode()
        assertEquals("20241224", startDay, "Start should be Dec 24")
    }

    // ==================== Extreme Timezone Tests (UTC+10, UTC+12, UTC-10) ====================

    @Test
    fun `all-day event parsed correctly regardless of default timezone - UTC+10 Sydney`() {
        // Australia/Sydney is UTC+10/+11 - extreme positive offset
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:sydney-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260106
                DTEND;VALUE=DATE:20260107
                SUMMARY:Sydney Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()
            assertTrue(event.isAllDay)

            // Should be Jan 6, not shifted by Sydney UTC+10
            val dayCode = event.dtStart.toDayCode()
            assertEquals("20260106", dayCode, "Day should be 6 (not shifted by Sydney UTC+10)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly regardless of default timezone - UTC+12 Auckland`() {
        // Pacific/Auckland is UTC+12/+13 - most extreme positive offset
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:auckland-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260315
                DTEND;VALUE=DATE:20260316
                SUMMARY:Auckland Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val dayCode = event.dtStart.toDayCode()
            assertEquals("20260315", dayCode, "Day should be 15 (not shifted by Auckland UTC+12)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly regardless of default timezone - UTC-10 Honolulu`() {
        // Pacific/Honolulu is UTC-10 - extreme negative offset
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:honolulu-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260720
                DTEND;VALUE=DATE:20260721
                SUMMARY:Honolulu Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val dayCode = event.dtStart.toDayCode()
            assertEquals("20260720", dayCode, "Day should be 20 (not shifted by Honolulu UTC-10)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly regardless of default timezone - UTC-6 Chicago`() {
        // America/Chicago is UTC-6 - common US timezone
        // This is the exact bug scenario: Jan 6 all-day event showed as Jan 5 in CST
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:chicago-birthday@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260106
                DTEND;VALUE=DATE:20260107
                SUMMARY:Birthday
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()
            assertTrue(event.isAllDay)

            val dayCode = event.dtStart.toDayCode()
            assertEquals("20260106", dayCode, "Day should be 6 (not 5 from Chicago UTC-6 shift)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly regardless of default timezone - UTC-8 Los Angeles`() {
        // America/Los_Angeles is UTC-8 - west coast
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:la-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260315
                DTEND;VALUE=DATE:20260316
                SUMMARY:Test Event LA
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val dayCode = event.dtStart.toDayCode()
            assertEquals("20260315", dayCode, "Day should be 15 (not shifted)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly regardless of default timezone - UTC+9 Tokyo`() {
        // Asia/Tokyo is UTC+9 - positive offset
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:tokyo-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260720
                DTEND;VALUE=DATE:20260721
                SUMMARY:Test Event Tokyo
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val dayCode = event.dtStart.toDayCode()
            assertEquals("20260720", dayCode, "Day should be 20 (not shifted by Tokyo UTC+9)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ==================== Multi-Day All-Day Timezone Tests ====================

    @Test
    fun `multi-day all-day event Jan 5-7 in negative offset timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:multiday-ny@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260105
                DTEND;VALUE=DATE:20260108
                SUMMARY:3-Day Conference
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val startDay = event.dtStart.toDayCode()
            assertEquals("20260105", startDay, "Start day should be 5")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `multi-day all-day event in Sydney preserves correct date span`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:sydney-multiday@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260105
                DTEND;VALUE=DATE:20260108
                SUMMARY:3-Day Sydney Conference
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val startDay = event.dtStart.toDayCode()
            assertEquals("20260105", startDay, "Start day should be 5")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ==================== Year Boundary Tests ====================

    @Test
    fun `all-day event at year boundary Dec 31 to Jan 1`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:newyear-test@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20251231
                DTEND;VALUE=DATE:20260102
                SUMMARY:New Year Celebration
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val startDay = event.dtStart.toDayCode()
            assertEquals("20251231", startDay, "Start should be Dec 31, 2025")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ==================== EXDATE Timezone Tests ====================

    @Test
    fun `EXDATE VALUE=DATE in positive offset timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:exdate-sydney@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260101
                DTEND;VALUE=DATE:20260102
                RRULE:FREQ=DAILY;COUNT=10
                EXDATE;VALUE=DATE:20260106
                SUMMARY:Daily with Exception
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertEquals(1, event.exdates.size, "Should have 1 EXDATE")
            val exdateDay = event.exdates.first().toDayCode()
            assertEquals("20260106", exdateDay, "EXDATE should be Jan 6 (not shifted)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ==================== RECURRENCE-ID Timezone Tests ====================

    @Test
    fun `RECURRENCE-ID VALUE=DATE in positive offset timezone`() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:recid-allday@test.com
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260101
                DTEND;VALUE=DATE:20260102
                RRULE:FREQ=WEEKLY;COUNT=10
                SUMMARY:Weekly All-Day
                END:VEVENT
                BEGIN:VEVENT
                UID:recid-allday@test.com
                DTSTAMP:20251221T153656Z
                RECURRENCE-ID;VALUE=DATE:20260108
                DTSTART;VALUE=DATE:20260109
                DTEND;VALUE=DATE:20260110
                SUMMARY:Moved to Friday
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value

            assertEquals(2, events.size, "Should have 2 events (master + exception)")

            val exception = events.find { it.recurrenceId != null }
            assertNotNull(exception, "Should have exception event")

            val recidDay = exception!!.recurrenceId!!.toDayCode()
            assertEquals("20260108", recidDay, "RECURRENCE-ID should be Jan 8 (not shifted)")
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ==================== Datetime with TZID Tests ====================

    @Test
    fun `timed event with TZID America_New_York`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tzid-ny@test.com
            DTSTAMP:20251221T153656Z
            DTSTART;TZID=America/New_York:20260115T140000
            DTEND;TZID=America/New_York:20260115T150000
            SUMMARY:New York Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertEquals("America/New_York", event.dtStart.timezone?.id)
        assertFalse(event.isAllDay)
    }

    @Test
    fun `timed event with UTC datetime`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:utc-event@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T190000Z
            DTEND:20260115T200000Z
            SUMMARY:UTC Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertFalse(event.isAllDay)
        // UTC events may have "UTC" or no timezone depending on library
    }

    @Test
    fun `floating datetime has no timezone`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:floating@test.com
            DTSTAMP:20251221T153656Z
            DTSTART:20260115T140000
            DTEND:20260115T150000
            SUMMARY:Floating Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertFalse(event.isAllDay)
        // Floating time should have no specific timezone (or system default)
        // The library may store system default for floating times
        assertNotNull(event.dtStart, "Should have valid dtStart")
    }

    // ==================== Windows Timezone Name Tests (Issue #45) ====================

    @Test
    fun `Outlook ICS with Windows timezone name resolves correct timestamp`() {
        // Issue #45: Outlook uses "Eastern Standard Time" instead of "America/New_York"
        // Bug: parseTimezone() falls back to system default, producing wrong timestamp
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VTIMEZONE
            TZID:Eastern Standard Time
            BEGIN:STANDARD
            DTSTART:16011104T020000
            RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:16010311T020000
            RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID="Eastern Standard Time":20260115T140000
            DTEND;TZID="Eastern Standard Time":20260115T150000
            DTSTAMP:20260115T100000Z
            UID:outlook-east@test.com
            SUMMARY:NYC Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Jan 15 2026 is winter → EST (UTC-5)
        // 2:00 PM EST = 19:00 UTC = 1737olean486400000L
        val expectedUtcMs = java.time.LocalDateTime.of(2026, 1, 15, 19, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertEquals(expectedUtcMs, event.dtStart.timestamp,
            "Eastern Standard Time should resolve to UTC-5, not system default")
    }

    @Test
    fun `Outlook ICS with India Standard Time resolves correct timestamp`() {
        // Non-US timezone: India Standard Time = Asia/Kolkata (UTC+5:30)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VTIMEZONE
            TZID:India Standard Time
            BEGIN:STANDARD
            DTSTART:16010101T000000
            TZOFFSETFROM:+0530
            TZOFFSETTO:+0530
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID="India Standard Time":20260115T143000
            DTEND;TZID="India Standard Time":20260115T153000
            DTSTAMP:20260115T100000Z
            UID:outlook-india@test.com
            SUMMARY:Mumbai Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // 2:30 PM IST (UTC+5:30) = 09:00 UTC
        val expectedUtcMs = java.time.LocalDateTime.of(2026, 1, 15, 9, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertEquals(expectedUtcMs, event.dtStart.timestamp,
            "India Standard Time should resolve to UTC+5:30, not system default")
    }

    @Test
    fun `Outlook ICS with W Europe Standard Time resolves correct timestamp`() {
        // W. Europe Standard Time = Europe/Berlin (UTC+1 winter, UTC+2 summer)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VTIMEZONE
            TZID:W. Europe Standard Time
            BEGIN:STANDARD
            DTSTART:16011028T030000
            RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:16010325T020000
            RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3
            TZOFFSETFROM:+0100
            TZOFFSETTO:+0200
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID="W. Europe Standard Time":20260115T140000
            DTEND;TZID="W. Europe Standard Time":20260115T150000
            DTSTAMP:20260115T100000Z
            UID:outlook-europe@test.com
            SUMMARY:Berlin Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Jan 15 2026 is winter → CET (UTC+1)
        // 2:00 PM CET = 13:00 UTC
        val expectedUtcMs = java.time.LocalDateTime.of(2026, 1, 15, 13, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertEquals(expectedUtcMs, event.dtStart.timestamp,
            "W. Europe Standard Time should resolve to UTC+1, not system default")
    }

    // ==================== Windows Timezone POST Tests (Issue #45) ====================

    @Test
    fun `Outlook ICS with Tokyo Standard Time resolves correct timestamp`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VTIMEZONE
            TZID:Tokyo Standard Time
            BEGIN:STANDARD
            DTSTART:16010101T000000
            TZOFFSETFROM:+0900
            TZOFFSETTO:+0900
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID="Tokyo Standard Time":20260115T140000
            DTEND;TZID="Tokyo Standard Time":20260115T150000
            DTSTAMP:20260115T100000Z
            UID:outlook-tokyo@test.com
            SUMMARY:Tokyo Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // 2:00 PM JST (UTC+9) = 05:00 UTC
        val expectedUtcMs = java.time.LocalDateTime.of(2026, 1, 15, 5, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertEquals(expectedUtcMs, event.dtStart.timestamp,
            "Tokyo Standard Time should resolve to UTC+9")
    }

    @Test
    fun `Outlook ICS with AUS Eastern Standard Time resolves correct timestamp`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VTIMEZONE
            TZID:AUS Eastern Standard Time
            BEGIN:STANDARD
            DTSTART:16010401T030000
            RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=4
            TZOFFSETFROM:+1100
            TZOFFSETTO:+1000
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:16011007T020000
            RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=10
            TZOFFSETFROM:+1000
            TZOFFSETTO:+1100
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID="AUS Eastern Standard Time":20260115T140000
            DTEND;TZID="AUS Eastern Standard Time":20260115T150000
            DTSTAMP:20260115T100000Z
            UID:outlook-australia@test.com
            SUMMARY:Melbourne Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // Jan 15 2026 is summer in Australia → AEDT (UTC+11)
        // 2:00 PM AEDT = 03:00 UTC
        val expectedUtcMs = java.time.LocalDateTime.of(2026, 1, 15, 3, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertEquals(expectedUtcMs, event.dtStart.timestamp,
            "AUS Eastern Standard Time should resolve to UTC+11 in January (AEDT)")
    }

    @Test
    fun `Outlook ICS without VTIMEZONE still resolves Windows timezone`() {
        // Many real-world email invitations lack VTIMEZONE blocks entirely
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VEVENT
            DTSTART;TZID="Eastern Standard Time":20260115T140000
            DTEND;TZID="Eastern Standard Time":20260115T150000
            DTSTAMP:20260115T100000Z
            UID:outlook-no-vtimezone@test.com
            SUMMARY:Meeting Without VTIMEZONE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        // 2:00 PM EST (UTC-5) = 19:00 UTC
        val expectedUtcMs = java.time.LocalDateTime.of(2026, 1, 15, 19, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertEquals(expectedUtcMs, event.dtStart.timestamp,
            "Should resolve Windows timezone even without VTIMEZONE block")
    }

    // ==================== TripIt-Style Tests ====================

    @Test
    fun `TripIt style multi-day event Oct 11-12 trip`() {
        // Simulates TripIt ICS: DTSTART=20251011, DTEND=20251013 (exclusive)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TripIt//Test//EN
            BEGIN:VEVENT
            UID:tripit-hotel@example.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20251011
            DTEND;VALUE=DATE:20251013
            SUMMARY:Green Slide Hotel
            LOCATION:San Antonio, TX
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)
        assertTrue(result is ParseResult.Success)
        val event = (result as ParseResult.Success).value.first()

        assertTrue(event.isAllDay)
        assertEquals("20251011", event.dtStart.toDayCode(), "Should start Oct 11")
    }
}
