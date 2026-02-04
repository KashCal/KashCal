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
 * Integration test for Stalwart CalDAV server workflows.
 *
 * Stalwart is a modern, Rust-based mail server with full CalDAV/CardDAV support.
 * It uses RFC 6764 well-known discovery and standard CalDAV endpoints.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*StalwartCalDavIntegrationTest*"
 *
 * Prerequisites:
 * - Stalwart server running at localhost:8080
 * - Credentials in local.properties:
 *   STALWART_SERVER=http://localhost:8080
 *   STALWART_USERNAME=testuser@example.com
 *   STALWART_PASSWORD=testpass123
 *
 * Start Stalwart:
 *   docker run -d --name stalwart-caldav-test -p 8080:8080 stalwartlabs/mail-server
 *   Then setup admin account at http://localhost:8080/login
 *
 * Stalwart features:
 * - RFC 6764 well-known discovery (.well-known/caldav)
 * - CalDAV Scheduling (RFC 6638)
 * - JMAP for Calendars
 * - WebDAV sync-collection
 */
class StalwartCalDavIntegrationTest {

    private lateinit var client: CalDavClient
    private lateinit var clientFactory: OkHttpCalDavClientFactory
    private var serverUrl: String = "http://localhost:8080"
    private var username: String? = null
    private var password: String? = null

    // Test state
    private var calendarUrl: String? = null
    private var testEventUrl: String? = null
    private var testEventEtag: String? = null
    // Use UUID-only format to avoid URL encoding issues with '@' character
    private val testUid = "test-stalwart-${UUID.randomUUID()}"

    // Date formatter for ICS
    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        loadCredentials()

        // Use DefaultQuirks for generic CalDAV
        val quirks = DefaultQuirks(serverUrl)
        clientFactory = OkHttpCalDavClientFactory()

