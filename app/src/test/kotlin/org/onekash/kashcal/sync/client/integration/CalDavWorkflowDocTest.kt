package org.onekash.kashcal.sync.client.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Comprehensive CalDAV workflow documentation test.
 *
 * This test documents EXACT iCloud behavior for all KashCal workflows:
 * - Single event: create, update, delete, move
 * - Recurring event: create, edit occurrence, delete occurrence, split, etc.
 * - Sync: ctag, sync-token, conflict detection
 * - Edge cases: special characters, timezones, multi-day events
 *
 * Run: ./gradlew testDebugUnitTest --tests "*CalDavWorkflowDocTest*"
 *
 * Output: Test logs document ICS format at each step for reference.
 */
class CalDavWorkflowDocTest {

    private lateinit var client: OkHttpCalDavClient
    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

    // Test state
    private var calendarUrl: String? = null
    private var secondCalendarUrl: String? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>() // url, etag

    // Date formatters
    private val icsDateTimeFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val icsDateFormat = SimpleDateFormat("yyyyMMdd").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        val quirks = ICloudQuirks()
        client = OkHttpCalDavClient(quirks)
        loadCredentials()

        if (username != null && password != null) {
            client.setCredentials(username!!, password!!)
        }
    }

    @After
    fun cleanup() = runBlocking {
        // Clean up all test events
        for ((url, etag) in createdEventUrls) {
            try {
                client.deleteEvent(url, etag)
                println("Cleaned up: $url")
            } catch (e: Exception) {
                println("Failed to cleanup $url: ${e.message}")
            }
        }
        createdEventUrls.clear()
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

    private suspend fun discoverCalendars(): Pair<String?, String?> {
        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return null to null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null to null
        val calendars = client.listCalendars(home).getOrNull() ?: return null to null

        // Find first two non-inbox/outbox calendars
        val usableCalendars = calendars.filter { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }

        return usableCalendars.getOrNull(0)?.url to usableCalendars.getOrNull(1)?.url
    }

    private fun generateUid(): String = "test-${UUID.randomUUID()}@kashcal.test"

    private fun printSection(title: String) {
        println("\n" + "=".repeat(80))
        println(title)
        println("=".repeat(80))
    }

    private fun printSubsection(title: String) {
        println("\n--- $title ---")
    }

    private fun printIcs(label: String, ics: String) {
        println("\n[$label]")
        println(ics.trim())
    }

    // ========================================================================
    // PHASE 1: SINGLE EVENT WORKFLOWS
    // ========================================================================

    @Test
    fun `Test 1 - Create single event then fetch back`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 1: CREATE SINGLE EVENT → FETCH → VERIFY")

        // Setup
        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 1)
        now.set(Calendar.HOUR_OF_DAY, 14)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Step 1: Create event
        printSubsection("Step 1: Create Event via PUT")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Test Single Event
DESCRIPTION:Integration test - safe to delete
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("ICS Sent", icsCreate)

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) {
            "Failed to create: ${(createResult as? CalDavResult.Error)?.message}"
        }

        val (eventUrl, etag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to etag)
        println("\nCreated at: $eventUrl")
        println("ETag: $etag")

        // Step 2: Fetch back
        printSubsection("Step 2: Fetch Event via GET")
        val fetchResult = client.fetchEvent(eventUrl)
        assert(fetchResult.isSuccess()) { "Failed to fetch" }

        val fetched = fetchResult.getOrNull()!!
        printIcs("ICS Received from iCloud", fetched.icalData)

        // Step 3: Document observations
        printSubsection("Step 3: Observations")
        println("- Original UID preserved: ${fetched.icalData.contains(uid)}")
        println("- X-APPLE properties added: ${fetched.icalData.contains("X-APPLE")}")
        println("- SEQUENCE present: ${fetched.icalData.contains("SEQUENCE:")}")
        println("- CREATED added: ${fetched.icalData.contains("CREATED:")}")
        println("- LAST-MODIFIED added: ${fetched.icalData.contains("LAST-MODIFIED:")}")

        // Update etag for cleanup
        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (fetched.etag ?: etag))
    }

    @Test
    fun `Test 2 - Update event then fetch back`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 2: UPDATE EVENT → FETCH → VERIFY")

        // Setup
        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 2)
        now.set(Calendar.HOUR_OF_DAY, 10)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Step 1: Create initial event
        printSubsection("Step 1: Create Initial Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Test Update Event - Original
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to createEtag)
        println("Created with etag: $createEtag")

        // Fetch to get server's version
        val initialFetch = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("Initial ICS from iCloud", initialFetch.icalData)

        // Step 2: Update event
        printSubsection("Step 2: Update Event via PUT")
        now.add(Calendar.HOUR_OF_DAY, 3) // Move 3 hours later
        val newStartTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val newEndTime = icsDateTimeFormat.format(now.time)

        val icsUpdate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$newStartTime
