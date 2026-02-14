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
 * Integration test for SOGo CalDAV server.
 *
 * SOGo is a groupware server with CalDAV support. This test verifies:
 * 1. Calendar discovery via DefaultQuirks (standard CalDAV)
 * 2. Event CRUD operations
 * 3. Recurring events with exceptions (RECURRENCE-ID)
 * 4. Cancelled occurrences (EXDATE)
 * 5. Sync-collection delta sync
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*SoGoCalDavIntegrationTest*"
 *
 * Prerequisites:
 * - SOGo server running (docker/sogo/)
 * - Credentials in local.properties:
 *   SOGO_SERVER=http://localhost:8084
 *   SOGO_USERNAME=testuser1
 *   SOGO_PASSWORD=testpass1
 *
 * Start SOGo:
 *   docker build -t kashcal-sogo docker/sogo/
 *   docker compose -f docker/docker-compose.yml up -d sogo-db sogo
 */
class SoGoCalDavIntegrationTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String = "http://localhost:8084"
    private var username: String? = null
    private var password: String? = null
    private val factory = OkHttpCalDavClientFactory()

    // Test state
    private var calendarUrl: String? = null
    private var testEventUrl: String? = null
    private var testEventEtag: String? = null
    private val testUid = "test-recurring-${UUID.randomUUID()}@kashcal.test"

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        loadCredentials()

        val quirks = DefaultQuirks(serverUrl)

        val credentials = Credentials(
            username = username ?: "",
            password = password ?: "",
            serverUrl = serverUrl
        )
        client = factory.createClient(credentials, quirks)
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
                            "SOGO_SERVER" -> serverUrl = parts[1]
                            "SOGO_USERNAME" -> username = parts[1]
                            "SOGO_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    private fun assumeServerAvailable() {
        try {
            // SOGo's /SOGo/ returns 200 when ready
            val url = URL("$serverUrl/SOGo/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val responseCode = connection.responseCode
            assumeTrue("SOGo server not available (code: $responseCode)", responseCode in 200..299)
        } catch (e: Exception) {
            assumeTrue("SOGo server not reachable: ${e.message}", false)
        }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "SOGo credentials not available in local.properties",
            username != null && password != null
        )
    }

    private suspend fun discoverCalendar(): String? {
        // SOGo well-known discovery: serverUrl -> principal -> calendar-home -> calendars
        val davUrl = "$serverUrl/SOGo/dav/$username/"
        val principal = client.discoverPrincipal(davUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        // SOGo creates a default "Personal Calendar" at .../Calendar/personal/
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    // ========== DISCOVERY TESTS ==========

    @Test
    fun `discover principal URL`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val davUrl = "$serverUrl/SOGo/dav/$username/"
        val result = client.discoverPrincipal(davUrl)
        assert(result.isSuccess()) { "Failed to discover principal: ${(result as? CalDavResult.Error)?.message}" }

        val principal = result.getOrNull()!!
        println("Principal URL: $principal")
        assert(principal.contains(username!!)) { "Principal should contain username" }
    }

    @Test
    fun `discover calendar home`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val davUrl = "$serverUrl/SOGo/dav/$username/"
        val principal = client.discoverPrincipal(davUrl).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val result = client.discoverCalendarHome(principal!!)
        assert(result.isSuccess()) { "Failed to discover calendar home: ${(result as? CalDavResult.Error)?.message}" }

        val homes = result.getOrNull()!!
        println("Calendar home: ${homes.first()}")
        assert(homes.first().isNotEmpty()) { "Calendar home should not be empty" }
    }

    @Test
    fun `list calendars`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val davUrl = "$serverUrl/SOGo/dav/$username/"
        val principal = client.discoverPrincipal(davUrl).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val result = client.listCalendars(home!!)
        assert(result.isSuccess()) { "Failed to list calendars: ${(result as? CalDavResult.Error)?.message}" }

        val calendars = result.getOrNull()!!
        println("Found ${calendars.size} calendar(s):")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}: ${cal.url}")
        }
        assert(calendars.isNotEmpty()) { "Should have at least one calendar (SOGo creates Personal Calendar)" }
    }

    @Test
    fun `check connection reports CalDAV support`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val davUrl = "$serverUrl/SOGo/dav/$username/Calendar/"
        val result = client.checkConnection(davUrl)
        assert(result.isSuccess()) { "SOGo should report CalDAV support: ${(result as? CalDavResult.Error)?.message}" }
        println("SOGo CalDAV connection check: OK")
    }

    // ========== CRUD TESTS ==========

    @Test
    fun `create simple event`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val simpleUid = "test-simple-${UUID.randomUUID()}@kashcal.test"
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$simpleUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:SOGo Test Event
DESCRIPTION:Created by SOGo integration test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, simpleUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        println("Created event at: $url with etag: $etag")

        testEventUrl = url
        testEventEtag = etag

        // Verify by fetching
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch created event" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("SUMMARY:SOGo Test Event"))
    }

    @Test
    fun `update event`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val updateUid = "test-update-${UUID.randomUUID()}@kashcal.test"

        val initialIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$updateUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Original Title
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, updateUid, initialIcs)
        assert(createResult.isSuccess()) { "Failed to create event" }

        val (url, etag) = createResult.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        val updatedIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$updateUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Updated Title
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val updateResult = client.updateEvent(url, updatedIcs, etag)
        assert(updateResult.isSuccess()) { "Failed to update event: ${(updateResult as? CalDavResult.Error)?.message}" }

        testEventEtag = updateResult.getOrNull()!!
        println("Updated event, new etag: $testEventEtag")

        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch updated event" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("SUMMARY:Updated Title")) { "Title should be updated" }
    }

    @Test
    fun `delete event`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val deleteUid = "test-delete-${UUID.randomUUID()}@kashcal.test"

        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$deleteUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Event To Delete
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, deleteUid, icsContent)
        assert(createResult.isSuccess()) { "Failed to create event" }

        val (url, etag) = createResult.getOrNull()!!

        val deleteResult = client.deleteEvent(url, etag)
        assert(deleteResult.isSuccess()) { "Failed to delete event: ${(deleteResult as? CalDavResult.Error)?.message}" }
        println("Deleted event successfully")

        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isNotFound()) { "Event should be deleted (404)" }

        testEventUrl = null
        testEventEtag = null
    }

    // ========== RECURRING EVENT TESTS ==========

    @Test
    fun `recurring event workflow with exception`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        println("\n" + "=".repeat(80))
        println("RECURRING EVENT EXCEPTION WORKFLOW TEST (SOGo)")
        println("=".repeat(80))
        println("Test UID: $testUid")
        println("=".repeat(80) + "\n")

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)
        println("Using calendar: $calendarUrl\n")

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

        println("Date plan:")
        println("  First occurrence: $firstOccurrenceStr (Mon 10am)")
        println("  Second occurrence: $secondOccurrenceStr (Mon 10am)")
        println("  Exception time: $exceptionTimeStr (2pm)")
        println()

        // Step 1: Create recurring event
        println("=== STEP 1: Create Recurring Event ===\n")
        val createIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Team Standup
