package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.util.CaldavUrlNormalizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * Reproduces #333 live against the Radicale test server.
 *
 * KashCal builds a resource URL from the event UID, which has a literal '@'
 * ("<uuid>@kashcal.onekash.org"), and stores it as caldav_url. When another client deletes
 * the resource, Radicale echoes the deleted href in the sync-collection REPORT with '@'
 * encoded as %40, so a plain string compare on caldav_url misses the row and the deletion is
 * silently skipped.
 *
 * The test creates an event with such a UID, deletes it on the server, runs sync-collection,
 * and asserts that:
 *  (a) Radicale re-encodes '@' as %40 in the deleted href. This is the precondition; when a
 *      Radicale version doesn't, assumeTrue skips the test instead of failing it.
 *  (b) The pull's key derivation (DefaultQuirks.buildEventUrl, then
 *      CaldavUrlNormalizer.canonicalize) maps the re-encoded href to the same canonical value
 *      as the stored URL, while the raw strings differ.
 *
 * Run: ./gradlew :app:testDebugUnitTest -Pintegration --tests "*RadicaleDeletedEventReencodeTest*"
 * Prereqs: Radicale at localhost:5232 + RADICALE_* creds in local.properties
 * (see RadicaleCalDavIntegrationTest for the container recipe).
 */
class RadicaleDeletedEventReencodeTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String = "http://localhost:5232"
    private var username: String? = null
    private var password: String? = null
    private val factory = OkHttpCalDavClientFactory()
    private lateinit var quirks: DefaultQuirks

    // Track for cleanup in case an assertion aborts before the server-side delete.
    private var createdEventUrl: String? = null
    private var createdEventEtag: String? = null

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        loadCredentials()
        quirks = DefaultQuirks(serverUrl)
        val credentials = Credentials(
            username = username.orEmpty(),
            password = password.orEmpty(),
            serverUrl = serverUrl
        )
        client = factory.createClient(credentials, quirks)
    }

    @After
    fun cleanup() = runBlocking {
        val url = createdEventUrl ?: return@runBlocking
        val result = client.deleteEvent(url, createdEventEtag.orEmpty())
        println("Cleanup delete: ${if (result.isSuccess() || result.isNotFound()) "ok" else result}")
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )
        for (path in possiblePaths) {
            val propsFile = File(path)
            if (!propsFile.exists()) continue
            propsFile.readLines().forEach { line ->
                val parts = line.split("=").map { it.trim() }
                if (parts.size == 2) {
                    when (parts[0]) {
                        "RADICALE_SERVER" -> serverUrl = parts[1]
                        "RADICALE_USERNAME" -> username = parts[1]
                        "RADICALE_PASSWORD" -> password = parts[1]
                    }
                }
            }
            if (username != null && password != null) break
        }
    }

    private fun assumeServerAvailable() {
        try {
            val connection = URL(serverUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val code = connection.responseCode
            assumeTrue("Radicale server not available (code: $code)", code in 200..299 || code == 401)
        } catch (e: Exception) {
            assumeTrue("Radicale server not reachable: ${e.message}", false)
        }
    }

    private suspend fun discoverCalendar(): String? {
        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    @Test
    fun `deleted at-sign event reconciles despite Radicale re-encoding the href`() = runBlocking {
        assumeServerAvailable()
        assumeTrue("Radicale credentials not available", username != null && password != null)

        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        // A UID shaped like KashCal's own, with a literal '@' in the filename.
        val uid = "${UUID.randomUUID()}@kashcal.onekash.org"
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Issue 333 reproduction - safe to delete
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        // 1. Create: the path that stores caldav_url with a literal '@'.
        val createResult = client.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) {
            "Failed to create event: ${(createResult as? CalDavResult.Error)?.message}"
        }
        val (storedUrl, createEtag) = createResult.getOrNull()!!
        createdEventUrl = storedUrl
        createdEventEtag = createEtag
        println("Stored caldav_url (as KashCal would store it): $storedUrl")
        assert(storedUrl.contains("@")) {
            "Stored URL should contain a literal '@' (KashCal build path): $storedUrl"
        }
        assert(!storedUrl.contains("%40")) {
            "Stored URL should NOT be pre-encoded: $storedUrl"
        }

        // 2. Baseline sync token, then delete server-side (simulating another client).
        val tokenBefore = client.getSyncToken(calendarUrl).getOrNull()
        assumeTrue("Radicale did not return a sync token", !tokenBefore.isNullOrEmpty())

        val deleteResult = client.deleteEvent(storedUrl, createEtag)
        assert(deleteResult.isSuccess() || deleteResult.isNotFound()) {
            "Failed to delete event server-side: ${(deleteResult as? CalDavResult.Error)?.message}"
        }
        createdEventUrl = null // deleted; nothing to clean up

        // 3. sync-collection REPORT: how the next pull learns of the deletion.
        val reportResult = client.syncCollection(calendarUrl, tokenBefore)
        assert(reportResult.isSuccess()) {
            "sync-collection failed: ${(reportResult as? CalDavResult.Error)?.message}"
        }
        val report = reportResult.getOrNull()!!
        println("Deleted hrefs from sync-collection: ${report.deleted}")

        // Locate the href for our resource among the reported deletions.
        val filenameStem = uid // the '@'-containing stem, before .ics
        val deletedHref = report.deleted.firstOrNull {
            it.contains(filenameStem) || it.contains(filenameStem.replace("@", "%40"))
        }
        assumeTrue(
            "Radicale did not report our resource as deleted in this sync window " +
                "(deleted=${report.deleted})",
            deletedHref != null
        )
        println("Matched deleted href: $deletedHref")

        // (a) Precondition: Radicale re-encodes '@' as %40 in the echoed href. Without it the
        // bug can't occur, so skip; a server change never fails this test.
        assumeTrue(
            "Radicale did not re-encode '@' as %40 in this run; #333 precondition absent",
            deletedHref!!.contains("%40")
        )

        // (b) The pull's key derivation reconciles the re-encoded href with the stored URL;
        // the pull also builds the URL with buildEventUrl.
        val reportedUrl = quirks.buildEventUrl(deletedHref, calendarUrl)
        val canonicalReported = CaldavUrlNormalizer.canonicalize(reportedUrl) ?: reportedUrl
        val canonicalStored = CaldavUrlNormalizer.canonicalize(storedUrl) ?: storedUrl

        // Control: a raw compare misses, which is the silent skip.
        assert(reportedUrl != storedUrl) {
            "Expected raw URLs to differ by encoding (else there is nothing to fix): " +
                "reported=$reportedUrl stored=$storedUrl"
        }
        // A canonical compare matches, so the pull finds and deletes the row.
        assert(canonicalReported == canonicalStored) {
            "Canonicalized URLs should match so the deletion reconciles:\n" +
                "  reported=$reportedUrl -> $canonicalReported\n" +
                "  stored=$storedUrl -> $canonicalStored"
        }
        println("Reconciled: canonical('$reportedUrl') == canonical('$storedUrl') == '$canonicalStored'")
    }
}
