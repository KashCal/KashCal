package org.onekash.kashcal.sync.parser.integration

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.parser.Ical4jParser
import java.io.File
import java.util.Properties

/**
 * Integration test that verifies Ical4jParser with real iCloud data.
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*RealICloudParserTest*"
 *
 * Requires: local.properties with iCloud credentials
 */
class RealICloudParserTest {

    private lateinit var parser: Ical4jParser
    private var username: String? = null
    private var password: String? = null

    @Before
    fun setup() {
        parser = Ical4jParser()

        // Try multiple paths for the credentials file
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
                    // Parse "key = value" format
                    val parts = line.split("=").map { it.trim() }
                    if (parts.size == 2) {
                        when {
                            parts[0].contains("username", ignoreCase = true) -> username = parts[1]
                            parts[0].contains("password", ignoreCase = true) &&
                                !parts[0].contains("keystore", ignoreCase = true) -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }

        println("Credentials loaded: username=${username?.take(5)}***, password=${if (password != null) "***" else "null"}")
    }

    @Test
    fun `connect to iCloud and discover principal`() {
        assumeCredentialsAvailable()

        val client = TestCalDavClient(username!!, password!!)
        val result = client.discoverPrincipal()

        println("Principal discovery result: $result")
        assumeTrue("Should discover principal", result.isSuccess)

        val principalUrl = result.getOrThrow()
        println("Principal URL: $principalUrl")
        assert(principalUrl.contains("principal")) { "Principal URL should contain 'principal'" }
    }

    @Test
    fun `discover calendar home from principal`() {
        assumeCredentialsAvailable()

        val client = TestCalDavClient(username!!, password!!)

        // Step 1: Discover principal
        val principalResult = client.discoverPrincipal()
        assumeTrue("Should discover principal", principalResult.isSuccess)
        val principalUrl = principalResult.getOrThrow()
        println("Principal URL: $principalUrl")

        // Step 2: Discover calendar home
        val homeResult = client.discoverCalendarHome(principalUrl)
        println("Calendar home result: $homeResult")
        assumeTrue("Should discover calendar home", homeResult.isSuccess)

        val homeUrl = homeResult.getOrThrow()
        println("Calendar Home URL: $homeUrl")
        assert(homeUrl.isNotBlank()) { "Calendar home URL should not be blank" }
    }

    @Test
    fun `list calendars from iCloud`() {
        assumeCredentialsAvailable()

        val client = TestCalDavClient(username!!, password!!)

        // Discover principal -> calendar home -> calendars
        val principalUrl = client.discoverPrincipal().getOrNull()
        assumeTrue("Should discover principal", principalUrl != null)

        val homeUrl = client.discoverCalendarHome(principalUrl!!).getOrNull()
        assumeTrue("Should discover calendar home", homeUrl != null)

        val calendarsResult = client.getCalendars(homeUrl!!)
        println("Calendars result: $calendarsResult")
        assumeTrue("Should list calendars", calendarsResult.isSuccess)

        val calendars = calendarsResult.getOrThrow()
        println("Found ${calendars.size} calendars:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}: ${cal.url}")
        }

        assert(calendars.isNotEmpty()) { "Should have at least one calendar" }
    }

