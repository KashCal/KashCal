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
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests sync-collection truncation handling (RFC 6578 §3.6).
 *
 * A server that truncates the results marks it with a 507 Insufficient Storage status for the
 * request-URI, inside a 207 with the partial results and a new sync-token; the client also
 * accepts a top-level HTTP 507, which these tests send. The client continues from the new token.
 */
class OkHttpCalDavClientSyncPaginationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

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
    fun `syncCollection handles 507 truncated response`() = runTest {
        // Arrange: the server returns 507 with partial results
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/calendars/test/event1.ics</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:getetag>"etag1"</d:getetag>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                        <d:sync-token>http://example.com/sync/page2</d:sync-token>
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.syncCollection(calendarUrl, "http://example.com/sync/page1")

        // Assert
        assertTrue("Result should be success with truncated flag", result.isSuccess())
        val report = result.getOrNull()!!
        assertTrue("Report should be marked as truncated", report.truncated)
        assertEquals("Should have partial changed items", 1, report.changed.size)
        assertEquals("Should have new sync token for continuation",
            "http://example.com/sync/page2", report.syncToken)
    }

    @Test
    fun `syncCollection normal response is not truncated`() = runTest {
        // Arrange: a 207 without a truncation marker
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/calendars/test/event1.ics</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:getetag>"etag1"</d:getetag>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                        <d:sync-token>http://example.com/sync/final</d:sync-token>
                    </d:multistatus>
                """.trimIndent())
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.syncCollection(calendarUrl, "http://example.com/sync/start")

        // Assert
        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertFalse("Normal response should not be truncated", report.truncated)
    }

    @Test
    fun `syncCollection 507 without valid response body returns error`() = runTest {
        // Arrange: a 507 whose body isn't a multistatus
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody("Server storage limit exceeded")
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.syncCollection(calendarUrl, "http://example.com/sync/start")

        // Assert: RFC 6578 §3.6 has a truncated report carry a multistatus with the new
        // sync-token. A body that isn't one says nothing about what changed and has no token
        // to continue from, so it is a retryable error, not an empty report.
        assertTrue("Should be an error", result is CalDavResult.Error)
        result as CalDavResult.Error
        assertEquals(CalDavResult.CODE_NOT_MULTISTATUS, result.code)
        assertTrue(result.isRetryable)
    }
}