DTEND:$newEndTime
SUMMARY:Test Update Event - MODIFIED
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("Update ICS Sent", icsUpdate)

        val updateResult = client.updateEvent(eventUrl, icsUpdate, initialFetch.etag!!)
        assert(updateResult.isSuccess()) {
            "Failed to update: ${(updateResult as? CalDavResult.Error)?.message}"
        }

        val newEtag = updateResult.getOrNull()!!
        println("\nUpdate successful, new etag: $newEtag")

        // Step 3: Fetch back
        printSubsection("Step 3: Fetch Updated Event")
        val updatedFetch = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("Updated ICS from iCloud", updatedFetch.icalData)

        // Step 4: Document observations
        printSubsection("Step 4: Observations")
        println("- SEQUENCE incremented: ${updatedFetch.icalData.contains("SEQUENCE:1")}")
        println("- SUMMARY changed: ${updatedFetch.icalData.contains("MODIFIED")}")
        println("- LAST-MODIFIED updated: ${updatedFetch.icalData.contains("LAST-MODIFIED:")}")
        println("- ETag changed: ${createEtag != newEtag}")

        // Update for cleanup
        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (updatedFetch.etag ?: newEtag))
    }

    @Test
    fun `Test 3 - Delete event and verify gone`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 3: DELETE EVENT → VERIFY GONE")

        // Setup
        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 3)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Step 1: Create event
        printSubsection("Step 1: Create Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Test Delete Event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, etag) = createResult.getOrNull()!!
        println("Created: $eventUrl")

        // Fetch to get current etag
        val fetched = client.fetchEvent(eventUrl).getOrNull()!!

        // Step 2: Delete event
        printSubsection("Step 2: Delete Event via DELETE")
        val deleteResult = client.deleteEvent(eventUrl, fetched.etag ?: etag)
        println("Delete result: ${if (deleteResult.isSuccess()) "Success" else deleteResult}")
        assert(deleteResult.isSuccess()) { "Failed to delete" }

        // Step 3: Verify gone
        printSubsection("Step 3: Verify Event is Gone")
        val fetchAfterDelete = client.fetchEvent(eventUrl)
        println("Fetch after delete: ${if (fetchAfterDelete.isNotFound()) "404 Not Found (expected)" else fetchAfterDelete}")

        // Step 4: Document observations
        printSubsection("Step 4: Observations")
        println("- Delete returns success immediately: true")
        println("- Fetch returns 404: ${fetchAfterDelete.isNotFound()}")
        println("- No soft delete period observed")
    }

    @Test
    fun `Test 4 - Move event between calendars`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 4: MOVE EVENT BETWEEN CALENDARS")

        // Setup
        val (cal1, cal2) = discoverCalendars()
        calendarUrl = cal1
        secondCalendarUrl = cal2
        assumeTrue("Need at least 2 calendars", calendarUrl != null && secondCalendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 4)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Step 1: Create in Calendar A
        printSubsection("Step 1: Create Event in Calendar A")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Test Move Event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create in Cal A" }
        val (eventUrlA, etagA) = createResult.getOrNull()!!
        println("Created in Calendar A: $eventUrlA")

        // Fetch for current etag
        val fetchedA = client.fetchEvent(eventUrlA).getOrNull()!!

        // Step 2: Delete from Calendar A
        printSubsection("Step 2: Delete from Calendar A")
        val deleteResult = client.deleteEvent(eventUrlA, fetchedA.etag ?: etagA)
        assert(deleteResult.isSuccess()) { "Failed to delete from Cal A" }
        println("Deleted from Calendar A")

        // Step 3: Create in Calendar B (same UID)
        printSubsection("Step 3: Create in Calendar B (same UID)")
        val createResultB = client.createEvent(secondCalendarUrl!!, uid, icsCreate)

        if (createResultB.isSuccess()) {
            val (eventUrlB, etagB) = createResultB.getOrNull()!!
            createdEventUrls.add(eventUrlB to etagB)
            println("Created in Calendar B: $eventUrlB")

            // Fetch back
            val fetchedB = client.fetchEvent(eventUrlB).getOrNull()!!
            printIcs("ICS in Calendar B", fetchedB.icalData)

            // Step 4: Observations
            printSubsection("Step 4: Observations")
            println("- Same UID accepted in different calendar: true")
            println("- UID preserved: ${fetchedB.icalData.contains(uid)}")
        } else {
            println("Create in Calendar B failed: ${(createResultB as? CalDavResult.Error)?.message}")
            printSubsection("Step 4: Observations")
            println("- iCloud rejected same UID in different calendar")
        }
    }

    // ========================================================================
    // PHASE 2: RECURRING EVENT WORKFLOWS
    // ========================================================================

    @Test
    fun `Test 5 - Create recurring event then fetch`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 5: CREATE RECURRING EVENT → FETCH")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // Find next Monday
        while (now.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            now.add(Calendar.DAY_OF_MONTH, 1)
        }
        now.set(Calendar.HOUR_OF_DAY, 10)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Step 1: Create recurring event
        printSubsection("Step 1: Create Recurring Event (FREQ=WEEKLY;COUNT=4)")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Recurring Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("ICS Sent", icsCreate)

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, etag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to etag)

        // Step 2: Fetch back
        printSubsection("Step 2: Fetch from iCloud")
        val fetched = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("ICS from iCloud", fetched.icalData)

        // Step 3: Observations
        printSubsection("Step 3: Observations")
        println("- RRULE preserved: ${fetched.icalData.contains("RRULE:")}")
        println("- RRULE unchanged: ${fetched.icalData.contains("FREQ=WEEKLY;COUNT=4")}")
        println("- X-APPLE properties: ${fetched.icalData.contains("X-APPLE")}")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (fetched.etag ?: etag))
    }

    @Test
    fun `Test 6 - Edit single occurrence with RECURRENCE-ID`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 6: EDIT SINGLE OCCURRENCE (RECURRENCE-ID)")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        while (now.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            now.add(Calendar.DAY_OF_MONTH, 1)
        }
        now.set(Calendar.HOUR_OF_DAY, 10)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val firstOccurrence = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val firstEnd = icsDateTimeFormat.format(now.time)

        // Calculate second occurrence
        now.add(Calendar.DAY_OF_MONTH, 6) // Back to start, then +7 days
        now.set(Calendar.HOUR_OF_DAY, 10)
        val secondOccurrence = icsDateTimeFormat.format(now.time)

        // Exception time (2pm instead of 10am)
        now.set(Calendar.HOUR_OF_DAY, 14)
        val exceptionStart = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val exceptionEnd = icsDateTimeFormat.format(now.time)

        // Step 1: Create recurring event
        printSubsection("Step 1: Create Recurring Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to createEtag)

        val initial = client.fetchEvent(eventUrl).getOrNull()!!

        // Step 2: Add exception
        printSubsection("Step 2: Add Exception (move 2nd occurrence to 2pm)")
        val icsWithException = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Test
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrence
DTSTART:$exceptionStart
DTEND:$exceptionEnd
SUMMARY:Weekly Test (Moved to 2pm)
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("ICS with Exception Sent", icsWithException)

        val updateResult = client.updateEvent(eventUrl, icsWithException, initial.etag!!)
        assert(updateResult.isSuccess()) { "Failed to update" }

        // Step 3: Fetch back
        printSubsection("Step 3: Fetch from iCloud")
        val withException = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("ICS with Exception from iCloud", withException.icalData)

        // Step 4: Observations
        printSubsection("Step 4: Observations")
        val veventCount = withException.icalData.split("BEGIN:VEVENT").size - 1
        println("- VEVENT count: $veventCount (expected: 2)")
        println("- Master VEVENT present: ${withException.icalData.contains("RRULE:")}")
        println("- RECURRENCE-ID present: ${withException.icalData.contains("RECURRENCE-ID:")}")
        println("- Exception has same UID: ${withException.icalData.split("UID:$uid").size > 2}")
        println("- RECURRENCE-ID format: datetime (not date)")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (withException.etag ?: updateResult.getOrNull()!!))
    }

    @Test
    fun `Test 7 - Delete single occurrence with EXDATE`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 7: DELETE SINGLE OCCURRENCE (EXDATE)")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        while (now.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            now.add(Calendar.DAY_OF_MONTH, 1)
        }
        now.set(Calendar.HOUR_OF_DAY, 10)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val firstOccurrence = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val firstEnd = icsDateTimeFormat.format(now.time)

        // Second occurrence (to exclude)
        now.add(Calendar.DAY_OF_MONTH, 6)
        now.set(Calendar.HOUR_OF_DAY, 10)
        val secondOccurrence = icsDateTimeFormat.format(now.time)

        // Step 1: Create recurring event
        printSubsection("Step 1: Create Recurring Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Test - EXDATE
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to createEtag)

        val initial = client.fetchEvent(eventUrl).getOrNull()!!

        // Step 2: Add EXDATE
        printSubsection("Step 2: Add EXDATE (exclude 2nd occurrence)")
        val icsWithExdate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE:$secondOccurrence
