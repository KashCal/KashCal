package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.network.DavRedirectPolicy
import org.onekash.kashcal.network.DavTransportGuard
import org.onekash.kashcal.network.DavTransportRefusal
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.DigestAuthenticator
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Records what every configured CalDAV server does on the wire, hop by hop, and checks that the
 * app's redirect and reply handling holds for each.
 *
 * The recording client never lets OkHttp follow a redirect: it follows each one itself,
 * re-sending the same method, body and headers, and prints the status, method and any change of
 * scheme, host or port. It never follows a redirect from https to http, so no password is sent in
 * cleartext.
 *
 * It walks the requests a real sync makes: the discovery `.well-known` URL, the principal, the
 * calendar home, the calendar listing, a calendar-query REPORT, and create, read, update and
 * delete of one event named after a KashCal-style UID (with an '@').
 *
 * Asserted, because the client relies on them:
 * - no server redirects from https to http;
 * - each principal, home-set, listing and REPORT reply that succeeds is a 207 whose body is XML
 *   with a multistatus root element (RFC 4918 §13), so treating anything else as an error can't
 *   break a real server;
 * - no hop is one [DavRedirectPolicy] refuses (a downgrade to http elsewhere, plain http on an
 *   https account), and no chain reaches [MAX_HOPS] still redirecting (a likely loop).
 * Recorded only: whether a write (PUT, DELETE) or a REPORT is redirected at all, and whether the
 * target then accepts the same method.
 *
 * Each run creates one event with a new UID and deletes it; teardown deletes it again in both
 * spellings of its '@'. Addresses are redacted in the printed census.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerTransportBaselineTest*'
 */
