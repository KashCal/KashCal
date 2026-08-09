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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.util.CaldavUrlNormalizer

/**
 * Identity contract for the URL *builders* that produce and reconstruct CalDAV
 * resource URLs. [CaldavUrlNormalizerTest] pins the comparison-only canonicalizer;
 * this pins the two surfaces that FEED it:
 *
 *  1. [OkHttpCalDavClient.createEvent] / [OkHttpCalDavClient.moveEvent] — the raw
 *     `"${calendarUrl.trimEnd('/')}/$uid.ics"` interpolation that mints the resource
 *     URL the app then stores as `caldav_url`. Driven here through the REAL client +
 *     [MockWebServer] (not a re-implementation of the string logic), so the assertion
 *     tracks what actually goes on the wire, encoding included.
 *  2. [DefaultQuirks.buildEventUrl] / [ICloudQuirks.buildEventUrl] — reconstruction of
 *     an absolute URL from an echoed href + calendar URL (absolute-href passthrough,
 *     relative-href host-join, leading-slash normalization).
 *
 * Grounded in RFC 3986 §3.3 (`pchar` — '@' is pchar-legal in a path segment, '/' is a
 * segment delimiter) and the reference client's URL-identity behavior: it stores a
 * decoded file-name segment and canonically percent-encodes on append (a literal '/'
 * in a name becomes `%2F`, never a new segment). KashCal instead interpolates raw, so
 * this test *characterizes* where KashCal's builder diverges from that model and ties
 * the builder output to the normalizer that has to reconcile it.
 *
 * All assertions are on existing behavior — no production change is implied.
 */
class CalDavUrlBuilderIdentityTest {

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

    private fun testIcal(uid: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//KashCal//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260115T000000Z
        DTSTART:20260201T100000Z
        DTEND:20260201T110000Z
        SUMMARY:Test Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    // ========== createEvent: URL minting on the wire ==========

    @Test
    fun `createEvent keeps a literal at-sign UID as a single path segment`() = runTest {
        // KashCal UIDs are "<uuid>@kashcal.onekash.org". '@' is pchar-legal (RFC 3986
        // §3.3), so it must stay literal AND stay inside one segment — the resource is
        // "<uuid>@kashcal.onekash.org.ics", not a nested path.
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e\""))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val uid = "550e8400-e29b-41d4-a716-446655440000@kashcal.onekash.org"
        val result = client.createEvent(calendarUrl, uid, testIcal(uid))

