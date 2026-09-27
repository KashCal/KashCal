package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * A server may store a resource named `<uuid>@host.ics` with its '@' written as
 * `%40` and then not answer the other spelling (Stalwart answers GET and DELETE
 * on the literal '@' with 404). Both spellings name the same resource (RFC 3986
 * section 3.3: '@' is legal in a path segment), so when a request for a stored
 * resource URL gets 404 the client asks once more with the resource name's '@'
 * written the other way. Only the resource name changes: a collection path can
 * itself hold a `%40` (`/dav/cal/user%40host/`), which must be sent as it is.
 */
class OkHttpCalDavClientAtSignFallbackTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    private val collection = "/dav/cal/user%40host/default"
    private val literalPath = "$collection/abc@kashcal.onekash.org.ics"
    private val encodedPath = "$collection/abc%40kashcal.onekash.org.ics"

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:abc@kashcal.onekash.org\r\n" +
        "DTSTAMP:20260101T000000Z\r\nDTSTART:20260101T100000Z\r\nSUMMARY:Probe\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

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
        val serverUrl = server.url("/").toString()
        client = OkHttpCalDavClientFactory()
            .createClient(Credentials(username = "u", password = "p", serverUrl = serverUrl), DefaultQuirks(serverUrl)) as OkHttpCalDavClient
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun url(path: String) = server.url("/").toString().trimEnd('/') + path

    /** Answers [ok] for requests to [storedPath], 404 for anything else. */
    private fun serveOnly(storedPath: String, ok: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == storedPath) ok(request) else MockResponse().setResponseCode(404)
        }
    }

    private fun recorded(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    // ---- the server stores %40, KashCal holds the literal '@' ----

    @Test
    fun `a delete reaches a resource the server stores with a percent-encoded at sign`() = runTest {
        serveOnly(encodedPath) { MockResponse().setResponseCode(204) }

        val result = client.deleteEvent(url(literalPath), "e1")

        assertTrue(result.isSuccess())
        val requests = recorded()
        assertEquals(listOf(literalPath, encodedPath), requests.map { it.path })
        assertEquals(listOf("DELETE", "DELETE"), requests.map { it.method })
        assertEquals("the retry keeps the precondition", "\"e1\"", requests[1].getHeader("If-Match"))
    }

    @Test
    fun `a delete where neither spelling exists is still already gone`() = runTest {
        serveOnly("/nowhere") { MockResponse().setResponseCode(204) }

        val result = client.deleteEvent(url(literalPath), "e1")

        assertTrue(result.isSuccess())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a read back finds a resource the server stores with a percent-encoded at sign`() = runTest {
        serveOnly(encodedPath) { MockResponse().setResponseCode(200).setHeader("ETag", "\"e2\"").setBody(ics) }

        val result = client.fetchEvent(url(literalPath))

        assertTrue("got $result", result.isSuccess())
        assertEquals("e2", result.getOrNull()!!.etag)
        assertTrue(result.getOrNull()!!.icalData.contains("SUMMARY:Probe"))
    }

    @Test
    fun `a read back where neither spelling exists is not found`() = runTest {
        serveOnly("/nowhere") { MockResponse().setResponseCode(200) }

        assertTrue(client.fetchEvent(url(literalPath)) is CalDavResult.Error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an etag lookup finds a resource the server stores with a percent-encoded at sign`() = runTest {
        serveOnly(encodedPath) {
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>$encodedPath</d:href>""" +
                    """<d:propstat><d:prop><d:getetag>"e3"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
            )
        }

        val result = client.fetchEtag(url(literalPath))

        assertEquals("e3", result.getOrNull())
        assertEquals(listOf("PROPFIND", "PROPFIND"), recorded().map { it.method })
    }

    @Test
    fun `an update reaches a resource the server stores with a percent-encoded at sign`() = runTest {
        serveOnly(encodedPath) { MockResponse().setResponseCode(204).setHeader("ETag", "\"e4\"") }

        val result = client.updateEvent(url(literalPath), ics, "e1")

        assertEquals("e4", result.getOrNull())
        val retry = recorded()[1]
        assertEquals(encodedPath, retry.path)
        assertEquals("PUT", retry.method)
        assertEquals("\"e1\"", retry.getHeader("If-Match"))
        assertTrue("the retry carries the body", retry.body.readUtf8().contains("SUMMARY:Probe"))
    }

    @Test
    fun `an update with no etag header reads the new etag where the resource was found`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path != encodedPath -> MockResponse().setResponseCode(404)
                request.method == "PUT" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$encodedPath</d:href><d:propstat><d:prop>""" +
                        """<d:getetag>"e5"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                )
            }
        }

        assertEquals("e5", client.updateEvent(url(literalPath), ics, "e1").getOrNull())
        val propfinds = recorded().filter { it.method == "PROPFIND" }.map { it.path }
        assertEquals("the etag is read straight from where the update landed", listOf(encodedPath), propfinds)
    }

    @Test
    fun `a move of a resource the server stores with a percent-encoded at sign names the destination the same way`() = runTest {
        // A server that only answers the %40 source (Stalwart) leaves a literal-'@' destination
        // unwritable, so the retried MOVE spells the destination's '@' as %40 too.
        serveOnly(encodedPath) { MockResponse().setResponseCode(201).setHeader("ETag", "\"e6\"") }

        val result = client.moveEvent(url(literalPath), url("/dav/cal/user%40host/other/"), "abc@kashcal.onekash.org")

        assertTrue("got $result", result.isSuccess())
        val (first, retry) = recorded()
        assertEquals("MOVE", retry.method)
        assertEquals(encodedPath, retry.path)
        assertTrue(first.getHeader("Destination")!!.endsWith("/other/abc@kashcal.onekash.org.ics"))
        assertTrue(retry.getHeader("Destination")!!.endsWith("/dav/cal/user%40host/other/abc%40kashcal.onekash.org.ics"))
        assertEquals("F", retry.getHeader("Overwrite"))
        assertEquals("the stored URL is the destination the server took", retry.getHeader("Destination"), result.getOrNull()!!.first)
    }

    @Test
    fun `a move the server takes on the literal spelling keeps the literal destination`() = runTest {
        // A server that keeps a Destination's "%40" verbatim (Radicale) must get the literal '@'.
        serveOnly(literalPath) { MockResponse().setResponseCode(201).setHeader("ETag", "\"e9\"") }

        val result = client.moveEvent(url(literalPath), url("/dav/cal/user%40host/other/"), "abc@kashcal.onekash.org")

        val destination = recorded().single().getHeader("Destination")!!
        assertTrue(destination, destination.endsWith("/dav/cal/user%40host/other/abc@kashcal.onekash.org.ics"))
        assertEquals("the stored URL is the destination as sent", destination, result.getOrNull()!!.first)
    }

    @Test
    fun `a move of a stored percent-encoded URL on a server that answers both spellings keeps the literal destination`() = runTest {
        // A pull can store the server's %40 href. A server that answers either spelling
        // (Radicale) keeps a Destination's "%40" verbatim, so the destination must stay literal.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == literalPath || request.path == encodedPath) MockResponse().setResponseCode(201).setHeader("ETag", "\"e10\"")
                else MockResponse().setResponseCode(404)
        }

        val result = client.moveEvent(url(encodedPath), url("/dav/cal/user%40host/other/"), "abc@kashcal.onekash.org")

        val request = recorded().single()
        assertEquals("the literal spelling is asked first", literalPath, request.path)
        assertTrue(request.getHeader("Destination")!!.endsWith("/other/abc@kashcal.onekash.org.ics"))
        assertEquals(request.getHeader("Destination"), result.getOrNull()!!.first)
    }

    @Test
    fun `a move of a stored percent-encoded URL on a server that answers only that spelling names the destination the same way`() = runTest {
        serveOnly(encodedPath) { MockResponse().setResponseCode(201).setHeader("ETag", "\"e11\"") }

        val result = client.moveEvent(url(encodedPath), url("/dav/cal/user%40host/other/"), "abc@kashcal.onekash.org")

        val (first, retry) = recorded()
        assertEquals("the literal spelling is asked first", literalPath, first.path)
        assertEquals(encodedPath, retry.path)
        assertTrue(retry.getHeader("Destination")!!.endsWith("/dav/cal/user%40host/other/abc%40kashcal.onekash.org.ics"))
        assertEquals(retry.getHeader("Destination"), result.getOrNull()!!.first)
    }

    @Test
    fun `a move of another client's event keeps that client's resource name`() = runTest {
        // Another client named the resource without the UID (UID with '@', name without).
        // Naming the destination after the UID would give Stalwart a literal-'@' name it
        // then refuses every If-Match PUT on, so the source's own name is kept.
        val foreignPath = "/dav/cal/user%40host/default/3F2504E0-4F89-11D3.ics"
        serveOnly(foreignPath) { MockResponse().setResponseCode(201).setHeader("ETag", "\"f1\"") }

        val result = client.moveEvent(url(foreignPath), url("/dav/cal/user%40host/other/"), "event-7@calendar.example.org")

        val destination = recorded().single().getHeader("Destination")!!
        assertTrue(destination, destination.endsWith("/dav/cal/user%40host/other/3F2504E0-4F89-11D3.ics"))
        assertFalse("the UID is not used as the name", destination.contains("event-7"))
        assertEquals(destination, result.getOrNull()!!.first)
    }

    @Test
    fun `a percent-encoded source name is never encoded a second time in the destination`() = runTest {
        serveOnly(encodedPath) { MockResponse().setResponseCode(201).setHeader("ETag", "\"e12\"") }

        val result = client.moveEvent(url(encodedPath), url("/dav/cal/user%40host/other/"), "abc@kashcal.onekash.org")

        val destination = result.getOrNull()!!.first
        assertFalse(destination, destination.contains("%2540"))
        assertTrue(destination, destination.endsWith("/other/abc%40kashcal.onekash.org.ics"))
    }

    @Test
    fun `a name with both spellings of the at sign is retried all percent-encoded`() {
        assertEquals("https://h/c/a%40b%40c.ics", atSignAlternate("https://h/c/a@b%40c.ics"))
        assertEquals("https://h/c/a@b.ics", atSignAlternate("https://h/c/a%40b.ics"))
        assertEquals(null, atSignAlternate("https://h/c%40x/plain.ics"))
    }

    // ---- the reverse: the server stores the literal '@', KashCal holds %40 ----

    @Test
    fun `a stored percent-encoded name reaches a resource the server stores with a literal at sign`() = runTest {
        serveOnly(literalPath) { MockResponse().setResponseCode(204) }

        assertTrue(client.deleteEvent(url(encodedPath), "e1").isSuccess())
        assertEquals(listOf(encodedPath, literalPath), recorded().map { it.path })
    }

    @Test
    fun `a stored percent-encoded name reads back and updates a resource stored with a literal at sign`() = runTest {
        serveOnly(literalPath) { r ->
            if (r.method == "GET") MockResponse().setResponseCode(200).setHeader("ETag", "\"e7\"").setBody(ics)
            else MockResponse().setResponseCode(204).setHeader("ETag", "\"e8\"")
        }

        assertEquals("e7", client.fetchEvent(url(encodedPath)).getOrNull()!!.etag)
        assertEquals("e8", client.updateEvent(url(encodedPath), ics, "e7").getOrNull())
    }

    // ---- what the retry keeps ----

    @Test
    fun `a delete with an etag that no longer matches is a conflict, not a success`() = runTest {
        // The retry keeps If-Match, so a stale or empty etag meets the real resource and gets 412.
        serveOnly(encodedPath) { r ->
            if (r.getHeader("If-Match") == "\"e1\"") MockResponse().setResponseCode(204) else MockResponse().setResponseCode(412)
        }

        assertTrue(client.deleteEvent(url(literalPath), "") is CalDavResult.Error)
        assertTrue(client.deleteEvent(url(literalPath), "e1").isSuccess())
    }

    @Test
    fun `a network failure on the retry is reported as a network error`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == literalPath) MockResponse().setResponseCode(404)
                else MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }

        val result = client.deleteEvent(url(literalPath), "e1")

        assertTrue("got $result", result is CalDavResult.Error && result.isRetryable)
    }

    // ---- when nothing is retried ----

    @Test
    fun `a name without an at sign is not retried`() = runTest {
        serveOnly("/nowhere") { MockResponse().setResponseCode(204) }

        client.deleteEvent(url("$collection/plain-uid.ics"), "e1")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `only the resource name changes, never the collection path`() = runTest {
        serveOnly("/nowhere") { MockResponse().setResponseCode(204) }

        client.deleteEvent(url("$collection/plain-uid.ics"), "e1")
        client.deleteEvent(url(literalPath), "e1")

        val paths = recorded().map { it.path!! }
        assertTrue("the collection's %40 is kept: $paths", paths.all { it.startsWith("$collection/") })
    }

    @Test
    fun `other errors are not retried`() = runTest {
        for (code in listOf(401, 403, 412, 500)) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code)
            }
            val before = server.requestCount
            client.deleteEvent(url(literalPath), "e1")
            client.fetchEvent(url(literalPath))
            assertEquals("HTTP $code: one request per call", before + 2, server.requestCount)
        }
    }

    @Test
    fun `a refused move is not retried`() = runTest {
        for (code in listOf(403, 405, 412)) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code)
            }
            val before = server.requestCount
            client.moveEvent(url(literalPath), url("/dav/cal/user%40host/other/"), "abc@kashcal.onekash.org")
            assertEquals("HTTP $code: one request", before + 1, server.requestCount)
        }
    }
}
