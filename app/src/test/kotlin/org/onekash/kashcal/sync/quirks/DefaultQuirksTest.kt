package org.onekash.kashcal.sync.quirks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for DefaultQuirks - generic CalDAV parsing implementation.
 *
 * Uses test fixtures from:
 * - test/resources/caldav/nextcloud/ - Nextcloud-style responses
 * - test/resources/caldav/generic/ - RFC-compliant responses
 */
class DefaultQuirksTest {

    private lateinit var quirks: DefaultQuirks

    @Before
    fun setup() {
        quirks = DefaultQuirks("https://nextcloud.example.com")
    }

    // ========== Property tests ==========

    @Test
    fun `providerId is caldav`() {
        assertEquals("caldav", quirks.providerId)
    }

    @Test
    fun `displayName is CalDAV`() {
        assertEquals("CalDAV", quirks.displayName)
    }

    @Test
    fun `baseUrl returns server URL from constructor`() {
        val customQuirks = DefaultQuirks("https://custom-server.com/dav")
        assertEquals("https://custom-server.com/dav", customQuirks.baseUrl)
    }

    @Test
    fun `requiresAppSpecificPassword is false`() {
        assertFalse(quirks.requiresAppSpecificPassword)
    }

    // ========== extractPrincipalUrl tests ==========

    @Test
    fun `extractPrincipalUrl parses Nextcloud response`() {
        val xml = loadResource("caldav/nextcloud/01_current_user_principal.xml")
        val principal = quirks.extractPrincipalUrl(xml)
        assertEquals("/remote.php/dav/principals/users/testuser/", principal)
    }

    @Test
    fun `extractPrincipalUrl parses generic DAV response`() {
        val xml = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/</href>
                    <propstat>
                        <prop>
                            <current-user-principal>
                                <href>/principals/user123/</href>
                            </current-user-principal>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()
        val principal = quirks.extractPrincipalUrl(xml)
        assertEquals("/principals/user123/", principal)
    }

