package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.delay
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
 * Probes, per server, moving an event between two calendars on the same account while its
 * title and description are edited in the same save (issue #292).
 *
 * The RFCs under-specify what servers do here, so each test records one behavior:
 *
 *  A. Whether a WebDAV MOVE succeeds and leaves the old body at the destination. RFC 4918 §9.9
 *     defines MOVE as copy then delete, so a same-save edit isn't carried; this reproduces #292.
 *  B. Whether MOVE then PUT (relocate, then overwrite the destination with the edited body)
 *     lands the edits. Production does this after a successful MOVE (`PushStrategy.processMove`).
 *  C. Whether a CREATE with the same UID into a sibling collection is rejected while the
 *     source still exists. RFC 4791 §5.3.2.1 scopes CALDAV:no-uid-conflict to the targeted
 *     collection, but iCloud enforces it more broadly; this measures whether "always CREATE on
 *     the target, then DELETE the source" is viable per server.
 *
 * Each test runs once per server and skips when credentials are missing, the server is
 * unreachable, or the account lists fewer than two calendars (inbox and outbox excluded).
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*MultiServerCalendarMoveEditTest*"
 */
@RunWith(Parameterized::class)
class MultiServerCalendarMoveEditTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    // (url, etag) of everything this test created, deleted best-effort in reverse.
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

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
        for ((url, etag) in createdEventUrls.reversed()) {
            try {
                c.deleteEvent(url, etag)
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
    }

    private fun assumeReady() {
        assumeTrue(
            "${config.name} credentials not available",
            client != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    /** Returns the account's calendar collection URLs, minus any URL containing inbox or outbox. */
    private suspend fun discoverCalendars(): List<String> {
        val c = client!!
        val endpoint = creds!!.davEndpoint

        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }

        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return emptyList()
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return emptyList()
        val calendars = c.listCalendars(home).getOrNull() ?: return emptyList()

        return calendars
            .map { it.url }
            .filter { !it.contains("inbox") && !it.contains("outbox") }
            .distinct()
    }

    private fun createTestIcs(
        uid: String,
        summary: String,
        description: String,
        sequence: Int = 0
    ): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//MoveEdit Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260701T100000Z
DTEND:20260710T110000Z
SUMMARY:$summary
DESCRIPTION:$description
SEQUENCE:$sequence
END:VEVENT
END:VCALENDAR
    """.trimIndent()

    private fun track(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    private suspend fun assumeTwoCalendars(): Pair<String, String>? {
        val cals = discoverCalendars()
        assumeTrue(
            "${config.name} exposes fewer than 2 writable calendars (got ${cals.size})",
            cals.size >= 2
        )
        return cals[0] to cals[1]
    }

    // ========== A. Does same-account MOVE leave a stale body? (issue #292) ==========

    @Test
    fun `same-account MOVE relocates the old body verbatim`() = runBlocking {
        assumeReady()
        val (calA, calB) = assumeTwoCalendars() ?: return@runBlocking

        val uid = "move-stale-${config.name.lowercase()}-${UUID.randomUUID()}"
        val createResult = client!!.createEvent(calA, uid, createTestIcs(uid, "Room 1", "Room 1 note"))
        assumeTrue(
            "${config.name} could not create source event: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess()
        )
        val (sourceUrl, sourceEtag) = createResult.getOrNull()!!
        track(sourceUrl, sourceEtag)

        // The production same-account path starts with a bodyless WebDAV MOVE. The in-app edit
        // to "Room 2" isn't part of the MOVE request, as in the #292 sequence.
        val moveResult = client!!.moveEvent(sourceUrl, calB, uid)

        val moveCode = (moveResult as? CalDavResult.Error)?.code
        println("MOVE-RESULT ${config.name}: success=${moveResult.isSuccess()} code=$moveCode")

        // A server that declines MOVE (403, 405 or 412) takes production's CREATE+DELETE
        // fallback, which re-serializes the current body, so it isn't the #292 vector. Only
        // servers that accept MOVE can strand a stale body.
        assumeTrue(
            "${config.name} does not accept WebDAV MOVE (code=$moveCode) — not the #292 vector",
            moveResult.isSuccess()
        )
        val (newUrl, newEtag) = moveResult.getOrNull()!!
        track(newUrl, newEtag)

        val fetched = client!!.fetchEvent(newUrl)
        assumeTrue("${config.name} could not fetch moved event", fetched.isSuccess())
        val body = fetched.getOrNull()!!.icalData
        val staleTitlePresent = body.contains("Room 1")
        println("MOVE-BODY ${config.name}: stale='Room 1' present=$staleTitlePresent at $newUrl")

        // On a MOVE-capable server the relocated body is the original, so without a follow-up
        // PUT an edit made in the same save is lost.
        assert(staleTitlePresent) {
            "${config.name}: expected MOVE to relocate the original body verbatim, " +
                "but 'Room 1' was absent. Body:\n$body"
        }
    }

    // ========== B. Does MOVE-then-PUT land the edits? ==========

    @Test
    fun `MOVE then PUT lands the edited body at the destination`() = runBlocking {
        assumeReady()
        val (calA, calB) = assumeTwoCalendars() ?: return@runBlocking

        val uid = "move-put-${config.name.lowercase()}-${UUID.randomUUID()}"
        val createResult = client!!.createEvent(calA, uid, createTestIcs(uid, "Room 1", "Room 1 note"))
        assumeTrue(
            "${config.name} could not create source event",
            createResult.isSuccess()
        )
        val (sourceUrl, sourceEtag) = createResult.getOrNull()!!
        track(sourceUrl, sourceEtag)

        val moveResult = client!!.moveEvent(sourceUrl, calB, uid)
        assumeTrue(
            "${config.name} does not accept WebDAV MOVE — fix path is CREATE+DELETE, not MOVE+PUT",
            moveResult.isSuccess()
        )
        val (newUrl, movedEtag) = moveResult.getOrNull()!!
        track(newUrl, movedEtag)

        // Overwrite the relocated resource with the edited body, as production does.
        val putResult = client!!.updateEvent(
            newUrl,
            createTestIcs(uid, "Room 2", "Room 2 note", sequence = 1),
            movedEtag
        )
        assert(putResult.isSuccess()) {
            "${config.name}: PUT after MOVE failed: ${(putResult as? CalDavResult.Error)?.message}"
        }
        track(newUrl, putResult.getOrNull()!!)

        val fetched = client!!.fetchEvent(newUrl)
        assert(fetched.isSuccess()) { "${config.name}: could not fetch after MOVE+PUT" }
        val body = fetched.getOrNull()!!.icalData
        assert(body.contains("Room 2")) {
            "${config.name}: edited title 'Room 2' should be present after MOVE+PUT. Body:\n$body"
        }
    }

    // ========== C. Does same-UID CREATE into a sibling collection conflict? ==========

    @Test
    fun `CREATE with same UID while source still exists`() = runBlocking {
        assumeReady()
        val (calA, calB) = assumeTwoCalendars() ?: return@runBlocking

        val uid = "uid-conflict-${config.name.lowercase()}-${UUID.randomUUID()}"
        val createA = client!!.createEvent(calA, uid, createTestIcs(uid, "Room 1", "Room 1 note"))
        assumeTrue(
            "${config.name} could not create source event",
            createA.isSuccess()
        )
        val (urlA, etagA) = createA.getOrNull()!!
        track(urlA, etagA)

        // "Always CREATE on the target, then DELETE the source" would issue this CREATE while
        // the source resource (same UID) is still live. RFC 4791 §5.3.2.1 scopes
        // no-uid-conflict to the targeted collection, so the spec allows it; iCloud enforces
        // UID uniqueness across the account and rejects it.
        val createB = client!!.createEvent(calB, uid, createTestIcs(uid, "Room 2", "Room 2 note"))
        val code = (createB as? CalDavResult.Error)?.code
        val conflicted = createB is CalDavResult.Error && (code == 403 || code == 409 || code == 412)
        println(
            "UID-CONFLICT ${config.name}: createB success=${createB.isSuccess()} " +
                "code=$code conflicted=$conflicted"
        )

        if (createB.isSuccess()) {
            val (urlB, etagB) = createB.getOrNull()!!
            track(urlB, etagB)
        }

        // No assertion on the outcome: this test records per-server behavior. A conflict means
        // "always CREATE+DELETE" is unsafe on this server and same-account moves must keep the
        // MOVE-based path. The delay gives eventually-consistent servers a moment before cleanup.
        delay(200)
    }
}
