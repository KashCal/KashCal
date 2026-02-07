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
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests verifying HTTP Digest authentication support (RFC 2617/7616).
 *
 * KashCal sends HTTP Basic auth preemptively via NetworkInterceptor. When a server
 * (e.g., Baikal configured for Digest auth) rejects Basic with 401 + WWW-Authenticate: Digest,
 * the DigestAuthenticator handles the challenge-response and retries automatically.
 *
 * Flow: Request (Basic) → 401 Digest challenge → Authenticator computes Digest → retry → success
 */
class OkHttpCalDavClientDigestAuthTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    companion object {
        // Realistic Digest challenge header as Baikal/SabreDAV returns it
        private const val DIGEST_CHALLENGE =
            """Digest realm="SabreDAV", nonce="abc123def456", qop="auth", opaque="opaque-value""""

        // Digest challenge with stale=true (nonce expired, retry ok)
        private const val DIGEST_CHALLENGE_STALE =
            """Digest realm="SabreDAV", nonce="new-nonce-789", qop="auth", opaque="opaque-value", stale=true"""

        // Successful CalDAV OPTIONS response
        private const val DAV_HEADER = "1, 2, 3, calendar-access, addressbook"

        // Minimal PROPFIND response for principal discovery
        private val PROPFIND_PRINCIPAL_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/dav.php/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:current-user-principal>
                                <d:href>/dav.php/principals/testuser/</d:href>
                            </d:current-user-principal>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        // Calendar list PROPFIND response
        private val CALENDAR_LIST_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                           xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:response>
                    <d:href>/dav.php/calendars/testuser/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Default</d:displayname>
                            <d:resourcetype>
                                <d:collection/>
                                <c:calendar/>
                            </d:resourcetype>
                            <ic:calendar-color>#0000FFFF</ic:calendar-color>
                            <cs:getctag>ctag-123</cs:getctag>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        // Sync collection REPORT response
        private val SYNC_REPORT_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/dav.php/calendars/testuser/default/event1.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-event1"</d:getetag>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                <d:sync-token>new-sync-token-456</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        // iCal event body for fetchEvent
        private val ICAL_EVENT_BODY = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Test//EN
            BEGIN:VEVENT
            UID:test-uid@kashcal
            DTSTART:20260201T100000Z
            DTEND:20260201T110000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

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

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val credentials = Credentials(
            username = "testuser",
            password = "correctpassword",
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

    // ========== DIGEST AUTH SUCCESS TESTS ==========
    // These verify each CalDAV operation succeeds through Digest challenge-response.
    // Each test enqueues: 401 (Digest challenge) → success response.
    // The Authenticator handles the challenge transparently within OkHttp's execute().

    @Test
    fun `checkConnection succeeds through Digest auth challenge`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", DAV_HEADER)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed through Digest challenge-response", result.isSuccess())
    }

    @Test
    fun `discoverPrincipal succeeds through Digest auth challenge`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(PROPFIND_PRINCIPAL_RESPONSE)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.discoverPrincipal(serverUrl)

        assertTrue("Principal discovery should succeed through Digest", result.isSuccess())
    }

    @Test
    fun `listCalendars succeeds through Digest auth challenge`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(CALENDAR_LIST_RESPONSE)
        )

        val calendarHome = mockWebServer.url("/dav.php/calendars/testuser/").toString()
        val result = client.listCalendars(calendarHome)

        assertTrue("Calendar listing should succeed through Digest", result.isSuccess())
    }

    @Test
    fun `createEvent succeeds through Digest auth challenge`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"etag-new-event\"")
        )

        val calendarUrl = mockWebServer.url("/dav.php/calendars/testuser/default/").toString()
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test-uid@kashcal
            DTSTART:20260201T100000Z
            DTEND:20260201T110000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl, "test-uid@kashcal", ics)

        assertTrue("Event creation should succeed through Digest", result.isSuccess())
    }

    @Test
    fun `fetchEvent succeeds through Digest auth challenge`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-test\"")
                .setBody(ICAL_EVENT_BODY)
        )

        val eventUrl = mockWebServer.url("/dav.php/calendars/testuser/default/test.ics").toString()
        val result = client.fetchEvent(eventUrl)

        assertTrue("Event fetch should succeed through Digest", result.isSuccess())
    }

    @Test
    fun `syncCollection succeeds through Digest auth challenge`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(SYNC_REPORT_RESPONSE)
        )

        val calendarUrl = mockWebServer.url("/dav.php/calendars/testuser/default/").toString()
        val result = client.syncCollection(calendarUrl, null)

        assertTrue("Sync collection should succeed through Digest", result.isSuccess())
    }

    // ========== DIGEST AUTH HEADER VERIFICATION ==========

    @Test
    fun `client retries with Digest credentials after 401 challenge`() = runTest {
        // Verifies the full Basic → 401 → Digest → success flow with request inspection
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", DAV_HEADER)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after Digest retry", result.isSuccess())
        assertEquals(
            "Should make 2 requests (Basic→401, Digest→200)",
            2, mockWebServer.requestCount
        )

        // First request has Basic (preemptive from NetworkInterceptor)
        val firstRequest = mockWebServer.takeRequest()
        assertTrue(
            "First request should have Basic auth",
            firstRequest.getHeader("Authorization")?.startsWith("Basic ") == true
        )

        // Second request has Digest (from DigestAuthenticator)
        val secondRequest = mockWebServer.takeRequest()
        assertTrue(
            "Second request should have Digest auth",
            secondRequest.getHeader("Authorization")?.startsWith("Digest ") == true
        )
    }

    @Test
    fun `Digest auth header contains correct RFC 2617 fields`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", DAV_HEADER)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        client.checkConnection(serverUrl)

        mockWebServer.takeRequest() // skip first (Basic)
        val digestRequest = mockWebServer.takeRequest()
        val authHeader = digestRequest.getHeader("Authorization") ?: ""

        // Verify all required Digest fields per RFC 2617 Section 3.2.2
        assertTrue("Should start with Digest", authHeader.startsWith("Digest "))
        assertTrue("Should contain username", authHeader.contains("username=\"testuser\""))
        assertTrue("Should contain realm from challenge", authHeader.contains("realm=\"SabreDAV\""))
        assertTrue("Should contain nonce from challenge", authHeader.contains("nonce=\"abc123def456\""))
        assertTrue("Should contain nc=00000001", authHeader.contains("nc=00000001"))
        assertTrue("Should contain cnonce (hex)", Regex("cnonce=\"[a-f0-9]+\"").containsMatchIn(authHeader))
        assertTrue("Should contain response hash (32-char MD5)", Regex("response=\"[a-f0-9]{32}\"").containsMatchIn(authHeader))
        assertTrue("Should contain request URI", authHeader.contains("uri=\"/dav.php/\""))
        assertTrue("Should contain qop=auth", authHeader.contains("qop=auth"))
        assertTrue("Should echo opaque unchanged", authHeader.contains("opaque=\"opaque-value\""))
    }

    @Test
    fun `client sends Basic auth preemptively not in response to challenge`() = runTest {
        // When server accepts Basic directly, no Digest challenge occurs
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(PROPFIND_PRINCIPAL_RESPONSE)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        client.discoverPrincipal(serverUrl)

        val request = mockWebServer.takeRequest()
        val authHeader = request.getHeader("Authorization") ?: ""

        assertTrue(
            "Auth header should be Basic (preemptive)",
            authHeader.startsWith("Basic ")
        )
        assertFalse(
            "Auth header should NOT be Digest",
            authHeader.startsWith("Digest ")
        )
    }

    // ========== MIXED AUTH SCENARIOS ==========

    @Test
    fun `server offering both Basic and Digest - Digest auth succeeds`() = runTest {
        // Baikal may offer both auth methods but reject our preemptive Basic
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("WWW-Authenticate", DIGEST_CHALLENGE)
                .addHeader("WWW-Authenticate", "Basic realm=\"SabreDAV\"")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", DAV_HEADER)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed - DigestAuthenticator prefers Digest over Basic", result.isSuccess())
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `well-known discovery succeeds through Digest auth`() = runTest {
        // well-known endpoint also requires Digest auth
        // With Authenticator, the 401 triggers retry and actual response is returned
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(PROPFIND_PRINCIPAL_RESPONSE)
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverWellKnown(serverUrl)

        assertTrue("Well-known discovery should succeed through Digest", result.isSuccess())
    }

    // ========== WRONG CREDENTIALS / LOOP PREVENTION ==========

    @Test
    fun `wrong credentials fail after Digest retry - no infinite loop`() = runTest {
        // Server rejects Basic, then rejects Digest too (wrong password)
        // Authenticator detects existing Digest header + stale=false → returns null → stops
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should get auth error after failed Digest", result.isAuthError())
        assertEquals(
            "Should make exactly 2 requests (Basic→401, Digest→401, stop)",
            2, mockWebServer.requestCount
        )
    }

    // ========== STALE NONCE HANDLING ==========

    @Test
    fun `stale nonce triggers retry with new nonce`() = runTest {
        // 1. Basic → 401 Digest challenge (nonce1)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE)
        )
        // 2. Digest with nonce1 → 401 stale=true (nonce expired, server sends new nonce)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", DIGEST_CHALLENGE_STALE)
        )
        // 3. Digest with nonce2 → 200 success
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", DAV_HEADER)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed after stale nonce retry", result.isSuccess())
        assertEquals("Should make 3 requests", 3, mockWebServer.requestCount)

        // Verify third request uses the new nonce from stale challenge
        mockWebServer.takeRequest() // 1st: Basic
        mockWebServer.takeRequest() // 2nd: Digest with old nonce
        val thirdRequest = mockWebServer.takeRequest()
        val authHeader = thirdRequest.getHeader("Authorization") ?: ""
        assertTrue("Third request should use new nonce", authHeader.contains("nonce=\"new-nonce-789\""))
        assertTrue("Third request should reset nc to 1", authHeader.contains("nc=00000001"))
    }

    // ========== BASIC-ONLY COMPATIBILITY ==========

    @Test
    fun `Basic-only server works without Authenticator involvement`() = runTest {
        // Server accepts Basic auth directly - Authenticator never fires
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", DAV_HEADER)
        )

        val serverUrl = mockWebServer.url("/dav.php/").toString()
        val result = client.checkConnection(serverUrl)

        assertTrue("Should succeed with just Basic", result.isSuccess())
        assertEquals("Should make only 1 request (no Digest retry)", 1, mockWebServer.requestCount)

        val request = mockWebServer.takeRequest()
        assertTrue(
            "Should have Basic auth (preemptive)",
            request.getHeader("Authorization")?.startsWith("Basic ") == true
        )
    }
}
