package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.time.Instant

/**
 * RFC 4791 compliance tests for protocol-level requirements.
 *
 * Tests cross-cutting protocol concerns:
 * - Section 3: CalDAV capability detection (OPTIONS + DAV header)
 * - Section 5.1: Content-Type requirements for PROPFIND/REPORT/PUT
 * - Section 5.3.4: ETag retrieval and normalization
 * - CalendarServer extension: ctag change detection
 * - RFC 6578: sync-token retrieval
 * - RFC 7231: Retry-After header handling
 * - RFC 7232: Weak ETag normalization
 */
class OkHttpCalDavClientRfc4791ProtocolTest {

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

    // ========== RFC 4791 Section 3: CalDAV Capability Detection ==========

    @Test
    fun `checkConnection sends OPTIONS method`() = runTest {
        // RFC 4791 Section 3: OPTIONS is used to check CalDAV compliance
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access")
        )

        val serverUrl = mockWebServer.url("/").toString()
        client.checkConnection(serverUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 4791 uses OPTIONS for capability check", "OPTIONS", request.method)
    }

    @Test
    fun `checkConnection validates calendar-access in DAV header`() = runTest {
        // RFC 4791 Section 3: CalDAV servers MUST include "calendar-access" in DAV header
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access, addressbook")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Server with calendar-access should pass", result.isSuccess())
    }

    @Test
    fun `checkConnection is case insensitive for calendar-access`() = runTest {
        // Robustness: Some servers may use different casing
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, Calendar-Access")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("calendar-access check should be case-insensitive", result.isSuccess())
    }

    @Test
    fun `checkConnection fails for addressbook-only server`() = runTest {
        // CardDAV server without CalDAV support
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, addressbook")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertFalse("Server without calendar-access should fail", result.isSuccess())
    }

    @Test
    fun `checkConnection fails for server without DAV header`() = runTest {
        // Plain HTTP server with no WebDAV support
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                // No DAV header
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertFalse("Server without DAV header should fail", result.isSuccess())
    }

    @Test
    fun `checkConnection returns auth error on 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("401 should be auth error", result.isAuthError())
    }

    @Test
    fun `checkConnection retries on 429 with Retry-After`() = runTest {
        // RFC 7231: 429 Too Many Requests with Retry-After header
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0") // Immediate retry for test speed
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after retry", result.isSuccess())
        assertEquals("Should make 2 requests (original + retry)", 2, mockWebServer.requestCount)
    }

    @Test
    fun `checkConnection retries on 503 with Retry-After`() = runTest {
        // RFC 7231: 503 Service Unavailable with Retry-After
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after 503 retry", result.isSuccess())
        assertEquals("Should make 2 requests", 2, mockWebServer.requestCount)
    }

    @Test
    fun `checkConnection retries on 5xx with backoff`() = runTest {
        // Server error with retry using exponential backoff
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after 500 retry", result.isSuccess())
    }

    // ========== CalendarServer Extension: ctag Change Detection ==========

    @Test
    fun `getCtag sends PROPFIND with Depth 0`() = runTest {
        // ctag (CalendarServer extension): Lightweight change detection on collection
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(ctagResponse("ctag-value-123"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.getCtag(calendarUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PROPFIND", "PROPFIND", request.method)
        assertEquals("Must use Depth: 0", "0", request.getHeader("Depth"))
    }

    @Test
    fun `getCtag requests getctag in calendarserver namespace`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(ctagResponse("ctag-value"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.getCtag(calendarUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Must request getctag property",
            body.contains("getctag")
        )
        assertTrue(
            "Must include CalendarServer namespace",
            body.contains("http://calendarserver.org/ns/")
        )
    }

    @Test
    fun `getCtag parses ctag from response`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(ctagResponse("my-ctag-abc"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.getCtag(calendarUrl)

        assertTrue("Result should be success", result.isSuccess())
        assertEquals("my-ctag-abc", result.getOrNull())
    }

    @Test
    fun `getCtag returns error when ctag not in response`() = runTest {
        // Server doesn't support ctag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/calendars/testuser/personal/</d:href>
                            <d:propstat>
                                <d:prop/>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.getCtag(calendarUrl)

        assertTrue("Missing ctag should be error", result.isError())
    }

    // ========== RFC 6578: Sync Token Retrieval ==========

    @Test
    fun `getSyncToken sends PROPFIND with Depth 0`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncTokenResponse("http://example.com/sync/token-1"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.getSyncToken(calendarUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PROPFIND", "PROPFIND", request.method)
        assertEquals("Must use Depth: 0", "0", request.getHeader("Depth"))
    }

    @Test
    fun `getSyncToken requests sync-token property`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncTokenResponse("http://example.com/sync/token-1"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.getSyncToken(calendarUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request sync-token property", body.contains("sync-token"))
    }

    @Test
    fun `getSyncToken returns null when server has no token`() = runTest {
        // RFC 6578: Server may not support sync-token
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/calendars/testuser/personal/</d:href>
                            <d:propstat>
                                <d:prop/>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.getSyncToken(calendarUrl)

        assertTrue("Result should be success", result.isSuccess())
        assertNull("Sync token should be null when not supported", result.getOrNull())
    }

    @Test
    fun `getSyncToken parses token from response`() = runTest {
        val token = "http://example.com/sync/token-12345"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncTokenResponse(token))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.getSyncToken(calendarUrl)

        assertTrue("Result should be success", result.isSuccess())
        assertEquals("Token should be parsed from response", token, result.getOrNull())
    }

    // ========== RFC 4791 Section 5.3.4: ETag Retrieval ==========

    @Test
    fun `fetchEtag sends PROPFIND with Depth 0`() = runTest {
        // RFC 4791 Section 5.3.4: Fetch ETag via PROPFIND when not in PUT response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("fetched-etag"))
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        client.fetchEtag(eventUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PROPFIND", "PROPFIND", request.method)
        assertEquals("Must use Depth: 0", "0", request.getHeader("Depth"))
    }

    @Test
    fun `fetchEtag requests getetag property`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("test-etag"))
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        client.fetchEtag(eventUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request getetag", body.contains("getetag"))
    }

    @Test
    fun `fetchEtag falls back to multiget when PROPFIND returns 501`() = runTest {
        // Real-world: Zoho returns 501 for PROPFIND on individual events
        mockWebServer.enqueue(MockResponse().setResponseCode(501))
        // Multiget fallback
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("multiget-fallback-etag"))
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.fetchEtag(eventUrl)

        assertTrue("Should succeed with multiget fallback", result.isSuccess())
        assertTrue(
            "Should make 2 requests: PROPFIND + multiget",
            mockWebServer.requestCount >= 2
        )
    }

    // ========== RFC 7232: ETag Normalization ==========

    @Test
    fun `fetchEtag normalizes quoted etag`() = runTest {
        // ETags from servers often include surrounding quotes
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("quoted-etag-value"))
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.fetchEtag(eventUrl)

        assertTrue(result.isSuccess())
        val etag = result.getOrNull()
        assertNotNull("ETag should not be null", etag)
        assertFalse(
            "ETag should not contain surrounding quotes",
            etag!!.startsWith("\"") || etag.endsWith("\"")
        )
    }

    @Test
    fun `fetchEtag normalizes weak etag`() = runTest {
        // RFC 7232: Weak ETags have W/ prefix
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/calendars/testuser/personal/event.ics</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:getetag>W/"weak-etag-123"</d:getetag>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.fetchEtag(eventUrl)

        assertTrue(result.isSuccess())
        val etag = result.getOrNull()
        assertNotNull("ETag should not be null", etag)
        assertFalse(
            "W/ prefix should be stripped per RFC 7232",
            etag!!.startsWith("W/")
        )
    }

    // ========== Content-Type Verification ==========

    @Test
    fun `all PROPFIND requests use application xml content type`() = runTest {
        // RFC 4791: PROPFIND bodies are XML
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(ctagResponse("ctag"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.getCtag(calendarUrl)

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("PROPFIND must have Content-Type", contentType)
        assertTrue(
            "PROPFIND Content-Type must be application/xml",
            contentType!!.contains("application/xml")
        )
    }

    @Test
    fun `all REPORT requests use application xml content type`() = runTest {
        // RFC 4791: REPORT bodies are XML
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        client.fetchEventsInRange(calendarUrl, start, end)

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("REPORT must have Content-Type", contentType)
        assertTrue(
            "REPORT Content-Type must be application/xml",
            contentType!!.contains("application/xml")
        )
    }

    @Test
    fun `all PUT requests use text calendar content type`() = runTest {
        // RFC 4791 Section 5.3.1: PUT body is iCalendar data
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"etag\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.createEvent(calendarUrl, "uid", testIcal("uid"))

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("PUT must have Content-Type", contentType)
        assertTrue(
            "PUT Content-Type must be text/calendar",
            contentType!!.contains("text/calendar")
        )
    }

    // ========== Helper Methods ==========

    private fun ctagResponse(ctag: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
            <d:response>
                <d:href>/calendars/testuser/personal/</d:href>
                <d:propstat>
                    <d:prop>
                        <cs:getctag>$ctag</cs:getctag>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun syncTokenResponse(token: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/calendars/testuser/personal/</d:href>
                <d:propstat>
                    <d:prop>
                        <d:sync-token>$token</d:sync-token>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun propfindEtagResponse(etag: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/calendars/testuser/personal/event.ics</d:href>
                <d:propstat>
                    <d:prop>
                        <d:getetag>"$etag"</d:getetag>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun testIcal(uid: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//KashCal//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260115T000000Z
        DTSTART:20260201T100000Z
        DTEND:20260201T110000Z
        SUMMARY:Test Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()
}
