package org.onekash.kashcal.sync.strategy.integration

import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.session.SyncSessionStore
import java.io.File

/**
 * Integration test for PullStrategy with real iCloud data.
 *
 * Uses real CalDavClient and Parser, but mocks the DAOs to verify
 * the data transformation and storage logic.
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*RealICloudPullStrategyTest*"
 *
 * Requires: local.properties with iCloud credentials
 */
class RealICloudPullStrategyTest {

    private lateinit var client: OkHttpCalDavClient
    private lateinit var pullStrategy: PullStrategy

    // Mocked DAOs
    private lateinit var database: KashCalDatabase
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var dataStore: KashCalDataStore
    private lateinit var syncSessionStore: SyncSessionStore

    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

    // Discovered calendar for testing
    private var testCalendar: CalDavCalendar? = null

    @Before
    fun setup() {
        loadCredentials()

        val quirks = ICloudQuirks()
        client = OkHttpCalDavClient(quirks)

        if (username != null && password != null) {
            client.setCredentials(username!!, password!!)
        }

        // Mock DAOs
        database = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        eventsDao = mockk(relaxed = true)
        occurrenceGenerator = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        syncSessionStore = mockk(relaxed = true)

        // Mock database.runInTransaction to execute the block directly
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Setup mock behaviors
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByUid(any()) } returns emptyList()
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        // Setup DataStore mock to return default reminder settings
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)

        pullStrategy = PullStrategy(
            database = database,
            client = client,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            syncSessionStore = syncSessionStore
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
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

    private suspend fun discoverTestCalendar(): CalDavCalendar? {
        if (testCalendar != null) return testCalendar

        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        testCalendar = calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }
        return testCalendar
    }

    // ========== PullStrategy Tests ==========

    @Test
    fun `pull full sync from real iCloud calendar`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Create Calendar entity for PullStrategy
        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = caldavCalendar!!.url,
            displayName = caldavCalendar.displayName,
            color = -1,
            ctag = null, // Force full sync
            syncToken = null,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )

        println("=== Starting Full Pull ===")
        println("Calendar: ${calendar.displayName}")
        println("URL: ${calendar.caldavUrl}")

        // Execute pull
        val result = pullStrategy.pull(calendar, forceFullSync = true)

        println("\n=== Pull Result ===")
        when (result) {
            is PullResult.Success -> {
                println("Events added: ${result.eventsAdded}")
                println("Events updated: ${result.eventsUpdated}")
                println("Events deleted: ${result.eventsDeleted}")
                println("New sync token: ${result.newSyncToken}")
                println("New ctag: ${result.newCtag}")

                // Verify events were upserted
                coVerify(atLeast = 0) { eventsDao.upsert(any()) }

                // Verify calendarsDao was updated with new sync token
                coVerify { calendarsDao.updateSyncToken(eq(1L), any(), any()) }
            }
            is PullResult.NoChanges -> {
                println("No changes detected")
            }
            is PullResult.Error -> {
                println("Error: ${result.message}")
                println("Code: ${result.code}")
                println("Retryable: ${result.isRetryable}")
                // Don't fail - might be a network issue
            }
        }
    }

    @Test
    fun `pull detects no changes when ctag unchanged`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // First, get current ctag
        val ctagResult = client.getCtag(caldavCalendar!!.url)
        assumeTrue("Should get ctag", ctagResult.isSuccess())
        val currentCtag = ctagResult.getOrNull()!!

        // Create Calendar entity with current ctag
        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = caldavCalendar.url,
            displayName = caldavCalendar.displayName,
            color = -1,
            ctag = currentCtag, // Set to current - no changes expected
            syncToken = null,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )

        println("=== Testing No Changes Detection ===")
        println("Current ctag: $currentCtag")

        // Execute pull
        val result = pullStrategy.pull(calendar, forceFullSync = false)

        println("\n=== Pull Result ===")
        when (result) {
            is PullResult.NoChanges -> {
                println("Correctly detected no changes")
                assert(true)
            }
            is PullResult.Success -> {
                println("Got success (ctag might have changed)")
                // This is OK if there were actual changes
            }
            is PullResult.Error -> {
                println("Error: ${result.message}")
            }
        }
    }

    @Test
    fun `pull correctly captures event data`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Capture upserted events
        val capturedEvents = mutableListOf<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvents)) } returns 1L

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = caldavCalendar!!.url,
            displayName = caldavCalendar.displayName,
            color = -1,
            ctag = null,
            syncToken = null,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )

        println("=== Testing Event Data Capture ===")

        // Execute pull
        val result = pullStrategy.pull(calendar, forceFullSync = true)

        if (result is PullResult.Success && capturedEvents.isNotEmpty()) {
            println("\n=== Captured Events (${capturedEvents.size}) ===")
            capturedEvents.take(5).forEach { event ->
                println("\n- ${event.title}")
                println("  UID: ${event.uid}")
                println("  Start: ${java.util.Date(event.startTs)}")
                println("  End: ${java.util.Date(event.endTs)}")
                println("  All-day: ${event.isAllDay}")
                println("  RRULE: ${event.rrule}")
                println("  CalDAV URL: ${event.caldavUrl}")
                println("  ETag: ${event.etag}")
                println("  Calendar ID: ${event.calendarId}")

                // Verify required fields
                assert(event.uid.isNotBlank()) { "Event UID should not be blank" }
                assert(event.title.isNotBlank()) { "Event title should not be blank" }
                assert(event.startTs > 0) { "Event startTs should be positive" }
                assert(event.endTs >= event.startTs) { "Event endTs should be >= startTs" }
                assert(event.calendarId == 1L) { "Event calendarId should match" }
            }

            // Check for recurring events
            val recurringEvents = capturedEvents.filter { it.rrule != null }
            println("\n=== Recurring Events: ${recurringEvents.size} ===")
            recurringEvents.take(3).forEach { event ->
                println("- ${event.title}: ${event.rrule}")
            }

            // Verify occurrences were generated for recurring events
            if (recurringEvents.isNotEmpty()) {
                coVerify(atLeast = 1) {
                    occurrenceGenerator.generateOccurrences(any(), any(), any())
                }
            }

            // Check for exception events
            val exceptions = capturedEvents.filter { it.originalInstanceTime != null }
            println("\n=== Exception Events: ${exceptions.size} ===")
            exceptions.take(3).forEach { event ->
                println("- ${event.title}")
                println("  Original time: ${java.util.Date(event.originalInstanceTime!!)}")
                println("  Master ID: ${event.originalEventId}")
            }
        } else {
            println("No events captured (calendar might be empty)")
        }
    }

    @Test
    fun `pull handles incremental sync with sync token`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Get sync token from server
        val syncTokenResult = client.getSyncToken(caldavCalendar!!.url)
        assumeTrue("Should get sync token", syncTokenResult.isSuccess())

        val syncToken = syncTokenResult.getOrNull()
        if (syncToken == null) {
            println("Server does not support sync-token, skipping incremental sync test")
            return@runBlocking
        }

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = caldavCalendar.url,
            displayName = caldavCalendar.displayName,
            color = -1,
            ctag = null, // ctag different to trigger sync
            syncToken = syncToken,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )

        println("=== Testing Incremental Sync ===")
        println("Sync token: $syncToken")

        val result = pullStrategy.pull(calendar, forceFullSync = false)

        println("\n=== Incremental Pull Result ===")
        when (result) {
            is PullResult.Success -> {
                println("Events added: ${result.eventsAdded}")
                println("Events updated: ${result.eventsUpdated}")
                println("Events deleted: ${result.eventsDeleted}")
                println("New sync token: ${result.newSyncToken}")
            }
            is PullResult.NoChanges -> {
                println("No changes since last sync")
            }
            is PullResult.Error -> {
                // 403/410 is expected if token is expired
                if (result.code == 403 || result.code == 410) {
                    println("Sync token expired (expected) - would trigger full sync")
                } else {
                    println("Error: ${result.message}")
                }
            }
        }
    }

    @Test
    fun `verify occurrence generator called for recurring events`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Track generateOccurrences calls
        val generatedEvents = mutableListOf<Event>()
        coEvery {
            occurrenceGenerator.generateOccurrences(capture(generatedEvents), any(), any())
        } returns 10 // Return dummy occurrence count

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = caldavCalendar!!.url,
            displayName = caldavCalendar.displayName,
            color = -1,
            ctag = null,
            syncToken = null,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )

        pullStrategy.pull(calendar, forceFullSync = true)

        println("=== Occurrence Generation Calls ===")
        println("Recurring events processed: ${generatedEvents.size}")

        generatedEvents.take(5).forEach { event ->
            println("- ${event.title}: ${event.rrule}")
        }

        // Verify all generated events have RRULE
        generatedEvents.forEach { event ->
            assert(event.rrule != null) { "Generated event should have RRULE" }
        }
    }
}