        // Create client using factory pattern
        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = serverUrl
            )
            client = clientFactory.createClient(credentials, quirks)
        } else {
            // Create a dummy client for cases where credentials aren't available
            val dummyCredentials = Credentials(
                username = "dummy",
                password = "dummy",
                serverUrl = serverUrl
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
                            "STALWART_SERVER" -> serverUrl = parts[1]
                            "STALWART_USERNAME" -> username = parts[1]
                            "STALWART_PASSWORD" -> password = parts[1]
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
            assumeTrue("Stalwart server not available (code: $responseCode)", responseCode in 200..299 || responseCode == 401)
        } catch (e: Exception) {
            assumeTrue("Stalwart server not reachable: ${e.message}", false)
        }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "Stalwart credentials not available in local.properties",
            username != null && password != null
        )
    }

    private suspend fun getCaldavUrl(): String {
        // Stalwart requires well-known discovery to get /dav/cal URL
        val wellKnownResult = client.discoverWellKnown(serverUrl)
        return if (wellKnownResult.isSuccess()) {
            wellKnownResult.getOrNull()!!
        } else {
            serverUrl
        }
    }

    private suspend fun discoverCalendar(): String? {
        // Try well-known discovery first (RFC 6764)
        val wellKnownResult = client.discoverWellKnown(serverUrl)
        val caldavUrl = if (wellKnownResult.isSuccess()) {
            val discoveredUrl = wellKnownResult.getOrNull()!!
            println("Well-known discovery found: $discoveredUrl")
            discoveredUrl
        } else {
            serverUrl
        }

        val principal = client.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        val calendar = calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url

        // NOTE: Fallback no longer needed after RFC 4918 multi-propstat fix (v21.5.8)
        // Parser now correctly handles 404 propstat for missing optional properties.
        // Keeping fallback as safety net but it should not be reached.
        if (calendar == null && home.contains("dav/cal")) {
            println("WARNING: Fallback triggered - parser may have regressed")
            val fallbackUrl = serverUrl.trimEnd('/') + "/dav/cal/_4294967295/test-calendar/"
            return fallbackUrl
        }

        return calendar
    }

    // ========== DISCOVERY TESTS ==========

    @Test
    fun `well-known discovery on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val result = client.discoverWellKnown(serverUrl)
        if (result.isSuccess()) {
            val discoveredUrl = result.getOrNull()!!
            println("Stalwart well-known CalDAV URL: $discoveredUrl")
            assert(discoveredUrl.isNotEmpty()) { "Well-known URL should not be empty" }
        } else {
            println("Well-known discovery not available (falling back to direct discovery)")
            // Not a failure - some Stalwart configs may not have well-known
        }
    }

    @Test
    fun `discover principal URL on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val caldavUrl = getCaldavUrl()
        val result = client.discoverPrincipal(caldavUrl)
        assert(result.isSuccess()) { "Failed to discover principal: ${(result as? CalDavResult.Error)?.message}" }

        val principal = result.getOrNull()!!
        println("Stalwart principal URL: $principal")
        assert(principal.isNotEmpty()) { "Principal should not be empty" }
    }

    @Test
    fun `discover calendar home on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val caldavUrl = getCaldavUrl()
        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val result = client.discoverCalendarHome(principal!!)
        assert(result.isSuccess()) { "Failed to discover calendar home: ${(result as? CalDavResult.Error)?.message}" }

        val home = result.getOrNull()!!
        println("Stalwart calendar home: $home")
        assert(home.isNotEmpty()) { "Calendar home should not be empty" }
    }

    @Test
    fun `list calendars on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        val caldavUrl = getCaldavUrl()
        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val result = client.listCalendars(home!!)
        assert(result.isSuccess()) { "Failed to list calendars: ${(result as? CalDavResult.Error)?.message}" }

        val calendars = result.getOrNull()!!
        println("Found ${calendars.size} calendar(s) on Stalwart:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}: ${cal.url} (readOnly=${cal.isReadOnly}, color=${cal.color})")
        }

        // After RFC 4918 multi-propstat fix (v21.5.8), calendars should be discovered
        assert(calendars.isNotEmpty()) {
            "Should find at least one calendar on Stalwart (RFC 4918 multi-propstat fix)"
        }
    }

    // ========== CRUD TESTS ==========

    @Test
    fun `create and fetch event on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Stalwart Test Event
DESCRIPTION:Created by integration test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, testUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        println("Created event on Stalwart: $url (etag: $etag)")

        // Verify by fetching
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS from Stalwart:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("SUMMARY:Stalwart Test Event"))
    }

    @Test
    fun `update event on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        val updateUid = "test-update-stalwart-${UUID.randomUUID()}"

        // Create initial event
        val initialIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
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
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$updateUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Updated Title on Stalwart
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
        assert(fetchedIcs.contains("SUMMARY:Updated Title on Stalwart")) { "Title should be updated" }
    }

    @Test
    fun `delete event on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        val deleteUid = "test-delete-stalwart-${UUID.randomUUID()}"

        // Create event
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
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

    // ========== RECURRING EVENT WITH EXCEPTION TEST ==========

    @Test
    fun `recurring event with exception on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        println("\n" + "=".repeat(60))
        println("STALWART RECURRING EVENT EXCEPTION WORKFLOW")
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

        println("Date plan:")
        println("  First occurrence: $firstOccurrenceStr (Mon 10am)")
        println("  Second occurrence: $secondOccurrenceStr (Mon 10am)")
        println("  Exception rescheduled to: $exceptionTimeStr (Mon 2pm)")
        println()

        // Step 1: Create recurring event
        println("Step 1: Creating recurring event...")
        val createIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Stalwart Weekly Meeting
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, testUid, createIcs)
        assert(createResult.isSuccess()) { "Failed to create recurring event: ${(createResult as? CalDavResult.Error)?.message}" }

        val (url, etag) = createResult.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag
        println("Created: $url")

        // Step 2: Add exception (reschedule second occurrence)
        println("\nStep 2: Adding exception (reschedule second occurrence)...")
        val exceptionIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
