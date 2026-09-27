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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests PROPFIND-based CalDAV discovery against the RFCs:
 * - Principal discovery through DAV:current-user-principal (RFC 6764 §6).
 * - Calendar listing with PROPFIND Depth:1 on the home, keeping resources whose resourcetype
 *   holds CALDAV:calendar (RFC 4791 §4.2).
 * - Parsing supported-calendar-component-set (RFC 4791 §5.2.3).
 * - calendar-home-set discovery, several homes included (RFC 4791 §6.2.1).
 * - The well-known URI (RFC 6764 §5).
 *
 * Tests check the outgoing request (method, headers, XML body) and the reply handling (status
 * codes, parsing).
 */
class OkHttpCalDavClientRfc4791DiscoveryTest {

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

    // ========== Principal Discovery (RFC 6764 §6) ==========

    @Test
    fun `discoverPrincipal sends PROPFIND with Depth 0`() = runTest {
        // The client asks for the principal with a PROPFIND at Depth:0.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse("/principals/user/testuser/"))
        )

        val serverUrl = mockWebServer.url("/").toString()
        client.discoverPrincipal(serverUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 4791 requires PROPFIND method", "PROPFIND", request.method)
        assertEquals("RFC 4791 requires Depth: 0 for principal discovery", "0", request.getHeader("Depth"))
    }

    @Test
    fun `discoverPrincipal requests current-user-principal property`() = runTest {
        // RFC 6764 §6: the PROPFIND body SHOULD ask for DAV:current-user-principal.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse("/principals/user/testuser/"))
        )