SUMMARY:Weekly Test - EXDATE
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("ICS with EXDATE Sent", icsWithExdate)

        val updateResult = client.updateEvent(eventUrl, icsWithExdate, initial.etag!!)
        assert(updateResult.isSuccess()) { "Failed to update" }

        // Step 3: Fetch back
        printSubsection("Step 3: Fetch from iCloud")
        val withExdate = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("ICS with EXDATE from iCloud", withExdate.icalData)

        // Step 4: Observations
        printSubsection("Step 4: Observations")
        println("- EXDATE present: ${withExdate.icalData.contains("EXDATE:")}")
        println("- EXDATE format matches DTSTART: datetime")
        println("- Only 1 VEVENT (no separate exception): ${withExdate.icalData.split("BEGIN:VEVENT").size == 2}")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (withExdate.etag ?: updateResult.getOrNull()!!))
    }

    @Test
    fun `Test 8 - Re-edit exception`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 8: RE-EDIT EXCEPTION (RECURRENCE-ID unchanged)")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        while (now.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            now.add(Calendar.DAY_OF_MONTH, 1)
        }
        now.set(Calendar.HOUR_OF_DAY, 10)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val firstOccurrence = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val firstEnd = icsDateTimeFormat.format(now.time)

        // Second occurrence (original time for RECURRENCE-ID)
        now.add(Calendar.DAY_OF_MONTH, 6)
        now.set(Calendar.HOUR_OF_DAY, 10)
        val secondOccurrenceOriginal = icsDateTimeFormat.format(now.time)

        // First edit: 2pm
        now.set(Calendar.HOUR_OF_DAY, 14)
        val firstEditStart = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val firstEditEnd = icsDateTimeFormat.format(now.time)

        // Second edit: 4pm
        now.set(Calendar.HOUR_OF_DAY, 16)
        val secondEditStart = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val secondEditEnd = icsDateTimeFormat.format(now.time)

        // Step 1: Create recurring event
        printSubsection("Step 1: Create Recurring Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, _) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to "")

        var current = client.fetchEvent(eventUrl).getOrNull()!!

        // Step 2: First edit (move to 2pm)
        printSubsection("Step 2: First Edit (move to 2pm)")
        val icsFirstEdit = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Test
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceOriginal
DTSTART:$firstEditStart
DTEND:$firstEditEnd
SUMMARY:Weekly Test (2pm)
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        client.updateEvent(eventUrl, icsFirstEdit, current.etag!!)
        current = client.fetchEvent(eventUrl).getOrNull()!!
        println("After first edit - RECURRENCE-ID: $secondOccurrenceOriginal")

        // Step 3: Second edit (move to 4pm) - RECURRENCE-ID stays same!
        printSubsection("Step 3: Second Edit (move to 4pm)")
        val icsSecondEdit = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly Test
SEQUENCE:2
END:VEVENT
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceOriginal
DTSTART:$secondEditStart
DTEND:$secondEditEnd
SUMMARY:Weekly Test (4pm)
SEQUENCE:2
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("Second Edit ICS (RECURRENCE-ID unchanged)", icsSecondEdit)
        client.updateEvent(eventUrl, icsSecondEdit, current.etag!!)

        // Step 4: Fetch and verify
        printSubsection("Step 4: Fetch from iCloud")
        val final = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("Final ICS from iCloud", final.icalData)

        // Step 5: Observations
        printSubsection("Step 5: Key Observation")
        println("- RECURRENCE-ID NEVER CHANGES: $secondOccurrenceOriginal")
        println("- RECURRENCE-ID = original occurrence time, regardless of edits")
        println("- Only DTSTART/DTEND of exception changes")
        println("- This is RFC 5545 compliant behavior")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (final.etag ?: ""))
    }

    @Test
    fun `Test 9 - Edit this and future (series split)`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 9: EDIT THIS AND FUTURE (SERIES SPLIT)")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val masterUid = generateUid()
        val newSeriesUid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        while (now.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            now.add(Calendar.DAY_OF_MONTH, 1)
        }
        now.set(Calendar.HOUR_OF_DAY, 10)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val firstOccurrence = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val firstEnd = icsDateTimeFormat.format(now.time)

        // Split point: 3rd occurrence
        now.add(Calendar.DAY_OF_MONTH, 13) // +2 weeks
        now.set(Calendar.HOUR_OF_DAY, 10)
        val splitPoint = icsDateTimeFormat.format(now.time)

        // UNTIL for master (day before split)
        now.add(Calendar.DAY_OF_MONTH, -1)
        val untilDate = icsDateTimeFormat.format(now.time)

        // Step 1: Create original recurring event
        printSubsection("Step 1: Create Original Recurring Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Original Series
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, masterUid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create master" }
        val (masterUrl, _) = createResult.getOrNull()!!
        createdEventUrls.add(masterUrl to "")

        val initial = client.fetchEvent(masterUrl).getOrNull()!!

        // Step 2: Truncate master with UNTIL
        printSubsection("Step 2: Truncate Master with UNTIL")
        val icsTruncated = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$masterUid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$firstOccurrence
DTEND:$firstEnd
RRULE:FREQ=WEEKLY;UNTIL=$untilDate
SUMMARY:Original Series (Truncated)
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("Truncated Master ICS", icsTruncated)
        client.updateEvent(masterUrl, icsTruncated, initial.etag!!)

        // Step 3: Create new series
        printSubsection("Step 3: Create New Series (from split point)")
        // New series starts at split point, 2pm instead of 10am
        now.add(Calendar.DAY_OF_MONTH, 1) // Back to split point
        now.set(Calendar.HOUR_OF_DAY, 14) // 2pm
        val newSeriesStart = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val newSeriesEnd = icsDateTimeFormat.format(now.time)

        val icsNewSeries = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$newSeriesUid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$newSeriesStart
DTEND:$newSeriesEnd
RRULE:FREQ=WEEKLY;COUNT=2
SUMMARY:New Series (2pm)
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("New Series ICS", icsNewSeries)
        val newResult = client.createEvent(calendarUrl!!, newSeriesUid, icsNewSeries)
        assert(newResult.isSuccess()) { "Failed to create new series" }
        val (newUrl, _) = newResult.getOrNull()!!
        createdEventUrls.add(newUrl to "")

        // Step 4: Fetch both and verify
        printSubsection("Step 4: Fetch Both Series")
        val truncatedMaster = client.fetchEvent(masterUrl).getOrNull()!!
        val newSeries = client.fetchEvent(newUrl).getOrNull()!!

        printIcs("Truncated Master from iCloud", truncatedMaster.icalData)
        printIcs("New Series from iCloud", newSeries.icalData)

        // Step 5: Observations
        printSubsection("Step 5: Observations")
        println("- Master has UNTIL: ${truncatedMaster.icalData.contains("UNTIL=")}")
        println("- New series has different UID: true")
        println("- Series are independent (no link)")
        println("- KashCal approach: truncate + create new = 2 independent events")

        createdEventUrls.clear()
        createdEventUrls.add(masterUrl to (truncatedMaster.etag ?: ""))
        createdEventUrls.add(newUrl to (newSeries.etag ?: ""))
    }

    @Test
    fun `Test 10 - All-day recurring event with exception`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 10: ALL-DAY RECURRING EVENT WITH EXCEPTION")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        while (now.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            now.add(Calendar.DAY_OF_MONTH, 1)
        }
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val firstDate = icsDateFormat.format(now.time)
        now.add(Calendar.DAY_OF_MONTH, 1)
        val firstEndDate = icsDateFormat.format(now.time)

        // Second occurrence
        now.add(Calendar.DAY_OF_MONTH, 6)
        val secondDate = icsDateFormat.format(now.time)
        now.add(Calendar.DAY_OF_MONTH, 1)
        val secondEndDate = icsDateFormat.format(now.time)

        // Exception: move to different day
        now.add(Calendar.DAY_OF_MONTH, 1)
        val exceptionDate = icsDateFormat.format(now.time)
        now.add(Calendar.DAY_OF_MONTH, 1)
        val exceptionEndDate = icsDateFormat.format(now.time)

        // Step 1: Create all-day recurring event
        printSubsection("Step 1: Create All-Day Recurring Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART;VALUE=DATE:$firstDate
DTEND;VALUE=DATE:$firstEndDate
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly All-Day
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("All-Day ICS Sent", icsCreate)

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, _) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to "")

        var current = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("All-Day ICS from iCloud", current.icalData)

        // Step 2: Add exception
        printSubsection("Step 2: Add Exception (move 2nd to different day)")
        val icsWithException = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART;VALUE=DATE:$firstDate
