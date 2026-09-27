package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import org.onekash.kashcal.sync.discovery.isCalendarGone
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.util.concurrent.TimeUnit

/**
 * Tests the per-calendar probe that decides whether a calendar missing from a
 * home-set listing is gone. The rule under test: only a readable answer
 * about exactly this URL can say "not a calendar" or "gone"; anything the app
 * cannot read (a login page, a broken body, a reply about some other resource,
 * a redirect to another server) must come back as an error that keeps the
 * calendar.
 */
class OkHttpCalDavClientCalendarProbeTest {

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
        client = OkHttpCalDavClientFactory()
            .createClient(credentials, DefaultQuirks(serverUrl)) as OkHttpCalDavClient
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    private fun url(path: String = CAL_PATH) = mockWebServer.url(path).toString()

    private fun multistatus(vararg responses: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">\n" +
            responses.joinToString("\n") +
            "\n</d:multistatus>"

    private fun response(
        href: String = CAL_PATH,
        resourceType: String? = "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>",
        components: String = "",
        displayName: String = "Work",
        status: String = "HTTP/1.1 200 OK"
    ) = """
        <d:response>
          <d:href>$href</d:href>
          <d:propstat>
            <d:prop>
              <d:displayname>$displayName</d:displayname>
              ${resourceType ?: ""}
              $components
            </d:prop>
            <d:status>$status</d:status>
          </d:propstat>
        </d:response>
    """.trimIndent()

    private fun enqueue207(body: String) {
        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(body))
    }

    /**
     * A reply that isn't a WebDAV multistatus at all (a hotspot page, a cut-off body)
     * has its own code, so the refresh can stop probing: every other probe on the same
     * network would get the same page. The calendar is kept either way.
     */
    private fun assertNotMultistatus(result: CalDavResult<Boolean>) {
        assertTrue("Expected an error, got $result", result is CalDavResult.Error)
        assertEquals(CalDavResult.CODE_NOT_MULTISTATUS, (result as CalDavResult.Error).code)
        assertFalse("the calendar is kept", isCalendarGone(result))
    }

    private fun assertUnreadable(result: CalDavResult<Boolean>) {
        assertTrue("Expected an error, got $result", result is CalDavResult.Error)
        assertEquals(500, (result as CalDavResult.Error).code)
    }

    // ==================== Request shape ====================

    @Test
    fun `probe sends a Depth 0 PROPFIND for resourcetype, displayname and component set to the calendar URL`() = runTest {
        enqueue207(multistatus(response()))

        client.probeCalendarCollection(url())

        val request = mockWebServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals(CAL_PATH, request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("resourcetype"))
        assertTrue(body.contains("displayname"))
        assertTrue(body.contains("supported-calendar-component-set"))
    }

    // ==================== Readable answers ====================

    @Test
    fun `calendar collection answers true`() = runTest {
        enqueue207(multistatus(response()))

        val result = client.probeCalendarCollection(url())

        assertEquals(CalDavResult.Success(true), result)
    }

