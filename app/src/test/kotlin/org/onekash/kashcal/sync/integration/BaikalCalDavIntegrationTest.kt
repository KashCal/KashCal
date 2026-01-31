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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Integration test for Baikal CalDAV server workflows.
 *
 * Baikal is a popular self-hosted CalDAV/CardDAV server built on SabreDAV.
 * It uses /dav.php/ as the WebDAV endpoint.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*BaikalCalDavIntegrationTest*"
 *
 * Prerequisites:
 * - Baikal server running at localhost:8081
 * - Credentials in local.properties:
 *   BAIKAL_SERVER=http://localhost:8081
 *   BAIKAL_USERNAME=testuser
 *   BAIKAL_PASSWORD=testpass123
 *
 * Start Baikal:
 *   docker run -d --name baikal-caldav-test -p 8081:80 ckulka/baikal:nginx
 *   Then setup at http://localhost:8081/admin
 */
class BaikalCalDavIntegrationTest {

    private lateinit var client: CalDavClient
    private lateinit var clientFactory: OkHttpCalDavClientFactory
    private var baseServerUrl: String = "http://localhost:8081"  // Base URL for URL construction
    private var davEndpoint: String = "http://localhost:8081/dav.php/"  // DAV endpoint for requests
    private var username: String? = null
    private var password: String? = null

    // Test state
    private var calendarUrl: String? = null
    private var testEventUrl: String? = null
    private var testEventEtag: String? = null
    private val testUid = "test-baikal-${UUID.randomUUID()}@kashcal.test"

    // Date formatter for ICS
    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        loadCredentials()

        // Use DefaultQuirks for generic CalDAV (Baikal uses SabreDAV)
        val quirks = DefaultQuirks(baseServerUrl)
        clientFactory = OkHttpCalDavClientFactory()