@RunWith(Parameterized::class)
class MultiServerTransportBaselineTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        private const val MAX_HOPS = 5
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ICAL = "text/calendar; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val MULTISTATUS_ROOT = Regex("""<([A-Za-z0-9_.-]+:)?multistatus[\s>/]""")
    }

    /**
     * One request as sent and its response: the code (-1 when the network refused it) and, for a
     * redirect, the target.
     */
    private data class Hop(val method: String, val code: Int, val from: HttpUrl, val to: HttpUrl?)

    private data class Trace(val step: String, val hops: List<Hop>, val finalCode: Int, val body: String, val contentType: String?) {
        val redirected get() = hops.size > 1
        val downgrade get() = hops.any { it.to != null && it.from.isHttps && !it.to.isHttps }
    }

    private var appClient: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var http: OkHttpClient
    private val traces = mutableListOf<Trace>()
    private val cleanup = mutableListOf<String>()

    @Before
    fun setUp() {
        CalDavTestServerLoader.createClient(config)?.let { appClient = it.first; creds = it.second }
        val c = creds ?: return
        http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .authenticator(DigestAuthenticator(c.username, c.password, allowCleartext = true))
            .addNetworkInterceptor { chain ->
                val r = chain.request()
                val out = if (r.header("Authorization") == null) {
                    r.newBuilder().header("Authorization", Credentials.basic(c.username, c.password, Charsets.UTF_8)).build()
                } else {
                    r
                }
                chain.proceed(out)
            }
            .build()
    }

    @After
    fun tearDown() {
        // Printed even when a step failed or the case was skipped: the census is the point.
        printCensus()
        if (!::http.isInitialized) return
        for (url in cleanup) {
            for (u in listOf(url, url.replace("@", "%40"))) {
                runCatching { http.newCall(Request.Builder().url(u).delete().build()).execute().close() }
            }
        }
    }

    @Test
    fun `every server keeps its redirects on https and answers DAV requests with an XML multistatus`() = runBlocking<Unit> {
        val c = creds
        assumeTrue("${config.name} credentials not available", c != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(c!!.davEndpoint))

        val endpoint = c.davEndpoint.toHttpUrl()
        val origin = endpoint.newBuilder().encodedPath("/").query(null).build()

        // Discovery bootstrap (RFC 6764 §5): 401, 404 or 405 are fine; only redirects count.
        val wellKnown = send("well-known", "PROPFIND", origin.resolve("/.well-known/caldav")!!, propfind("<d:current-user-principal/>"), depth = "0")

        // The configured endpoint first; where it names no principal, the URL well-known discovery
        // ended at. A server root may be a web page (Stalwart's admin UI answers 200 text/html):
        // recorded, not asserted.
        var principalTrace = send("principal-at-endpoint", "PROPFIND", endpoint, propfind("<d:current-user-principal/>"), depth = "0")
        if (hrefIn(principalTrace, "current-user-principal") == null && wellKnown.finalCode != -1) {
            principalTrace = send("principal", "PROPFIND", finalUrl(wellKnown), propfind("<d:current-user-principal/>"), depth = "0")
        }
        val principal = hrefIn(principalTrace, "current-user-principal")?.let { finalUrl(principalTrace).resolve(it) }
        assumeTrue("${config.name}: no current-user-principal in ${principalTrace.finalCode}", principal != null)

        val homeTrace = send("home-set", "PROPFIND", principal!!, propfind("<c:calendar-home-set/>"), depth = "0")
        val home = hrefIn(homeTrace, "calendar-home-set")?.let { finalUrl(homeTrace).resolve(it) }
        assumeTrue("${config.name}: no calendar-home-set in ${homeTrace.finalCode}", home != null)

        send("listing", "PROPFIND", home!!, propfind("<d:resourcetype/><d:displayname/>"), depth = "1")

        // A writable VEVENT calendar that isn't the inbox or outbox.
        val calendar = appClient!!.listCalendars(home.toString()).getOrNull().orEmpty()
            .firstOrNull { !it.isReadOnly && !it.url.contains("inbox") && !it.url.contains("outbox") &&
                (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
        assumeTrue("${config.name}: no writable calendar", calendar != null)
        val calendarUrl = calendar!!.url.toHttpUrl()

        send("report", "REPORT", calendarUrl, calendarQuery(), depth = "1")

        // One event, named after a KashCal-style UID, created, read, updated and deleted.
        val uid = "transport-baseline-${UUID.randomUUID()}@kashcal.onekash.org"
        val eventUrl = "${calendar.url.trimEnd('/')}/$uid.ics"
        cleanup += eventUrl
        send("put-create", "PUT", eventUrl.toHttpUrl(), ics(uid, "Transport baseline"), headers = mapOf("If-None-Match" to "*"))
        send("get", "GET", eventUrl.toHttpUrl(), null)
        val etag = headEtag(eventUrl)
        send("put-update", "PUT", eventUrl.toHttpUrl(), ics(uid, "Transport baseline edited"),
            headers = etag?.let { mapOf("If-Match" to it) } ?: emptyMap())
        send("delete", "DELETE", eventUrl.toHttpUrl(), null, headers = headEtag(eventUrl)?.let { mapOf("If-Match" to it) } ?: emptyMap())

        val downgrades = traces.filter { it.downgrade }.map { it.step }
        assertTrue("${config.name}: redirected from https to http at $downgrades", downgrades.isEmpty())

        // The client's own redirect policy, applied to every hop recorded here with this
        // account's scheme: none of them may be one the client refuses to follow.
        val allowCleartext = DavTransportGuard.allowsCleartext(c.davEndpoint)
        val refused = traces.flatMap { t ->
            t.hops.mapNotNull { h ->
                val location = h.to?.toString()
                val decision = if (!h.from.isHttps && !allowCleartext) {
                    DavRedirectPolicy.Decision.Refuse(DavTransportRefusal.CLEARTEXT_REFUSED, h.from)
                } else {
                    DavRedirectPolicy.next(h.from, h.code, location, h.method, allowCleartext)
                }
                (decision as? DavRedirectPolicy.Decision.Refuse)?.let { "${t.step}: ${it.reason}" }
            } + listOfNotNull(
                // The recorder stops after MAX_HOPS requests; still being redirected then
                // means a chain at least as long as the client's limit, a likely loop.
                "${t.step}: ${DavTransportRefusal.TOO_MANY_REDIRECTS}".takeIf { t.hops.size >= MAX_HOPS && t.hops.last().to != null }
            )
        }
        assertTrue("${config.name}: the client would refuse $refused", refused.isEmpty())

        // The replies discovery and sync use: the principal that was found, home, listing, REPORT.
        val davSteps = traces.filter { it.step in setOf("home-set", "listing", "report") } + principalTrace
        for (t in davSteps) {
            if (t.finalCode !in 200..299) continue
            assertTrue("${config.name} ${t.step}: expected 207, got ${t.finalCode}", t.finalCode == 207)
            val xmlish = t.contentType?.contains("xml", ignoreCase = true) == true || t.body.trimStart().startsWith("<")
            assertTrue("${config.name} ${t.step}: 207 body isn't XML (content-type ${t.contentType})", xmlish)
            assertTrue("${config.name} ${t.step}: 207 body has no multistatus root", MULTISTATUS_ROOT.containsMatchIn(t.body))
        }
    }

    // ---- the recording client ----

    private fun send(step: String, method: String, url: HttpUrl, body: String?, depth: String? = null, headers: Map<String, String> = emptyMap()): Trace {
        val hops = mutableListOf<Hop>()
        var current = url
        var finalCode = -1
        var finalBody = ""
        var contentType: String? = null
        for (i in 0 until MAX_HOPS) {
            val builder = Request.Builder().url(current)
                .method(method, body?.toRequestBody(if (method == "PUT") ICAL else XML))
            depth?.let { builder.header("Depth", it) }
            headers.forEach { (k, v) -> builder.header(k, v) }
            val call = try {
                http.newCall(builder.build()).execute()
            } catch (e: java.io.IOException) {
                // A refused hop is a finding too, e.g. a redirect to a port that speaks TLS.
                hops += Hop(method, -1, current, null)
                return Trace(step, hops, -1, "${e.javaClass.simpleName}: ${e.message}", null).also { traces += it }
            }
            call.use { response ->
                val location = response.header("Location")
                val next = if (response.code in REDIRECT_CODES && location != null) current.resolve(location) else null
                hops += Hop(method, response.code, current, next)
                finalCode = response.code
                contentType = response.header("Content-Type")
                finalBody = response.body?.string().orEmpty()
                if (next == null) {
                    return Trace(step, hops, finalCode, finalBody, contentType).also { traces += it }
                }
                // Never follow a downgrade: that would send the password in cleartext.
                if (current.isHttps && !next.isHttps) {
                    return Trace(step, hops, finalCode, finalBody, contentType).also { traces += it }
                }
                current = next
            }
        }
        return Trace(step, hops, finalCode, finalBody, contentType).also { traces += it }
    }

    private fun finalUrl(t: Trace): HttpUrl = t.hops.last().let { it.to ?: it.from }

    private fun headEtag(url: String): String? = runCatching {
        http.newCall(
            Request.Builder().url(url).method("PROPFIND", propfind("<d:getetag/>").toRequestBody(XML)).header("Depth", "0").build()
        ).execute().use { r ->
            Regex("""getetag[^>]*>([^<]+)<""").find(r.body?.string().orEmpty())?.groupValues?.get(1)?.replace("&quot;", "\"")?.trim()
        }
    }.getOrNull()

    private fun hrefIn(t: Trace, property: String): String? =
        Regex("""<([A-Za-z0-9_.-]+:)?$property[^>]*>\s*<([A-Za-z0-9_.-]+:)?href[^>]*>\s*([^<\s]+)\s*<""", RegexOption.IGNORE_CASE)
            .find(t.body)?.groupValues?.get(3)

    private fun printCensus() {
        for (t in traces) {
            for ((i, h) in t.hops.withIndex()) {
                val flags = buildList {
                    if (h.to != null) {
                        if (h.from.scheme != h.to.scheme) add("scheme ${h.from.scheme}->${h.to.scheme}")
                        if (h.from.host != h.to.host) add("host changed")
                        if (h.from.port != h.to.port) add("port ${h.from.port}->${h.to.port}")
                    }
                }.joinToString(", ")
                val line = "CENSUS|${config.name}|${t.step}|hop $i|${h.method}|${h.code}|${where(h.from)}|${h.to?.let { where(it) } ?: "-"}|$flags"
                println(FixtureRedactor.redact(line))
            }
            if (t.hops.last().to == null) {
                val note = if (t.finalCode == -1) "|${t.body.take(120)}" else ""
                println(FixtureRedactor.redact("CENSUS|${config.name}|${t.step}|final|${t.finalCode}|${t.contentType}$note"))
            }
        }
    }

    private fun where(u: HttpUrl) = "${u.scheme}://${u.host}:${u.port}${u.encodedPath.take(40)}"

    // ---- request bodies ----

    private fun propfind(props: String) = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop>$props</d:prop></d:propfind>"""

    private fun calendarQuery() = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/></d:prop>
<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="20260101T000000Z"/></c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""

    private fun ics(uid: String, summary: String): String {
        val start = java.time.Instant.now().plusSeconds(30L * 86_400)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
        return listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Transport baseline//EN", "BEGIN:VEVENT",
            "UID:$uid", "DTSTAMP:${fmt.format(java.time.Instant.now())}", "DTSTART:${fmt.format(start)}",
            "DTEND:${fmt.format(start.plusSeconds(3600))}", "SUMMARY:$summary", "END:VEVENT", "END:VCALENDAR", "",
        ).joinToString("\r\n")
    }
}