SUMMARY:Stalwart Weekly Meeting
SEQUENCE:1
END:VEVENT
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
RECURRENCE-ID:$secondOccurrenceStr
DTSTART:$exceptionTimeStr
DTEND:${exceptionTimeStr.replace("T14", "T15")}
SUMMARY:Stalwart Weekly Meeting (Rescheduled)
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
        println("Fetched ICS from Stalwart:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("RECURRENCE-ID:")) { "Stalwart should preserve RECURRENCE-ID" }

        val veventCount = fetchedIcs.split("BEGIN:VEVENT").size - 1
        println("\nVEVENT count: $veventCount (expected: 2)")
        assert(veventCount >= 2) { "Should have master + exception VEVENTs" }

        println("\n" + "=".repeat(60))
        println("STALWART WORKFLOW TEST COMPLETE")
        println("=".repeat(60))
    }

    // ========== CANCELLED OCCURRENCE (EXDATE) TEST ==========

    @Test
    fun `recurring event with cancelled occurrence (EXDATE) on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        val exdateUid = "test-exdate-stalwart-${UUID.randomUUID()}"

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

        // Third occurrence (2 weeks later) - will be cancelled
        cal.add(Calendar.WEEK_OF_YEAR, 2)
        val thirdOccurrenceStr = icsDateFormat.format(cal.time)

        // Create recurring event with EXDATE
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$exdateUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:$firstOccurrenceStr
DTEND:${firstOccurrenceStr.replace("T10", "T11")}
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE:$thirdOccurrenceStr
SUMMARY:Weekly Event with Cancelled Occurrence
DESCRIPTION:Third occurrence is cancelled via EXDATE
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
        println("Fetched ICS with EXDATE from Stalwart:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("EXDATE:")) { "EXDATE should be preserved by Stalwart" }
        assert(fetchedIcs.contains(thirdOccurrenceStr)) { "EXDATE should contain cancelled date" }
    }

    // ========== SYNC-COLLECTION TEST ==========

    @Test
    fun `get sync token from Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        val result = client.getSyncToken(calendarUrl!!)
        assert(result.isSuccess()) { "Failed to get sync token: ${(result as? CalDavResult.Error)?.message}" }

        val token = result.getOrNull()!!
        println("Stalwart sync token: $token")
        assert(token.isNotEmpty()) { "Sync token should not be empty" }
    }

    @Test
    fun `sync-collection delta sync on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        // Get initial sync token
        val tokenResult = client.getSyncToken(calendarUrl!!)
        assumeTrue("Could not get sync token", tokenResult.isSuccess())
        val initialToken = tokenResult.getOrNull()!!
        println("Initial sync token: $initialToken")

        // Create an event
        val deltaUid = "test-delta-stalwart-${UUID.randomUUID()}"
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$deltaUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260301T100000Z
DTEND:20260301T110000Z
SUMMARY:Delta Sync Test Event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl!!, deltaUid, icsContent)
        assert(createResult.isSuccess()) { "Failed to create event" }

        val (url, etag) = createResult.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        // Get changes since initial token
        val syncResult = client.syncCollection(calendarUrl!!, initialToken)
        assert(syncResult.isSuccess()) { "Failed to sync collection: ${(syncResult as? CalDavResult.Error)?.message}" }

        val syncReport = syncResult.getOrNull()!!
        println("Sync report:")
        println("  Changed: ${syncReport.changed.size}")
        println("  Deleted: ${syncReport.deleted.size}")
        println("  New token: ${syncReport.syncToken}")

        // Should have at least our new event
        assert(syncReport.changed.isNotEmpty() || syncReport.syncToken != initialToken) {
            "Sync should detect our new event or provide new token"
        }
    }

    // ========== ALARM TEST ==========

    @Test
    fun `event with alarm on Stalwart`() = runBlocking {
        assumeServerAvailable()
        assumeCredentialsAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Stalwart", calendarUrl != null)

        val alarmUid = "test-alarm-stalwart-${UUID.randomUUID()}"

        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Stalwart Test//EN
BEGIN:VEVENT
UID:$alarmUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260401T100000Z
DTEND:20260401T110000Z
SUMMARY:Event with Alarm
BEGIN:VALARM
TRIGGER:-PT15M
ACTION:DISPLAY
DESCRIPTION:15 minutes before
END:VALARM
BEGIN:VALARM
TRIGGER:-PT1H
ACTION:DISPLAY
DESCRIPTION:1 hour before
END:VALARM
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, alarmUid, icsContent)
        assert(result.isSuccess()) { "Failed to create event with alarm: ${(result as? CalDavResult.Error)?.message}" }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        // Verify alarms are preserved
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS with alarms from Stalwart:")
        println(fetchedIcs)

        assert(fetchedIcs.contains("BEGIN:VALARM")) { "VALARM should be preserved" }
        assert(fetchedIcs.contains("TRIGGER:")) { "TRIGGER should be preserved" }
    }
}
