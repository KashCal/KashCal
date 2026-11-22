package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.feeds.CalendarFeedUrl
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.util.UUID

/**
 * Proves live that [CalDavClient.listWebcalFeeds] reads calendar feeds (`CS:subscribed`
 * collections) the way the Calendar Feeds "Add from account" sheet needs.
 *
 * - Every server below: the listing answers without throwing and never returns a calendar.
 * - Nextcloud, Radicale and iCloud, which store feeds: the test creates its own feed, finds it in
 *   the listing with its source, and deletes it again.
 *
 * Writes follow the real-account rule: the only collection ever deleted is the one this run's
 * own MKCOL created (a UUID in its name), at the URL that MKCOL used, and only when it returned
 * 201. On iCloud (a dedicated test Apple ID) that URL is on the host the server reports for the
 * calendar home, because a redirect from the canonical host would turn MKCOL and DELETE into GET.
 * Zoho, Mailbox and Fastmail are not in the list: they are the owner's accounts.
 *
 * Skips (never fails) servers without credentials or that are unreachable.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerCalendarFeedsTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCalendarFeedsTest(
    private val config: CalDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = listOf(
            CalDavServerConfig.NEXTCLOUD,
            CalDavServerConfig.RADICALE,
            CalDavServerConfig.ICLOUD,
            CalDavServerConfig.BAIKAL,
            CalDavServerConfig.BAIKAL_DIGEST,
            CalDavServerConfig.STALWART,
            CalDavServerConfig.XANDIKOS,
            CalDavServerConfig.SOGO,
            CalDavServerConfig.CYRUS,
        ).map { arrayOf<Any>(it) }

        /** Servers that store feeds; the test writes only to these. */
        private val FEED_SERVERS = setOf("Nextcloud", "Radicale", "iCloud")
        private val XML = "application/xml; charset=utf-8".toMediaType()
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    /** The calendar home as the app discovers and stores it. */
    private suspend fun calendarHome(): String {
        val c = client!!
        val cr = creds!!
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal", principal != null)
        val home = (c.discoverCalendarHome(principal!!) as? CalDavResult.Success)?.data?.firstOrNull()
        assumeTrue("${config.name}: no calendar-home-set", home != null)
        return if (home!!.endsWith("/")) home else "$home/"
    }

    @Test
    fun `listing feeds answers without throwing and never returns a calendar`() = runBlocking {
        assumeReady()
        val home = calendarHome()

        val result = client!!.listWebcalFeeds(home)

        when (result) {
            is CalDavResult.Success -> {
                val calendars = client!!.listCalendars(home).getOrNull().orEmpty()
                    .map { URI(it.url).path.trimEnd('/') }.toSet()
                val feeds = result.data.map { URI(it.href).path.trimEnd('/') }
                println("${config.name}: ${feeds.size} feed(s), ${calendars.size} calendar(s)")
                assertTrue("${config.name}: a calendar was listed as a feed", feeds.none { it in calendars })
            }
            is CalDavResult.Error -> println("${config.name}: feed listing error ${result.code}")
        }
    }

    @Test
    fun `a feed the test creates is listed with its source and cleans to https`() = runBlocking {
        assumeReady()
        assumeTrue("${config.name}: doesn't store feeds; read-only here", config.name in FEED_SERVERS)
        val home = calendarHome()
        val writeHome = serverReportedHome(home)
        val runId = UUID.randomUUID().toString()
        val slug = "kashcal-feed-probe-$runId"
        val createdUrl = "$writeHome$slug/"
        val source = "webcal://feeds.example.test/kashcal-$runId.ics"
        val http = CollectionResourceTypeProof.rawClient(creds!!.username, creds!!.password)

        val created = mkcol(http, createdUrl, source)
        assumeTrue("${config.name}: MKCOL of a subscribed collection returned $created", created == 201)
        try {
            if (config.name == "Radicale") {
                assertEquals("Radicale: PROPPATCH of the source", 207, proppatchSource(http, createdUrl, source))
            }
            val feeds = (client!!.listWebcalFeeds(home) as? CalDavResult.Success)?.data
            assertNotNull("${config.name}: feed listing failed", feeds)
            val mine = feeds!!.singleOrNull { it.href.trimEnd('/').endsWith(slug) }
            assertNotNull("${config.name}: the created feed is not listed", mine)
            assertEquals("${config.name}: stored source", source, mine!!.source)
            assertEquals(
                "https://feeds.example.test/kashcal-$runId.ics",
                CalendarFeedUrl.normalize(mine.source),
            )
            println("${config.name}: created feed listed with its source")
            if (config.name == "Radicale") {
                // The standing collection with an empty source, seeded on the local server.
                val broken = feeds.firstOrNull { it.href.trimEnd('/').endsWith("webcal-broken") }
                assumeTrue("Radicale: standing empty-source collection missing", broken != null)
                assertEquals(null, broken!!.source)
                assertEquals(null, CalendarFeedUrl.normalize(broken.source))
            }
        } finally {
            // Only this run's own collection, at the URL its own MKCOL used, after a 201.
            val deleted = http.newCall(Request.Builder().url(createdUrl).delete().build())
                .execute().use { it.code }
            println("${config.name}: DELETE own feed -> $deleted")
            assertTrue("${config.name}: DELETE of the test feed failed ($deleted)", deleted in 200..299)
            val after = propfindDepth0(http, createdUrl)
            println("${config.name}: PROPFIND after delete -> $after")
            assertTrue("${config.name}: the test feed is still there ($after)", after == 404 || after == 410)
        }
    }

    /**
     * Creates a subscribed collection and returns the MKCOL status. Radicale keeps the source
     * only when it is set as bare text in a PROPPATCH (its MKCOL stores an `<href>` source as
     * empty), so for Radicale the source goes in [proppatchSource] instead; the others take it in
     * the MKCOL, the way Apple Calendar sends it.
     */
    private fun mkcol(http: OkHttpClient, url: String, source: String): Int {
        val sourceProp = if (config.name == "Radicale") "" else "<cs:source><d:href>$source</d:href></cs:source>"
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:mkcol xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:set><d:prop>
                    <d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype>
                    <d:displayname>KashCal feed probe</d:displayname>
                    $sourceProp
                </d:prop></d:set>
            </d:mkcol>
        """.trimIndent()
        return http.newCall(
            Request.Builder().url(url).method("MKCOL", body.toRequestBody(XML)).build()
        ).execute().use { it.code }
    }

    private fun proppatchSource(http: OkHttpClient, url: String, source: String): Int {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propertyupdate xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:set><d:prop><cs:source>$source</cs:source></d:prop></d:set>
            </d:propertyupdate>
        """.trimIndent()
        return http.newCall(
            Request.Builder().url(url).method("PROPPATCH", body.toRequestBody(XML)).build()
        ).execute().use { it.code }
    }

    private fun propfindDepth0(http: OkHttpClient, url: String): Int {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        return http.newCall(
            Request.Builder().url(url).method("PROPFIND", body.toRequestBody(XML)).header("Depth", "0").build()
        ).execute().use { it.code }
    }

    /**
     * Returns [home] on the host the server reports for it. For iCloud that is the account's
     * partition (pNN-caldav.icloud.com), which the app normalizes away; other servers return
     * [home] unchanged.
     */
    private suspend fun serverReportedHome(home: String): String {
        if (!config.name.equals("iCloud", ignoreCase = true)) return home
        val http = CollectionResourceTypeProof.rawClient(creds!!.username, creds!!.password)
        val principal = client!!.discoverPrincipal(creds!!.davEndpoint).getOrNull()
        assumeTrue("iCloud: no principal", principal != null)
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""
        val reported = http.newCall(
            Request.Builder().url(principal!!).method("PROPFIND", body.toRequestBody(XML)).header("Depth", "0").build()
        ).execute().use { r ->
            Regex("""calendar-home-set[^>]*>\s*<[^>]*href[^>]*>\s*(https?://[^<\s]+)""")
                .find(r.body?.string().orEmpty())?.groupValues?.get(1)
        }
        assumeTrue("iCloud: the principal reported no absolute calendar home", reported != null)
        val host = URI(reported!!).let { "${it.scheme}://${it.host}" }
        assumeTrue("iCloud: reported host is the canonical one", !host.endsWith("//caldav.icloud.com"))
        return home.replaceFirst(Regex("""^https?://[^/]+"""), host)
    }
}