    @Test
    fun `extractPrincipalUrl handles uppercase namespace`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:propstat>
                        <D:prop>
                            <D:current-user-principal>
                                <D:href>/principals/admin/</D:href>
                            </D:current-user-principal>
                        </D:prop>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()
        val principal = quirks.extractPrincipalUrl(xml)
        assertEquals("/principals/admin/", principal)
    }

    @Test
    fun `extractPrincipalUrl returns null for missing data`() {
        val xml = """<multistatus xmlns="DAV:"><response></response></multistatus>"""
        assertNull(quirks.extractPrincipalUrl(xml))
    }

    // ========== extractCalendarHomeUrl tests ==========

    @Test
    fun `extractCalendarHomeUrl parses Nextcloud response`() {
        val xml = loadResource("caldav/nextcloud/02_calendar_home_set.xml")
        val home = quirks.extractCalendarHomeUrl(xml)
        assertEquals("/remote.php/dav/calendars/testuser/", home)
    }

    @Test
    fun `extractCalendarHomeUrl handles various namespaces`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <C:calendar-home-set>
                                <d:href>/caldav/calendars/user/</d:href>
                            </C:calendar-home-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()
        val home = quirks.extractCalendarHomeUrl(xml)
        assertEquals("/caldav/calendars/user/", home)
    }

    @Test
    fun `extractCalendarHomeUrl returns null for missing data`() {
        val xml = """<multistatus xmlns="DAV:"><response></response></multistatus>"""
        assertNull(quirks.extractCalendarHomeUrl(xml))
    }

    // ========== extractCalendars tests ==========

    @Test
    fun `extractCalendars parses Nextcloud calendar list`() {
        val xml = loadResource("caldav/nextcloud/03_calendar_list.xml")
        val calendars = quirks.extractCalendars(xml, "https://nextcloud.example.com")

        // Should find Personal, Work, and Company Holidays (3 calendars)
        // Should skip: root collection, Tasks, inbox, outbox
        assertEquals(3, calendars.size)

        // Personal calendar
        val personal = calendars.find { it.displayName == "Personal" }
        assertNotNull(personal)
        assertEquals("/remote.php/dav/calendars/testuser/personal/", personal!!.href)
        assertEquals("#0082C9FF", personal.color)
        assertFalse(personal.isReadOnly)

        // Work calendar
        val work = calendars.find { it.displayName == "Work" }
        assertNotNull(work)
        assertEquals("/remote.php/dav/calendars/testuser/work/", work!!.href)
        assertEquals("#FF6B6BFF", work.color)
        assertFalse(work.isReadOnly)

        // Shared read-only calendar
        val shared = calendars.find { it.displayName == "Company Holidays" }
        assertNotNull(shared)
        assertEquals("/remote.php/dav/calendars/testuser/shared-holidays/", shared!!.href)
        assertTrue(shared.isReadOnly)  // No write privilege
    }

    @Test
    fun `extractCalendars parses RFC-compliant generic response`() {
        val xml = loadResource("caldav/generic/rfc_compliant_response.xml")
        val calendars = quirks.extractCalendars(xml, "https://caldav.example.com")

        assertEquals(1, calendars.size)
        val calendar = calendars[0]
        assertEquals("My Calendar", calendar.displayName)
        assertEquals("/calendars/user/default/", calendar.href)
        assertEquals("#FF5733FF", calendar.color)
        assertEquals("ctag-value-12345", calendar.ctag)
        assertFalse(calendar.isReadOnly)
    }

    @Test
    fun `extractCalendars skips root collection without calendar resourcetype`() {
        val xml = loadResource("caldav/nextcloud/03_calendar_list.xml")
        val calendars = quirks.extractCalendars(xml, "https://nextcloud.example.com")

        // Root collection href should NOT be in results
        val root = calendars.find { it.href == "/remote.php/dav/calendars/testuser/" }
        assertNull(root)
    }

    @Test
    fun `extractCalendars skips Tasks calendar`() {
        val xml = loadResource("caldav/nextcloud/03_calendar_list.xml")
        val calendars = quirks.extractCalendars(xml, "https://nextcloud.example.com")

        val tasks = calendars.find { it.displayName == "Tasks" }
        assertNull(tasks)
    }

    @Test
    fun `extractCalendars skips inbox and outbox`() {
        val xml = loadResource("caldav/nextcloud/03_calendar_list.xml")
        val calendars = quirks.extractCalendars(xml, "https://nextcloud.example.com")

        val inbox = calendars.find { it.href.contains("inbox") }
        val outbox = calendars.find { it.href.contains("outbox") }
        assertNull(inbox)
        assertNull(outbox)
    }

    // ========== extractICalData tests ==========

    @Test
    fun `extractICalData parses Nextcloud event report`() {
        val xml = loadResource("caldav/nextcloud/04_event_report.xml")
        val events = quirks.extractICalData(xml)

        assertEquals(3, events.size)

        // Single event
        val standup = events.find { it.href.contains("event1.ics") }
        assertNotNull(standup)
        assertEquals("abc123def456", standup!!.etag)  // Quotes are stripped
        assertTrue(standup.icalData.contains("SUMMARY:Team Standup"))
        assertTrue(standup.icalData.contains("BEGIN:VCALENDAR"))

        // Recurring event
        val weekly = events.find { it.href.contains("event2.ics") }
        assertNotNull(weekly)
        assertTrue(weekly!!.icalData.contains("RRULE:FREQ=WEEKLY"))

        // All-day event
        val allDay = events.find { it.href.contains("event3.ics") }
        assertNotNull(allDay)
        assertTrue(allDay!!.icalData.contains("VALUE=DATE"))
    }

    @Test
    fun `extractICalData parses generic RFC response`() {
        val xml = loadResource("caldav/generic/rfc_compliant_response.xml")
        val events = quirks.extractICalData(xml)

        assertEquals(1, events.size)
        val meeting = events[0]
        assertEquals("/calendars/user/default/meeting.ics", meeting.href)
        assertEquals("etag-meeting-v1", meeting.etag)  // Quotes are stripped
        assertTrue(meeting.icalData.contains("SUMMARY:Project Meeting"))
    }

    @Test
    fun `extractICalData handles XML entity encoding`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/cal/event.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag123"</d:getetag>
                            <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test@example.com
