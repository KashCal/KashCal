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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests for [OkHttpCalDavClient.postToOutbox] — the client-side scheduling
 * Outbox POST (RFC 6638 §6). Verifies the request shape (POST, text/calendar,
 * Originator + one Recipient header per recipient, body == the iTIP bytes) and
 * the parsing of the schedule-response into a per-recipient request-status.
 */
class OkHttpCalDavClientOutboxPostTest {

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
        val credentials = Credentials(username = "testuser", password = "testpass", serverUrl = serverUrl)
        client = OkHttpCalDavClientFactory().createClient(credentials, DefaultQuirks(serverUrl)) as OkHttpCalDavClient
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    private val sampleIcs = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n"

    @Test
    fun `postToOutbox sends POST with text-calendar, Originator and Recipient headers and the iTIP body`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:response>
                    <C:recipient><D:href>mailto:guest@example.test</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status>
                  </C:response>
                </C:schedule-response>
                """.trimIndent()
            )
        )
        val outboxUrl = mockWebServer.url("/caldav/me/outbox/").toString()

        val result = client.postToOutbox(
            outboxUrl = outboxUrl,
            originator = "me@example.test",
            recipients = listOf("guest@example.test"),
            icalData = sampleIcs
        )

        assertTrue("Result should be success", result.isSuccess())
        val response = result.getOrNull()!!
        assertEquals(1, response.recipients.size)
        assertEquals("mailto:guest@example.test", response.recipients[0].recipient)
        assertEquals("2.0;Success", response.recipients[0].requestStatus)

        val recorded = mockWebServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            "Content-Type must be text/calendar",
            recorded.getHeader("Content-Type")?.startsWith("text/calendar") == true
        )
        assertEquals("mailto:me@example.test", recorded.getHeader("Originator"))
        assertEquals("mailto:guest@example.test", recorded.getHeader("Recipient"))
        assertEquals(sampleIcs, recorded.body.readUtf8())
    }

    @Test
    fun `postToOutbox emits one Recipient header per recipient`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:response><C:recipient><D:href>mailto:a@example.test</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status></C:response>
                  <C:response><C:recipient><D:href>mailto:b@example.test</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status></C:response>
                </C:schedule-response>
                """.trimIndent()
            )
        )
        val outboxUrl = mockWebServer.url("/caldav/me/outbox/").toString()

        val result = client.postToOutbox(
            outboxUrl = outboxUrl,
            originator = "me@example.test",
            recipients = listOf("a@example.test", "b@example.test"),
            icalData = sampleIcs
        )

        assertTrue(result.isSuccess())
        assertEquals(2, result.getOrNull()!!.recipients.size)

        val recorded = mockWebServer.takeRequest()
        val recipientHeaders = recorded.headers.values("Recipient")
        assertEquals(listOf("mailto:a@example.test", "mailto:b@example.test"), recipientHeaders)
    }

    @Test
    fun `postToOutbox accepts 207 multistatus as success`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:response><C:recipient><D:href>mailto:a@example.test</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status></C:response>
                </C:schedule-response>
                """.trimIndent()
            )
        )
        val outboxUrl = mockWebServer.url("/caldav/me/outbox/").toString()

        val result = client.postToOutbox(outboxUrl, "me@example.test", listOf("a@example.test"), sampleIcs)

        assertTrue(result.isSuccess())
    }

    @Test
    fun `postToOutbox surfaces a 501 NotImplemented (Sabre free-busy-only) as an error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(501).setBody("We only support VFREEBUSY"))
        val outboxUrl = mockWebServer.url("/remote.php/dav/calendars/me/outbox/").toString()

        val result = client.postToOutbox(outboxUrl, "me@example.test", listOf("a@example.test"), sampleIcs)

        assertTrue("501 must be an error", result.isError())
        assertEquals(501, (result as CalDavResult.Error).code)
    }

    @Test
    fun `postToOutbox surfaces a 400 invalid-scheduling-message (Stalwart) as an error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("<A:valid-scheduling-message/>"))
        val outboxUrl = mockWebServer.url("/dav/itip/me/outbox/").toString()

        val result = client.postToOutbox(outboxUrl, "me@example.test", listOf("a@example.test"), sampleIcs)

        assertTrue(result.isError())
    }

    @Test
    fun `postToOutbox resolves a server-relative outbox href against the base host`() = runTest {
        // Zoho discovery returns a relative href like "/caldav/<id>/outbox/";
        // OkHttp needs an absolute URL, so postToOutbox must resolve it.
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:response><C:recipient><D:href>mailto:a@example.test</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status></C:response>
                </C:schedule-response>
                """.trimIndent()
            )
        )

        val result = client.postToOutbox(
            outboxUrl = "/caldav/me/outbox/",  // relative, no scheme/host
            originator = "me@example.test",
            recipients = listOf("a@example.test"),
            icalData = sampleIcs
        )

        assertTrue("relative href must resolve and succeed", result.isSuccess())
        val recorded = mockWebServer.takeRequest()
        assertEquals("/caldav/me/outbox/", recorded.path)
    }

    @Test
    fun `postToOutbox maps 401 to an auth error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val outboxUrl = mockWebServer.url("/caldav/me/outbox/").toString()

        val result = client.postToOutbox(outboxUrl, "me@example.test", listOf("a@example.test"), sampleIcs)

        assertTrue(result.isAuthError())
    }
}
