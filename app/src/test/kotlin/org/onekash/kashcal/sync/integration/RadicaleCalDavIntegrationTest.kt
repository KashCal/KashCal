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
 * Integration test for generic CalDAV (Radicale) server workflows.
 *
 * This test verifies:
 * 1. Calendar discovery via DefaultQuirks
 * 2. Event CRUD operations
 * 3. Recurring events with exceptions (RECURRENCE-ID)
 * 4. Cancelled occurrences (EXDATE)
 * 5. Sync-collection delta sync
 *
 * Run: ./gradlew testDebugUnitTest --tests "*RadicaleCalDavIntegrationTest*"
 *
 * Prerequisites:
 * - Radicale server running at localhost:5232
 * - Credentials in local.properties:
 *   RADICALE_SERVER=http://localhost:5232
 *   RADICALE_USERNAME=testuser
 *   RADICALE_PASSWORD=testpass123
 *
 * Start Radicale:
 *   docker run -d --name radicale-caldav-test -p 5232:5232 tomsquest/docker-radicale
 */
class RadicaleCalDavIntegrationTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String = "http://localhost:5232"
    private var username: String? = null
    private var password: String? = null
    private val factory = OkHttpCalDavClientFactory()

    // Test state
    private var calendarUrl: String? = null
    private var testEventUrl: String? = null
    private var testEventEtag: String? = null
    private val testUid = "test-recurring-${UUID.randomUUID()}@kashcal.test"

    // Date formatter for ICS
    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        loadCredentials()

        // Use DefaultQuirks for generic CalDAV
        val quirks = DefaultQuirks(serverUrl)

        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = serverUrl
            )
            client = factory.createClient(credentials, quirks)
        } else {
            // Create a minimal client for discovery tests that don't require auth
            val credentials = Credentials(
                username = "",
                password = "",
                serverUrl = serverUrl
            )
            client = factory.createClient(credentials, quirks)
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
                            "RADICALE_SERVER" -> serverUrl = parts[1]
                            "RADICALE_USERNAME" -> username = parts[1]
                            "RADICALE_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    private fun assumeServerAvailable() {
        try {
            val url = URL(serverUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val responseCode = connection.responseCode
            assumeTrue("Radicale server not available (code: $responseCode)", responseCode in 200..299 || responseCode == 401)
        } catch (e: Exception) {
            assumeTrue("Radicale server not reachable: ${e.message}", false)
        }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "Radicale credentials not available in local.properties",
            username != null && password != null
        )
    }

    private suspend fun discoverCalendar(): String? {
        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        // Find first calendar (not inbox/outbox)
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    // ========== DISCOVERY TESTS ==========

    @Test
    fun `discover principal URL`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val result = client.discoverPrincipal(serverUrl)
        assert(result.isSuccess()) { "Failed to discover principal: ${(result as? CalDavResult.Error)?.message}" }

        val principal = result.getOrNull()!!
        println("Principal URL: $principal")
        assert(principal.contains(username!!)) { "Principal should contain username" }
    }

    @Test
    fun `discover calendar home`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val principal = client.discoverPrincipal(serverUrl).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val result = client.discoverCalendarHome(principal!!)
        assert(result.isSuccess()) { "Failed to discover calendar home: ${(result as? CalDavResult.Error)?.message}" }

        val home = result.getOrNull()!!
        println("Calendar home: $home")
        assert(home.isNotEmpty()) { "Calendar home should not be empty" }
    }

    @Test
    fun `list calendars`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val principal = client.discoverPrincipal(serverUrl).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val result = client.listCalendars(home!!)
        assert(result.isSuccess()) { "Failed to list calendars: ${(result as? CalDavResult.Error)?.message}" }

        val calendars = result.getOrNull()!!
        println("Found ${calendars.size} calendar(s):")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}: ${cal.url}")
        }
        assert(calendars.isNotEmpty()) { "Should have at least one calendar" }
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
SUMMARY:Simple Test Event
DESCRIPTION:Created by integration test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, simpleUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        println("Created event at: $url with etag: $etag")

        // Track for cleanup
        testEventUrl = url
        testEventEtag = etag

        // Verify by fetching
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch created event" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("SUMMARY:Simple Test Event"))
    }

    @Test
    fun `update event`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val updateUid = "test-update-${UUID.randomUUID()}@kashcal.test"

        // Create initial event
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

        // Update event
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

        // Verify update
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch updated event" }
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("SUMMARY:Updated Title")) { "Title should be updated" }
        assert(fetchedIcs.contains("SEQUENCE:1")) { "Sequence should be 1" }
    }

    @Test
    fun `delete event`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val deleteUid = "test-delete-${UUID.randomUUID()}@kashcal.test"

        // Create event
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

        // Delete event
        val deleteResult = client.deleteEvent(url, etag)
        assert(deleteResult.isSuccess()) { "Failed to delete event: ${(deleteResult as? CalDavResult.Error)?.message}" }
        println("Deleted event successfully")

        // Verify deletion (should get 404)
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isNotFound()) { "Event should be deleted (404)" }

        // Clear tracking since we deleted it ourselves
        testEventUrl = null
        testEventEtag = null
    }

    // ========== RECURRING EVENT TESTS ==========

    @Test
    fun `recurring event workflow with exception`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        println("\n" + "=".repeat(80))
        println("RECURRING EVENT EXCEPTION WORKFLOW TEST (Radicale)")
        println("=".repeat(80))
        println("Test UID: $testUid")
        println("=".repeat(80) + "\n")

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)
        println("Using calendar: $calendarUrl\n")

        // Calculate dates
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Find next Monday
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOccurrence = cal.time
        val firstOccurrenceStr = icsDateFormat.format(firstOccurrence)

        // Second occurrence (1 week later)
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val secondOccurrence = cal.time
        val secondOccurrenceStr = icsDateFormat.format(secondOccurrence)

        // Modified time for exception (2pm instead of 10am)
        cal.set(Calendar.HOUR_OF_DAY, 14)
        val exceptionTimeStr = icsDateFormat.format(cal.time)

        println("Date plan:")
        println("  First occurrence: $firstOccurrenceStr (Mon 10am)")
        println("  Second occurrence: $secondOccurrenceStr (Mon 10am)")
        println("  Exception time: $exceptionTimeStr (2pm)")
        println()

        // Step 1: Create recurring event
        step1_createRecurringEvent(firstOccurrenceStr)

        // Step 2: Fetch and verify
        step2_fetchAndVerify()

        // Step 3: Create exception (modify second occurrence)
        step3_createException(firstOccurrenceStr, secondOccurrenceStr, exceptionTimeStr)

        // Step 4: Fetch and verify exception
        step4_fetchAndVerifyException()

        println("\n" + "=".repeat(80))
        println("WORKFLOW TEST COMPLETE")
        println("=".repeat(80))
    }

    private suspend fun step1_createRecurringEvent(firstOccurrenceStr: String) {
        println("=== STEP 1: Create Recurring Event ===\n")

        val icsContent = """
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
DESCRIPTION:Integration test event - safe to delete
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating with ICS:")
        println(icsContent)
        println()

        val result = client.createEvent(calendarUrl!!, testUid, icsContent)

        assert(result.isSuccess()) {
            "Failed to create event: ${(result as? CalDavResult.Error)?.message}"
        }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        println("Created event:")
        println("  URL: $url")
        println("  ETag: $etag")
        println()
    }

    private suspend fun step2_fetchAndVerify() {
        println("=== STEP 2: Fetch and Verify ===\n")

        val result = client.fetchEvent(testEventUrl!!)
        assert(result.isSuccess()) { "Failed to fetch event" }

        val event = result.getOrNull()!!
        testEventEtag = event.etag

        println("Fetched ICS from Radicale:")
        println(event.icalData)
        println()
        println("ETag: ${event.etag}")
        println()

        // Verify structure
        assert(event.icalData.contains("RRULE:")) { "Should have RRULE" }
        assert(event.icalData.contains("BEGIN:VEVENT")) { "Should have VEVENT" }
    }

    private suspend fun step3_createException(
        firstOccurrenceStr: String,
        secondOccurrenceStr: String,
        exceptionTimeStr: String
    ) {
        println("=== STEP 3: Create Exception (Edit Second Occurrence) ===\n")

        // RFC 5545: Exception is same UID, different VEVENT with RECURRENCE-ID
        val icsContent = """
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
DESCRIPTION:Integration test event - safe to delete
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

        println("Updating with ICS (master + exception):")
        println(icsContent)
        println()

        val result = client.updateEvent(testEventUrl!!, icsContent, testEventEtag!!)

        assert(result.isSuccess()) {
            "Failed to update event: ${(result as? CalDavResult.Error)?.message}"
        }

        testEventEtag = result.getOrNull()!!
        println("Updated successfully, new ETag: $testEventEtag")
        println()
    }

    private suspend fun step4_fetchAndVerifyException() {
        println("=== STEP 4: Fetch and Verify Exception ===\n")

        val result = client.fetchEvent(testEventUrl!!)
        assert(result.isSuccess()) { "Failed to fetch event" }

        val event = result.getOrNull()!!
        testEventEtag = event.etag

        println("Fetched ICS from Radicale (with exception):")
        println(event.icalData)
        println()

        // Verify structure
        assert(event.icalData.contains("RECURRENCE-ID:")) { "Should have RECURRENCE-ID" }

        // Count VEVENTs
        val veventCount = event.icalData.split("BEGIN:VEVENT").size - 1
        println("VEVENT count: $veventCount (expected: 2 - master + exception)")
        assert(veventCount >= 2) { "Should have at least 2 VEVENTs (master + exception)" }
        println()
    }

    // ========== EXDATE (CANCELLED OCCURRENCE) TEST ==========

    @Test
    fun `recurring event with cancelled occurrence (EXDATE)`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val exdateUid = "test-exdate-${UUID.randomUUID()}@kashcal.test"

        // Calculate dates
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Find next Monday
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOccurrenceStr = icsDateFormat.format(cal.time)

        // Third occurrence (2 weeks later) - will be cancelled
        cal.add(Calendar.WEEK_OF_YEAR, 2)
        val thirdOccurrenceStr = icsDateFormat.format(cal.time)

        // Create recurring event with EXDATE
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
DESCRIPTION:Third occurrence is cancelled
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, exdateUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        // Verify EXDATE is preserved
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS with EXDATE:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("EXDATE:")) { "EXDATE should be preserved" }
        assert(fetchedIcs.contains(thirdOccurrenceStr)) { "EXDATE should contain cancelled date" }
    }

    // ========== SYNC-COLLECTION TEST ==========

    @Test
    fun `sync-collection returns sync token`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found", calendarUrl != null)

        val result = client.getSyncToken(calendarUrl!!)
        assert(result.isSuccess()) { "Failed to get sync token: ${(result as? CalDavResult.Error)?.message}" }

        val token = result.getOrNull()!!
        println("Sync token: $token")
        assert(token.isNotEmpty()) { "Sync token should not be empty" }
    }
}
