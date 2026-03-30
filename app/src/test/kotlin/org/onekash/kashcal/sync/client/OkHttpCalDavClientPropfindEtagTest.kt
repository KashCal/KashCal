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
    fun `fetchAllEtags filters out non-ics hrefs`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/calendars/user/default/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"col-etag"</d:getetag>
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
                <d:href>/calendars/user/default/readme.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"txt-etag"</d:getetag>
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
        assertEquals("Should return only .ics hrefs", 1, pairs.size)
        assertEquals("/calendars/user/default/event1.ics", pairs[0].first)
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
    fun `fetchAllEtags handles null etags`() = runTest {
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
        assertEquals(2, pairs.size)
        assertEquals("etag-aaa", pairs[0].second)
        assertNull("Event without getetag should have null etag", pairs[1].second)
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
