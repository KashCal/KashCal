package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.model.ParseResult
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.util.Properties

/**
 * Integration test for icaldav library migration.
 * Tests parsing and iCloud sync with real credentials.
 */
class ICalDavMigrationTest {

    private lateinit var parser: ICalParser
    private lateinit var calDavClient: OkHttpCalDavClient
    private var username: String? = null
    private var password: String? = null

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        parser = ICalParser()
        calDavClient = OkHttpCalDavClient(ICloudQuirks())

        loadCredentials()
    }

    @After
    fun tearDown() {
        calDavClient.clearCredentials()
        unmockkAll()
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "/onekash/KashCal/local.properties",
            "../local.properties",
            "local.properties"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                username = props.getProperty("ICLOUD_USERNAME")
                    ?: props.getProperty("caldav.username")
                password = props.getProperty("ICLOUD_APP_PASSWORD")
                if (username != null && password != null) {
                    println("Loaded credentials from $path")
                    return
                }
            }
        }
        println("No credentials found")
    }

    // ==================== Parser Tests ====================

    @Test
    fun `ICalParser initializes without error`() {
        val parser = ICalParser()
        assertNotNull(parser)
        println("✓ ICalParser initialized successfully")
    }

    @Test
    fun `ICalParser parses simple event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-123@test.com
            DTSTAMP:20250122T100000Z
            DTSTART:20250125T100000Z
            DTEND:20250125T110000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)

        assertTrue("Should parse successfully", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertEquals(1, events.size)
        assertEquals("test-123@test.com", events[0].uid)
        assertEquals("Test Event", events[0].summary)
        println("✓ Parsed simple event: ${events[0].summary}")
    }

    @Test
    fun `ICalParser parses recurring event with exception`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-123@test.com
            DTSTAMP:20250122T100000Z
            DTSTART:20250120T100000Z
            DTEND:20250120T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring-123@test.com
            RECURRENCE-ID:20250127T100000Z
            DTSTAMP:20250122T100000Z
            DTSTART:20250127T140000Z
            DTEND:20250127T150000Z
            SUMMARY:Weekly Meeting - Moved
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)

        assertTrue("Should parse successfully", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertEquals("Should have 2 events (master + exception)", 2, events.size)

        val master = events.find { it.recurrenceId == null }
        val exception = events.find { it.recurrenceId != null }

        assertNotNull("Should have master event", master)
        assertNotNull("Should have exception event", exception)
        assertNotNull("Master should have RRULE", master?.rrule)
        assertNull("Exception should NOT have RRULE", exception?.rrule)

        println("✓ Parsed recurring event with exception")
        println("  Master: ${master?.summary}, RRULE: ${master?.rrule}")
        println("  Exception: ${exception?.summary}, RECURRENCE-ID: ${exception?.recurrenceId}")
    }

    @Test
    fun `ICalParser parses event with multiple alarms`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarms-123@test.com
            DTSTAMP:20250122T100000Z
            DTSTART:20250125T100000Z
            DTEND:20250125T110000Z
            SUMMARY:Event with Alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT2H
            DESCRIPTION:2 hours before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(ics)

        assertTrue("Should parse successfully", result is ParseResult.Success)
        val events = (result as ParseResult.Success).value
        assertEquals(1, events.size)
        assertEquals("Should have 5 alarms", 5, events[0].alarms.size)

        println("✓ Parsed event with ${events[0].alarms.size} alarms")
        events[0].alarms.forEach { alarm ->
            println("  - Trigger: ${alarm.trigger}")
        }
    }

    // ==================== iCloud Sync Tests ====================

    /**
     * Helper to discover calendar home via principal.
     * Returns null if discovery fails.
     */
    private suspend fun discoverCalendarHome(): String? {
        val principalResult = calDavClient.discoverPrincipal("https://caldav.icloud.com")
        if (principalResult !is CalDavResult.Success) {
            println("Principal discovery failed: ${(principalResult as CalDavResult.Error).message}")
            return null
        }
        println("Principal: ${principalResult.data}")

        val homeResult = calDavClient.discoverCalendarHome(principalResult.data)
        if (homeResult !is CalDavResult.Success) {
            println("Home discovery failed: ${(homeResult as CalDavResult.Error).message}")
            return null
        }
        return homeResult.data
    }

    @Test
    fun `iCloud discovery works with icaldav`() = runTest {
        if (username == null || password == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        calDavClient.setCredentials(username!!, password!!)

        try {
            val homeUrl = discoverCalendarHome()

            if (homeUrl != null) {
                println("✓ Discovered calendar home: $homeUrl")
                assertTrue("Should be iCloud URL", homeUrl.contains("icloud.com"))
            } else {
                fail("Discovery should succeed with valid credentials")
            }
        } catch (e: Exception) {
            println("Network error: ${e.message}")
        }
    }

    @Test
    fun `iCloud list calendars works with icaldav`() = runTest {
        if (username == null || password == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        calDavClient.setCredentials(username!!, password!!)

        try {
            // First discover home via principal
            val homeUrl = discoverCalendarHome()
            if (homeUrl == null) {
                println("SKIPPED: Could not discover home")
                return@runTest
            }

            // Then list calendars
            val calendarsResult = calDavClient.listCalendars(homeUrl)

            when (calendarsResult) {
                is CalDavResult.Success -> {
                    val calendars = calendarsResult.data
                    println("✓ Found ${calendars.size} calendars:")
                    calendars.forEach { cal ->
                        println("  - ${cal.displayName} (${cal.href})")
                    }
                    assertTrue("Should have at least one calendar", calendars.isNotEmpty())
                }
                is CalDavResult.Error -> {
                    println("✗ List calendars failed: ${calendarsResult.code} - ${calendarsResult.message}")
                    fail("List calendars should succeed")
                }
            }
        } catch (e: Exception) {
            println("Network error: ${e.message}")
        }
    }

    @Test
    fun `iCloud fetch and parse events works with icaldav`() = runTest {
        if (username == null || password == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        calDavClient.setCredentials(username!!, password!!)

        try {
            // Discover and list calendars via principal
            val homeUrl = discoverCalendarHome()
            if (homeUrl == null) {
                println("SKIPPED: Could not discover home")
                return@runTest
            }

            val calendarsResult = calDavClient.listCalendars(homeUrl)
            if (calendarsResult !is CalDavResult.Success || calendarsResult.data.isEmpty()) {
                println("SKIPPED: No calendars found")
                return@runTest
            }

            // Fetch events from first calendar (last 30 days to next 30 days)
            val calendar = calendarsResult.data.first()
            println("Fetching events from: ${calendar.displayName} (${calendar.url})")

            val now = System.currentTimeMillis()
            val startMillis = now - 30L * 24 * 60 * 60 * 1000  // 30 days ago
            val endMillis = now + 30L * 24 * 60 * 60 * 1000    // 30 days from now

            val eventsResult = calDavClient.fetchEventsInRange(calendar.url, startMillis, endMillis)

            when (eventsResult) {
                is CalDavResult.Success -> {
                    val serverEvents = eventsResult.data
                    println("✓ Fetched ${serverEvents.size} events from server")

                    // Parse each event with icaldav
                    var parseSuccess = 0
                    var parseFailed = 0

                    for (serverEvent in serverEvents.take(10)) { // Test first 10
                        val parseResult = parser.parseAllEvents(serverEvent.icalData)
                        when (parseResult) {
                            is ParseResult.Success -> {
                                parseSuccess++
                                val events = parseResult.value
                                if (events.isNotEmpty()) {
                                    println("  ✓ ${events[0].summary ?: "(no title)"}")
                                }
                            }
                            is ParseResult.Error -> {
                                parseFailed++
                                println("  ✗ Parse failed: ${parseResult.error.message}")
                            }
                        }
                    }

                    println("\n✓ Parse results: $parseSuccess success, $parseFailed failed")
                    assertTrue("Most events should parse", parseSuccess >= parseFailed)
                }
                is CalDavResult.Error -> {
                    println("✗ Fetch events failed: ${eventsResult.code} - ${eventsResult.message}")
                    fail("Fetch events should succeed")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error: ${e.message}")
        }
    }
}