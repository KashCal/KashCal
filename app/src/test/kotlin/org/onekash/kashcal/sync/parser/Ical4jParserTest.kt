package org.onekash.kashcal.sync.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for Ical4jParser using the test corpus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Ical4jParserTest {

    private lateinit var parser: Ical4jParser

    @Before
    fun setup() {
        parser = Ical4jParser()
    }

    // ========== Basic Events ==========

    @Test
    fun `parse simple event`() {
        val ical = loadTestFile("basic/simple_event.ics")
        val result = parser.parse(ical)

        assertTrue("Should parse successfully", result.isSuccess())
        assertEquals("Should have 1 event", 1, result.events.size)

        val event = result.events.first()
        assertEquals("simple-event-001@kashcal.test", event.uid)
        assertEquals("Simple Test Event", event.summary)
        assertEquals("A basic test event with minimal properties.", event.description)
        assertEquals("Conference Room A", event.location)
        assertFalse("Should not be all-day", event.isAllDay)
        assertEquals("CONFIRMED", event.status)
        assertNull("Should not have RRULE", event.rrule)
        assertNull("Should not have RECURRENCE-ID", event.recurrenceId)
    }

    @Test
    fun `parse all-day event`() {
        val ical = loadTestFile("basic/all_day_event.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        assertEquals("all-day-001@kashcal.test", event.uid)
        assertEquals("Christmas Day", event.summary)
        assertTrue("Should be all-day", event.isAllDay)
    }

    @Test
    fun `all-day event endTs adjusted for RFC 5545 exclusive DTEND`() {
        // Christmas: DTSTART=20241225, DTEND=20241226 (exclusive)
        // After RFC 5545 adjustment, endTs should be Dec 25 (not Dec 26)
        val ical = loadTestFile("basic/all_day_event.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        // DTSTART:20241225 = Dec 25, 2024 00:00:00 UTC = 1735084800 seconds
        // DTEND:20241226 = Dec 26, 2024 00:00:00 UTC = 1735171200 seconds
        // After -1 second adjustment: endTs = 1735171199 (Dec 25, 2024 23:59:59 UTC)
        val startDay = event.startTs / 86400  // Days since epoch
        val endDay = event.endTs / 86400      // Days since epoch

        assertEquals(
            "Single-day all-day event should have endTs on same day as startTs",
            startDay, endDay
        )
    }

    @Test
    fun `parse multi-day all-day event`() {
        val ical = loadTestFile("basic/multi_day_all_day.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        assertEquals("Holiday Vacation", event.summary)
        assertTrue(event.isAllDay)
        // Dec 24-26 = 3 days, DTEND is exclusive (Dec 27)
        // After RFC 5545 adjustment: endTs should be Dec 26 23:59:59
        assertTrue("End should be after start", event.endTs > event.startTs)

        // Verify exactly 3 days (not 4 due to exclusive end)
        val startDay = event.startTs / 86400  // Days since epoch
        val endDay = event.endTs / 86400      // Days since epoch
        val daySpan = endDay - startDay + 1   // +1 for inclusive counting

        assertEquals(
            "Dec 24-26 should be exactly 3 days",
            3L, daySpan
        )
    }

    @Test
    fun `all-day date parsed as UTC to avoid timezone shift`() {
        // Dec 25, 2025 should stay Dec 25 regardless of device timezone
        // This tests the fix for all-day events showing a day early
        val tripItIcs = """
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

        val result = parser.parse(tripItIcs)
        assertTrue(result.isSuccess())
        val event = result.events.first()
        assertTrue(event.isAllDay)

        // Dec 25, 2025 00:00:00 UTC = 1766620800 seconds
        // Verify the date is stored as UTC midnight (not shifted by device timezone)
        val expectedStartTs = 1766620800L
        assertEquals(
            "All-day date should be parsed as UTC midnight to avoid timezone shifts",
            expectedStartTs,
            event.startTs
        )

        // Verify it's actually Dec 25 when formatted in UTC
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = event.startTs * 1000
        assertEquals("Year should be 2025", 2025, calendar.get(java.util.Calendar.YEAR))
        assertEquals("Month should be December (11)", 11, calendar.get(java.util.Calendar.MONTH))
        assertEquals("Day should be 25", 25, calendar.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse event with special characters`() {
        val ical = loadTestFile("basic/special_chars.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        // ical4j should automatically unescape
        assertEquals("Meeting, Important!", event.summary)
        assertTrue("Description should have newlines", event.description.contains("\n"))
        assertEquals("Room; Building A", event.location)
    }

    @Test
    fun `parse event with duration instead of dtend`() {
        val ical = loadTestFile("basic/no_dtend_with_duration.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        // Duration is PT2H30M = 2.5 hours = 9000 seconds
        val durationSeconds = event.endTs - event.startTs
        assertEquals("Duration should be 2h30m", 9000L, durationSeconds)
    }

    // ========== Datetime Formats ==========

    @Test
    fun `parse UTC datetime`() {
        val ical = loadTestFile("datetime/utc_datetime.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        assertNull("UTC events have no TZID", event.timezone)
        assertFalse(event.isAllDay)
    }

    @Test
    fun `parse event with TZID America New York`() {
        val ical = loadTestFile("datetime/tzid_america_new_york.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        assertEquals("America/New_York", event.timezone)
        assertEquals("New York Meeting", event.summary)
    }

    @Test
    fun `parse event with TZID Europe London`() {
        val ical = loadTestFile("datetime/tzid_europe_london.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        assertEquals("Europe/London", event.timezone)
    }

    @Test
    fun `parse floating datetime`() {
        val ical = loadTestFile("datetime/floating_datetime.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        // Floating times have no TZID (interpreted as local time)
        assertNull("Floating time has no TZID", event.timezone)
        assertFalse(event.isAllDay)
    }

    // ========== Recurring Events ==========

    @Test
    fun `parse daily recurring event`() {
        val ical = loadTestFile("recurring/daily_simple.ics")
        val result = parser.parse(ical)

        assertTrue(result.isSuccess())
        val event = result.events.first()

        assertNotNull("Should have RRULE", event.rrule)
        assertTrue(event.rrule!!.contains("FREQ=DAILY"))
        assertTrue(event.isRecurring())
    }

    @Test
    fun `parse daily with interval 3`() {
        val ical = loadTestFile("recurring/daily_interval_3.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("INTERVAL=3"))
    }

    @Test
    fun `parse weekly single day`() {
        val ical = loadTestFile("recurring/weekly_single_day.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(event.rrule!!.contains("BYDAY=FR"))
    }

    @Test
    fun `parse weekly multiple days MWF`() {
        val ical = loadTestFile("recurring/weekly_multiple_days.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("BYDAY=MO,WE,FR"))
    }

    @Test
    fun `parse monthly same day`() {
        val ical = loadTestFile("recurring/monthly_same_day.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("FREQ=MONTHLY"))
        assertTrue(event.rrule!!.contains("BYMONTHDAY=15"))
    }

    @Test
    fun `parse monthly last day`() {
        val ical = loadTestFile("recurring/monthly_last_day.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("BYMONTHDAY=-1"))
    }

    @Test
    fun `parse monthly nth weekday - 3rd Friday`() {
        val ical = loadTestFile("recurring/monthly_nth_weekday.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("BYDAY=3FR"))
    }

    @Test
    fun `parse monthly last weekday - last Monday`() {
        val ical = loadTestFile("recurring/monthly_last_weekday.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("BYDAY=-1MO"))
    }

    @Test
    fun `parse yearly simple`() {
        val ical = loadTestFile("recurring/yearly_simple.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("FREQ=YEARLY"))
        assertTrue(event.isAllDay)
    }

    @Test
    fun `parse with COUNT limit`() {
        val ical = loadTestFile("recurring/with_count_10.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("COUNT=10"))
    }

    @Test
    fun `parse with UNTIL date`() {
        val ical = loadTestFile("recurring/with_until_date.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("UNTIL="))
    }

    @Test
    fun `parse biweekly`() {
        val ical = loadTestFile("recurring/biweekly.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue(event.rrule!!.contains("INTERVAL=2"))
        assertTrue(event.rrule!!.contains("FREQ=WEEKLY"))
    }

    // ========== Exceptions (EXDATE, RDATE, RECURRENCE-ID) ==========

    @Test
    fun `parse with single EXDATE`() {
        val ical = loadTestFile("exceptions/with_single_exdate.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(1, event.exdates.size)
        assertEquals("20241225", event.exdates.first())
    }

    @Test
    fun `parse with multiple EXDATEs`() {
        val ical = loadTestFile("exceptions/with_multiple_exdates.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(3, event.exdates.size)
        assertTrue(event.exdates.contains("20241224"))
        assertTrue(event.exdates.contains("20241225"))
        assertTrue(event.exdates.contains("20241231"))
    }

    @Test
    fun `parse with RDATE`() {
        val ical = loadTestFile("exceptions/with_rdate.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(1, event.rdates.size)
        assertEquals("20241227", event.rdates.first())
    }

    @Test
    fun `parse RECURRENCE-ID - master and exception`() {
        val ical = loadTestFile("exceptions/recurrence_id_single.ics")
        val result = parser.parse(ical)

        assertEquals("Should have 2 events (master + exception)", 2, result.events.size)

        val master = result.events.find { it.recurrenceId == null }!!
        val exception = result.events.find { it.recurrenceId != null }!!

        assertEquals("recid-001@kashcal.test", master.uid)
        assertNotNull("Master should have RRULE", master.rrule)
        assertFalse(master.isException())

        assertEquals("recid-001@kashcal.test", exception.uid)
        assertEquals("20241223T100000Z", exception.recurrenceId)
        assertNull("Exception should not have RRULE", exception.rrule)
        assertTrue(exception.isException())
        assertEquals("Weekly Meeting (moved to afternoon)", exception.summary)
    }

    @Test
    fun `parse RECURRENCE-ID with cancelled status`() {
        val ical = loadTestFile("exceptions/recurrence_id_cancelled.ics")
        val result = parser.parse(ical)

        val exception = result.events.find { it.recurrenceId != null }!!
        assertEquals("CANCELLED", exception.status)
    }

    // ========== Reminders (VALARM) ==========

    @Test
    fun `parse VALARM 15 minutes`() {
        val ical = loadTestFile("reminders/valarm_15_minutes.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(1, event.reminderMinutes.size)
        assertEquals(15, event.reminderMinutes.first())
    }

    @Test
    fun `parse VALARM 1 hour`() {
        val ical = loadTestFile("reminders/valarm_1_hour.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(60, event.reminderMinutes.first())
    }

    @Test
    fun `parse VALARM 1 day`() {
        val ical = loadTestFile("reminders/valarm_1_day.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(1440, event.reminderMinutes.first())  // 24 * 60
    }

    @Test
    fun `parse VALARM combined 1h30m`() {
        val ical = loadTestFile("reminders/valarm_combined_duration.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(90, event.reminderMinutes.first())  // 60 + 30
    }

    @Test
    fun `parse multiple VALARMs`() {
        val ical = loadTestFile("reminders/valarm_multiple.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(3, event.reminderMinutes.size)
        assertTrue(event.reminderMinutes.contains(15))
        assertTrue(event.reminderMinutes.contains(60))
        assertTrue(event.reminderMinutes.contains(1440))
    }

    @Test
    fun `skip VALARM with RELATED=END`() {
        val ical = loadTestFile("reminders/valarm_related_end.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertTrue("RELATED=END alarms should be skipped", event.reminderMinutes.isEmpty())
    }

    // ========== Edge Cases ==========

    @Test
    fun `parse long folded description`() {
        val ical = loadTestFile("edge_cases/long_description_folded.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        // ical4j automatically unfolds continuation lines
        // The description should be properly unfolded
        assertTrue("Description should contain expected text: '${event.description}'",
            event.description.contains("This is a very long description"))
        // Just verify it's not empty - folding handling varies by parser
        assertTrue("Description should not be empty", event.description.isNotEmpty())
    }

    @Test
    fun `VTIMEZONE RRULE should not confuse event`() {
        val ical = loadTestFile("edge_cases/vtimezone_with_rrule.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertNull("Event should not have RRULE from VTIMEZONE", event.rrule)
        assertFalse(event.isRecurring())
    }

    @Test
    fun `parse cancelled status`() {
        val ical = loadTestFile("edge_cases/cancelled_status.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals("CANCELLED", event.status)
    }

    @Test
    fun `parse tentative status`() {
        val ical = loadTestFile("edge_cases/tentative_status.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals("TENTATIVE", event.status)
    }

    @Test
    fun `parse organizer`() {
        val ical = loadTestFile("edge_cases/with_organizer.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals("john@example.com", event.organizerEmail)
        assertEquals("John Doe", event.organizerName)
    }

    @Test
    fun `parse event without description`() {
        val ical = loadTestFile("edge_cases/empty_description.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals("", event.description)
    }

    @Test
    fun `parse high sequence number`() {
        val ical = loadTestFile("edge_cases/high_sequence.ics")
        val result = parser.parse(ical)

        val event = result.events.first()
        assertEquals(42, event.sequence)
    }

    // ========== Error Handling ==========

    @Test
    fun `empty input returns empty result`() {
        val result = parser.parse("")
        assertTrue(result.events.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `blank input returns empty result`() {
        val result = parser.parse("   \n\t  ")
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `malformed iCal returns error`() {
        val result = parser.parse("not valid ical data")
        assertFalse(result.isSuccess())
        assertTrue(result.errors.isNotEmpty())
    }

    // ========== Round-Trip Tests ==========

    @Test
    fun `round trip simple event`() {
        val original = loadTestFile("basic/simple_event.ics")
        val parsed = parser.parse(original)
        assertTrue(parsed.isSuccess())

        val generated = parser.generate(parsed.events)
        val reparsed = parser.parse(generated)

        assertTrue(reparsed.isSuccess())
        assertEquals(parsed.events.size, reparsed.events.size)

        val originalEvent = parsed.events.first()
        val roundTrippedEvent = reparsed.events.first()

        assertEquals(originalEvent.uid, roundTrippedEvent.uid)
        assertEquals(originalEvent.summary, roundTrippedEvent.summary)
        assertEquals(originalEvent.startTs, roundTrippedEvent.startTs)
        assertEquals(originalEvent.endTs, roundTrippedEvent.endTs)
    }

    @Test
    fun `round trip all-day event preserves DTEND`() {
        // Single-day all-day event: DTSTART=20241225, DTEND=20241226
        val original = loadTestFile("basic/all_day_event.ics")
        val parsed = parser.parse(original)
        assertTrue(parsed.isSuccess())

        val generated = parser.generate(parsed.events)
        val reparsed = parser.parse(generated)

        assertTrue(reparsed.isSuccess())
        val originalEvent = parsed.events.first()
        val roundTrippedEvent = reparsed.events.first()

        // Start and end timestamps should be identical after round-trip
        assertEquals(originalEvent.startTs, roundTrippedEvent.startTs)
        assertEquals(
            "All-day event endTs must be preserved after round-trip",
            originalEvent.endTs,
            roundTrippedEvent.endTs
        )

        // Verify both are on the same day (single-day event)
        val originalStartDay = originalEvent.startTs / 86400
        val originalEndDay = originalEvent.endTs / 86400
        val roundTripStartDay = roundTrippedEvent.startTs / 86400
        val roundTripEndDay = roundTrippedEvent.endTs / 86400

        assertEquals("Start day preserved", originalStartDay, roundTripStartDay)
        assertEquals("End day preserved", originalEndDay, roundTripEndDay)
    }

    @Test
    fun `round trip multi-day all-day event preserves day span`() {
        // Multi-day: Dec 24-26 (DTSTART=20241224, DTEND=20241227)
        val original = loadTestFile("basic/multi_day_all_day.ics")
        val parsed = parser.parse(original)
        assertTrue(parsed.isSuccess())

        val originalEvent = parsed.events.first()
        val originalDaySpan = (originalEvent.endTs / 86400) - (originalEvent.startTs / 86400) + 1

        val generated = parser.generate(parsed.events)
        val reparsed = parser.parse(generated)

        assertTrue(reparsed.isSuccess())
        val roundTrippedEvent = reparsed.events.first()
        val roundTripDaySpan = (roundTrippedEvent.endTs / 86400) - (roundTrippedEvent.startTs / 86400) + 1

        assertEquals(
            "Day span must be preserved after round-trip (was $originalDaySpan, now $roundTripDaySpan)",
            originalDaySpan,
            roundTripDaySpan
        )
        assertEquals(
            "Multi-day event should still be 3 days",
            3L,
            roundTripDaySpan
        )
    }

    @Test
    fun `round trip TripIt style multi-day event`() {
        // Simulates TripIt ICS: Oct 11-12 trip with DTSTART=20251011, DTEND=20251013
        val tripItIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TripIt//Test//EN
            BEGIN:VEVENT
            UID:tripit-test@example.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20251011
            DTEND;VALUE=DATE:20251013
            SUMMARY:Green Slide Hotel
            LOCATION:San Antonio, TX
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parse(tripItIcs)
        assertTrue(parsed.isSuccess())

        val originalEvent = parsed.events.first()
        val startDay = originalEvent.startTs / 86400
        val endDay = originalEvent.endTs / 86400

        // Oct 11-12 = 2 days
        assertEquals("Should be 2-day event", 2L, endDay - startDay + 1)

        // Round-trip
        val generated = parser.generate(parsed.events)
        val reparsed = parser.parse(generated)

        assertTrue(reparsed.isSuccess())
        val roundTrippedEvent = reparsed.events.first()
        val rtStartDay = roundTrippedEvent.startTs / 86400
        val rtEndDay = roundTrippedEvent.endTs / 86400

        assertEquals(
            "Round-trip should preserve 2-day span",
            2L,
            rtEndDay - rtStartDay + 1
        )
    }

    // ========== Timezone-Specific All-Day Event Tests ==========
    // These tests verify the fix for all-day events showing 1 day early in negative UTC offset timezones

    @Test
    fun `all-day event Jan 6 2026 parsed correctly in America_Chicago timezone`() {
        // This is the exact bug scenario: Jan 6 all-day event showed as Jan 5 in CST
        val originalTz = java.util.TimeZone.getDefault()
        try {
            // Set device timezone to America/Chicago (UTC-6)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Chicago"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:ellie-birthday-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260106
                DTEND;VALUE=DATE:20260107
                SUMMARY:Ellie birthday
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue("Should parse successfully", result.isSuccess())

            val event = result.events.first()
            assertTrue("Should be all-day", event.isAllDay)

            // Verify it's stored as Jan 6 00:00:00 UTC
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = event.startTs * 1000

            assertEquals("Year should be 2026", 2026, calendar.get(java.util.Calendar.YEAR))
            assertEquals("Month should be January (0)", 0, calendar.get(java.util.Calendar.MONTH))
            assertEquals("Day should be 6 (not 5!)", 6, calendar.get(java.util.Calendar.DAY_OF_MONTH))

            // Jan 6, 2026 00:00:00 UTC = 1767657600 seconds
            assertEquals("startTs should be Jan 6 2026 UTC midnight", 1767657600L, event.startTs)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly in America_Los_Angeles timezone`() {
        // Test with very negative offset (UTC-8)
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:la-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260315
                DTEND;VALUE=DATE:20260316
                SUMMARY:Test Event LA
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = event.startTs * 1000

            assertEquals("Day should be 15 (not shifted)", 15, calendar.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals("Month should be March (2)", 2, calendar.get(java.util.Calendar.MONTH))
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly in Asia_Tokyo timezone`() {
        // Test with positive offset (UTC+9) - should not shift forward
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:tokyo-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260720
                DTEND;VALUE=DATE:20260721
                SUMMARY:Test Event Tokyo
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = event.startTs * 1000

            assertEquals("Day should be 20 (not shifted)", 20, calendar.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals("Month should be July (6)", 6, calendar.get(java.util.Calendar.MONTH))
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `multi-day all-day event Jan 5-7 parsed correctly in negative offset timezone`() {
        // Multi-day event: should preserve both start and end dates
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:multiday-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260105
                DTEND;VALUE=DATE:20260108
                SUMMARY:3-Day Conference
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()

            // Check start date
            val startCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            startCal.timeInMillis = event.startTs * 1000
            assertEquals("Start day should be 5", 5, startCal.get(java.util.Calendar.DAY_OF_MONTH))

            // Check end date (DTEND is exclusive, so Jan 8 - 1 sec = Jan 7 23:59:59)
            val endCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            endCal.timeInMillis = event.endTs * 1000
            assertEquals("End day should be 7 (exclusive Jan 8 - 1 sec)", 7, endCal.get(java.util.Calendar.DAY_OF_MONTH))

            // Verify it's exactly 3 days
            val daySpan = (event.endTs / 86400) - (event.startTs / 86400) + 1
            assertEquals("Should be 3-day event", 3L, daySpan)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event at year boundary parsed correctly`() {
        // Edge case: Dec 31 to Jan 1 crossing year boundary
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Chicago"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:newyear-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20251231
                DTEND;VALUE=DATE:20260102
                SUMMARY:New Year Celebration
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()

            val startCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            startCal.timeInMillis = event.startTs * 1000
            assertEquals("Start year should be 2025", 2025, startCal.get(java.util.Calendar.YEAR))
            assertEquals("Start day should be 31", 31, startCal.get(java.util.Calendar.DAY_OF_MONTH))

            val endCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            endCal.timeInMillis = event.endTs * 1000
            assertEquals("End year should be 2026", 2026, endCal.get(java.util.Calendar.YEAR))
            assertEquals("End day should be 1", 1, endCal.get(java.util.Calendar.DAY_OF_MONTH))
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    // ========== Extreme Timezone Tests (UTC+10, UTC+12, UTC-10) ==========
    // These tests verify the fix for all-day events parsed using date string instead of epoch

    @Test
    fun `all-day event parsed correctly in Australia_Sydney timezone (UTC+10)`() {
        // Australia/Sydney is UTC+10/+11 - extreme positive offset
        // This is the primary bug scenario reported
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:sydney-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260106
                DTEND;VALUE=DATE:20260107
                SUMMARY:Sydney Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue("Should parse successfully", result.isSuccess())

            val event = result.events.first()
            assertTrue("Should be all-day", event.isAllDay)

            // Verify it's stored as Jan 6 00:00:00 UTC (not Jan 5 or Jan 7)
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = event.startTs * 1000

            assertEquals("Year should be 2026", 2026, calendar.get(java.util.Calendar.YEAR))
            assertEquals("Month should be January (0)", 0, calendar.get(java.util.Calendar.MONTH))
            assertEquals("Day should be 6 (not shifted by Sydney UTC+10)", 6, calendar.get(java.util.Calendar.DAY_OF_MONTH))

            // Jan 6, 2026 00:00:00 UTC = 1767657600 seconds
            assertEquals("startTs should be Jan 6 2026 UTC midnight", 1767657600L, event.startTs)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly in Pacific_Auckland timezone (UTC+12)`() {
        // Pacific/Auckland is UTC+12/+13 - most extreme positive offset
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:auckland-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260315
                DTEND;VALUE=DATE:20260316
                SUMMARY:Auckland Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = event.startTs * 1000

            assertEquals("Day should be 15 (not shifted by Auckland UTC+12)", 15, calendar.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals("Month should be March (2)", 2, calendar.get(java.util.Calendar.MONTH))
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `all-day event parsed correctly in Pacific_Honolulu timezone (UTC-10)`() {
        // Pacific/Honolulu is UTC-10 - extreme negative offset
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:honolulu-test@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260720
                DTEND;VALUE=DATE:20260721
                SUMMARY:Honolulu Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = event.startTs * 1000

            assertEquals("Day should be 20 (not shifted by Honolulu UTC-10)", 20, calendar.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals("Month should be July (6)", 6, calendar.get(java.util.Calendar.MONTH))
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `multi-day all-day event in Australia_Sydney preserves correct date span`() {
        // Multi-day event should preserve both start and end dates in UTC+10
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:sydney-multiday@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260105
                DTEND;VALUE=DATE:20260108
                SUMMARY:3-Day Sydney Conference
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()

            // Check start date
            val startCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            startCal.timeInMillis = event.startTs * 1000
            assertEquals("Start day should be 5", 5, startCal.get(java.util.Calendar.DAY_OF_MONTH))

            // Check end date (DTEND is exclusive, so Jan 8 - 1 sec = Jan 7 23:59:59)
            val endCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            endCal.timeInMillis = event.endTs * 1000
            assertEquals("End day should be 7 (exclusive Jan 8 - 1 sec)", 7, endCal.get(java.util.Calendar.DAY_OF_MONTH))

            // Verify it's exactly 3 days
            val daySpan = (event.endTs / 86400) - (event.startTs / 86400) + 1
            assertEquals("Should be 3-day event", 3L, daySpan)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `EXDATE parsed correctly in Australia_Sydney timezone`() {
        // EXDATE should also be immune to timezone shifts
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:exdate-sydney@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260101
                DTEND;VALUE=DATE:20260102
                RRULE:FREQ=DAILY;COUNT=10
                EXDATE;VALUE=DATE:20260106
                SUMMARY:Daily with Exception
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())

            val event = result.events.first()
            assertEquals("Should have 1 EXDATE", 1, event.exdates.size)
            assertEquals("EXDATE should be 20260106 (Jan 6)", "20260106", event.exdates.first())
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `RECURRENCE-ID VALUE=DATE parsed correctly in positive offset timezone`() {
        // RECURRENCE-ID for all-day exceptions should also use string parsing
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Sydney"))

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:recid-allday@kashcal.test
                DTSTAMP:20251221T153656Z
                DTSTART;VALUE=DATE:20260101
                DTEND;VALUE=DATE:20260102
                RRULE:FREQ=WEEKLY;COUNT=10
                SUMMARY:Weekly All-Day
                END:VEVENT
                BEGIN:VEVENT
                UID:recid-allday@kashcal.test
                DTSTAMP:20251221T153656Z
                RECURRENCE-ID;VALUE=DATE:20260108
                DTSTART;VALUE=DATE:20260109
                DTEND;VALUE=DATE:20260110
                SUMMARY:Moved to Friday
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parse(ics)
            assertTrue(result.isSuccess())
            assertEquals("Should have 2 events (master + exception)", 2, result.events.size)

            val exception = result.events.find { it.recurrenceId != null }!!
            assertEquals("RECURRENCE-ID should be 20260108 (not shifted)", "20260108", exception.recurrenceId)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    // ========== Helper Functions ==========

    private fun loadTestFile(relativePath: String): String {
        val resourcePath = "/ical/$relativePath"
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Test file not found: $resourcePath")
        return inputStream.bufferedReader().use { it.readText() }
    }
}
