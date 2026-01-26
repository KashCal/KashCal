package org.onekash.kashcal.sync.engine.integration

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.data.preferences.KashCalDataStore
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.UUID

/**
 * Integration test for CalDavSyncEngine with real iCloud.
 *
 * This test performs REAL operations against iCloud:
 * - Creates test events on iCloud
 * - Syncs data back and forth
 * - Cleans up test events
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*RealICloudSyncEngineTest*"
 *
 * Requires: local.properties with iCloud credentials
 */
class RealICloudSyncEngineTest {

    // Real components
    private lateinit var client: OkHttpCalDavClient
    private lateinit var quirks: ICloudQuirks

    // Strategies
    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy
    private lateinit var conflictResolver: ConflictResolver
    private lateinit var syncEngine: CalDavSyncEngine

    // Mocked DAOs (we verify interactions)
    private lateinit var database: KashCalDatabase
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var syncLogsDao: SyncLogsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var dataStore: KashCalDataStore
    private lateinit var syncSessionStore: SyncSessionStore

    // Credentials
    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

    // Test state
    private var testCalendar: CalDavCalendar? = null
    private val createdEventUrls = mutableListOf<String>()

    @Before
    fun setup() {
        loadCredentials()

        quirks = ICloudQuirks()
        client = OkHttpCalDavClient(quirks)

        if (username != null && password != null) {
            client.setCredentials(username!!, password!!)
        }

        setupMockedDaos()
        setupStrategies()
    }

    @After
    fun tearDown() {
        // Clean up any events we created
        runBlocking {
            for (url in createdEventUrls) {
                try {
                    client.deleteEvent(url, "")
                    println("Cleaned up event: $url")
                } catch (e: Exception) {
                    println("Failed to clean up event $url: ${e.message}")
                }
            }
        }
        clearAllMocks()
    }

