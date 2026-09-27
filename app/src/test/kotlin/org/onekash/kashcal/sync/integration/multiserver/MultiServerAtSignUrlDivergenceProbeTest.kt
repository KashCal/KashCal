package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID

/**
 * Probes the push side of the "@"-URL divergence from #333.
 *
 * KashCal builds a resource URL from its event UID, which holds a literal "@" (e.g.
 * "<uuid>@kashcal.onekash.org"), as "{calendar}/{uid}.ics", and stores that constructed URL as
 * caldav_url unless the PUT was redirected; it doesn't read a Location header on success. #333
 * showed Radicale stores the resource at a re-encoded href ("%40") and echoes "%40" in
 * sync-collection, which is why the pull path compares URLs canonically.
 *
 * The question: on a server that stores at "%40", does an UPDATE, GET or DELETE aimed at the
 * literal-"@" URL the app stored still succeed, or does the server 404 it? If every server
 * treats "@" and "%40" as the same path, the divergence is benign. If any rejects the literal
 * form, it is a bug of the same class as #333 on the write side.
 *
 * It prints per-server behavior and fails on the bug shape: the server re-encodes the stored
 * href and an operation on the app's literal-'@' URL fails. The client retries a 404 on a stored
 * resource URL with the '@' written the other way (`OkHttpCalDavClient.executeForResource`),
 * which Stalwart needs (it answers GET and DELETE only on the %40 form). A DELETE counts as
 * working only if the server stops listing the resource: the client treats a 404 as "already
 * gone", so its result alone can't show a lost delete.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests "*MultiServerAtSignUrlDivergenceProbeTest*"
 */
@RunWith(Parameterized::class)
class MultiServerAtSignUrlDivergenceProbeTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    // (url, etag) pairs to attempt cleanup on, whatever encoding the server used.
    private val cleanupUrls = mutableListOf<Pair<String, String>>()

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }
    }

    @After
    fun cleanup() = runBlocking {
        val c = client ?: return@runBlocking
        for ((url, etag) in cleanupUrls.reversed()) {
            try {
                c.deleteEvent(url, etag)
            } catch (_: Exception) {}
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun ics(uid: String, summary: String): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//AtSign Divergence Probe//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:20260601T120000Z
DTSTART:20260601T100000Z
DTEND:20260601T110000Z
SUMMARY:$summary
END:VEVENT
END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    private fun <T> codeOf(result: CalDavResult<T>): String = when (result) {
        is CalDavResult.Success -> "OK"
        is CalDavResult.Error -> "ERR(${result.code})"
    }

    @Test
    fun `probe at-sign url divergence on create update fetch delete`() = runBlocking {
        assumeReady()
        val c = client!!
        val calendarUrl = discoverCalendar()
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)

        // A UID shaped like KashCal's own: a literal '@' in the resource name.
        val uid = "${UUID.randomUUID()}@kashcal.onekash.org"

        // 1. CREATE, the app path. createEvent returns the constructed URL (literal '@').
        val createResult = c.createEvent(calendarUrl!!, uid, ics(uid, "AtSign probe - safe to delete"))
        assumeTrue("${config.name}: create failed (${codeOf(createResult)}) - cannot probe", createResult.isSuccess())
        val (storedUrl, createEtag) = createResult.getOrNull()!!
        cleanupUrls.add(storedUrl to createEtag)

        val storedHasLiteralAt = storedUrl.contains("@")
        val storedHasEncodedAt = storedUrl.contains("%40", ignoreCase = true)

        // 2. List the href the server stored (a calendar-query REPORT returns the server's own
        // hrefs) and detect re-encoding by comparing stems.
        val stem = uid // '@'-containing filename stem, before .ics
        val encodedStem = uid.replace("@", "%40")
        val rangeEnd = 1_900_000_000_000L // ~2030, comfortably after DTSTART (2026)
        val etagsResult = c.fetchEtagsInRange(calendarUrl, 0L, rangeEnd)
        val serverHrefs = if (etagsResult.isSuccess()) etagsResult.getOrNull()!!.map { it.first } else emptyList()
        val serverHref = serverHrefs.firstOrNull { it.contains(stem) || it.contains(encodedStem, ignoreCase = true) }
        val serverReEncoded = serverHref?.contains("%40", ignoreCase = true) == true &&
            serverHref?.contains("@") != true

        // 3. UPDATE at the literal-'@' stored URL (what the app does on edit).
        val updateResult = c.updateEvent(storedUrl, ics(uid, "AtSign probe - EDITED"), createEtag)
        val updateEtag = if (updateResult.isSuccess()) updateResult.getOrNull() else null
        if (updateEtag != null) {
            cleanupUrls.clear()
            cleanupUrls.add(storedUrl to updateEtag)
        }

        // 4. GET at the literal-'@' stored URL (read-back path).
        val fetchResult = c.fetchEvent(storedUrl)

        // 5. DELETE at the literal-'@' stored URL (what the app does on delete).
        val deleteEtag = updateEtag ?: createEtag
        val deleteResult = c.deleteEvent(storedUrl, deleteEtag)
        val stillListed = serverHref != null && (c.fetchEtagsInRange(calendarUrl, 0L, rangeEnd).getOrNull()
            ?.any { it.first == serverHref } ?: false) // a failed listing proves nothing either way
        cleanupUrls.clear()
        if (stillListed) {
            // The delete didn't take: clean up at the href the server itself lists.
            cleanupUrls.add(java.net.URI(calendarUrl).resolve(serverHref!!).toString() to deleteEtag)
        }

        // 6. Report.
        val updateOk = updateResult.isSuccess()
        val fetchOk = fetchResult.isSuccess()
        val deleteOk = deleteResult.isSuccess() && !stillListed
        val literalOpsAllSucceeded = updateOk && fetchOk && deleteOk

        val genuineBug = serverReEncoded && !literalOpsAllSucceeded

        val verdict = when {
            serverHref == null -> "INCONCLUSIVE (server href not enumerable via calendar-query)"
            genuineBug -> "GENUINE BUG: re-encodes stored href AND rejects a literal-@ op"
            serverReEncoded && literalOpsAllSucceeded -> "OK: re-encodes href; every op on the literal-@ URL reaches it (server alias or client retry)"
            !serverReEncoded -> "NOT APPLICABLE: server preserves literal @ in stored href"
            else -> "UNCLASSIFIED"
        }

        println(
            """
            |=== AtSign URL divergence probe: ${config.name} ===
            |  constructed storedUrl : $storedUrl
            |    contains literal @  : $storedHasLiteralAt
            |    contains %40        : $storedHasEncodedAt
            |  server-stored href    : ${serverHref ?: "(not found; hrefs seen=${serverHrefs.size})"}
            |    server re-encoded @ : $serverReEncoded
            |  literal-@ UPDATE      : ${codeOf(updateResult)}
            |  literal-@ GET         : ${codeOf(fetchResult)}
            |  literal-@ DELETE      : ${codeOf(deleteResult)}${if (stillListed) " (but still listed)" else ""}
            |  VERDICT               : $verdict
            """.trimMargin()
        )

        if (genuineBug) {
            throw AssertionError(
                "${config.name}: push-side @-URL divergence is back: the server stores at " +
                    "'$serverHref' but the app keeps its literal-@ URL '$storedUrl'; " +
                    "UPDATE=${codeOf(updateResult)} GET=${codeOf(fetchResult)} " +
                    "DELETE=${codeOf(deleteResult)}${if (stillListed) " (still listed)" else ""}."
            )
        }
    }
}
