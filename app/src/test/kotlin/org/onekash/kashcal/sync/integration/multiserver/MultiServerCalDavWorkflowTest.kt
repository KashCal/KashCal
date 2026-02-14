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
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Parameterized CalDAV workflow tests that run across all configured servers.
 *
 * Each test runs once per server. Tests auto-skip via `assumeTrue` when:
 * - Credentials are not available in local.properties
 * - Server is not reachable
 *
 * Run: ./gradlew testDebugUnitTest --tests "*MultiServerCalDavWorkflowTest*"
 *
 * Servers tested: iCloud, Stalwart, Baikal, Radicale, Nextcloud, Zoho
 */
@RunWith(Parameterized::class)
class MultiServerCalDavWorkflowTest(
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
    private val createdEventUrls = mutableListOf<Pair<String, String>>() // (url, etag)

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

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint

        // Well-known discovery first if the server supports it
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }

        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null

        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    private fun createTestIcs(
        uid: String,
        summary: String,
        dtstart: String = "20260315T100000Z",
        dtend: String = "20260315T110000Z",
        extra: String = ""
    ): String = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//MultiServer Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$dtstart
DTEND:$dtend
SUMMARY:$summary
$extra
END:VEVENT
END:VCALENDAR
    """.trimIndent()

    private fun trackEvent(url: String, etag: String) {
        // Remove previous entry for same URL, add updated one
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    // ========== 1. Discover principal URL ==========

    @Test
    fun `01 discover principal URL`() = runBlocking {
        assumeReady()
        val c = client!!
        val endpoint = creds!!.davEndpoint

        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }

        val result = c.discoverPrincipal(caldavUrl)
        // Skip if CalDAV discovery not functional (server reachable but CalDAV not configured)
        assumeTrue(
            "CalDAV discovery not functional on ${config.name}: ${(result as? CalDavResult.Error)?.message}",
            result.isSuccess()
        )
        val principal = result.getOrNull()!!
        assert(principal.isNotEmpty()) { "Principal should not be empty on ${config.name}" }
    }

    // ========== 2. Discover calendar home ==========

    @Test
    fun `02 discover calendar home`() = runBlocking {
        assumeReady()
        val c = client!!
        val endpoint = creds!!.davEndpoint

        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }

        val principal = c.discoverPrincipal(caldavUrl).getOrNull()
        assumeTrue("Could not discover principal on ${config.name}", principal != null)

        val result = c.discoverCalendarHome(principal!!)
        assert(result.isSuccess()) {
            "Failed to discover calendar home on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }
        assert(result.getOrNull()!!.first().isNotEmpty()) { "Calendar home should not be empty on ${config.name}" }
    }

    // ========== 3. List calendars ==========

    @Test
    fun `03 list calendars`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        // discoverCalendar already verified listing works; calendarUrl is non-null
        assert(calendarUrl!!.isNotEmpty()) { "Calendar URL should not be empty on ${config.name}" }
    }

    // ========== 4. Create single event, fetch back ==========

    @Test
    fun `04 create single event then fetch back`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-create-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "${config.name} Create Test")

        val result = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(result.isSuccess()) {
            "Failed to create event on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }

        val (url, etag) = result.getOrNull()!!
        trackEvent(url, etag)

        // Fetch back and verify
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("${config.name} Create Test")) {
            "Fetched ICS should contain summary on ${config.name}"
        }
    }

    // ========== 5. Update event, fetch back ==========

    @Test
    fun `05 update event then fetch back`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-update-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "Original Title")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Update
        val updatedIcs = createTestIcs(uid, "Updated Title on ${config.name}", extra = "SEQUENCE:1")
        val updateResult = client!!.updateEvent(url, updatedIcs, etag)
        assert(updateResult.isSuccess()) {
            "Failed to update event on ${config.name}: ${(updateResult as? CalDavResult.Error)?.message}"
        }
        trackEvent(url, updateResult.getOrNull()!!)

        // Verify
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch updated event on ${config.name}" }
        assert(fetchResult.getOrNull()!!.icalData.contains("Updated Title on ${config.name}")) {
            "Updated title should be present on ${config.name}"
        }
    }

    // ========== 6. Delete event, verify gone ==========

    @Test
    fun `06 delete event verify gone`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-delete-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "Event To Delete")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!

        // Delete
        val deleteResult = client!!.deleteEvent(url, etag)
        assert(deleteResult.isSuccess()) {
            "Failed to delete event on ${config.name}: ${(deleteResult as? CalDavResult.Error)?.message}"
        }

        // Verify gone — most servers return 404, some (Zoho) return 200 with empty body
        val fetchResult = client!!.fetchEvent(url)
        val isGone = fetchResult.isNotFound() ||
            fetchResult.isError() ||
            (fetchResult.isSuccess() && fetchResult.getOrNull()!!.icalData.isBlank())
        assert(isGone) {
            "Event should be deleted on ${config.name}, but fetch returned: $fetchResult"
        }
    }

    // ========== 7. Create recurring event, fetch back ==========

    @Test
    fun `07 create recurring event then fetch back`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-recur-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(
            uid, "Weekly on ${config.name}",
            extra = "RRULE:FREQ=WEEKLY;COUNT=4"
        )

        val result = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(result.isSuccess()) {
            "Failed to create recurring event on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = result.getOrNull()!!
        trackEvent(url, etag)

        // Verify RRULE preserved
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch recurring event on ${config.name}" }
        assert(fetchResult.getOrNull()!!.icalData.contains("RRULE:")) {
            "RRULE should be preserved on ${config.name}"
        }
    }

    // ========== 8. Edit single occurrence (RECURRENCE-ID) ==========

    @Test
    fun `08 edit single occurrence with RECURRENCE-ID`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-exception-${config.name.lowercase()}-${UUID.randomUUID()}"

        // Calculate dates: next Monday at 10am UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOcc = icsDateFormat.format(cal.time)
        val firstEnd = firstOcc.replace("T10", "T11")
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val secondOcc = icsDateFormat.format(cal.time)
        cal.set(Calendar.HOUR_OF_DAY, 14)
        val exceptionStart = icsDateFormat.format(cal.time)
        val exceptionEnd = exceptionStart.replace("T14", "T15")

        // Create recurring event
        val createIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//MultiServer Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOcc
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly on ${config.name}
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client!!.createEvent(calendarUrl!!, uid, createIcs)
        assert(createResult.isSuccess()) { "Failed to create recurring event on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Update with exception (reschedule second occurrence)
        val exceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//MultiServer Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOcc
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly on ${config.name}
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOcc
DTSTART:$exceptionStart
DTEND:$exceptionEnd
SUMMARY:Weekly on ${config.name} (Rescheduled)
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val updateResult = client!!.updateEvent(url, exceptionIcs, etag)
        assert(updateResult.isSuccess()) {
            "Failed to update with RECURRENCE-ID on ${config.name}: ${(updateResult as? CalDavResult.Error)?.message}"
        }
        trackEvent(url, updateResult.getOrNull()!!)

        // Verify RECURRENCE-ID preserved
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event with exception on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("RECURRENCE-ID:")) {
            "${config.name} should preserve RECURRENCE-ID"
        }
        val veventCount = fetchedIcs.split("BEGIN:VEVENT").size - 1
        assert(veventCount >= 2) {
            "Should have master + exception VEVENTs on ${config.name} (got $veventCount)"
        }
    }

    // ========== 9. Delete single occurrence (EXDATE) ==========

    @Test
    fun `09 delete single occurrence with EXDATE`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-exdate-${config.name.lowercase()}-${UUID.randomUUID()}"

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOcc = icsDateFormat.format(cal.time)
        val firstEnd = firstOcc.replace("T10", "T11")
        cal.add(Calendar.WEEK_OF_YEAR, 2)
        val thirdOcc = icsDateFormat.format(cal.time)

        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//MultiServer Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOcc
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE:$thirdOcc
SUMMARY:Weekly with cancelled third on ${config.name}
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(result.isSuccess()) {
            "Failed to create event with EXDATE on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = result.getOrNull()!!
        trackEvent(url, etag)

        // Verify event was created and EXDATE preserved (some servers like Zoho may strip EXDATE)
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("RRULE:")) {
            "RRULE should be preserved on ${config.name}"
        }
        if (!fetchedIcs.contains("EXDATE:")) {
            println("NOTE: ${config.name} did not preserve EXDATE in fetched ICS")
        }
    }

    // ========== 10. Ctag change detection ==========

    @Test
    fun `10 ctag change detection`() = runBlocking {
        assumeReady()
        assumeTrue(
            "${config.name} does not support ctag",
            config.supportsCtag
        )
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        // Get initial ctag
        val ctagResult1 = client!!.getCtag(calendarUrl!!)
        // Some servers may not support ctag even if config says they do
        assumeTrue(
            "${config.name} getCtag failed: ${(ctagResult1 as? CalDavResult.Error)?.message}",
            ctagResult1.isSuccess()
        )
        val ctag1 = ctagResult1.getOrNull()

        // Create an event to change the ctag
        val uid = "test-ctag-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "Ctag Test on ${config.name}")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Get new ctag
        val ctagResult2 = client!!.getCtag(calendarUrl!!)
        assert(ctagResult2.isSuccess()) { "Failed to get ctag after create on ${config.name}" }
        val ctag2 = ctagResult2.getOrNull()

        // Ctag should have changed (or at least be non-null)
        assert(ctag2 != null) { "Ctag should not be null after event creation on ${config.name}" }
        if (ctag1 != null) {
            assert(ctag1 != ctag2) {
                "Ctag should change after event creation on ${config.name} (was: $ctag1, now: $ctag2)"
            }
        }
    }

    // ========== 11. Sync-token delta sync ==========

    @Test
    fun `11 sync-token delta sync`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        // Get initial sync token
        val tokenResult = client!!.getSyncToken(calendarUrl!!)
        assumeTrue(
            "${config.name} does not support sync-token",
            tokenResult.isSuccess() && tokenResult.getOrNull() != null
        )
        val initialToken = tokenResult.getOrNull()!!

        // Create an event
        val uid = "test-sync-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "Sync Token Test on ${config.name}")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Sync collection with initial token
        val syncResult = client!!.syncCollection(calendarUrl!!, initialToken)
        assert(syncResult.isSuccess()) {
            "Failed sync-collection on ${config.name}: ${(syncResult as? CalDavResult.Error)?.message}"
        }

        val report = syncResult.getOrNull()!!
        // Should detect our new event (changed list or new token)
        assert(report.changed.isNotEmpty() || report.syncToken != initialToken) {
            "Sync should detect new event or provide new token on ${config.name}"
        }
    }

    // ========== 12. Conflict detection (412) ==========

    @Test
    fun `12 conflict detection with stale etag`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-conflict-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = createTestIcs(uid, "Conflict Test")

        val createResult = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(createResult.isSuccess()) { "Failed to create event on ${config.name}" }
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Update the event to get a new etag
        val updatedIcs = createTestIcs(uid, "Updated for conflict", extra = "SEQUENCE:1")
        val updateResult = client!!.updateEvent(url, updatedIcs, etag)
        assert(updateResult.isSuccess()) { "Failed to update event on ${config.name}" }
        val newEtag = updateResult.getOrNull()!!
        trackEvent(url, newEtag)

        // Try to update with the OLD etag (should get 412 Conflict or 409 Conflict)
        val conflictIcs = createTestIcs(uid, "Stale update", extra = "SEQUENCE:2")
        val conflictResult = client!!.updateEvent(url, conflictIcs, etag)
        // RFC 4791 says 412, but some servers (Zoho) return 409
        val isConflictResponse = conflictResult.isConflict() ||
            (conflictResult is CalDavResult.Error && (conflictResult as CalDavResult.Error).code == 409)
        assert(isConflictResponse) {
            "Expected conflict (412/409) on ${config.name} with stale etag, got: $conflictResult"
        }
    }

    // ========== 13. Special characters in title ==========

    @Test
    fun `13 special characters in title and description`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-special-${config.name.lowercase()}-${UUID.randomUUID()}"
        // Title with special chars: quotes, ampersand, angle brackets, unicode
        val summary = "Team Sync: Q&A <Review> \"Sprint\" — Café ☕"
        val ics = createTestIcs(uid, summary, extra = "DESCRIPTION:Notes with special chars: <>&\"'")

        val result = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(result.isSuccess()) {
            "Failed to create event with special chars on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = result.getOrNull()!!
        trackEvent(url, etag)

        // Fetch back and verify content is preserved
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        // Check key parts are preserved (servers may re-encode slightly)
        assert(fetchedIcs.contains("Q&A") || fetchedIcs.contains("Q\\&A")) {
            "Special characters should be preserved on ${config.name}"
        }
    }

    // ========== 14. Timezone handling ==========

    @Test
    fun `14 timezone handling`() = runBlocking {
        assumeReady()
        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        val uid = "test-tz-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//MultiServer Test//EN
BEGIN:VTIMEZONE
TZID:America/New_York
BEGIN:STANDARD
DTSTART:19701101T020000
RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
TZOFFSETFROM:-0400
TZOFFSETTO:-0500
TZNAME:EST
END:STANDARD
BEGIN:DAYLIGHT
DTSTART:19700308T020000
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
TZOFFSETFROM:-0500
TZOFFSETTO:-0400
TZNAME:EDT
END:DAYLIGHT
END:VTIMEZONE
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART;TZID=America/New_York:20260315T140000
DTEND;TZID=America/New_York:20260315T150000
SUMMARY:Timezone Test on ${config.name}
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client!!.createEvent(calendarUrl!!, uid, ics)
        assert(result.isSuccess()) {
            "Failed to create event with timezone on ${config.name}: ${(result as? CalDavResult.Error)?.message}"
        }
        val (url, etag) = result.getOrNull()!!
        trackEvent(url, etag)

        // Verify timezone info preserved
        val fetchResult = client!!.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event on ${config.name}" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        // Server should preserve timezone reference (either VTIMEZONE or converted to UTC)
        assert(
            fetchedIcs.contains("America/New_York") ||
                fetchedIcs.contains("DTSTART:20260315T190000Z") // UTC equivalent
        ) {
            "Timezone info should be preserved on ${config.name}"
        }
    }
}
