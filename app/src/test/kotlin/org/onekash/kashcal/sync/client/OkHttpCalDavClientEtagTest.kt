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
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests for OkHttpCalDavClient ETag handling with PROPFIND fallback.
 *
 * These tests verify:
 * 1. ETag extraction from PUT response headers (primary path)
 * 2. PROPFIND fallback when ETag header is missing (Nextcloud behavior)
 * 3. Various ETag formats (quoted, weak, XML-encoded, etc.)
 * 4. Error handling when both header and PROPFIND fail
 *
 * Uses MockWebServer to simulate CalDAV server responses.
 */
class OkHttpCalDavClientEtagTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @Before
    fun setup() {
        // Mock Android Log methods
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

    // ========== PRIMARY PATH TESTS (ETag in response header) ==========

    @Test
    fun `createEvent returns ETag from response header when present`() = runTest {
        // Arrange: Server returns ETag in header (normal case)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"abc123\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("ETag should be extracted without quotes", "abc123", etag)
        assertEquals("Should only make 1 request (no PROPFIND)", 1, mockWebServer.requestCount)
    }

    @Test
    fun `updateEvent returns new ETag from response header when present`() = runTest {
        // Arrange: Server returns new ETag after update
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"newetag456\"")
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "oldetag123"
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val newEtag = result.getOrNull()
        assertEquals("New ETag should be extracted", "newetag456", newEtag)
        assertEquals("Should only make 1 request (no PROPFIND)", 1, mockWebServer.requestCount)
    }

    @Test
    fun `createEvent handles lowercase etag header (HTTP2)`() = runTest {
        // Arrange: HTTP/2 uses lowercase headers
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("etag", "\"http2etag\"")  // lowercase
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should handle lowercase etag header", "http2etag", etag)
    }

    // ========== WEAK ETAG TESTS (RFC 7232) ==========

    @Test
    fun `createEvent handles weak ETag in header (W prefix)`() = runTest {
        // Arrange: Some servers/proxies return weak ETags
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "W/\"weaketag123\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should strip W/ prefix and quotes", "weaketag123", etag)
    }

    @Test
    fun `updateEvent handles weak ETag in header (W prefix)`() = runTest {
        // Arrange: Weak ETag in response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "W/\"weakupdate456\"")
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "oldetag"
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        assertEquals("Should strip W/ prefix", "weakupdate456", result.getOrNull())
    }

    @Test
    fun `fetchEvent handles weak ETag in header`() = runTest {
        // Arrange: GET returns weak ETag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "W/\"weakfetch789\"")
                .setBody(createTestIcal("test-event"))
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.fetchEvent(eventUrl)

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        assertEquals("Should strip W/ prefix", "weakfetch789", result.getOrNull()?.etag)
    }

    @Test
    fun `PROPFIND parses weak ETag in XML response`() = runTest {
        // Arrange: PROPFIND returns weak ETag in XML
        mockWebServer.enqueue(MockResponse().setResponseCode(201)) // No ETag header
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:propstat>
                                <d:prop>
                                    <d:getetag>W/"weakXmlEtag"</d:getetag>
                                </d:prop>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should strip W/ prefix from XML", "weakXmlEtag", etag)
    }

    @Test
    fun `createEvent handles ETag without quotes`() = runTest {
        // Arrange: Some servers return unquoted ETags
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "unquotedvalue")
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should handle unquoted ETag", "unquotedvalue", etag)
    }

    // ========== PROPFIND FALLBACK TESTS ==========

    @Test
    fun `createEvent uses PROPFIND fallback when header missing`() = runTest {
        // Arrange: Server doesn't return ETag header (Nextcloud behavior)
        // First response: PUT 201 without ETag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        // Second response: PROPFIND returns ETag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createPropfindResponse("fallbacketag123"))
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should get ETag from PROPFIND fallback", "fallbacketag123", etag)
        assertEquals("Should make 2 requests (PUT + PROPFIND)", 2, mockWebServer.requestCount)

        // Verify PROPFIND request was made
        mockWebServer.takeRequest() // Skip PUT request
        val propfindRequest = mockWebServer.takeRequest()
        assertEquals("PROPFIND", propfindRequest.method)
        assertEquals("0", propfindRequest.getHeader("Depth"))
    }

    @Test
    fun `updateEvent uses PROPFIND fallback when header missing`() = runTest {
        // Arrange: Server doesn't return ETag header
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                // No ETag header
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createPropfindResponse("updatedEtag789"))
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "oldetag"
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val newEtag = result.getOrNull()
        assertEquals("Should get ETag from PROPFIND fallback", "updatedEtag789", newEtag)
        assertEquals("Should make 2 requests (PUT + PROPFIND)", 2, mockWebServer.requestCount)
    }

    @Test
    fun `createEvent returns empty ETag when PROPFIND and multiget both fail`() = runTest {
        // Arrange: No ETag header, PROPFIND fails, multiget also fails
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)  // PROPFIND fails
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // Multiget fallback also fails
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should still be success (create worked)", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should return empty ETag when all fallbacks fail", "", etag)
        assertEquals("Should make 3 requests (PUT + PROPFIND + multiget)", 3, mockWebServer.requestCount)
    }

    @Test
    fun `updateEvent returns old ETag when PROPFIND and multiget both fail`() = runTest {
        // Arrange: No ETag header, PROPFIND fails, multiget also fails
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                // No ETag header
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)  // PROPFIND fails
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // Multiget fallback also fails
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()
        val oldEtag = "oldetag123"

        // Act
        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = oldEtag
        )

        // Assert
        assertTrue("Result should still be success (update worked)", result.isSuccess())
        val newEtag = result.getOrNull()
        assertEquals("Should return old ETag when all fallbacks fail", oldEtag, newEtag)
        assertEquals("Should make 3 requests (PUT + PROPFIND + multiget)", 3, mockWebServer.requestCount)
    }

    // ========== PROPFIND XML PARSING TESTS ==========

    @Test
    fun `PROPFIND parses ETag with XML-encoded quotes`() = runTest {
        // Arrange: Nextcloud returns XML-encoded quotes
        mockWebServer.enqueue(MockResponse().setResponseCode(201)) // No ETag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:propstat>
                                <d:prop>
                                    <d:getetag>&quot;xmlencodedEtag&quot;</d:getetag>
                                </d:prop>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should parse XML-encoded quotes", "xmlencodedEtag", etag)
    }

    @Test
    fun `PROPFIND parses ETag with different namespace prefixes`() = runTest {
        // Arrange: Different servers use different prefixes
        mockWebServer.enqueue(MockResponse().setResponseCode(201)) // No ETag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0"?>
                    <D:multistatus xmlns:D="DAV:">
                        <D:response>
                            <D:propstat>
                                <D:prop>
                                    <D:getetag>"differentPrefix"</D:getetag>
                                </D:prop>
                            </D:propstat>
                        </D:response>
                    </D:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should handle uppercase D: prefix", "differentPrefix", etag)
    }

    // ========== MULTIGET FALLBACK TESTS ==========

    @Test
    fun `fetchEtag uses multiget when PROPFIND fails`() = runTest {
        // Arrange: PROPFIND returns 501, multiget succeeds
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // PROPFIND fails (Zoho)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createMultigetResponse("multiget-etag-abc"))
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.fetchEtag(eventUrl)

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        assertEquals("multiget-etag-abc", result.getOrNull())
        assertEquals("Should make 2 requests (PROPFIND + multiget)", 2, mockWebServer.requestCount)

        // Verify request types
        val propfindRequest = mockWebServer.takeRequest()
        assertEquals("PROPFIND", propfindRequest.method)
        val multigetRequest = mockWebServer.takeRequest()
        assertEquals("REPORT", multigetRequest.method)
        assertTrue("Multiget body should contain calendar-multiget",
            multigetRequest.body.readUtf8().contains("calendar-multiget"))
    }

    @Test
    fun `fetchEtag does not try multiget on 404`() = runTest {
        // Arrange: PROPFIND returns 404 — event doesn't exist, multiget won't help
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
        )

        val eventUrl = mockWebServer.url("/calendars/test/nonexistent.ics").toString()

        // Act
        val result = client.fetchEtag(eventUrl)

        // Assert
        assertTrue("Should be not found error", result.isNotFound())
        assertEquals("Should only make 1 request (no multiget)", 1, mockWebServer.requestCount)
    }

    @Test
    fun `fetchEtag returns error when both PROPFIND and multiget fail`() = runTest {
        // Arrange: PROPFIND 501, multiget also 501
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // PROPFIND fails
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // Multiget also fails
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.fetchEtag(eventUrl)

        // Assert
        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be from PROPFIND", 501, error.code)
        assertEquals("Should make 2 requests (PROPFIND + multiget)", 2, mockWebServer.requestCount)
    }

    @Test
    fun `createEvent uses multiget fallback when PROPFIND fails`() = runTest {
        // Arrange: PUT 201 (no ETag), PROPFIND 501, multiget 207 with etag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // PROPFIND fails (Zoho)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createMultigetResponse("multiget-etag-123"))
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should get ETag from multiget fallback", "multiget-etag-123", etag)
        assertEquals("Should make 3 requests (PUT + PROPFIND + multiget)", 3, mockWebServer.requestCount)
    }

    @Test
    fun `updateEvent uses multiget fallback when PROPFIND fails`() = runTest {
        // Arrange: PUT 201 (no ETag), PROPFIND 501, multiget 207 with etag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(501)  // PROPFIND fails (Zoho)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createMultigetResponse("multiget-etag-456"))
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "oldetag"
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val newEtag = result.getOrNull()
        assertEquals("Should get ETag from multiget, not old etag", "multiget-etag-456", newEtag)
        assertEquals("Should make 3 requests (PUT + PROPFIND + multiget)", 3, mockWebServer.requestCount)
    }

    @Test
    fun `PROPFIND success short-circuits multiget fallback`() = runTest {
        // Arrange: PUT 201 (no ETag), PROPFIND 207 succeeds — multiget not needed
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createPropfindResponse("propfind-etag"))
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertEquals("Should get ETag from PROPFIND", "propfind-etag", etag)
        assertEquals("Should make only 2 requests (no multiget)", 2, mockWebServer.requestCount)
    }

    // ========== fetchEtag() DIRECT TESTS ==========

    @Test
    fun `fetchEtag returns ETag from successful PROPFIND`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createPropfindResponse("directFetchEtag"))
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.fetchEtag(eventUrl)

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        assertEquals("directFetchEtag", result.getOrNull())
    }

    @Test
    fun `fetchEtag returns null for 404`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val eventUrl = mockWebServer.url("/calendars/test/nonexistent.ics").toString()

        // Act
        val result = client.fetchEtag(eventUrl)

        // Assert
        assertTrue("Should be not found error", result.isNotFound())
    }

    @Test
    fun `fetchEtag returns error for auth failure`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        // Act
        val result = client.fetchEtag(eventUrl)

        // Assert
        assertTrue("Should be auth error", result.isAuthError())
    }

    // ========== 413 Too Large Tests (RFC 4791) ==========

    @Test
    fun `createEvent returns clear error for 413 too large`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(413))

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "large-event",
            icalData = createTestIcal("large-event")
        )

        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(413, error.code)
        assertTrue("Should mention too large", error.message.contains("too large"))
    }

    @Test
    fun `updateEvent returns clear error for 413 too large`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(413))

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("large-event"),
            etag = "oldetag"
        )

        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(413, error.code)
        assertTrue("Should mention too large", error.message.contains("too large"))
    }

    // ========== UID Conflict Tests (RFC 4791) ==========

    @Test
    fun `createEvent returns UID conflict error with Location header`() = runTest {
        // RFC 4791: 403 with Location header indicates UID already exists
        val existingEventUrl = mockWebServer.url("/calendars/test/existing-event.ics").toString()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Location", existingEventUrl)
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "duplicate-uid",
            icalData = createTestIcal("duplicate-uid")
        )

        // Assert
        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(403, error.code)
        assertTrue("Error should mention UID conflict", error.message.contains("UID conflict"))
        assertTrue("Error should include existing URL", error.message.contains(existingEventUrl))
    }

    @Test
    fun `createEvent returns permission denied for 403 without Location header`() = runTest {
        // 403 without Location is just permission denied
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.createEvent(
            calendarUrl = calendarUrl,
            uid = "test-event",
            icalData = createTestIcal("test-event")
        )

        // Assert
        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(403, error.code)
        assertEquals("Permission denied", error.message)
    }

    // ========== HTTP 201 Acceptance Tests (Zoho compatibility) ==========

    @Test
    fun `updateEvent accepts HTTP 201 response`() = runTest {
        // Zoho CalDAV returns 201 for updates instead of standard 204
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"updated-etag-201\"")
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "old-etag"
        )

        assertTrue("Result should be success for 201", result.isSuccess())
        assertEquals("updated-etag-201", result.getOrNull())
    }

    @Test
    fun `updateEvent accepts HTTP 200 response`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"updated-etag-200\"")
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "old-etag"
        )

        assertTrue("Result should be success for 200", result.isSuccess())
        assertEquals("updated-etag-200", result.getOrNull())
    }

    @Test
    fun `updateEvent with 201 and no ETag header falls back to PROPFIND`() = runTest {
        // 201 with no ETag header → PROPFIND fallback
        mockWebServer.enqueue(
            MockResponse().setResponseCode(201)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(createPropfindResponse("propfind-etag-201"))
        )

        val eventUrl = mockWebServer.url("/calendars/test/event.ics").toString()

        val result = client.updateEvent(
            eventUrl = eventUrl,
            icalData = createTestIcal("test-event"),
            etag = "old-etag"
        )

        assertTrue("Result should be success", result.isSuccess())
        assertEquals("propfind-etag-201", result.getOrNull())
        assertEquals("Should make 2 requests (PUT + PROPFIND)", 2, mockWebServer.requestCount)
    }

    // ========== Helper Functions ==========

    private fun createTestIcal(uid: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//KashCal//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260127T000000Z
        DTSTART:20260201T100000Z
        DTEND:20260201T110000Z
        SUMMARY:Test Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun createPropfindResponse(etag: String): String = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/calendars/test/event.ics</d:href>
                <d:propstat>
                    <d:prop>
                        <d:getetag>"$etag"</d:getetag>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun createMultigetResponse(etag: String): String = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:response>
                <d:href>/calendars/test/test-event.ics</d:href>
                <d:propstat>
                    <d:prop>
                        <d:getetag>"$etag"</d:getetag>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()
}
