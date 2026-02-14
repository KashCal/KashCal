package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Integration test that reproduces the 412 Conflict scenario on real iCloud.
 *
 * Demonstrates that when an event's etag changes on the server (due to another
 * user editing a shared calendar, or iCloud internal metadata updates), a local
 * push with the stale etag gets 412 Precondition Failed.
 *
 * This is the root cause of "sometimes shared calendar events don't push":
 * 1. Pull syncs event with etag "A"
 * 2. Server etag changes to "B" (another user's edit or iCloud housekeeping)
 * 3. Local edit → push with If-Match: "A" → 412
 * 4. SERVER_WINS conflict resolution silently discards local changes
 *
 * Also tests:
 * - Whether fetchEtag can retrieve fresh etag after 412
 * - Whether retry with fresh etag succeeds (the proposed fix)
 * - Whether shared calendars report isReadOnly correctly
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*RealICloud412ConflictTest*"
 *
 * Requires: local.properties with iCloud credentials
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealICloud412ConflictTest {

    private lateinit var client: CalDavClient
    private var username: String? = null
    private var password: String? = null
    private val serverUrl = "https://caldav.icloud.com"
    private val factory = OkHttpCalDavClientFactory()

    companion object {
        private var testCalendarUrl: String? = null
        private var testEventUrl: String? = null
        private var testEventUid: String? = null
        private var latestEtag: String? = null
        private var sharedCalendars: List<SharedCalInfo> = emptyList()

        // Credentials cached for @AfterClass cleanup
        private var cachedUsername: String? = null
        private var cachedPassword: String? = null

        data class SharedCalInfo(
            val url: String,
            val displayName: String,
            val isReadOnly: Boolean
        )

        /**
         * Safety net cleanup: if test05 doesn't run (e.g. @Before throws,
         * class construction fails), this ensures the test event doesn't
         * leak on iCloud.
         */
        @AfterClass
        @JvmStatic
        fun cleanupSafetyNet() {
            val url = testEventUrl ?: return
            val etag = latestEtag ?: return
            val user = cachedUsername ?: return
            val pass = cachedPassword ?: return

            println("\n[AfterClass] Safety net cleanup: deleting test event $url")
            try {
                val quirks = ICloudQuirks()
                val credentials = Credentials(
                    username = user,
                    password = pass,
                    serverUrl = "https://caldav.icloud.com"
                )
                val cleanupClient = OkHttpCalDavClientFactory().createClient(credentials, quirks)
                runBlocking {
                    val result = cleanupClient.deleteEvent(url, etag)
                    println("[AfterClass] Cleanup result: ${if (result.isSuccess()) "OK" else "Failed"}")
                }
            } catch (e: Exception) {
                println("[AfterClass] Cleanup failed: ${e.message}")
            }
            testEventUrl = null
            latestEtag = null
        }
    }

    @Before
    fun setup() {
        val quirks = ICloudQuirks()
        loadCredentials()

        val credentials = Credentials(
            username = username ?: "",
            password = password ?: "",
            serverUrl = serverUrl
        )
        client = factory.createClient(credentials, quirks)
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )
        for (path in possiblePaths) {
            val propsFile = File(path)
            if (propsFile.exists()) {
                propsFile.readLines().forEach { line ->
                    if (line.startsWith("#")) return@forEach
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "caldav.username" -> username = parts[1]
                            "caldav.app_password" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
        // Cache for @AfterClass cleanup safety net
        cachedUsername = username
        cachedPassword = password
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue("iCloud credentials not available", username != null && password != null)
    }

    private fun formatUtcDate(millis: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }

    // ========== Test 1: Discover calendars and check shared calendar permissions ==========

    @Test
    fun `test01 discover calendars and check shared calendar permissions`() = runBlocking {
        assumeCredentialsAvailable()

        println("\n========== TEST 1: Discover & Check Shared Calendar Permissions ==========")

        val principalResult = client.discoverPrincipal(serverUrl)
        assert(principalResult.isSuccess()) { "Failed to discover principal: $principalResult" }
        val principal = principalResult.getOrNull()!!

        val homeResult = client.discoverCalendarHome(principal)
        assert(homeResult.isSuccess()) { "Failed to discover calendar home" }
        val home = homeResult.getOrNull()!!.first()

        val calendarsResult = client.listCalendars(home)
        assert(calendarsResult.isSuccess()) { "Failed to list calendars" }
        val calendars = calendarsResult.getOrNull()!!

        println("Found ${calendars.size} calendars:")
        val shared = mutableListOf<SharedCalInfo>()
        for (cal in calendars) {
            val isShared = !cal.url.contains(home.substringAfter("://").substringBefore("/").let {
                // Check if calendar URL contains a different user's DSID
                // Shared calendars on iCloud use the owner's DSID, not the sharee's
                ""  // Can't easily determine this from URL alone
            })
            println("  - ${cal.displayName}")
            println("    URL: ${cal.url}")
            println("    isReadOnly: ${cal.isReadOnly}")
            println("    ctag: ${cal.ctag?.take(20)}...")

            if (cal.isReadOnly) {
                shared.add(SharedCalInfo(cal.url, cal.displayName ?: "Unknown", true))
            }
        }

        sharedCalendars = shared
        println("\nRead-only calendars (likely shared): ${shared.size}")
        shared.forEach { println("  - ${it.displayName} (readOnly=${it.isReadOnly})") }

        // Select first WRITABLE calendar for testing
        val testCalendar = calendars.firstOrNull { !it.isReadOnly && !it.url.contains("webcal") }
        assert(testCalendar != null) { "No writable calendar found" }
        testCalendarUrl = testCalendar!!.url
        println("\nSelected writable calendar for test: ${testCalendar.displayName}")
    }

    // ========== Test 2: Create test event ==========

    @Test
    fun `test02 create test event on iCloud`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Calendar not discovered", testCalendarUrl != null)

        println("\n========== TEST 2: Create Test Event ==========")

        testEventUid = "kashcal-412-test-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//412 Conflict Test//EN
            BEGIN:VEVENT
            UID:${testEventUid}
            DTSTAMP:${formatUtcDate(now)}
            DTSTART:${formatUtcDate(now + 3600000)}
            DTEND:${formatUtcDate(now + 7200000)}
            SUMMARY:412 Conflict Test Event
            DESCRIPTION:Testing etag conflict handling
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(testCalendarUrl!!, testEventUid!!, ics)
        assert(result.isSuccess()) { "Failed to create event: $result" }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        latestEtag = etag

        println("Created event: $testEventUid")
        println("  URL: $testEventUrl")
        println("  ETag: $latestEtag")
    }

    // ========== Test 3: Reproduce 412 with stale etag ==========

    @Test
    fun `test03 reproduce 412 conflict with stale etag`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Event not created", testEventUrl != null && latestEtag != null)

        println("\n========== TEST 3: Reproduce 412 Conflict ==========")

        val now = System.currentTimeMillis()

        // Step 1: Update event (simulates another user's edit, changes the etag)
        val update1Ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//412 Conflict Test//EN
            BEGIN:VEVENT
            UID:${testEventUid}
            DTSTAMP:${formatUtcDate(now)}
            DTSTART:${formatUtcDate(now + 3600000)}
            DTEND:${formatUtcDate(now + 7200000)}
            SUMMARY:412 Conflict Test - Server Version
            DESCRIPTION:Modified by another user (simulated)
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val staleEtag = latestEtag!!
        println("Step 1: Update event with current etag (simulating another user's edit)")
        println("  Using etag: $staleEtag")

        val update1Result = client.updateEvent(testEventUrl!!, update1Ics, staleEtag)
        assert(update1Result.isSuccess()) { "First update failed: $update1Result" }

        val newServerEtag = update1Result.getOrNull()!!
        latestEtag = newServerEtag
        println("  Server now has etag: $newServerEtag")
        println("  Old etag (stale): $staleEtag")

        // Step 2: Try to push local edit with the STALE etag (simulates the bug)
        val localEditIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//412 Conflict Test//EN
            BEGIN:VEVENT
            UID:${testEventUid}
            DTSTAMP:${formatUtcDate(now + 1000)}
            DTSTART:${formatUtcDate(now + 3600000)}
            DTEND:${formatUtcDate(now + 7200000)}
            SUMMARY:412 Conflict Test - LOCAL User Edit
            DESCRIPTION:This is the local user change that should push
            SEQUENCE:2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        println("\nStep 2: Push local edit with STALE etag (reproducing the bug)")
        println("  Using stale etag: $staleEtag")
        println("  Server has etag: $newServerEtag")

        val conflictResult = client.updateEvent(testEventUrl!!, localEditIcs, staleEtag)

        println("\n  Result: ${if (conflictResult.isConflict()) "412 CONFLICT (BUG REPRODUCED)" else "Unexpected: $conflictResult"}")

        // VERIFY: We get 412
        assert(conflictResult.isConflict()) {
            "Expected 412 Conflict but got: $conflictResult"
        }

        println("\n  *** 412 Confirmed ***")
        println("  In production, SERVER_WINS would now discard the local edit silently.")
        println("  The user's 'LOCAL User Edit' title change is lost.")
    }

    // ========== Test 4: Demonstrate the fix - fetch fresh etag and retry ==========

    @Test
    fun `test04 fix - fetch fresh etag and retry succeeds`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Event not created", testEventUrl != null && latestEtag != null)

        println("\n========== TEST 4: Proposed Fix - Fetch Fresh ETag & Retry ==========")

        val now = System.currentTimeMillis()

        // Step 1: Fetch fresh etag from server
        println("Step 1: Fetch fresh etag via PROPFIND")
        val freshEtagResult = client.fetchEtag(testEventUrl!!)
        assert(freshEtagResult.isSuccess()) { "Failed to fetch etag: $freshEtagResult" }

        val freshEtag = freshEtagResult.getOrNull()
        println("  Fresh etag: $freshEtag")
        println("  Known latest: $latestEtag")

        assert(freshEtag != null) { "Server returned null etag" }

        // Step 2: Retry push with fresh etag
        val retryIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//412 Conflict Test//EN
            BEGIN:VEVENT
            UID:${testEventUid}
            DTSTAMP:${formatUtcDate(now)}
            DTSTART:${formatUtcDate(now + 3600000)}
            DTEND:${formatUtcDate(now + 7200000)}
            SUMMARY:412 Conflict Test - LOCAL Edit (RETRY with fresh etag)
            DESCRIPTION:This push should succeed with the fresh etag
            SEQUENCE:3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        println("\nStep 2: Retry PUT with fresh etag")
        val retryResult = client.updateEvent(testEventUrl!!, retryIcs, freshEtag!!)

        println("  Result: ${if (retryResult.isSuccess()) "SUCCESS (FIX WORKS)" else "Failed: $retryResult"}")

        assert(retryResult.isSuccess()) {
            "Retry with fresh etag should succeed but got: $retryResult"
        }

        latestEtag = retryResult.getOrNull()!!
        println("  New etag after successful retry: $latestEtag")

        // Step 3: Verify the pushed content is on the server
        println("\nStep 3: Verify server has our content")
        val fetchResult = client.fetchEvent(testEventUrl!!)
        assert(fetchResult.isSuccess()) { "Failed to fetch event: $fetchResult" }

        val serverEvent = (fetchResult as CalDavResult.Success).data
        val hasOurTitle = serverEvent.icalData.contains("LOCAL Edit (RETRY with fresh etag)")
        println("  Server has our title: $hasOurTitle")
        assert(hasOurTitle) { "Server should have our pushed content" }

        println("\n  *** Fix verified: fetch fresh etag + retry = local changes pushed successfully ***")
    }

    // ========== Test 5: Cleanup ==========

    @Test
    fun `test05 cleanup test event`() = runBlocking {
        assumeCredentialsAvailable()
        if (testEventUrl == null || latestEtag == null) {
            println("Nothing to clean up")
            return@runBlocking
        }

        println("\n========== TEST 5: Cleanup ==========")

        val deleteResult = client.deleteEvent(testEventUrl!!, latestEtag!!)
        println("Delete result: ${if (deleteResult.isSuccess()) "OK" else "Failed: $deleteResult"}")

        testEventUrl = null
        testEventUid = null
        latestEtag = null
    }
}
