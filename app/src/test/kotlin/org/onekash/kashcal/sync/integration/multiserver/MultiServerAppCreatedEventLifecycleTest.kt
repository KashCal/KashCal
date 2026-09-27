package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

/**
 * Round-trips an event KashCal creates itself, live on every configured CalDAV server. Its UID
 * is `<uuid>@kashcal.onekash.org`, so the resource name KashCal PUTs to carries an '@', which a
 * server may store percent-encoded (`%40`). Whatever the server does with it, KashCal must still
 * read the event back, update it and delete it, and a deleted event must not come back on the
 * next full sync.
 *
 *   create in KashCal -> push -> read back -> rename -> push -> read back;
 *   create in KashCal -> push -> delete in KashCal -> push -> the server lists nothing for it
 *   and a pull into a fresh database finds nothing;
 *   create in KashCal -> push -> move to another calendar of the account -> push -> only the
 *   other calendar lists it -> rename -> push -> read back, both with the URL KashCal built and
 *   with the href the server lists stored on the event.
 *
 * Each run uses a new UID, deletes whatever the server still holds for it afterwards (by the
 * href the server lists, in whatever encoding), and skips a server with no credentials or no
 * answer.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerAppCreatedEventLifecycleTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerAppCreatedEventLifecycleTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val HOUR_MS = 3_600_000L
        private const val FUTURE_END_MS = 4_102_444_800_000L
    }

    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private lateinit var pull: PullStrategy
    private lateinit var push: PushStrategy
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private var otherCalendarUrl: String? = null
    private val createdUids = mutableListOf<String>()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accountRepository(db),
            // Side-effect collaborators only: the test pushes and pulls itself.
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
        pull = pullStrategyFor(db)
        push = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            accountRepository = accountRepository(db),
            attendeesDao = db.attendeesDao(),
            pendingCancelsDao = db.pendingCancelsDao(),
        )
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
    }

    @After
    fun tearDown() = runBlocking {
        val c = client
        if (c != null) {
            for (url in listOfNotNull(calendarUrl, otherCalendarUrl)) {
                for (href in serverHrefsFor(createdUids, url)) {
                    try {
                        val resource = resolve(url, href)
                        c.deleteEvent(resource, c.fetchEtag(resource).getOrNull() ?: "")
                    } catch (_: Exception) { /* best effort */ }
                }
            }
        }
        if (::db.isInitialized) db.close()
        unmockkAll()
    }

    @Test
    fun `an event created in KashCal reads back and takes an update on the server`() = runBlocking<Unit> {
        val (calendar, id) = createInKashCal()
        assertTrue("${config.name}: the created event reads back", readBack(id).contains("SUMMARY:Lifecycle probe"))

        coordinator.updateEvent(db.eventsDao().getById(id)!!.copy(title = "Lifecycle probe renamed", updatedAt = 2))
        pushClean(calendar)
        assertTrue("${config.name}: the rename reached the server", readBack(id).contains("SUMMARY:Lifecycle probe renamed"))
    }

    @Test
    fun `an event created in KashCal and deleted in KashCal is gone from the server`() = runBlocking<Unit> {
        val (calendar, id) = createInKashCal()
        val uid = db.eventsDao().getById(id)!!.uid
        assertEquals("${config.name}: the server lists the new event", 1, settled(1) { serverHrefsFor(listOf(uid)).size })

        coordinator.deleteEvent(id)
        pushClean(calendar)

        assertEquals("${config.name}: the server no longer lists it", emptyList<String>(), settled(emptyList()) { serverHrefsFor(listOf(uid)) })
        assertEquals("${config.name}: a fresh sync doesn't bring it back", emptyList<Event>(), freshPull(uid))
    }

    @Test
    fun `an event created in KashCal moves to another calendar and takes an update there`() = runBlocking<Unit> {
        moveAndUpdate(storeServerHref = false)
    }

    @Test
    fun `an event stored under the server's own href moves to another calendar and takes an update there`() = runBlocking<Unit> {
        // A pull that sees a changed etag adopts the href the server lists, which may spell the
        // '@' as %40 (iCloud, Radicale, Zoho, Stalwart).
        moveAndUpdate(storeServerHref = true)
    }

    private suspend fun moveAndUpdate(storeServerHref: Boolean) {
        val (source, id) = createInKashCal()
        val otherUrl = discoverCalendars(writableForEvents = true).firstOrNull { it != calendarUrl }
        assumeTrue("${config.name}: needs a second writable calendar", otherUrl != null)
        otherCalendarUrl = otherUrl
        // Same account, so the move goes to the server as one MOVE.
        val target = db.calendarsDao().getById(
            db.calendarsDao().insert(Calendar(accountId = source.accountId, caldavUrl = otherUrl!!, displayName = "Lifecycle 2", color = 0)),
        )!!
        val uid = db.eventsDao().getById(id)!!.uid
        assertEquals("${config.name}: the server lists the new event", 1, settled(1) { serverHrefsFor(listOf(uid)).size })
        if (storeServerHref) {
            db.eventsDao().updateCaldavUrl(id, resolve(calendarUrl!!, serverHrefsFor(listOf(uid)).single()))
        }

        coordinator.moveEventToCalendar(id, target.id)
        // The move is queued against one of the two calendars; the other has nothing to send.
        pushClean(source, allowNothingToSend = true)
        pushClean(target, allowNothingToSend = true)

        assertEquals("${config.name}: the source no longer lists it", emptyList<String>(), settled(emptyList()) { serverHrefsFor(listOf(uid)) })
        assertEquals("${config.name}: the other calendar lists it", 1, settled(1) { serverHrefsFor(listOf(uid), otherUrl).size })

        coordinator.updateEvent(db.eventsDao().getById(id)!!.copy(title = "Lifecycle probe moved", updatedAt = 3))
        pushClean(target)
        assertTrue("${config.name}: the rename after the move reached the server", readBack(id).contains("SUMMARY:Lifecycle probe moved"))
    }

    /** Creates a one-off event through KashCal with a UID KashCal generates, and pushes it. */
    private suspend fun createInKashCal(): Pair<Calendar, Long> {
        assumeReady()
        val url = discoverCalendar().also { calendarUrl = it }
        assumeTrue("No calendar found on ${config.name}", url != null)
        val calendar = localCalendarFor(url!!)
        val start = System.currentTimeMillis() + 30L * 24 * HOUR_MS
        val created = coordinator.createEvent(
            // A blank UID: KashCal generates its own, as it does for every event a user creates.
            Event(uid = "", calendarId = calendar.id, title = "Lifecycle probe", startTs = start, endTs = start + HOUR_MS,
                timezone = "America/New_York", dtstamp = 1, createdAt = 1, updatedAt = 1),
            calendarId = calendar.id,
        )
        createdUids += created.uid
        assertTrue("KashCal's own UID carries an '@': ${created.uid}", created.uid.contains('@'))
        pushClean(calendar)
        return calendar to created.id
    }

    // ---- helpers ----

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private suspend fun discoverCalendar(): String? = discoverCalendars().firstOrNull()

    private suspend fun discoverCalendars(writableForEvents: Boolean = false): List<String> {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return emptyList()
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return emptyList()
        return c.listCalendars(home).getOrNull().orEmpty()
            .filter { !writableForEvents || (!it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents)) }
            .map { it.url }
            .filter { !it.contains("inbox") && !it.contains("outbox") }.distinct()
    }

    private suspend fun localCalendarFor(url: String, into: KashCalDatabase = db): Calendar {
        into.calendarsDao().getAllOnce().firstOrNull { it.caldavUrl == url }?.let { return it }
        val accountId = into.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "lifecycle@example.test"))
        val id = into.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Lifecycle", color = 0))
        return into.calendarsDao().getById(id)!!
    }

    private suspend fun pushClean(calendar: Calendar, allowNothingToSend: Boolean = false) {
        val result = push.pushForCalendar(calendar, client!!)
        if (allowNothingToSend && result is PushResult.NoPendingOperations) return
        assertTrue("${config.name}: push must succeed, got $result", result is PushResult.Success)
        assertTrue("${config.name}: push errors: ${(result as PushResult.Success).pushErrors}", result.pushErrors.isEmpty())
    }

    /** Returns the server's body for the event, unfolded, fetched at the URL KashCal stored. */
    private suspend fun readBack(id: Long): String {
        val url = db.eventsDao().getById(id)!!.caldavUrl!!
        val fetched = client!!.fetchEvent(url)
        assertTrue("${config.name}: GET of the stored URL failed: $fetched", fetched.isSuccess())
        return fetched.getOrNull()!!.icalData.replace(Regex("""\r?\n[ \t]"""), "")
    }

    /** Returns the hrefs the server lists that contain the part of one of [uids] before its '@'. */
    private suspend fun serverHrefsFor(uids: List<String>, collection: String = calendarUrl!!): List<String> {
        val keys = uids.map { it.substringBefore('@') }.filter { it.isNotBlank() }
        if (keys.isEmpty()) return emptyList()
        val listed = client!!.fetchEtagsInRange(collection, 0L, FUTURE_END_MS).getOrNull().orEmpty().map { it.first }
        return listed.filter { href -> keys.any { href.contains(it) } }
    }

    /**
     * Zoho lists a change a few seconds late: re-read until [expected] shows or a minute passes.
     */
    private suspend fun <T> settled(expected: T, read: suspend () -> T): T {
        val deadline = System.currentTimeMillis() + 60_000
        var got = read()
        while (got != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = read()
        }
        return got
    }

    private fun resolve(calendarUrl: String, href: String): String =
        if (href.startsWith("http")) href else URI(calendarUrl).resolve(href).toString()

    /** Returns every row for [uid] after a full pull into a fresh database. */
    private suspend fun freshPull(uid: String): List<Event> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val calendar = localCalendarFor(calendarUrl!!, fresh)
            pullStrategyFor(fresh).pull(calendar, forceFullSync = true, client = client!!)
            return fresh.eventsDao().getByUid(uid)
        } finally {
            fresh.close()
        }
    }

    private fun pullStrategyFor(database: KashCalDatabase): PullStrategy {
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        return PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()),
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
        )
    }

    private fun accountRepository(database: KashCalDatabase): AccountRepository = AccountRepositoryImpl(
        accountsDao = database.accountsDao(),
        addressBookDao = database.addressBookDao(),
        calendarsDao = database.calendarsDao(),
        eventsDao = database.eventsDao(),
        pendingOperationsDao = database.pendingOperationsDao(),
        credentialManager = mockk(relaxed = true),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = mockk(relaxed = true),
    )
}
