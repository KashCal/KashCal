package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.backup.SettingsBackupImporter
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.feeds.noFeedAccountsServerCalendarFeeds
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.ui.permission.FakePermissionChecker
import org.onekash.kashcal.ui.viewmodels.AccountSettingsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests that unticking a device calendar in Settings > Device calendars stops its reminders: the
 * one armed device alarm moves to the soonest reminder of the calendars still enabled, and an
 * alarm of the unticked calendar that fires anyway (armed before the untick, or a snooze) posts
 * nothing and moves the alarm on. The other calendars' reminders keep showing.
 *
 * Drives the real AccountSettingsViewModel with the real DeviceCalendarReminderScheduler,
 * CalendarProviderManager, DataStore and AndroidCalendarProviderRepository over
 * SqliteCalendarProvider, and delivers alarms through the real receiver handler. The repository
 * reads on Dispatchers.IO, so the test polls instead of only advancing the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceUntickReminderTest {

    private companion object {
        const val CAL_A = 1L
        const val CAL_B = 2L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repository: AndroidCalendarProviderRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: DeviceCalendarReminderScheduler
    private lateinit var manager: CalendarProviderManager
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = SqliteCalendarProvider.install()
        provider.seedCalendar(CAL_A)
        provider.seedCalendar(CAL_B)
        repository = SqliteCalendarProvider.repository(context)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "untick_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(CAL_A, CAL_B))
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
        scheduler = DeviceCalendarReminderScheduler(context, repository, dataStore)
        manager = CalendarProviderManager(context, dataStore, scheduler, mockk(relaxed = true), DeviceCalendarChangeWatcher(context, dataStore))
    }

    @After
    fun tearDown() {
        manager.onDisabled()
        provider.db.close()
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        dataStoreFile.delete()
    }

    private fun eventWithReminder(calendarId: Long, start: Long): Long {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId, Events.TITLE to "Event in $calendarId", Events.DTSTART to start,
            Events.DTEND to start + HOUR, Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
            Events.STATUS to Events.STATUS_CONFIRMED,
        )
        provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to id, Reminders.MINUTES to 60, Reminders.METHOD to Reminders.METHOD_ALERT,
        )
        return id
    }

    /** Device reminder alarms currently set, as event id to trigger time. */
    private fun deviceAlarms(): List<Pair<Long, Long>> =
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { Shadows.shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != DeviceCalendarAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1L) to alarm.triggerAtMs
        }

    private suspend fun TestScope.awaitUntil(what: String, condition: suspend () -> Boolean) {
        // Generous: the first test in a cold JVM spends seconds loading classes.
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what; alarms=${deviceAlarms()}")
            Thread.sleep(20)
        }
    }

    private fun createViewModel(): AccountSettingsViewModel {
        // Data-bearing collaborators are strict: only what the ViewModel's init reads is stubbed.
        val accountRepository = mockk<AccountRepository> {
            coEvery { getAccountsByProvider(AccountProvider.ICLOUD) } returns emptyList()
            every { getAccountsByProviderFlow(AccountProvider.ICLOUD) } returns flowOf(emptyList())
        }
        val eventCoordinator = mockk<EventCoordinator> {
            every { getAllCalendars() } returns MutableStateFlow(emptyList<Calendar>())
            every { getLocalCalendarSummaries(any()) } returns flowOf(emptyList())
            every { getAllAccounts() } returns flowOf(emptyList())
            every { getICloudCalendarCount() } returns MutableStateFlow(0)
            every { getCalDavAccountCount() } returns MutableStateFlow(0)
            every { getCalDavAccounts() } returns flowOf(emptyList())
            every { getAllIcsSubscriptions() } returns flowOf(emptyList())
            coEvery { getContactBirthdayEventCount() } returns 0
            coEvery { getContactAnniversaryEventCount() } returns 0
            coEvery { getContactBirthdaysColor() } returns null
            coEvery { getContactAnniversariesColor() } returns null
        }
        val syncLogReader = mockk<SyncLogReader> {
            every { getRecentLogs(any()) } returns MutableStateFlow(emptyList())
        }
        val importer = SettingsBackupImporter(
            database = mockk(),
            dataStore = dataStore,
            accountRepository = mockk(),
            calendarRepository = mockk(),
            icsSubscriptionsDao = mockk(),
            categoryDao = mockk(),
            icsRefreshScheduleReconciler = mockk(),
            context = context,
        )
        return AccountSettingsViewModel(
            accountRepository = accountRepository,
            userPreferences = UserPreferencesRepository(dataStore),
            syncScheduler = mockk(relaxed = true),
            discoveryService = mockk(),
            calDavDiscoveryService = mockk(),
            eventCoordinator = eventCoordinator,
            serverCalendarFeeds = noFeedAccountsServerCalendarFeeds(),
            syncLogReader = syncLogReader,
            contactEventManager = mockk<ContactEventManager>(),
            calendarProviderManager = manager,
            deviceEventReader = repository.deviceEventReader(),
            deviceEventWriter = repository.deviceEventWriter(dataStore, manager),
            dataStore = dataStore,
            widgetUpdateManager = mockk(relaxed = true),
            deviceCalendarReminderScheduler = scheduler,
            backupExporter = mockk(),
            backupImporter = importer,
            permissionChecker = FakePermissionChecker().apply {
                calendarRead = true
                calendarWrite = true
            },
            changeNotificationChannels = org.onekash.kashcal.sync.notification.blockedChangeNotificationChannels(),
            reminderNotificationChannels = org.onekash.kashcal.reminder.notification.allowedReminderNotificationChannels(),
            context = context,
            applicationScope = CoroutineScope(SupervisorJob() + testDispatcher),
        )
    }

    /** Unticks [calendarId] the way the Settings > Device calendars checkbox does. */
    private suspend fun TestScope.untick(viewModel: AccountSettingsViewModel, calendarId: Long) {
        // The ViewModel builds the new set from its copy of the enabled ids.
        awaitUntil("the enabled ids to load") { viewModel.enabledDeviceCalendarIds.value == setOf(CAL_A, CAL_B) }
        viewModel.onToggleDeviceCalendar(calendarId, enabled = false)
        awaitUntil("the untick to be stored") { dataStore.getEnabledDeviceCalendarIds() == setOf(CAL_B) }
    }

    private suspend fun deliver(
        notifications: DeviceCalendarReminderNotificationManager,
        eventId: Long,
        calendarId: Long,
        start: Long,
    ) = DeviceCalendarAlarmReceiver().handleAlarm(
        scheduler = scheduler,
        notificationManager = notifications,
        eventId = eventId,
        occurrenceTs = start,
        title = "Event in $calendarId",
        location = null,
        isAllDay = false,
        calendarColor = 0,
        calendarId = calendarId,
        triggerTime = start - HOUR,
    )

    private fun recordingNotifications() = mockk<DeviceCalendarReminderNotificationManager> {
        coEvery { showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
    }

    @Test
    fun `switching device calendars off and back on re-arms the next reminder`() = runTest(testDispatcher) {
        // Off cancels the armed alarm. Nothing else changes in the provider, so only the switch
        // turning back on can arm the reminder again before it is due.
        val event = eventWithReminder(CAL_A, start = now + 2 * HOUR)
        scheduler.scheduleNextReminder()
        assertEquals(listOf(event to now + HOUR), deviceAlarms())
        val viewModel = createViewModel()

        viewModel.onToggleDeviceCalendars(false)
        awaitUntil("the switch-off to cancel the alarm") { deviceAlarms().isEmpty() }
        viewModel.onToggleDeviceCalendars(true)

        awaitUntil("the switch-on to re-arm the reminder") { deviceAlarms() == listOf(event to now + HOUR) }
    }

    @Test
    fun `unticking the calendar that holds the next reminder moves the alarm to another calendar`() = runTest(testDispatcher) {
        val unticked = eventWithReminder(CAL_A, start = now + 2 * HOUR)
        val kept = eventWithReminder(CAL_B, start = now + 4 * HOUR)
        scheduler.scheduleNextReminder()
        assertEquals(listOf(unticked to now + HOUR), deviceAlarms())
        val viewModel = createViewModel()

        untick(viewModel, CAL_A)

        awaitUntil("the alarm to move to the kept calendar") { deviceAlarms() == listOf(kept to now + 3 * HOUR) }
    }

    @Test
    fun `an unticked calendar's alarm that fires anyway shows nothing and arms the next reminder`() = runTest(testDispatcher) {
        // An alarm armed before the untick and never replaced, or a snooze alarm, which carries
        // the same calendar id.
        val unticked = eventWithReminder(CAL_A, start = now + 2 * HOUR)
        val kept = eventWithReminder(CAL_B, start = now + 4 * HOUR)
        scheduler.scheduleNextReminder()
        untick(createViewModel(), CAL_A)
        awaitUntil("the untick's re-pick") { deviceAlarms() == listOf(kept to now + 3 * HOUR) }
        // Clear it, so only the receiver's own re-pick can arm the next reminder below.
        scheduler.cancelPendingAlarm()
        val notifications = recordingNotifications()

        deliver(notifications, unticked, CAL_A, start = now + 2 * HOUR)

        coVerify(exactly = 0) { notifications.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) }
        awaitUntil("the receiver to arm the next reminder") { deviceAlarms() == listOf(kept to now + 3 * HOUR) }
    }

    @Test
    fun `the calendars still enabled keep showing their reminders`() = runTest(testDispatcher) {
        eventWithReminder(CAL_A, start = now + 2 * HOUR)
        val kept = eventWithReminder(CAL_B, start = now + 4 * HOUR)
        scheduler.scheduleNextReminder()
        untick(createViewModel(), CAL_A)
        val notifications = recordingNotifications()

        deliver(notifications, kept, CAL_B, start = now + 4 * HOUR)

        coVerify(exactly = 1) { notifications.showNotification(eq(kept), any(), any(), any(), any(), any(), any(), any()) }
    }
}