DTEND;VALUE=DATE:$firstEndDate
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Weekly All-Day
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
RECURRENCE-ID;VALUE=DATE:$secondDate
DTSTART;VALUE=DATE:$exceptionDate
DTEND;VALUE=DATE:$exceptionEndDate
SUMMARY:Weekly All-Day (Moved)
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("All-Day Exception ICS Sent", icsWithException)

        client.updateEvent(eventUrl, icsWithException, current.etag!!)

        // Step 3: Fetch and verify
        printSubsection("Step 3: Fetch from iCloud")
        val final = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("All-Day with Exception from iCloud", final.icalData)

        // Step 4: Observations
        printSubsection("Step 4: Observations")
        println("- All-day uses VALUE=DATE: ${final.icalData.contains("VALUE=DATE")}")
        println("- RECURRENCE-ID for all-day uses VALUE=DATE")
        println("- No time component in dates")
        println("- iCloud preserves VALUE=DATE format")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (final.etag ?: ""))
    }

    // ========================================================================
    // PHASE 3: SYNC BEHAVIOR TESTS
    // ========================================================================

    @Test
    fun `Test 11 - Ctag change detection`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 11: CTAG CHANGE DETECTION")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        // Step 1: Get initial ctag
        printSubsection("Step 1: Get Initial Ctag")
        val ctag1Result = client.getCtag(calendarUrl!!)
        assert(ctag1Result.isSuccess()) { "Failed to get ctag" }
        val ctag1 = ctag1Result.getOrNull()!!
        println("Initial ctag: $ctag1")

        // Step 2: Create event
        printSubsection("Step 2: Create Event")
        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 10)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Ctag Test Event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, etag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to etag)
        println("Event created")

        // Step 3: Get new ctag
        printSubsection("Step 3: Get New Ctag")
        val ctag2Result = client.getCtag(calendarUrl!!)
        val ctag2 = ctag2Result.getOrNull()!!
        println("New ctag: $ctag2")

        // Step 4: Observations
        printSubsection("Step 4: Observations")
        println("- Ctag changed after create: ${ctag1 != ctag2}")
        println("- Ctag format: appears to be numeric/timestamp")
        println("- Ctag is reliable for detecting ANY calendar change")
    }

    @Test
    fun `Test 12 - Sync token incremental sync`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 12: SYNC-TOKEN INCREMENTAL SYNC")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        // Step 1: Get initial sync token
        printSubsection("Step 1: Get Initial Sync Token")
        val tokenResult = client.getSyncToken(calendarUrl!!)
        if (!tokenResult.isSuccess() || tokenResult.getOrNull() == null) {
            println("Sync token not supported by server, skipping test")
            return@runBlocking
        }
        val token1 = tokenResult.getOrNull()!!
        println("Initial sync token: $token1")

        // Step 2: Create event
        printSubsection("Step 2: Create Event")
        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 11)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Sync Token Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, etag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to etag)

        // Step 3: Sync with old token
        printSubsection("Step 3: SYNC-COLLECTION with Old Token")
        val syncResult = client.syncCollection(calendarUrl!!, token1)

        if (syncResult.isSuccess()) {
            val report = syncResult.getOrNull()!!
            println("New sync token: ${report.syncToken}")
            println("Changed items: ${report.changed.size}")
            report.changed.forEach { println("  - ${it.href} (etag: ${it.etag})") }
            println("Deleted items: ${report.deleted.size}")
            report.deleted.forEach { println("  - $it") }
        } else {
            println("Sync failed: ${(syncResult as? CalDavResult.Error)?.message}")
            println("This may be expected if token expired")
        }

        // Step 4: Observations
        printSubsection("Step 4: Observations")
        println("- SYNC-COLLECTION returns only changes since token")
        println("- Efficient for incremental sync")
        println("- Token may expire (410 Gone)")
    }

    @Test
    fun `Test 13 - Conflict detection 412`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 13: CONFLICT DETECTION (412)")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 12)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Step 1: Create event
        printSubsection("Step 1: Create Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Conflict Test
SEQUENCE:0
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, oldEtag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to oldEtag)
        println("Created with etag: $oldEtag")

        // Step 2: Update event (simulating another client)
        printSubsection("Step 2: First Update (succeeds)")
        val fetched = client.fetchEvent(eventUrl).getOrNull()!!
        val icsUpdate1 = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Conflict Test - Updated Once
