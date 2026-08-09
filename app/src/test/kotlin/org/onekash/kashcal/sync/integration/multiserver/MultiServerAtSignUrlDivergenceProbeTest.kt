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
 * Empirical probe for the push-side "@"-URL divergence question raised after #333.
 *
 * KashCal builds a resource URL by interpolating its event UID (which contains a
 * literal "@", e.g. "<uuid>@kashcal.onekash.org") straight into
 * "{calendar}/{uid}.ics", stores THAT constructed string as caldav_url, and never
 * adopts the server's Location header. #333 proved Radicale stores the resource at
 * a re-encoded href ("%40") and echoes "%40" in sync-collection — which is why the
 * PULL path now canonicalizes before matching.
 *
 * The open question is whether the PUSH path is actually broken: on a server that
 * stores at "%40", does a follow-up UPDATE / GET / DELETE aimed at the literal-"@"
 * URL (the app's stored guess) still succeed, or does the server 404 it? If every
 * server aliases "@" ≡ "%40" on the path, the divergence is benign and only worth
 * defensive hardening. If any server rejects the literal form, it is a real bug of
 * the same class as #333, on the write side.
 *
 * This test does NOT assert a fix — it MEASURES and prints per-server behavior so
 * we can decide. It only hard-fails if a server exhibits the genuine-bug shape
 * (re-encodes the stored href AND rejects an operation on the literal URL), which
 * is exactly the signal we're hunting.
 *
 * Run: ./gradlew :app:testDebugUnitTest -Pintegration --tests "*MultiServerAtSignUrlDivergenceProbeTest*"
 */
@RunWith(Parameterized::class)
class MultiServerAtSignUrlDivergenceProbeTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        /**
         * Servers KNOWN to exhibit the genuine-bug shape (re-encode the stored href AND
         * reject a literal-'@' op) while the push-side fix is unshipped. This is an
         * expected-failure allowlist: the bug is documented and deferred, so its presence
         * on these servers is not a fresh regression and must not turn the integration
         * suite red. The probe still HARD-FAILS if the shape appears on any server NOT in
         * this set (a new server regressing into the bug), and hard-fails in REVERSE if a
         * listed server stops exhibiting it — that means the fix effectively landed and
         * this allowance (plus the xfail wiring) should be removed and the probe returned
         * to a plain hard-fail. Names match CalDavServerConfig.name exactly.
         */
        val KNOWN_DIVERGENT_SERVERS: Set<String> = setOf("Stalwart")
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

        // UID shaped exactly like KashCal's own — literal '@' in the filename.
        val uid = "${UUID.randomUUID()}@kashcal.onekash.org"

        // 1. CREATE — the app path. createEvent returns the CONSTRUCTED url (literal '@').
        val createResult = c.createEvent(calendarUrl!!, uid, ics(uid, "AtSign probe - safe to delete"))
        assumeTrue("${config.name}: create failed (${codeOf(createResult)}) - cannot probe", createResult.isSuccess())
        val (storedUrl, createEtag) = createResult.getOrNull()!!
        cleanupUrls.add(storedUrl to createEtag)

        val storedHasLiteralAt = storedUrl.contains("@")
        val storedHasEncodedAt = storedUrl.contains("%40", ignoreCase = true)

        // 2. Enumerate what href the server ACTUALLY stored (calendar-query REPORT
        // returns the server's own hrefs). Detect re-encoding by comparing stems.
        val stem = uid // '@'-containing filename stem, before .ics
        val encodedStem = uid.replace("@", "%40")
        val rangeEnd = 1_900_000_000_000L // ~2030, comfortably after DTSTART (2026)
        val etagsResult = c.fetchEtagsInRange(calendarUrl, 0L, rangeEnd)
        val serverHrefs = if (etagsResult.isSuccess()) etagsResult.getOrNull()!!.map { it.first } else emptyList()
        val serverHref = serverHrefs.firstOrNull { it.contains(stem) || it.contains(encodedStem, ignoreCase = true) }
        val serverReEncoded = serverHref?.contains("%40", ignoreCase = true) == true &&
            serverHref?.contains("@") != true

        // 3. UPDATE at the LITERAL-'@' stored URL (what the app would do on edit).
        val updateResult = c.updateEvent(storedUrl, ics(uid, "AtSign probe - EDITED"), createEtag)
        val updateEtag = if (updateResult.isSuccess()) updateResult.getOrNull() else null
        if (updateEtag != null) {
            cleanupUrls.clear()
            cleanupUrls.add(storedUrl to updateEtag)
        }

        // 4. GET at the literal-'@' stored URL (read-back path).
        val fetchResult = c.fetchEvent(storedUrl)

        // 5. DELETE at the literal-'@' stored URL (what the app would do on delete).
        val deleteEtag = updateEtag ?: createEtag
        val deleteResult = c.deleteEvent(storedUrl, deleteEtag)
        if (deleteResult.isSuccess() || deleteResult.isNotFound()) {
            cleanupUrls.clear() // resource gone (or was never at this url); nothing to clean
        }

        // 6. Report.
        val updateOk = updateResult.isSuccess()
        val fetchOk = fetchResult.isSuccess()
        val deleteOk = deleteResult.isSuccess()
        val literalOpsAllSucceeded = updateOk && fetchOk && deleteOk

        val genuineBug = serverReEncoded && !literalOpsAllSucceeded
        val isKnownDivergent = config.name in KNOWN_DIVERGENT_SERVERS

        val verdict = when {
            serverHref == null -> "INCONCLUSIVE (server href not enumerable via calendar-query)"
            genuineBug && isKnownDivergent -> "XFAIL: genuine bug, but a KNOWN-divergent server (push-side fix deferred)"
            genuineBug -> "GENUINE BUG: re-encodes stored href AND rejects a literal-@ op"
            serverReEncoded && literalOpsAllSucceeded -> "BENIGN: re-encodes href but ALIASES @ ≡ %40 (all literal-@ ops OK)"
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
            |  literal-@ DELETE      : ${codeOf(deleteResult)}
            |  VERDICT               : $verdict
            """.trimMargin()
        )

        // Hard-fail on the genuine-bug shape ONLY for a server not already known to
        // exhibit it (a fresh regression). Known-divergent servers are an expected
        // failure while the push-side fix is deferred — recorded above as XFAIL, not thrown.
        if (genuineBug && !isKnownDivergent) {
            throw AssertionError(
                "${config.name}: push-side @-URL divergence is a REAL bug — server stores at " +
                    "'$serverHref' but the app keeps its literal-@ guess '$storedUrl'; " +
                    "UPDATE=${codeOf(updateResult)} GET=${codeOf(fetchResult)} DELETE=${codeOf(deleteResult)}. " +
                    "This is the write-side twin of #333 and needs the same canonicalization/Location-adoption fix. " +
                    "If this is a known, deferred case, add '${config.name}' to KNOWN_DIVERGENT_SERVERS."
            )
        }

        // Reverse guard: a server on the known-divergent allowlist that NO LONGER shows
        // the bug means the divergence was effectively fixed (code or server change).
        // Fail loudly so the stale allowance can't silently mask a future real regression —
        // the maintainer should drop it from KNOWN_DIVERGENT_SERVERS and, once the set is
        // empty, restore the plain hard-fail.
        if (isKnownDivergent && serverHref != null && !genuineBug) {
            throw AssertionError(
                "${config.name} is on KNOWN_DIVERGENT_SERVERS but no longer exhibits the push-side " +
                    "@-URL divergence (verdict: $verdict). The bug appears fixed — remove " +
                    "'${config.name}' from KNOWN_DIVERGENT_SERVERS so the probe hard-fails on any " +
                    "future regression."
            )
        }
    }
}
