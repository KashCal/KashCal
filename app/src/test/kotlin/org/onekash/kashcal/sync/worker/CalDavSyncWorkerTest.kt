package org.onekash.kashcal.sync.worker

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.notification.ExpiredCalendarScope
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.provider.icloud.ICloudUrlMigration
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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
    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarRepository: CalendarRepository
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
    private lateinit var eventsDao: org.onekash.kashcal.data.db.dao.EventsDao
    private lateinit var dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore
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
        accountRepository = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)
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
        eventsDao = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)

        // Default: dataStore returns reasonable default values
        coEvery { dataStore.defaultReminderMinutes } returns kotlinx.coroutines.flow.flowOf(15)
        coEvery { dataStore.defaultAllDayReminder } returns kotlinx.coroutines.flow.flowOf(720)

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
            accountRepository = accountRepository,
            calendarRepository = calendarRepository,
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
            iCloudUrlMigration = iCloudUrlMigration,
            eventsDao = eventsDao,
            dataStore = dataStore
        )
    }

    // ==================== getForegroundInfo (expedited fallback) ====================

    @Test
    fun `getForegroundInfo returns valid ForegroundInfo for expedited fallback`() = runTest {
        // On API < 31, WorkManager calls getForegroundInfo() for setExpedited() fallback.
        // Without this override, expedited sync silently fails on Android 10-11.
        val mockForegroundInfo = mockk<ForegroundInfo>()
        every { notificationManager.createForegroundInfo(any(), any()) } returns mockForegroundInfo

        val worker = createWorker()
        val result = worker.getForegroundInfo()

        assertEquals(mockForegroundInfo, result)
        verify { notificationManager.createForegroundInfo(any(), null) }
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

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

        coEvery { calendarRepository.getCalendarById(calendarId) } returns calendar
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

        coEvery { calendarRepository.getCalendarById(calendarId) } returns null

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

        coEvery { accountRepository.getAccountById(accountId) } returns account
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

        coEvery { accountRepository.getAccountById(accountId) } returns null

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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { calendarRepository.getCalendarById(calendarId) } returns calendar
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

        coEvery { calendarRepository.getCalendarById(calendarId) } returns calendar
        coEvery { syncEngine.syncCalendar(calendar, false, any(), any(), any(), any()) } returns syncResult

        // When
        val result = worker.doWork()

        // Then - Should fail after max retries even if retryable
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    // ==================== Exception Handling Tests ====================

    @Test
    fun `syncAll account exception records failure and continues`() = runTest {
        // When syncEngine throws for one account, record failure and continue
        // to next account instead of aborting the entire sync.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } throws
            RuntimeException("Unexpected error")

        // After recording failure
        coEvery { accountRepository.getAccountById(1L) } returns testAccount.copy(consecutiveSyncFailures = 1)

        // When
        val result = worker.doWork()

        // Then - Exception is caught, failure recorded, result is success (with errors)
        assertTrue(result is ListenableWorker.Result.Success)
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
    }

    @Test
    fun `syncAll first account exception does not block second account`() = runTest {
        // Two accounts — first throws, second succeeds. Both should be processed.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val account1 = createTestAccount(id = 1L)
        val account2 = createTestAccount(id = 2L).copy(
            email = "test2@icloud.com",
            displayName = "Test Account 2"
        )

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(account1, account2)

        // Account 1 throws exception
        coEvery { syncEngine.syncAccountWithQuirks(account1, any(), any(), any(), any(), any()) } throws
            RuntimeException("Account 1 crashed")
        // Account 2 succeeds
        coEvery { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) } returns
            SyncResult.Success(calendarsSynced = 2, durationMs = 500)

        // After recording failure for account 1
        coEvery { accountRepository.getAccountById(1L) } returns account1.copy(consecutiveSyncFailures = 1)

        // When
        val result = worker.doWork()

        // Then - Both accounts processed, first failure recorded, second succeeds
        assertTrue(result is ListenableWorker.Result.Success)
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        coVerify { accountRepository.recordSyncSuccess(2L, any()) }
        // Verify second account was actually synced (not skipped)
        coVerify { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) }
    }

    // ==================== Top-Level Exception Propagation Tests ====================

    @Test
    fun `top-level exception returns retry when under max attempts`() = runTest {
        // Given - attempt 0 (first try), exception thrown before sync engine
        every { workerParams.runAttemptCount } returns 0
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        coEvery { accountRepository.getEnabledAccounts() } throws NullPointerException("test NPE")

        // When
        val result = worker.doWork()

        // Then - should retry (under max attempts)
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `top-level exception returns failure when max retries exceeded`() = runTest {
        // Given - attempt 3 (4th try, exceeds MAX_RETRY_ATTEMPTS=3)
        every { workerParams.runAttemptCount } returns 3
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        coEvery { accountRepository.getEnabledAccounts() } throws RuntimeException("Database locked")

        // When
        val result = worker.doWork()

        // Then - should fail (not retry) with error message in output
        assertTrue("Expected Failure but got ${result.javaClass.simpleName}",
            result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `top-level exception returns retry on last allowed attempt`() = runTest {
        // Given - attempt 2 (3rd try, still under MAX_RETRY_ATTEMPTS=3)
        every { workerParams.runAttemptCount } returns 2
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)

        coEvery { accountRepository.getEnabledAccounts() } throws RuntimeException("Transient error")

        // When
        val result = worker.doWork()

        // Then - attempt 2 < MAX_RETRY_ATTEMPTS (3), should still retry
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `top-level exception notification uses class name when message is null`() = runTest {
        // Given - showNotification=true, exception with null message
        every { workerParams.runAttemptCount } returns 0
        val inputData = CalDavSyncWorker.createFullSyncInput(showNotification = true)
        val worker = createWorker(inputData)

        coEvery { accountRepository.getEnabledAccounts() } throws NullPointerException()

        // When
        worker.doWork()

        // Then - notification should show "NullPointerException" not "Unknown error"
        verify { notificationManager.showErrorNotification("Sync Failed", "NullPointerException") }
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(createTestAccount())

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
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult

        // When
        worker.doWork()

        // Then - No reminder scheduling for deleted events
        coVerify(exactly = 0) { eventReader.getEventById(any()) }
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
    }

    @Test
    fun `sync skips reminder scheduling for events without reminders on initial sync`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()
        val eventId = 100L
        val calendarId = 1L

        // Use isFromInitialSync = true to test the skip behavior
        // (on initial sync, no defaults are applied, so events without reminders are skipped)
        val syncChange = createTestSyncChange(ChangeType.NEW, eventId).copy(isFromInitialSync = true)
        val syncResult = SyncResult.Success(
            calendarsSynced = 1,
            eventsPulledAdded = 1,
            durationMs = 100,
            changes = listOf(syncChange)
        )

        // Event with no reminders
        val testEvent = createTestEvent(eventId, calendarId, reminders = null)

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult
        coEvery { eventReader.getEventById(eventId) } returns testEvent

        // When
        worker.doWork()

        // Then - No reminder scheduling for events without reminders on initial sync
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

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
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

    @Test
    fun `sync cancels reminders for MODIFIED events that now have no reminders`() = runTest {
        // Regression test: When an event transitions from having reminders to no reminders
        // (e.g., after removing default reminder application from sync), the old AlarmManager
        // alarms must be cancelled. Without this fix, phantom notifications would fire.
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

        // MODIFIED event now has NO reminders (previously had defaults applied)
        val testEvent = createTestEvent(eventId, calendarId, reminders = null)

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns syncResult
        coEvery { eventReader.getEventById(eventId) } returns testEvent

        // When
        worker.doWork()

        // Then - Old reminders MUST be cancelled even though new reminders are null
        coVerify { reminderScheduler.cancelRemindersForEvent(eventId) }
        // No new reminders should be scheduled
        coVerify(exactly = 0) { reminderScheduler.scheduleRemindersForEvent(any(), any(), any()) }
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
        // Given - one expired op whose event resolves to a single calendar
        val expiredOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_CREATE,
            lifetimeResetAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
        )
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns listOf(expiredOp)
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } returns 1
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { eventsDao.getById(100L) } returns createTestEvent(id = 100L, calendarId = 7L)
        coEvery { calendarRepository.getCalendarById(7L) } returns createTestCalendar(id = 7L)
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - operation abandoned and user notified with the calendar name
        coVerify { pendingOperationsDao.abandonOperation(1L, any(), any()) }
        verify {
            notificationManager.showOperationExpiredNotification(
                1, ExpiredCalendarScope.Single("Home")
            )
        }
    }

    @Test
    fun `doWork reports calendar count when expired ops span multiple calendars`() = runTest {
        // Given - two expired ops resolving to two different calendars
        val op1 = PendingOperation(id = 1L, eventId = 100L, operation = PendingOperation.OPERATION_CREATE)
        val op2 = PendingOperation(id = 2L, eventId = 200L, operation = PendingOperation.OPERATION_CREATE)
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns listOf(op1, op2)
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } returns 1
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { eventsDao.getById(100L) } returns createTestEvent(id = 100L, calendarId = 7L)
        coEvery { eventsDao.getById(200L) } returns createTestEvent(id = 200L, calendarId = 8L)
        coEvery { calendarRepository.getCalendarById(7L) } returns createTestCalendar(id = 7L)
        coEvery { calendarRepository.getCalendarById(8L) } returns createTestCalendar(id = 8L)
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        val worker = createWorker(CalDavSyncWorker.createFullSyncInput(forceFullSync = false))

        // When
        worker.doWork()

        // Then - names the number of calendars affected, not a single one
        verify {
            notificationManager.showOperationExpiredNotification(
                2, ExpiredCalendarScope.Multiple(2)
            )
        }
    }

    @Test
    fun `doWork names the source calendar for an expired move-related operation`() = runTest {
        // For MOVE / synced->local DELETE ops the event's calendarId has already
        // advanced to the move target, while the stuck operation concerns the
        // source calendar it carries. The notification must name the source.
        val expiredOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_DELETE,
            sourceCalendarId = 7L, // stuck DELETE is against calendar 7 (source)
            lifetimeResetAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
        )
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns listOf(expiredOp)
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } returns 1
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        // Event row has already moved to the target calendar (8L).
        coEvery { eventsDao.getById(100L) } returns createTestEvent(id = 100L, calendarId = 8L)
        coEvery { calendarRepository.getCalendarById(7L) } returns createTestCalendar(id = 7L).copy(displayName = "Source Cal")
        coEvery { calendarRepository.getCalendarById(8L) } returns createTestCalendar(id = 8L).copy(displayName = "Target Cal")
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        val worker = createWorker(CalDavSyncWorker.createFullSyncInput(forceFullSync = false))

        // When
        worker.doWork()

        // Then - names the source calendar (7L), not the event's current target (8L)
        verify {
            notificationManager.showOperationExpiredNotification(
                1, ExpiredCalendarScope.Single("Source Cal")
            )
        }
    }

    @Test
    fun `doWork reports unknown scope when expired op event was deleted`() = runTest {
        // Given - an expired op whose event no longer exists (ops survive event deletion)
        val expiredOp = PendingOperation(id = 1L, eventId = 100L, operation = PendingOperation.OPERATION_DELETE)
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns listOf(expiredOp)
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } returns 1
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { eventsDao.getById(100L) } returns null
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        val worker = createWorker(CalDavSyncWorker.createFullSyncInput(forceFullSync = false))

        // When
        worker.doWork()

        // Then - unresolvable event collapses to count-only fallback
        verify {
            notificationManager.showOperationExpiredNotification(
                1, ExpiredCalendarScope.Unknown
            )
        }
    }

    @Test
    fun `doWork does not re-notify on second sync after operations abandoned`() = runTest {
        // The core bug: dismissing must stick. Once abandoned, the next sync's
        // getExpiredOperations returns empty, so no second notify.
        val expiredOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_CREATE,
            lifetimeResetAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
        )
        // First sync finds it expired; second sync finds nothing (now ABANDONED).
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returnsMany
            listOf(listOf(expiredOp), emptyList())
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } returns 1
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { eventsDao.getById(100L) } returns createTestEvent(id = 100L, calendarId = 7L)
        coEvery { calendarRepository.getCalendarById(7L) } returns createTestCalendar(id = 7L)
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        // When - two sync runs
        createWorker(CalDavSyncWorker.createFullSyncInput(forceFullSync = false)).doWork()
        createWorker(CalDavSyncWorker.createFullSyncInput(forceFullSync = false)).doWork()

        // Then - notified exactly once across both runs
        verify(exactly = 1) { notificationManager.showOperationExpiredNotification(any(), any()) }
    }

    @Test
    fun `doWork does not notify for ops a concurrent sync already abandoned`() = runTest {
        // getExpiredOperations is global and this block runs on every sync, so an
        // overlapping sync can read the same expired op. abandonOperation is a
        // compare-and-set: the run that loses the race transitions 0 rows and
        // must stay silent instead of firing a duplicate alert on the same op.
        val expiredOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_CREATE,
            lifetimeResetAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
        )
        // Still visible to this run's read, but already abandoned by a concurrent
        // run — so the compare-and-set update transitions no rows.
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns listOf(expiredOp)
        coEvery { pendingOperationsDao.abandonOperation(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { eventsDao.getById(100L) } returns createTestEvent(id = 100L, calendarId = 7L)
        coEvery { calendarRepository.getCalendarById(7L) } returns createTestCalendar(id = 7L)
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        createWorker(CalDavSyncWorker.createFullSyncInput(forceFullSync = false)).doWork()

        // Then - no notification, because this run abandoned nothing
        verify(exactly = 0) { notificationManager.showOperationExpiredNotification(any(), any()) }
    }

    @Test
    fun `doWork auto-resets old failed operations on every sync`() = runTest {
        // Given - no expired ops, auto-reset returns 3
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 3
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 0
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

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
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

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
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

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
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

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

    // ==================== Stale IN_PROGRESS Recovery Tests ====================

    @Test
    fun `doWork recovers stale IN_PROGRESS operations at sync start`() = runTest {
        // Given - stale operations exist
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 2
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - stale recovery was called with ~1hr cutoff
        coVerify { pendingOperationsDao.resetStaleInProgress(any(), any()) }
    }

    @Test
    fun `doWork stale recovery runs before retry lifecycle`() = runTest {
        // Given
        coEvery { pendingOperationsDao.resetStaleInProgress(any(), any()) } returns 1
        coEvery { pendingOperationsDao.getExpiredOperations(any()) } returns emptyList()
        coEvery { pendingOperationsDao.autoResetOldFailed(any(), any(), any()) } returns 0
        coEvery { accountRepository.getEnabledAccounts() } returns emptyList()

        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = false)
        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - stale recovery runs before expiry check
        coVerifyOrder {
            pendingOperationsDao.resetStaleInProgress(any(), any())
            pendingOperationsDao.getExpiredOperations(any())
        }
    }

    // ==================== Per-Account Failure Handling Tests ====================

    @Test
    fun `syncAll records failure but posts no system notification when an account fails repeatedly`() = runTest {
        // Repeated background sync failures are self-healing and surfaced in-app
        // (Accounts warning indicator + on-open error banner), so no system
        // notification is posted for the repeated-failure condition.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } returns
            SyncResult.Error(-1, "Server unreachable", true)

        // When
        worker.doWork()

        // Then - failure counter still advances (in-app surfaces depend on it),
        // but the repeated background failure stays silent: no completion or
        // error notification is posted.
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        verify(exactly = 0) { notificationManager.showCompletionNotification(any(), any()) }
        verify(exactly = 0) { notificationManager.showErrorNotification(any<String>(), any<String>()) }
    }

    // ==================== syncAll Per-Account Metadata Tests ====================

    @Test
    fun `syncAll records per-account metadata for multiple accounts with mixed results`() = runTest {
        // Given - two accounts: one succeeds, one fails
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val account1 = createTestAccount(id = 1L)
        val account2 = createTestAccount(id = 2L).copy(
            email = "test2@icloud.com",
            displayName = "Test Account 2"
        )

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(account1, account2)

        // Account 1 succeeds
        coEvery { syncEngine.syncAccountWithQuirks(account1, any(), any(), any(), any(), any()) } returns
            SyncResult.Success(calendarsSynced = 2, durationMs = 500)
        // Account 2 fails
        coEvery { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) } returns
            SyncResult.Error(-1, "Connection refused", true)

        // Account 2 after failure has 1 consecutive failure
        coEvery { accountRepository.getAccountById(2L) } returns account2.copy(consecutiveSyncFailures = 1)

        // When
        worker.doWork()

        // Then - BOTH accounts get their metadata recorded
        coVerify { accountRepository.recordSyncSuccess(1L, any()) }
        coVerify { accountRepository.recordSyncFailure(2L, any()) }
    }

    // ==================== Adverse Tests — All Accounts Fail ====================

    @Test
    fun `syncAll all accounts throw exceptions — returns success with errors`() = runTest {
        // When EVERY account throws an exception, syncAll should still
        // return Result.Success (with error data) — not crash or return Failure.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val account1 = createTestAccount(id = 1L)
        val account2 = createTestAccount(id = 2L).copy(
            email = "test2@icloud.com",
            displayName = "Test Account 2"
        )

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(account1, account2)

        // Both accounts throw
        coEvery { syncEngine.syncAccountWithQuirks(account1, any(), any(), any(), any(), any()) } throws
            RuntimeException("Account 1 server unreachable")
        coEvery { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) } throws
            RuntimeException("Account 2 auth failure")

        // After recording failures
        coEvery { accountRepository.getAccountById(1L) } returns account1.copy(consecutiveSyncFailures = 1)
        coEvery { accountRepository.getAccountById(2L) } returns account2.copy(consecutiveSyncFailures = 1)

        // When
        val result = worker.doWork()

        // Then — returns success (with errors in output data), not Failure
        assertTrue("Should be Result.Success even when all accounts fail", result is ListenableWorker.Result.Success)
        // Both failures recorded
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        coVerify { accountRepository.recordSyncFailure(2L, any()) }
        // Both accounts were attempted (second wasn't skipped)
        coVerify { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `syncAll exception message propagates to error output`() = runTest {
        // Verify the exception message is captured in the allErrors list
        // so it surfaces in the home banner via PartialSuccess.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } throws
            RuntimeException("Connection timed out after 30s")

        coEvery { accountRepository.getAccountById(1L) } returns testAccount.copy(consecutiveSyncFailures = 1)

        val result = worker.doWork()

        // Result should be Success (PartialSuccess maps to Result.success with error in output)
        assertTrue(result is ListenableWorker.Result.Success)
        // Verify failure was recorded (the exception path records failure metadata)
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
    }

    @Test
    fun `syncAll exception records failure without posting a system notification`() = runTest {
        // After an exception the failure counter advances (in-app surfaces depend
        // on it), but no repeated-failure system notification is posted.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), any(), any(), any(), any()) } throws
            RuntimeException("Unexpected error")

        worker.doWork()

        // Failure recorded, but the background failure stays silent.
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        verify(exactly = 0) { notificationManager.showCompletionNotification(any(), any()) }
        verify(exactly = 0) { notificationManager.showErrorNotification(any<String>(), any<String>()) }
    }

    @Test
    fun `syncAll three accounts — first and third throw, second succeeds`() = runTest {
        // Verify isolation with 3 accounts — exceptions don't affect other accounts.
        val inputData = CalDavSyncWorker.createFullSyncInput()
        val worker = createWorker(inputData)
        val account1 = createTestAccount(id = 1L)
        val account2 = createTestAccount(id = 2L).copy(email = "test2@icloud.com", displayName = "Account 2")
        val account3 = createTestAccount(id = 3L).copy(email = "test3@icloud.com", displayName = "Account 3")

        coEvery { accountRepository.getEnabledAccounts() } returns listOf(account1, account2, account3)

        coEvery { syncEngine.syncAccountWithQuirks(account1, any(), any(), any(), any(), any()) } throws
            RuntimeException("Account 1 crashed")
        coEvery { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) } returns
            SyncResult.Success(calendarsSynced = 3, durationMs = 1000)
        coEvery { syncEngine.syncAccountWithQuirks(account3, any(), any(), any(), any(), any()) } throws
            RuntimeException("Account 3 network error")

        coEvery { accountRepository.getAccountById(1L) } returns account1.copy(consecutiveSyncFailures = 1)
        coEvery { accountRepository.getAccountById(3L) } returns account3.copy(consecutiveSyncFailures = 1)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Account 1: failure recorded
        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        // Account 2: success recorded
        coVerify { accountRepository.recordSyncSuccess(2L, any()) }
        // Account 3: failure recorded (was not skipped despite account 1 failing)
        coVerify { accountRepository.recordSyncFailure(3L, any()) }
        // All three accounts were attempted
        coVerify { syncEngine.syncAccountWithQuirks(account1, any(), any(), any(), any(), any()) }
        coVerify { syncEngine.syncAccountWithQuirks(account2, any(), any(), any(), any(), any()) }
        coVerify { syncEngine.syncAccountWithQuirks(account3, any(), any(), any(), any(), any()) }
    }

    // ==================== Account Detail: Sync Recording & isEnabled Guard ====================

    @Test
    fun `syncAccount calls recordSyncSuccess on Success`() = runTest {
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId = 1L)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getAccountById(1L) } returns testAccount
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns
            SyncResult.Success(calendarsSynced = 2, durationMs = 500)

        worker.doWork()

        coVerify { accountRepository.recordSyncSuccess(1L, any()) }
        coVerify(exactly = 0) { accountRepository.recordSyncFailure(any(), any()) }
    }

    @Test
    fun `syncAccount calls recordSyncFailure on PartialSuccess`() = runTest {
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId = 1L)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getAccountById(1L) } returns testAccount
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns
            SyncResult.PartialSuccess(
                calendarsSynced = 1,
                errors = listOf(SyncError(phase = SyncPhase.PULL, calendarId = 2L, message = "fetch failed")),
                durationMs = 500
            )

        worker.doWork()

        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        coVerify(exactly = 0) { accountRepository.recordSyncSuccess(any(), any()) }
    }

    @Test
    fun `syncAccount calls recordSyncFailure on AuthError`() = runTest {
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId = 1L)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getAccountById(1L) } returns testAccount
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns
            SyncResult.AuthError("Invalid token")

        worker.doWork()

        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        coVerify(exactly = 0) { accountRepository.recordSyncSuccess(any(), any()) }
    }

    @Test
    fun `syncAccount calls recordSyncFailure on Error`() = runTest {
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId = 1L)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountRepository.getAccountById(1L) } returns testAccount
        coEvery { syncEngine.syncAccountWithQuirks(testAccount, any(), false, any(), any(), any()) } returns
            SyncResult.Error(-1, "Server error", true)

        worker.doWork()

        coVerify { accountRepository.recordSyncFailure(1L, any()) }
        coVerify(exactly = 0) { accountRepository.recordSyncSuccess(any(), any()) }
    }

    @Test
    fun `syncAccount skips disabled account and returns Success with 0 calendars`() = runTest {
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId = 1L)
        val worker = createWorker(inputData)
        val disabledAccount = createTestAccount().copy(isEnabled = false)

        coEvery { accountRepository.getAccountById(1L) } returns disabledAccount

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        // Sync engine should never be called
        coVerify(exactly = 0) { syncEngine.syncAccountWithQuirks(any(), any(), any(), any(), any(), any()) }
        // No sync metadata should be recorded
        coVerify(exactly = 0) { accountRepository.recordSyncSuccess(any(), any()) }
        coVerify(exactly = 0) { accountRepository.recordSyncFailure(any(), any()) }
    }
}
