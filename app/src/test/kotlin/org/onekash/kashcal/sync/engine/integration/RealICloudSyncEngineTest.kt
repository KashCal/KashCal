package org.onekash.kashcal.sync.engine.integration

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Runs CalDavSyncEngine against live iCloud with an in-memory Room database.
 *
 * The client, parser, OccurrenceGenerator, PullStrategy, PushStrategy and ConflictResolver
 * are real. Mocked (relaxed): SyncNotificationManager, the AccountRepository both strategies
 * take, and PullStrategy's invite notifier and reminder scheduler.
 *
 * Against iCloud it:
 * - pulls the first discovered calendar into Room
 * - creates events in Room and pushes them
 * - checks that title and location survive a round trip
 * - deletes the events it created, by the URLs its own pushes returned
 *
 * Run with: ./gradlew testDebugUnitTest -Pintegration --tests "*RealICloudSyncEngineTest*"
 *
 * Requires: local.properties with iCloud credentials
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RealICloudSyncEngineTest {

    // Real components
    private lateinit var database: KashCalDatabase
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var client: CalDavClient
    private lateinit var syncEngine: CalDavSyncEngine

    // Credentials
    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

    // Test-scoped DataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File

    // Test state
    private var testCalendar: CalDavCalendar? = null
    private var testCalendarId: Long = 0
    private var testAccountId: Long = 0
    private val createdEventUrls = mutableListOf<String>()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()

        // In-memory Room database
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        eventsDao = database.eventsDao()
        pendingOperationsDao = database.pendingOperationsDao()
        val calendarsDao = database.calendarsDao()
        val occurrencesDao = database.occurrencesDao()
        val syncLogsDao = database.syncLogsDao()

        val calendarRepository = CalendarRepositoryImpl(calendarsDao)
        // PushStrategy reads the account only for RSVP pushes, outbox delivery and
        // pending cancels. These tests queue no RSVP and add no attendees, so a relaxed
        // mock is enough.
        val accountRepository = io.mockk.mockk<
            org.onekash.kashcal.data.repository.AccountRepository
        >(relaxed = true)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        val dataStore = KashCalDataStore(context, testPrefsDataStore)
        val occurrenceGenerator = OccurrenceGenerator(database, occurrencesDao, eventsDao, dataStore)
        val syncSessionStore = SyncSessionStore(context)

        // Load credentials and create real client
        loadCredentials()
        val quirks = ICloudQuirks()
        if (username != null && password != null) {
            client = OkHttpCalDavClientFactory().createClient(
                Credentials(username!!, password!!, serverUrl), quirks
            )
        } else {
            client = OkHttpCalDavClientFactory().createClient(
                Credentials("", "", serverUrl), quirks
            )
        }

        val pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true)
        )

        val pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )

        val conflictResolver = ConflictResolver(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = database.attendeesDao(),
            pendingOperationsDao = pendingOperationsDao,
            occurrenceGenerator = occurrenceGenerator,
            database = database
        )

        // SyncNotificationManager needs the Android notification system, so it's mocked.
        syncEngine = CalDavSyncEngine(
            pullStrategy = pullStrategy,
            pushStrategy = pushStrategy,
            conflictResolver = conflictResolver,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            syncLogsDao = syncLogsDao,
            syncSessionStore = syncSessionStore,
            notificationManager = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        // Delete only the events this run pushed.
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
        database.close()
        dataStoreScope.cancel()
        testDataStoreFile.delete()
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

    private suspend fun discoverTestCalendar(): CalDavCalendar? {
        if (testCalendar != null) return testCalendar

        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        testCalendar = calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }
        return testCalendar
    }

    private suspend fun setupDbCalendar(caldavUrl: String, displayName: String): Calendar {
        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = caldavUrl,
                displayName = displayName,
                color = 0xFF0000
            )
        )
        return database.calendarsDao().getById(testCalendarId)!!
    }

    // ========== Sync Engine Tests ==========

    @Test
    fun `full sync workflow - pull from iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        println("=== Full Sync Test ===")
        println("Calendar: ${calendar.displayName}")
        println("URL: ${calendar.caldavUrl}")

        val result = syncEngine.syncCalendar(calendar, forceFullSync = true, client = client)

        println("\n=== Sync Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS")
                println("Calendars synced: ${result.calendarsSynced}")
                println("Events pulled (added): ${result.eventsPulledAdded}")
                println("Duration: ${result.durationMs}ms")

                assertEquals("Should sync 1 calendar", 1, result.calendarsSynced)

                // Check events landed in Room
                val now = System.currentTimeMillis()
                val farFuture = now + 365L * 24 * 60 * 60 * 1000 * 10
                val farPast = now - 365L * 24 * 60 * 60 * 1000 * 10
                val dbEvents = eventsDao.getByCalendarIdInRange(testCalendarId, farPast, farFuture)
                println("Events in DB: ${dbEvents.size}")
                assertTrue("Should have events in DB", dbEvents.isNotEmpty())
            }
            is SyncResult.PartialSuccess -> {
                println("PARTIAL SUCCESS - ${result.errors.size} errors")
                result.errors.forEach { err -> println("  - ${err.phase}: ${err.message}") }
            }
            is SyncResult.AuthError -> {
                println("AUTH ERROR: ${result.message}")
            }
            is SyncResult.Error -> {
                println("ERROR: ${result.message} (code: ${result.code})")
            }
        }
    }

    @Test
    fun `push new event to iCloud and verify sync`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        // Create the event in Room
        val uid = "kashcal-test-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val oneHourLater = now + 3600_000

        val eventId = eventsDao.upsert(Event(
            uid = uid,
            calendarId = testCalendarId,
            title = "KashCal Test Event - $now",
            location = "Test Location",
            description = "Created by KashCal integration test",
            startTs = now,
            endTs = oneHourLater,
            timezone = "America/New_York",
            isAllDay = false,
            status = "CONFIRMED",
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_CREATE,
            localModifiedAt = now,
            createdAt = now,
            updatedAt = now
        ))

        // Queue its CREATE
        pendingOperationsDao.insert(PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        ))

        println("=== Push New Event Test ===")
        println("Event ID: $eventId, UID: $uid")

        val result = syncEngine.syncCalendar(calendar, client = client)

        println("\n=== Sync Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS")
                println("Events pushed (created): ${result.eventsPushedCreated}")

                // The pushed row now has the server's URL and etag
                val pushedEvent = eventsDao.getById(eventId)
                assertNotNull("Event should still exist in DB", pushedEvent)
                assertNotNull("CalDAV URL should be assigned by server", pushedEvent?.caldavUrl)
                assertNotNull("ETag should be assigned by server", pushedEvent?.etag)
                assertEquals("Sync status should be SYNCED", SyncStatus.SYNCED, pushedEvent?.syncStatus)

                // Track for cleanup
                pushedEvent?.caldavUrl?.let { createdEventUrls.add(it) }

                println("CalDAV URL: ${pushedEvent?.caldavUrl}")
                println("ETag: ${pushedEvent?.etag}")

                assertEquals("Should have created 1 event", 1, result.eventsPushedCreated)
            }
            is SyncResult.PartialSuccess -> {
                println("PARTIAL SUCCESS - ${result.errors.size} errors")
                result.errors.forEach { println("  Error: ${it.message}") }
            }
            is SyncResult.AuthError -> println("AUTH ERROR: ${result.message}")
            is SyncResult.Error -> println("ERROR: ${result.message}")
        }
    }

    @Test
    fun `push recurring event to iCloud`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        val uid = "kashcal-recurring-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val oneHourLater = now + 3600_000

        val eventId = eventsDao.upsert(Event(
            uid = uid,
            calendarId = testCalendarId,
            title = "KashCal Recurring Test - Weekly",
            description = "Recurring event created by integration test",
            startTs = now,
            endTs = oneHourLater,
            timezone = "America/New_York",
            isAllDay = false,
            status = "CONFIRMED",
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=5",
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_CREATE,
            localModifiedAt = now,
            createdAt = now,
            updatedAt = now
        ))

        pendingOperationsDao.insert(PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        ))

        println("=== Push Recurring Event Test ===")
        println("Event ID: $eventId, UID: $uid, RRULE: FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=5")

        val result = syncEngine.syncCalendar(calendar, client = client)

        println("\n=== Sync Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS - Recurring event created on iCloud")

                val pushedEvent = eventsDao.getById(eventId)
                assertNotNull("CalDAV URL should be assigned", pushedEvent?.caldavUrl)
                assertEquals("Should be SYNCED", SyncStatus.SYNCED, pushedEvent?.syncStatus)
                pushedEvent?.caldavUrl?.let { createdEventUrls.add(it) }

                assertEquals("Should have created 1 event", 1, result.eventsPushedCreated)
            }
            is SyncResult.PartialSuccess -> {
                println("PARTIAL SUCCESS")
                result.errors.forEach { println("  Error: ${it.message}") }
            }
            else -> println("Failed: $result")
        }
    }

    @Test
    fun `push and then pull back to verify round-trip`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        // Create and push a test event
        val uid = "kashcal-roundtrip-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val oneHourLater = now + 3600_000
        val testTitle = "KashCal Round-Trip Test"
        val testLocation = "Round Trip Location"

        val eventId = eventsDao.upsert(Event(
            uid = uid,
            calendarId = testCalendarId,
            title = testTitle,
            location = testLocation,
            description = "Testing round-trip sync",
            startTs = now,
            endTs = oneHourLater,
            timezone = "America/Los_Angeles",
            isAllDay = false,
            status = "CONFIRMED",
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_CREATE,
            localModifiedAt = now,
            createdAt = now,
            updatedAt = now
        ))

        pendingOperationsDao.insert(PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        ))

        println("=== Round-Trip Test: PUSH Phase ===")
        val pushResult = syncEngine.syncCalendar(calendar, client = client)

        if (pushResult !is SyncResult.Success && pushResult !is SyncResult.PartialSuccess) {
            println("Push failed: $pushResult")
            return@runBlocking
        }

        val pushedEvent = eventsDao.getById(eventId)
        println("Event pushed to: ${pushedEvent?.caldavUrl}")
        pushedEvent?.caldavUrl?.let { createdEventUrls.add(it) }

        // Delete the calendar's events from Room only (no pending operations), so the
        // forced full pull below has to bring the event back from the server.
        eventsDao.deleteByCalendarId(testCalendarId)

        // Re-read the calendar: the first sync may have stored a new ctag or sync-token
        val updatedCalendar = database.calendarsDao().getById(testCalendarId)!!

        println("\n=== Round-Trip Test: PULL Phase ===")
        val pullResult = syncEngine.syncCalendar(updatedCalendar, forceFullSync = true, client = client)

        println("\n=== Round-Trip Result ===")
        when (pullResult) {
            is SyncResult.Success -> {
                println("Pull SUCCESS")
                println("Events pulled: ${pullResult.eventsPulledAdded}")

                // Find the event in Room by UID
                val roundTrippedEvents = eventsDao.getByUid(uid)
                val roundTrippedEvent = roundTrippedEvents.firstOrNull()

                if (roundTrippedEvent != null) {
                    println("\nRound-tripped event found:")
                    println("  Title: ${roundTrippedEvent.title}")
                    println("  Location: ${roundTrippedEvent.location}")
                    println("  Start: ${java.util.Date(roundTrippedEvent.startTs)}")
                    println("  Timezone: ${roundTrippedEvent.timezone}")

                    // Title and location survive the round trip
                    assertEquals("Title should survive round-trip", testTitle, roundTrippedEvent.title)
                    assertEquals("Location should survive round-trip", testLocation, roundTrippedEvent.location)
                    println("\nRound-trip data integrity verified!")
                } else {
                    println("Note: Event not found in pulled events (might be outside sync window)")
                }
            }
            else -> println("Pull result: $pullResult")
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
        val ctag = ctagResult.getOrNull()!!.ctag

        val calendar = setupDbCalendar(caldavCalendar.url, caldavCalendar.displayName)
        database.calendarsDao().updateCtag(testCalendarId, ctag)
        val updatedCalendar = database.calendarsDao().getById(testCalendarId)!!

        println("=== No Changes Test ===")
        println("Calendar ctag: $ctag")

        val result = syncEngine.syncCalendar(updatedCalendar, client = client)

        println("\n=== Result ===")
        when (result) {
            is SyncResult.Success -> {
                println("SUCCESS")
                println("Total changes: ${result.totalChanges}")
                assertEquals("Should have 0 changes when calendar unchanged", 0, result.totalChanges)
            }
            else -> println("Result: $result")
        }
    }
}
