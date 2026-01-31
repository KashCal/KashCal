package org.onekash.kashcal.sync.worker

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.provider.icloud.ICloudUrlMigration
import java.util.concurrent.TimeUnit
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CalDavSyncWorker.
 *
 * Tests:
 * - Sync type routing (full/calendar/account)
 * - Success/failure result handling
 * - Input/output data handling
 * - Retry logic
 * - Error scenarios
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var syncEngine: CalDavSyncEngine
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var notificationManager: SyncNotificationManager
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var mockQuirks: CalDavQuirks
    private lateinit var mockCredentialProvider: CredentialProvider
    private lateinit var calDavClient: CalDavClient
    private lateinit var calDavClientFactory: CalDavClientFactory
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var widgetUpdateManager: org.onekash.kashcal.widget.WidgetUpdateManager
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var eventReader: EventReader
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var syncSessionStore: SyncSessionStore
    private lateinit var syncLogsDao: SyncLogsDao
    private lateinit var iCloudUrlMigration: ICloudUrlMigration
    private lateinit var worker: CalDavSyncWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        syncEngine = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        providerRegistry = mockk(relaxed = true)
        mockQuirks = mockk(relaxed = true)
        mockCredentialProvider = mockk(relaxed = true)
        calDavClient = mockk(relaxed = true)
        calDavClientFactory = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        pendingOperationsDao = mockk(relaxed = true)
        syncSessionStore = mockk(relaxed = true)
        syncLogsDao = mockk(relaxed = true)
        iCloudUrlMigration = mockk(relaxed = true)

        // Default: iCloud URL migration returns false (already completed)
        coEvery { iCloudUrlMigration.migrateIfNeeded() } returns false

        // Default: return empty input data
        every { workerParams.inputData } returns Data.EMPTY
        every { workerParams.runAttemptCount } returns 0

        // Make createForegroundInfo throw so setForeground is skipped in tests
        // (setForeground doesn't work properly in unit tests without WorkManager test utilities)
        every { notificationManager.createForegroundInfo(any(), any()) } throws
            IllegalStateException("Test: foreground not available")

        // Default: setup provider registry mocks (new API)
        every { providerRegistry.getQuirks(any()) } returns mockQuirks
        every { providerRegistry.getCredentialProvider(any()) } returns mockCredentialProvider

        // Default: return test credentials
        coEvery { mockCredentialProvider.getCredentials(any()) } returns Credentials(
            username = "test@icloud.com",
            password = "test-password"
        )

        // Default: factory returns isolated client (the main calDavClient mock)
        every { calDavClientFactory.createClient(any(), any()) } returns calDavClient
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createWorker(inputData: Data = Data.EMPTY): CalDavSyncWorker {
        every { workerParams.inputData } returns inputData
        return CalDavSyncWorker(
            context = context,
            params = workerParams,
            syncEngine = syncEngine,
            accountsDao = accountsDao,
            calendarsDao = calendarsDao,
            notificationManager = notificationManager,
            providerRegistry = providerRegistry,
            calDavClientFactory = calDavClientFactory,
            syncScheduler = syncScheduler,
            widgetUpdateManager = widgetUpdateManager,
            reminderScheduler = reminderScheduler,
            eventReader = eventReader,
            pendingOperationsDao = pendingOperationsDao,
            syncSessionStore = syncSessionStore,
            syncLogsDao = syncLogsDao,
            iCloudUrlMigration = iCloudUrlMigration
        )
    }

    // ==================== Full Sync Tests ====================

    @Test
    fun `full sync with success returns Result_success`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val syncResult = SyncResult.Success(
            calendarsSynced = 2,
            eventsPushedCreated = 1,
            eventsPushedUpdated = 2,
            eventsPushedDeleted = 0,
            eventsPulledAdded = 5,
            eventsPulledUpdated = 3,
            eventsPulledDeleted = 1,
            conflictsResolved = 0,
            durationMs = 1500
        )

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), calDavClient, any()) }
    }

    @Test
    fun `full sync with force flag passes to engine`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = true)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), true, any(), any(), any()) } returns
            SyncResult.Success(calendarsSynced = 1, durationMs = 100)

        // When
        worker.doWork()

        // Then
        coVerify { syncEngine.syncAccountWithQuirks(testAccount, any(), true, any(), calDavClient, any()) }
    }

    @Test
    fun `full sync with no accounts returns success with zero calendars`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 0) { syncEngine.syncAccountWithQuirks(any(), any(), any(), any(), any(), any()) }
    }

    // ==================== Calendar Sync Tests ====================

    @Test
    fun `calendar sync routes to syncCalendar`() = runTest {
        // Given
        val calendarId = 42L
        val inputData = CalDavSyncWorker.createCalendarSyncInput(calendarId)
        val worker = createWorker(inputData)
        val calendar = createTestCalendar(calendarId)

        coEvery { calendarsDao.getById(calendarId) } returns calendar
        coEvery { syncEngine.syncCalendar(calendar, false, any(), any(), any(), any()) } returns SyncResult.Success(
            calendarsSynced = 1,
            durationMs = 500
        )

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify { syncEngine.syncCalendar(calendar, false, any(), any(), any(), any()) }
    }

    @Test
    fun `calendar sync with missing calendar_id returns failure`() = runTest {
        // Given - Calendar sync type but no calendar_id
        val inputData = Data.Builder()
            .putString(CalDavSyncWorker.KEY_SYNC_TYPE, CalDavSyncWorker.SYNC_TYPE_CALENDAR)
            .build()
        val worker = createWorker(inputData)

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `calendar sync with unknown calendar returns error result`() = runTest {
        // Given
        val calendarId = 999L
        val inputData = CalDavSyncWorker.createCalendarSyncInput(calendarId)
        val worker = createWorker(inputData)

        coEvery { calendarsDao.getById(calendarId) } returns null

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    // ==================== Account Sync Tests ====================

    @Test
    fun `account sync routes to syncAccountWithQuirks`() = runTest {
        // Given
        val accountId = 7L
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId)
        val worker = createWorker(inputData)
        val account = createTestAccount(accountId)

        coEvery { accountsDao.getById(accountId) } returns account
        // Account sync uses same ProviderRegistry pattern as syncAll for consistency
        coEvery { syncEngine.syncAccountWithQuirks(account, any(), false, any(), calDavClient, any()) } returns SyncResult.Success(
            calendarsSynced = 3,
            durationMs = 800
        )

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify { syncEngine.syncAccountWithQuirks(account, any(), false, any(), calDavClient, any()) }
    }

    @Test
    fun `account sync with missing account_id returns failure`() = runTest {
        // Given
        val inputData = Data.Builder()
            .putString(CalDavSyncWorker.KEY_SYNC_TYPE, CalDavSyncWorker.SYNC_TYPE_ACCOUNT)
            .build()
        val worker = createWorker(inputData)

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `account sync with unknown account returns error result`() = runTest {
        // Given
        val accountId = 999L
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId)
        val worker = createWorker(inputData)

        coEvery { accountsDao.getById(accountId) } returns null

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    // ==================== Result Handling Tests ====================

    @Test
    fun `SyncResult_Success produces success with output data`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val syncResult = SyncResult.Success(
            calendarsSynced = 5,
            eventsPushedCreated = 2,
            eventsPushedUpdated = 3,
            eventsPushedDeleted = 1,
            eventsPulledAdded = 10,
            eventsPulledUpdated = 5,
            eventsPulledDeleted = 2,
            conflictsResolved = 1,
            durationMs = 2000
        )

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `SyncResult_PartialSuccess is treated as success`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val syncResult = SyncResult.PartialSuccess(
            calendarsSynced = 3,
            eventsPushedCreated = 1,
            eventsPushedUpdated = 1,
            eventsPushedDeleted = 0,
            eventsPulledAdded = 5,
            eventsPulledUpdated = 2,
            eventsPulledDeleted = 0,
            conflictsResolved = 0,
            durationMs = 1000,
            errors = listOf(
                SyncError(phase = SyncPhase.PULL, calendarId = 1L, message = "Network timeout"),
                SyncError(phase = SyncPhase.PULL, calendarId = 2L, message = "Parse error")
            )
        )

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `SyncResult_AuthError is aggregated and returns success`() = runTest {
        // Given - AuthError for one account doesn't fail the whole sync
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val syncResult = SyncResult.AuthError(
            message = "Invalid credentials"
        )

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then - Auth errors are aggregated but don't fail the overall sync
        // (Other accounts may succeed)
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `SyncResult_Error is aggregated and returns success`() = runTest {
        // Given - Single account error doesn't fail the whole sync
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val syncResult = SyncResult.Error(
            code = 500,
            message = "Server error",
            isRetryable = true
        )

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then - Errors are aggregated but don't fail the overall sync
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `calendar sync - SyncResult_Error with retryable true produces retry`() = runTest {
        // Given - Calendar-specific sync can still retry
        val calendarId = 42L
        val inputData = CalDavSyncWorker.createCalendarSyncInput(calendarId)
        val worker = createWorker(inputData)
        val calendar = createTestCalendar(calendarId)
        val syncResult = SyncResult.Error(
            code = 500,
            message = "Server error",
            isRetryable = true
        )

        coEvery { calendarsDao.getById(calendarId) } returns calendar
        coEvery { syncEngine.syncCalendar(calendar, false, any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `exceeding max retries produces failure on calendar sync`() = runTest {
        // Given - Simulate 4th attempt (0-indexed, so runAttemptCount = 3)
        every { workerParams.runAttemptCount } returns 3
        val calendarId = 42L
        val inputData = CalDavSyncWorker.createCalendarSyncInput(calendarId)
        val worker = createWorker(inputData)
        val calendar = createTestCalendar(calendarId)
        val syncResult = SyncResult.Error(
            code = 500,
            message = "Server error",
            isRetryable = true
        )

        coEvery { calendarsDao.getById(calendarId) } returns calendar
        coEvery { syncEngine.syncCalendar(calendar, false, any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then - Should fail after max retries even if retryable
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    // ==================== Exception Handling Tests ====================

    @Test
    fun `unhandled exception produces retry`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } throws
            RuntimeException("Unexpected error")

        // When
        val result = worker.doWork()

        // Then
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    // ==================== Input Data Helper Tests ====================

    @Test
    fun `createFullSyncInput creates correct data`() {
        // When
        val data = CalDavSyncWorker.createFullSyncInput(forceFullSync = true)

        // Then
        assertEquals(CalDavSyncWorker.SYNC_TYPE_FULL, data.getString(CalDavSyncWorker.KEY_SYNC_TYPE))
        assertTrue(data.getBoolean(CalDavSyncWorker.KEY_FORCE_FULL_SYNC, false))
    }

    @Test
    fun `createCalendarSyncInput creates correct data`() {
        // When
        val calendarId = 123L
        val data = CalDavSyncWorker.createCalendarSyncInput(calendarId, forceFullSync = true)

        // Then
        assertEquals(CalDavSyncWorker.SYNC_TYPE_CALENDAR, data.getString(CalDavSyncWorker.KEY_SYNC_TYPE))
        assertEquals(calendarId, data.getLong(CalDavSyncWorker.KEY_CALENDAR_ID, -1))
        assertTrue(data.getBoolean(CalDavSyncWorker.KEY_FORCE_FULL_SYNC, false))
    }

    @Test
    fun `createAccountSyncInput creates correct data`() {
        // When
        val accountId = 456L
        val data = CalDavSyncWorker.createAccountSyncInput(accountId, forceFullSync = false)

        // Then
        assertEquals(CalDavSyncWorker.SYNC_TYPE_ACCOUNT, data.getString(CalDavSyncWorker.KEY_SYNC_TYPE))
        assertEquals(accountId, data.getLong(CalDavSyncWorker.KEY_ACCOUNT_ID, -1))
        assertFalse(data.getBoolean(CalDavSyncWorker.KEY_FORCE_FULL_SYNC, true))
    }

    // ==================== Credential Loading Tests ====================

    @Test
    fun `sync without credentials skips account`() = runTest {
        // Given - No credentials available for account
        coEvery { mockCredentialProvider.getCredentials(any()) } returns null
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(createTestAccount())

        // When
        val result = worker.doWork()

        // Then - Returns success but skips account with no credentials
        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { syncEngine.syncAccountWithQuirks(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { calDavClientFactory.createClient(any(), any()) }
    }

    @Test
    fun `sync loads credentials per account and creates isolated client`() = runTest {
        // Given
        val testCredentials = Credentials(
            username = "user@icloud.com",
            password = "app-specific-pwd"
        )
        coEvery { mockCredentialProvider.getCredentials(any()) } returns testCredentials
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        val testAccount = createTestAccount()
        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), calDavClient, any()) } returns
            SyncResult.Success(calendarsSynced = 1, durationMs = 100)

        // When
        worker.doWork()

        // Then - Factory creates client with credentials (instead of mutating singleton)
        verify { calDavClientFactory.createClient(testCredentials, any()) }
        coVerify { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), calDavClient, any()) }
    }

    @Test
    fun `sync with custom server URL in credentials`() = runTest {
        // Given
        val testCredentials = Credentials(
            username = "user@example.com",
            password = "pwd123",
            serverUrl = "https://caldav.example.com"
        )
        coEvery { mockCredentialProvider.getCredentials(any()) } returns testCredentials
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        val testAccount = createTestAccount()
        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), calDavClient, any()) } returns
            SyncResult.Success(calendarsSynced = 1, durationMs = 100)

        // When
        worker.doWork()

        // Then - Factory creates client (server URL is part of credentials for discovery)
        verify { calDavClientFactory.createClient(testCredentials, any()) }
    }

    // ==================== Reminder Scheduling Tests ====================

    @Test
    fun `sync schedules reminders for NEW events with reminders`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val eventId = 100L
        val calendarId = 1L
        val now = System.currentTimeMillis()

        val syncChange = createTestSyncChange(ChangeType.NEW, eventId)
        val syncResult = SyncResult.Success(
            calendarsSynced = 1,
            eventsPulledAdded = 1,
            durationMs = 100,
            changes = listOf(syncChange)
        )

        val testEvent = createTestEvent(eventId, calendarId, reminders = listOf("-PT15M"))
        val testCalendar = createTestCalendar(calendarId)
        val testOccurrence = createTestOccurrence(eventId, calendarId)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult
        coEvery { eventReader.getEventById(eventId) } returns testEvent
        coEvery { eventReader.getCalendarById(calendarId) } returns testCalendar
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(eventId) } returns listOf(testOccurrence)

        // When
        worker.doWork()

        // Then
        coVerify { reminderScheduler.scheduleRemindersForEvent(testEvent, listOf(testOccurrence), any()) }
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `sync cancels and reschedules reminders for MODIFIED events`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val eventId = 100L
        val calendarId = 1L

        val syncChange = createTestSyncChange(ChangeType.MODIFIED, eventId)
        val syncResult = SyncResult.Success(
            calendarsSynced = 1,
            eventsPulledUpdated = 1,
            durationMs = 100,
            changes = listOf(syncChange)
        )

        val testEvent = createTestEvent(eventId, calendarId, reminders = listOf("-PT15M"))
        val testCalendar = createTestCalendar(calendarId)
        val testOccurrence = createTestOccurrence(eventId, calendarId)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult
        coEvery { eventReader.getEventById(eventId) } returns testEvent
        coEvery { eventReader.getCalendarById(calendarId) } returns testCalendar
        coEvery { eventReader.getOccurrencesForEventInScheduleWindow(eventId) } returns listOf(testOccurrence)

        // When
        worker.doWork()

        // Then - For MODIFIED events, cancel first, then schedule
        coVerify { reminderScheduler.cancelRemindersForEvent(eventId) }
        coVerify { reminderScheduler.scheduleRemindersForEvent(testEvent, listOf(testOccurrence), any()) }
    }

    @Test
    fun `sync skips reminder scheduling for DELETED events`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val eventId = 100L

        val syncChange = createTestSyncChange(ChangeType.DELETED, eventId)
        val syncResult = SyncResult.Success(
            calendarsSynced = 1,
            eventsPulledDeleted = 1,
            durationMs = 100,
            changes = listOf(syncChange)
        )

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult

        // When
        worker.doWork()

        // Then - No reminder scheduling for deleted events
        coVerify(exactly = 0) { eventReader.getEventById(any()) }
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `sync skips reminder scheduling for events without reminders`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val eventId = 100L
        val calendarId = 1L

        val syncChange = createTestSyncChange(ChangeType.NEW, eventId)
        val syncResult = SyncResult.Success(
            calendarsSynced = 1,
            eventsPulledAdded = 1,
            durationMs = 100,
            changes = listOf(syncChange)
        )

        // Event with no reminders
        val testEvent = createTestEvent(eventId, calendarId, reminders = null)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult
        coEvery { eventReader.getEventById(eventId) } returns testEvent

        // When
        worker.doWork()

        // Then - No reminder scheduling for events without reminders
        coVerify(exactly = 0) { eventReader.getCalendarById(any()) }
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `sync handles exception events correctly for reminders`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val eventId = 100L
        val masterEventId = 50L
        val calendarId = 1L

        val syncChange = createTestSyncChange(ChangeType.NEW, eventId)
        val syncResult = SyncResult.Success(
            calendarsSynced = 1,
            eventsPulledAdded = 1,
            durationMs = 100,
            changes = listOf(syncChange)
        )

        // Exception event (has originalEventId)
        val testEvent = createTestEvent(
            eventId,
            calendarId,
            reminders = listOf("-PT15M"),
            originalEventId = masterEventId
        )
        val testCalendar = createTestCalendar(calendarId)
        val testOccurrence = createTestOccurrence(eventId, calendarId)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult
        coEvery { eventReader.getEventById(eventId) } returns testEvent
        coEvery { eventReader.getCalendarById(calendarId) } returns testCalendar
        coEvery { eventReader.getOccurrenceByExceptionEventId(eventId) } returns testOccurrence

        // When
        worker.doWork()

        // Then - Uses getOccurrenceByExceptionEventId for exception events
        coVerify { eventReader.getOccurrenceByExceptionEventId(eventId) }
        coVerify(exactly = 0) { eventReader.getOccurrencesForEventInScheduleWindow(any()) }
        coVerify { reminderScheduler.scheduleRemindersForEvent(testEvent, listOf(testOccurrence), any()) }
    }

    // ==================== Trigger Propagation Tests ====================

    @Test
    fun `createFullSyncInput includes trigger in data`() {
        // When
        val data = CalDavSyncWorker.createFullSyncInput(
            trigger = org.onekash.kashcal.sync.session.SyncTrigger.FOREGROUND_PULL_TO_REFRESH
        )

        // Then
        assertEquals(
            "FOREGROUND_PULL_TO_REFRESH",
            data.getString(CalDavSyncWorker.KEY_SYNC_TRIGGER)
        )
    }

    @Test
    fun `createCalendarSyncInput includes trigger in data`() {
        // When
        val data = CalDavSyncWorker.createCalendarSyncInput(
            calendarId = 123L,
            trigger = org.onekash.kashcal.sync.session.SyncTrigger.FOREGROUND_APP_OPEN
        )

        // Then
        assertEquals(
            "FOREGROUND_APP_OPEN",
            data.getString(CalDavSyncWorker.KEY_SYNC_TRIGGER)
        )
    }

    @Test
    fun `createAccountSyncInput includes trigger in data`() {
        // When
        val data = CalDavSyncWorker.createAccountSyncInput(
            accountId = 456L,
            trigger = org.onekash.kashcal.sync.session.SyncTrigger.FOREGROUND_MANUAL
        )

        // Then
        assertEquals(
            "FOREGROUND_MANUAL",
            data.getString(CalDavSyncWorker.KEY_SYNC_TRIGGER)
        )
    }

    @Test
    fun `createFullSyncInput defaults to BACKGROUND_PERIODIC`() {
        // When - no trigger specified
        val data = CalDavSyncWorker.createFullSyncInput()

        // Then
        assertEquals(
            "BACKGROUND_PERIODIC",
            data.getString(CalDavSyncWorker.KEY_SYNC_TRIGGER)
        )
    }

    // ==================== Retry Lifecycle Sequence Tests ====================

    @Test
    fun `doWork abandons operations exceeding 30-day lifetime and shows notification`() = runTest {
        // Given - an operation that has exceeded 30-day lifetime
        val expiredOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_CREATE,
            lifetimeResetAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
        )
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns listOf(expiredOp)
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - operation abandoned and user notified
        coVerify { pendingOperationsDao.abandonOperation(1L, any(), any()) }
        verify { notificationManager.showOperationExpiredNotification(1) }
    }

    @Test
    fun `doWork auto-resets old failed operations on every sync`() = runTest {
        // Given - no expired ops, auto-reset returns 3
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 3
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - auto-reset was called
        coVerify { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) }
    }

    @Test
    fun `doWork force full sync resets all failed with fresh lifetime before expiry check`() = runTest {
        // Given - force sync enabled
        coEvery { pendingOperationsDao.resetAllFailed(any()) } returns 5
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = true)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - A runs before D (reset before expiry check)
        coVerifyOrder {
            pendingOperationsDao.resetAllFailed(any())
            pendingOperationsDao.getExpiredOperations(any())
        }
    }

    @Test
    fun `doWork does not reset all failed on normal sync`() = runTest {
        // Given - normal sync (not force)
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - resetAllFailed not called
        coVerify(exactly = 0) { pendingOperationsDao.resetAllFailed(any()) }
    }

    @Test
    fun `doWork executes retry lifecycle in correct order A then D then B`() = runTest {
        // Given - force sync with operations to process
        coEvery { pendingOperationsDao.resetAllFailed(any()) } returns 1
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 2
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = true)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - Full A→D→B sequence verified
        coVerifyOrder {
            pendingOperationsDao.resetAllFailed(any())           // A: Force reset
            pendingOperationsDao.getExpiredOperations(any())     // D: Expiry check
            pendingOperationsDao.autoResetOldFailed(any(), any(), any()) // B: 24h auto-reset
        }
    }

    // ==================== Helper Functions ====================

    private fun createTestAccount(id: Long = 1L): Account {
        return Account(
            id = id,
            provider = AccountProvider.ICLOUD,
            email = "test@icloud.com",
            displayName = "Test Account",
            principalUrl = "https://caldav.icloud.com/123/principal",
            homeSetUrl = "https://caldav.icloud.com/123/calendars",
            credentialKey = "test_key",
            isEnabled = true,
            lastSyncAt = null,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createTestCalendar(id: Long = 1L, accountId: Long = 1L): Calendar {
        return Calendar(
            id = id,
            accountId = accountId,
            caldavUrl = "https://caldav.icloud.com/123/calendars/home",
            displayName = "Home",
            color = 0xFF0000,
            ctag = "ctag-123",
            syncToken = null,
            isVisible = true,
            isDefault = false,
            isReadOnly = false,
            sortOrder = 0
        )
    }

    private fun createTestEvent(
        id: Long,
        calendarId: Long,
        reminders: List<String>? = null,
        originalEventId: Long? = null
    ): Event {
        val now = System.currentTimeMillis()
        return Event(
            id = id,
            calendarId = calendarId,
            uid = "test-event-$id",
            title = "Test Event",
            description = null,
            location = null,
            startTs = now + 3600_000, // 1 hour from now
            endTs = now + 7200_000,   // 2 hours from now
            isAllDay = false,
            rrule = null,
            rdate = null,
            exdate = null,
            reminders = reminders,
            originalEventId = originalEventId,
            originalInstanceTime = if (originalEventId != null) now + 3600_000 else null,
            syncStatus = org.onekash.kashcal.data.db.entity.SyncStatus.SYNCED,
            dtstamp = now
        )
    }

    private fun createTestOccurrence(eventId: Long, calendarId: Long): Occurrence {
        val now = System.currentTimeMillis()
        return Occurrence(
            id = 1L,
            eventId = eventId,
            calendarId = calendarId,
            startTs = now + 3600_000,
            endTs = now + 7200_000,
            startDay = 20250108,  // Today's date in YYYYMMDD format
            endDay = 20250108,
            isCancelled = false
        )
    }

    private fun createTestSyncChange(type: ChangeType, eventId: Long): SyncChange {
        val now = System.currentTimeMillis()
        return SyncChange(
            type = type,
            eventId = eventId,
            eventTitle = "Test Event",
            eventStartTs = now + 3600_000,
            isAllDay = false,
            isRecurring = false,
            calendarName = "Home",
            calendarColor = 0xFF0000
        )
    }
}
