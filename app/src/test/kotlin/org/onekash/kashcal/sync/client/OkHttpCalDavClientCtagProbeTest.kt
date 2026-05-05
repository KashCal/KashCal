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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests the widened getCtag PROPFIND: body includes displayname, calendar-color,
 * current-user-privilege-set; response parses into [CalendarMetadataProbe].
 *
 * The four probe fields power the calendar-metadata refresh at
 * [PullStrategy.maybeRefreshMetadata]. This test isolates the client+parser
 * seam.
 */
class OkHttpCalDavClientCtagProbeTest {

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
    fun `PROPFIND body requests displayname`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(fullProbeResponse()))
        client.getCtag(mockWebServer.url("/cal/").toString())

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue("Body must request displayname", body.contains("displayname"))
    }

    @Test
    fun `PROPFIND body requests calendar-color in apple ns`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(fullProbeResponse()))
        client.getCtag(mockWebServer.url("/cal/").toString())

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue("Body must request calendar-color", body.contains("calendar-color"))
        assertTrue(
            "Must include Apple iCal namespace for calendar-color",
            body.contains("http://apple.com/ns/ical/")
        )
    }

    @Test
    fun `PROPFIND body requests current-user-privilege-set`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(fullProbeResponse()))
        client.getCtag(mockWebServer.url("/cal/").toString())

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(
            "Body must request current-user-privilege-set for isReadOnly refresh",
            body.contains("current-user-privilege-set")
        )
    }

    @Test
    fun `PROPFIND body still requests getctag`() = runTest {
        // Widening must not drop existing fields.
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(fullProbeResponse()))
        client.getCtag(mockWebServer.url("/cal/").toString())

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("getctag"))
        assertTrue(body.contains("http://calendarserver.org/ns/"))
    }

    @Test
    fun `response with all four fields parses into probe`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(fullProbeResponse()))
        val result = client.getCtag(mockWebServer.url("/cal/").toString())

        assertTrue("Probe result should succeed", result.isSuccess())
        val probe = result.getOrNull()!!
        assertEquals("my-ctag", probe.ctag)
        assertEquals("Work", probe.displayName)
        assertEquals("#FF5733FF", probe.color)
        assertEquals(false, probe.isReadOnly)
    }

    @Test
    fun `response without privilege-set yields null isReadOnly`() = runTest {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:href>/cal/</d:href>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>abc</cs:getctag>
                            <d:displayname>Work</d:displayname>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(body))

        val probe = client.getCtag(mockWebServer.url("/cal/").toString()).getOrNull()!!
        assertEquals("abc", probe.ctag)
        assertEquals("Work", probe.displayName)
        assertNull("Server omitted privilege-set → null preserves local", probe.isReadOnly)
    }

    @Test
    fun `response without ctag still returns error`() = runTest {
        // Widening preserves existing contract: no ctag → error (Zoho fallback).
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/cal/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Work</d:displayname>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(body))

        val result = client.getCtag(mockWebServer.url("/cal/").toString())
        assertTrue(
            "No ctag → error (Zoho/no-ctag servers don't get metadata refresh via this path)",
            result.isError()
        )
    }

    private fun fullProbeResponse(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"
                       xmlns:ic="http://apple.com/ns/ical/">
            <d:response>
                <d:href>/cal/</d:href>
                <d:propstat>
                    <d:prop>
                        <cs:getctag>my-ctag</cs:getctag>
                        <d:displayname>Work</d:displayname>
                        <ic:calendar-color>#FF5733FF</ic:calendar-color>
                        <d:current-user-privilege-set>
                            <d:privilege><d:read/></d:privilege>
                            <d:privilege><d:write/></d:privilege>
                        </d:current-user-privilege-set>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()
}
