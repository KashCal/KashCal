package org.onekash.kashcal.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests [DavTransportGuard] on the wire through [HostileDavServer]: redirects are re-sent
 * with the same method, body and headers (a 303 only for a read), nothing goes over plain
 * http for an https account, credentials follow redirects, loops and one-shot bodies are
 * refused, a redirect without a Location is handed back, the caller sees the redirect chain,
 * and a refusal message names only masked hosts. Ends with [DavRedirectPolicy] and
 * [DavTransportGuard.allowsCleartext] on their own.
 */
class DavTransportGuardTest {

    private val servers = mutableListOf<MockWebServer>()
    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val ical = "text/calendar; charset=utf-8".toMediaType()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun https() = HostileDavServer.httpsServer().also { servers += it }
    private fun http() = HostileDavServer.httpServer().also { servers += it }

    /**
     * [path] on [server], always addressed as "localhost" (a different spelling is a different
     * host).
     */
    private fun MockWebServer.at(path: String): HttpUrl = url(path).newBuilder().host("localhost").build()

    private fun OkHttpClient.send(request: Request): Response = newCall(request).execute()

    private fun MockWebServer.next(): RecordedRequest = takeRequest(5, TimeUnit.SECONDS)!!

    private fun put(url: HttpUrl, body: String = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n") =
        Request.Builder().url(url).put(body.toRequestBody(ical)).header("If-Match", "\"etag-1\"").build()

    // ---- same method, same body, same headers ----

    @Test
    fun `a redirected PUT is sent again as a PUT with the same body and If-Match`() {
        for (code in listOf(301, 302, 307, 308)) {
            val server = http()
            server.enqueue(HostileDavServer.redirect(code, "/moved/a.ics"))
            server.enqueue(MockResponse().setResponseCode(204))

            HostileDavServer.davClient(allowCleartext = true).send(put(server.at("/cal/a.ics"), "BODY-$code")).use {
                assertEquals(204, it.code)
                assertEquals("/moved/a.ics", it.request.url.encodedPath)
            }
            val first = server.next()
            val second = server.next()
            assertEquals("PUT", first.method)
            assertEquals("$code: re-sent as PUT, never GET", "PUT", second.method)
            assertEquals("BODY-$code", second.body.readUtf8())
            assertEquals("\"etag-1\"", second.getHeader("If-Match"))
        }
    }

    @Test
    fun `a redirected DELETE REPORT PROPFIND MOVE and POST keep their method and headers`() {
        val cases = listOf(
            Request.Builder().delete().header("If-Match", "\"e\""),
            Request.Builder().method("REPORT", "<r/>".toRequestBody(xml)).header("Depth", "1"),
            Request.Builder().method("PROPFIND", "<p/>".toRequestBody(xml)).header("Depth", "0"),
            Request.Builder().method("MOVE", null).header("Destination", "http://localhost/other/a.ics").header("Overwrite", "F"),
            Request.Builder().post("BEGIN:VCALENDAR".toRequestBody(ical)).header("Originator", "mailto:a@example.test"),
        )
        for (builder in cases) {
            val server = http()
            server.enqueue(HostileDavServer.redirect(301, "/elsewhere/"))
            server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
            val original = builder.url(server.at("/cal/")).build()

            HostileDavServer.davClient(allowCleartext = true).send(original).use { assertEquals(207, it.code) }

            server.next()
            val resent = server.next()
            assertEquals(original.method, resent.method)
            assertEquals("/elsewhere/", resent.path)
            for (name in original.headers.names()) {
                assertEquals("${original.method} $name", original.header(name), resent.getHeader(name))
            }
            val body = original.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            if (body != null) assertEquals(body, resent.body.readUtf8())
        }
    }

    @Test
    fun `GET HEAD and OPTIONS are followed as themselves`() {
        for (method in listOf("GET", "HEAD", "OPTIONS")) {
            val server = http()
            server.enqueue(HostileDavServer.redirect(308, "/next"))
            server.enqueue(MockResponse().setResponseCode(200))
            HostileDavServer.davClient(allowCleartext = true)
                .send(Request.Builder().url(server.at("/start")).method(method, null).build())
                .use { assertEquals(200, it.code) }
            server.next()
            assertEquals(method, server.next().method)
        }
    }

    @Test
    fun `303 is followed for PROPFIND and GET but handed back for a write`() {
        val server = http()
        server.enqueue(HostileDavServer.redirect(303, "/dav/"))
        server.enqueue(MockResponse().setResponseCode(207))
        HostileDavServer.davClient(allowCleartext = true)
            .send(Request.Builder().url(server.at("/.well-known/caldav")).method("PROPFIND", "<p/>".toRequestBody(xml)).build())
            .use { assertEquals(207, it.code) }
        server.next()
        assertEquals("PROPFIND", server.next().method)

        server.enqueue(HostileDavServer.redirect(303, "/done"))
        HostileDavServer.davClient(allowCleartext = true).send(put(server.at("/cal/a.ics"))).use {
            assertEquals("a write answered 303 is not re-sent", 303, it.code)
        }
        server.next()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a redirect without a Location is handed back as it is`() {
        val server = http()
        server.enqueue(MockResponse().setResponseCode(302))
        HostileDavServer.davClient(allowCleartext = true).send(put(server.at("/cal/a.ics"))).use {
            assertEquals(302, it.code)
        }
        assertEquals(1, server.requestCount)
    }

    // ---- loops ----

    @Test
    fun `a redirect loop stops after five redirects`() {
        val server = http()
        repeat(10) { i -> server.enqueue(HostileDavServer.redirect(307, if (i % 2 == 0) "/b" else "/a")) }
        try {
            HostileDavServer.davClient(allowCleartext = true).send(put(server.at("/a"))).close()
            fail("expected the loop to be refused")
        } catch (e: DavTransportRefusedException) {
            assertEquals(DavTransportRefusal.TOO_MANY_REDIRECTS, e.reason)
        }
        assertEquals("the first request plus five redirects", 6, server.requestCount)
    }

    // ---- https and http ----

    @Test
    fun `https redirected to http on the same host is sent over https instead`() {
        val secure = https()
        val plain = http()
        // A TLS-terminating proxy writes http:// (port 80) into its Location header.
        secure.enqueue(HostileDavServer.redirect(301, "http://localhost/dav.php/"))
        secure.enqueue(MockResponse().setResponseCode(207))

        HostileDavServer.davClient(allowCleartext = false)
            .send(Request.Builder().url(secure.at("/.well-known/caldav")).method("PROPFIND", "<p/>".toRequestBody(xml)).build())
            .use {
                assertEquals(207, it.code)
                assertTrue(it.request.url.isHttps)
                assertEquals(secure.port, it.request.url.port)
            }
        secure.next()
        assertEquals("/dav.php/", secure.next().path)
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun `https redirected to http on another host is refused and nothing reaches it`() {
        val secure = https()
        val plain = http()
        val elsewhere = plain.url("/steal/").newBuilder().host("127.0.0.1").build()
        secure.enqueue(HostileDavServer.redirect(302, elsewhere.toString()))
        try {
            HostileDavServer.davClient(allowCleartext = false).send(put(secure.at("/cal/a.ics"))).close()
            fail("expected a refusal")
        } catch (e: DavTransportRefusedException) {
            assertEquals(DavTransportRefusal.INSECURE_REDIRECT, e.reason)
            assertFalse("no path in the message", e.message!!.contains("steal"))
        }
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun `https redirected to http on the same host but another port is refused`() {
        val secure = https()
        val plain = http()
        secure.enqueue(HostileDavServer.redirect(307, plain.at("/cal/").toString()))
        try {
            HostileDavServer.davClient(allowCleartext = false).send(put(secure.at("/cal/a.ics"))).close()
            fail("expected a refusal")
        } catch (e: DavTransportRefusedException) {
            assertEquals(DavTransportRefusal.INSECURE_REDIRECT, e.reason)
        }
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun `an https account never sends a plain http request and no password leaves`() {
        val plain = http()
        plain.enqueue(MockResponse().setResponseCode(204))
        try {
            HostileDavServer.davClient(allowCleartext = false).send(put(plain.at("/cal/a.ics"))).close()
            fail("expected a refusal")
        } catch (e: DavTransportRefusedException) {
            assertEquals(DavTransportRefusal.CLEARTEXT_REFUSED, e.reason)
        }
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun `an http account follows http redirects across ports with its credentials`() {
        val first = http()
        val second = http()
        // Baikal's well-known drops the port the account uses.
        first.enqueue(HostileDavServer.redirect(302, second.at("/dav.php").toString()))
        second.enqueue(MockResponse().setResponseCode(207))
        HostileDavServer.davClient(allowCleartext = true)
            .send(Request.Builder().url(first.at("/.well-known/caldav")).method("PROPFIND", "<p/>".toRequestBody(xml)).build())
            .use { assertEquals(207, it.code) }
        first.next()
        assertNotNull(second.next().getHeader("Authorization"))
    }

    @Test
    fun `an http account follows a redirect up to https`() {
        val plain = http()
        val secure = https()
        plain.enqueue(HostileDavServer.redirect(308, secure.at("/dav/").toString()))
        secure.enqueue(MockResponse().setResponseCode(207))
        HostileDavServer.davClient(allowCleartext = true)
            .send(Request.Builder().url(plain.at("/")).method("PROPFIND", "<p/>".toRequestBody(xml)).build())
            .use { assertTrue(it.request.url.isHttps) }
    }

    @Test
    fun `an https redirect to another host is followed with the account's credentials`() {
        val first = https()
        val second = https()
        // "127.0.0.1" is another host to the client (the certificate names both). OkHttp's own
        // follow-up would strip Authorization on a host change; the guard re-sends through the
        // chain, so the auth interceptor adds it again (no domain scoping for https redirects).
        val otherHost = second.url("/partition/cal/").newBuilder().host("127.0.0.1").build()
        first.enqueue(HostileDavServer.redirect(307, otherHost.toString()))
        second.enqueue(MockResponse().setResponseCode(207))
        HostileDavServer.davClient(allowCleartext = false)
            .send(Request.Builder().url(first.at("/cal/")).method("REPORT", "<r/>".toRequestBody(xml)).build())
            .use { assertEquals(207, it.code) }
        assertNotNull(first.next().getHeader("Authorization"))
        assertNotNull(second.next().getHeader("Authorization"))
    }

    // ---- what the caller sees ----

    @Test
    fun `the returned response keeps the redirect chain`() {
        val server = http()
        server.enqueue(HostileDavServer.redirect(301, "/b"))
        server.enqueue(HostileDavServer.redirect(302, "/c"))
        server.enqueue(MockResponse().setResponseCode(207).setBody("<x/>"))
        HostileDavServer.davClient(allowCleartext = true)
            .send(Request.Builder().url(server.at("/a")).method("PROPFIND", "<p/>".toRequestBody(xml)).build())
            .use { response ->
                assertEquals("<x/>", response.body.string())
                val chain = generateSequence(response.priorResponse) { it.priorResponse }.toList()
                assertEquals(listOf(302, 301), chain.map { it.code })
                assertTrue(chain.all { it.isRedirect })
                assertEquals("/c", response.request.url.encodedPath)
            }
    }

    @Test
    fun `an unredirected response has no prior response`() {
        val server = http()
        server.enqueue(MockResponse().setResponseCode(204))
        HostileDavServer.davClient(allowCleartext = true).send(put(server.at("/cal/a.ics"))).use {
            assertNull(it.priorResponse)
        }
    }

    @Test
    fun `a body that can only be written once is not re-sent`() {
        val server = http()
        server.enqueue(HostileDavServer.redirect(307, "/b"))
        val oneShot = object : RequestBody() {
            override fun contentType() = ical
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("once") }
        }
        try {
            HostileDavServer.davClient(allowCleartext = true)
                .send(Request.Builder().url(server.at("/a")).put(oneShot).build()).close()
            fail("expected a refusal")
        } catch (e: DavTransportRefusedException) {
            assertEquals(DavTransportRefusal.UNREPLAYABLE_BODY, e.reason)
        }
    }