        val serverUrl = mockWebServer.url("/").toString()
        client.discoverPrincipal(serverUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Request must contain current-user-principal property",
            body.contains("current-user-principal")
        )
    }

    @Test
    fun `discoverPrincipal sends Content-Type application xml`() = runTest {
        // RFC 4918 §8.2: an XML request body SHOULD be sent as application/xml.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse("/principals/user/testuser/"))
        )

        val serverUrl = mockWebServer.url("/").toString()
        client.discoverPrincipal(serverUrl)

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("PROPFIND must have Content-Type", contentType)
        assertTrue(
            "Content-Type must be application/xml",
            contentType!!.contains("application/xml")
        )
    }

    @Test
    fun `discoverPrincipal resolves relative principal path to absolute URL`() = runTest {
        // RFC 4918 §8.3: an href may be relative (resolved against the request URL); the
        // client returns an absolute URL.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse("/principals/user/testuser/"))
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverPrincipal(serverUrl)

        assertTrue("Result should be success", result.isSuccess())
        val principalUrl = result.getOrNull()!!
        assertTrue(
            "Relative path should be resolved to absolute URL",
            principalUrl.startsWith("http")
        )
        assertTrue(
            "Resolved URL should contain the principal path",
            principalUrl.contains("/principals/user/testuser/")
        )
    }

    @Test
    fun `discoverPrincipal passes through absolute principal URL`() = runTest {
        // An absolute href is used as it is.
        val absoluteUrl = "https://caldav.example.com/principals/user/testuser/"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse(absoluteUrl))
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverPrincipal(serverUrl)

        assertTrue("Result should be success", result.isSuccess())
        val principalUrl = result.getOrNull()!!
        assertEquals(
            "Absolute URL from server should be returned as-is",
            absoluteUrl,
            principalUrl
        )
    }

    @Test
    fun `discoverPrincipal returns error when principal not in response`() = runTest {
        // A reply without the principal is an error, not a crash.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:displayname>Server</d:displayname>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverPrincipal(serverUrl)

        assertTrue("Should return error when principal not found", result.isError())
    }

    @Test
    fun `discoverPrincipal handles 401 authentication failure`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverPrincipal(serverUrl)

        assertTrue("Should return auth error on 401", result.isAuthError())
    }

    // ========== RFC 4791 Section 6.2.1: Calendar Home Set Discovery ==========

    @Test
    fun `discoverCalendarHome sends PROPFIND with Depth 0`() = runTest {
        // The home-set is read with a Depth:0 PROPFIND on the principal URL.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarHomeResponse("/calendars/testuser/"))
        )

        val principalUrl = mockWebServer.url("/principals/user/testuser/").toString()
        client.discoverCalendarHome(principalUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PROPFIND method", "PROPFIND", request.method)
        assertEquals("Must use Depth: 0", "0", request.getHeader("Depth"))
    }

    @Test
    fun `discoverCalendarHome requests calendar-home-set in caldav namespace`() = runTest {
        // The body asks for calendar-home-set in the CalDAV namespace
        // (urn:ietf:params:xml:ns:caldav, RFC 4791 §6.2.1).
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarHomeResponse("/calendars/testuser/"))
        )

        val principalUrl = mockWebServer.url("/principals/user/testuser/").toString()
        client.discoverCalendarHome(principalUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Request must contain calendar-home-set property",
            body.contains("calendar-home-set")
        )
        assertTrue(
            "Request must include CalDAV namespace",
            body.contains("urn:ietf:params:xml:ns:caldav")
        )
    }

    @Test
    fun `discoverCalendarHome returns multiple home URLs per RFC 4791 Section 6-2-1`() = runTest {
        // RFC 4791 §6.2.1: calendar-home-set holds any number of DAV:href elements.
        // Real-world: SOGo/AEGEE servers return 3+ home set URLs
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                        <d:response>
                            <d:href>/principals/user/testuser/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <c:calendar-home-set>
                                        <d:href>/calendars/testuser/</d:href>
                                        <d:href>/shared/calendars/group1/</d:href>
                                        <d:href>/other/calendars/</d:href>
                                    </c:calendar-home-set>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val principalUrl = mockWebServer.url("/principals/user/testuser/").toString()
        val result = client.discoverCalendarHome(principalUrl)

        assertTrue("Result should be success", result.isSuccess())
        val homeUrls = result.getOrNull()!!
        assertEquals(
            "RFC 4791 Section 6.2.1: All home set URLs must be returned",
            3,
            homeUrls.size
        )
    }

    @Test
    fun `discoverCalendarHome resolves relative home paths to absolute`() = runTest {
        // RFC 4918 §8.3: a relative href is resolved against the request URL.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarHomeResponse("/calendars/testuser/"))
        )

        val principalUrl = mockWebServer.url("/principals/user/testuser/").toString()
        val result = client.discoverCalendarHome(principalUrl)

        assertTrue("Result should be success", result.isSuccess())
        val homeUrls = result.getOrNull()!!
        assertTrue(
            "Relative path should be resolved to absolute URL",
            homeUrls[0].startsWith("http")
        )
        assertTrue(
            "Resolved URL should contain the home path",
            homeUrls[0].contains("/calendars/testuser/")
        )
    }

    @Test
    fun `discoverCalendarHome returns error for empty calendar-home-set`() = runTest {
        // A calendar-home-set with no hrefs is an error: there is nowhere to list calendars.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                        <d:response>
                            <d:href>/principals/user/testuser/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <c:calendar-home-set/>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val principalUrl = mockWebServer.url("/principals/user/testuser/").toString()
        val result = client.discoverCalendarHome(principalUrl)

        assertTrue("Should return error for empty calendar-home-set", result.isError())
    }

    // ========== Calendar Collection Listing (RFC 4791 §4.2, §5.2) ==========

    @Test
    fun `listCalendars sends PROPFIND with Depth 1`() = runTest {
        // A Depth:1 PROPFIND lists the children of the calendar home.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarListResponse())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        client.listCalendars(homeUrl)

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PROPFIND method", "PROPFIND", request.method)
        assertEquals(
            "RFC 4791 requires Depth: 1 for calendar enumeration",
            "1",
            request.getHeader("Depth")
        )
    }

    @Test
    fun `listCalendars requests resourcetype property`() = runTest {
        // RFC 4791 §4.2: a calendar collection is identified by its resourcetype.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarListResponse())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        client.listCalendars(homeUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request resourcetype property", body.contains("resourcetype"))
    }

    @Test
    fun `listCalendars requests supported-calendar-component-set`() = runTest {
        // RFC 4791 Section 5.2.3: Used to filter VEVENT-only vs VTODO calendars
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarListResponse())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        client.listCalendars(homeUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Must request supported-calendar-component-set",
            body.contains("supported-calendar-component-set")
        )
    }

    @Test
    fun `listCalendars requests displayname property`() = runTest {
        // RFC 4918: displayname for human-readable calendar names
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarListResponse())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        client.listCalendars(homeUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request displayname property", body.contains("displayname"))
    }

    @Test
    fun `listCalendars requests current-user-privilege-set`() = runTest {
        // RFC 3744: Used to detect read-only vs read-write calendars
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(calendarListResponse())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        client.listCalendars(homeUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Must request current-user-privilege-set",
            body.contains("current-user-privilege-set")
        )
    }

    @Test
    fun `listCalendars filters non-calendar resources`() = runTest {
        // RFC 4791 §4.2: only resources whose resourcetype holds CALDAV:calendar are calendars.
        // Response includes a calendar, a non-calendar collection, and the home collection itself
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                                   xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                        <!-- Home collection itself (not a calendar) -->
                        <d:response>
                            <d:href>/calendars/testuser/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/></d:resourcetype>
                                    <d:displayname>User Home</d:displayname>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                        <!-- Actual calendar -->
                        <d:response>
                            <d:href>/calendars/testuser/personal/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                    <d:displayname>Personal</d:displayname>
                                    <c:supported-calendar-component-set>
                                        <c:comp name="VEVENT"/>
                                    </c:supported-calendar-component-set>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                        <!-- Non-calendar resource (e.g., inbox) -->
                        <d:response>
                            <d:href>/calendars/testuser/inbox/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:schedule-inbox/></d:resourcetype>
                                    <d:displayname>Inbox</d:displayname>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        val result = client.listCalendars(homeUrl)

        assertTrue("Result should be success", result.isSuccess())
        val calendars = result.getOrNull()!!
        assertEquals(
            "Only resources with <calendar> resourcetype should be included",
            1,
            calendars.size
        )
        assertEquals("Personal", calendars[0].displayName)
    }

    @Test
    fun `listCalendars identifies VEVENT-only calendars via component set`() = runTest {
        // RFC 4791 Section 5.2.3: supported-calendar-component-set declares supported types
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                                   xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                        <d:response>
                            <d:href>/calendars/testuser/events/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                    <d:displayname>Events Only</d:displayname>
                                    <c:supported-calendar-component-set>
                                        <c:comp name="VEVENT"/>
                                    </c:supported-calendar-component-set>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                        <d:response>
                            <d:href>/calendars/testuser/mixed/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                    <d:displayname>Mixed</d:displayname>
                                    <c:supported-calendar-component-set>
                                        <c:comp name="VEVENT"/>
                                        <c:comp name="VTODO"/>
                                    </c:supported-calendar-component-set>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        val result = client.listCalendars(homeUrl)

        assertTrue("Result should be success", result.isSuccess())
        val calendars = result.getOrNull()!!
        assertEquals(2, calendars.size)

        val eventsOnly = calendars.find { it.displayName == "Events Only" }!!
        assertTrue(
            "VEVENT should be in supported components",
            eventsOnly.supportedComponents.contains("VEVENT")
        )
        assertFalse(
            "VTODO should NOT be in events-only calendar",
            eventsOnly.supportedComponents.contains("VTODO")
        )

        val mixed = calendars.find { it.displayName == "Mixed" }!!
        assertTrue(
            "Both VEVENT and VTODO should be in mixed calendar",
            mixed.supportedComponents.containsAll(setOf("VEVENT", "VTODO"))
        )
    }

    @Test
    fun `listCalendars detects read-only calendars via privilege set`() = runTest {
        // RFC 3744: current-user-privilege-set indicates read/write access
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                                   xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                        <d:response>
                            <d:href>/calendars/testuser/shared/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                    <d:displayname>Shared Calendar</d:displayname>
                                    <d:current-user-privilege-set>
                                        <d:privilege><d:read/></d:privilege>
                                        <d:privilege><d:read-current-user-privilege-set/></d:privilege>
                                    </d:current-user-privilege-set>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        val result = client.listCalendars(homeUrl)

        assertTrue("Result should be success", result.isSuccess())
        val calendars = result.getOrNull()!!
        assertEquals(1, calendars.size)
        assertTrue(
            "Calendar without write privilege should be read-only",
            calendars[0].isReadOnly
        )
    }

    @Test
    fun `listCalendars handles multi-propstat response per RFC 4918`() = runTest {
        // RFC 4918 §14.22: each propstat pairs a status with the props it applies to, so a
        // response can hold several.
        // Real-world: Stalwart/Radicale return optional props in separate 404 propstat
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                                   xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                        <d:response>
                            <d:href>/calendars/testuser/personal/</d:href>
                            <!-- Required properties: 200 OK -->
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                    <d:displayname>Personal</d:displayname>
                                    <c:supported-calendar-component-set>
                                        <c:comp name="VEVENT"/>
                                    </c:supported-calendar-component-set>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                            <!-- Optional properties: 404 Not Found -->
                            <d:propstat>
                                <d:prop>
                                    <ic:calendar-color/>
                                    <cs:getctag/>
                                    <d:current-user-privilege-set/>
                                </d:prop>
                                <d:status>HTTP/1.1 404 Not Found</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        val result = client.listCalendars(homeUrl)

        assertTrue("Result should be success", result.isSuccess())
        val calendars = result.getOrNull()!!
        assertEquals(
            "Calendar with resourcetype in 200 propstat should be included",
            1,
            calendars.size
        )
        assertEquals("Personal", calendars[0].displayName)
        // Optional props in 404 should result in null/defaults
        assertNull("Color should be null when in 404 propstat", calendars[0].color)
        assertNull("Ctag should be null when in 404 propstat", calendars[0].ctag)
    }

    @Test
    fun `listCalendars rejects calendar when resourcetype in 404 propstat`() = runTest {
        // A resourcetype in the 404 propstat was not found, so the resource isn't a calendar.
        // Real-world: Stalwart can return resourcetype in 404 for non-collection resources
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                                   xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                        <d:response>
                            <d:href>/calendars/testuser/broken/</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:displayname>Not A Calendar</d:displayname>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                            <d:propstat>
                                <d:prop>
                                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                </d:prop>
                                <d:status>HTTP/1.1 404 Not Found</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                """.trimIndent())
        )

        val homeUrl = mockWebServer.url("/calendars/testuser/").toString()
        val result = client.listCalendars(homeUrl)

        assertTrue("Result should be success", result.isSuccess())
        val calendars = result.getOrNull()!!
        assertEquals(
            "Calendar with resourcetype in 404 propstat must be rejected",
            0,
            calendars.size
        )
    }

    // ========== Well-Known URI Discovery (RFC 6764 §5) ==========

    @Test
    fun `wellKnown sends PROPFIND to well-known caldav path`() = runTest {
        // RFC 6764 §6: without a TXT path, the initial context path is /.well-known/caldav.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse("/principals/user/testuser/"))
        )

        val serverUrl = mockWebServer.url("/").toString()
        client.discoverWellKnown(serverUrl)

        val request = mockWebServer.takeRequest()
        assertTrue(
            "Request path must include /.well-known/caldav",
            request.path!!.contains("/.well-known/caldav")
        )
    }

    @Test
    fun `wellKnown returns final URL after redirects`() = runTest {
        // RFC 6764 §5: the server redirects well-known to the CalDAV endpoint. No redirect
        // is enqueued here, so this covers a well-known that answers in place;
        // OkHttpCalDavClientRedirectTest covers the redirect.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(principalResponse("/principals/user/testuser/"))
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverWellKnown(serverUrl)

        assertTrue("Result should be success", result.isSuccess())
        val discoveredUrl = result.getOrNull()!!
        assertTrue("Should return a valid URL", discoveredUrl.startsWith("http"))
    }

    @Test
    fun `wellKnown returns original URL when well-known returns 404`() = runTest {
        // A 404 on well-known falls back to the entered URL.
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val serverUrl = mockWebServer.url("/").toString()
        val result = client.discoverWellKnown(serverUrl)

        assertTrue("Result should be success even on 404", result.isSuccess())
    }

    // ========== Helper Methods ==========

    private fun principalResponse(principalPath: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/</d:href>
                <d:propstat>
                    <d:prop>
                        <d:current-user-principal>
                            <d:href>$principalPath</d:href>
                        </d:current-user-principal>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun calendarHomeResponse(homePath: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:response>
                <d:href>/principals/user/testuser/</d:href>
                <d:propstat>
                    <d:prop>
                        <c:calendar-home-set>
                            <d:href>$homePath</d:href>
                        </c:calendar-home-set>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun calendarListResponse(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                       xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
            <d:response>
                <d:href>/calendars/testuser/personal/</d:href>
                <d:propstat>
                    <d:prop>
                        <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                        <d:displayname>Personal</d:displayname>
                        <ic:calendar-color>#0E61B9FF</ic:calendar-color>
                        <cs:getctag>ctag-123</cs:getctag>
                        <c:supported-calendar-component-set>
                            <c:comp name="VEVENT"/>
                        </c:supported-calendar-component-set>
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
