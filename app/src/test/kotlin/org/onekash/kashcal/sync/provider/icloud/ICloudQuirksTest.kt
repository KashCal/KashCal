package org.onekash.kashcal.sync.provider.icloud

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Tests for ICloudQuirks - iCloud-specific CalDAV parsing.
 */
class ICloudQuirksTest {

    private lateinit var quirks: ICloudQuirks

    @Before
    fun setup() {
        quirks = ICloudQuirks()
    }

    // Provider info tests

    @Test
    fun `provider id is icloud`() {
        assertEquals("icloud", quirks.providerId)
    }

    @Test
    fun `base url is caldav icloud com`() {
        assertEquals("https://caldav.icloud.com", quirks.baseUrl)
    }

    @Test
    fun `requires app specific password`() {
        assertTrue(quirks.requiresAppSpecificPassword)
    }

    // Principal URL extraction tests

    @Test
    fun `extract principal url from iCloud response with xmlns`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/</href>
                    <propstat>
                        <prop>
                            <current-user-principal xmlns="DAV:">
                                <href xmlns="DAV:">/123456789/principal/</href>
                            </current-user-principal>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/123456789/principal/", result)
    }

    @Test
    fun `extract principal url from prefixed response`() {
        val response = """
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:current-user-principal>
                                <d:href>/987654321/principal/</d:href>
                            </d:current-user-principal>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/987654321/principal/", result)
    }

    @Test
    fun `extract principal url returns null for invalid response`() {
        val response = "<html><body>Error</body></html>"
        assertNull(quirks.extractPrincipalUrl(response))
    }

    // Calendar home URL extraction tests

    @Test
    fun `extract calendar home url from iCloud response`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123456789/principal/</href>
                    <propstat>
                        <prop>
                            <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <href xmlns="DAV:">https://p180-caldav.icloud.com:443/123456789/calendars/</href>
                            </calendar-home-set>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val result = quirks.extractCalendarHomeUrl(response)
        assertEquals("https://p180-caldav.icloud.com:443/123456789/calendars/", result)
    }

    @Test
    fun `extract calendar home url with prefixed namespaces`() {
        val response = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <c:calendar-home-set>
                                <d:href>/user/calendars/</d:href>
                            </c:calendar-home-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractCalendarHomeUrl(response)
        assertEquals("/user/calendars/", result)
    }

    // Calendar list extraction tests

    @Test
    fun `extract calendars from iCloud response`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123456789/calendars/ABC123/</href>
                    <propstat>
                        <prop>
                            <displayname xmlns="DAV:">Work Calendar</displayname>
                            <resourcetype xmlns="DAV:">
                                <collection/>
                                <calendar xmlns="urn:ietf:params:xml:ns:caldav"/>
                            </resourcetype>
                            <calendar-color xmlns="http://apple.com/ns/ical/">#FF5733FF</calendar-color>
                            <getctag xmlns="http://calendarserver.org/ns/">abc123ctag</getctag>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
                <response xmlns="DAV:">
                    <href>/123456789/calendars/DEF456/</href>
                    <propstat>
                        <prop>
                            <displayname xmlns="DAV:">Personal</displayname>
                            <resourcetype xmlns="DAV:">
                                <collection/>
                                <calendar xmlns="urn:ietf:params:xml:ns:caldav"/>
                            </resourcetype>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://p180-caldav.icloud.com:443")

        assertEquals(2, calendars.size)

        assertEquals("/123456789/calendars/ABC123/", calendars[0].href)
        assertEquals("Work Calendar", calendars[0].displayName)
        assertEquals("#FF5733FF", calendars[0].color)
        assertEquals("abc123ctag", calendars[0].ctag)

        assertEquals("/123456789/calendars/DEF456/", calendars[1].href)
        assertEquals("Personal", calendars[1].displayName)
    }

    @Test
    fun `extract calendars skips inbox and outbox`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123/calendars/inbox/</href>
                    <propstat>
                        <prop>
                            <displayname>Inbox</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                    </propstat>
                </response>
                <response xmlns="DAV:">
                    <href>/123/calendars/outbox/</href>
                    <propstat>
                        <prop>
                            <displayname>Outbox</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                    </propstat>
                </response>
                <response xmlns="DAV:">
                    <href>/123/calendars/real/</href>
                    <propstat>
                        <prop>
                            <displayname>Real Calendar</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("Real Calendar", calendars[0].displayName)
    }

    @Test
    fun `extract calendars skips non-calendar collections`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123/calendars/</href>
                    <propstat>
                        <prop>
                            <displayname>Home</displayname>
                            <resourcetype><collection/></resourcetype>
                        </prop>
                    </propstat>
                </response>
                <response xmlns="DAV:">
                    <href>/123/calendars/work/</href>
                    <propstat>
                        <prop>
                            <displayname>Work</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("Work", calendars[0].displayName)
    }

    // ========== Component-based filtering tests ==========

    @Test
    fun `extractCalendars skips VTODO-only calendar by component type`() {
        val xml = """
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123/calendars/personal/</href>
                    <propstat>
                        <prop>
                            <displayname>Personal</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                            <supported-calendar-component-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <comp name='VEVENT' xmlns='urn:ietf:params:xml:ns:caldav'/>
                            </supported-calendar-component-set>
                        </prop>
                    </propstat>
                </response>
                <response xmlns="DAV:">
                    <href>/123/calendars/tasks/</href>
                    <propstat>
                        <prop>
                            <displayname>Tasks</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                            <supported-calendar-component-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <comp name='VTODO' xmlns='urn:ietf:params:xml:ns:caldav'/>
                            </supported-calendar-component-set>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(xml, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("Personal", calendars[0].displayName)
    }

    @Test
    fun `extractCalendars keeps calendar with no component set`() {
        val xml = """
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123/calendars/personal/</href>
                    <propstat>
                        <prop>
                            <displayname>Personal</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(xml, "https://caldav.icloud.com")

        // No supported-calendar-component-set → permissive fallback, keep the calendar
        assertEquals(1, calendars.size)
        assertEquals("Personal", calendars[0].displayName)
    }

    @Test
    fun `extractCalendars keeps calendar with VEVENT and VTODO`() {
        val xml = """
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123/calendars/personal/</href>
                    <propstat>
                        <prop>
                            <displayname>Personal</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                            <supported-calendar-component-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <comp name='VEVENT' xmlns='urn:ietf:params:xml:ns:caldav'/>
                                <comp name='VTODO' xmlns='urn:ietf:params:xml:ns:caldav'/>
                            </supported-calendar-component-set>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(xml, "https://caldav.icloud.com")

        // Calendar supports both VEVENT and VTODO → keep (has events)
        assertEquals(1, calendars.size)
        assertEquals("Personal", calendars[0].displayName)
    }

    // iCal data extraction tests

    @Test
    fun `extract ical data from CDATA response`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/123/calendars/work/event1.ics</href>
                    <propstat>
                        <prop>
                            <getetag xmlns="DAV:">"abc123"</getetag>
                            <calendar-data xmlns="urn:ietf:params:xml:ns:caldav"><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event-1
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR]]></calendar-data>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertEquals("/123/calendars/work/event1.ics", events[0].href)
        assertEquals("abc123", events[0].etag)
        assertTrue(events[0].icalData.contains("BEGIN:VCALENDAR"))
        assertTrue(events[0].icalData.contains("UID:event-1"))
    }

    @Test
    fun `extract multiple ical events from response`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event1.ics</href>
                    <propstat>
                        <prop>
                            <getetag>"etag1"</getetag>
                            <calendar-data xmlns="urn:ietf:params:xml:ns:caldav"><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:uid-1
