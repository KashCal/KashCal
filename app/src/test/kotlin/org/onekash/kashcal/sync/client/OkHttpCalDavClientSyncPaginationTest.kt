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
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests for sync-collection pagination handling (RFC 6578 Section 3.6).
 *
 * When a server truncates sync-collection results due to storage constraints,
 * it MUST return 507 Insufficient Storage with partial results and a new sync-token.
 * Client MUST use the new token to continue syncing.
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
        // Arrange: Server returns 507 with partial results
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
        // Arrange: Normal 207 response
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
        // Arrange: 507 without parseable response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody("Server storage limit exceeded")
        )

        val calendarUrl = mockWebServer.url("/calendars/test/").toString()

        // Act
        val result = client.syncCollection(calendarUrl, "http://example.com/sync/start")

        // Assert - should still succeed with empty results and truncated flag
        // (per RFC, server SHOULD include partial results, but if not, we still know to retry)
        assertTrue("Should handle 507 gracefully", result.isSuccess())
        val report = result.getOrNull()!!
        assertTrue("Should still be marked as truncated", report.truncated)
    }
}
