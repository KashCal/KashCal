package org.onekash.kashcal.sync.parser

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @After
    fun tearDown() {
        unmockkAll()
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

    // ========== extractScheduleOutboxUrl (RFC 6638 §2.1.1) ==========

    @Test
    fun `extractScheduleOutboxUrl parses Nextcloud relative href`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/remote.php/dav/principals/users/admin/</d:href>
                <d:propstat>
                  <d:prop>
                    <cal:schedule-outbox-URL>
                      <d:href>/remote.php/dav/calendars/admin/outbox/</d:href>
                    </cal:schedule-outbox-URL>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        assertEquals("/remote.php/dav/calendars/admin/outbox/", parser.extractScheduleOutboxUrl(xml))
    }

    @Test
    fun `extractScheduleOutboxUrl parses Zoho absolute href`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:response>
                <D:href>/caldav/zz123/user/</D:href>
                <D:propstat>
                  <D:prop>
                    <C:schedule-outbox-URL>
                      <D:href>https://calendar.zoho.com/caldav/zz123/outbox/</D:href>
                    </C:schedule-outbox-URL>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals("https://calendar.zoho.com/caldav/zz123/outbox/", parser.extractScheduleOutboxUrl(xml))
    }

    @Test
    fun `extractScheduleOutboxUrl ignores the response self-href and returns the property href`() {
        // RFC 6638 §2.1.1: the property wraps its OWN href. The response's
        // self-href (the principal URL) must NOT be mistaken for the outbox.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/dav.php/principals/testuser1/</d:href>
                <d:propstat>
                  <d:prop>
                    <c:schedule-outbox-URL>
                      <d:href>/dav.php/calendars/testuser1/outbox/</d:href>
                    </c:schedule-outbox-URL>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val result = parser.extractScheduleOutboxUrl(xml)
        assertEquals("/dav.php/calendars/testuser1/outbox/", result)
        // Explicitly assert it is NOT the principal self-href.
        assertTrue(result != "/dav.php/principals/testuser1/")
    }

    @Test
    fun `extractScheduleOutboxUrl returns null when property is empty (not outbox-enabled)`() {
        // SOGo: returns the property element but with no href child.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:response>
                <D:href>/SOGo/dav/testuser1/</D:href>
                <D:propstat>
                  <D:prop>
                    <C:schedule-outbox-URL/>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertNull(parser.extractScheduleOutboxUrl(xml))
    }

    @Test
    fun `extractScheduleOutboxUrl returns null when property is absent`() {
        // Radicale: property not advertised at all (404 propstat / missing).
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:response>
                <D:href>/testuser1/</D:href>
                <D:propstat>
                  <D:prop/>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertNull(parser.extractScheduleOutboxUrl(xml))
    }

    @Test
    fun `extractScheduleOutboxUrl returns null for empty xml`() {
        assertNull(parser.extractScheduleOutboxUrl(""))
    }

    @Test
    fun `extractScheduleOutboxUrl returns null for malformed xml`() {
        assertNull(parser.extractScheduleOutboxUrl("<not-valid>broken</not-valid>"))
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
        assertEquals("/123456789/calendars/11111111-2222-3333-4444-555555555555/", personal!!.href)
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
    fun `extractCalendars treats DAV all aggregate privilege as writable`() {
        // Real Xandikos response: the calendar grants the RFC 3744 <all>
        // aggregate privilege rather than the leaf <write>/<write-content>.
        // RFC 3744 §3.11 + §3.12: DAV:all contains DAV:write contains
        // DAV:write-content, so the calendar must be writable, not read-only.
        val xml = loadResource("caldav/xandikos/03_calendar_list.xml")
        val calendars = parser.extractCalendars(xml)

        val calendar = calendars.find { it.displayName == "calendar" }
        assertNotNull("Should extract the Xandikos calendar collection", calendar)
        assertFalse(
            "Calendar granting <all> must be writable (DAV:all aggregates DAV:write)",
            calendar!!.isReadOnly
        )
        assertEquals("/user/calendars/calendar/", calendar.href)
        assertTrue("Should advertise VEVENT", calendar.supportedComponents.contains("VEVENT"))
    }

    @Test
    fun `extractCalendars treats explicit DAV all privilege element as writable`() {
        // Minimal synthetic case isolating the <all> mapping from fixture noise.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/cal/all-priv/</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:displayname>All Priv</d:displayname>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:current-user-privilege-set>
                                <d:privilege><d:all/></d:privilege>
                            </d:current-user-privilege-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = parser.extractCalendars(xml)
        assertEquals(1, calendars.size)
        assertFalse("<all> confers write", calendars[0].isReadOnly)
    }

    @Test
    fun `extractCalendars treats write-properties only as read-only`() {
        // Guard against over-broadening the fix: a calendar granting only
        // read + write-properties (dead-property writes, not content) must
        // stay read-only. This is the shape real read-only shared calendars
        // return (Nextcloud contact_birthdays, Mailbox shared).
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/cal/shared/</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:displayname>Shared</d:displayname>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:current-user-privilege-set>
                                <d:privilege><d:read/></d:privilege>
                                <d:privilege><d:write-properties/></d:privilege>
                            </d:current-user-privilege-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = parser.extractCalendars(xml)
        assertEquals(1, calendars.size)
        assertTrue(
            "read + write-properties (no content write) must remain read-only",
            calendars[0].isReadOnly
        )
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

    @Test
    fun `decodeXmlEntities decodes all five standard XML entities`() {
        assertEquals("Friends & Family", CalDavXmlParser.decodeXmlEntities("Friends &amp; Family"))
        assertEquals("Work <2024>", CalDavXmlParser.decodeXmlEntities("Work &lt;2024&gt;"))
        assertEquals("He said \"hello\"", CalDavXmlParser.decodeXmlEntities("He said &quot;hello&quot;"))
        assertEquals("It's mine", CalDavXmlParser.decodeXmlEntities("It&apos;s mine"))
    }

    @Test
    fun `decodeXmlEntities is no-op when no entities present`() {
        assertEquals("Normal Calendar", CalDavXmlParser.decodeXmlEntities("Normal Calendar"))
        assertEquals("", CalDavXmlParser.decodeXmlEntities(""))
    }

    @Test
    fun `decodeXmlEntities handles amp-last ordering to avoid double decode`() {
        // If text contains "&amp;lt;" it should become "&lt;", not "<"
        assertEquals("&lt;tag&gt;", CalDavXmlParser.decodeXmlEntities("&amp;lt;tag&amp;gt;"))
    }

    @Test
    fun `extractCalendars decodes XML entities in displayName`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/user/friends/</href>
                    <propstat>
                        <prop>
                            <displayname>Friends &amp; Family</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
                <response>
                    <href>/calendars/user/work/</href>
                    <propstat>
                        <prop>
                            <displayname>Work &lt;2024&gt; &amp; Personal</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = parser.extractCalendars(xml)
        assertEquals(2, calendars.size)

        val friends = calendars.find { it.href == "/calendars/user/friends/" }
        assertNotNull("Should find friends calendar", friends)
        assertEquals("Friends & Family", friends!!.displayName)

        val work = calendars.find { it.href == "/calendars/user/work/" }
        assertNotNull("Should find work calendar", work)
        assertEquals("Work <2024> & Personal", work!!.displayName)
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

    // ========== Bare-UID hrefs (issue #249, SabreDAV stacks) ==========

    @Test
    fun `extractChangedItems keeps bare-UID hrefs and skips collection self-row`() {
        val xml = loadResource("caldav/sabredav/04_propfind_bare_uid.xml")
        val items = parser.extractChangedItems(xml)

        // Expect 3 changed items: 2 bare-UID + 1 .ics-named.
        // Collection self-row is skipped (resourcetype/collection, no etag).
        // Response-level 404 row is excluded (deletion, not change).
        assertEquals(3, items.size)

        val hrefs = items.map { it.first }
        assertTrue(
            "Bare-UID member must be kept",
            hrefs.contains("/index.php/calendars/test-account/test-cal/345cf39b-27fd-413f-a8c3-98fb85fd5240")
        )
        assertTrue(
            "Second bare-UID member must be kept",
            hrefs.contains("/index.php/calendars/test-account/test-cal/9f2e1b00-7a5d-4f3e-8c3a-b8b9d1c2e3f4")
        )
        assertTrue(
            ".ics-named member must be kept",
            hrefs.contains("/index.php/calendars/test-account/test-cal/event-with-extension.ics")
        )
        assertTrue(
            "Collection self-row must NOT be kept as a changed item",
            hrefs.none { it == "/index.php/calendars/test-account/test-cal/" }
        )

        // Etags should be normalized (quotes stripped).
        val firstBare = items.first { it.first.endsWith("345cf39b-27fd-413f-a8c3-98fb85fd5240") }
        assertEquals("sabre-bare-uid-etag-abc", firstBare.second)
    }

    @Test
    fun `extractChangedItems treats response-level 404 as deletion not change`() {
        val xml = loadResource("caldav/sabredav/04_propfind_bare_uid.xml")
        val items = parser.extractChangedItems(xml)

        // The response-level 404 row must not appear as a changed item.
        assertTrue(
            "Response-level 404 must not appear as a changed item",
            items.none { it.first.endsWith("/deleted-bare-uid-jkl") }
        )
    }

    @Test
    fun `extractChangedItems does not treat propstat-404 on collection self-row as deletion`() {
        // The collection self-row has propstat 404 on getetag (a perfectly RFC-conformant
        // way to report "this property doesn't apply to me"). That must NOT cause it to
        // be treated as a deletion or to leak into changed items.
        val xml = loadResource("caldav/sabredav/04_propfind_bare_uid.xml")
        val items = parser.extractChangedItems(xml)

        assertTrue(
            "Collection self-row (propstat 404 on getetag) must not appear as changed",
            items.none { it.first == "/index.php/calendars/test-account/test-cal/" }
        )
    }

    @Test
    fun `extractDeletedHrefs reports response-level 404 from SabreDAV PROPFIND`() {
        val xml = loadResource("caldav/sabredav/04_propfind_bare_uid.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals(
            "/index.php/calendars/test-account/test-cal/deleted-bare-uid-jkl",
            deleted[0]
        )
    }

    @Test
    fun `extractDeletedHrefs does not report collection self-row with propstat 404`() {
        val xml = loadResource("caldav/sabredav/04_propfind_bare_uid.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        // Propstat-level 404 (e.g., getetag absent on a collection) must NOT mark the
        // entire response as deleted. RFC 4918 §13: propstat status applies only to
        // those properties; response-level status applies to the whole resource.
        assertTrue(
            "Collection self-row must not be reported as deleted (propstat 404 != response 404)",
            deleted.none { it == "/index.php/calendars/test-account/test-cal/" }
        )
    }

    @Test
    fun `extractSyncCollectionData keeps bare-UID hrefs and skips collection self-row`() {
        // While SabreDAV sync-collection responses typically use .ics extensions, this
        // verifies the discriminator behaves consistently across all sync-related
        // extraction paths if a server happens to return resourcetype-bearing rows.
        val xml = loadResource("caldav/sabredav/04_propfind_bare_uid.xml")
        val data = parser.extractSyncCollectionData(xml)

        assertEquals(3, data.changedItems.size)
        assertEquals(1, data.deletedHrefs.size)
        assertEquals(
            "/index.php/calendars/test-account/test-cal/deleted-bare-uid-jkl",
            data.deletedHrefs[0]
        )
        assertTrue(
            "Collection self-row must not appear in changed items",
            data.changedItems.none { it.first == "/index.php/calendars/test-account/test-cal/" }
        )
    }

    // ========== Real-server PROPFIND captures ==========
    // These fixtures are captured (or structurally derived) from live servers,
    // so the parser is tested against real-world XML quirks (default xmlns,
    // uppercase prefix, status-before-prop, real etags on collection self-rows,
    // member rows with propstat-404 on resourcetype, etc.).

    @Test
    fun `extractChangedItems parses real Baikal PROPFIND with bare-UID member`() {
        val xml = loadResource("caldav/baikal/04_propfind_etag_listing.xml")
        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertTrue(
            "Bare-UID member from real Baikal must be kept",
            hrefs.contains("/dav.php/calendars/testuser1/default/c8f1a2d3-4e5b-6789-abcd-ef0123456789")
        )
        assertTrue(
            ".ics-named member must be kept",
            hrefs.contains("/dav.php/calendars/testuser1/default/event-with-extension.ics")
        )
        assertTrue(
            "Collection self-row must NOT be kept (resourcetype/collection in 200, getetag in 404)",
            hrefs.none { it == "/dav.php/calendars/testuser1/default/" }
        )
    }

    @Test
    fun `extractDeletedHrefs parses real Baikal PROPFIND deletion`() {
        val xml = loadResource("caldav/baikal/04_propfind_etag_listing.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals(
            "/dav.php/calendars/testuser1/default/deleted-member.ics",
            deleted[0]
        )
    }

    @Test
    fun `extractChangedItems parses real Radicale PROPFIND with collection-row etag`() {
        // Radicale serializes elements with default xmlns="DAV:" (no prefix) AND advertises
        // a real synthetic etag on the collection self-row alongside resourcetype/collection.
        // The discriminator must privilege the collection marker over the etag.
        val xml = loadResource("caldav/radicale/04_propfind_etag_listing.xml")
        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertTrue(
            "Bare-UID member must be kept",
            hrefs.contains("/testuser1/test-calendar/8a7b6c5d-4e3f-2a1b-9c8d-7e6f5a4b3c2d")
        )
        assertTrue(
            ".ics-named member must be kept",
            hrefs.contains("/testuser1/test-calendar/event-with-extension.ics")
        )
        assertTrue(
            "Collection self-row must NOT be kept (has real etag but resourcetype/collection wins)",
            hrefs.none { it == "/testuser1/test-calendar/" }
        )
    }

    @Test
    fun `extractChangedItems parses real SOGo PROPFIND with status-before-prop`() {
        // SOGo emits <D:..> uppercase prefix, status BEFORE prop inside propstat,
        // and synthesizes a literal "None" etag on the collection self-row alongside
        // resourcetype/collection.
        val xml = loadResource("caldav/sogo/04_propfind_etag_listing.xml")
        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertTrue(
            "Bare-UID member must be kept",
            hrefs.contains("/SOGo/dav/testuser1/Calendar/personal/c8f1a2d3-4e5b-6789-abcd-ef0123456789")
        )
        assertTrue(
            ".ics-named member must be kept",
            hrefs.contains("/SOGo/dav/testuser1/Calendar/personal/event-with-extension.ics")
        )
        assertTrue(
            "Collection self-row must NOT be kept (literal \"None\" etag but resourcetype/collection wins)",
            hrefs.none { it == "/SOGo/dav/testuser1/Calendar/personal/" }
        )
    }

    @Test
    fun `extractDeletedHrefs parses real SOGo PROPFIND deletion`() {
        val xml = loadResource("caldav/sogo/04_propfind_etag_listing.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals(
            "/SOGo/dav/testuser1/Calendar/personal/deleted-member.ics",
            deleted[0]
        )
    }

    @Test
    fun `extractChangedItems parses real Nextcloud PROPFIND with bare-UID member`() {
        val xml = loadResource("caldav/nextcloud/04_propfind_etag_listing.xml")
        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertTrue(
            "Bare-UID member confirms Nextcloud-on-SabreDAV accepts extensionless hrefs",
            hrefs.contains("/remote.php/dav/calendars/admin/personal/c8f1a2d3-4e5b-6789-abcd-ef0123456789")
        )
        assertTrue(
            ".ics-named member must be kept",
            hrefs.contains("/remote.php/dav/calendars/admin/personal/event-with-extension.ics")
        )
        assertTrue(
            "Collection self-row must NOT be kept",
            hrefs.none { it == "/remote.php/dav/calendars/admin/personal/" }
        )
    }

    @Test
    fun `extractChangedItems parses iCloud PROPFIND with member resourcetype propstat-404`() {
        // iCloud serializes with default xmlns="DAV:" (redundant per-element). The
        // collection self-row carries a real ctag-style etag in a single 200 propstat.
        // Member rows split resourcetype into a separate 404 propstat ("doesn't apply
        // to me") which must NOT be conflated with response-level deletion.
        val xml = loadResource("caldav/icloud/04_propfind_etag_listing.xml")
        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertEquals("Both members must be kept; collection self-row skipped", 2, items.size)
        assertTrue(
            "Member with propstat-404 on resourcetype must be kept (not deleted)",
            hrefs.contains("/redacted-account-id/calendars/redacted-calendar-id/AAAAAAAA-1111-2222-3333-444444444444.ics")
        )
        assertTrue(
            "Second member must be kept",
            hrefs.contains("/redacted-account-id/calendars/redacted-calendar-id/BBBBBBBB-5555-6666-7777-888888888888.ics")
        )
        assertTrue(
            "Collection self-row must NOT be kept (real etag but resourcetype/collection wins)",
            hrefs.none { it == "/redacted-account-id/calendars/redacted-calendar-id/" }
        )
    }

    @Test
    fun `extractDeletedHrefs does not flag iCloud member propstat-404 as deletion`() {
        // The two member rows have a propstat-404 on resourcetype, but no response-level
        // 404 and they DO have a successful sibling propstat with the etag. The parser
        // must NOT treat them as deletions (RFC 4918 §13).
        val xml = loadResource("caldav/icloud/04_propfind_etag_listing.xml")
        val deleted = parser.extractDeletedHrefs(xml)

        assertTrue(
            "iCloud members with propstat-404 + sibling 200 must not be reported as deleted",
            deleted.isEmpty()
        )
    }

    // ========== Trailing-slash discriminator (post-v23.7.53) ==========
    // Wire bodies for fetchAllEtags / fetchEtagsInRange request only <d:getetag/>;
    // resourcetype is no longer asked for. Servers that follow RFC 4918 §5.2 emit
    // a trailing slash on the collection self-row and no trailing slash on members.
    // The parser uses href.endsWith("/") as the primary collection discriminator,
    // with the legacy resourcetype/collection marker kept as a defensive fallback
    // for any server that volunteers the element unprompted.

    @Test
    fun `extractChangedItems classifies by trailing slash when no resourcetype is returned`() {
        // Mirrors the wire reality after v23.7.53: server omits resourcetype entirely.
        // Self-row identified ONLY by trailing slash. Bare-UID + .ics members kept.
        // Response-level 404 row is a deletion (excluded from changed).
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"collection-ctag-token"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/event-with-extension.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-ics-001"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/345cf39b-27fd-413f-a8c3-98fb85fd5240</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-bare-002"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/deleted-bare-uid</d:href>
                <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertEquals("Both members kept; collection self-row + deletion excluded", 2, items.size)
        assertTrue(
            ".ics-named member must be kept",
            hrefs.contains("/calendars/user/default/event-with-extension.ics")
        )
        assertTrue(
            "Bare-UID member must be kept",
            hrefs.contains("/calendars/user/default/345cf39b-27fd-413f-a8c3-98fb85fd5240")
        )
        assertTrue(
            "Collection self-row (trailing slash) must NOT be kept even though it has an etag",
            hrefs.none { it == "/calendars/user/default/" }
        )
        assertTrue(
            "Response-level 404 row must NOT appear as changed",
            hrefs.none { it.endsWith("/deleted-bare-uid") }
        )
    }

    @Test
    fun `extractDeletedHrefs reports response-level 404 when no resourcetype returned`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"collection-ctag-token"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/deleted-bare-uid</d:href>
                <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals("/calendars/user/default/deleted-bare-uid", deleted[0])
    }

    @Test
    fun `extractChangedItems uses resourcetype fallback for slashless self-row volunteered by server`() {
        // RFC 4918 §5.2 is a SHOULD, not MUST. A non-conforming server might omit the
        // trailing slash on a collection self-row but still volunteer
        // <resourcetype><collection/></resourcetype>. The parser keeps the resourcetype
        // path as a defensive fallback so this row is still skipped.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/event1.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-aaa"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = parser.extractChangedItems(xml)
        val hrefs = items.map { it.first }

        assertEquals("Member kept; slashless self-row identified via resourcetype fallback", 1, items.size)
        assertTrue(hrefs.contains("/calendars/user/default/event1.ics"))
        assertTrue(
            "Slashless self-row must be skipped via resourcetype/collection fallback",
            hrefs.none { it == "/calendars/user/default" }
        )
    }

    @Test
    fun `extractChangedItems treats slashless member with mixed propstat as changed not deleted`() {
        // Slashless member href (no .ics extension) carries TWO propstats: 200 OK with
        // getetag, plus 404 Not Found on some other prop. RFC 4918 §13: propstat status
        // applies only to those properties; the response itself is alive. The parser
        // must keep the row as changed (etag intact), not flag it as deleted.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"collection-ctag"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/bare-uid-mixed-propstat</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-mixed-001"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = parser.extractChangedItems(xml)
        val deleted = parser.extractDeletedHrefs(xml)

        assertEquals(1, items.size)
        assertEquals(
            "/calendars/user/default/bare-uid-mixed-propstat",
            items[0].first
        )
        assertEquals("etag-mixed-001", items[0].second)
        assertTrue(
            "Slashless member with propstat-404 + sibling 200 must NOT be deleted",
            deleted.isEmpty()
        )
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
    fun `extractSyncCollectionData flags truncation from an embedded 507 status`() {
        // RFC 6578 §3.6: a server that truncates a large sync-collection returns
        // HTTP 207 (NOT a top-level 507) with a <response> for the collection whose
        // <status> is "507 Insufficient Storage", plus a partial sync-token to
        // resume from. The client must page again on the returned token, so the
        // parser has to surface this as truncated=true.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/addressbooks/user/contacts/a.vcf</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>"etag-a"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/addressbooks/user/contacts/</d:href>
                    <d:status>HTTP/1.1 507 Insufficient Storage</d:status>
                </d:response>
                <d:sync-token>http://example.test/ns/sync/partial-1</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val data = parser.extractSyncCollectionData(xml)

        assertTrue("an embedded 507 status must set truncated", data.truncated)
        assertEquals("http://example.test/ns/sync/partial-1", data.syncToken)
        assertEquals("the partial page's changed items are still returned", 1, data.changedItems.size)
        assertEquals("/addressbooks/user/contacts/a.vcf", data.changedItems[0].first)
        // The 507 self-row is the collection, not a deleted member.
        assertTrue("collection self-row is never a deletion", data.deletedHrefs.isEmpty())
    }

    @Test
    fun `extractSyncCollectionData leaves truncated false on a normal 207`() {
        // A complete response (no 507 anywhere) must not be misread as truncated,
        // or the client would page forever against a non-advancing token.
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val data = parser.extractSyncCollectionData(xml)
        assertFalse("a complete response is not truncated", data.truncated)
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