    @Test
    fun `calendar advertising VEVENT answers true`() = runTest {
        enqueue207(multistatus(response(components = COMPONENTS_VEVENT_VTODO)))

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    @Test
    fun `absolute href for the same path still matches`() = runTest {
        enqueue207(multistatus(response(href = url())))

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    @Test
    fun `href without the trailing slash still matches`() = runTest {
        enqueue207(multistatus(response(href = CAL_PATH.trimEnd('/'))))

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    @Test
    fun `percent-encoded href matches the decoded request path`() = runTest {
        enqueue207(multistatus(response(href = "/calendars/user/my%40cal/")))

        assertEquals(
            CalDavResult.Success(true),
            client.probeCalendarCollection(url("/calendars/user/my@cal/"))
        )
    }

    @Test
    fun `plain collection without calendar resourcetype answers false`() = runTest {
        enqueue207(multistatus(response(resourceType = "<d:resourcetype><d:collection/></d:resourcetype>")))

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `empty resourcetype answers false`() = runTest {
        enqueue207(multistatus(response(resourceType = "<d:resourcetype/>")))

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `tasks-only calendar answers false because the listing would not include it`() = runTest {
        enqueue207(multistatus(response(components = COMPONENTS_VTODO_ONLY)))

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `reserved inbox collection answers false because the listing would not include it`() = runTest {
        val inbox = "/calendars/user/inbox/"
        enqueue207(multistatus(response(href = inbox)))

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url(inbox)))
    }

    @Test
    fun `iCloud quirks also report a tasks-only list as false`() = runTest {
        val icloudClient = OkHttpCalDavClient(ICloudQuirks(), OkHttpClient())
        enqueue207(multistatus(response(components = COMPONENTS_VTODO_ONLY)))

        assertEquals(CalDavResult.Success(false), icloudClient.probeCalendarCollection(url()))
    }

    @Test
    fun `href spelled as a fully percent-encoded absolute URL still matches`() = runTest {
        val encodedAbsolute = url().replace(":", "%3A")
        enqueue207(multistatus(response(href = encodedAbsolute)))

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    // ==================== Server answers for a missing resource ====================

    @Test
    fun `response-level 404 status for the href answers false`() = runTest {
        // Shape a server uses for a missing resource: a response with a status and no propstat.
        enqueue207(
            multistatus(
                "<d:response><d:href>${url().replace(":", "%3A")}</d:href>" +
                    "<d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
            )
        )

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `response-level 410 status for the href answers false`() = runTest {
        enqueue207(
            multistatus("<d:response><d:href>$CAL_PATH</d:href><d:status>HTTP/1.1 410 Gone</d:status></d:response>")
        )

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `response-level 404 for a different href is unreadable`() = runTest {
        enqueue207(
            multistatus("<d:response><d:href>/elsewhere/</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>")
        )

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `response-level 403 status for the href answers false`() = runTest {
        // Mailbox (Open-Xchange) shape for a calendar that was deleted.
        enqueue207(
            multistatus("<d:response><d:href>$CAL_PATH</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>")
        )

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `response-level 403 for a different href is unreadable`() = runTest {
        enqueue207(
            multistatus("<d:response><d:href>/elsewhere/</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>")
        )

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `a 403 on the properties alone is unreadable, not gone`() = runTest {
        // A propstat-level 403 says those properties may not be read, not that the
        // calendar is gone.
        enqueue207(multistatus(response(status = "HTTP/1.1 403 Forbidden")))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `successful propstat with no properties at all answers false`() = runTest {
        // Shape a server uses for a URL that does not exist: 200 OK with an empty prop.
        enqueue207(
            multistatus(
                "<d:response><d:href>$CAL_PATH</d:href><d:propstat><d:prop/>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            )
        )

        assertEquals(CalDavResult.Success(false), client.probeCalendarCollection(url()))
    }

    @Test
    fun `empty successful propstat next to a failing one is unreadable`() = runTest {
        enqueue207(
            multistatus(
                "<d:response><d:href>$CAL_PATH</d:href>" +
                    "<d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "<d:propstat><d:prop><d:resourcetype/><d:displayname/></d:prop>" +
                    "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"
            )
        )

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `status placed before prop in the propstat is still read`() = runTest {
        enqueue207(
            multistatus(
                "<d:response><d:href>$CAL_PATH</d:href><d:propstat>" +
                    "<d:status>HTTP/1.1 200 OK</d:status>" +
                    "<d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype></d:prop>" +
                    "</d:propstat></d:response>"
            )
        )

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    // ==================== Status codes ====================

    @Test
    fun `404 is reported as 404`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        assertEquals(404, (client.probeCalendarCollection(url()) as CalDavResult.Error).code)
    }

    @Test
    fun `403 is reported as 403`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        assertEquals(403, (client.probeCalendarCollection(url()) as CalDavResult.Error).code)
    }

    @Test
    fun `410 is reported as 410`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(410))

        assertEquals(410, (client.probeCalendarCollection(url()) as CalDavResult.Error).code)
    }

    @Test
    fun `401 is reported as an auth error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.probeCalendarCollection(url()) as CalDavResult.Error
        assertEquals(401, result.code)
    }

    @Test
    fun `503 is reported with its code`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(503))

        assertEquals(503, (client.probeCalendarCollection(url()) as CalDavResult.Error).code)
    }

    @Test
    fun `connection failure is a network error`() = runTest {
        val deadUrl = url()
        mockWebServer.shutdown()

        val result = client.probeCalendarCollection(deadUrl) as CalDavResult.Error
        assertEquals(0, result.code)
    }

    @Test
    fun `read timeout is a timeout error`() = runTest {
        val slowClient = OkHttpCalDavClient(
            DefaultQuirks(url("/")),
            OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(207).setBody(multistatus(response()))
                .setHeadersDelay(2, TimeUnit.SECONDS)
        )

        val result = slowClient.probeCalendarCollection(url()) as CalDavResult.Error
        assertEquals(CalDavResult.CODE_TIMEOUT, result.code)
    }

    // ==================== Unreadable answers keep the calendar ====================

    @Test
    fun `HTML login page on 200 is unreadable`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body><form>Sign in to the Wi-Fi</form></body></html>")
        )

        assertNotMultistatus(client.probeCalendarCollection(url()))
    }

    @Test
    fun `empty 207 body is unreadable`() = runTest {
        enqueue207("")

        assertNotMultistatus(client.probeCalendarCollection(url()))
    }

    @Test
    fun `malformed XML is unreadable`() = runTest {
        enqueue207("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$CAL_PATH")

        assertNotMultistatus(client.probeCalendarCollection(url()))
    }

    @Test
    fun `multistatus with no response is unreadable`() = runTest {
        enqueue207(multistatus())

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `response about a different href is unreadable`() = runTest {
        enqueue207(
            multistatus(
                response(
                    href = "/calendars/user/",
                    resourceType = "<d:resourcetype><d:collection/></d:resourcetype>"
                )
            )
        )

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `two responses for the same href are unreadable`() = runTest {
        enqueue207(multistatus(response(), response(resourceType = "<d:resourcetype/>")))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `resourcetype in a 404 propstat is unreadable`() = runTest {
        enqueue207(multistatus(response(status = "HTTP/1.1 404 Not Found")))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `a failing resourcetype propstat after a successful one keeps the calendar answer`() = runTest {
        enqueue207(
            multistatus(
                "<d:response><d:href>$CAL_PATH</d:href>" +
                    "<d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype></d:prop>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "<d:propstat><d:prop><d:resourcetype/></d:prop>" +
                    "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"
            )
        )

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    @Test
    fun `skip rules judge the stored path, not the reply's spelling of the href`() = runTest {
        // Stored without the trailing slash, so the listing's reserved "tasks/" rule never applied.
        val stored = "/calendars/user/tasks"
        enqueue207(multistatus(response(href = "$stored/")))

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url(stored)))
    }

    @Test
    fun `malformed percent escape in the href does not match`() = runTest {
        enqueue207(multistatus(response(href = "/calendars/user/w%-1rk/")))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `response without any resourcetype is unreadable`() = runTest {
        enqueue207(multistatus(response(resourceType = null)))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    // ==================== Redirects ====================

    @Test
    fun `redirect to another host is unreadable even when it answers 404`() = runTest {
        val requested = mockWebServer.url(CAL_PATH).newBuilder().host("localhost").build()
        val elsewhere = mockWebServer.url("/portal/").newBuilder().host("127.0.0.1").build()
        mockWebServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.toString()))
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        assertUnreadable(client.probeCalendarCollection(requested.toString()))
    }

    @Test
    fun `redirect to another host is unreadable even when it answers not-a-calendar`() = runTest {
        val requested = mockWebServer.url(CAL_PATH).newBuilder().host("localhost").build()
        val elsewhere = mockWebServer.url(CAL_PATH).newBuilder().host("127.0.0.1").build()
        mockWebServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.toString()))
        enqueue207(multistatus(response(resourceType = "<d:resourcetype/>")))

        assertUnreadable(client.probeCalendarCollection(requested.toString()))
    }

    @Test
    fun `same-host redirect to another path is unreadable even when it answers not-a-calendar`() = runTest {
        val moved = "/calendars/user/moved/"
        mockWebServer.enqueue(MockResponse().setResponseCode(301).setHeader("Location", url(moved)))
        enqueue207(multistatus(response(href = moved, resourceType = "<d:resourcetype><d:collection/></d:resourcetype>")))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `same-host redirect to a login route answering 404 is unreadable`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url("/oauth2/sign_in")))
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        assertUnreadable(client.probeCalendarCollection(url()))
    }

    @Test
    fun `same-host redirect that only adds the trailing slash is evaluated normally`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(301).setHeader("Location", url(CAL_PATH)))
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        assertEquals(
            404,
            (client.probeCalendarCollection(url(CAL_PATH.trimEnd('/'))) as CalDavResult.Error).code
        )
    }

    @Test
    fun `digest auth retry on the same host is evaluated normally`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Digest realm=\"test\", nonce=\"abc123\", qop=\"auth\"")
        )
        enqueue207(multistatus(response()))

        assertEquals(CalDavResult.Success(true), client.probeCalendarCollection(url()))
    }

    // ==================== Same-server rule per provider ====================

    @Test
    fun `default quirks treat only the exact host as the same server`() {
        val quirks = DefaultQuirks("https://dav.example.test/")
        assertTrue(quirks.isSameServerRedirect("dav.example.test", "DAV.example.test"))
        assertFalse(quirks.isSameServerRedirect("dav.example.test", "portal.example.test"))
        assertFalse(quirks.isSameServerRedirect("caldav.icloud.com", "p42-caldav.icloud.com"))
    }

    @Test
    fun `iCloud quirks treat the canonical and partition hosts as the same server`() {
        val quirks = ICloudQuirks()
        assertTrue(quirks.isSameServerRedirect("caldav.icloud.com", "p42-caldav.icloud.com"))
        assertTrue(quirks.isSameServerRedirect("p42-caldav.icloud.com", "caldav.icloud.com"))
        assertTrue(quirks.isSameServerRedirect("p42-caldav.icloud.com", "p07-caldav.icloud.com"))
        assertFalse(quirks.isSameServerRedirect("caldav.icloud.com", "captive.portal.test"))
        assertFalse(quirks.isSameServerRedirect("caldav.icloud.com", "p42-caldav.icloud.com.evil.test"))
    }

    private companion object {
        const val CAL_PATH = "/calendars/user/work/"
        const val COMPONENTS_VEVENT_VTODO =
            "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>"
        const val COMPONENTS_VTODO_ONLY =
            "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>"
    }
}