SUMMARY:Event 1
END:VEVENT
END:VCALENDAR]]></calendar-data>
                        </prop>
                    </propstat>
                </response>
                <response>
                    <href>/event2.ics</href>
                    <propstat>
                        <prop>
                            <getetag>"etag2"</getetag>
                            <calendar-data xmlns="urn:ietf:params:xml:ns:caldav"><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:uid-2
SUMMARY:Event 2
END:VEVENT
END:VCALENDAR]]></calendar-data>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(2, events.size)
        assertEquals("etag1", events[0].etag)
        assertEquals("etag2", events[1].etag)
        assertTrue(events[0].icalData.contains("UID:uid-1"))
        assertTrue(events[1].icalData.contains("UID:uid-2"))
    }

    @Test
    fun `extract ical data handles XML entities`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event.ics</href>
                    <propstat>
                        <prop>
                            <calendar-data xmlns="urn:ietf:params:xml:ns:caldav">BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test
SUMMARY:Meeting &amp; Review
END:VEVENT
END:VCALENDAR</calendar-data>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertTrue(events[0].icalData.contains("Meeting & Review"))
    }

    // Sync token and ctag tests

    @Test
    fun `extract sync token from response`() {
        val response = """
            <multistatus xmlns="DAV:">
                <sync-token>https://caldav.icloud.com/sync/token123</sync-token>
            </multistatus>
        """.trimIndent()

        val token = quirks.extractSyncToken(response)
        assertEquals("https://caldav.icloud.com/sync/token123", token)
    }

    @Test
    fun `extract ctag from response`() {
        val response = """
            <propstat>
                <prop>
                    <getctag xmlns="http://calendarserver.org/ns/">HwoQEgwABC123</getctag>
                </prop>
            </propstat>
        """.trimIndent()

        val ctag = quirks.extractCtag(response)
        assertEquals("HwoQEgwABC123", ctag)
    }

    // URL building tests

    @Test
    fun `build calendar url from relative href`() {
        // Regional URLs are normalized to canonical form
        val url = quirks.buildCalendarUrl("/123/calendars/work/", "https://p180-caldav.icloud.com:443")
        assertEquals("https://caldav.icloud.com/123/calendars/work/", url)
    }

    @Test
    fun `build calendar url from absolute href`() {
        val url = quirks.buildCalendarUrl("https://other.icloud.com/123/calendars/work/", "https://caldav.icloud.com")
        assertEquals("https://other.icloud.com/123/calendars/work/", url)
    }

    @Test
    fun `build event url from relative href`() {
        // Regional URLs are normalized to canonical form
        val url = quirks.buildEventUrl("/123/calendars/work/event.ics", "https://p180-caldav.icloud.com:443/123/calendars/work/")
        assertEquals("https://caldav.icloud.com/123/calendars/work/event.ics", url)
    }

    // Date formatting tests

    @Test
    fun `format date for query returns UTC format`() {
        // Jan 1, 2024 00:00:00 UTC
        val millis = 1704067200000L
        val formatted = quirks.formatDateForQuery(millis)
        assertEquals("20240101T000000Z", formatted)
    }

    // Skip calendar tests

    @Test
    fun `should skip inbox calendar`() {
        assertTrue(quirks.shouldSkipCalendar("/user/calendars/inbox/", "Inbox"))
    }

    @Test
    fun `should skip outbox calendar`() {
        assertTrue(quirks.shouldSkipCalendar("/user/calendars/outbox/", "Outbox"))
    }

    @Test
    fun `should skip tasks calendar`() {
        assertTrue(quirks.shouldSkipCalendar("/user/calendars/tasks/", "Tasks"))
    }

    @Test
    fun `should skip reminders calendar`() {
        assertTrue(quirks.shouldSkipCalendar("/user/calendars/reminders/", "Reminders"))
    }

    @Test
    fun `should not skip regular calendar`() {
        assertFalse(quirks.shouldSkipCalendar("/user/calendars/work/", "Work Calendar"))
    }

    // Sync token invalid tests

    @Test
    fun `sync token is not invalid on bare 403 response`() {
        // Issue #51: bare 403 is "permission denied", not sync-token expiry
        assertFalse(quirks.isSyncTokenInvalid(403, ""))
    }

    @Test
    fun `sync token is invalid on 403 with valid-sync-token in body`() {
        assertTrue(quirks.isSyncTokenInvalid(403, "<error><valid-sync-token/></error>"))
    }

    @Test
    fun `sync token is invalid when response mentions valid-sync-token`() {
        val response = "<error><valid-sync-token/></error>"
        assertTrue(quirks.isSyncTokenInvalid(400, response))
    }

    @Test
    fun `sync token is valid on 207 response`() {
        assertFalse(quirks.isSyncTokenInvalid(207, "<multistatus/>"))
    }

    // Default sync range tests

    @Test
    fun `default sync range back is 1 year`() {
        val oneYearMs = 365L * 24 * 60 * 60 * 1000
        assertEquals(oneYearMs, quirks.getDefaultSyncRangeBack())
    }

    @Test
    fun `default sync range forward is far future`() {
        // Far-future date (Jan 1, 2100 UTC) - effectively unlimited
        val farFutureMs = 4102444800000L
        assertEquals(farFutureMs, quirks.getDefaultSyncRangeForward())
    }

    // Additional headers tests

    @Test
    fun `additional headers include user agent`() {
        val headers = quirks.getAdditionalHeaders()
        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers["User-Agent"]!!.contains("KashCal"))
    }

    // extractDeletedHrefs tests

    @Test
    fun `extractDeletedHrefs returns empty list for no deletions`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event1.ics</href>
                    <propstat>
                        <prop><getetag>"etag1"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `extractDeletedHrefs finds 404 status items`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event1.ics</href>
                    <status>HTTP/1.1 404 Not Found</status>
                </response>
                <response>
                    <href>/event2.ics</href>
                    <propstat>
                        <prop><getetag>"etag2"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)
        assertEquals(1, deleted.size)
        assertEquals("/event1.ics", deleted[0])
    }

    @Test
    fun `extractDeletedHrefs finds multiple deleted items`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/deleted1.ics</href>
                    <status>HTTP/1.1 404 Not Found</status>
                </response>
                <response>
                    <href>/deleted2.ics</href>
                    <status>HTTP/1.1 404</status>
                </response>
                <response>
                    <href>/exists.ics</href>
                    <propstat>
                        <prop><getetag>"etag"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)
        assertEquals(2, deleted.size)
        assertTrue(deleted.contains("/deleted1.ics"))
        assertTrue(deleted.contains("/deleted2.ics"))
    }

    @Test
    fun `extractDeletedHrefs handles prefixed namespaces`() {
        val response = """
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/deleted-event.ics</d:href>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)
        assertEquals(1, deleted.size)
        assertEquals("/deleted-event.ics", deleted[0])
    }

    @Test
    fun `extractDeletedHrefs returns empty list for empty response`() {
        val deleted = quirks.extractDeletedHrefs("")
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `extractDeletedHrefs ignores 404 in propstat when response is OK`() {
        // This tests that we only look at response-level status, not propstat status
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event.ics</href>
                    <propstat>
                        <prop><getetag>"etag"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)
        assertTrue(deleted.isEmpty())
    }

    // extractChangedItems tests

    @Test
    fun `extractChangedItems returns hrefs and etags from sync-collection`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/home/event1.ics</href>
                    <propstat>
                        <prop><getetag>"etag1"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/calendars/home/event1.ics", items[0].first)
        assertEquals("etag1", items[0].second)
    }

    @Test
    fun `extractChangedItems handles multiple items`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event1.ics</href>
                    <propstat>
                        <prop><getetag>"etag1"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
                <response>
                    <href>/event2.ics</href>
                    <propstat>
                        <prop><getetag>"etag2"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(2, items.size)
        assertEquals("/event1.ics", items[0].first)
        assertEquals("etag1", items[0].second)
        assertEquals("/event2.ics", items[1].first)
        assertEquals("etag2", items[1].second)
    }

    @Test
    fun `extractChangedItems handles prefixed namespaces`() {
        val response = """
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/work/meeting.ics</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>"abc123"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/calendars/work/meeting.ics", items[0].first)
        assertEquals("abc123", items[0].second)
    }

    @Test
    fun `extractChangedItems excludes deleted items`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event1.ics</href>
                    <propstat>
                        <prop><getetag>"etag1"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
                <response>
                    <href>/deleted-event.ics</href>
                    <status>HTTP/1.1 404 Not Found</status>
                </response>
            </multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/event1.ics", items[0].first)
        // deleted-event.ics should NOT be in the list
        assertFalse(items.any { it.first.contains("deleted") })
    }

    @Test
    fun `extractChangedItems skips non-ics files`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/home/</href>
                    <propstat>
                        <prop><getetag>"collection-etag"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
                <response>
                    <href>/calendars/home/event.ics</href>
                    <propstat>
                        <prop><getetag>"event-etag"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/calendars/home/event.ics", items[0].first)
    }

    @Test
    fun `extractChangedItems returns empty for empty response`() {
        val items = quirks.extractChangedItems("")
        assertTrue(items.isEmpty())
    }

    @Test
    fun `extractChangedItems handles XML entity encoded etags`() {
        // Some servers return &quot; instead of literal quotes in getetag
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/home/event.ics</href>
                    <propstat>
                        <prop><getetag>&quot;abc123def456&quot;</getetag></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        // Should decode &quot; and strip quotes
        assertEquals("abc123def456", items[0].second)
    }

    @Test
    fun `extractChangedItems handles missing etag`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/event-no-etag.ics</href>
                    <propstat>
                        <prop></prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/event-no-etag.ics", items[0].first)
        assertNull(items[0].second)
    }
}
