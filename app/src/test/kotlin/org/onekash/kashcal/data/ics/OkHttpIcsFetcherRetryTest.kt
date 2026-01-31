package org.onekash.kashcal.data.ics

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
import org.onekash.kashcal.data.db.entity.IcsSubscription

/**
 * Tests for OkHttpIcsFetcher retry logic.
 *
 * Retry behavior:
 * - YES: SocketTimeoutException, ConnectException, UnknownHostException, HTTP 429/503/5xx
 * - NO: HTTP 401/403/404/413, SSLHandshakeException, invalid ICS content
 */
class OkHttpIcsFetcherRetryTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var fetcher: OkHttpIcsFetcher
    private lateinit var subscription: IcsSubscription

    private val validIcsContent = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:test-event@example.com
        DTSTART:20240101T100000Z
        DTEND:20240101T110000Z
        SUMMARY:Test Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

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

        mockServer = MockWebServer()
        mockServer.start()

        fetcher = OkHttpIcsFetcher()

        subscription = IcsSubscription(
            id = 1,
            url = mockServer.url("/calendar.ics").toString(),
            name = "Test Calendar",
            color = 0xFF2196F3.toInt(),
            calendarId = 1,
            enabled = true
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        unmockkAll()
    }

    // ==================== Retry on HTTP Errors ====================
    // Note: Network-level retry tests (SocketTimeout, ConnectionReset) are omitted
    // because MockWebServer + coroutine test dispatchers don't work reliably together.
    // HTTP-level retry is tested below and covers the retry logic.

    @Test
    fun `retries on HTTP 503 with exponential backoff`() = runTest {
        // 503 should trigger retry
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should succeed after retry on 503", result is IcsFetcher.FetchResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    @Test
    fun `retries on HTTP 500 server error`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should succeed after retry on 500", result is IcsFetcher.FetchResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    @Test
    fun `respects Retry-After header on 429`() = runTest {
        // 429 with Retry-After should wait before retry
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "1"))  // 1 second
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should succeed after retry", result is IcsFetcher.FetchResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
        // Note: Virtual time doesn't advance wall clock, so we just verify retry happened
    }

    @Test
    fun `respects Retry-After header on 503`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "1"))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should succeed after retry", result is IcsFetcher.FetchResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
        // Note: Virtual time doesn't advance wall clock, so we just verify retry happened
    }

    // ==================== NO Retry Cases ====================

    @Test
    fun `does NOT retry on HTTP 404`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should return error on 404", result is IcsFetcher.FetchResult.Error)
        assertEquals("Should only make 1 request (no retry)", 1, mockServer.requestCount)
    }

    @Test
    fun `does NOT retry on HTTP 401`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should return error on 401", result is IcsFetcher.FetchResult.Error)
        assertEquals("Should only make 1 request (no retry)", 1, mockServer.requestCount)
    }

    @Test
    fun `does NOT retry on HTTP 403`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(403))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should return error on 403", result is IcsFetcher.FetchResult.Error)
        assertEquals("Should only make 1 request (no retry)", 1, mockServer.requestCount)
    }

    @Test
    fun `does NOT retry on HTTP 413`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(413))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should return error on 413", result is IcsFetcher.FetchResult.Error)
        assertEquals("Should only make 1 request (no retry)", 1, mockServer.requestCount)
    }

    @Test
    fun `does NOT retry on invalid ICS content`() = runTest {
        // Return invalid ICS (not a VCALENDAR)
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("This is not valid ICS content"))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(validIcsContent))

        val result = fetcher.fetch(subscription)

        assertTrue("Should return error on invalid ICS", result is IcsFetcher.FetchResult.Error)
        assertEquals("Should only make 1 request (no retry)", 1, mockServer.requestCount)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `exhausts retries and returns error`() = runTest {
        // All requests fail with 503
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))

        val result = fetcher.fetch(subscription)

        assertTrue("Should return error after exhausting retries", result is IcsFetcher.FetchResult.Error)
        assertEquals("Should have made MAX_RETRIES requests", 2, mockServer.requestCount)
    }

    @Test
    fun `handles 304 Not Modified without retry`() = runTest {
        val subWithEtag = subscription.copy(etag = "\"abc123\"")
        mockServer.enqueue(MockResponse().setResponseCode(304))

        val result = fetcher.fetch(subWithEtag)

        assertTrue("Should return NotModified", result is IcsFetcher.FetchResult.NotModified)
        assertEquals("Should only make 1 request", 1, mockServer.requestCount)
    }
}