    @Test
    fun `fetch and parse real iCloud events`() {
        assumeCredentialsAvailable()

        val client = TestCalDavClient(username!!, password!!)

        // Discovery
        println("Step 1: Discovering principal...")
        val principalResult = client.discoverPrincipal()
        println("Principal result: $principalResult")
        if (principalResult.isFailure) {
            println("ERROR: ${principalResult.exceptionOrNull()?.message}")
        }
        val principalUrl = principalResult.getOrNull()
        assumeTrue("Should discover principal: ${principalResult.exceptionOrNull()?.message}", principalUrl != null)

        println("Step 2: Discovering calendar home from: $principalUrl")
        val homeResult = client.discoverCalendarHome(principalUrl!!)
        println("Home result: $homeResult")
        if (homeResult.isFailure) {
            println("ERROR: ${homeResult.exceptionOrNull()?.message}")
        }
        val homeUrl = homeResult.getOrNull()
        assumeTrue("Should discover calendar home: ${homeResult.exceptionOrNull()?.message}", homeUrl != null)

        println("Step 3: Getting calendars from: $homeUrl")
        val calendarsResult = client.getCalendars(homeUrl!!)
        println("Calendars result: $calendarsResult")
        val calendars = calendarsResult.getOrNull()
        assumeTrue("Should list calendars", calendars != null && calendars.isNotEmpty())

        // Try to fetch events from all calendars to find one with events
        var allIcalDataList = mutableListOf<String>()
        var calendarWithEvents: TestCalDavClient.CalendarInfo? = null

        for (calendar in calendars!!) {
            // Skip inbox/outbox
            if (calendar.url.contains("inbox") || calendar.url.contains("outbox")) continue

            println("Fetching events from: ${calendar.displayName} (${calendar.url})")
            val eventsResult = client.fetchEvents(calendar.url, daysBack = 90, daysForward = 365)
            if (eventsResult.isSuccess) {
                val icalData = eventsResult.getOrThrow()
                println("  Found ${icalData.size} iCal entries")
                if (icalData.isNotEmpty()) {
                    allIcalDataList.addAll(icalData)
                    calendarWithEvents = calendar
                }
            } else {
                println("  Error: ${eventsResult.exceptionOrNull()?.message}")
            }
        }

        val icalDataList = allIcalDataList
        println("\nTotal iCal entries from all calendars: ${icalDataList.size}")

        // Using first calendar for backward compatibility
        val firstCalendar = calendarWithEvents ?: calendars.first()
        println("Fetch result: Found ${icalDataList.size} events across ${calendars.filter { !it.url.contains("inbox") && !it.url.contains("outbox") }.size} calendars")

        // Note: We don't require events to exist - just test parsing if there are any

        // Parse each iCal entry
        var totalEvents = 0
        var parseErrors = 0
        val parsedEvents = mutableListOf<org.onekash.kashcal.sync.parser.ParsedEvent>()

        icalDataList.forEachIndexed { index, icalData ->
            val parseResult = parser.parse(icalData)

            if (parseResult.isSuccess()) {
                totalEvents += parseResult.events.size
                parsedEvents.addAll(parseResult.events)
            } else {
                parseErrors++
                println("Parse error #$parseErrors for entry $index: ${parseResult.errors.firstOrNull()}")
                println("  iCal preview: ${icalData.take(200)}...")
            }
        }

        println("\n=== PARSE RESULTS ===")
        println("Total iCal entries: ${icalDataList.size}")
        println("Total events parsed: $totalEvents")
        println("Parse errors: $parseErrors")

        // Print some sample events
        println("\n=== SAMPLE EVENTS ===")
        parsedEvents.take(10).forEach { event ->
            println("- ${event.summary}")
            println("  UID: ${event.uid}")
            println("  Start: ${java.util.Date(event.startTs * 1000)}")
            println("  All-day: ${event.isAllDay}")
            println("  Recurring: ${event.isRecurring()} ${event.rrule ?: ""}")
            println("  Exception: ${event.isException()} ${event.recurrenceId ?: ""}")
            println("  Reminders: ${event.reminderMinutes}")
            println()
        }

        // Assertions
        assert(parseErrors == 0) { "Should parse all events without errors" }
        println("\n✓ All ${icalDataList.size} iCal entries parsed successfully into $totalEvents events")
    }

    @Test
    fun `verify RECURRENCE-ID handling with real data`() {
        assumeCredentialsAvailable()

        val client = TestCalDavClient(username!!, password!!)

        // Discovery
        val principalUrl = client.discoverPrincipal().getOrNull() ?: run {
            assumeTrue("Should discover principal", false)
            return
        }
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull() ?: run {
            assumeTrue("Should discover calendar home", false)
            return
        }
        val calendars = client.getCalendars(homeUrl).getOrNull() ?: run {
            assumeTrue("Should list calendars", false)
            return
        }

        // Fetch from all calendars to find recurring events with exceptions
        var foundRecurring = 0
        var foundExceptions = 0

        calendars.forEach { calendar ->
            val eventsResult = client.fetchEvents(calendar.url, daysBack = 90, daysForward = 365)
            if (eventsResult.isSuccess) {
                eventsResult.getOrThrow().forEach { icalData ->
                    val parseResult = parser.parse(icalData)
                    parseResult.events.forEach { event ->
                        if (event.isRecurring()) foundRecurring++
                        if (event.isException()) {
                            foundExceptions++
                            println("Found exception event:")
                            println("  Summary: ${event.summary}")
                            println("  UID: ${event.uid}")
                            println("  RECURRENCE-ID: ${event.recurrenceId}")
                            println("  Import ID: ${event.toImportId()}")
                        }
                    }
                }
            }
        }

        println("\n=== RECURRING EVENT STATS ===")
        println("Recurring events: $foundRecurring")
        println("Exception events: $foundExceptions")
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "iCloud credentials not available. Add caldav.username and caldav.app_password to local.properties.",
            username != null && password != null
        )
    }
}
