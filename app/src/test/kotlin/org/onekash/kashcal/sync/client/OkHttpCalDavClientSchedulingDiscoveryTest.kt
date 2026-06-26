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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests for the RFC 6638 scheduling-discovery operations on
 * [OkHttpCalDavClient]:
 *  - `discoverScheduleOutboxUrl` (§2.1.1 PROPFIND for schedule-outbox-URL)
 *  - `supportsAutoSchedule` (§2 OPTIONS DAV-header calendar-auto-schedule)
 *
 * Verifies outgoing request shape (method, headers) and response handling
 * (href extraction, DAV-header token detection, error paths).
 */
class OkHttpCalDavClientSchedulingDiscoveryTest {

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

    // ========== discoverScheduleOutboxUrl ==========

    @Test
    fun `discoverScheduleOutboxUrl sends PROPFIND Depth 0 and returns the href`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                      <d:response>
                        <d:href>/principals/users/admin/</d:href>
                        <d:propstat>
                          <d:prop>
                            <c:schedule-outbox-URL>
                              <d:href>/calendars/admin/outbox/</d:href>
                            </c:schedule-outbox-URL>
                          </d:prop>
                          <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                      </d:response>
                    </d:multistatus>
                    """.trimIndent()
                )
        )

        val principalUrl = mockWebServer.url("/principals/users/admin/").toString()
        val result = client.discoverScheduleOutboxUrl(principalUrl)

        assertTrue(result.isSuccess())
        assertEquals("/calendars/admin/outbox/", (result as CalDavResult.Success).data)

        val request = mockWebServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertTrue(request.body.readUtf8().contains("schedule-outbox-URL"))
    }

    @Test
    fun `discoverScheduleOutboxUrl returns null when property empty`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                      <d:response>
                        <d:href>/SOGo/dav/testuser1/</d:href>
                        <d:propstat>
                          <d:prop><c:schedule-outbox-URL/></d:prop>
                          <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                      </d:response>
                    </d:multistatus>
                    """.trimIndent()
                )
        )

        val result = client.discoverScheduleOutboxUrl(mockWebServer.url("/SOGo/dav/testuser1/").toString())

        assertTrue(result.isSuccess())
        assertNull((result as CalDavResult.Success).data)
    }

    @Test
    fun `discoverScheduleOutboxUrl surfaces HTTP error as CalDavResult Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val result = client.discoverScheduleOutboxUrl(mockWebServer.url("/principals/x/").toString())

        assertTrue(result.isError())
    }

    // ========== supportsAutoSchedule ==========

    @Test
    fun `supportsAutoSchedule sends OPTIONS and returns true when header advertises token`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, 3, calendar-access, calendar-auto-schedule")
        )

        val result = client.supportsAutoSchedule(mockWebServer.url("/calendars/admin/personal/").toString())

        assertTrue(result.isSuccess())
        assertTrue((result as CalDavResult.Success).data)

        val request = mockWebServer.takeRequest()
        assertEquals("OPTIONS", request.method)
    }

    @Test
    fun `supportsAutoSchedule returns false when header omits the token`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, 3, calendar-access")
        )

        val result = client.supportsAutoSchedule(mockWebServer.url("/calendars/admin/personal/").toString())

        assertTrue(result.isSuccess())
        assertFalse((result as CalDavResult.Success).data)
    }

    @Test
    fun `supportsAutoSchedule matches token case-insensitively`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access, Calendar-Auto-Schedule")
        )

        val result = client.supportsAutoSchedule(mockWebServer.url("/c/").toString())

        assertTrue(result.isSuccess())
        assertTrue((result as CalDavResult.Success).data)
    }

    @Test
    fun `supportsAutoSchedule surfaces HTTP error as CalDavResult Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = client.supportsAutoSchedule(mockWebServer.url("/c/").toString())

        assertTrue(result.isError())
    }

    @Test
    fun `supportsAutoSchedule finds the token when DAV is split across multiple header lines`() = runTest {
        // Some servers (e.g. Cyrus-based hosts) emit several DAV: response
        // header lines and put calendar-auto-schedule on a line OTHER than the
        // first. response.header("DAV") returns only one of them, so the token
        // must be matched across ALL DAV lines (response.headers("DAV")).
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("DAV", "1, 2, 3, access-control, extended-mkcol")
                .addHeader("DAV", "calendar-access, calendar-auto-schedule")
                .addHeader("DAV", "calendar-query-extended, calendar-availability")
        )

        val result = client.supportsAutoSchedule(mockWebServer.url("/c/").toString())

        assertTrue(result.isSuccess())
        assertTrue(
            "calendar-auto-schedule on a non-first DAV line must still be detected",
            (result as CalDavResult.Success).data
        )
    }
}
