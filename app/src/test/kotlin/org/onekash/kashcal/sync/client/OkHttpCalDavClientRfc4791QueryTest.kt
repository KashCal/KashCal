package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.time.Instant

/**
 * RFC 4791 compliance tests for CalDAV query operations.
 *
 * Tests REPORT-based queries against RFC 4791 requirements:
 * - Section 7.8: calendar-query REPORT (time-range filtering)
 * - Section 7.9: calendar-multiget REPORT (batch event retrieval)
 * - Section 9.9: time-range element format
 *
 * Each test verifies BOTH outgoing request compliance (method, headers, XML body)
 * and response handling compliance (parsing multistatus responses).
 */
class OkHttpCalDavClientRfc4791QueryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockWebServer = MockWebServer()
        mockWebServer.start()

        val serverUrl = mockWebServer.url("/").toString()
        val credentials = Credentials(
            username = "testuser",
            password = "testpass",
            serverUrl = serverUrl
        )
        val factory = OkHttpCalDavClientFactory()
        client = factory.createClient(credentials, DefaultQuirks(serverUrl)) as OkHttpCalDavClient
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    // ========== RFC 4791 Section 7.8: calendar-query REPORT ==========

    @Test
    fun `fetchEventsInRange sends REPORT method`() = runTest {
        // RFC 4791 Section 7.8: calendar-query uses REPORT method
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 4791 requires REPORT method for calendar-query", "REPORT", request.method)
    }

    @Test
    fun `fetchEventsInRange sends Depth 1 header`() = runTest {
        // RFC 4791 Section 7.8: Depth:1 for calendar-query on a collection
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 4791 requires Depth: 1 for calendar-query", "1", request.getHeader("Depth"))
    }

    @Test
    fun `fetchEventsInRange uses calendar-query element in caldav namespace`() = runTest {
        // RFC 4791 Section 7.8: Root element must be CALDAV:calendar-query
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Request must use calendar-query root element",
            body.contains("calendar-query")
        )
        assertTrue(
            "Request must include CalDAV namespace",
            body.contains("urn:ietf:params:xml:ns:caldav")
        )
    }

    @Test
    fun `fetchEventsInRange requests getetag and calendar-data properties`() = runTest {
        // RFC 4791 Section 7.8: DAV:prop must include getetag and calendar-data
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request getetag property", body.contains("getetag"))
        assertTrue("Must request calendar-data property", body.contains("calendar-data"))
    }

    @Test
    fun `fetchEventsInRange applies comp-filter for VCALENDAR and VEVENT`() = runTest {
        // RFC 4791 Section 7.8.1: Filter must have nested comp-filter for VCALENDAR > VEVENT
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Must have comp-filter for VCALENDAR",
            body.contains("comp-filter") && body.contains("VCALENDAR")
        )
        assertTrue(
            "Must have comp-filter for VEVENT",
            body.contains("VEVENT")
        )
    }

    @Test
    fun `fetchEventsInRange formats time-range start and end in UTC`() = runTest {
        // RFC 4791 Section 9.9: time-range values MUST be in UTC (ending with Z)
        // Format: yyyyMMdd'T'HHmmss'Z'
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        // Jan 15, 2026 00:00 UTC to Feb 15, 2026 00:00 UTC
        val start = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-02-15T00:00:00Z").toEpochMilli()
        client.fetchEventsInRange(calendarUrl, start, end)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "time-range start must be in UTC format (ending with Z)",
            body.contains("start=\"20260115T000000Z\"")
        )
        assertTrue(
            "time-range end must be in UTC format (ending with Z)",
            body.contains("end=\"20260215T000000Z\"")
        )
    }

    @Test
    fun `fetchEventsInRange parses multistatus response with events`() = runTest {
        // RFC 4791 Section 7.8: Response is a DAV:multistatus with calendar-data
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(eventReportResponse())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        assertTrue("Result should be success", result.isSuccess())
        val events = result.getOrNull()!!
        assertTrue("Should parse at least one event from response", events.isNotEmpty())

        val event = events[0]
        assertNotNull("Event must have href", event.href)
        assertNotNull("Event must have etag", event.etag)
        assertTrue(
            "Event must have iCal data starting with BEGIN:VCALENDAR",
            event.icalData.contains("BEGIN:VCALENDAR")
        )
    }

    @Test
    fun `fetchEventsInRange handles empty calendar`() = runTest {
        // RFC 4791: Empty multistatus response for calendars with no events in range
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.fetchEventsInRange(calendarUrl, startMillis(), endMillis())

        assertTrue("Result should be success", result.isSuccess())
        val events = result.getOrNull()!!
        assertTrue("Empty calendar should return empty list", events.isEmpty())
    }

    // ========== RFC 4791 Section 7.8: calendar-query (etags-only variant) ==========

    @Test
    fun `fetchEtagsInRange omits calendar-data from request`() = runTest {
        // Bandwidth optimization: Same calendar-query but without calendar-data property
        // Saves ~96% bandwidth (33KB vs 834KB for 231 events)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(etagOnlyResponse())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEtagsInRange(calendarUrl, startMillis(), endMillis())

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request getetag", body.contains("getetag"))
        assertFalse(
            "Must NOT request calendar-data (bandwidth optimization)",
            body.contains("calendar-data")
        )
    }

    @Test
    fun `fetchEtagsInRange returns href and etag pairs`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(etagOnlyResponse())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.fetchEtagsInRange(calendarUrl, startMillis(), endMillis())

        assertTrue("Result should be success", result.isSuccess())
        val pairs = result.getOrNull()!!
        assertTrue("Should return href+etag pairs", pairs.isNotEmpty())
        // Each pair has (href, etag?)
        assertNotNull("Pair should have href", pairs[0].first)
    }

    // ========== RFC 4791 Section 7.9: calendar-multiget REPORT ==========

    @Test
    fun `fetchEventsByHref sends calendar-multiget REPORT`() = runTest {
        // RFC 4791 Section 7.9: calendar-multiget is a REPORT with specific hrefs
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(multigetResponse())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsByHref(calendarUrl, listOf("/calendars/testuser/personal/event1.ics"))

        val request = mockWebServer.takeRequest()
        assertEquals("Must use REPORT method", "REPORT", request.method)
        val body = request.body.readUtf8()
        assertTrue(
            "Root element must be calendar-multiget",
            body.contains("calendar-multiget")
        )
    }

    @Test
    fun `fetchEventsByHref includes all hrefs in request body`() = runTest {
        // RFC 4791 Section 7.9: Each requested href must be in the REPORT body
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(multigetResponse())
        )

        val hrefs = listOf(
            "/calendars/testuser/personal/event1.ics",
            "/calendars/testuser/personal/event2.ics",
            "/calendars/testuser/personal/event3.ics"
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsByHref(calendarUrl, hrefs)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        for (href in hrefs) {
            assertTrue(
                "Request body must include href: $href",
                body.contains(href)
            )
        }
    }

    @Test
    fun `fetchEventsByHref requests getetag and calendar-data`() = runTest {
        // RFC 4791 Section 7.9: Must request both etag and calendar data
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(multigetResponse())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.fetchEventsByHref(calendarUrl, listOf("/calendars/testuser/personal/event1.ics"))

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request getetag", body.contains("getetag"))
        assertTrue("Must request calendar-data", body.contains("calendar-data"))
    }

    @Test
    fun `fetchEventsByHref parses multiple events from response`() = runTest {
        // RFC 4791 Section 7.9: Response contains one DAV:response per requested href
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(multigetResponseTwoEvents())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val hrefs = listOf(
            "/calendars/testuser/personal/event1.ics",
            "/calendars/testuser/personal/event2.ics"
        )
        val result = client.fetchEventsByHref(calendarUrl, hrefs)

        assertTrue("Result should be success", result.isSuccess())
        val events = result.getOrNull()!!
        assertEquals("Should return 2 events for 2 hrefs", 2, events.size)
    }

    @Test
    fun `fetchEventsByHref returns empty list for empty hrefs input`() = runTest {
        // Edge case: No hrefs to fetch should short-circuit without HTTP request
        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.fetchEventsByHref(calendarUrl, emptyList())

        assertTrue("Result should be success", result.isSuccess())
        val events = result.getOrNull()!!
        assertTrue("Empty hrefs should return empty list", events.isEmpty())
        assertEquals("No HTTP request should be made", 0, mockWebServer.requestCount)
    }

    @Test
    fun `fetchEventsByHref handles partial failure in multiget response`() = runTest {
        // RFC 4791 Section 7.9: Server may return 404 for individual hrefs
        // within the same multistatus response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(partialFailureResponse())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val hrefs = listOf(
            "/calendars/testuser/personal/event1.ics",
            "/calendars/testuser/personal/event2.ics"
        )
        val result = client.fetchEventsByHref(calendarUrl, hrefs)

        assertTrue("Result should be success overall", result.isSuccess())
        val events = result.getOrNull()!!
        // Only events with actual calendar-data should be returned
        assertEquals(
            "Only events with 200 status and calendar-data should be returned",
            1,
            events.size
        )
        assertTrue(
            "Returned event should be the one that exists",
            events[0].icalData.contains("event1@test")
        )
    }

    // ========== fetchEvent (GET single event) ==========

    @Test
    fun `fetchEvent sends GET request`() = runTest {
        // RFC 4791: Individual event retrieval uses plain HTTP GET
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag123\"")
                .setBody(singleEventIcal())
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event1.ics").toString()
        client.fetchEvent(eventUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("Individual event retrieval uses GET", "GET", request.method)
    }

    @Test
    fun `fetchEvent extracts etag from response header`() = runTest {
        // RFC 4791 Section 5.3.4: Server SHOULD return ETag header
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-from-header\"")
                .setBody(singleEventIcal())
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event1.ics").toString()
        val result = client.fetchEvent(eventUrl)

        assertTrue("Result should be success", result.isSuccess())
        val event = result.getOrNull()!!
        assertEquals(
            "ETag should be extracted and normalized from response header",
            "etag-from-header",
            event.etag
        )
    }

    @Test
    fun `fetchEvent returns 404 as not found error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/gone.ics").toString()
        val result = client.fetchEvent(eventUrl)

        assertTrue("404 should be returned as not found error", result.isNotFound())
    }

    // ========== Helper Methods ==========

    private fun startMillis(): Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private fun endMillis(): Long = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()

    private fun emptyMultistatus(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        </d:multistatus>
    """.trimIndent()

    private fun eventReportResponse(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"report-etag-1"</d:getetag>
                <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:event1@test.local
DTSTAMP:20260115T120000Z
DTSTART:20260120T090000Z
DTEND:20260120T100000Z
SUMMARY:Team Meeting
END:VEVENT
END:VCALENDAR</c:calendar-data>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun etagOnlyResponse(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:response>
                <d:href>/calendars/testuser/personal/event1.ics</d:href>
                <d:propstat>
                    <d:prop>
                        <d:getetag>"etag-only-1"</d:getetag>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun multigetResponse(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"multiget-etag-1"</d:getetag>
                <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1@test.local
DTSTAMP:20260115T120000Z
DTSTART:20260120T090000Z
DTEND:20260120T100000Z
SUMMARY:Event One
END:VEVENT
END:VCALENDAR</c:calendar-data>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun multigetResponseTwoEvents(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag1"</d:getetag>
                <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1@test.local
DTSTAMP:20260115T120000Z
DTSTART:20260120T090000Z
DTEND:20260120T100000Z
SUMMARY:Event One
END:VEVENT
END:VCALENDAR</c:calendar-data>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/event2.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag2"</d:getetag>
                <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event2@test.local
DTSTAMP:20260115T120000Z
DTSTART:20260121T140000Z
DTEND:20260121T150000Z
SUMMARY:Event Two
END:VEVENT
END:VCALENDAR</c:calendar-data>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun partialFailureResponse(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag1"</d:getetag>
                <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1@test
DTSTART:20260115T100000Z
DTEND:20260115T110000Z
SUMMARY:Exists
END:VEVENT
END:VCALENDAR</c:calendar-data>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/event2.ics</d:href>
        <d:propstat>
            <d:prop/>
            <d:status>HTTP/1.1 404 Not Found</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun singleEventIcal(): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//EN
        BEGIN:VEVENT
        UID:event1@test.local
        DTSTAMP:20260115T120000Z
        DTSTART:20260120T090000Z
        DTEND:20260120T100000Z
        SUMMARY:Single Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()
}