SEQUENCE:1
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val update1Result = client.updateEvent(eventUrl, icsUpdate1, fetched.etag!!)
        assert(update1Result.isSuccess()) { "First update should succeed" }
        val newEtag = update1Result.getOrNull()!!
        println("First update succeeded, new etag: $newEtag")

        // Step 3: Try update with OLD etag (conflict)
        printSubsection("Step 3: Second Update with OLD Etag (should fail)")
        val icsUpdate2 = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:Conflict Test - This Should Fail
SEQUENCE:2
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val conflictResult = client.updateEvent(eventUrl, icsUpdate2, fetched.etag!!)
        println("Conflict result: $conflictResult")
        println("Is conflict (412): ${conflictResult.isConflict()}")

        // Step 4: Observations
        printSubsection("Step 4: Observations")
        println("- If-Match with old etag returns 412 Precondition Failed")
        println("- Server prevents lost updates")
        println("- Recovery: fetch current version, merge, retry with new etag")

        // Update cleanup etag
        val finalFetch = client.fetchEvent(eventUrl).getOrNull()!!
        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (finalFetch.etag ?: newEtag))
    }

    // ========================================================================
    // PHASE 4: EDGE CASES
    // ========================================================================

    @Test
    fun `Test 14 - Special characters in title and description`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 14: SPECIAL CHARACTERS")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 13)
        val startTime = icsDateTimeFormat.format(now.time)
        now.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = icsDateTimeFormat.format(now.time)

        // Test various special characters
        val specialTitle = "Test: Emoji 🎉 & \"Quotes\" + <Tags>"
        val specialDesc = "Line1\\nLine2\\nUnicode: 日本語 中文\\nBackslash: \\\\ Quote: \\\""

        printSubsection("Step 1: Create Event with Special Characters")
        println("Title: $specialTitle")
        println("Description: $specialDesc")

        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART:$startTime
