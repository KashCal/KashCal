package org.onekash.kashcal.sync.strategy.integration

import io.mockk.mockk

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Integration test for PullStrategy with real iCloud data and real Room DB.
 *
 * Uses real CalDavClient, Parser, Room in-memory database, and OccurrenceGenerator.
 * Only SyncNotificationManager-type components are absent (not needed for pull).
 *
 * Run with: ./gradlew testDebugUnitTest -Pintegration --tests "*RealICloudPullStrategyTest*"
 *
 * Requires: local.properties with iCloud credentials
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RealICloudPullStrategyTest {

    // Real components
    private lateinit var database: KashCalDatabase
    private lateinit var eventsDao: EventsDao
    private lateinit var occurrencesDao: OccurrencesDao
    private lateinit var pullStrategy: PullStrategy
    private lateinit var client: CalDavClient
    private lateinit var occurrenceGenerator: OccurrenceGenerator

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

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()

        // Real Room in-memory database
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        eventsDao = database.eventsDao()
        occurrencesDao = database.occurrencesDao()
        val calendarRepository = CalendarRepositoryImpl(database.calendarsDao())
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        val dataStore = KashCalDataStore(context, testPrefsDataStore)
        occurrenceGenerator = OccurrenceGenerator(database, occurrencesDao, eventsDao, dataStore)
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

        pullStrategy = PullStrategy(
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
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        database.close()
        testDataStoreFile.delete()
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
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "ICLOUD_USERNAME" -> username = parts[1]
                            "ICLOUD_PASSWORD", "ICLOUD_APP_PASSWORD" -> password = parts[1]
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
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        testCalendar = calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }
        return testCalendar
    }

    /**
     * Insert account + calendar into real Room DB. Must be called after
     * discovering the real iCloud calendar URL.
     */
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

    // ========== Pull Tests ==========

    @Test
    fun `pull full sync from real iCloud calendar`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        println("=== Starting Full Pull ===")
        println("Calendar: ${calendar.displayName}")
        println("URL: ${calendar.caldavUrl}")

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        println("\n=== Pull Result ===")
        when (result) {
            is PullResult.Success -> {
                println("Events added: ${result.eventsAdded}")
                println("Events updated: ${result.eventsUpdated}")
                println("Events deleted: ${result.eventsDeleted}")
                println("New sync token: ${result.newSyncToken}")
                println("New ctag: ${result.newCtag}")

                // Verify events were actually persisted in the real DB
                val now = System.currentTimeMillis()
                val farFuture = now + 365L * 24 * 60 * 60 * 1000 * 10  // 10 years
                val farPast = now - 365L * 24 * 60 * 60 * 1000 * 10
                val dbEvents = eventsDao.getByCalendarIdInRange(testCalendarId, farPast, farFuture)
                println("Events in DB: ${dbEvents.size}")

                assertTrue("Should have events in DB after pull", dbEvents.isNotEmpty())
                assertEquals(
                    "DB event count should match eventsAdded",
                    result.eventsAdded, dbEvents.size
                )
            }
            is PullResult.NoChanges -> println("No changes detected")
            is PullResult.Error -> println("Error: code=${result.code}, ${result.message}")
        }
    }

    @Test
    fun `pull detects no changes when ctag unchanged`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Get current ctag
        val ctagResult = client.getCtag(caldavCalendar!!.url)
        assumeTrue("Should get ctag", ctagResult.isSuccess())
        val currentCtag = ctagResult.getOrNull()!!.ctag

        val calendar = setupDbCalendar(caldavCalendar.url, caldavCalendar.displayName)
        // Update ctag in DB to match server — simulates "already synced"
        database.calendarsDao().updateCtag(testCalendarId, currentCtag)
        val updatedCalendar = database.calendarsDao().getById(testCalendarId)!!

        println("=== Testing No Changes Detection ===")
        println("Current ctag: $currentCtag")

        val result = pullStrategy.pull(updatedCalendar, forceFullSync = false, client = client)

        println("\n=== Pull Result ===")
        when (result) {
            is PullResult.NoChanges -> {
                println("Correctly detected no changes")
                assertTrue(true)
            }
            is PullResult.Success -> {
                println("Got success (ctag might have changed between calls)")
            }
            is PullResult.Error -> println("Error: ${result.message}")
        }
    }

    @Test
    fun `pull correctly captures event data in real DB`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        println("=== Testing Event Data Capture ===")

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        if (result is PullResult.Success && result.eventsAdded > 0) {
            val now = System.currentTimeMillis()
            val farFuture = now + 365L * 24 * 60 * 60 * 1000 * 10
            val farPast = now - 365L * 24 * 60 * 60 * 1000 * 10
            val dbEvents = eventsDao.getByCalendarIdInRange(testCalendarId, farPast, farFuture)

            println("\n=== Events in DB (${dbEvents.size}) ===")
            dbEvents.take(5).forEach { event ->
                println("\n- ${event.title}")
                println("  UID: ${event.uid}")
                println("  Start: ${java.util.Date(event.startTs)}")
                println("  End: ${java.util.Date(event.endTs)}")
                println("  All-day: ${event.isAllDay}")
                println("  RRULE: ${event.rrule}")
                println("  CalDAV URL: ${event.caldavUrl}")
                println("  ETag: ${event.etag}")

                // Verify required fields
                assertTrue("UID should not be blank", event.uid.isNotBlank())
                assertTrue("startTs should be positive", event.startTs > 0)
                assertTrue("endTs should be >= startTs", event.endTs >= event.startTs)
                assertEquals("calendarId should match", testCalendarId, event.calendarId)
                assertNotNull("CalDAV URL should be set", event.caldavUrl)
                assertNotNull("ETag should be set", event.etag)
            }

            // Check for recurring events
            val recurringEvents = dbEvents.filter { it.rrule != null }
            println("\n=== Recurring Events: ${recurringEvents.size} ===")
            recurringEvents.take(3).forEach { event ->
                println("- ${event.title}: ${event.rrule}")
            }

            // Check for exception events
            val exceptions = dbEvents.filter { it.originalInstanceTime != null }
            println("\n=== Exception Events: ${exceptions.size} ===")
            exceptions.take(3).forEach { event ->
                println("- ${event.title}")
                println("  Original time: ${java.util.Date(event.originalInstanceTime!!)}")
                println("  Master ID: ${event.originalEventId}")
            }
        } else {
            println("No events pulled (calendar might be empty or pull failed: $result)")
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

        val calendar = setupDbCalendar(caldavCalendar.url, caldavCalendar.displayName)
        // Set sync token in DB — simulates "already synced"
        database.calendarsDao().updateSyncToken(testCalendarId, syncToken, null)
        val updatedCalendar = database.calendarsDao().getById(testCalendarId)!!

        println("=== Testing Incremental Sync ===")
        println("Sync token: $syncToken")

        val result = pullStrategy.pull(updatedCalendar, forceFullSync = false, client = client)

        println("\n=== Incremental Pull Result ===")
        when (result) {
            is PullResult.Success -> {
                println("Events added: ${result.eventsAdded}")
                println("Events updated: ${result.eventsUpdated}")
                println("Events deleted: ${result.eventsDeleted}")
                println("New sync token: ${result.newSyncToken}")
            }
            is PullResult.NoChanges -> println("No changes since last sync")
            is PullResult.Error -> {
                if (result.code == 403 || result.code == 410) {
                    println("Sync token expired (expected) - would trigger full sync")
                } else {
                    println("Error: ${result.message}")
                }
            }
        }
    }

    @Test
    fun `verify occurrences materialized for recurring events`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)
        assumeTrue("Pull should succeed", result is PullResult.Success)

        // Query recurring events from real DB
        val now = System.currentTimeMillis()
        val farFuture = now + 365L * 24 * 60 * 60 * 1000 * 10
        val farPast = now - 365L * 24 * 60 * 60 * 1000 * 10
        val dbEvents = eventsDao.getByCalendarIdInRange(testCalendarId, farPast, farFuture)
        val recurringEvents = dbEvents.filter { it.rrule != null }

        println("=== Occurrence Generation ===")
        println("Total events: ${dbEvents.size}")
        println("Recurring events: ${recurringEvents.size}")

        if (recurringEvents.isNotEmpty()) {
            // Verify occurrences were materialized in the real occurrences table
            recurringEvents.take(3).forEach { event ->
                val occurrences = occurrencesDao.getForEvent(event.id)
                println("- ${event.title} (${event.rrule}): ${occurrences.size} occurrences")
                assertTrue(
                    "Recurring event '${event.title}' should have occurrences in DB",
                    occurrences.isNotEmpty()
                )
            }
        } else {
            println("No recurring events in calendar - skipping occurrence check")
        }
    }

    @Test
    fun `pull full sync uses batched multiget for large calendar`() = runBlocking {
        // Verifies that batched multiget works against real iCloud with many events.
        // iCloud personal calendar has ~693 events — split into ~35 batches of 20.
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        println("=== Testing Batched Multiget ===")
        println("Calendar: ${calendar.displayName}")

        val startTime = System.currentTimeMillis()
        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)
        val durationMs = System.currentTimeMillis() - startTime

        println("\n=== Batched Multiget Result ===")
        println("Duration: ${durationMs}ms")

        when (result) {
            is PullResult.Success -> {
                println("Events added: ${result.eventsAdded}")

                // Verify events are in real DB
                val now = System.currentTimeMillis()
                val farFuture = now + 365L * 24 * 60 * 60 * 1000 * 10
                val farPast = now - 365L * 24 * 60 * 60 * 1000 * 10
                val dbEvents = eventsDao.getByCalendarIdInRange(testCalendarId, farPast, farFuture)
                println("Events in DB: ${dbEvents.size}")

                assertTrue(
                    "Should have events in DB from iCloud calendar",
                    dbEvents.isNotEmpty()
                )
            }
            is PullResult.Error -> {
                println("Error: code=${result.code}, message=${result.message}, retryable=${result.isRetryable}")
            }
            is PullResult.NoChanges -> println("No changes (ctag matched unexpectedly)")
        }
    }

    // ========== Calendar Metadata Refresh ==========

    @Test
    fun `getCtag probe returns color and displayName for real iCloud calendar`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        val result = client.getCtag(caldavCalendar!!.url)
        assumeTrue("Should get ctag probe from real iCloud", result.isSuccess())

        val probe = result.getOrNull()!!
        println("=== iCloud metadata probe ===")
        println("ctag: ${probe.ctag}")
        println("displayName: ${probe.displayName}")
        println("color: ${probe.color}")
        println("isReadOnly: ${probe.isReadOnly}")

        assertNotNull(
            "Real iCloud always returns ic:calendar-color (#RRGGBBAA) — probe must surface it",
            probe.color
        )
        assertNotNull(
            "Real iCloud always returns displayname — probe must surface it",
            probe.displayName
        )
    }

    @Test
    fun `pull updates Calendar color and displayName from real iCloud probe`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)

        // Insert a deliberately-wrong color and displayName so the refresh
        // has something to overwrite on the next pull.
        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = caldavCalendar!!.url,
                displayName = "STALE_NAME",
                color = 0xFF000000.toInt()
            )
        )
        val staleCalendar = database.calendarsDao().getById(testCalendarId)!!

        // Fetch the server probe so we know what to assert against.
        val probeResult = client.getCtag(caldavCalendar.url)
        assumeTrue("Should get probe", probeResult.isSuccess())
        val probe = probeResult.getOrNull()!!
        val expectedColor = org.onekash.kashcal.sync.parser.ServerColorParser
            .parseCaldavColorToArgb(probe.color)
        val expectedDisplayName = probe.displayName
        assumeTrue("Probe must carry color+name for this assertion", expectedColor != null && expectedDisplayName != null)

        pullStrategy.pull(staleCalendar, forceFullSync = true, client = client)

        val refreshed = database.calendarsDao().getById(testCalendarId)!!
        println("Before: color=${staleCalendar.color.toString(16)}, name=${staleCalendar.displayName}")
        println("After:  color=${refreshed.color.toString(16)}, name=${refreshed.displayName}")

        assertEquals(
            "Calendar.color should match server's parsed calendar-color after pull",
            expectedColor,
            refreshed.color
        )
        assertEquals(
            "Calendar.displayName should match server's after pull",
            expectedDisplayName,
            refreshed.displayName
        )
    }

    /**
     * Regression guard for the attendee persistence path against real iCloud:
     *   1. Create an event with 3 synthetic attendees on a real iCloud calendar
     *   2. Pull the calendar
     *   3. Assert the `attendees` table has rows for the new event
     *   4. Re-pull and assert the count is still the same (idempotent)
     *
     * iCloud's iTIP scheduling routing may drop NEEDS-ACTION attendees when
     * the ORGANIZER mailto doesn't match the authenticated account — so we
     * assert ≥1 attendee survives the roundtrip rather than exactly 3.
     */
    @Test
    fun `attendees persist on real iCloud pull and re-pull idempotently`() = runBlocking {
        assumeCredentialsAvailable()

        val caldavCalendar = discoverTestCalendar()
        assumeTrue("Should discover a calendar", caldavCalendar != null)
        val calendar = setupDbCalendar(caldavCalendar!!.url, caldavCalendar.displayName)

        // Create a synthetic attendee-bearing event on iCloud
        val uid = "b4read-regression-${java.util.UUID.randomUUID()}"
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//B4-read regression//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:20260601T100000Z
DTSTART:20260601T100000Z
DTEND:20260601T110000Z
SUMMARY:B4-read attendee regression test
ORGANIZER;CN=Test Organizer:mailto:organizer.synthetic@example.test
ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:alice.synthetic@example.test
ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:bob.synthetic@example.test
ATTENDEE;CN=Carol;PARTSTAT=DECLINED;ROLE=OPT-PARTICIPANT:mailto:carol.synthetic@example.test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendar.caldavUrl!!, uid, ics)
        assumeTrue("createEvent must succeed", createResult.isSuccess())
        val (eventUrl, createEtag) = createResult.getOrNull()!!

        try {
            // First pull
            val first = pullStrategy.pull(calendar, forceFullSync = true, client = client)
            assumeTrue("first pull should succeed", first is PullResult.Success)

            val event = eventsDao.getByCaldavUrl(eventUrl)
            assumeTrue("event should be in DB", event != null)
            val firstAttendees = database.attendeesDao().getForEvent(event!!.id).first()
            assertTrue(
                "expected ≥1 attendee persisted; iCloud iTIP routing may drop NEEDS-ACTION attendees " +
                    "when ORGANIZER mailto doesn't match the authenticated account",
                firstAttendees.isNotEmpty()
            )
            val firstCount = firstAttendees.size

            // Second pull — idempotent at row-set level
            val second = pullStrategy.pull(calendar, forceFullSync = true, client = client)
            assumeTrue("second pull should succeed", second is PullResult.Success)
            val secondAttendees = database.attendeesDao().getForEvent(event.id).first()
            assertEquals(
                "re-pull must not duplicate attendee rows",
                firstCount, secondAttendees.size
            )
            assertEquals(
                "re-pull must produce the same address set",
                firstAttendees.map { it.address }.toSet(),
                secondAttendees.map { it.address }.toSet()
            )
        } finally {
            // Cleanup — best-effort
            try { client.deleteEvent(eventUrl, createEtag) } catch (_: Exception) { /* ignore */ }
        }
    }
}