    @Test
    fun `the refusal message names masked hosts only`() {
        val e = DavTransportRefusedException(DavTransportRefusal.INSECURE_REDIRECT, "caldav.example.com", "evil.example.org")
        assertEquals("Connection refused by KashCal (INSECURE_REDIRECT): cal***.com -> evi***.org", e.message)
    }

    // ---- the policy on its own ----

    @Test
    fun `policy decisions`() {
        val https = "https://dav.example.com/cal/".toHttpUrl()
        fun next(code: Int, location: String?, method: String = "PUT", cleartext: Boolean = false) =
            DavRedirectPolicy.next(https, code, location, method, cleartext)

        assertEquals(DavRedirectPolicy.Decision.Stop, next(200, null))
        assertEquals(DavRedirectPolicy.Decision.Stop, next(304, "/x"))
        assertEquals(DavRedirectPolicy.Decision.Stop, next(303, "/x", "DELETE"))
        assertEquals(
            DavRedirectPolicy.Decision.Follow("https://dav.example.com/x".toHttpUrl()),
            next(303, "/x", "PROPFIND")
        )
        assertEquals(
            DavRedirectPolicy.Decision.Follow("https://dav.example.com/dav.php/".toHttpUrl()),
            next(301, "http://dav.example.com/dav.php/")
        )
        assertTrue(next(301, "http://dav.example.com:8080/") is DavRedirectPolicy.Decision.Refuse)
        for (method in listOf("GET", "HEAD")) {
            assertEquals(method, DavRedirectPolicy.Decision.Follow("https://dav.example.com/x".toHttpUrl()), next(303, "/x", method))
        }
        // Same host on the port that answered over https: the proxy wrote the scheme wrong.
        assertEquals(
            DavRedirectPolicy.Decision.Follow("https://dav.example.com:8443/dav/".toHttpUrl()),
            DavRedirectPolicy.next("https://dav.example.com:8443/".toHttpUrl(), 302, "http://dav.example.com:8443/dav/", "PROPFIND", false)
        )
        assertTrue(next(301, "http://other.example.com/") is DavRedirectPolicy.Decision.Refuse)
        assertEquals(
            DavRedirectPolicy.Decision.Follow("https://p01-dav.example.com/x".toHttpUrl()),
            next(307, "https://p01-dav.example.com/x")
        )
        assertTrue(DavTransportGuard.allowsCleartext("http://nas.local:5232/"))
        assertTrue(DavTransportGuard.allowsCleartext(" HTTP://nas.local/"))
        assertFalse(DavTransportGuard.allowsCleartext("https://caldav.icloud.com"))
        assertFalse(DavTransportGuard.allowsCleartext(null))
    }
}