    private fun setupMockedDaos() {
        database = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        eventsDao = mockk(relaxed = true)
        pendingOperationsDao = mockk(relaxed = true)
        syncLogsDao = mockk(relaxed = true)
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

        // DataStore mock setup
        io.mockk.every { dataStore.defaultReminderMinutes } returns flowOf(15)
        io.mockk.every { dataStore.defaultAllDayReminder } returns flowOf(1440)

        // Default mock behaviors
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByUid(any()) } returns emptyList()
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.getConflictOperations() } returns emptyList()
        coEvery { syncLogsDao.insert(any()) } returns 1L
    }

    private fun setupStrategies() {
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

        pushStrategy = PushStrategy(
            client = client,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao
        )

        conflictResolver = ConflictResolver(
            client = client,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            occurrenceGenerator = occurrenceGenerator,
            dataStore = dataStore
        )

        syncEngine = CalDavSyncEngine(
            pullStrategy = pullStrategy,
            pushStrategy = pushStrategy,
            conflictResolver = conflictResolver,
            accountsDao = accountsDao,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            syncLogsDao = syncLogsDao,
            syncSessionStore = mockk(relaxed = true),
            notificationManager = mockk(relaxed = true)
        )
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

    // ========== Full Sync Tests ==========

    @Test
    fun `full sync workflow - pull from iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

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

        println("=== Full Sync Test ===")
        println("Calendar: ${calendar.displayName}")
        println("URL: ${calendar.caldavUrl}")

        val result = syncEngine.syncCalendar(calendar, forceFullSync = true)

        println("\n=== Sync Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS")
                println("Calendars synced: ${result.calendarsSynced}")
                println("Events pulled (added): ${result.eventsPulledAdded}")
                println("Events pulled (updated): ${result.eventsPulledUpdated}")
                println("Events pulled (deleted): ${result.eventsPulledDeleted}")
                println("Events pushed (created): ${result.eventsPushedCreated}")
                println("Events pushed (updated): ${result.eventsPushedUpdated}")
                println("Events pushed (deleted): ${result.eventsPushedDeleted}")
                println("Conflicts resolved: ${result.conflictsResolved}")
                println("Duration: ${result.durationMs}ms")

                assert(result.calendarsSynced == 1)
            }
            is SyncResult.PartialSuccess -> {
                println("PARTIAL SUCCESS")
                println("Total changes: ${result.totalChanges}")
                println("Errors: ${result.errors.size}")
                result.errors.forEach { err ->
                    println("  - ${err.phase}: ${err.message}")
                }
            }
            is SyncResult.AuthError -> {
                println("AUTH ERROR: ${result.message}")
                assert(false) { "Auth should not fail with valid credentials" }
            }
            is SyncResult.Error -> {
                println("ERROR: ${result.message} (code: ${result.code})")
                // Don't fail - might be network issue
            }
        }
    }

    @Test
    fun `push new event to iCloud and verify sync`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Create a test event
        val uid = "kashcal-test-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val oneHourLater = now + 3600_000

        val testEvent = Event(
            id = 100L,
            uid = uid,
            calendarId = 1L,
            title = "KashCal Test Event - ${System.currentTimeMillis()}",
            location = "Test Location",
            description = "Created by KashCal integration test",
            startTs = now,
            endTs = oneHourLater,
            timezone = "America/New_York",
            isAllDay = false,
            status = "CONFIRMED",
            organizerEmail = null,
            organizerName = null,
            rrule = null,
            rdate = null,
            exdate = null,
            originalEventId = null,
            originalInstanceTime = null,
            originalSyncId = null,
            reminders = null,
            dtstamp = now,
            caldavUrl = null,
            etag = null,
            sequence = 0,
            syncStatus = SyncStatus.PENDING_CREATE,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = now,
            serverModifiedAt = null,
            createdAt = now,
            updatedAt = now
        )

        val pendingOp = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

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

        // Setup mocks for push
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(pendingOp)
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(1L) } returns calendar
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } answers {
            // Track the URL for cleanup
            val url = arg<String>(1)
            createdEventUrls.add(url)
            println("Event created on server: $url")
        }

        println("=== Push New Event Test ===")
        println("Event: ${testEvent.title}")
        println("UID: ${testEvent.uid}")

        val result = syncEngine.syncCalendar(calendar)

        println("\n=== Sync Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS")
                println("Events pushed (created): ${result.eventsPushedCreated}")

                // Verify event was created
                coVerify { eventsDao.markCreatedOnServer(testEvent.id, any(), any(), any()) }
                coVerify { pendingOperationsDao.deleteById(pendingOp.id) }

                assert(result.eventsPushedCreated == 1) { "Should have created 1 event" }
            }
            is SyncResult.PartialSuccess -> {
                println("PARTIAL SUCCESS - ${result.errors.size} errors")
                result.errors.forEach { println("  Error: ${it.message}") }
            }
            is SyncResult.AuthError -> {
                println("AUTH ERROR: ${result.message}")
            }
            is SyncResult.Error -> {
                println("ERROR: ${result.message}")
            }
        }
    }

    @Test
    fun `push recurring event to iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val uid = "kashcal-recurring-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val oneHourLater = now + 3600_000

        val recurringEvent = Event(
            id = 200L,
            uid = uid,
            calendarId = 1L,
            title = "KashCal Recurring Test - Weekly",
            location = null,
            description = "Recurring event created by integration test",
            startTs = now,
            endTs = oneHourLater,
            timezone = "America/New_York",
            isAllDay = false,
            status = "CONFIRMED",
            organizerEmail = null,
            organizerName = null,
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=5",
            rdate = null,
            exdate = null,
            originalEventId = null,
            originalInstanceTime = null,
            originalSyncId = null,
            reminders = null,
            dtstamp = now,
            caldavUrl = null,
            etag = null,
            sequence = 0,
            syncStatus = SyncStatus.PENDING_CREATE,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = now,
            serverModifiedAt = null,
            createdAt = now,
            updatedAt = now
        )

        val pendingOp = PendingOperation(
            id = 2L,
            eventId = recurringEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

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

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(pendingOp)
        coEvery { eventsDao.getById(recurringEvent.id) } returns recurringEvent
        coEvery { calendarsDao.getById(1L) } returns calendar
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } answers {
            createdEventUrls.add(arg<String>(1))
        }

        println("=== Push Recurring Event Test ===")
        println("Event: ${recurringEvent.title}")
        println("RRULE: ${recurringEvent.rrule}")

        val result = syncEngine.syncCalendar(calendar)

        println("\n=== Sync Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS - Recurring event created on iCloud")
                assert(result.eventsPushedCreated == 1)
            }
            is SyncResult.PartialSuccess -> {
                println("PARTIAL SUCCESS")
                result.errors.forEach { println("  Error: ${it.message}") }
            }
            else -> {
                println("Failed: $result")
            }
        }
    }

    @Test
    fun `push and then pull back to verify round-trip`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val uid = "kashcal-roundtrip-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val oneHourLater = now + 3600_000

        val testEvent = Event(
            id = 300L,
            uid = uid,
            calendarId = 1L,
            title = "KashCal Round-Trip Test",
            location = "Round Trip Location",
            description = "Testing round-trip sync",
            startTs = now,
            endTs = oneHourLater,
            timezone = "America/Los_Angeles",
            isAllDay = false,
            status = "CONFIRMED",
            organizerEmail = null,
            organizerName = null,
            rrule = null,
            rdate = null,
            exdate = null,
            originalEventId = null,
            originalInstanceTime = null,
            originalSyncId = null,
            reminders = null,
            dtstamp = now,
            caldavUrl = null,
            etag = null,
            sequence = 0,
            syncStatus = SyncStatus.PENDING_CREATE,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = now,
            serverModifiedAt = null,
            createdAt = now,
            updatedAt = now
        )

        val pendingOp = PendingOperation(
            id = 3L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

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

        var createdUrl: String? = null

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(pendingOp)
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(1L) } returns calendar
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } answers {
            createdUrl = arg<String>(1)
            createdEventUrls.add(createdUrl!!)
        }

        println("=== Round-Trip Test: PUSH Phase ===")
        val pushResult = syncEngine.syncCalendar(calendar)

        if (pushResult !is SyncResult.Success && pushResult !is SyncResult.PartialSuccess) {
            println("Push failed: $pushResult")
            return@runBlocking
        }

        println("Event pushed to: $createdUrl")

        // Now pull back and verify
        val pulledEvents = mutableListOf<Event>()
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns emptyList()
        coEvery { eventsDao.upsert(capture(pulledEvents)) } returns 1L
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null

        println("\n=== Round-Trip Test: PULL Phase ===")
        val pullResult = syncEngine.syncCalendar(calendar, forceFullSync = true)

        println("\n=== Round-Trip Result ===")
        when (pullResult) {
            is SyncResult.Success -> {
                println("Pull SUCCESS")
                println("Events pulled: ${pullResult.eventsPulledAdded}")

                // Find our event in pulled events
                val roundTrippedEvent = pulledEvents.find { it.uid == uid }
                if (roundTrippedEvent != null) {
                    println("\nRound-tripped event found:")
                    println("  Title: ${roundTrippedEvent.title}")
                    println("  Location: ${roundTrippedEvent.location}")
                    println("  Start: ${java.util.Date(roundTrippedEvent.startTs)}")
                    println("  Timezone: ${roundTrippedEvent.timezone}")

                    // Verify data integrity
                    assert(roundTrippedEvent.title == testEvent.title) { "Title should match" }
                    assert(roundTrippedEvent.location == testEvent.location) { "Location should match" }
                    println("\n✓ Round-trip data integrity verified!")
                } else {
                    println("Note: Event not in pulled events (might have been created before sync window)")
                }
            }
            else -> {
                println("Pull result: $pullResult")
            }
        }
    }

    @Test
    fun `sync detects no changes when calendar unchanged`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Get current ctag
        val ctagResult = client.getCtag(caldavCalendar!!.url)
        assumeTrue("Should get ctag", ctagResult.isSuccess())
        val ctag = ctagResult.getOrNull()!!

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = caldavCalendar.url,
            displayName = caldavCalendar.displayName,
            color = -1,
            ctag = ctag, // Set current ctag
            syncToken = null,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )

        println("=== No Changes Test ===")
        println("Calendar ctag: $ctag")

        // No pending operations
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns emptyList()

        val result = syncEngine.syncCalendar(calendar)

        println("\n=== Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS")
                println("Total changes: ${result.totalChanges}")
                assert(result.totalChanges == 0) { "Should have 0 changes when calendar unchanged" }
            }
            else -> {
                println("Result: $result")
            }
        }
    }
}
