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

/**
 * Tests [OkHttpCalDavClient.checkConnection]: it sends OPTIONS, requires `calendar-access` in the
 * DAV header (RFC 4791), names CalDAV in the error for a server without it, fails on auth and
 * server errors, and closes every response, retried ones included.
 */
class OkHttpCalDavClientConnectionTest {

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

    // ========== DAV HEADER VALIDATION TESTS (RFC 4791) ==========

    @Test
    fun `checkConnection succeeds for CalDAV server with calendar-access`() = runTest {
        // Arrange: Server advertises CalDAV support
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access, addressbook")
        )

        val serverUrl = mockWebServer.url("/").toString()

        // Act
        val result = client.checkConnection(serverUrl)

        // Assert
        assertTrue("Should succeed for CalDAV server", result.isSuccess())
    }

    @Test
    fun `checkConnection fails for server without calendar-access`() = runTest {
        // Arrange: Server is WebDAV but not CalDAV
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, addressbook")  // No calendar-access
        )

        val serverUrl = mockWebServer.url("/").toString()

        // Act
        val result = client.checkConnection(serverUrl)

        // Assert
        assertFalse("Should fail for non-CalDAV server", result.isSuccess())
        val error = result as? org.onekash.kashcal.sync.client.model.CalDavResult.Error
        assertNotNull("Should have error details", error)
        assertTrue("Error should mention CalDAV",
            error?.message?.contains("CalDAV", ignoreCase = true) == true)
    }

    @Test
    fun `checkConnection fails for server without DAV header`() = runTest {
        // Arrange: Regular HTTP server, no DAV support
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                // No DAV header at all
        )

        val serverUrl = mockWebServer.url("/").toString()

        // Act
        val result = client.checkConnection(serverUrl)

        // Assert
        assertFalse("Should fail for non-DAV server", result.isSuccess())
    }

    @Test
    fun `checkConnection handles auth failure`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val serverUrl = mockWebServer.url("/").toString()

        // Act
        val result = client.checkConnection(serverUrl)

        // Assert
        assertTrue("Should be auth error", result.isAuthError())
    }

    @Test
    fun `checkConnection handles server error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val serverUrl = mockWebServer.url("/").toString()

        // Act
        val result = client.checkConnection(serverUrl)

        // Assert
        assertFalse("Should fail on server error", result.isSuccess())
    }

    @Test
    fun `checkConnection sends OPTIONS request`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
        )

        val serverUrl = mockWebServer.url("/").toString()

        // Act
        client.checkConnection(serverUrl)

        // Assert
        val request = mockWebServer.takeRequest()
        assertEquals("Should use OPTIONS method", "OPTIONS", request.method)
    }

    // ========== RESPONSES ARE CLOSED ==========

    /** Counts calls whose response was closed: OkHttp fires callEnd only then. */
    private class ClosedCalls : okhttp3.EventListener() {
        val started = java.util.concurrent.atomic.AtomicInteger()
        val ended = java.util.concurrent.atomic.AtomicInteger()
        override fun callStart(call: okhttp3.Call) { started.incrementAndGet() }
        override fun callEnd(call: okhttp3.Call) { ended.incrementAndGet() }
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { ended.incrementAndGet() }
    }

    @Test
    fun `checkConnection closes every response, including the retried 429 and 5xx`() = runTest {
        for (first in listOf(429, 503, 500)) {
            val closed = ClosedCalls()
            val counted = OkHttpCalDavClient(
                DefaultQuirks(mockWebServer.url("/").toString()),
                okhttp3.OkHttpClient.Builder().eventListener(closed).build()
            )
            // Retry-After only on the 429: on a 503 with "Retry-After: 0" OkHttp retries
            // inside the same call, and the client's own retry is what is under test.
            val busy = MockResponse().setResponseCode(first).setBody("busy")
            mockWebServer.enqueue(if (first == 429) busy.setHeader("Retry-After", "0") else busy)
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("DAV", "1, calendar-access").setBody("ok"))

            val result = counted.checkConnection(mockWebServer.url("/").toString())

            assertTrue("$first then 200: $result", result.isSuccess())
            assertEquals("$first: two calls made", 2, closed.started.get())
            assertEquals("$first: both responses closed", 2, closed.ended.get())
        }
    }

    @Test
    fun `checkConnection closes the response it returns an error for`() = runTest {
        val closed = ClosedCalls()
        val counted = OkHttpCalDavClient(
            DefaultQuirks(mockWebServer.url("/").toString()),
            okhttp3.OkHttpClient.Builder().eventListener(closed).build()
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("DAV", "1, 2").setBody("<html/>"))

        counted.checkConnection(mockWebServer.url("/").toString())

        assertEquals(1, closed.ended.get())
    }
}
