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
 * Tests for Retry-After header parsing per RFC 7231.
 *
 * RFC 7231 Section 7.1.3 defines two formats:
 * - delay-seconds: "120" (seconds to wait)
 * - HTTP-date: "Sun, 06 Nov 1994 08:49:37 GMT"
 *
 * The server MAY send Retry-After with:
 * - 429 Too Many Requests (rate limiting)
 * - 503 Service Unavailable (temporary overload)
 */
class OkHttpCalDavClientRetryAfterTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient
    private lateinit var serverUrl: String

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

        serverUrl = mockServer.url("/").toString()
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
        mockServer.shutdown()
        unmockkAll()
    }

    // ==================== Seconds Format Tests ====================

    @Test
    fun `parseRetryAfter handles seconds format`() = runTest {
        // Arrange - 429 with Retry-After in seconds
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "1"))  // 1 second
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        // Act
        val result = client.checkConnection(serverUrl)

        // Assert - should have retried and succeeded
        assertTrue("Should succeed after retry", result is CalDavResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    @Test
    fun `parseRetryAfter handles zero seconds`() = runTest {
        // Zero means retry immediately
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "0"))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after immediate retry", result is CalDavResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    // ==================== HTTP-Date Format Tests ====================

    @Test
    fun `parseRetryAfter handles HTTP-date format with past date`() = runTest {
        // HTTP-date: "Sun, 06 Nov 1994 08:49:37 GMT"
        // Use a date in the past so retry happens immediately
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "Sun, 06 Nov 1994 08:49:37 GMT"))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        val result = client.checkConnection(serverUrl)

        // Past date should result in immediate retry (0 delay)
        assertTrue("Should succeed after retry with past HTTP-date", result is CalDavResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    @Test
    fun `parseRetryAfter handles HTTP-date with future time`() = runTest {
        // Future date - should calculate delay
        // Use a date 2 seconds in the future
        val futureDate = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(System.currentTimeMillis() + 2000))

        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", futureDate))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        val startTime = System.currentTimeMillis()
        val result = client.checkConnection(serverUrl)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Should succeed after waiting", result is CalDavResult.Success)
        assertTrue("Should have waited at least 1 second", elapsed >= 1000)
    }

    // ==================== 503 Service Unavailable Tests ====================

    @Test
    fun `503 response respects Retry-After header`() = runTest {
        // 503 with Retry-After should wait before retry
        mockServer.enqueue(MockResponse()
            .setResponseCode(503)
            .setHeader("Retry-After", "1"))  // 1 second
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        val startTime = System.currentTimeMillis()
        val result = client.checkConnection(serverUrl)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Should succeed after retry", result is CalDavResult.Success)
        assertTrue("Should have waited ~1 second", elapsed >= 900)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    @Test
    fun `503 without Retry-After uses exponential backoff`() = runTest {
        // 503 without Retry-After should use default backoff
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after retry", result is CalDavResult.Success)
        assertEquals("Should have made 2 requests", 2, mockServer.requestCount)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `parseRetryAfter handles invalid value gracefully`() = runTest {
        // Invalid value should fall back to default
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "not-a-number-or-date"))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        // Should not crash, should use fallback delay
        val result = client.checkConnection(serverUrl)

        assertTrue("Should handle invalid Retry-After gracefully", result is CalDavResult.Success)
    }

    @Test
    fun `parseRetryAfter handles missing header on 429`() = runTest {
        // 429 without Retry-After should use default backoff
        mockServer.enqueue(MockResponse().setResponseCode(429))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("DAV", "1, calendar-access"))

        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed without Retry-After header", result is CalDavResult.Success)
    }
}
