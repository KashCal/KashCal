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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests for OkHttpCalDavClient.fetchAllEtags() — PROPFIND Depth:1 etag listing.
 *
 * This method is used by PullStrategy.pullFull() on servers without sync-token
 * (e.g., Purelymail) where calendar-query REPORT has a stale index.
 * PROPFIND Depth:1 reads the filesystem directly — always accurate.
 *
 * Uses MockWebServer to simulate CalDAV server responses.
 */
class OkHttpCalDavClientPropfindEtagTest {

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

    @Test
    fun `fetchAllEtags returns href-etag pairs from PROPFIND Depth 1 response`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/</d:href>
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
              <d:response>
                <d:href>/calendars/user/default/event2.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-bbb"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue("Result should be success", result.isSuccess())
        val pairs = result.getOrNull()!!
        assertEquals("Should return 2 events (collection URL filtered out)", 2, pairs.size)
        assertEquals("/calendars/user/default/event1.ics", pairs[0].first)
        assertEquals("etag-aaa", pairs[0].second)
        assertEquals("/calendars/user/default/event2.ics", pairs[1].first)
        assertEquals("etag-bbb", pairs[1].second)
    }

    @Test
    fun `fetchAllEtags skips collection self-row identified by trailing slash`() = runTest {
        // The collection self-row is identified by href.endsWith("/") (RFC 4918 §5.2 SHOULD).
        // Wire body requests only <d:getetag/>, so server may not include resourcetype at
        // all — this fixture mirrors that wire reality. Member rows are identified by the
        // presence of an etag and a slashless href. Filename-based filtering would miss
        // servers that store events at extensionless UID hrefs.
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
                <d:href>/calendars/user/default/event1.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-aaa"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/bare-uid-no-extension</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-bbb"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue(result.isSuccess())
        val pairs = result.getOrNull()!!
        assertEquals("Should return both members; collection self-row skipped", 2, pairs.size)
        val hrefs = pairs.map { it.first }
        assertTrue(hrefs.contains("/calendars/user/default/event1.ics"))
        assertTrue(
            "Bare-UID member must be kept",
            hrefs.contains("/calendars/user/default/bare-uid-no-extension")
        )
    }

    @Test
    fun `fetchAllEtags sends correct PROPFIND request with Depth 1`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        client.fetchAllEtags(calendarUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        val body = request.body.readUtf8()
        assertTrue("Body should request getetag", body.contains("getetag"))
        assertTrue("Body should be a propfind request", body.contains("propfind"))
        assertFalse(
            "Body must NOT request resourcetype — iCloud emits per-member propstat-404 " +
                "for an empty resourcetype query and the response bloats well past the " +
                "read timeout. Collection self-row is discriminated by trailing slash on " +
                "href (RFC 4918 §5.2) instead.",
            Regex("""<[a-zA-Z]+:resourcetype\b""").containsMatchIn(body)
        )
    }

    @Test
    fun `fetchAllEtags handles empty calendar`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue(result.isSuccess())
        val pairs = result.getOrNull()!!
        assertTrue("Empty calendar should return empty list", pairs.isEmpty())
    }

    @Test
    fun `fetchAllEtags returns error on 404`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue("Should be error", result.isError())
    }

    @Test
    fun `fetchAllEtags returns error on 403`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue("Should be error", result.isError())
    }

    @Test
    fun `fetchAllEtags skips slashless member rows with no etag`() = runTest {
        // A slashless response with no etag can't be proven to be a member resource so
        // it's skipped (diagnostic). Without this rule, we'd emit Pair(href, null) and
        // downstream pulls would force-fetch every such response and waste bandwidth.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/event1.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-aaa"</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/calendars/user/default/event2.ics</d:href>
                <d:propstat>
                  <d:prop/>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val calendarUrl = mockWebServer.url("/calendars/user/default/").toString()
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue(result.isSuccess())
        val pairs = result.getOrNull()!!
        assertEquals(1, pairs.size)
        assertEquals("/calendars/user/default/event1.ics", pairs[0].first)
        assertEquals("etag-aaa", pairs[0].second)
    }

    @Test
    fun `fetchAllEtags returns error on network failure`() = runTest {
        // Shut down server to simulate network failure
        val port = mockWebServer.port
        mockWebServer.shutdown()

        val calendarUrl = "http://localhost:$port/calendars/user/default/"
        val result = client.fetchAllEtags(calendarUrl)

        assertTrue("Should be error on network failure", result.isError())
    }
}
