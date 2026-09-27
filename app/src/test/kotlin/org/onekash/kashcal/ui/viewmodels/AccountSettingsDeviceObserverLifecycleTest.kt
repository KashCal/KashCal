package org.onekash.kashcal.ui.viewmodels

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.eventsObserverCount
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.calendar_provider.notifyEventsChanged
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.backup.BackupEnvelope
import org.onekash.kashcal.domain.backup.BackupJson
import org.onekash.kashcal.domain.backup.BackupPreferenceValue
import org.onekash.kashcal.domain.backup.SettingsBackupImporter
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.ui.permission.FakePermissionChecker
import org.onekash.kashcal.ui.screens.BackupRestoreUiState
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests that the CalendarProvider observer must run while device calendars are enabled and
 * READ_CALENDAR is granted, and only then, whatever changed the setting: the master switch, a
 * settings toggle or a backup restore. A toggle while device calendars are off only re-queries.
 *
 * Drives the real AccountSettingsViewModel with a real CalendarProviderManager, a real DataStore
 * and a real backup importer, and counts observers through Robolectric's ContentResolver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AccountSettingsDeviceObserverLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: CalendarProviderManager
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var reminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var repo: FakeCalendarProviderRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "observer_lifecycle_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        // Unit-returning side-effect collaborators, so relaxed is allowed.
        widgetUpdateManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        manager = CalendarProviderManager(context, dataStore, reminderScheduler, widgetUpdateManager)
        permissionChecker = FakePermissionChecker().apply {
            calendarRead = true
            calendarWrite = true
        }
        repo = FakeCalendarProviderRepository()
    }

    @After
    fun teardown() {
        manager.onDisabled()
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        dataStoreFile.delete()
    }

    private fun observerCount(): Int = eventsObserverCount(context)

    private fun notifyProviderChange() = notifyEventsChanged(context)

    private fun passDebounce() {
        testDispatcher.scheduler.advanceTimeBy(CalendarProviderManager.OBSERVER_DEBOUNCE_MS + 1)
        testDispatcher.scheduler.runCurrent()
    }

    private fun createViewModel(): AccountSettingsViewModel {
        // Data-bearing collaborators are strict: only what the ViewModel's init
        // reads is stubbed, so an unexpected read fails the test.
        val accountRepository = mockk<AccountRepository> {
            coEvery { getAccountsByProvider(AccountProvider.ICLOUD) } returns emptyList()
        }
        val eventCoordinator = mockk<EventCoordinator> {
            every { getAllCalendars() } returns MutableStateFlow(emptyList<Calendar>())
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
            syncLogReader = syncLogReader,
            contactEventManager = mockk<ContactEventManager>(),
            calendarProviderManager = manager,
            deviceEventReader = repo.deviceEventReader(),
            deviceEventWriter = repo.deviceEventWriter(dataStore, manager),
            dataStore = dataStore,
            widgetUpdateManager = widgetUpdateManager,
            deviceCalendarReminderScheduler = reminderScheduler,
            backupExporter = mockk(),
            backupImporter = importer,
            permissionChecker = permissionChecker,
            context = context,
            applicationScope = CoroutineScope(SupervisorJob() + testDispatcher),
        )
    }

    /**
     * Restores a backup carrying SHOW_WEEK_NUMBERS and, unless [deviceCalendarsEnabled] is null,
     * the device-calendars switch.
     */
    private fun TestScope.restore(viewModel: AccountSettingsViewModel, deviceCalendarsEnabled: Boolean?) {
        val prefs = buildMap {
            put(PreferencesKeys.SHOW_WEEK_NUMBERS.name, BackupPreferenceValue.BoolPref(true))
            if (deviceCalendarsEnabled != null) {
                put(PreferencesKeys.DEVICE_CALENDARS_ENABLED.name, BackupPreferenceValue.BoolPref(deviceCalendarsEnabled))
            }
        }
        val json = BackupJson.encodeToString(
            BackupEnvelope.serializer(),
            BackupEnvelope(
                fileFormatVersion = 1,
                appVersion = "t",
                exportedAt = "t",
                preferences = prefs,
                subscriptions = emptyList(),
            ),
        )
        viewModel.onBackupFileSelected(json)
        assertTrue(
            "backup JSON should parse: ${viewModel.backupRestoreState.value}",
            viewModel.backupRestoreState.value is BackupRestoreUiState.PendingConfirmation,
        )
        viewModel.confirmRestore()
        advanceUntilIdle()
        assertTrue(viewModel.backupRestoreState.value is BackupRestoreUiState.Success)
    }

    // ---- settings actions while device calendars are off ----

    @Test
    fun `show declined while device calendars are off only re-queries`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val before = manager.changeSignal.value

        viewModel.onToggleShowDeclinedEvents(true)
        advanceUntilIdle()

        assertEquals(0, observerCount())
        assertEquals(before + 1, manager.changeSignal.value)
    }

    @Test
    fun `ticking a calendar while device calendars are off only re-queries`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val before = manager.changeSignal.value

        viewModel.onToggleDeviceCalendar(calendarId = 1L, enabled = true)
        advanceUntilIdle()

        assertEquals(0, observerCount())
        assertEquals(before + 1, manager.changeSignal.value)
    }

    // ---- calendar tick while device calendars are on, and the master switch ----

    @Test
    fun `ticking a calendar while device calendars are on but not observed starts observing`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals("not observing before the tick", 0, observerCount())

        viewModel.onToggleDeviceCalendar(calendarId = 1L, enabled = true)
        advanceUntilIdle()

        assertEquals(1, observerCount())
        notifyProviderChange()
        passDebounce()
        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets("device_calendar_changed") }
    }

    @Test
    fun `the master switch starts and stops observing`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onToggleDeviceCalendars(true)
        advanceUntilIdle()
        assertEquals(1, observerCount())

        viewModel.onToggleDeviceCalendars(false)
        advanceUntilIdle()
        assertEquals(0, observerCount())
    }

    // ---- restore ----

    @Test
    fun `restoring a backup that turns device calendars on starts observing at once`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(0, observerCount())

        restore(viewModel, deviceCalendarsEnabled = true)

        assertEquals(1, observerCount())
        notifyProviderChange()
        passDebounce()
        coVerify(exactly = 1) { widgetUpdateManager.updateAllWidgets("device_calendar_changed") }
        coVerify(exactly = 1) { reminderScheduler.scheduleNextReminder() }
    }

    @Test
    fun `restoring a backup that turns device calendars off stops observing`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        manager.onEnabled()
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(1, observerCount())

        restore(viewModel, deviceCalendarsEnabled = false)

        assertEquals(0, observerCount())
        verify { reminderScheduler.cancelPendingAlarm() }
        notifyProviderChange()
        passDebounce()
        coVerify(exactly = 0) { widgetUpdateManager.updateAllWidgets(any()) }
    }

    @Test
    fun `restoring device calendars on without calendar permission leaves them off and unobserved`() = runTest(testDispatcher) {
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)
        permissionChecker.calendarRead = false
        val viewModel = createViewModel()
        advanceUntilIdle()

        restore(viewModel, deviceCalendarsEnabled = true)

        assertEquals(0, observerCount())
        // The auto-disable write completes; waiting for "off" is the assertion.
        withTimeout(5_000) { dataStore.deviceCalendarsEnabled.first { !it } }
    }

    @Test
    fun `restoring a backup without the device calendar setting keeps observing as it was`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        manager.onEnabled()
        val viewModel = createViewModel()
        advanceUntilIdle()

        restore(viewModel, deviceCalendarsEnabled = null)
        assertEquals("on stays observed", 1, observerCount())

        viewModel.onToggleDeviceCalendars(false)
        advanceUntilIdle()
        restore(viewModel, deviceCalendarsEnabled = null)
        assertEquals("off stays unobserved", 0, observerCount())
    }
}
