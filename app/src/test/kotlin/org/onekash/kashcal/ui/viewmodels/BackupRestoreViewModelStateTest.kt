package org.onekash.kashcal.ui.viewmodels

import android.app.Application
import android.content.pm.PackageManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.contacts.ContactEventManager
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.backup.BackupEnvelope
import org.onekash.kashcal.domain.backup.BackupImportError
import org.onekash.kashcal.domain.backup.BackupParseResult
import org.onekash.kashcal.domain.backup.ImportResult
import org.onekash.kashcal.domain.backup.SettingsBackupExporter
import org.onekash.kashcal.domain.backup.SettingsBackupImporter
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.SyncLogReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.ui.screens.BackupRestoreUiState
import org.onekash.kashcal.widget.WidgetUpdateManager

/**
 * Unit tests for AccountSettingsViewModel's backup/restore state machine:
 * state transitions driven by onBackupFileSelected / confirmRestore / dismissDialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelStateTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var application: Application
    private lateinit var accountRepository: AccountRepository
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var discoveryService: AccountDiscoveryService
    private lateinit var calDavDiscoveryService: CalDavAccountDiscoveryService
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var syncLogReader: SyncLogReader
    private lateinit var contactEventManager: ContactEventManager
    private lateinit var calendarProviderManager: CalendarProviderManager
    private lateinit var calendarProviderRepository: CalendarProviderRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var eventWriter: EventWriter
    private lateinit var deviceCalendarReminderScheduler: DeviceCalendarReminderScheduler
    private lateinit var backupExporter: SettingsBackupExporter
    private lateinit var backupImporter: SettingsBackupImporter

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        application = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        discoveryService = mockk(relaxed = true)
        calDavDiscoveryService = mockk(relaxed = true)
        eventCoordinator = mockk(relaxed = true)
        syncLogReader = mockk(relaxed = true)
        contactEventManager = mockk(relaxed = true)
        calendarProviderManager = mockk(relaxed = true)
        calendarProviderRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        eventWriter = mockk(relaxed = true)
        deviceCalendarReminderScheduler = mockk(relaxed = true)
        backupExporter = mockk(relaxed = true)
        backupImporter = mockk(relaxed = true)

        // Minimal flow plumbing — the VM's init block observes many flows.
        every { eventCoordinator.getAllCalendars() } returns MutableStateFlow(emptyList<Calendar>())
        every { eventCoordinator.getAllAccounts() } returns flowOf(emptyList())
        every { eventCoordinator.getICloudCalendarCount() } returns MutableStateFlow(0)
        every { eventCoordinator.getCalDavAccountCount() } returns MutableStateFlow(0)
        every { eventCoordinator.getCalDavAccounts() } returns flowOf(emptyList())
        every { eventCoordinator.getAllIcsSubscriptions() } returns flowOf(emptyList())
        every { userPreferences.defaultCalendarId } returns MutableStateFlow<Long?>(null)
        every { userPreferences.defaultCalendar } returns MutableStateFlow<DefaultCalendar?>(null)
        every { userPreferences.syncIntervalMs } returns MutableStateFlow(24 * 60 * 60 * 1000L)
        every { userPreferences.defaultReminderTimed } returns MutableStateFlow(15)
        every { userPreferences.defaultReminderAllDay } returns MutableStateFlow(1440)
        every { userPreferences.defaultEventDuration } returns MutableStateFlow(60)
        every { syncLogReader.getRecentLogs(any()) } returns MutableStateFlow(emptyList<SyncLog>())
        every { dataStore.contactBirthdaysEnabled } returns MutableStateFlow(false)
        every { dataStore.contactBirthdaysLastSync } returns MutableStateFlow(0L)
        every { dataStore.birthdayReminder } returns MutableStateFlow(540)
        every { dataStore.contactAnniversariesEnabled } returns MutableStateFlow(false)
        every { dataStore.contactAnniversariesLastSync } returns MutableStateFlow(0L)
        every { dataStore.anniversaryReminder } returns MutableStateFlow(540)
        every { dataStore.deviceCalendarsEnabled } returns MutableStateFlow(false)
        every { dataStore.enabledDeviceCalendarIds } returns MutableStateFlow(emptySet<Long>())
        every { dataStore.showDeclinedEvents } returns MutableStateFlow(false)
        every { dataStore.deviceCalendarRemindersEnabled } returns MutableStateFlow(false)
        every { dataStore.showEventEmojis } returns MutableStateFlow(true)
        every { dataStore.timeFormat } returns MutableStateFlow(KashCalDataStore.TIME_FORMAT_SYSTEM)
        every { dataStore.firstDayOfWeek } returns MutableStateFlow(java.util.Calendar.SUNDAY)
        every { dataStore.showWeekNumbers } returns MutableStateFlow(false)
        every { dataStore.widgetMaxEventsPerDay } returns MutableStateFlow(5)
        every { dataStore.syncPastDays } returns MutableStateFlow(KashCalDataStore.DEFAULT_SYNC_PAST_DAYS)
        every { dataStore.quickAddEnabled } returns MutableStateFlow(false)
        every { dataStore.titleSuggestionsEnabled } returns MutableStateFlow(true)
        coEvery { accountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns emptyList()
        coEvery { accountRepository.hasCredentials(any()) } returns false
        coEvery { eventCoordinator.getContactBirthdayEventCount() } returns 0
        coEvery { eventCoordinator.getContactAnniversaryEventCount() } returns 0
        coEvery { eventCoordinator.getContactBirthdaysColor() } returns null
        coEvery { eventCoordinator.getContactAnniversariesColor() } returns null
        every { application.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AccountSettingsViewModel = AccountSettingsViewModel(
        application = application,
        accountRepository = accountRepository,
        userPreferences = userPreferences,
        syncScheduler = syncScheduler,
        discoveryService = discoveryService,
        calDavDiscoveryService = calDavDiscoveryService,
        eventCoordinator = eventCoordinator,
        eventWriter = eventWriter,
        syncLogReader = syncLogReader,
        contactEventManager = contactEventManager,
        calendarProviderManager = calendarProviderManager,
        calendarProviderRepository = calendarProviderRepository,
        dataStore = dataStore,
        widgetUpdateManager = widgetUpdateManager,
        deviceCalendarReminderScheduler = deviceCalendarReminderScheduler,
        backupExporter = backupExporter,
        backupImporter = backupImporter,
    )

    private fun envelope(
        subscriptions: Int = 0,
        preferences: Int = 3,
    ): BackupEnvelope = BackupEnvelope(
        fileFormatVersion = 1,
        appVersion = "23.6.4",
        exportedAt = "2026-04-23T14-30-00Z",
        preferences = (0 until preferences).associate {
            "KEY_$it" to org.onekash.kashcal.domain.backup.BackupPreferenceValue.BoolPref(true)
        },
        subscriptions = (0 until subscriptions).map {
            org.onekash.kashcal.domain.backup.BackupSubscription(
                url = "https://feed.example/$it.ics",
                name = "Feed $it",
                color = 0xFFFFFFFF.toInt(),
                syncIntervalHours = 24,
                enabled = true,
            )
        },
    )

    @Test
    fun `onBackupFileSelected with valid JSON sets PendingConfirmation with summary`() = runTest {
        val env = envelope(subscriptions = 1, preferences = 7)
        every { backupImporter.parseAndValidate(any()) } returns BackupParseResult.Ok(env)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("{\"ignored\":\"parse is mocked\"}")
        advanceUntilIdle()

        val state = viewModel.backupRestoreState.value
        assertTrue("expected PendingConfirmation, was $state", state is BackupRestoreUiState.PendingConfirmation)
        val pending = state as BackupRestoreUiState.PendingConfirmation
        assertEquals(env, pending.envelope)
        assertEquals(1, pending.summary.subscriptions)
        assertEquals("23.6.4", pending.summary.appVersion)
    }

    @Test
    fun `onBackupFileSelected with malformed JSON sets Error`() = runTest {
        val err = BackupImportError.MalformedJson("not json")
        every { backupImporter.parseAndValidate(any()) } returns BackupParseResult.Error(err)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("nope")
        advanceUntilIdle()

        val state = viewModel.backupRestoreState.value
        assertTrue("expected Error, was $state", state is BackupRestoreUiState.Error)
        assertEquals(err, (state as BackupRestoreUiState.Error).error)
    }

    @Test
    fun `confirmRestore on Idle is a no-op`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.confirmRestore()
        advanceUntilIdle()

        assertEquals(BackupRestoreUiState.Idle, viewModel.backupRestoreState.value)
        coVerify(exactly = 0) { backupImporter.applyBackup(any()) }
    }

    @Test
    fun `dismissDialog from PendingConfirmation returns to Idle`() = runTest {
        val env = envelope()
        every { backupImporter.parseAndValidate(any()) } returns BackupParseResult.Ok(env)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("{}")
        advanceUntilIdle()
        assertTrue(viewModel.backupRestoreState.value is BackupRestoreUiState.PendingConfirmation)

        viewModel.dismissDialog()
        advanceUntilIdle()

        assertEquals(BackupRestoreUiState.Idle, viewModel.backupRestoreState.value)
    }

    @Test
    fun `dismissDialog from Error returns to Idle`() = runTest {
        every { backupImporter.parseAndValidate(any()) } returns
            BackupParseResult.Error(BackupImportError.MalformedJson("bad"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("nope")
        advanceUntilIdle()

        viewModel.dismissDialog()
        advanceUntilIdle()

        assertEquals(BackupRestoreUiState.Idle, viewModel.backupRestoreState.value)
    }

    @Test
    fun `dismissDialog from Success returns to Idle`() = runTest {
        val env = envelope()
        every { backupImporter.parseAndValidate(any()) } returns BackupParseResult.Ok(env)
        val result = ImportResult(
            subscriptionsCreated = 0,
            subscriptionsUpdated = 0,
            preferencesApplied = 3,
            deviceCalendarsNoteNeeded = false,
        )
        coEvery { backupImporter.applyBackup(env) } returns result

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("{}")
        advanceUntilIdle()
        viewModel.confirmRestore()
        advanceUntilIdle()
        assertTrue(viewModel.backupRestoreState.value is BackupRestoreUiState.Success)

        viewModel.dismissDialog()
        advanceUntilIdle()
        assertEquals(BackupRestoreUiState.Idle, viewModel.backupRestoreState.value)
    }

    @Test
    fun `confirmRestore from PendingConfirmation applies and transitions to Success`() = runTest {
        val env = envelope(subscriptions = 2, preferences = 4)
        every { backupImporter.parseAndValidate(any()) } returns BackupParseResult.Ok(env)
        val result = ImportResult(
            subscriptionsCreated = 2,
            subscriptionsUpdated = 0,
            preferencesApplied = 4,
            deviceCalendarsNoteNeeded = true,
        )
        coEvery { backupImporter.applyBackup(env) } returns result

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("{}")
        advanceUntilIdle()
        viewModel.confirmRestore()
        advanceUntilIdle()

        val state = viewModel.backupRestoreState.value
        assertTrue("expected Success, was $state", state is BackupRestoreUiState.Success)
        assertEquals(result, (state as BackupRestoreUiState.Success).result)
        coVerify(exactly = 1) { backupImporter.applyBackup(env) }
    }

    @Test
    fun `confirmRestore surfaces ApplyFailed when applyBackup throws`() = runTest {
        val env = envelope()
        every { backupImporter.parseAndValidate(any()) } returns BackupParseResult.Ok(env)
        coEvery { backupImporter.applyBackup(env) } throws IllegalStateException("disk full")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBackupFileSelected("{}")
        advanceUntilIdle()
        viewModel.confirmRestore()
        advanceUntilIdle()

        val state = viewModel.backupRestoreState.value
        assertTrue("expected Error, was $state", state is BackupRestoreUiState.Error)
        val err = (state as BackupRestoreUiState.Error).error
        assertTrue("expected ApplyFailed, was $err", err is BackupImportError.ApplyFailed)
    }
}