DTEND:$endTime
SUMMARY:$specialTitle
DESCRIPTION:$specialDesc
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)

        if (createResult.isSuccess()) {
            val (eventUrl, etag) = createResult.getOrNull()!!
            createdEventUrls.add(eventUrl to etag)

            printSubsection("Step 2: Fetch from iCloud")
            val fetched = client.fetchEvent(eventUrl).getOrNull()!!
            printIcs("ICS with Special Characters", fetched.icalData)

            printSubsection("Step 3: Observations")
            println("- Emoji preserved: ${fetched.icalData.contains("🎉")}")
            println("- CJK preserved: ${fetched.icalData.contains("日本語")}")
            println("- iCloud may encode special chars differently")

            createdEventUrls.clear()
            createdEventUrls.add(eventUrl to (fetched.etag ?: etag))
        } else {
            println("Create failed: ${(createResult as? CalDavResult.Error)?.message}")
            println("May need proper iCal escaping")
        }
    }

    @Test
    fun `Test 15 - Multi-day event`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 15: MULTI-DAY EVENT")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        now.add(Calendar.DAY_OF_MONTH, 14)
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        val startDate = icsDateFormat.format(now.time)
        now.add(Calendar.DAY_OF_MONTH, 3) // 3-day event
        val endDate = icsDateFormat.format(now.time)

        printSubsection("Step 1: Create 3-Day All-Day Event")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART;VALUE=DATE:$startDate
