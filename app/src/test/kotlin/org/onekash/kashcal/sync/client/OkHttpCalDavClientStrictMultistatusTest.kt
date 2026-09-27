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
import org.onekash.kashcal.network.HostileDavServer
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Checks that a PROPFIND or REPORT reply that isn't a WebDAV multistatus is an error on every
 * call that reads the reply's body, never an empty answer: a sync treats "empty" as "the server
 * has nothing" and deletes local events.
 */
class OkHttpCalDavClientStrictMultistatusTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun u(path: String) = server.url(path).toString()

    /**
     * Every PROPFIND or REPORT call that reads a multistatus body, by name. Left out:
     * `discoverWellKnown`, which reads only where the redirects led.
     */
    private val calls: List<Pair<String, suspend () -> CalDavResult<*>>> by lazy {
        listOf(
            "discoverPrincipal" to { client.discoverPrincipal(u("/dav/")) },
            "discoverCalendarUserAddresses" to { client.discoverCalendarUserAddresses(u("/principals/u/")) },
            "discoverScheduleOutboxUrl" to { client.discoverScheduleOutboxUrl(u("/principals/u/")) },
            "discoverCalendarHome" to { client.discoverCalendarHome(u("/principals/u/")) },
            "listCalendars" to { client.listCalendars(u("/calendars/u/")) },
            "getCtag" to { client.getCtag(u("/calendars/u/cal/")) },
            "getSyncToken" to { client.getSyncToken(u("/calendars/u/cal/")) },
            "syncCollection" to { client.syncCollection(u("/calendars/u/cal/"), "token-1") },
            "fetchEventsInRange" to { client.fetchEventsInRange(u("/calendars/u/cal/"), 0L, 1_000_000_000_000L) },
            "fetchAllEtags" to { client.fetchAllEtags(u("/calendars/u/cal/")) },
            "fetchEtagsInRange" to { client.fetchEtagsInRange(u("/calendars/u/cal/"), 0L, 1_000_000_000_000L) },
            "fetchEventsByHref" to { client.fetchEventsByHref(u("/calendars/u/cal/"), listOf("/calendars/u/cal/a.ics")) },
            "fetchEtag" to { client.fetchEtag(u("/calendars/u/cal/a.ics")) },
            "probeCalendarCollection" to { client.probeCalendarCollection(u("/calendars/u/cal/")) },
        )
    }

    private suspend fun assertEveryCallRefuses(reply: () -> MockResponse, what: String) {
        for ((name, call) in calls) {
            server.enqueue(reply())
            val result = call()
            assertTrue("$name on $what: $result", result is CalDavResult.Error)
            result as CalDavResult.Error
            assertEquals("$name on $what", CalDavResult.CODE_NOT_MULTISTATUS, result.code)
            assertTrue("$name on $what is retryable", result.isRetryable)
        }
    }

    @Test
    fun `a hotspot login page answered with 200 is an error on every call`() = runTest {
        assertEveryCallRefuses({ HostileDavServer.loginPage() }, "an HTML 200")
    }

    @Test
    fun `a 207 cut off half way is an error on every call`() = runTest {
        assertEveryCallRefuses({ HostileDavServer.garbledMultistatus() }, "a garbled 207")
    }

    @Test
    fun `a 207 carrying a web page is an error on every call`() = runTest {
        assertEveryCallRefuses(
            { HostileDavServer.loginPage().setResponseCode(207) },
            "an HTML 207"
        )
    }

    @Test
    fun `a 507 whose body is not XML is an error, not a truncated success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(507).setBody("Server storage limit exceeded"))

        val result = client.syncCollection(u("/calendars/u/cal/"), "token-1")

        assertEquals(CalDavResult.CODE_NOT_MULTISTATUS, (result as CalDavResult.Error).code)
    }

    @Test
    fun `an empty but valid listing is still an empty success`() = runTest {
        server.enqueue(HostileDavServer.multistatus(""))
        server.enqueue(HostileDavServer.multistatus(""))

        assertEquals(emptyList<Any>(), client.fetchAllEtags(u("/calendars/u/cal/")).getOrNull())
        assertEquals(emptyList<Any>(), client.fetchEtagsInRange(u("/calendars/u/cal/"), 0L, 1_000_000_000_000L).getOrNull())
    }

    @Test
    fun `a truncated listing is an error on every listing call`() = runTest {
        val listings: List<Pair<String, suspend () -> CalDavResult<*>>> = listOf(
            "fetchAllEtags" to { client.fetchAllEtags(u("/calendars/u/cal/")) },
            "fetchEtagsInRange" to { client.fetchEtagsInRange(u("/calendars/u/cal/"), 0L, 1_000_000_000_000L) },
            "fetchEventsInRange" to { client.fetchEventsInRange(u("/calendars/u/cal/"), 0L, 1_000_000_000_000L) },
        )
        for ((name, call) in listings) {
            server.enqueue(
                HostileDavServer.truncatedListing("/calendars/u/cal/", HostileDavServer.member("/calendars/u/cal/a.ics", "a1"))
            )
            val result = call()
            assertTrue("$name: $result", result is CalDavResult.Error)
            assertEquals(name, 507, (result as CalDavResult.Error).code)
            assertTrue(name, result.isRetryable)
        }
    }

    @Test
    fun `a listing only limited by a number-of-matches error is an error too`() = runTest {
        server.enqueue(
            HostileDavServer.multistatus(
                HostileDavServer.member("/calendars/u/cal/a.ics", "a1") +
                    """<d:response><d:href>/calendars/u/cal/</d:href><d:status>HTTP/1.1 200 OK</d:status>""" +
                    """<d:error><d:number-of-matches-within-limits/></d:error></d:response>"""
            )
        )

        val result = client.fetchAllEtags(u("/calendars/u/cal/"))

        assertEquals(507, (result as CalDavResult.Error).code)
    }

    @Test
    fun `a valid ETag answer still gives the ETag`() = runTest {
        server.enqueue(HostileDavServer.multistatus(HostileDavServer.member("/calendars/u/cal/a.ics", "abc")))

        assertEquals("abc", client.fetchEtag(u("/calendars/u/cal/a.ics")).getOrNull())
    }
}
