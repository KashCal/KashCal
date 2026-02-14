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
 * Parameterized ETag-based sync tests across all configured servers.
 *
 * Tests the ETag lifecycle: create → etag returned, update → etag changes,
 * stale etag → 412 conflict. These are fundamental to KashCal's sync correctness.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*MultiServerEtagSyncTest*"
 */
@RunWith(Parameterized::class)
class MultiServerEtagSyncTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
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
        val home = c.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun createTestIcs(uid: String, summary: String, sequence: Int = 0): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//ETag Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260401T100000Z
DTEND:20260401T110000Z
SUMMARY:$summary
${if (sequence > 0) "SEQUENCE:$sequence" else ""}
END:VEVENT
END:VCALENDAR
    """.trimIndent()

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    // ========== Test 1: Create returns valid etag ==========

    @Test
    fun `create event returns valid etag`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-etag-create-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "ETag Create Test")

        val result = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(result.isSuccess()) {
            "Failed to create event on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }

        val (url, etag) = result.getOrNull()!!
        trackEvent(url, etag)

        assert(etag.isNotEmpty()) { "ETag should not be empty after create on ${config.name}" }
    }

    // ========== Test 2: Update changes etag ==========

    @Test
    fun `update event changes etag`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-etag-update-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "ETag Update Test")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag1) = createResult.getOrNull()!!
        trackEvent(url, etag1)

        // Update
        val updatedIcs = createTestIcs(uid, "ETag Update Test - Modified", sequence = 1)
        val updateResult = client!!.updateEvent(url, updatedIcs, etag1)
        assert(updateResult.isSuccess()) {
            "Failed to update event on ${config.name}: ${(updateResult as? CalDavResult.Error)?.message}"
        }
        val etag2 = updateResult.getOrNull()!!
        trackEvent(url, etag2)

        assert(etag2.isNotEmpty()) { "New ETag should not be empty on ${config.name}" }
        assert(etag1 != etag2) {
            "ETag should change after update on ${config.name} (was: $etag1, now: $etag2)"
        }
    }

    // ========== Test 3: Stale etag produces 412 ==========

    @Test
    fun `stale etag produces 412 conflict`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-etag-conflict-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "ETag Conflict Test")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag1) = createResult.getOrNull()!!
        trackEvent(url, etag1)

        // Update to change etag
        val updatedIcs = createTestIcs(uid, "Changed", sequence = 1)
        val updateResult = client!!.updateEvent(url, updatedIcs, etag1)
        assert(updateResult.isSuccess()) { "Failed to update on ${config.name}" }
        val etag2 = updateResult.getOrNull()!!
        trackEvent(url, etag2)

        // Try update with stale etag1
        val conflictIcs = createTestIcs(uid, "Stale update", sequence = 2)
        val conflictResult = client!!.updateEvent(url, conflictIcs, etag1)
        // RFC 4791 says 412, but some servers (Zoho) return 409
        val isConflictResponse = conflictResult.isConflict() ||
            (conflictResult is CalDavResult.Error && (conflictResult as CalDavResult.Error).code == 409)
        assert(isConflictResponse) {
            "Expected conflict (412/409) on ${config.name} with stale etag, got: $conflictResult"
        }
    }
}