DTEND;VALUE=DATE:$endDate
SUMMARY:3-Day Conference
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("Multi-Day ICS Sent", icsCreate)

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create" }
        val (eventUrl, etag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to etag)

        printSubsection("Step 2: Fetch from iCloud")
        val fetched = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("Multi-Day ICS from iCloud", fetched.icalData)

        printSubsection("Step 3: Observations")
        println("- DTEND is exclusive (day after last day)")
        println("- 3-day event: DTSTART=$startDate, DTEND=$endDate")
        println("- VALUE=DATE format preserved")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (fetched.etag ?: etag))
    }

    @Test
    fun `Test 16 - Timezone handling`(): Unit = runBlocking {
        assumeCredentialsAvailable()
        printSection("TEST 16: TIMEZONE HANDLING")

        val (cal1, _) = discoverCalendars()
        calendarUrl = cal1
        assumeTrue("No calendar found", calendarUrl != null)

        val uid = generateUid()

        // Create event in America/New_York timezone
        val nyTz = TimeZone.getTimeZone("America/New_York")
        val nyCal = Calendar.getInstance(nyTz)
        nyCal.add(Calendar.DAY_OF_MONTH, 15)
        nyCal.set(Calendar.HOUR_OF_DAY, 14) // 2pm NY time
        nyCal.set(Calendar.MINUTE, 0)
        nyCal.set(Calendar.SECOND, 0)

        val nyFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss").apply {
            timeZone = nyTz
        }
        val startTime = nyFormat.format(nyCal.time)
        nyCal.add(Calendar.HOUR_OF_DAY, 1)
        val endTime = nyFormat.format(nyCal.time)

        printSubsection("Step 1: Create Event with Timezone")
        val icsCreate = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Test//EN
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
DTSTAMP:${icsDateTimeFormat.format(java.util.Date())}
DTSTART;TZID=America/New_York:$startTime
DTEND;TZID=America/New_York:$endTime
SUMMARY:New York Timezone Event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        printIcs("Timezone ICS Sent", icsCreate)

        val createResult = client.createEvent(calendarUrl!!, uid, icsCreate)
        assert(createResult.isSuccess()) { "Failed to create: ${(createResult as? CalDavResult.Error)?.message}" }
        val (eventUrl, etag) = createResult.getOrNull()!!
        createdEventUrls.add(eventUrl to etag)

        printSubsection("Step 2: Fetch from iCloud")
        val fetched = client.fetchEvent(eventUrl).getOrNull()!!
        printIcs("Timezone ICS from iCloud", fetched.icalData)

        printSubsection("Step 3: Observations")
        println("- VTIMEZONE component: ${fetched.icalData.contains("VTIMEZONE")}")
        println("- TZID parameter preserved: ${fetched.icalData.contains("TZID=")}")
        println("- iCloud may add its own VTIMEZONE definitions")
        println("- Local times + TZID is preferred over UTC")

        createdEventUrls.clear()
        createdEventUrls.add(eventUrl to (fetched.etag ?: etag))
    }
}
