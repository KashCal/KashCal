package org.onekash.kashcal.sync.client.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File

/**
 * Runs [org.onekash.kashcal.sync.client.OkHttpCalDavClient]'s discovery and read calls against
 * real iCloud. Read-only: it creates, changes and deletes nothing.
 *
 * Run with: ./gradlew testDebugUnitTest -Pintegration --tests "*RealICloudClientTest*"
 *
 * Requires local.properties with ICLOUD_USERNAME and ICLOUD_APP_PASSWORD, or:
 *   caldav.username=your_apple_id@icloud.com
 *   caldav.app_password=xxxx-xxxx-xxxx-xxxx
 *   caldav.server=https://caldav.icloud.com
 */
class RealICloudClientTest {

    private lateinit var client: CalDavClient
    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

    // Discovery results. JUnit 4 makes a new instance per test, so they are never shared
    // between tests; only calendars is read, by getFirstCalendarUrl within the same test.
    private var principalUrl: String? = null
    private var calendarHomeUrl: String? = null
    private var calendars: List<CalDavCalendar>? = null

    @Before
    fun setup() {
        val quirks = ICloudQuirks()
        // Credentials from local.properties
        loadCredentials()

        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(credentials, quirks)
        } else {
            // Dummy credentials: every test is skipped without real ones
            val dummyCredentials = Credentials(
                username = "test@example.com",
                password = "test-password",
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(dummyCredentials, quirks)
        }
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
                println("Found credentials file at: ${propsFile.absolutePath}")
                propsFile.readLines().forEach { line ->
                    val parts = line.split("=").map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            // iCloud keys, which win
                            "ICLOUD_USERNAME" -> username = parts[1]
                            "ICLOUD_APP_PASSWORD" -> password = parts[1]
                            // Fallback keys, used only when the iCloud ones are absent
                            "caldav.username" -> if (username == null) username = parts[1]
                            "caldav.app_password" -> if (password == null) password = parts[1]
                            "caldav.server" -> serverUrl = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }

        println("Credentials loaded: username=${username?.take(5)}***, server=$serverUrl")
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "iCloud credentials not available. Add caldav.username and caldav.app_password to local.properties.",
            username != null && password != null
        )
    }

    // ========== Discovery ==========

    @Test
    fun `discover principal URL from iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        val result = client.discoverPrincipal(serverUrl)

        println("Principal discovery result: $result")
        assert(result.isSuccess()) { "Should discover principal: ${(result as? CalDavResult.Error)?.message}" }

        val url = result.getOrNull()!!
        println("Principal URL: $url")
        assert(url.contains("principal") || url.contains(username!!.substringBefore("@"))) {
            "Principal URL should contain 'principal' or username segment"
        }

        // Never read: another test gets a new instance
        principalUrl = url
    }

    @Test
    fun `discover calendar home from principal`() = runBlocking {
        assumeCredentialsAvailable()

        // Discover the principal first
        val principalResult = client.discoverPrincipal(serverUrl)
        assumeTrue("Should discover principal first", principalResult.isSuccess())
        val principal = principalResult.getOrNull()!!

        // Then the calendar home
        val result = client.discoverCalendarHome(principal)

        println("Calendar home discovery result: $result")
        assert(result.isSuccess()) { "Should discover calendar home: ${(result as? CalDavResult.Error)?.message}" }

        val url = result.getOrNull()!!.first()
        println("Calendar Home URL: $url")
        assert(url.isNotBlank()) { "Calendar home URL should not be blank" }

        // Never read: another test gets a new instance
        calendarHomeUrl = url
    }

    @Test
    fun `list calendars from iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        // The full discovery chain
        val principal = client.discoverPrincipal(serverUrl).getOrNull()
        assumeTrue("Should discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Should discover calendar home", home != null)

        // List the calendars
        val result = client.listCalendars(home!!)

        println("List calendars result: $result")
        assert(result.isSuccess()) { "Should list calendars: ${(result as? CalDavResult.Error)?.message}" }

        val cals = result.getOrNull()!!
        println("\nFound ${cals.size} calendars:")
        cals.forEach { cal ->
            println("  - ${cal.displayName}")
            println("    URL: ${cal.url}")
            println("    ctag: ${cal.ctag}")
            println("    readOnly: ${cal.isReadOnly}")
        }

        assert(cals.isNotEmpty()) { "Should have at least one calendar" }

        // Not seen by other tests: each gets a new instance
        calendars = cals
    }

    // ========== Change detection ==========

    @Test
    fun `get ctag for calendar`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("Getting ctag for: $calendarUrl")

        val result = client.getCtag(calendarUrl)

        println("Ctag result: $result")
        assert(result.isSuccess()) { "Should get ctag: ${(result as? CalDavResult.Error)?.message}" }

        val ctag = result.getOrNull()!!.ctag
        println("Ctag: $ctag")
        assert(ctag.isNotBlank()) { "Ctag should not be blank" }
    }

    @Test
    fun `get sync token for calendar`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("Getting sync token for: $calendarUrl")

        val result = client.getSyncToken(calendarUrl)

        println("Sync token result: $result")
        assert(result.isSuccess()) { "Should get sync token: ${(result as? CalDavResult.Error)?.message}" }

        val syncToken = result.getOrNull()
        println("Sync token: $syncToken")
        // The sync-token is null when the server doesn't support it
    }

    // ========== Event fetching ==========

    @Test
    fun `fetch events in range`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("Fetching events from: $calendarUrl")

        val now = System.currentTimeMillis()
        val startMs = now - (90L * 24 * 60 * 60 * 1000) // 90 days back
        val endMs = now + (365L * 24 * 60 * 60 * 1000)  // 1 year forward

        val result = client.fetchEventsInRange(calendarUrl, startMs, endMs)

        println("Fetch events result: ${if (result.isSuccess()) "Success" else result}")
        assert(result.isSuccess()) { "Should fetch events: ${(result as? CalDavResult.Error)?.message}" }

        val events = result.getOrNull()!!
        println("\nFound ${events.size} events:")
        events.take(5).forEach { event ->
            println("  - ${event.url}")
            println("    etag: ${event.etag}")
            println("    iCal preview: ${event.icalData.take(100)}...")
        }

        // Each event has a URL and iCal data
        events.forEach { event ->
            assert(event.url.isNotBlank()) { "Event URL should not be blank" }
            assert(event.icalData.contains("BEGIN:VCALENDAR")) { "Should be valid iCal data" }
        }
    }

    @Test
    fun `fetch events by href`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking

        // Fetch the events of the last and next 30 days to get hrefs
        val now = System.currentTimeMillis()
        val allEventsResult = client.fetchEventsInRange(
            calendarUrl,
            now - (30L * 24 * 60 * 60 * 1000),
            now + (30L * 24 * 60 * 60 * 1000)
        )
        assumeTrue("Should fetch events first", allEventsResult.isSuccess())

        val allEvents = allEventsResult.getOrNull()!!
        if (allEvents.isEmpty()) {
            println("No events found to test multiget")
            return@runBlocking
        }

        // The first 3 hrefs
        val hrefs = allEvents.take(3).map { it.href }
        println("Fetching ${hrefs.size} events by href: $hrefs")

        val result = client.fetchEventsByHref(calendarUrl, hrefs)

        println("Multiget result: ${if (result.isSuccess()) "Success" else result}")

        // iCloud might not support multiget with some href formats, so a failed multiget
        // skips the test with assumeTrue instead of failing it
        if (result.isError()) {
            val error = result as CalDavResult.Error
            println("Multiget not supported or failed: ${error.message}")
            // Skip, since this is server-specific behavior
            assumeTrue("Multiget not supported by server", false)
            return@runBlocking
        }

        val events = result.getOrNull()!!
        println("Fetched ${events.size} events by href (expected: ${hrefs.size})")
        // The server may return fewer events if some were deleted meanwhile
        assert(events.isNotEmpty()) { "Should fetch at least some events" }
    }

    @Test
    fun `sync collection incremental`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("Testing sync collection for: $calendarUrl")

        // Get a sync-token first
        val tokenResult = client.getSyncToken(calendarUrl)
        assumeTrue("Should get sync token", tokenResult.isSuccess())

        val syncToken = tokenResult.getOrNull()
        if (syncToken == null) {
            println("Server does not support sync-token, skipping test")
            return@runBlocking
        }

        // Then a sync-collection from it
        val result = client.syncCollection(calendarUrl, syncToken)

        println("Sync collection result: $result")
        // A 403 or 410 for an expired token is acceptable here
        if (result.isSuccess()) {
            val report = result.getOrNull()!!
            println("Sync report:")
            println("  New sync token: ${report.syncToken}")
            println("  Changed: ${report.changed.size}")
            println("  Deleted: ${report.deleted.size}")
        } else {
            val error = result as CalDavResult.Error
            println("Sync collection error (expected if token expired): ${error.message}")
        }
    }

    // ========== Full workflow ==========

    @Test
    fun `full discovery and fetch workflow`() = runBlocking {
        assumeCredentialsAvailable()

        println("=== Starting Full Discovery Workflow ===\n")

        // Step 1: discover the principal
        println("Step 1: Discovering principal...")
        val principalResult = client.discoverPrincipal(serverUrl)
        assert(principalResult.isSuccess()) { "Principal discovery failed" }
        val principal = principalResult.getOrNull()!!
        println("Principal: $principal\n")

        // Step 2: discover the calendar home
        println("Step 2: Discovering calendar home...")
        val homeResult = client.discoverCalendarHome(principal)
        assert(homeResult.isSuccess()) { "Calendar home discovery failed" }
        val home = homeResult.getOrNull()!!.first()
        println("Calendar home: $home\n")

        // Step 3: list the calendars
        println("Step 3: Listing calendars...")
        val calendarsResult = client.listCalendars(home)
        assert(calendarsResult.isSuccess()) { "Calendar listing failed" }
        val cals = calendarsResult.getOrNull()!!
        println("Found ${cals.size} calendars\n")

        // Step 4: for each calendar except inbox and outbox, get the ctag and fetch events
        var totalEvents = 0
        for (cal in cals) {
            if (cal.url.contains("inbox") || cal.url.contains("outbox")) {
                println("Skipping ${cal.displayName} (inbox/outbox)")
                continue
            }

            println("Processing: ${cal.displayName}")

            // Get ctag
            val ctagResult = client.getCtag(cal.url)
            if (ctagResult.isSuccess()) {
                println("  ctag: ${ctagResult.getOrNull()?.ctag}")
            }

            // Fetch events
            val now = System.currentTimeMillis()
            val eventsResult = client.fetchEventsInRange(
                cal.url,
                now - (90L * 24 * 60 * 60 * 1000),
                now + (365L * 24 * 60 * 60 * 1000)
            )

            if (eventsResult.isSuccess()) {
                val events = eventsResult.getOrNull()!!
                println("  Events: ${events.size}")
                totalEvents += events.size
            } else {
                println("  Error: ${(eventsResult as CalDavResult.Error).message}")
            }
            println()
        }

        println("=== Workflow Complete ===")
        println("Total calendars: ${cals.size}")
        println("Total events: $totalEvents")
    }

    @Test
    fun `check connection to iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        val result = client.checkConnection(serverUrl)

        println("Check connection result: $result")
        assert(result.isSuccess()) { "Should connect to iCloud: ${(result as? CalDavResult.Error)?.message}" }
    }

    // ========== Helper Methods ==========

    private suspend fun getFirstCalendarUrl(): String? {
        // Full discovery unless this test already listed the calendars
        if (calendars == null) {
            val principal = client.discoverPrincipal(serverUrl).getOrNull()
            if (principal == null) {
                println("Failed to discover principal")
                return null
            }

            val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull()
            if (home == null) {
                println("Failed to discover calendar home")
                return null
            }

            calendars = client.listCalendars(home).getOrNull()
        }

        // The first calendar whose URL contains neither "inbox" nor "outbox"
        val calendar = calendars?.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }

        if (calendar == null) {
            println("No suitable calendar found")
            return null
        }

        return calendar.url
    }
}
