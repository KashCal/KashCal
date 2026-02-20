package org.onekash.kashcal.sync.parser.icaldav

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.OfRuleAndFirst
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Tests for SOGo ICS parsing (Issue #62).
 * Validates parsing of edge-case ICS patterns from SOGo servers:
 * - All-day recurring events with DATE-format UNTIL
 * - Complex VTIMEZONE with historical 6-digit (HHMMSS) UTC offsets
 * - DESCRIPTION with ALTREP parameter
 * - Long folded DESCRIPTION with non-http URL scheme
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SogoParseTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    @Test
    fun `yearly all-day recurring event with DATE-format UNTIL`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\n" +
            "CREATED:20241205T185501Z\r\n" +
            "LAST-MODIFIED:20250903T204913Z\r\n" +
            "DTSTAMP:20250903T204913Z\r\n" +
            "UID:aabbccdd-1111-2222-3333-444455556666\r\n" +
            "SUMMARY:Anniversary\r\n" +
            "STATUS:CONFIRMED\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=20350927\r\n" +
            "CATEGORIES:Personal\r\n" +
            "DTSTART;VALUE=DATE:20120221\r\n" +
            "DTEND;VALUE=DATE:20120222\r\n" +
            "CLASS:PUBLIC\r\n" +
            "DESCRIPTION:2012 Anniversary\r\n" +
            "TRANSP:TRANSPARENT\r\n" +
            "X-MOZ-GENERATION:1\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val result = parser.parseAllEvents(ics)
        assertTrue("Should parse successfully: $result", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertEquals("Should have 1 event", 1, events.size)

        val event = events[0]
        assertEquals("Anniversary", event.summary)
        assertTrue("Should be all-day", event.isAllDay)
        assertNotNull("Should have rrule", event.rrule)
        assertEquals(Frequency.YEARLY, event.rrule!!.freq)
        assertNotNull("UNTIL should be parsed", event.rrule!!.until)

        // Verify it maps to Entity without error
        val entity = ICalEventMapper.toEntity(event, ics, 1L, "test.ics", "etag1")
        assertEquals("Anniversary", entity.title)
        assertTrue(entity.isAllDay)
        assertNotNull(entity.rrule)
    }

    // ========== lib-recur RRULE expansion tests ==========

    /**
     * Verifies lib-recur can expand FREQ=YEARLY;UNTIL=20350927 (DATE-format UNTIL).
     * This is the RRULE from event 1 — a yearly all-day event starting 2012-02-21.
     *
     * Bug: Using DateTime(tz, y, m, d, 0, 0, 0) creates a timed "floating" DateTime,
     * but UNTIL=20350927 is parsed as all-day. lib-recur rejects the mismatch:
     * "using floating start times with absolute until values is not allowed"
     *
     * Fix: Use DateTime(year, month, day) for all-day events (date-only, no time).
     */
    @Test
    fun `lib-recur expands yearly RRULE with DATE-format UNTIL`() {
        val rruleStr = "FREQ=YEARLY;UNTIL=20350927"
        val rule = RecurrenceRule(rruleStr)

        // DTSTART is VALUE=DATE:20120221 → must be date-only DateTime (month 0-based)
        val dtstart = DateTime(2012, 1, 21)

        val recurrenceSet = OfRuleAndFirst(rule, dtstart)
        val iterator = recurrenceSet.iterator()
        val occurrences = mutableListOf<DateTime>()
        var count = 0
        while (iterator.hasNext() && count < 50) {
            occurrences.add(iterator.next())
            count++
        }

        // Should have occurrences from 2012 through 2035 (24 years)
        assertTrue("Should have multiple occurrences, got ${occurrences.size}", occurrences.size >= 20)
        assertTrue("Should have at most 24 occurrences, got ${occurrences.size}", occurrences.size <= 24)

        // First occurrence should be 2012-02-21
        assertEquals(2012, occurrences[0].year)
        assertEquals(1, occurrences[0].month) // 0-based
        assertEquals(21, occurrences[0].dayOfMonth)

        // Last occurrence should be 2035-02-21 (before UNTIL=20350927)
        val last = occurrences.last()
        assertEquals(2035, last.year)
        assertEquals(1, last.month)
        assertEquals(21, last.dayOfMonth)
    }

    /**
     * Verifies lib-recur generates occurrences within PullStrategy's sync window.
     * Range: now - 1 year to now + 2 years (matches PullStrategy constants).
     */
    @Test
    fun `lib-recur generates occurrences within sync window for yearly all-day event`() {
        val rruleStr = "FREQ=YEARLY;UNTIL=20350927"
        val rule = RecurrenceRule(rruleStr)

        val dtstart = DateTime(2012, 1, 21) // date-only for all-day

        val recurrenceSet = OfRuleAndFirst(rule, dtstart)
        val iterator = recurrenceSet.iterator()

        // Simulate PullStrategy range: ~2025-02-19 to ~2028-02-19
        val rangeStartYear = 2025
        val rangeEndYear = 2028
        val inRange = mutableListOf<DateTime>()

        while (iterator.hasNext()) {
            val occ = iterator.next()
            if (occ.year >= rangeStartYear && occ.year <= rangeEndYear) {
                inRange.add(occ)
            }
            if (occ.year > rangeEndYear) break
        }

        // Should have occurrences for 2025, 2026, 2027, 2028
        assertTrue("Should have occurrences in range, got ${inRange.size}", inRange.size >= 3)
        // All should be Feb 21
        inRange.forEach { occ ->
            assertEquals("Month should be February (0-based=1)", 1, occ.month)
            assertEquals("Day should be 21", 21, occ.dayOfMonth)
        }
    }

    /**
     * Verifies lib-recur handles the RRULE string as stored by ICalEventMapper
     * (round-tripped through our RRule model → toICalString()).
     * Uses date-only DateTime matching OccurrenceGenerator.timestampToAllDayDateTime().
     */
    @Test
    fun `lib-recur handles round-tripped RRULE from ICalEventMapper`() {
        // Parse the ICS to get the ICalEvent
        val ics = "BEGIN:VCALENDAR\r\n" +
            "PRODID:-//Test//EN\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:test-rrule-roundtrip\r\n" +
            "DTSTAMP:20260219T000000Z\r\n" +
            "DTSTART;VALUE=DATE:20120221\r\n" +
            "DTEND;VALUE=DATE:20120222\r\n" +
            "SUMMARY:Test\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=20350927\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val parsed = (parser.parseAllEvents(ics) as ParseResult.Success).value[0]
        val entity = ICalEventMapper.toEntity(parsed, ics, 1L, "test.ics", "etag")

        // This is the string that OccurrenceGenerator.expandRRule receives
        val storedRrule = entity.rrule!!

        // Verify lib-recur can parse it
        val rule = RecurrenceRule(storedRrule)
        assertEquals("YEARLY", rule.freq.name)

        // Verify expansion works using date-only DateTime (same as OccurrenceGenerator fix)
        val utcTz = TimeZone.getTimeZone("UTC")
        val calendar = java.util.Calendar.getInstance(utcTz)
        calendar.timeInMillis = entity.startTs
        val dtstart = DateTime(
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val recurrenceSet = OfRuleAndFirst(rule, dtstart)
        val iterator = recurrenceSet.iterator()

        assertTrue("Should produce at least one occurrence", iterator.hasNext())
        val first = iterator.next()
        assertEquals(2012, first.year)
    }

    /**
     * SOGo embeds full historical VTIMEZONE data for Pacific/Auckland going back to 1868.
     * The historical offsets use 6-digit HHMMSS format (e.g., +113904 = +11:39:04).
     * Also tests DESCRIPTION with ALTREP parameter containing a data: URI.
     */
    @Test
    fun `timed event with complex historical VTIMEZONE and ALTREP description`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VTIMEZONE\r\n" +
            "TZID:Pacific/Auckland\r\n" +
            "X-TZINFO:Pacific/Auckland[2025b]\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+113000\r\n" +
            "TZOFFSETFROM:+113904\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:18681102T000000\r\n" +
            "RDATE:18681102T000000\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+123000\r\n" +
            "TZOFFSETFROM:+113000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19271106T020000\r\n" +
            "RDATE:19271106T020000\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+113000\r\n" +
            "TZOFFSETFROM:+123000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19280304T020000\r\n" +
            "RDATE:19280304T020000\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+113000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19290317T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=19330319T020000;BYMONTH=3;BYDAY=3SU\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+113000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19281014T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=19331008T020000;BYMONTH=10;BYDAY=2SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+113000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19340429T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=19400428T020000;BYMONTH=4;BYDAY=-1SU\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+113000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19340930T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=19400929T020000;BYMONTH=9;BYDAY=-1SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19460101T000000\r\n" +
            "RDATE:19460101T000000\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19741103T020000\r\n" +
            "RDATE:19741103T020000\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+130000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19750223T030000\r\n" +
            "RDATE:19750223T030000\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19751026T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=19881030T020000;BYMONTH=10;BYDAY=-1SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+130000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19760307T030000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=19890305T030000;BYMONTH=3;BYDAY=1SU\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19891008T020000\r\n" +
            "RDATE:19891008T020000\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:19901007T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=20061001T020000;BYMONTH=10;BYDAY=1SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+130000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:19900318T030000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=20070318T030000;BYMONTH=3;BYDAY=3SU\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+130000\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:20080406T030000\r\n" +
            "RDATE:20080406T030000\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:Pacific/Auckland(DST)\r\n" +
            "DTSTART:20070930T020000\r\n" +
            "RRULE:FREQ=YEARLY;UNTIL=20080928T020000;BYMONTH=9;BYDAY=-1SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:(DST)\r\n" +
            "DTSTART:20090927T020000\r\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=9;BYDAY=-1SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+130000\r\n" +
            "TZNAME:(STD)\r\n" +
            "DTSTART:20090405T030000\r\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=4;BYDAY=1SU\r\n" +
            "END:STANDARD\r\n" +
            "END:VTIMEZONE\r\n" +
            "BEGIN:VEVENT\r\n" +
            "CREATED:20260213T013627Z\r\n" +
            "LAST-MODIFIED:20260213T031952Z\r\n" +
            "DTSTAMP:20260213T031952Z\r\n" +
            "UID:aabbccdd-5555-6666-7777-888899990000\r\n" +
            "SUMMARY:Evening Party\r\n" +
            "CATEGORIES:Personal\r\n" +
            "DTSTART;TZID=Pacific/Auckland:20260221T173000\r\n" +
            "DTEND;TZID=Pacific/Auckland:20260221T223000\r\n" +
            "TRANSP:OPAQUE\r\n" +
            "LOCATION:Test Location\r\n" +
            "DESCRIPTION;ALTREP=\"data:text/html,%3Cbr%3E\":\\n\r\n" +
            "CLASS:PUBLIC\r\n" +
            "SEQUENCE:1\r\n" +
            "X-MOZ-GENERATION:1\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val result = parser.parseAllEvents(ics)
        assertTrue("Should parse successfully: $result", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertEquals("Should have 1 event", 1, events.size)

        val event = events[0]
        assertEquals("Evening Party", event.summary)
        assertFalse("Should NOT be all-day", event.isAllDay)
        assertEquals("Test Location", event.location)

        // Verify it maps to Entity without error
        val entity = ICalEventMapper.toEntity(event, ics, 1L, "test.ics", "etag2")
        assertEquals("Evening Party", entity.title)
    }

    /**
     * Tests long folded DESCRIPTION (from email import) and non-http URL scheme (mid:).
     * Also exercises the same complex historical VTIMEZONE (abbreviated for brevity).
     */
    @Test
    fun `timed event with long folded description and mid URL scheme`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VTIMEZONE\r\n" +
            "TZID:Pacific/Auckland\r\n" +
            "X-TZINFO:Pacific/Auckland[2025b]\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+113000\r\n" +
            "TZOFFSETFROM:+113904\r\n" +
            "TZNAME:Pacific/Auckland(STD)\r\n" +
            "DTSTART:18681102T000000\r\n" +
            "RDATE:18681102T000000\r\n" +
            "END:STANDARD\r\n" +
            "BEGIN:DAYLIGHT\r\n" +
            "TZOFFSETTO:+130000\r\n" +
            "TZOFFSETFROM:+120000\r\n" +
            "TZNAME:(DST)\r\n" +
            "DTSTART:20090927T020000\r\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=9;BYDAY=-1SU\r\n" +
            "END:DAYLIGHT\r\n" +
            "BEGIN:STANDARD\r\n" +
            "TZOFFSETTO:+120000\r\n" +
            "TZOFFSETFROM:+130000\r\n" +
            "TZNAME:(STD)\r\n" +
            "DTSTART:20090405T030000\r\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=4;BYDAY=1SU\r\n" +
            "END:STANDARD\r\n" +
            "END:VTIMEZONE\r\n" +
            "BEGIN:VEVENT\r\n" +
            "CREATED:20260202T224747Z\r\n" +
            "LAST-MODIFIED:20260202T224905Z\r\n" +
            "DTSTAMP:20260202T224905Z\r\n" +
            "UID:aabbccdd-aaaa-bbbb-cccc-ddddeeeeffff\r\n" +
            "SUMMARY:Film Night\r\n" +
            "CATEGORIES:Personal\r\n" +
            "DTSTART;TZID=Pacific/Auckland:20260224T183000\r\n" +
            "DTEND;TZID=Pacific/Auckland:20260224T210000\r\n" +
            "DESCRIPTION:Booking confirmation\\n\\nHi User\\,\\n\\nYour order has been confirme\r\n" +
            " d\\, please find a copy of your ticket/s and receipt/s attached to this ema\r\n" +
            " il.\\n\\nDate booked:\\n3 February 2026 11:45 AM\\nBooking ID:\\naaaaaaaa-bbbb\r\n" +
            " -cccc-dddd-eeeeeeeeeeee\\n\\n\\nFilm Night - City\\n\\n24 February 2026 6:30 P\r\n" +
            " M - 9:00 PM\\n\\nVenue Name\\nStreet Address\\, City\\, 3112\\nSection: Theatre\r\n" +
            " \\n\\n2 x tickets\\nTotal: $ 47.00\\n\\nAdd to calendar:\\nOutlook\\, Google\\, A\r\n" +
            " pple\\n\\nHave any questions about your event and your bookings?\\n\\nContact \r\n" +
            " Event Organiser\\n\\nCustomer Terms. All rights reserved.\\nThis email was in\r\n" +
            " tended for user@example.com\r\n" +
            "URL:mid:20260202224529.6f679ee857c70cad@mail.example.com\r\n" +
            "BEGIN:VALARM\r\n" +
            "ACTION:DISPLAY\r\n" +
            "TRIGGER:-P1D\r\n" +
            "DESCRIPTION:Default Mozilla Description\r\n" +
            "END:VALARM\r\n" +
            "CLASS:PUBLIC\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val result = parser.parseAllEvents(ics)
        assertTrue("Should parse successfully: $result", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertEquals("Should have 1 event", 1, events.size)

        val event = events[0]
        assertEquals("Film Night", event.summary)
        assertFalse("Should NOT be all-day", event.isAllDay)
        assertNotNull("Should have description", event.description)
        assertEquals("mid:20260202224529.6f679ee857c70cad@mail.example.com", event.url)
        assertEquals(1, event.alarms.size)

        // Verify it maps to Entity without error
        val entity = ICalEventMapper.toEntity(event, ics, 1L, "test.ics", "etag3")
        assertEquals("Film Night", entity.title)
    }
}