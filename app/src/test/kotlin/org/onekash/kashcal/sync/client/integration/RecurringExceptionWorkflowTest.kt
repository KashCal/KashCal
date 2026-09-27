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
 * Prints iCloud's ICS at each step of a recurring event's exception workflow.
 *
 * The test:
 * 1. Creates a weekly series
 * 2. Adds an exception that moves the second occurrence
 * 3. Edits the exception again
 * 4. Prints the fetched ICS after each write
 *
 * It creates one real event on iCloud and deletes only that one, by the URL its create returned.
 * Requires local.properties with iCloud credentials; skipped without them.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*RecurringExceptionWorkflowTest*"
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

    @After
    fun cleanup() = runBlocking {
        // Deletes the test's own event, when it was created and an etag is known
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
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
                    val key = trimmed.substringBefore("=").trim()
                    val value = trimmed.substringAfter("=").trim()
                    when (key) {
                        "ICLOUD_USERNAME" -> username = value
                        "ICLOUD_APP_PASSWORD" -> password = value
                        "caldav.username" -> if (username == null) username = value
                        "caldav.app_password" -> if (password == null) password = value
                        "caldav.server" -> serverUrl = value
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
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        // The first calendar whose URL contains neither "inbox" nor "outbox"
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    // ========== Workflow ==========

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

        // Occurrence dates, at 10:00 UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Today if it is a Monday, else the next Monday
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOccurrence = cal.time
        val firstOccurrenceStr = icsDateFormat.format(firstOccurrence)

        // Second occurrence, one week later
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        val secondOccurrence = cal.time
        val secondOccurrenceStr = icsDateFormat.format(secondOccurrence)

        // The exception's first time: 2pm instead of 10am
        cal.set(Calendar.HOUR_OF_DAY, 14)
        val exceptionTime = cal.time
        val exceptionTimeStr = icsDateFormat.format(exceptionTime)

        // The exception's second time: 4pm
        cal.set(Calendar.HOUR_OF_DAY, 16)
        val reEditTime = cal.time
        val reEditTimeStr = icsDateFormat.format(reEditTime)

        println("Date plan:")
        println("  First occurrence: $firstOccurrenceStr (Mon 10am)")
        println("  Second occurrence: $secondOccurrenceStr (Mon 10am)")
        println("  Exception edit 1: $exceptionTimeStr (2pm)")
        println("  Exception edit 2: $reEditTimeStr (4pm)")
        println()

        // ========== Step 1: create the series ==========
        step1_createRecurringEvent(firstOccurrenceStr)

        // ========== Step 2: fetch and check ==========
        step2_fetchAndVerify()

        // ========== Step 3: add the exception ==========
        step3_createException(firstOccurrenceStr, secondOccurrenceStr, exceptionTimeStr)

        // ========== Step 4: fetch and check the exception ==========
        step4_fetchAndVerifyException()

        // ========== Step 5: edit the exception again ==========
        step5_editExceptionAgain(firstOccurrenceStr, secondOccurrenceStr, reEditTimeStr)

        // ========== Step 6: fetch and check the re-edited exception ==========
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

        // The series has an RRULE and a VEVENT
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

        // RFC 5545 §3.8.4.4: the exception is another VEVENT with the same UID and a
        // RECURRENCE-ID
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

        // The exception kept its RECURRENCE-ID
        assert(event.icalData.contains("RECURRENCE-ID:")) { "Should have RECURRENCE-ID" }

        // Master and exception
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

        // What iCloud kept or changed
        println("KEY OBSERVATIONS:")
        println("-".repeat(40))

        // Whether RECURRENCE-ID survived
        if (event.icalData.contains("RECURRENCE-ID:")) {
            println("- RECURRENCE-ID: Present (exception preserved)")
        } else {
            println("- RECURRENCE-ID: MISSING (exception lost!)")
        }

        // Every SEQUENCE value in the fetched ICS
        val sequenceMatch = Regex("SEQUENCE:(\\d+)").findAll(event.icalData)
        sequenceMatch.forEach { match ->
            println("- SEQUENCE: ${match.groupValues[1]}")
        }

        // VEVENT count
        val veventCount = event.icalData.split("BEGIN:VEVENT").size - 1
        println("- VEVENT count: $veventCount")

        // Whether iCloud added X-APPLE properties
        if (event.icalData.contains("X-APPLE")) {
            println("- iCloud added X-APPLE properties")
        }

        println("-".repeat(40))
        println()
    }
}