        // Create client using factory pattern (replaces setCredentials)
        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = davEndpoint
            )
            client = clientFactory.createClient(credentials, quirks)
        } else {
            // Create a dummy client for cases where credentials aren't available
            val dummyCredentials = Credentials(
                username = "dummy",
                password = "dummy",
                serverUrl = davEndpoint
            )
            client = clientFactory.createClient(dummyCredentials, quirks)
        }
    }

    @After
    fun cleanup() = runBlocking {
        if (testEventUrl != null) {
            println("\n=== CLEANUP: Deleting test event ===")
            val result = client.deleteEvent(testEventUrl!!, testEventEtag ?: "")
            println("Delete result: ${if (result.isSuccess() || result.isNotFound()) "Success" else result}")
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
                propsFile.readLines().forEach { line ->
                    val parts = line.split("=").map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "BAIKAL_SERVER" -> {
                                baseServerUrl = parts[1]
                                davEndpoint = parts[1] + "/dav.php/"
                            }
                            "BAIKAL_USERNAME" -> username = parts[1]
                            "BAIKAL_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    private fun assumeServerAvailable() {
        try {
            val url = URL(davEndpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val responseCode = connection.responseCode
            assumeTrue("Baikal server not available (code: $responseCode)", responseCode in 200..299 || responseCode == 401)
        } catch (e: Exception) {
            assumeTrue("Baikal server not reachable: ${e.message}", false)
        }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "Baikal credentials not available in local.properties",
            username != null && password != null
        )
    }

    private suspend fun discoverCalendar(): String? {
        val principal = client.discoverPrincipal(davEndpoint).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    // ========== DISCOVERY TESTS ==========

    @Test
    fun `discover principal URL on Baikal`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val result = client.discoverPrincipal(davEndpoint)
        assert(result.isSuccess()) { "Failed to discover principal: ${(result as? CalDavResult.Error)?.message}" }

        val principal = result.getOrNull()!!
        println("Baikal principal URL: $principal")
        assert(principal.contains("principals")) { "Principal should contain 'principals'" }
    }

    @Test
    fun `discover calendar home on Baikal`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val principal = client.discoverPrincipal(davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val result = client.discoverCalendarHome(principal!!)
        assert(result.isSuccess()) { "Failed to discover calendar home: ${(result as? CalDavResult.Error)?.message}" }

        val home = result.getOrNull()!!
        println("Baikal calendar home: $home")
        assert(home.isNotEmpty()) { "Calendar home should not be empty" }
    }

    @Test
    fun `list calendars on Baikal`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val principal = client.discoverPrincipal(davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val result = client.listCalendars(home!!)
        assert(result.isSuccess()) { "Failed to list calendars: ${(result as? CalDavResult.Error)?.message}" }

        val calendars = result.getOrNull()!!
        println("Found ${calendars.size} calendar(s) on Baikal:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}: ${cal.url} (readOnly=${cal.isReadOnly})")
        }
        assert(calendars.isNotEmpty()) { "Should have at least one calendar" }
    }

    // ========== CRUD TESTS ==========

    @Test
    fun `create and fetch event on Baikal`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Baikal", calendarUrl != null)

        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Baikal Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Baikal Test Event
DESCRIPTION:Created by integration test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, testUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        println("Created event on Baikal: $url (etag: $etag)")

        // Verify by fetching
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS from Baikal:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("SUMMARY:Baikal Test Event"))
    }

    // ========== RECURRING EVENT WITH EXCEPTION TEST ==========

    @Test
    fun `recurring event with exception on Baikal`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Baikal", calendarUrl != null)

        println("\n" + "=".repeat(60))
        println("BAIKAL RECURRING EVENT EXCEPTION WORKFLOW")
        println("=".repeat(60) + "\n")

        // Calculate dates
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOccurrenceStr = icsDateFormat.format(cal.time)

        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val secondOccurrenceStr = icsDateFormat.format(cal.time)

        cal.set(Calendar.HOUR_OF_DAY, 14)
        val exceptionTimeStr = icsDateFormat.format(cal.time)

        // Step 1: Create recurring event
        println("Step 1: Creating recurring event...")
        val createIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Baikal Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Baikal Weekly Meeting
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, testUid, createIcs)
        assert(createResult.isSuccess()) { "Failed to create recurring event" }

        val (url, etag) = createResult.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag
        println("Created: $url")

        // Step 2: Add exception
        println("\nStep 2: Adding exception (reschedule second occurrence)...")
        val exceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Baikal Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Baikal Weekly Meeting
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceStr
DTSTART:$exceptionTimeStr
DTEND:${exceptionTimeStr.replace("T14", "T15")}
SUMMARY:Baikal Weekly Meeting (Rescheduled)
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val updateResult = client.updateEvent(url, exceptionIcs, etag)
        assert(updateResult.isSuccess()) { "Failed to update with exception: ${(updateResult as? CalDavResult.Error)?.message}" }
        testEventEtag = updateResult.getOrNull()!!

        // Step 3: Verify exception is preserved
        println("\nStep 3: Verifying exception preservation...")
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event with exception" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS from Baikal:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("RECURRENCE-ID:")) { "Baikal should preserve RECURRENCE-ID" }

        val veventCount = fetchedIcs.split("BEGIN:VEVENT").size - 1
        println("\nVEVENT count: $veventCount (expected: 2)")
        assert(veventCount >= 2) { "Should have master + exception VEVENTs" }

        println("\n" + "=".repeat(60))
        println("BAIKAL WORKFLOW TEST COMPLETE")
        println("=".repeat(60))
    }

    // ========== SYNC TOKEN TEST ==========

    @Test
    fun `get sync token from Baikal`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Baikal", calendarUrl != null)

        val result = client.getSyncToken(calendarUrl!!)
        assert(result.isSuccess()) { "Failed to get sync token: ${(result as? CalDavResult.Error)?.message}" }

        val token = result.getOrNull()!!
        println("Baikal sync token: $token")
        assert(token.isNotEmpty()) { "Sync token should not be empty" }
    }
}
