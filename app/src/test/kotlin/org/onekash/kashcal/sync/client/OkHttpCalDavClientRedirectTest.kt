package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.network.HostileDavServer
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.util.concurrent.TimeUnit

/**
 * The real CalDAV client, as the factory builds it, when the server redirects:
 * an edit, delete, move or listing reaches the new location unchanged and the
 * caller learns where it ended up; nothing is ever sent over plain http for an
 * https account.
 */
class OkHttpCalDavClientRedirectTest {

    private val servers = mutableListOf<MockWebServer>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        unmockkAll()
    }

    private fun http() = HostileDavServer.httpServer().also { servers += it }

    /** A client for an account set up at [accountUrl], built by the real factory. */
    private fun clientFor(accountUrl: String): CalDavClient =
        OkHttpCalDavClientFactory().createClient(
            Credentials(username = "testuser", password = "testpass", serverUrl = accountUrl),
            DefaultQuirks(accountUrl)
        )

    private fun MockWebServer.next(): RecordedRequest = takeRequest(5, TimeUnit.SECONDS)!!

    private fun MockWebServer.base() = url("/").toString()

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"

    // ---- writes ----

    @Test
    fun `a redirected update is sent again as a PUT and reports where it ended up`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(301, "/moved/event.ics"))
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"e2\""))

        val result = clientFor(server.base()).updateEvent(server.url("/cal/event.ics").toString(), ics, "e1")

        assertTrue("$result", result is CalDavResult.Success)
        result as CalDavResult.Success
        assertEquals("e2", result.data)
        assertEquals(server.url("/moved/event.ics").toString(), result.finalUrl)
        server.next()
        val resent = server.next()
        assertEquals("PUT", resent.method)
        assertEquals("\"e1\"", resent.getHeader("If-Match"))
        assertEquals(ics, resent.body.readUtf8())
    }

    @Test
    fun `an update that was not redirected reports no new URL`() = runTest {
        val server = http()
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"e2\""))

        val result = clientFor(server.base()).updateEvent(server.url("/cal/event.ics").toString(), ics, "e1")

        assertNull((result as CalDavResult.Success).finalUrl)
    }

    @Test
    fun `a redirected update without an ETag reads the new ETag where the event landed`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(301, "/moved/event.ics"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(HostileDavServer.multistatus(HostileDavServer.member("/moved/event.ics", "e3")))

        val result = clientFor(server.base()).updateEvent(server.url("/cal/event.ics").toString(), ics, "e1")

        assertEquals("e3", result.getOrNull())
        server.next()
        server.next()
        val propfind = server.next()
        assertEquals("PROPFIND", propfind.method)
        assertEquals("/moved/event.ics", propfind.path)
    }

    @Test
    fun `a redirected delete is sent again as a DELETE with its If-Match`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(302, "/moved/event.ics"))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = clientFor(server.base()).deleteEvent(server.url("/cal/event.ics").toString(), "e1")

        assertTrue("$result", result.isSuccess())
        server.next()
        val resent = server.next()
        assertEquals("DELETE", resent.method)
        assertEquals("\"e1\"", resent.getHeader("If-Match"))
    }

    @Test
    fun `a delete redirected to a page is not reported as done unless the DELETE itself succeeded`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(302, "/login"))
        server.enqueue(MockResponse().setResponseCode(405))

        val result = clientFor(server.base()).deleteEvent(server.url("/cal/event.ics").toString(), "e1")

        assertTrue("$result", result is CalDavResult.Error)
        server.next()
        assertEquals("DELETE", server.next().method)
    }

    @Test
    fun `a redirected create reports the URL the server took it at`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(307, "/elsewhere/new.ics"))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"c1\""))

        val result = clientFor(server.base()).createEvent(server.url("/cal/").toString(), "new", ics)

        result as CalDavResult.Success
        assertEquals(server.url("/elsewhere/new.ics").toString(), result.data.first)
        assertEquals(result.data.first, result.finalUrl)
        server.next()
        val resent = server.next()
        assertEquals("PUT", resent.method)
        assertEquals("*", resent.getHeader("If-None-Match"))
    }

    @Test
    fun `a redirected move is sent again as a MOVE with the same Destination`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(308, "/moved/event.ics"))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"m1\""))

        val result = clientFor(server.base())
            .moveEvent(server.url("/cal/event.ics").toString(), server.url("/other/").toString(), "uid-1")

        assertTrue("$result", result.isSuccess())
        val first = server.next()
        val resent = server.next()
        assertEquals("MOVE", resent.method)
        assertEquals(first.getHeader("Destination"), resent.getHeader("Destination"))
        assertEquals("F", resent.getHeader("Overwrite"))
    }

    // ---- reads ----

    @Test
    fun `a redirected listing is sent again as the same REPORT or PROPFIND`() = runTest {
        val server = http()
        val listing = HostileDavServer.multistatus(HostileDavServer.member("/moved/a.ics", "a1"))
        server.enqueue(HostileDavServer.redirect(301, "/moved/"))
        server.enqueue(listing)
        server.enqueue(HostileDavServer.redirect(301, "/moved/"))
        server.enqueue(listing)
        val client = clientFor(server.base())

        val report = client.fetchEtagsInRange(server.url("/cal/").toString(), 0L, 1_000_000_000_000L)
        val propfind = client.fetchAllEtags(server.url("/cal/").toString())

        assertEquals(1, report.getOrNull()!!.size)
        assertEquals(1, propfind.getOrNull()!!.size)
        val requests = List(4) { server.next() }
        assertEquals(listOf("REPORT", "REPORT", "PROPFIND", "PROPFIND"), requests.map { it.method })
        assertEquals(requests[0].body.readUtf8(), requests[1].body.readUtf8())
        assertEquals("1", requests[3].getHeader("Depth"))
    }

    @Test
    fun `a redirected fetch returns the event`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(301, "/moved/event.ics"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"g1\"").setBody(ics))

        val result = clientFor(server.base()).fetchEvent(server.url("/cal/event.ics").toString())

        assertEquals(ics, result.getOrNull()!!.icalData)
    }

    @Test
    fun `a redirected connection check still validates the server`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(308, "/dav/"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("DAV", "1, 2, calendar-access"))

        val result = clientFor(server.base()).checkConnection(server.url("/").toString())

        assertTrue("$result", result.isSuccess())
        server.next()
        assertEquals("OPTIONS", server.next().method)
    }

    @Test
    fun `a redirected outbox post is sent again as a POST`() = runTest {
        val server = http()
        server.enqueue(HostileDavServer.redirect(307, "/outbox2/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<C:schedule-response xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>"))

        clientFor(server.base()).postToOutbox(server.url("/outbox/").toString(), "me@example.test", listOf("you@example.test"), ics)

        server.next()
        val resent = server.next()
        assertEquals("POST", resent.method)
        assertEquals(ics, resent.body.readUtf8())
    }

    // ---- refusals ----

    @Test
    fun `an https account refuses a plain http URL before sending anything`() = runTest {
        val server = http()
        val result = clientFor("https://dav.example.test/").updateEvent(server.url("/cal/event.ics").toString(), ics, "e1")

        result as CalDavResult.Error
        assertEquals(CalDavResult.CODE_TRANSPORT_REFUSED, result.code)
        assertTrue("a pending edit waits instead of failing for good", result.isRetryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect loop ends as a refused connection on every kind of call`() = runTest {
        val server = http()
        val client = clientFor(server.base())
        val calls: List<suspend () -> CalDavResult<*>> = listOf(
            { client.updateEvent(server.url("/a").toString(), ics, "e") },
            { client.deleteEvent(server.url("/a").toString(), "e") },
            { client.fetchAllEtags(server.url("/a/").toString()) },
            { client.syncCollection(server.url("/a/").toString(), "token") },
            { client.checkConnection(server.url("/a").toString()) },
            { client.discoverWellKnown(server.url("/").toString()) },
            { client.fetchEvent(server.url("/a").toString()) },
            { client.fetchEtag(server.url("/a").toString()) },
            { client.createEvent(server.url("/a/").toString(), "u", ics) },
            { client.moveEvent(server.url("/a").toString(), server.url("/b/").toString(), "u") },
            { client.postToOutbox(server.url("/a/").toString(), "me@example.test", listOf("you@example.test"), ics) },
            { client.supportsAutoSchedule(server.url("/a/").toString()) },
            { client.probeCalendarCollection(server.url("/a/").toString()) },
        )
        for (call in calls) {
            repeat(6) { server.enqueue(HostileDavServer.redirect(307, "/a")) }
            val result = call()
            assertTrue("$result", result is CalDavResult.Error)
            assertEquals(CalDavResult.CODE_TRANSPORT_REFUSED, (result as CalDavResult.Error).code)
        }
    }

    // ---- well-known discovery never ends on plain http for an https account ----

    private fun https() = HostileDavServer.httpsServer().also { servers += it }

    /** Like [clientFor], trusting the test certificate the way a self-signed setup does. */
    private fun trustingClientFor(accountUrl: String): CalDavClient =
        OkHttpCalDavClientFactory().createClient(
            Credentials(username = "testuser", password = "testpass", serverUrl = accountUrl, trustInsecure = true),
            DefaultQuirks(accountUrl)
        )

    @Test
    fun `an http account whose well-known redirects to https keeps the https endpoint`() = runTest {
        val plain = http()
        val secure = https()
        val endpoint = secure.url("/remote.php/dav/").newBuilder().host("localhost").build()
        plain.enqueue(HostileDavServer.redirect(301, endpoint.toString()))
        secure.enqueue(HostileDavServer.multistatus(""))

        val result = trustingClientFor(plain.base()).discoverWellKnown(plain.base())

        assertEquals(endpoint.toString(), result.getOrNull())
    }

    @Test
    fun `an https well-known redirected to http on the same host is followed over https`() = runTest {
        val secure = https()
        val plain = http()
        secure.enqueue(HostileDavServer.redirect(301, "http://localhost/dav.php/"))
        secure.enqueue(HostileDavServer.multistatus(""))
        val base = secure.url("/").newBuilder().host("localhost").build().toString()

        val result = trustingClientFor(base).discoverWellKnown(base)

        assertEquals(secure.url("/dav.php/").newBuilder().host("localhost").build().toString(), result.getOrNull())
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun `an https well-known redirected to http elsewhere is a refused connection`() = runTest {
        val secure = https()
        val plain = http()
        secure.enqueue(HostileDavServer.redirect(302, plain.url("/dav/").newBuilder().host("127.0.0.1").build().toString()))
        val base = secure.url("/").newBuilder().host("localhost").build().toString()

        val result = trustingClientFor(base).discoverWellKnown(base)

        assertEquals(CalDavResult.CODE_TRANSPORT_REFUSED, (result as CalDavResult.Error).code)
        assertEquals(0, plain.requestCount)
    }
}