SUMMARY:Meeting &amp; Planning &lt;Q1&gt;
END:VEVENT
END:VCALENDAR</cal:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()
        val events = quirks.extractICalData(xml)

        assertEquals(1, events.size)
        assertTrue(events[0].icalData.contains("SUMMARY:Meeting & Planning <Q1>"))
    }

    @Test
    fun `extractICalData returns empty list for no events`() {
        val xml = """<multistatus xmlns="DAV:"></multistatus>"""
        assertTrue(quirks.extractICalData(xml).isEmpty())
    }

    // ========== extractSyncToken tests ==========

    @Test
    fun `extractSyncToken parses sync-collection response`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val token = quirks.extractSyncToken(xml)
        assertEquals("http://sabre.io/ns/sync/63845d9c3a7b9", token)
    }

    @Test
    fun `extractSyncToken handles various namespace prefixes`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
                <D:sync-token>sync-token-12345</D:sync-token>
            </D:multistatus>
        """.trimIndent()
        val token = quirks.extractSyncToken(xml)
        assertEquals("sync-token-12345", token)
    }

    @Test
    fun `extractSyncToken returns null when not present`() {
        val xml = """<multistatus xmlns="DAV:"></multistatus>"""
        assertNull(quirks.extractSyncToken(xml))
    }

    // ========== extractCtag tests ==========

    @Test
    fun `extractCtag parses getctag element`() {
        val xml = """
            <d:propstat xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:prop>
                    <cs:getctag>ctag-abc123</cs:getctag>
                </d:prop>
            </d:propstat>
        """.trimIndent()
        val ctag = quirks.extractCtag(xml)
        assertEquals("ctag-abc123", ctag)
    }

    @Test
    fun `extractCtag handles quoted ctag`() {
        val xml = loadResource("caldav/nextcloud/03_calendar_list.xml")
        val ctag = quirks.extractCtag(xml)
        // Should find the first ctag
        assertNotNull(ctag)
        assertTrue(ctag!!.startsWith("\""))
    }

    // ========== extractDeletedHrefs tests ==========

    @Test
    fun `extractDeletedHrefs finds 404 responses`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val deleted = quirks.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals("/remote.php/dav/calendars/testuser/personal/deleted-event.ics", deleted[0])
    }

    @Test
    fun `extractDeletedHrefs returns empty for no deletions`() {
        val xml = loadResource("caldav/nextcloud/04_event_report.xml")
        val deleted = quirks.extractDeletedHrefs(xml)
        assertTrue(deleted.isEmpty())
    }

    // ========== extractChangedItems tests ==========

    @Test
    fun `extractChangedItems finds changed and new events`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val items = quirks.extractChangedItems(xml)

        assertEquals(2, items.size)

        // Changed event
        val changed = items.find { it.first.contains("event1.ics") }
        assertNotNull(changed)
        assertEquals("abc123def456-v2", changed!!.second)  // Quotes are stripped

        // New event
        val newEvent = items.find { it.first.contains("event4.ics") }
        assertNotNull(newEvent)
        assertEquals("new123event", newEvent!!.second)  // Quotes are stripped
    }

    @Test
    fun `extractChangedItems excludes deleted items`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val items = quirks.extractChangedItems(xml)

        // Should not include deleted-event.ics (404)
        val deleted = items.find { it.first.contains("deleted-event.ics") }
        assertNull(deleted)
    }

    @Test
    fun `extractChangedItems handles XML entity encoded etags`() {
        // Nextcloud returns &quot; instead of literal quotes in getetag
        val xml = """
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/user/personal/event.ics</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>&quot;820584d69f6962bbb113a0cc9b446de4&quot;</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()
        val items = quirks.extractChangedItems(xml)

        assertEquals(1, items.size)
        // Should decode &quot; and strip quotes
        assertEquals("820584d69f6962bbb113a0cc9b446de4", items[0].second)
    }

    @Test
    fun `extractChangedItems excludes non-ics files`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/user/personal/</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>"collection"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/calendars/user/personal/event.ics</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>"event-etag"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()
        val items = quirks.extractChangedItems(xml)

        assertEquals(1, items.size)
        assertTrue(items[0].first.endsWith(".ics"))
    }

    // ========== buildCalendarUrl tests ==========

    @Test
    fun `buildCalendarUrl returns absolute URL unchanged`() {
        val url = quirks.buildCalendarUrl("https://other.server.com/calendar/", "https://example.com")
        assertEquals("https://other.server.com/calendar/", url)
    }

    @Test
    fun `buildCalendarUrl builds URL from relative href`() {
        val url = quirks.buildCalendarUrl("/remote.php/dav/calendars/user/", "https://nextcloud.example.com")
        assertEquals("https://nextcloud.example.com/remote.php/dav/calendars/user/", url)
    }

    @Test
    fun `buildCalendarUrl handles base host with trailing slash`() {
        val url = quirks.buildCalendarUrl("/calendars/", "https://example.com/")
        assertEquals("https://example.com/calendars/", url)
    }

    @Test
    fun `buildCalendarUrl handles href without leading slash`() {
        val url = quirks.buildCalendarUrl("calendars/user/", "https://example.com")
        assertEquals("https://example.com/calendars/user/", url)
    }

    // ========== buildEventUrl tests ==========

    @Test
    fun `buildEventUrl extracts host from calendar URL`() {
        val url = quirks.buildEventUrl(
            "/remote.php/dav/calendars/user/cal/event.ics",
            "https://nextcloud.example.com/remote.php/dav/calendars/user/cal/"
        )
        assertEquals("https://nextcloud.example.com/remote.php/dav/calendars/user/cal/event.ics", url)
    }

    @Test
    fun `buildEventUrl handles port in calendar URL`() {
        val url = quirks.buildEventUrl(
            "/cal/event.ics",
            "https://server.com:8443/dav/calendars/"
        )
        assertEquals("https://server.com:8443/cal/event.ics", url)
    }

    // ========== shouldSkipCalendar tests ==========

    @Test
    fun `shouldSkipCalendar returns true for inbox`() {
        assertTrue(quirks.shouldSkipCalendar("/user/inbox/", null))
        assertTrue(quirks.shouldSkipCalendar("/calendars/INBOX/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for outbox`() {
        assertTrue(quirks.shouldSkipCalendar("/user/outbox/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for tasks by name`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/todo/", "Tasks"))
        assertTrue(quirks.shouldSkipCalendar("/calendars/reminders-list/", "Reminders"))
    }

    @Test
    fun `shouldSkipCalendar returns true for tasks by href`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/user/tasks/", null))
    }

    @Test
    fun `shouldSkipCalendar returns false for regular calendars`() {
        assertFalse(quirks.shouldSkipCalendar("/calendars/user/personal/", "Personal"))
        assertFalse(quirks.shouldSkipCalendar("/calendars/user/work/", "Work"))
    }

    // ========== isSyncTokenInvalid tests ==========

    @Test
    fun `isSyncTokenInvalid returns true for 403`() {
        assertTrue(quirks.isSyncTokenInvalid(403, ""))
    }

    @Test
    fun `isSyncTokenInvalid returns true for 410`() {
        assertTrue(quirks.isSyncTokenInvalid(410, ""))
    }

    @Test
    fun `isSyncTokenInvalid returns true for valid-sync-token error`() {
        val errorBody = """<error><valid-sync-token/></error>"""
        assertTrue(quirks.isSyncTokenInvalid(400, errorBody))
    }

    @Test
    fun `isSyncTokenInvalid returns false for normal response`() {
        assertFalse(quirks.isSyncTokenInvalid(200, ""))
    }

    // ========== formatDateForQuery tests ==========

    @Test
    fun `formatDateForQuery formats to UTC`() {
        // Jan 15, 2024 12:00:00 UTC
        val millis = 1705320000000L
        val formatted = quirks.formatDateForQuery(millis)
        assertEquals("20240115T000000Z", formatted)
    }

    @Test
    fun `formatDateForQuery handles year boundary`() {
        // Dec 31, 2023 23:59:59 UTC
        val millis = 1704067199000L
        val formatted = quirks.formatDateForQuery(millis)
        assertEquals("20231231T000000Z", formatted)
    }

    // ========== getAdditionalHeaders tests ==========

    @Test
    fun `getAdditionalHeaders includes User-Agent`() {
        val headers = quirks.getAdditionalHeaders()
        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers["User-Agent"]!!.contains("KashCal"))
    }

    // ========== Helper ==========

    private fun loadResource(path: String): String {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }
}
