package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
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
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Narrowing "Sync lookback" keeps a series' changed occurrence from before the new window, so
 * the user's next edit to the series still uploads it and the server keeps it for every device.
 * An upload is rebuilt from the Room rows and replaces the whole resource, so a changed
 * occurrence missing from Room would be deleted on the server.
 *
 * Per server: create a weekly series that started 200 days ago with its third occurrence moved
 * two hours later and retitled, bring that resource into Room, narrow the lookback from the
 * default to 90 days, rename the series through the app and push, then read the resource back.
 * The narrowing goes through the EventCoordinator entry the settings screen calls; the settings
 * screen's own part (the cutoff and the sync it requests) is covered by
 * AccountSettingsLookbackNarrowTest.
 *
 * Safety: this touches only the resource it creates. Only that resource is brought into Room,
 * through a multiget of its own href (the calendar is never pulled); before pushing, the test
 * checks that every queued operation belongs to it; cleanup deletes only the URL its own create
 * call returned. No attendees, so no invitations. A server that refuses an event in the past is
 * skipped with the reason printed. Failure messages go through [FixtureRedactor].
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerLookbackNarrowTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerLookbackNarrowTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val DAY = 86_400_000L
        private const val HOUR = 3_600_000L
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var createdUrl: String? = null
    private lateinit var db: KashCalDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: KashCalDataStore

    @Before
    fun setUp() {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        // A real DataStore, so the lookback the occurrence generator reads changes as the setting
        // does.
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "lookback_narrow_live_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val c = client
        val url = createdUrl
        if (c != null && url != null) {
            runCatching { c.deleteEvent(url, c.fetchEtag(url).getOrNull()) }
        }
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    @Test
    fun `a series edit after narrowing the lookback keeps the changed occurrence on the server`() = runBlocking<Unit> {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val c = client!!
        val calendarUrl = discoverWritableCalendar()
        assumeTrue("${config.name}: no writable calendar", calendarUrl != null)

        val uid = "lookback-narrow-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val start = (now - 200 * DAY) / DAY * DAY + 10 * HOUR
        val slot = start + 14 * DAY
        val body = calendar(
            listOf(
                "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${utc(now)}",
                "DTSTART:${utc(start)}", "DTEND:${utc(start + HOUR)}",
                "RRULE:FREQ=WEEKLY;COUNT=40", "SUMMARY:Lookback narrow weekly", "END:VEVENT",
            ),
            listOf(
                "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${utc(now)}", "RECURRENCE-ID:${utc(slot)}",
                "DTSTART:${utc(slot + 2 * HOUR)}", "DTEND:${utc(slot + 3 * HOUR)}",
                "SUMMARY:Lookback narrow moved", "END:VEVENT",
            ),
        )
        val created = c.createEvent(calendarUrl!!, uid, body)
        val createStatus = (created as? CalDavResult.Error)?.let { "${it.code} ${it.message}" } ?: "ok"
        println(FixtureRedactor.redact("LOOKBACK|${config.name}|create=$createStatus"))
        assumeTrue(FixtureRedactor.redact("${config.name}: create refused ($createStatus)"), created.isSuccess())
        val url = created.getOrNull()!!.first
        createdUrl = url

        // Only this run's own resource goes into Room.
        val calendar = localCalendar(calendarUrl)
        val fetched = settled { c.fetchEventsByHref(calendarUrl, listOf(URI(url).rawPath)).getOrNull().orEmpty() }.singleOrNull()
        println(FixtureRedactor.redact("LOOKBACK|${config.name}|multiget=${if (fetched == null) "empty" else "ok"}"))
        assumeTrue("${config.name}: the multiget of its own href returned nothing", fetched != null)
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), dataStore)
        val masterId = storeOwnResource(generator, calendar, url, uid, fetched!!.icalData, fetched.etag)
        assertEquals("the changed occurrence is stored", 1, db.eventsDao().getExceptionsForMaster(masterId).size)

        // The user narrows the setting: the value is stored, then the cleanup runs.
        val coordinator = coordinator(generator)
        assertEquals(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS, dataStore.syncPastDays.first())
        dataStore.setSyncPastDays(90)
        coordinator.cleanupEventsOutsideLookback(now - 90 * DAY)
        assertEquals("narrowing keeps the changed occurrence", 1, db.eventsDao().getExceptionsForMaster(masterId).size)

        // The user renames the whole series and it syncs.
        val master = db.eventsDao().getById(masterId)!!
        coordinator.updateEvent(master.copy(title = "Lookback narrow renamed", updatedAt = master.updatedAt + 1))
        val foreign = db.pendingOperationsDao().getAllOnce().filter { db.eventsDao().getById(it.eventId)?.uid != uid }
        assertTrue(FixtureRedactor.redact("${config.name}: refusing to push work for other events: $foreign"), foreign.isEmpty())
        val push = push().pushForCalendar(db.calendarsDao().getById(calendar.id)!!, c)
        assertTrue(FixtureRedactor.redact("${config.name}: push $push"), push is PushResult.Success && push.pushErrors.isEmpty())
        val urlAfterPush = db.eventsDao().getById(masterId)!!.caldavUrl
        assertTrue(FixtureRedactor.redact("${config.name}: the push moved the resource from $url to $urlAfterPush"), urlAfterPush == url)

        val after = c.fetchEvent(url).getOrNull()?.icalData
        assertTrue("${config.name}: GET after the push failed", after != null)
        val blocks = vevents(after!!)
        val series = blocks.filter { !it.contains("RECURRENCE-ID") }
        val changed = blocks.filter { it.contains("RECURRENCE-ID") }
        val problems = buildList {
            if (series.size != 1) add("${series.size} series VEVENTs")
            if (series.none { it.lines().any { l -> l.startsWith("RRULE:") } }) add("series has no RRULE")
            if (series.none { it.lines().contains("SUMMARY:Lookback narrow renamed") }) add("series not renamed")
            if (changed.size != 1) add("${changed.size} changed-occurrence VEVENTs")
            if (changed.none { it.lines().contains("SUMMARY:Lookback narrow moved") }) add("changed occurrence lost its title")
        }
        println(FixtureRedactor.redact("LOOKBACK|${config.name}|${if (problems.isEmpty()) "ok" else "BROKEN $problems"}"))
        assertTrue(FixtureRedactor.redact("${config.name}: $problems"), problems.isEmpty())
    }

    // ---- the resource ----

    private fun utc(ms: Long) = UTC.format(Instant.ofEpochMilli(ms))

    private fun calendar(vararg components: List<String>): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Lookback narrow//EN") +
            components.flatMap { it } + listOf("END:VCALENDAR", "")).joinToString("\r\n")

    /** The VEVENT blocks of [ics], unfolded, with LF line ends. */
    private fun vevents(ics: String): List<String> {
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "").replace("\r\n", "\n")
        return Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).map { it.value }.toList()
    }

    /**
     * Stores this run's own resource the way pull does: every VEVENT row carries the whole
     * body, and the changed occurrence is normalized against the series DTSTART and linked to
     * its occurrence. Returns the series row id.
     */
    private suspend fun storeOwnResource(
        generator: OccurrenceGenerator, calendar: Calendar, url: String, uid: String, body: String, etag: String?,
    ): Long {
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!
        assertTrue("${config.name}: the stored resource must be this run's own event", parsed.all { it.uid == uid })
        val seriesParsed = parsed.first { it.recurrenceId == null }
        val seriesId = db.eventsDao().insert(ICalEventMapper.toEntity(seriesParsed, body, calendar.id, url, etag).event)
        generator.regenerateOccurrences(db.eventsDao().getById(seriesId)!!)
        parsed.filter { it.recurrenceId != null }.forEach { occurrence ->
            val row = ICalEventMapper.toEntity(occurrence, body, calendar.id, url, etag, masterDtStart = seriesParsed.dtStart)
                .event.copy(originalEventId = seriesId)
            val rowId = db.eventsDao().insert(row)
            generator.linkException(seriesId, row.originalInstanceTime!!, db.eventsDao().getById(rowId)!!)
        }
        return seriesId
    }

    // ---- the app ----

    private suspend fun discoverWritableCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull().orEmpty()
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
            .map { it.url }
            .firstOrNull { !it.contains("inbox") && !it.contains("outbox") }
    }

    /** Zoho lists a new resource a few seconds late: re-read until it shows or a minute passes. */
    private suspend fun <T> settled(read: suspend () -> List<T>): List<T> {
        val deadline = System.currentTimeMillis() + 60_000
        var got = read()
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = read()
        }
        return got
    }

    private suspend fun localCalendar(url: String): Calendar {
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "lookback-narrow@example.test"))
        val id = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Lookback narrow", color = 0))
        return db.calendarsDao().getById(id)!!
    }

    private fun coordinator(generator: OccurrenceGenerator) = EventCoordinator(
        eventWriter = EventWriter(db, generator),
        eventReader = EventReader(db),
        occurrenceGenerator = generator,
        localCalendarInitializer = LocalCalendarInitializer(db),
        icsSubscriptionRepository = mockk(),
        contactBirthdayRepository = mockk(),
        contactAnniversaryRepository = mockk(),
        accountRepository = accountRepository(),
        // Side-effect collaborators only: the test pushes itself.
        syncScheduler = mockk(relaxed = true),
        reminderScheduler = mockk(relaxed = true),
        widgetUpdateManager = mockk(relaxed = true),
        inviteNotifier = mockk(relaxed = true),
        icsRefreshScheduleReconciler = mockk(relaxed = true),
        dataStore = dataStore,
    )

    private fun push() = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        accountRepository = accountRepository(),
        attendeesDao = db.attendeesDao(),
        pendingCancelsDao = db.pendingCancelsDao(),
    )

    private fun accountRepository(): AccountRepository = AccountRepositoryImpl(
        accountsDao = db.accountsDao(),
        addressBookDao = db.addressBookDao(),
        calendarsDao = db.calendarsDao(),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        // Never read here: the test hands the client its credentials itself.
        credentialManager = mockk(),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = FakeContactsProviderRepository(),
    )
}
