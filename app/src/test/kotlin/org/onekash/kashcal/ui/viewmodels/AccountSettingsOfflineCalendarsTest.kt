package org.onekash.kashcal.ui.viewmodels

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.ics.IcsFetcher
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.feeds.noFeedAccountsServerCalendarFeeds
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.device.DeviceCalendarChangeWatcher
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.testutil.insertOfflineCalendar
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.permission.FakePermissionChecker
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The settings screen's offline calendar actions on [AccountSettingsViewModel], over real Room
 * with the pending-operation trigger, the real coordinator, writer, initializer and reminder
 * scheduler on Robolectric's AlarmManager, and a real DataStore: the list it shows, create,
 * rename, recolor and delete, the translated built-in name in the duplicate check, the default
 * calendar it tints, and a delete whose caller goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AccountSettingsOfflineCalendarsTest {

    private lateinit var context: Context
    private lateinit var db: KashCalDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accountRepository: AccountRepository
    private lateinit var coordinator: EventCoordinator
    private lateinit var vm: AccountSettingsViewModel
    private var builtInId = 0L

    // Unit-returning side-effect collaborators, so relaxed is allowed.
    private val widgetUpdateManager = mockk<WidgetUpdateManager>(relaxed = true)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)

    private val hour = 3_600_000L
    private val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "offline_settings_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), dataStore)
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
        val reader = EventReader(db)
        // Real: the delete runs the row delete inside the scheduler's alarm capture.
        val reminderScheduler = ReminderScheduler(
            context = context,
            scheduledRemindersDao = db.scheduledRemindersDao(),
            eventReader = reader,
            channels = mockk(relaxed = true),
            attendeesDao = db.attendeesDao(),
            accountsDao = db.accountsDao(),
            calendarsDao = db.calendarsDao(),
            occurrencesDao = db.occurrencesDao(),
        )
        val fetcher = object : IcsFetcher {
            override suspend fun fetch(subscription: IcsSubscription) = IcsFetcher.FetchResult.Success("", null, null)
        }
        val subscriptions = IcsSubscriptionRepository(
            database = db,
            icsSubscriptionsDao = db.icsSubscriptionsDao(),
            accountRepository = accountRepository,
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            occurrenceGenerator = generator,
            icsFetcher = fetcher,
            reminderScheduler = reminderScheduler,
            eventReader = reader,
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
            eventReader = reader,
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = subscriptions,
            contactBirthdayRepository = birthdays,
            contactAnniversaryRepository = anniversaries,
            accountRepository = accountRepository,
            syncScheduler = syncScheduler,
            reminderScheduler = reminderScheduler,
            widgetUpdateManager = widgetUpdateManager,
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = dataStore,
        )
        builtInId = coordinator.ensureLocalCalendarExists()
        vm = viewModel()
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
        Dispatchers.resetMain()
    }

    private fun viewModel(): AccountSettingsViewModel {
        val repo = FakeCalendarProviderRepository()
        val manager = CalendarProviderManager(context, dataStore, mockk(relaxed = true), widgetUpdateManager, DeviceCalendarChangeWatcher(context, dataStore))
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
            serverCalendarFeeds = noFeedAccountsServerCalendarFeeds(),
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
            changeNotificationChannels = org.onekash.kashcal.sync.notification.blockedChangeNotificationChannels(),
            reminderNotificationChannels = org.onekash.kashcal.reminder.notification.allowedReminderNotificationChannels(),
            context = context,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    private fun refusalOf(result: Result<*>): LocalCalendarRefusal? =
        (result.exceptionOrNull() as? LocalCalendarException)?.refusal

    /** Waits until the list the screen shows satisfies [predicate] and returns it. */
    private suspend fun awaitList(predicate: (List<LocalCalendarSummary>) -> Boolean): List<LocalCalendarSummary> =
        withTimeout(5_000) {
            while (!predicate(vm.offlineCalendars.value)) delay(10)
            vm.offlineCalendars.value
        }

    private fun names(list: List<LocalCalendarSummary>) = list.map { it.calendar.localizedDisplayName(context.resources) }

    private fun event(calendarId: Long, title: String) = Event(
        uid = "settings-${UUID.randomUUID()}@example.test",
        calendarId = calendarId,
        title = title,
        startTs = start,
        endTs = start + hour,
        dtstamp = 0L,
        reminders = listOf("-PT15M"),
        alarmCount = 1,
    )

    /** Reminder ids with an alarm currently set. */
    private fun armedReminderIds(): Set<Long> {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != ReminderAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        }.toSet()
    }

    // ---- the list ----

    @Test
    fun `a created calendar appears in the list with the built-in and its event count`() = runBlocking {
        val id = vm.createOfflineCalendar("Work", 0xFF1E90FF.toInt()).getOrThrow()
        val list = awaitList { rows -> rows.any { it.calendar.id == id } }
        assertEquals(listOf("Local", "Work"), names(list))
        assertEquals(0, list.single { it.calendar.id == id }.eventCount)

        coordinator.createEvent(event(id, "Dentist"), calendarId = id)
        val updated = awaitList { rows -> rows.single { it.calendar.id == id }.eventCount == 1 }
        assertEquals(1, updated.single { it.calendar.id == id }.eventCount)
    }

    @Test
    fun `the list is in the order the drawer and pickers give the offline group`() = runBlocking {
        db.insertOfflineCalendar("beta")
        db.insertOfflineCalendar("Alpha")
        val list = awaitList { it.size == 3 }
        val groups = CalendarGroup.fromCalendarsAndAccounts(
            coordinator.getAllCalendars().first(),
            coordinator.getAllAccounts().first(),
            localLabel = "Offline",
            icsLabel = "Feeds",
            localizeCalendarName = { it.localizedDisplayName(context.resources) },
        )
        val offline = groups.single { group -> group.calendars.any { it.id == builtInId } }
        assertEquals(offline.calendars.map { it.id }, list.map { it.calendar.id })
        assertEquals(listOf("Alpha", "beta", "Local"), names(list))
    }

    // ---- create, rename, recolor ----

    @Test
    @Config(qualifiers = "de")
    fun `the built-in is matched by its translated name, not its stored English one`() = runBlocking {
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(vm.createOfflineCalendar("Lokal", 0)))
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(vm.createOfflineCalendar("lokal ", 0)))
        assertTrue(vm.createOfflineCalendar("Local", 0).isSuccess)
    }

    @Test
    @Config(qualifiers = "de")
    fun `a rename to the translated built-in name is refused`() = runBlocking {
        val id = vm.createOfflineCalendar("Arbeit", 0).getOrThrow()
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(vm.renameOfflineCalendar(id, "Lokal")))
        assertEquals("Arbeit", db.calendarsDao().getById(id)!!.displayName)
    }

    @Test
    fun `rename and recolor show in the list`() = runBlocking {
        val id = vm.createOfflineCalendar("Work", 0).getOrThrow()
        assertTrue(vm.renameOfflineCalendar(id, "  Office ").isSuccess)
        assertTrue(vm.recolorOfflineCalendar(id, 0xFFFF6347.toInt()).isSuccess)
        val list = awaitList { rows -> rows.any { it.calendar.id == id && it.calendar.displayName == "Office" && it.calendar.color == 0xFFFF6347.toInt() } }
        assertEquals("Office", list.single { it.calendar.id == id }.calendar.displayName)
    }

    // ---- delete ----

    @Test
    fun `delete removes the calendar, cancels its alarm and moves the default to the built-in`() = runBlocking {
        val id = vm.createOfflineCalendar("Work", 0).getOrThrow()
        coordinator.createEvent(event(id, "Dentist"), calendarId = id)
        val reminderIds = db.scheduledRemindersDao().getPendingForCalendar(id).map { it.id }
        assertTrue("the event's reminder is armed", reminderIds.isNotEmpty() && armedReminderIds().containsAll(reminderIds))
        dataStore.setDefaultCalendar(DefaultCalendar.Room(id))

        assertEquals(1, vm.deleteOfflineCalendar(id).getOrThrow())

        awaitList { rows -> rows.none { it.calendar.id == id } }
        assertNull(db.calendarsDao().getById(id))
        assertTrue("no alarm of the deleted calendar is left", armedReminderIds().intersect(reminderIds.toSet()).isEmpty())
        assertEquals(DefaultCalendar.Room(builtInId), dataStore.getDefaultCalendar())
    }

    @Test
    fun `a delete still completes when its caller is cancelled`() = runBlocking {
        val id = vm.createOfflineCalendar("Work", 0).getOrThrow()
        coordinator.createEvent(event(id, "Dentist"), calendarId = id)
        val reminderIds = db.scheduledRemindersDao().getPendingForCalendar(id).map { it.id }
        dataStore.setDefaultCalendar(DefaultCalendar.Room(id))

        val caller = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { vm.deleteOfflineCalendar(id) }
        caller.cancel()

        withTimeout(5_000) {
            while (db.calendarsDao().getById(id) != null || dataStore.getDefaultCalendar() != DefaultCalendar.Room(builtInId)) {
                delay(10)
            }
        }
        assertTrue(armedReminderIds().intersect(reminderIds.toSet()).isEmpty())
    }

    @Test
    fun `a create still completes when its caller is cancelled`() = runBlocking {
        val caller = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { vm.createOfflineCalendar("Trips", 0) }
        caller.cancel()
        withTimeout(5_000) {
            while (db.calendarsDao().getAll().first().none { it.displayName == "Trips" }) delay(10)
        }
    }

    // ---- the default calendar's tint ----

    @Test
    fun `the tinted calendar is the Room default`() = runBlocking {
        val id = vm.createOfflineCalendar("Work", 0).getOrThrow()
        dataStore.setDefaultCalendar(DefaultCalendar.Room(id))
        withTimeout(5_000) { while (vm.offlineDefaultCalendarId.value != id) delay(10) }
        assertEquals(id, vm.offlineDefaultCalendarId.value)
    }

    @Test
    fun `a device default with the same id tints nothing`() = runBlocking {
        val id = vm.createOfflineCalendar("Work", 0).getOrThrow()
        dataStore.setDefaultCalendar(DefaultCalendar.Room(id))
        withTimeout(5_000) { while (vm.offlineDefaultCalendarId.value != id) delay(10) }

        dataStore.setDefaultCalendar(DefaultCalendar.Device(id))
        withTimeout(5_000) { while (vm.offlineDefaultCalendarId.value != null) delay(10) }
        assertNull(vm.offlineDefaultCalendarId.value)
    }
}
