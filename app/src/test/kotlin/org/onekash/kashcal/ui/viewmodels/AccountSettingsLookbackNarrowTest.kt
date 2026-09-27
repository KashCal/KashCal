package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.contacts.ContactAnniversaryRepository
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsFetcher
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.strategy.FakeDavCalendar
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.ui.permission.FakePermissionChecker
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Narrowing "Sync lookback" in settings removes only synced one-off server events (CalDAV and ICS
 * subscription) that ended before the cutoff with no pending change; an ICS feed's placeholder
 * master for changed occurrences counts as one, and its changed occurrences go with it (not
 * covered here). Local calendars keep everything, series keep their occurrences and changed
 * occurrences, and a later series edit still uploads the changed occurrence, because an upload
 * is rebuilt from the Room rows and replaces the whole resource on the server.
 *
 * Drives [AccountSettingsViewModel.onSyncLookbackChange], the call the settings screen makes,
 * over a real DataStore (so the lookback the occurrence generator and the pull read changes the
 * way the setting does), a real in-memory Room, the real EventCoordinator, EventWriter,
 * OccurrenceGenerator, PullStrategy, PushStrategy and IcsSubscriptionRepository, and a CalDAV
 * server that keeps its resources ([FakeDavCalendar]). Scrolling back runs the past extension and
 * the occurrence repair that HomeViewModel runs on month navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AccountSettingsLookbackNarrowTest {

    private val savedZone: TimeZone = TimeZone.getDefault()
    private lateinit var context: Context
    private lateinit var db: KashCalDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: KashCalDataStore
    private lateinit var generator: OccurrenceGenerator
    private lateinit var accountRepository: AccountRepository
    private lateinit var subscriptions: IcsSubscriptionRepository
    private lateinit var coordinator: EventCoordinator
    private lateinit var server: MockWebServer
    private val dav = FakeDavCalendar()
    private var feed = ""

    // Unit-returning side-effect collaborators, so relaxed is allowed.
    private val widgetUpdateManager = mockk<WidgetUpdateManager>(relaxed = true)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)

    /** Syncs requested and not yet awaited. */
    private val syncRequests = LinkedBlockingQueue<Boolean>()

    /** The forceFullSync flag of each sync the settings screen requested, in order. */
    private val requested = mutableListOf<Boolean>()

    private lateinit var vm: AccountSettingsViewModel

    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    /** 10:00 UTC on the day [daysAgo] days before today. */
    private fun at(daysAgo: Int) = (now - daysAgo * day) / day * day + 10 * hour

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "lookback_narrow_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), dataStore)
        accountRepository = AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            // Strict: these tests never read credentials or contacts.
            credentialManager = mockk(),
            reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(),
        )
        val fetcher = object : IcsFetcher {
            override suspend fun fetch(subscription: IcsSubscription) = IcsFetcher.FetchResult.Success(feed, null, null)
        }
        subscriptions = IcsSubscriptionRepository(
            database = db,
            icsSubscriptionsDao = db.icsSubscriptionsDao(),
            accountRepository = accountRepository,
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            occurrenceGenerator = generator,
            icsFetcher = fetcher,
            reminderScheduler = mockk(relaxed = true),
            eventReader = EventReader(db),
            context = context,
        )
        // Strict: the settings screen's start-up reads only the contact calendars' color and id,
        // and both are absent here.
        val birthdays = mockk<ContactBirthdayRepository> {
            coEvery { getCalendarColor() } returns null
            coEvery { getCalendarId() } returns null
        }
        val anniversaries = mockk<ContactAnniversaryRepository> {
            coEvery { getCalendarColor() } returns null
            coEvery { getCalendarId() } returns null
        }
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = subscriptions,
            contactBirthdayRepository = birthdays,
            contactAnniversaryRepository = anniversaries,
            accountRepository = accountRepository,
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = widgetUpdateManager,
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = dataStore,
        )
        every { syncScheduler.requestImmediateSync(any(), any(), any()) } answers {
            syncRequests.add(firstArg())
            UUID.randomUUID()
        }
        server = MockWebServer().apply { dispatcher = dav; start() }
        vm = viewModel()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        dataStoreScope.cancel()
        dataStoreFile.delete()
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
    }

    // ---- local calendar ----

    @Test
    fun `narrowing leaves a local calendar exactly as it was, after scrolling back and after widening`() = runBlocking {
        val local = coordinator.ensureLocalCalendarExists()
        val oneOff = coordinator.createEvent(event(local, "Local one-off", at(200), null), calendarId = local).id
        val ended = coordinator.createEvent(event(local, "Local ended weekly", at(300), "FREQ=WEEKLY;COUNT=10"), calendarId = local).id
        val ongoing = coordinator.createEvent(event(local, "Local ongoing weekly", at(200), "FREQ=WEEKLY;COUNT=40"), calendarId = local).id
        val slot = at(200) + 14 * day
        coordinator.editSingleOccurrence(ongoing, slot) {
            it.copy(title = "Local ongoing changed", startTs = it.startTs + 2 * hour, endTs = it.endTs + 2 * hour)
        }
        val ids = listOf(oneOff, ended, ongoing)
        val before = visible(ids)
        assertEquals("the one-off and every occurrence of both series are shown", 1 + 10 + 40, before.size)
        assertChangedSlot(ongoing, slot, "Local ongoing changed")

        narrowTo(90)

        assertEquals("narrowing changes nothing a local calendar shows", before, visible(ids))
        assertEquals("every local row stays", 4, db.eventsDao().getByCalendarId(local).first().size)
        assertChangedSlot(ongoing, slot, "Local ongoing changed")

        scrollBackTo(330)
        assertEquals("scrolling back shows the same", before, visible(ids))
        assertChangedSlot(ongoing, slot, "Local ongoing changed")

        widen()
        scrollBackTo(330)
        assertEquals("widening again shows the same", before, visible(ids))
        assertChangedSlot(ongoing, slot, "Local ongoing changed")
        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets("lookback_shrink_cleanup") }
    }

    // ---- synced calendar ----

    @Test
    fun `narrowing keeps a synced series' past changed occurrence and a later series edit still uploads it`() = runBlocking {
        val seriesStart = at(200)
        val slot = seriesStart + 14 * day
        dav.put("/cal/series.ics", vcalendar(
            vevent("series@example.test", "Synced weekly", seriesStart, rrule = "FREQ=WEEKLY;COUNT=40"),
            vevent("series@example.test", "Synced weekly moved", slot + 2 * hour, recurrenceId = slot),
        ))
        val calendar = pullSyncedCalendar()
        val series = master("series@example.test")
        assertEquals("the changed occurrence is pulled", 1, db.eventsDao().getExceptionsForMaster(series.id).size)

        narrowTo(90)

        assertEquals("the changed occurrence row stays", 1, db.eventsDao().getExceptionsForMaster(series.id).size)
        assertChangedSlot(series.id, slot, "Synced weekly moved")

        // The user renames the whole series and it syncs.
        coordinator.updateEvent(series.copy(title = "Synced weekly renamed", updatedAt = series.updatedAt + 1))
        val push = pushStrategy().pushForCalendar(db.calendarsDao().getById(calendar.id)!!, client())
        assertTrue("push: $push", push is PushResult.Success)

        val stored = vevents(dav.body("/cal/series.ics"))
        assertEquals("the server keeps both parts of the resource: $stored", 2, stored.size)
        val seriesPart = stored.single { !it.contains("RECURRENCE-ID") }
        val changedPart = stored.single { it.contains("RECURRENCE-ID") }
        assertTrue(seriesPart.lines().contains("SUMMARY:Synced weekly renamed"))
        assertTrue(seriesPart.lines().any { it.startsWith("RRULE:") })
        assertTrue(changedPart.lines().contains("RECURRENCE-ID:${utc(slot)}"))
        assertTrue(changedPart.lines().contains("DTSTART:${utc(slot + 2 * hour)}"))
        assertTrue(changedPart.lines().contains("SUMMARY:Synced weekly moved"))
    }

    @Test
    fun `a synced series that ended before the cutoff stays and shows on scroll-back`() = runBlocking {
        dav.put("/cal/ended.ics", vcalendar(vevent("ended@example.test", "Synced ended weekly", at(260), rrule = "FREQ=WEEKLY;COUNT=5")))
        pullSyncedCalendar()
        val ended = master("ended@example.test")
        val before = visible(listOf(ended.id))
        assertEquals(5, before.size)

        narrowTo(90)
        scrollBackTo(330)

        assertNotNull("the series row stays", db.eventsDao().getById(ended.id))
        assertEquals("its occurrences show on scroll-back", before, visible(listOf(ended.id)))
    }

    @Test
    fun `narrowing still removes old synced one-offs with no pending change and keeps the rest`() = runBlocking {
        dav.put("/cal/old.ics", vcalendar(vevent("old@example.test", "Old one-off", at(200))))
        dav.put("/cal/edited.ics", vcalendar(vevent("edited@example.test", "Edited one-off", at(210))))
        dav.put("/cal/recent.ics", vcalendar(vevent("recent@example.test", "Recent one-off", at(10))))
        pullSyncedCalendar()
        val edited = master("edited@example.test")
        coordinator.updateEvent(edited.copy(title = "Edited one-off, not yet synced", updatedAt = edited.updatedAt + 1))
        assertEquals(SyncStatus.PENDING_UPDATE, db.eventsDao().getById(edited.id)!!.syncStatus)

        narrowTo(90)

        assertTrue("the old synced one-off goes", db.eventsDao().getByUid("old@example.test").isEmpty())
        assertNotNull("an unsynced edit stays", db.eventsDao().getById(edited.id))
        assertTrue("a one-off inside the window stays", db.eventsDao().getByUid("recent@example.test").isNotEmpty())
        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets("lookback_shrink_cleanup") }

        widen()
        assertEquals("narrowing asks for a normal sync, widening for a full one", listOf(false, true), requested)
    }

    // ---- ICS subscription ----

    @Test
    fun `narrowing keeps a subscribed series' past changed occurrence and removes an old subscribed one-off`() = runBlocking {
        val seriesStart = at(200)
        val slot = seriesStart + 14 * day
        feed = vcalendar(
            vevent("sub-series@example.test", "Feed weekly", seriesStart, rrule = "FREQ=WEEKLY;COUNT=40"),
            vevent("sub-series@example.test", "Feed weekly moved", slot + 2 * hour, recurrenceId = slot),
            vevent("sub-oneoff@example.test", "Feed old one-off", at(200)),
        )
        subscriptions.addSubscription("https://feeds.example.test/team.ics", "Team", 0)
        val series = master("sub-series@example.test")
        assertEquals(1, db.eventsDao().getExceptionsForMaster(series.id).size)
        assertTrue(db.eventsDao().getByUid("sub-oneoff@example.test").isNotEmpty())

        narrowTo(90)

        assertEquals("the changed occurrence row stays", 1, db.eventsDao().getExceptionsForMaster(series.id).size)
        assertChangedSlot(series.id, slot, "Feed weekly moved")
        assertTrue("an old subscribed one-off goes; the feed adds it again on its next refresh with a body",
            db.eventsDao().getByUid("sub-oneoff@example.test").isEmpty())
    }

    // ---- the settings screen ----

    private fun viewModel(): AccountSettingsViewModel {
        val repo = FakeCalendarProviderRepository()
        val manager = CalendarProviderManager(context, dataStore, mockk(relaxed = true), widgetUpdateManager)
        val syncLogReader = mockk<SyncLogReader> {
            every { getRecentLogs(any()) } returns MutableStateFlow(emptyList())
        }
        return AccountSettingsViewModel(
            accountRepository = accountRepository,
            userPreferences = UserPreferencesRepository(dataStore),
            syncScheduler = syncScheduler,
            discoveryService = mockk(),
            calDavDiscoveryService = mockk(),
            eventCoordinator = coordinator,
            syncLogReader = syncLogReader,
            contactEventManager = mockk(),
            calendarProviderManager = manager,
            deviceEventReader = repo.deviceEventReader(),
            deviceEventWriter = repo.deviceEventWriter(dataStore, manager),
            dataStore = dataStore,
            widgetUpdateManager = widgetUpdateManager,
            deviceCalendarReminderScheduler = mockk(relaxed = true),
            backupExporter = mockk(),
            backupImporter = mockk(),
            permissionChecker = FakePermissionChecker(),
            context = context,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    /** Picks [days] in the Sync lookback setting and waits for the sync it requests. */
    private suspend fun narrowTo(days: Int) {
        assertEquals("the setting starts at the default", KashCalDataStore.DEFAULT_SYNC_PAST_DAYS, dataStore.syncPastDays.first())
        vm.onSyncLookbackChange(days)
        awaitSyncRequest()
        assertEquals(days, dataStore.syncPastDays.first())
    }

    /** Picks "All events" and waits for the sync it requests. */
    private suspend fun widen() {
        vm.onSyncLookbackChange(Int.MAX_VALUE)
        awaitSyncRequest()
        assertEquals(Int.MAX_VALUE, dataStore.syncPastDays.first())
    }

    private fun awaitSyncRequest() {
        val flag = syncRequests.poll(20, TimeUnit.SECONDS)
        assertNotNull("the settings screen requested a sync", flag)
        requested += flag!!
    }

    /**
     * Month navigation back to [daysAgo] days before today: the past extension and the repair
     * HomeViewModel runs (it also extends forward, which a past cutoff doesn't touch).
     */
    private suspend fun scrollBackTo(daysAgo: Int) {
        coordinator.extendPastOccurrencesIfNeeded(now - daysAgo * day)
        coordinator.repairMissingOccurrences()
    }

    // ---- what the calendar shows ----

    /** The occurrences of [eventIds] shown from 400 days ago to 100 days ahead. */
    private suspend fun visible(eventIds: List<Long>) =
        db.occurrencesDao().getInRangeOnce(now - 400 * day, now + 100 * day)
            .filter { it.eventId in eventIds }
            .sortedWith(compareBy({ it.eventId }, { it.startTs }))
            .map { listOf(it.eventId, it.startTs, it.endTs, it.exceptionEventId) }

    /** The slot originally at [slot] shows [title] two hours later. */
    private suspend fun assertChangedSlot(masterId: Long, slot: Long, title: String) {
        val shown = db.occurrencesDao().getInRangeOnce(slot - day, slot + day).filter { it.eventId == masterId }
        assertEquals("one occurrence on the changed day: $shown", 1, shown.size)
        val exceptionId = shown.single().exceptionEventId
        assertNotNull("the slot is linked to its changed occurrence", exceptionId)
        assertEquals(title, db.eventsDao().getById(exceptionId!!)!!.title)
        assertEquals(slot + 2 * hour, shown.single().startTs)
    }

    private suspend fun master(uid: String): Event {
        val row = db.eventsDao().getByUid(uid).firstOrNull { it.originalEventId == null }
        assertNotNull("$uid is stored", row)
        return row!!
    }

    // ---- the synced calendar ----

    private fun client(): CalDavClient {
        val base = server.url("/").toString()
        return OkHttpCalDavClientFactory().createClient(Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base))
    }

    /**
     * Adds a CalDAV calendar over [dav] and pulls it. The stored token makes the first pull ask
     * for everything changed since it, the path the fake server answers.
     */
    private suspend fun pullSyncedCalendar(): Calendar {
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "me@example.test"))
        val id = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = server.url(dav.collectionPath).toString(), displayName = "Cal", color = 0, syncToken = "t0")
        )
        val calendar = db.calendarsDao().getById(id)!!
        val result = pullStrategy().pull(calendar, client = client())
        assertTrue("pull: $result", result is PullResult.Success)
        return calendar
    }

    private fun pullStrategy() = PullStrategy(
        database = db,
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        attendeesDao = db.attendeesDao(),
        occurrenceGenerator = generator,
        defaultQuirks = DefaultQuirks(server.url("/").toString()),
        dataStore = dataStore,
        inviteNotifier = mockk(relaxed = true),
        accountRepository = accountRepository,
        reminderScheduler = mockk(relaxed = true),
    )

    private fun pushStrategy() = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        accountRepository = accountRepository,
        attendeesDao = db.attendeesDao(),
        pendingCancelsDao = db.pendingCancelsDao(),
    )

    // ---- fixtures ----

    private fun event(calendarId: Long, title: String, start: Long, rrule: String?) = Event(
        uid = "", calendarId = calendarId, title = title, startTs = start, endTs = start + hour,
        timezone = "UTC", rrule = rrule, dtstamp = 1, createdAt = 1, updatedAt = 1,
    )

    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private fun utc(ms: Long) = utcFormat.format(Instant.ofEpochMilli(ms))

    private fun vevent(uid: String, summary: String, start: Long, rrule: String? = null, recurrenceId: Long? = null) = listOfNotNull(
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${utc(now)}",
        recurrenceId?.let { "RECURRENCE-ID:${utc(it)}" },
        "DTSTART:${utc(start)}", "DTEND:${utc(start + hour)}",
        rrule?.let { "RRULE:$it" }, "SUMMARY:$summary", "END:VEVENT",
    )

    private fun vcalendar(vararg vevents: List<String>) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Lookback narrow//EN") + vevents.flatMap { it } + listOf("END:VCALENDAR", ""))
            .joinToString("\r\n")

    /** The VEVENT blocks of [ics], unfolded. */
    private fun vevents(ics: String): List<String> {
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "").replace("\r\n", "\n")
        return Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).map { it.value }.toList()
    }
}
