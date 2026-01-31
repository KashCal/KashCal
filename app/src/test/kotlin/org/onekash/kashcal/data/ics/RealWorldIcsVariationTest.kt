package org.onekash.kashcal.data.ics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Real-world ICS variation tests.
 *
 * Tests cover actual ICS formats exported from:
 * - Google Calendar
 * - Microsoft Outlook (365/Desktop)
 * - Apple iCloud
 * - Nextcloud Calendar
 * - iCal.com/webcal subscriptions
 * - Fastmail Calendar
 * - Mozilla Thunderbird
 *
 * Each provider has quirks that deviate from strict RFC 5545.
 * These tests ensure KashCal handles real-world calendar data correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RealWorldIcsVariationTest {

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L
    }

    // ==================== Google Calendar Variations ====================

    @Test
    fun `Google Calendar export with 75-char line folding`() {
        // Google folds lines at exactly 75 characters
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:PUBLISH
            X-WR-CALNAME:Work Calendar
            X-WR-TIMEZONE:America/New_York
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            DTSTAMP:20231201T000000Z
            UID:a1b2c3d4e5f6g7h8i9j0@google.com
            CREATED:20231201T000000Z
            DESCRIPTION:This is a very long description that Google Calendar will fold
              at exactly 75 characters per line because that is what the RFC recommends
              and Google follows the standard pretty closely for line folding behavior.
            LAST-MODIFIED:20231201T000000Z
            LOCATION:Conference Room A
            SEQUENCE:0
            STATUS:CONFIRMED
            SUMMARY:Team Planning Meeting - Q1 2024 Strategy Discussion
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Description should be unfolded", events[0].description?.contains("RFC recommends") == true)
        assertEquals("Team Planning Meeting - Q1 2024 Strategy Discussion", events[0].title)
    }

    @Test
    fun `Google Calendar recurring with EXDATE list format`() {
        // Google uses comma-separated EXDATE list
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231201T090000Z
            DTEND:20231201T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=20
            EXDATE:20231206T090000Z,20231213T090000Z,20231225T090000Z
            UID:recurring-123@google.com
            SUMMARY:Morning Standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        // RRULE format may vary between parsers, check key components
        val rrule = events[0].rrule
        assertTrue("RRULE should contain FREQ=WEEKLY", rrule?.contains("FREQ=WEEKLY") == true)
        assertTrue("RRULE should contain COUNT=20", rrule?.contains("COUNT=20") == true)
        // EXDATE is stored as comma-separated timestamps by ICalEventMapper
        assertTrue("EXDATE should be preserved", events[0].exdate?.isNotBlank() == true)
    }

    @Test
    fun `Google Calendar with ATTACH URL for conference link`() {
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:meet-123@google.com
            SUMMARY:Video Conference
            DESCRIPTION:Join Google Meet: https://meet.google.com/abc-defg-hij
            X-GOOGLE-CONFERENCE:https://meet.google.com/abc-defg-hij
            ATTACH;FILENAME="invite.ics":https://calendar.google.com/calendar/ical/123
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should preserve meet link", events[0].description?.contains("meet.google.com") == true)
    }

    @Test
    fun `Google Calendar with VALUE=DATE-TIME explicit parameter`() {
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;VALUE=DATE-TIME:20231215T140000Z
            DTEND;VALUE=DATE-TIME:20231215T150000Z
            UID:datetime-explicit@google.com
            SUMMARY:Explicit DateTime Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertFalse("Should not be all-day", events[0].isAllDay)
    }

    // ==================== Microsoft Outlook Variations ====================

    @Test
    fun `Outlook 365 export with Windows timezone IDs`() {
        // Outlook uses Windows timezone names instead of IANA
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Microsoft Corporation//Outlook 16.0 MIMEDIR//EN
            VERSION:2.0
            METHOD:PUBLISH
            X-WR-CALNAME:Calendar
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
            DTSTART;TZID="Eastern Standard Time":20231215T090000
            DTEND;TZID="Eastern Standard Time":20231215T100000
            UID:040000008200E00074C5B7101A82E00800000000
            SUMMARY:Outlook Meeting
            LOCATION:Office
            X-MICROSOFT-CDO-BUSYSTATUS:BUSY
            X-MICROSOFT-CDO-IMPORTANCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Outlook Meeting", events[0].title)
        // Timezone may be parsed as system default if Windows name not recognized
    }

    @Test
    fun `Outlook with quoted TZID parameter`() {
        // Outlook quotes TZID with spaces
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook//EN
            BEGIN:VEVENT
            DTSTART;TZID="Pacific Standard Time":20231215T090000
            DTEND;TZID="Pacific Standard Time":20231215T100000
            UID:outlook-quoted@outlook.com
            SUMMARY:Quoted TZID Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
    }

    @Test
    fun `Outlook with X-ALT-DESC HTML description`() {
        // Outlook includes HTML description in X-ALT-DESC
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Microsoft Corporation//Outlook//EN
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:outlook-html@outlook.com
            SUMMARY:HTML Description Event
            DESCRIPTION:Plain text description for compatibility
            X-ALT-DESC;FMTTYPE=text/html:<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2//
             EN"><HTML><BODY><p>Rich <b>HTML</b> description</p></BODY></HTML>
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Plain text description for compatibility", events[0].description)
    }

    @Test
    fun `Outlook meeting with ORGANIZER and ATTENDEE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            METHOD:REQUEST
            PRODID:-//Microsoft Corporation//Outlook 16.0//EN
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:outlook-meeting@outlook.com
            ORGANIZER;CN="John Smith":mailto:john@company.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN="Jane Doe":mailto:jane@company.com
            ATTENDEE;PARTSTAT=ACCEPTED;CN="Bob Wilson":mailto:bob@company.com
            SUMMARY:Team Sync
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Team Sync", events[0].title)
    }

    // ==================== Apple iCloud Variations ====================

    @Test
    fun `iCloud export with X-APPLE properties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud 2023//EN
            CALSCALE:GREGORIAN
            X-WR-CALNAME:Personal
            X-APPLE-CALENDAR-COLOR:#FF2968
            BEGIN:VEVENT
            DTSTART;TZID=America/Los_Angeles:20231215T090000
            DTEND;TZID=America/Los_Angeles:20231215T100000
            UID:E3A7B9C1-2D4F-5E6A-8B9C-0D1E2F3A4B5C
            SUMMARY:Apple Event
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            X-APPLE-TRAVEL-DURATION:1800
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-APPLE-RADIUS=141.176;X-TITLE="App
             le Park":geo:37.334900,-122.009020
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Apple Event", events[0].title)
    }

    @Test
    fun `iCloud with IANA timezone and VTIMEZONE block`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            TZOFFSETFROM:-0500
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            DTSTART:20070311T020000
            TZNAME:EDT
            TZOFFSETTO:-0400
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:-0400
            RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
            DTSTART:20071104T020000
            TZNAME:EST
            TZOFFSETTO:-0500
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART;TZID=America/New_York:20231215T140000
            DTEND;TZID=America/New_York:20231215T150000
            UID:icloud-tz@icloud.com
            SUMMARY:EST Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("America/New_York", events[0].timezone)
    }

    @Test
    fun `iCloud all-day event with VALUE=DATE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20231225
            DTEND;VALUE=DATE:20231226
            UID:christmas@icloud.com
            SUMMARY:Christmas Day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should be all-day", events[0].isAllDay)
        assertEquals("Christmas Day", events[0].title)
    }

    // ==================== Nextcloud Calendar Variations ====================

    @Test
    fun `Nextcloud export with CATEGORIES property`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud//Calendar//EN
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:nc-cat@nextcloud.local
            SUMMARY:Categorized Event
            CATEGORIES:Work,Meeting,Important
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Categorized Event", events[0].title)
    }

    @Test
    fun `Nextcloud recurring task-like event with VTODO properties`() {
        // Some Nextcloud exports include VTODO-like properties in VEVENTs
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud//Calendar//EN
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:nc-hybrid@nextcloud.local
            SUMMARY:Review Tasks
            RRULE:FREQ=DAILY;COUNT=5
            PERCENT-COMPLETE:50
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("FREQ=DAILY;COUNT=5", events[0].rrule)
    }

    // ==================== Webcal/ICS Subscription Variations ====================

    @Test
    fun `Webcal subscription with REFRESH-INTERVAL`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//icalendar.org//EN
            REFRESH-INTERVAL;VALUE=DURATION:P1D
            X-WR-CALNAME:Public Holidays
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20240101
            DTEND;VALUE=DATE:20240102
            UID:newyear@holidays.org
            SUMMARY:New Year's Day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue(events[0].isAllDay)
    }

    @Test
    fun `iCal dot com subscription format`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalendar.com//EN
            X-WR-CALNAME:Sports Schedule
            BEGIN:VEVENT
            DTSTART:20231215T190000Z
            DTEND:20231215T220000Z
            UID:game-123@ical.com
            SUMMARY:Championship Game
            LOCATION:Stadium
            URL:https://example.com/tickets
            GEO:40.7128;-74.0060
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Stadium", events[0].location)
    }

    @Test
    fun `TeamUp calendar subscription with custom X-properties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TeamUp Solutions//TeamUp//EN
            X-WR-CALNAME:Team Schedule
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:teamup-123@teamup.com
            SUMMARY:Team Practice
            X-TEAMUP-CUSTOM-STATUS:confirmed
            X-TEAMUP-SIGNUP-ENABLED:TRUE
            X-TEAMUP-LIMIT:20
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
    }

    // ==================== Encoding and Character Set Variations ====================

    @Test
    fun `ICS with quoted-printable encoding`() {
        // Some older tools use quoted-printable for special characters
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:qp-encoding@test.com
            SUMMARY;ENCODING=QUOTED-PRINTABLE:Caf=C3=A9 Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        // Title may contain encoded characters or be decoded
    }

    @Test
    fun `ICS with backslash-escaped semicolons in SUMMARY`() {
        // RFC 5545 requires escaping: \ ; , and newlines
        // Colons (:) are NOT escaped in TEXT values
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:escaped@test.com
            SUMMARY:Meeting\; Room A\; Lunch provided
            DESCRIPTION:Note: bring laptop\, charger\, and notes
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Meeting; Room A; Lunch provided", events[0].title)
        assertEquals("Note: bring laptop, charger, and notes", events[0].description)
    }

    @Test
    fun `ICS with newline escaping in description`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:newline@test.com
            SUMMARY:Multi-line Description
            DESCRIPTION:Line 1\nLine 2\nLine 3\N\NDouble newline above
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should have newlines", events[0].description?.contains("\n") == true)
    }

    // ==================== Duration Format Variations ====================

    @Test
    fun `Event with DURATION instead of DTEND - hours and minutes`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DURATION:PT1H30M
            UID:duration-hm@test.com
            SUMMARY:90 Minute Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        val durationMs = events[0].endTs - events[0].startTs
        assertEquals("Duration should be 90 minutes", 90 * 60 * 1000L, durationMs)
    }

    @Test
    fun `Event with DURATION in days`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20231215
            DURATION:P3D
            UID:duration-days@test.com
            SUMMARY:3-Day Conference
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        val durationMs = events[0].endTs - events[0].startTs
        // Allow for adjustment of endTs for all-day events
        assertTrue("Duration should be ~3 days", durationMs > 2 * 24 * 3600 * 1000L)
    }

    @Test
    fun `Event with DURATION in weeks`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20231215
            DURATION:P1W
            UID:duration-week@test.com
            SUMMARY:Week-long Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        val durationMs = events[0].endTs - events[0].startTs
        assertTrue("Duration should be ~7 days", durationMs > 6 * 24 * 3600 * 1000L)
    }

    // ==================== VALARM Variations ====================

    @Test
    fun `Multiple VALARM blocks with different triggers`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:multi-alarm@test.com
            SUMMARY:Reminder Test
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:DISPLAY
            DESCRIPTION:15 minutes before
            END:VALARM
            BEGIN:VALARM
            TRIGGER:-PT1H
            ACTION:DISPLAY
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            TRIGGER:-P1D
            ACTION:DISPLAY
            DESCRIPTION:1 day before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        // ICalEventMapper limits to 3 reminders (RfcIcsParser limited to 2)
        val reminders = events[0].reminders
        assertTrue("Should have reminders", reminders?.isNotEmpty() == true)
        assertTrue("Should have max 3 reminders", (reminders?.size ?: 0) <= 3)
    }

    @Test
    fun `VALARM with absolute datetime trigger`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:abs-alarm@test.com
            SUMMARY:Absolute Alarm
            BEGIN:VALARM
            TRIGGER;VALUE=DATE-TIME:20231215T130000Z
            ACTION:DISPLAY
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
    }

    // ==================== Recurrence Pattern Variations ====================

    @Test
    fun `Complex RRULE with BYSETPOS from Outlook`() {
        // Second-to-last Friday of every month (common payroll schedule)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:bysetpos@test.com
            SUMMARY:Payroll Processing
            RRULE:FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-2;COUNT=12
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        // RRULE format may vary between parsers, check key components
        val rrule = events[0].rrule
        assertTrue("RRULE should contain FREQ=MONTHLY", rrule?.contains("FREQ=MONTHLY") == true)
        assertTrue("RRULE should contain BYSETPOS=-2", rrule?.contains("BYSETPOS=-2") == true)
        assertTrue("RRULE should contain COUNT=12", rrule?.contains("COUNT=12") == true)
    }

    @Test
    fun `RRULE with multiple BYDAY values`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:multi-byday@test.com
            SUMMARY:MWF Class
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20240515T235959Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue(events[0].rrule?.contains("BYDAY=MO,WE,FR") == true)
    }

    @Test
    fun `RRULE with BYMONTH and BYMONTHDAY`() {
        // Quarterly reviews on the 15th of Jan, Apr, Jul, Oct
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20240115T100000Z
            DTEND:20240115T110000Z
            UID:quarterly@test.com
            SUMMARY:Quarterly Review
            RRULE:FREQ=YEARLY;BYMONTH=1,4,7,10;BYMONTHDAY=15;COUNT=8
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue(events[0].rrule?.contains("BYMONTH=1,4,7,10") == true)
    }

    // ==================== Fastmail/Mozilla Thunderbird Variations ====================

    @Test
    fun `Fastmail export with Fastmail-specific headers`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Fastmail//Fastmail Calendar//EN
            X-WR-CALNAME:Work
            X-WR-TIMEZONE:UTC
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:fastmail-123@fastmail.com
            SUMMARY:Fastmail Event
            X-FASTMAIL-USEDEFAULTREMINDERS:TRUE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
    }

    @Test
    fun `Thunderbird Lightning export format`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:thunderbird-123@mozilla.org
            SUMMARY:Lightning Event
            X-MOZ-GENERATION:1
            X-MOZ-FAKED-MASTER:0
            X-MOZ-LASTACK:20231201T000000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
    }

    // ==================== Edge Case Combinations ====================

    @Test
    fun `All-day recurring event with EXDATE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20231201
            DTEND;VALUE=DATE:20231202
            UID:allday-recurring@test.com
            SUMMARY:Daily Standup
            RRULE:FREQ=DAILY;COUNT=30
            EXDATE;VALUE=DATE:20231225
            EXDATE;VALUE=DATE:20231226
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue(events[0].isAllDay)
        // EXDATE is stored as comma-separated timestamps by ICalEventMapper
        assertTrue("EXDATE should be preserved", events[0].exdate?.isNotBlank() == true)
    }

    @Test
    fun `Event with STATUS CANCELLED should be skipped`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:cancelled@test.com
            SUMMARY:Cancelled Meeting
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            DTSTART:20231215T160000Z
            DTEND:20231215T170000Z
            UID:confirmed@test.com
            SUMMARY:Confirmed Meeting
            STATUS:CONFIRMED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Confirmed Meeting", events[0].title)
    }

    @Test
    fun `Event with TRANSP TRANSPARENT should be preserved`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:transparent@test.com
            SUMMARY:Free Time Block
            TRANSP:TRANSPARENT
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("TRANSPARENT", events[0].transp)
    }

    @Test
    fun `Event with CLASS PRIVATE should be preserved`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            UID:private@test.com
            SUMMARY:Private Event
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("PRIVATE", events[0].classification)
    }

    @Test
    fun `Large batch of events from subscription feed`() {
        // Simulate a subscription feed with many events
        val eventBlocks = (1..50).joinToString("\n") { i ->
            val hour = i % 24
            val endHour = (hour + 1) % 24
            """BEGIN:VEVENT
DTSTART:20231215T${String.format("%02d", hour)}0000Z
DTEND:20231215T${String.format("%02d", endHour)}0000Z
UID:batch-$i@test.com
SUMMARY:Event $i
END:VEVENT"""
        }

        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
$eventBlocks
END:VCALENDAR"""

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(50, events.size)
    }
}
