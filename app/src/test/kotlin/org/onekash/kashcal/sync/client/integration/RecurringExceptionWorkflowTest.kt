package org.onekash.kashcal.sync.client.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Integration test documenting iCloud's recurring event + exception workflow.
 *
 * This test:
 * 1. Creates a recurring event via CalDAV
 * 2. Creates an exception (edit one instance) via CalDAV
 * 3. Edits the exception again via CalDAV
 * 4. Logs ICS format at each step
 *
 * Run: ./gradlew testDebugUnitTest --tests "*RecurringExceptionWorkflowTest*"
 *
 * IMPORTANT: This test creates real events on iCloud and cleans them up.
 * Requires local.properties with valid iCloud credentials.
 */
class RecurringExceptionWorkflowTest {

    private lateinit var client: CalDavClient
    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

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
        val quirks = ICloudQuirks()
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
            // Create a client with dummy credentials for tests that will be skipped
            val dummyCredentials = Credentials(
                username = "test@example.com",
                password = "test-password",
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(dummyCredentials, quirks)
        }
    }

    @After
    fun cleanup() = runBlocking {
        // Clean up test event if it was created
        if (testEventUrl != null && testEventEtag != null) {
            println("\n=== CLEANUP: Deleting test event ===")
            val result = client.deleteEvent(testEventUrl!!, testEventEtag!!)
            println("Delete result: ${if (result.isSuccess()) "Success" else result}")
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
                        when {
                            parts[0].contains("username", ignoreCase = true) -> username = parts[1]
                            parts[0].contains("password", ignoreCase = true) &&
                                !parts[0].contains("keystore", ignoreCase = true) -> password = parts[1]
                            parts[0].contains("server", ignoreCase = true) -> serverUrl = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "iCloud credentials not available",
            username != null && password != null
        )
    }

    private suspend fun discoverCalendar(): String? {
        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        // Find first non-inbox/outbox calendar
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    // ========== WORKFLOW TEST ==========

    @Test
    fun `document recurring event exception workflow`() = runBlocking {
        assumeCredentialsAvailable()

        println("\n" + "=".repeat(80))
        println("RECURRING EVENT EXCEPTION WORKFLOW TEST")
        println("=".repeat(80))
        println("Test UID: $testUid")
        println("=".repeat(80) + "\n")

        // Discover calendar
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
        val exceptionTime = cal.time
        val exceptionTimeStr = icsDateFormat.format(exceptionTime)

        // Re-modified time for second edit (4pm)
        cal.set(Calendar.HOUR_OF_DAY, 16)
        val reEditTime = cal.time
        val reEditTimeStr = icsDateFormat.format(reEditTime)

        println("Date plan:")
        println("  First occurrence: $firstOccurrenceStr (Mon 10am)")
        println("  Second occurrence: $secondOccurrenceStr (Mon 10am)")
        println("  Exception edit 1: $exceptionTimeStr (2pm)")
        println("  Exception edit 2: $reEditTimeStr (4pm)")
        println()

        // ========== STEP 1: Create Recurring Event ==========
        step1_createRecurringEvent(firstOccurrenceStr)

        // ========== STEP 2: Fetch and verify ==========
        step2_fetchAndVerify()

        // ========== STEP 3: Create Exception ==========
        step3_createException(firstOccurrenceStr, secondOccurrenceStr, exceptionTimeStr)

        // ========== STEP 4: Fetch and verify exception ==========
        step4_fetchAndVerifyException()

        // ========== STEP 5: Edit Exception Again ==========
        step5_editExceptionAgain(firstOccurrenceStr, secondOccurrenceStr, reEditTimeStr)

        // ========== STEP 6: Fetch and verify re-edited exception ==========
        step6_fetchAndVerifyReEdit()

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
SUMMARY:Test Recurring Event
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

        println("Fetched ICS from iCloud:")
        println(event.icalData)
        println()
        println("ETag: ${event.etag}")
        println()

        // Verify structure
        assert(event.icalData.contains("RRULE:")) { "Should have RRULE" }
        assert(event.icalData.count { it == 'B' && event.icalData.indexOf("BEGIN:VEVENT") >= 0 } >= 1) {
            "Should have at least one VEVENT"
        }
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
SUMMARY:Test Recurring Event
DESCRIPTION:Integration test event - safe to delete
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceStr
DTSTART:$exceptionTimeStr
DTEND:${exceptionTimeStr.replace("T14", "T15")}
SUMMARY:Test Recurring Event (Rescheduled to 2pm)
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

        println("Fetched ICS from iCloud (with exception):")
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

    private suspend fun step5_editExceptionAgain(
        firstOccurrenceStr: String,
        secondOccurrenceStr: String,
        reEditTimeStr: String
    ) {
        println("=== STEP 5: Edit Exception Again (Move to 4pm) ===\n")

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
SUMMARY:Test Recurring Event
DESCRIPTION:Integration test event - safe to delete
SEQUENCE:2
END:VEVENT
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceStr
DTSTART:$reEditTimeStr
DTEND:${reEditTimeStr.replace("T16", "T17")}
SUMMARY:Test Recurring Event (Rescheduled to 4pm)
DESCRIPTION:Exception - moved from 2pm to 4pm
SEQUENCE:2
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Updating with ICS (re-edited exception):")
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

    private suspend fun step6_fetchAndVerifyReEdit() {
        println("=== STEP 6: Fetch and Verify Re-Edited Exception ===\n")

        val result = client.fetchEvent(testEventUrl!!)
        assert(result.isSuccess()) { "Failed to fetch event" }

        val event = result.getOrNull()!!
        testEventEtag = event.etag

        println("Fetched ICS from iCloud (re-edited exception):")
        println(event.icalData)
        println()

        // Key observations
        println("KEY OBSERVATIONS:")
        println("-".repeat(40))

        // Check RECURRENCE-ID is preserved
        if (event.icalData.contains("RECURRENCE-ID:")) {
            println("- RECURRENCE-ID: Present (exception preserved)")
        } else {
            println("- RECURRENCE-ID: MISSING (exception lost!)")
        }

        // Check SEQUENCE was incremented
        val sequenceMatch = Regex("SEQUENCE:(\\d+)").findAll(event.icalData)
        sequenceMatch.forEach { match ->
            println("- SEQUENCE: ${match.groupValues[1]}")
        }

        // Count VEVENTs
        val veventCount = event.icalData.split("BEGIN:VEVENT").size - 1
        println("- VEVENT count: $veventCount")

        // Check if iCloud modified anything
        if (event.icalData.contains("X-APPLE")) {
            println("- iCloud added X-APPLE properties")
        }

        println("-".repeat(40))
        println()
    }
}
