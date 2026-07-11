package org.onekash.kashcal.sync.engine.integration

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.integration.multiserver.CalDavServerConfig
import org.onekash.kashcal.sync.integration.multiserver.CalDavTestServerLoader
import org.onekash.kashcal.sync.integration.multiserver.ServerCredentials
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Regression test for issue #292: moving an event to another calendar on the
 * same account while editing the title/note in the same save must not lose the
 * edits on the CalDAV server.
 *
 * Drives the real production stack (EventWriter -> PushStrategy -> live server
 * -> real Room DB) through the exact save sequence the UI uses
 * (moveEventToCalendar, then updateEvent with the new field values), then
 * fetches the destination body from the server and asserts it carries the edit.
 *
 * Runs once per server (iCloud, Nextcloud) to cover both same-account move
 * paths:
 *  - Servers that ACCEPT WebDAV MOVE (iCloud): the atomic MOVE relocates the old
 *    body, then the fix PUTs the current body to the new URL.
 *  - Servers that DECLINE MOVE (some Nextcloud/Sabre builds return 403): the
 *    CREATE+DELETE fallback re-serializes the current body.
 * Either way the destination must end up with the edited body.
 *
 * Auto-skips when credentials are missing, the server is unreachable, or the
 * account exposes fewer than two writable calendars.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*CalendarMoveWithEditIntegrationTest*"
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CalendarMoveWithEditIntegrationTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = listOf(
            CalDavServerConfig.ICLOUD,
            CalDavServerConfig.NEXTCLOUD
        )
    }

    private lateinit var database: KashCalDatabase
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var eventWriter: EventWriter
    private lateinit var pushStrategy: PushStrategy

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File

    private val createdUrls = mutableListOf<String>()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val calendarRepository = CalendarRepositoryImpl(database.calendarsDao())
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "movewithedit_prefs_${System.nanoTime()}.preferences_pb")
        val prefs = PreferenceDataStoreFactory.create(scope = dataStoreScope) { testDataStoreFile }
        val dataStore = KashCalDataStore(context, prefs)
        val occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), dataStore)

        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }

        eventWriter = EventWriter(database, occurrenceGenerator)
        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = mockk(relaxed = true),
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun tearDown() {
        val c = client
        if (c != null) {
            runBlocking {
                for (url in createdUrls) {
                    try {
                        c.deleteEvent(url, "")
                    } catch (_: Exception) {
                    }
                }
            }
        }
        database.close()
        dataStoreScope.cancel()
        testDataStoreFile.delete()
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    /** All writable calendar collection URLs on the account (inbox/outbox/birthdays excluded). */
    private suspend fun discoverCalendars(): List<String> {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return emptyList()
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return emptyList()
        val calendars: List<CalDavCalendar> = c.listCalendars(home).getOrNull() ?: return emptyList()
        return calendars
            .map { it.url }
            .filter {
                !it.contains("inbox") && !it.contains("outbox") &&
                    !it.contains("birthday") && !it.contains("trashbin")
            }
            .distinct()
    }

    @Test
    fun `move to another calendar plus edit lands edited body on server`() = runBlocking {
        assumeReady()

        val calendars = discoverCalendars()
        assumeTrue(
            "${config.name} exposes fewer than 2 writable calendars (got ${calendars.size})",
            calendars.size >= 2
        )
        val calAUrl = calendars[0]
        val calBUrl = calendars[1]

        // Seed the local DB with an account + both calendars.
        val accountId = database.accountsDao().insert(
            Account(provider = config.accountProvider(), email = creds!!.username, displayName = "Test")
        )
        val calAId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calAUrl, displayName = "A", color = -1)
        )
        val calBId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calBUrl, displayName = "B", color = -1)
        )

        val uid = "move-edit-${config.name.lowercase()}-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()

        // Create the event in calendar A and sync it to the server.
        val created = eventWriter.createEvent(
            Event(
                uid = uid,
                calendarId = calAId,
                title = "Room 1",
                description = "Room 1 note",
                startTs = now,
                endTs = now + 3_600_000,
                dtstamp = now,
                syncStatus = SyncStatus.PENDING_CREATE
            )
        )
        pushStrategy.pushAll(client!!)
        val afterCreate = database.eventsDao().getById(created.id)
        assumeTrue("${config.name}: create did not sync", afterCreate?.caldavUrl != null)
        afterCreate!!.caldavUrl?.let { createdUrls.add(it) }

        // Same save sequence the UI uses: move first, then apply the field edits.
        eventWriter.moveEventToCalendar(created.id, calBId)
        val moved = database.eventsDao().getById(created.id)!!
        eventWriter.updateEvent(
            moved.copy(
                title = "Room 2",
                description = "Room 2 note",
                updatedAt = System.currentTimeMillis()
            )
        )

        // Push the queued operation(s) and read the destination body back.
        pushStrategy.pushAll(client!!)
        val finalUrl = database.eventsDao().getById(created.id)!!.caldavUrl
        assumeTrue("${config.name}: no server URL after move", finalUrl != null)
        if (finalUrl != null && finalUrl !in createdUrls) createdUrls.add(finalUrl)
        val fetched = client!!.fetchEvent(finalUrl!!)
        assumeTrue("${config.name}: could not fetch moved event", fetched.isSuccess())
        val body = fetched.getOrNull()!!.icalData
        println("MOVE-EDIT ${config.name}: url=$finalUrl hasRoom2=${body.contains("Room 2")} hasRoom1=${body.contains("Room 1")}")

        assert(body.contains("Room 2")) {
            "${config.name}: after move+edit, server body should carry the edited title 'Room 2'. Body:\n$body"
        }
        assert(!body.contains("Room 1")) {
            "${config.name}: after move+edit, server body should not retain the stale title 'Room 1'. Body:\n$body"
        }
    }

    private fun CalDavServerConfig.accountProvider(): AccountProvider =
        when (name) {
            "iCloud" -> AccountProvider.ICLOUD
            else -> AccountProvider.CALDAV
        }
}
