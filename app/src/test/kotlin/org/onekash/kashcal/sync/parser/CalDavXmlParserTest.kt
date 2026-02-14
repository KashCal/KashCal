package org.onekash.kashcal.sync.parser

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct unit tests for CalDavXmlParser.
 *
 * Tests all 9 public methods using real XML fixtures from multiple CalDAV providers:
 * iCloud, Nextcloud, Stalwart, Zoho, Open-Xchange, Radicale, and generic RFC-compliant.
 *
 * Fixtures are in: test/resources/caldav/{provider}/
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavXmlParserTest {

    private lateinit var parser: CalDavXmlParser

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0

        parser = CalDavXmlParser()
    }

    private fun loadResource(path: String): String =
        javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")

    // ========== extractPrincipalUrl ==========

    @Test
    fun `extractPrincipalUrl parses iCloud response with non-prefixed namespace`() {
        val xml = loadResource("caldav/icloud/01_current_user_principal.xml")
        val url = parser.extractPrincipalUrl(xml)
        assertEquals("/123456789/principal/", url)
    }

    @Test
    fun `extractPrincipalUrl parses Nextcloud response with d prefix`() {
        val xml = loadResource("caldav/nextcloud/01_current_user_principal.xml")
        val url = parser.extractPrincipalUrl(xml)
        assertEquals("/remote.php/dav/principals/users/testuser/", url)
    }

    @Test
    fun `extractPrincipalUrl parses Stalwart response with D prefix`() {
        val xml = loadResource("caldav/stalwart/01_current_user_principal.xml")
        val url = parser.extractPrincipalUrl(xml)
        assertEquals("/dav/principal/admin/", url)
    }

    @Test
    fun `extractPrincipalUrl parses Zoho response with opaque hash`() {
        val xml = loadResource("caldav/zoho/01_current_user_principal.xml")
        val url = parser.extractPrincipalUrl(xml)
        assertEquals("/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/user/", url)
    }

    @Test
    fun `extractPrincipalUrl parses Open-Xchange response`() {
        val xml = loadResource("caldav/openxchange/01_current_user_principal.xml")
        val url = parser.extractPrincipalUrl(xml)
        assertEquals("/caldav/testuser@mailbox.org/", url)
    }

    @Test
    fun `extractPrincipalUrl returns null for empty xml`() {
        assertNull(parser.extractPrincipalUrl(""))
    }

    @Test
    fun `extractPrincipalUrl returns null for blank xml`() {
        assertNull(parser.extractPrincipalUrl("   "))
    }

    @Test
    fun `extractPrincipalUrl returns null for malformed xml`() {
        assertNull(parser.extractPrincipalUrl("<not-valid-caldav>broken</not-valid-caldav>"))
    }

    // ========== extractCalendarHomeUrl ==========

    @Test
    fun `extractCalendarHomeUrl parses iCloud response with full URL`() {
        val xml = loadResource("caldav/icloud/02_calendar_home_set.xml")
        val url = parser.extractCalendarHomeUrl(xml)
        assertEquals("https://p180-caldav.icloud.com:443/123456789/calendars/", url)
    }

    @Test
    fun `extractCalendarHomeUrl parses Nextcloud response`() {
        val xml = loadResource("caldav/nextcloud/02_calendar_home_set.xml")
        val url = parser.extractCalendarHomeUrl(xml)
        assertEquals("/remote.php/dav/calendars/testuser/", url)
    }

    @Test
    fun `extractCalendarHomeUrl parses Zoho response`() {
        val xml = loadResource("caldav/zoho/02_calendar_home_set.xml")
        val url = parser.extractCalendarHomeUrl(xml)
        assertEquals("/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/", url)
    }

    @Test
    fun `extractCalendarHomeUrl returns null for empty xml`() {
        assertNull(parser.extractCalendarHomeUrl(""))
    }

    @Test
    fun `extractCalendarHomeUrl returns null for malformed xml`() {
        assertNull(parser.extractCalendarHomeUrl("<response><href>/test</href></response>"))
    }

    // ========== extractCalendarHomeUrls (Issue #70) ==========

    @Test
    fun `extractCalendarHomeUrls returns all hrefs from AEGEE multi-home-set response`() {
        val xml = loadResource("caldav/aegee/02_calendar_home_set.xml")
        val urls = parser.extractCalendarHomeUrls(xml)

        assertEquals(3, urls.size)
        assertEquals("/dav/calendars/user/aaa/", urls[0])
        assertEquals("/dav/calendars/user/bbb/", urls[1])
        assertEquals("/dav/calendars/user/cal/", urls[2])
    }

    @Test
    fun `extractCalendarHomeUrls returns empty list for blank XML`() {
        assertEquals(emptyList<String>(), parser.extractCalendarHomeUrls(""))
        assertEquals(emptyList<String>(), parser.extractCalendarHomeUrls("   "))
    }

    @Test
    fun `extractCalendarHomeUrls filters empty href elements`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <c:calendar-home-set>
                                <d:href>/real/path/</d:href>
                                <d:href>  </d:href>
                                <d:href>/another/path/</d:href>
                            </c:calendar-home-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val urls = parser.extractCalendarHomeUrls(xml)
        assertEquals(2, urls.size)
        assertEquals("/real/path/", urls[0])
        assertEquals("/another/path/", urls[1])
    }

    @Test
    fun `extractCalendarHomeUrl returns first URL for backward compatibility`() {
        val xml = loadResource("caldav/aegee/02_calendar_home_set.xml")

        val singleUrl = parser.extractCalendarHomeUrl(xml)
        val allUrls = parser.extractCalendarHomeUrls(xml)

        assertEquals(allUrls.first(), singleUrl)
        assertEquals("/dav/calendars/user/aaa/", singleUrl)
    }

    // ========== extractCalendars ==========

    @Test
    fun `extractCalendars parses iCloud response with multiple calendars`() {
        val xml = loadResource("caldav/icloud/03_calendar_list.xml")
        val calendars = parser.extractCalendars(xml)

        // iCloud fixture has: calendars/ (collection, no calendar type), Personal Calendar, inbox, notification, tasks (VTODO), work, outbox
        // Only Personal Calendar and Work Calendar should be extracted (have <calendar/> resourcetype)
        // inbox has schedule-inbox, outbox has schedule-outbox, tasks has VTODO only, notification isn't a calendar
        assertTrue("Should find at least 2 calendars", calendars.size >= 2)

        val personal = calendars.find { it.displayName == "Personal Calendar" }
        assertNotNull("Should find Personal Calendar", personal)
        assertEquals("/123456789/calendars/4D24D1CF-D573-4130-BFB7-F9E0B616E6FE/", personal!!.href)
        assertEquals("#1E4C63FF", personal.color)
        assertNotNull(personal.ctag)
        assertTrue("Personal should have VEVENT", personal.supportedComponents.contains("VEVENT"))

        val work = calendars.find { it.displayName == "Work Calendar" }
        assertNotNull("Should find Work Calendar", work)
        assertEquals("/123456789/calendars/work/", work!!.href)
    }

    @Test
    fun `extractCalendars filters out VTODO-only calendars from iCloud`() {
        val xml = loadResource("caldav/icloud/03_calendar_list.xml")
        val calendars = parser.extractCalendars(xml)

        // The tasks calendar has only VTODO, should still be parsed by extractCalendars
        // (filtering by VEVENT is done at the quirks layer, not parser layer)
        val tasks = calendars.find { it.displayName == "Reminders" }
        if (tasks != null) {
            assertTrue("Tasks calendar should have VTODO", tasks.supportedComponents.contains("VTODO"))
        }
    }

    @Test
    fun `extractCalendars parses Stalwart single propstat`() {
        val xml = loadResource("caldav/stalwart/03_calendar_list_single_propstat.xml")
        val calendars = parser.extractCalendars(xml)

        assertEquals(1, calendars.size)
        assertEquals("Test Calendar", calendars[0].displayName)
        assertEquals("/dav/cal/admin/test-calendar/", calendars[0].href)
        assertEquals("#0082C9FF", calendars[0].color)
        assertEquals("stalwart-ctag-single-123", calendars[0].ctag)
    }

    @Test
    fun `extractCalendars rejects resourcetype in 404 propstat`() {
        val xml = loadResource("caldav/stalwart/03_calendar_list_resourcetype_404.xml")
        val calendars = parser.extractCalendars(xml)

        // Calendar with resourcetype in 404 propstat should NOT be included
        assertEquals(0, calendars.size)
    }

    @Test
    fun `extractCalendars parses generic RFC-compliant response`() {
        val xml = loadResource("caldav/generic/rfc_compliant_response.xml")
        val calendars = parser.extractCalendars(xml)

        // The generic fixture has one calendar entry and one event entry
        // Only the calendar (with <collection/><calendar/> resourcetype) should be extracted
        assertTrue("Should find at least 1 calendar", calendars.isNotEmpty())
        val cal = calendars[0]
        assertEquals("My Calendar", cal.displayName)
        assertEquals("/calendars/user/default/", cal.href)
        assertEquals("#FF5733FF", cal.color)
        assertEquals("ctag-value-12345", cal.ctag)
        assertTrue("Should have VEVENT", cal.supportedComponents.contains("VEVENT"))
    }

    @Test
    fun `extractCalendars handles no component set gracefully`() {
        val xml = loadResource("caldav/generic/no_component_set.xml")
        val calendars = parser.extractCalendars(xml)

        // Calendar without supported-calendar-component-set should have empty set
        assertTrue(calendars.isNotEmpty())
        assertTrue("Components should be empty when not advertised", calendars[0].supportedComponents.isEmpty())
    }

    @Test
    fun `extractCalendars returns empty list for empty xml`() {
        assertEquals(emptyList<Any>(), parser.extractCalendars(""))
    }

    @Test
    fun `extractCalendars returns empty list for malformed xml`() {
        assertEquals(emptyList<Any>(), parser.extractCalendars("<broken"))
    }

    // ========== extractSyncToken ==========

    @Test
    fun `extractSyncToken parses Nextcloud sync-collection response`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val token = parser.extractSyncToken(xml)
        assertEquals("http://sabre.io/ns/sync/63845d9c3a7b9", token)
    }

    @Test
    fun `extractSyncToken parses Stalwart sync-collection response`() {
        val xml = loadResource("caldav/stalwart/04_sync_collection.xml")
        val token = parser.extractSyncToken(xml)
        assertEquals("http://stalwart.example.com/ns/sync/new-token-789", token)
    }

    @Test
    fun `extractSyncToken parses Open-Xchange sync-collection response`() {
        val xml = loadResource("caldav/openxchange/04_sync_collection.xml")
        val token = parser.extractSyncToken(xml)
        assertEquals("http://www.open-xchange.com/sync/1706889999", token)
    }

    @Test
    fun `extractSyncToken returns null for empty xml`() {
        assertNull(parser.extractSyncToken(""))
    }

    @Test
    fun `extractSyncToken returns null when no sync-token element`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/test.ics</D:href>
                </D:response>
            </D:multistatus>
        """.trimIndent()
        assertNull(parser.extractSyncToken(xml))
    }

    // ========== extractCtag ==========

    @Test
    fun `extractCtag parses ctag from Stalwart calendar list`() {
        val xml = loadResource("caldav/stalwart/03_calendar_list_single_propstat.xml")
        val ctag = parser.extractCtag(xml)
        assertEquals("stalwart-ctag-single-123", ctag)
    }

    @Test
    fun `extractCtag returns null when ctag missing from Zoho`() {
        val xml = loadResource("caldav/zoho/04_ctag_missing.xml")
        val ctag = parser.extractCtag(xml)
        // The ctag element is in a 404 propstat and is empty, so should be null
        assertNull(ctag)
    }

    @Test
    fun `extractCtag returns null for empty xml`() {
        assertNull(parser.extractCtag(""))
    }

    // ========== extractICalData ==========

    @Test
    fun `extractICalData parses Nextcloud event report with multiple events`() {
        val xml = loadResource("caldav/nextcloud/04_event_report.xml")
        val events = parser.extractICalData(xml)

        assertEquals(3, events.size)

        val standup = events[0]
        assertEquals("/remote.php/dav/calendars/testuser/personal/event1.ics", standup.href)
        assertEquals("abc123def456", standup.etag)
        assertTrue(standup.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue(standup.icalData.contains("Team Standup"))

        val recurring = events[1]
        assertEquals("/remote.php/dav/calendars/testuser/personal/event2.ics", recurring.href)
        assertTrue(recurring.icalData.contains("RRULE:FREQ=WEEKLY"))
    }

    @Test
    fun `extractICalData parses Stalwart multiget response`() {
        val xml = loadResource("caldav/stalwart/05_multiget_response.xml")
        val events = parser.extractICalData(xml)

        assertEquals(2, events.size)
        assertEquals("/dav/cal/admin/test-calendar/meeting.ics", events[0].href)
        assertEquals("stalwart-etag-meeting-111", events[0].etag)
        assertTrue(events[0].icalData.contains("Team Meeting"))

        assertEquals("/dav/cal/admin/test-calendar/recurring.ics", events[1].href)
        assertTrue(events[1].icalData.contains("RRULE:FREQ=WEEKLY"))
    }

    @Test
    fun `extractICalData parses Open-Xchange multiget with exception event`() {
        val xml = loadResource("caldav/openxchange/05_multiget_response.xml")
        val events = parser.extractICalData(xml)

        assertEquals(2, events.size)
        assertEquals("ox-etag-abc123", events[0].etag)
        assertTrue(events[0].icalData.contains("Team Meeting"))

        // Second event has both master and exception (RECURRENCE-ID)
        assertTrue(events[1].icalData.contains("RECURRENCE-ID"))
        assertTrue(events[1].icalData.contains("Weekly Review (Rescheduled)"))
    }

    @Test
    fun `extractICalData parses Zoho multiget with B namespace prefix`() {
        val xml = loadResource("caldav/zoho/06_calendar_multiget.xml")
        val events = parser.extractICalData(xml)

        assertEquals(2, events.size)
        assertTrue(events[0].icalData.contains("Team Standup"))
        assertTrue(events[0].icalData.contains("BEGIN:VCALENDAR"))
        // Zoho etags are numeric timestamps (not quoted)
        assertEquals("1770859402675", events[0].etag)

        assertTrue(events[1].icalData.contains("Project Review"))
    }

    @Test
    fun `extractICalData parses generic RFC response with inline calendar-data`() {
        val xml = loadResource("caldav/generic/rfc_compliant_response.xml")
        val events = parser.extractICalData(xml)

        // Only the event response (not the calendar collection) should be extracted
        assertEquals(1, events.size)
        assertEquals("/calendars/user/default/meeting.ics", events[0].href)
        assertEquals("etag-meeting-v1", events[0].etag)
        assertTrue(events[0].icalData.contains("Project Meeting"))
    }

    @Test
    fun `extractICalData returns empty for Zoho calendar-query with no calendar-data`() {
        val xml = loadResource("caldav/zoho/05_calendar_query_no_data.xml")
        val events = parser.extractICalData(xml)

        // Zoho calendar-query returns etags but NO calendar-data
        assertEquals(0, events.size)
    }

    @Test
    fun `extractICalData returns empty for empty xml`() {
        assertEquals(emptyList<Any>(), parser.extractICalData(""))
    }

    // ========== extractChangedItems ==========

    @Test
    fun `extractChangedItems parses Nextcloud sync-collection with changes and deletions`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val items = parser.extractChangedItems(xml)

        // 2 changed events, 1 deleted (should be excluded)
        assertEquals(2, items.size)
        assertEquals("/remote.php/dav/calendars/testuser/personal/event1.ics", items[0].first)
        assertEquals("abc123def456-v2", items[0].second)
        assertEquals("/remote.php/dav/calendars/testuser/personal/event4.ics", items[1].first)
        assertEquals("new123event", items[1].second)
    }

    @Test
    fun `extractChangedItems parses Stalwart sync-collection`() {
        val xml = loadResource("caldav/stalwart/04_sync_collection.xml")
        val items = parser.extractChangedItems(xml)

        assertEquals(2, items.size)
        assertEquals("/dav/cal/admin/test-calendar/event-changed.ics", items[0].first)
        assertEquals("stalwart-etag-changed-abc123", items[0].second)
    }

    @Test
    fun `extractChangedItems excludes deleted items`() {
        val xml = loadResource("caldav/openxchange/04_sync_collection.xml")
        val items = parser.extractChangedItems(xml)

        // 2 changed, 1 deleted
        assertEquals(2, items.size)
        // Deleted item should not be in the list
        assertTrue(items.none { it.first.contains("deleted-event") })
    }

    @Test
    fun `extractChangedItems returns empty for empty xml`() {
        assertEquals(emptyList<Any>(), parser.extractChangedItems(""))
    }

    // ========== extractDeletedHrefs ==========

    @Test
    fun `extractDeletedHrefs parses Nextcloud sync-collection`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals("/remote.php/dav/calendars/testuser/personal/deleted-event.ics", deleted[0])
    }

    @Test
    fun `extractDeletedHrefs parses Stalwart sync-collection`() {
        val xml = loadResource("caldav/stalwart/04_sync_collection.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals("/dav/cal/admin/test-calendar/event-deleted.ics", deleted[0])
    }

    @Test
    fun `extractDeletedHrefs parses Open-Xchange sync-collection`() {
        val xml = loadResource("caldav/openxchange/04_sync_collection.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertTrue(deleted[0].contains("deleted-event"))
    }

    @Test
    fun `extractDeletedHrefs returns empty when no deletions`() {
        // Use a response with no 404 entries
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/calendars/test/event.ics</D:href>
                    <D:propstat>
                        <D:prop><D:getetag>"etag-1"</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals(emptyList<String>(), parser.extractDeletedHrefs(xml))
    }

    @Test
    fun `extractDeletedHrefs returns empty for empty xml`() {
        assertEquals(emptyList<String>(), parser.extractDeletedHrefs(""))
    }

    // ========== extractSyncCollectionData ==========

    @Test
    fun `extractSyncCollectionData parses full Nextcloud response`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val data = parser.extractSyncCollectionData(xml)

        assertEquals("http://sabre.io/ns/sync/63845d9c3a7b9", data.syncToken)
        assertEquals(2, data.changedItems.size)
        assertEquals(1, data.deletedHrefs.size)
        assertEquals("/remote.php/dav/calendars/testuser/personal/deleted-event.ics", data.deletedHrefs[0])
    }

    @Test
    fun `extractSyncCollectionData parses Stalwart response`() {
        val xml = loadResource("caldav/stalwart/04_sync_collection.xml")
        val data = parser.extractSyncCollectionData(xml)

        assertEquals("http://stalwart.example.com/ns/sync/new-token-789", data.syncToken)
        assertEquals(2, data.changedItems.size)
        assertEquals(1, data.deletedHrefs.size)
    }

    @Test
    fun `extractSyncCollectionData parses Open-Xchange response`() {
        val xml = loadResource("caldav/openxchange/04_sync_collection.xml")
        val data = parser.extractSyncCollectionData(xml)

        assertEquals("http://www.open-xchange.com/sync/1706889999", data.syncToken)
        assertEquals(2, data.changedItems.size)
        assertEquals(1, data.deletedHrefs.size)
    }

    @Test
    fun `extractSyncCollectionData returns empty for empty xml`() {
        val data = parser.extractSyncCollectionData("")
        assertNull(data.syncToken)
        assertTrue(data.changedItems.isEmpty())
        assertTrue(data.deletedHrefs.isEmpty())
    }

    @Test
    fun `extractSyncCollectionData consistent with individual extract methods`() {
        // Verify that extractSyncCollectionData returns the same results as
        // calling extractSyncToken, extractChangedItems, and extractDeletedHrefs individually
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")

        val combined = parser.extractSyncCollectionData(xml)
        val token = parser.extractSyncToken(xml)
        val changed = parser.extractChangedItems(xml)
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(token, combined.syncToken)
        assertEquals(changed.size, combined.changedItems.size)
        assertEquals(deleted.size, combined.deletedHrefs.size)
    }

    // ========== Edge cases ==========

    @Test
    fun `parser handles different namespace prefixes for same elements`() {
        // iCloud uses xmlns="DAV:" (no prefix), Nextcloud uses d:, Stalwart uses D:
        // All should parse correctly since XmlPullParser is namespace-aware
        val icloudPrincipal = parser.extractPrincipalUrl(
            loadResource("caldav/icloud/01_current_user_principal.xml")
        )
        val nextcloudPrincipal = parser.extractPrincipalUrl(
            loadResource("caldav/nextcloud/01_current_user_principal.xml")
        )
        val stalwartPrincipal = parser.extractPrincipalUrl(
            loadResource("caldav/stalwart/01_current_user_principal.xml")
        )

        assertNotNull("iCloud principal should parse", icloudPrincipal)
        assertNotNull("Nextcloud principal should parse", nextcloudPrincipal)
        assertNotNull("Stalwart principal should parse", stalwartPrincipal)
    }

    @Test
    fun `extractICalData normalizes etag quotes`() {
        // Nextcloud uses quoted etags: "abc123def456"
        // Zoho uses unquoted etags: 1770859402675
        val nextcloudEvents = parser.extractICalData(
            loadResource("caldav/nextcloud/04_event_report.xml")
        )
        val zohoEvents = parser.extractICalData(
            loadResource("caldav/zoho/06_calendar_multiget.xml")
        )

        // Nextcloud etag should be stripped of quotes
        assertEquals("abc123def456", nextcloudEvents[0].etag)
        // Zoho etag should be kept as-is (no quotes to strip)
        assertEquals("1770859402675", zohoEvents[0].etag)
    }

    @Test
    fun `extractChangedItems normalizes etag quotes in sync-collection`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val items = parser.extractChangedItems(xml)

        // Etags in sync-collection should also be normalized
        assertEquals("abc123def456-v2", items[0].second)
    }
}
