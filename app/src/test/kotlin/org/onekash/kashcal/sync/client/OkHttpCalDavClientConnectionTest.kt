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
 * Tests for OkHttpCalDavClient connection and capability checking.
 *
 * These tests verify:
 * 1. OPTIONS request is sent correctly
 * 2. DAV header is parsed for CalDAV capabilities (RFC 4791)
 * 3. Proper error messages for non-CalDAV servers
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
}