        val request = mockWebServer.takeRequest()
        assertEquals("PUT", request.method)
        // Wire path retains the literal '@' and appends exactly one ".ics" segment.
        assertTrue(
            "wire path must end with the literal-@ segment, got ${request.path}",
            request.path!!.endsWith("/$uid.ics")
        )
        // Exactly one segment past the collection — no boundary crossing.
        assertEquals(
            "/calendars/testuser/personal/$uid.ics",
            request.path
        )
        // The stored URL the app keeps is the same literal-@ form.
        val storedUrl = result.getOrNull()!!.first
        assertTrue(storedUrl.endsWith("/$uid.ics"))
        assertTrue("stored url should keep literal @", storedUrl.contains("@"))
        assertFalse("stored url should not pre-encode @", storedUrl.contains("%40"))
    }

    @Test
    fun `createEvent produces the same wire path whether or not the calendar URL has a trailing slash`() = runTest {
        // trimEnd('/') must make the two calendar-URL spellings converge on one resource.
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e2\""))

        val uid = "abc@kashcal.onekash.org"
        val withSlash = mockWebServer.url("/calendars/testuser/personal/").toString()
        val withoutSlash = mockWebServer.url("/calendars/testuser/personal").toString()

        client.createEvent(withSlash, uid, testIcal(uid))
        val pathWithSlash = mockWebServer.takeRequest().path

        client.createEvent(withoutSlash, uid, testIcal(uid))
        val pathWithoutSlash = mockWebServer.takeRequest().path

        assertEquals(
            "trailing-slash and no-trailing-slash calendar URLs must mint the same resource path",
            pathWithSlash,
            pathWithoutSlash
        )
        assertEquals("/calendars/testuser/personal/$uid.ics", pathWithSlash)
    }

    @Test
    fun `createEvent does NOT encode a slash-bearing UID - documents segment-boundary crossing`() = runTest {
        // CHARACTERIZATION (not endorsement): the raw interpolation means a '/' inside a
        // UID becomes a real path separator, unlike the reference client which encodes it
        // as %2F to keep one segment. This is latent-only: KashCal-generated UIDs are
        // "<uuid>@<domain>" and never contain '/'. If a UID source ever admits '/', the
        // resource identity would silently split across a boundary — this test is the
        // tripwire that would flag such a change.
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e\""))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val uid = "a/b@kashcal.onekash.org"
        client.createEvent(calendarUrl, uid, testIcal(uid))

        val request = mockWebServer.takeRequest()
        // Current behavior: the '/' is a live separator, NOT %2F. Pin it so a future
        // change to encoding (deliberate or accidental) is visible in the diff.
        assertEquals("/calendars/testuser/personal/a/b@kashcal.onekash.org.ics", request.path)
        assertFalse("current builder does not encode '/' as %2F", request.path!!.contains("%2F"))
    }

    @Test
    fun `moveEvent builds the destination header the same way createEvent builds its URL`() = runTest {
        // The MOVE Destination header is minted by the identical interpolation. It must
        // land the resource at the same {calendar}/{uid}.ics as a create would.
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"moved\""))

        val sourceUrl = mockWebServer.url("/calendars/testuser/work/abc@kashcal.onekash.org.ics").toString()
        val destCalendar = mockWebServer.url("/calendars/testuser/personal/").toString()
        val uid = "abc@kashcal.onekash.org"

        val result = client.moveEvent(sourceUrl, destCalendar, uid)

        val request = mockWebServer.takeRequest()
        assertEquals("MOVE", request.method)
        val destination = request.getHeader("Destination")!!
        assertTrue(
            "MOVE Destination must target {destCalendar}/{uid}.ics, got $destination",
            destination.endsWith("/calendars/testuser/personal/$uid.ics")
        )
        // The returned (stored) URL matches the Destination header form.
        assertEquals(destination, result.getOrNull()!!.first)
    }

    // ========== buildEventUrl: reconstruction from an echoed href ==========

    @Test
    fun `DefaultQuirks buildEventUrl passes an absolute href through verbatim`() {
        val quirks = DefaultQuirks("https://s.example")
        val calendarUrl = "https://s.example/cal/"

        // A server that echoes an absolute href — in either encoding — is trusted verbatim.
        val literal = "https://s.example/cal/uuid@kashcal.onekash.org.ics"
        val encoded = "https://s.example/cal/uuid%40kashcal.onekash.org.ics"
        assertEquals(literal, quirks.buildEventUrl(literal, calendarUrl))
        assertEquals(encoded, quirks.buildEventUrl(encoded, calendarUrl))
    }

    @Test
    fun `DefaultQuirks buildEventUrl output canonicalizes-equal for literal-at and percent-40 echoes`() {
        // The builder⇄normalizer tie: whichever encoding the server echoes, the
        // reconstructed URL must canonicalize to the same identity the create path stored.
        val quirks = DefaultQuirks("https://s.example")
        val calendarUrl = "https://s.example/cal/"

        val fromLiteral = quirks.buildEventUrl("/cal/uuid@kashcal.onekash.org.ics", calendarUrl)
        val fromEncoded = quirks.buildEventUrl("/cal/uuid%40kashcal.onekash.org.ics", calendarUrl)

        assertEquals(
            CaldavUrlNormalizer.canonicalize(fromLiteral),
            CaldavUrlNormalizer.canonicalize(fromEncoded)
        )
    }

    @Test
    fun `DefaultQuirks buildEventUrl joins a root-relative href onto the calendar host`() {
        val quirks = DefaultQuirks("https://s.example")
        val calendarUrl = "https://s.example/dav/cal/"

        // Root-relative href resolves against the host of the calendar URL, not its path.
        assertEquals(
            "https://s.example/other/evt@kashcal.onekash.org.ics",
            quirks.buildEventUrl("/other/evt@kashcal.onekash.org.ics", calendarUrl)
        )
    }

    @Test
    fun `DefaultQuirks buildEventUrl prepends a leading slash to a bare relative href`() {
        val quirks = DefaultQuirks("https://s.example")
        val calendarUrl = "https://s.example/dav/cal/"

        assertEquals(
            "https://s.example/evt.ics",
            quirks.buildEventUrl("evt.ics", calendarUrl)
        )
    }

    @Test
    fun `DefaultQuirks buildEventUrl preserves the calendar URL port when joining a relative href`() {
        val quirks = DefaultQuirks("https://s.example:8443")
        val calendarUrl = "https://s.example:8443/dav/cal/"

        assertEquals(
            "https://s.example:8443/dav/cal/evt.ics",
            quirks.buildEventUrl("/dav/cal/evt.ics", calendarUrl)
        )
    }

    @Test
    fun `ICloudQuirks buildEventUrl folds a regional host and strips the 443 port on reconstruction`() {
        val quirks = ICloudQuirks()
        // Calendar URL as iCloud hands it out: regional host, explicit :443.
        val calendarUrl = "https://p180-caldav.icloud.com:443/123456/calendars/home/"

        // Root-relative href → host-join, then normalize to the canonical host.
        assertEquals(
            "https://caldav.icloud.com/123456/calendars/home/evt@kashcal.onekash.org.ics",
            quirks.buildEventUrl("/123456/calendars/home/evt@kashcal.onekash.org.ics", calendarUrl)
        )
    }

    @Test
    fun `ICloudQuirks buildEventUrl normalizes an absolute regional href to the canonical host`() {
        val quirks = ICloudQuirks()
        val calendarUrl = "https://caldav.icloud.com/123456/calendars/home/"

        // Even a fully-absolute echoed href gets host-normalized (regional → canonical).
        assertEquals(
            "https://caldav.icloud.com/123456/calendars/home/evt.ics",
            quirks.buildEventUrl("https://p42-caldav.icloud.com/123456/calendars/home/evt.ics", calendarUrl)
        )
    }
}