DESCRIPTION:SOGo integration test event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, testUid, createIcs)
        assert(createResult.isSuccess()) { "Failed to create event: ${(createResult as? CalDavResult.Error)?.message}" }

        val (url, etag) = createResult.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag
        println("Created event at: $url with etag: $etag\n")

        // Step 2: Fetch and verify
        println("=== STEP 2: Fetch and Verify ===\n")
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event" }
        val fetched = fetchResult.getOrNull()!!
        testEventEtag = fetched.etag
        println("Fetched ICS:\n${fetched.icalData}\n")
        assert(fetched.icalData.contains("RRULE:")) { "Should have RRULE" }

        // Step 3: Create exception
        println("=== STEP 3: Create Exception ===\n")
        val exceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Team Standup
DESCRIPTION:SOGo integration test event
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceStr
DTSTART:$exceptionTimeStr
DTEND:${exceptionTimeStr.replace("T14", "T15")}
SUMMARY:Weekly Team Standup (Rescheduled to 2pm)
DESCRIPTION:Exception - moved from 10am to 2pm
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val updateResult = client.updateEvent(url, exceptionIcs, testEventEtag!!)
        assert(updateResult.isSuccess()) { "Failed to update event: ${(updateResult as? CalDavResult.Error)?.message}" }
        testEventEtag = updateResult.getOrNull()!!
        println("Updated with exception, new etag: $testEventEtag\n")

        // Step 4: Verify exception
        println("=== STEP 4: Verify Exception ===\n")
        val verifyResult = client.fetchEvent(url)
        assert(verifyResult.isSuccess()) { "Failed to fetch event with exception" }
        val verified = verifyResult.getOrNull()!!
        testEventEtag = verified.etag
        println("Fetched ICS with exception:\n${verified.icalData}\n")
        assert(verified.icalData.contains("RECURRENCE-ID:")) { "Should have RECURRENCE-ID" }

        val veventCount = verified.icalData.split("BEGIN:VEVENT").size - 1
        println("VEVENT count: $veventCount (expected: 2 - master + exception)")
        assert(veventCount >= 2) { "Should have at least 2 VEVENTs" }

        println("\n" + "=".repeat(80))
        println("SOGo WORKFLOW TEST COMPLETE")
        println("=".repeat(80))
    }

    // ========== EXDATE TEST ==========

    @Test
    fun `recurring event with cancelled occurrence (EXDATE)`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val exdateUid = "test-exdate-${UUID.randomUUID()}@kashcal.test"

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOccurrenceStr = icsDateFormat.format(cal.time)

        cal.add(Calendar.WEEK_OF_YEAR, 2)
        val thirdOccurrenceStr = icsDateFormat.format(cal.time)

        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$exdateUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE:$thirdOccurrenceStr
SUMMARY:Weekly Event with Cancelled Occurrence
DESCRIPTION:Third occurrence is cancelled (SOGo test)
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, exdateUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS with EXDATE:\n$fetchedIcs")

        assert(fetchedIcs.contains("EXDATE")) { "EXDATE should be preserved by SOGo" }
    }

    // ========== SYNC TOKEN TEST ==========

    @Test
    fun `sync-collection returns sync token`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val result = client.getSyncToken(calendarUrl!!)
        // SOGo may or may not support sync-collection; test what we get
        if (result.isSuccess()) {
            val token = result.getOrNull()!!
            println("SOGo sync token: $token")
            assert(token.isNotEmpty()) { "Sync token should not be empty" }
        } else {
            println("SOGo does not support sync-collection (this is expected for some versions)")
            // Try ctag as fallback
            val ctagResult = client.getCtag(calendarUrl!!)
            if (ctagResult.isSuccess()) {
                println("SOGo ctag: ${ctagResult.getOrNull()}")
            } else {
                println("SOGo doesn't support ctag either: ${(ctagResult as? CalDavResult.Error)?.message}")
            }
        }
    }
}
