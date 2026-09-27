package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.DigestAuthenticator
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Checks the client's transport guards on every configured CalDAV server, through the app's own
 * client as its factory builds it:
 *
 * - the principal, calendar-home and listing reads succeed, and no other read the app makes
 *   during discovery and sync fails with a guard error ([GUARD_CODES]: not a multistatus, or
 *   refused to send);
 * - an event another client created (resource name without '@', UID with one) moves to another
 *   calendar under its own resource name and takes an update there;
 * - an event KashCal created is updated and then deleted with the ETag just read (the MOVE
 *   fallback's delete), and the server lists nothing for it after;
 * - no create or update is redirected (no final URL to store), and an https account is never
 *   handed a plain http URL.
 *
 * Each run uses new UIDs. Teardown deletes the hrefs the server still lists that contain a
 * recorded UID's part before '@' (for the foreign event, its resource name), so either spelling
 * of '@' in the href is caught. URLs in failure messages are redacted.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerTransportGuardRoundTripTest*'
 */
@RunWith(Parameterized::class)
class MultiServerTransportGuardRoundTripTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        private const val FUTURE_END_MS = 4_102_444_800_000L
        private val ICAL = "text/calendar; charset=utf-8".toMediaType()

        /** The two errors the guards introduce; neither may appear on a real server. */
        private val GUARD_CODES = setOf(CalDavResult.CODE_NOT_MULTISTATUS, CalDavResult.CODE_TRANSPORT_REFUSED)
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val collections = mutableSetOf<String>()
    private val uids = mutableListOf<String>()

    @Before
    fun setUp() {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
    }

    @After
    fun tearDown() = runBlocking {
        val c = client ?: return@runBlocking
        for (collection in collections) {
            for (href in listedHrefsFor(uids, collection)) {
                runCatching { c.deleteEvent(resolve(collection, href), null) }
            }
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private fun redact(text: Any?): String = FixtureRedactor.redact(text.toString())

    private fun assertNotGuardError(what: String, result: CalDavResult<*>) {
        val code = (result as? CalDavResult.Error)?.code
        assertFalse("${config.name} $what: ${redact(result)}", code in GUARD_CODES)
    }

    private fun assertSuccess(what: String, result: CalDavResult<*>) {
        assertTrue("${config.name} $what: ${redact(result)}", result is CalDavResult.Success)
    }

    private data class Discovered(val principal: String, val home: String, val calendars: List<String>)

    private suspend fun discover(): Discovered? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).also { assertNotGuardError("well-known", it) }.getOrNull() ?: endpoint
        } else {
            endpoint
        }
        // The discovery reads must succeed outright, not only avoid the guard errors.
        val principalResult = c.discoverPrincipal(caldavUrl)
        // Credentials the server no longer accepts (Fastmail's retired app password) skip.
        assumeTrue("${config.name}: credentials rejected", !principalResult.isAuthError())
        assertSuccess("principal", principalResult)
        val principal = principalResult.getOrNull()!!
        val home = c.discoverCalendarHome(principal).also { assertSuccess("calendar home", it) }.getOrNull()!!.first()
        val calendars = c.listCalendars(home).also { assertSuccess("calendar listing", it) }.getOrNull().orEmpty()
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
            .map { it.url }
            .filter { !it.contains("inbox") && !it.contains("outbox") }
            .distinct()
        return Discovered(principal, home, calendars)
    }

    private suspend fun listedHrefsFor(keys: List<String>, collection: String): List<String> {
        val names = keys.map { it.substringBefore('@') }.filter { it.isNotBlank() }
        if (names.isEmpty()) return emptyList()
        val listed = client!!.fetchEtagsInRange(collection, 0L, FUTURE_END_MS).getOrNull().orEmpty().map { it.first }
        return listed.filter { href -> names.any { href.contains(it) } }
    }

    private fun resolve(collection: String, href: String): String =
        if (href.startsWith("http")) href else collection.toHttpUrl().resolve(href).toString()

    private fun ics(uid: String, summary: String): String {
        val start = java.time.Instant.now().plusSeconds(20L * 86_400)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
        return listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Transport guard//EN", "BEGIN:VEVENT",
            "UID:$uid", "DTSTAMP:${fmt.format(java.time.Instant.now())}", "DTSTART:${fmt.format(start)}",
            "DTEND:${fmt.format(start.plusSeconds(3600))}", "SUMMARY:$summary", "END:VEVENT", "END:VCALENDAR", "",
        ).joinToString("\r\n")
    }

    /**
     * Zoho lists a change a few seconds late: re-read until [expected] shows or a minute passes.
     */
    private suspend fun <T> settled(expected: T, read: suspend () -> T): T {
        val deadline = System.currentTimeMillis() + 60_000
        var got = read()
        while (got != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = read()
        }
        return got
    }

    @Test
    fun `every read the app makes is a real multistatus and nothing is refused`() = runBlocking<Unit> {
        assumeReady()
        val c = client!!
        val found = discover()
        assumeTrue("${config.name}: discovery found no writable calendar", found != null && found.calendars.isNotEmpty())
        val calendar = found!!.calendars.first()

        assertNotGuardError("calendar-user addresses", c.discoverCalendarUserAddresses(found.principal))
        assertNotGuardError("schedule outbox", c.discoverScheduleOutboxUrl(found.principal))
        assertNotGuardError("ctag", c.getCtag(calendar))
        val token = c.getSyncToken(calendar).also { assertNotGuardError("sync token", it) }.getOrNull()
        assertNotGuardError("sync-collection", c.syncCollection(calendar, token))
        assertNotGuardError("all etags", c.fetchAllEtags(calendar))
        val listing = c.fetchEtagsInRange(calendar, 0L, FUTURE_END_MS).also { assertNotGuardError("etags in range", it) }
        listing.getOrNull().orEmpty().firstOrNull()?.let { (href, _) ->
            assertNotGuardError("multiget", c.fetchEventsByHref(calendar, listOf(href)))
            assertNotGuardError("etag", c.fetchEtag(resolve(calendar, href)))
        }
        assertNotGuardError("calendar probe", c.probeCalendarCollection(calendar))

        if (creds!!.davEndpoint.startsWith("https://")) {
            val urls = listOf(found.principal, found.home) + found.calendars +
                listing.getOrNull().orEmpty().map { resolve(calendar, it.first) }
            val plain = urls.filter { it.startsWith("http://") }
            assertTrue("${config.name}: an https account was handed plain http URLs ${redact(plain)}", plain.isEmpty())
        }
    }

    @Test
    fun `an event KashCal created is updated then deleted with the etag just read`() = runBlocking<Unit> {
        assumeReady()
        val c = client!!
        val calendar = discover()?.calendars?.firstOrNull()
        assumeTrue("${config.name}: no writable calendar", calendar != null)
        collections += calendar!!
        val uid = "transport-guard-${UUID.randomUUID()}@kashcal.onekash.org"
        uids += uid

        val created = c.createEvent(calendar, uid, ics(uid, "Transport guard"))
        assertTrue("${config.name}: create: ${redact(created)}", created is CalDavResult.Success)
        created as CalDavResult.Success
        assertNull("${config.name}: the create was not redirected", created.finalUrl)
        val (url, createdEtag) = created.data

        val etag = createdEtag.ifEmpty { c.fetchEtag(url).getOrNull().orEmpty() }
        val updated = c.updateEvent(url, ics(uid, "Transport guard edited"), etag)
        assertTrue("${config.name}: update: ${redact(updated)}", updated is CalDavResult.Success)
        assertNull("${config.name}: the update was not redirected", (updated as CalDavResult.Success).finalUrl)

        // The MOVE fallback's delete: read the current etag, then delete only if it still holds.
        val current = c.fetchEtag(url)
        assertTrue("${config.name}: etag before delete: ${redact(current)}", current is CalDavResult.Success && current.data != null)
        val deleted = c.deleteEvent(url, (current as CalDavResult.Success).data)
        assertTrue("${config.name}: conditional delete: ${redact(deleted)}", deleted.isSuccess())

        assertEquals("${config.name}: the server no longer lists it", emptyList<String>(), settled(emptyList()) { listedHrefsFor(listOf(uid), calendar) })
    }

    @Test
    fun `an event another client created moves under its own name and takes an update there`() = runBlocking<Unit> {
        assumeReady()
        val c = client!!
        val calendars = discover()?.calendars.orEmpty()
        assumeTrue("${config.name}: needs two writable calendars", calendars.size >= 2)
        val (source, destination) = calendars[0] to calendars[1]
        collections += source
        collections += destination

        // Another client's naming: a resource name of its own, a UID with an '@' in it.
        val name = "guard-foreign-${UUID.randomUUID()}"
        val uid = "$name-uid@calendar.example.test"
        uids += name
        val sourceUrl = "${source.trimEnd('/')}/$name.ics"
        assertTrue("${config.name}: foreign-style PUT failed", rawPut(sourceUrl, ics(uid, "Foreign event")))

        val moved = c.moveEvent(sourceUrl, destination, uid)
        assumeTrue("${config.name}: server doesn't support MOVE here: ${redact(moved)}", moved.isSuccess())
        val (movedUrl, movedEtag) = (moved as CalDavResult.Success).data
        assertTrue("${config.name}: kept its own name, got ${redact(movedUrl)}", movedUrl.endsWith("/$name.ics"))

        val etag = movedEtag.ifEmpty { c.fetchEtag(movedUrl).getOrNull().orEmpty() }
        val updated = c.updateEvent(movedUrl, ics(uid, "Foreign event edited"), etag)
        assertTrue("${config.name}: the moved event takes an update: ${redact(updated)}", updated is CalDavResult.Success)
        val readBack = c.fetchEvent(movedUrl).getOrNull()?.icalData.orEmpty().replace(Regex("""\r?\n[ \t]"""), "")
        assertTrue("${config.name}: the update reads back", readBack.contains("SUMMARY:Foreign event edited"))
    }

    /** PUTs a new resource at [url] outside the app client, the way another client would. */
    private fun rawPut(url: String, body: String): Boolean {
        val c = creds!!
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .authenticator(DigestAuthenticator(c.username, c.password, allowCleartext = true))
            .addNetworkInterceptor { chain ->
                val r = chain.request()
                chain.proceed(
                    if (r.header("Authorization") == null) {
                        r.newBuilder().header("Authorization", Credentials.basic(c.username, c.password, Charsets.UTF_8)).build()
                    } else {
                        r
                    }
                )
            }
            .build()
        val request = Request.Builder().url(url).put(body.toRequestBody(ICAL)).header("If-None-Match", "*").build()
        return http.newCall(request).execute().use { it.code in 200..299 }
    }
}
