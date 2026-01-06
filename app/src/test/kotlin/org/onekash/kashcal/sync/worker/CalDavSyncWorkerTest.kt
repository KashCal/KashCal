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
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.engine.CalDavSyncEngine
import org.onekash.kashcal.sync.provider.CalendarProvider
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.scheduler.SyncScheduler
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
    private lateinit var mockICloudProvider: CalendarProvider
    private lateinit var mockCredentialProvider: CredentialProvider
    private lateinit var calDavClient: CalDavClient
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var widgetUpdateManager: org.onekash.kashcal.widget.WidgetUpdateManager
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
        mockICloudProvider = mockk(relaxed = true)
        mockCredentialProvider = mockk(relaxed = true)
        calDavClient = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)

        // Default: return empty input data
        every { workerParams.inputData } returns Data.EMPTY
        every { workerParams.runAttemptCount } returns 0

        // Make createForegroundInfo throw so setForeground is skipped in tests
        // (setForeground doesn't work properly in unit tests without WorkManager test utilities)
        every { notificationManager.createForegroundInfo(any(), any()) } throws
            IllegalStateException("Test: foreground not available")

        // Default: setup iCloud provider mock
        every { mockICloudProvider.requiresNetwork } returns true
        every { mockICloudProvider.getCredentialProvider() } returns mockCredentialProvider
        every { providerRegistry.getProviderForAccount(any()) } returns mockICloudProvider

        // Default: return test credentials
        coEvery { mockCredentialProvider.getCredentials(any()) } returns Credentials(
            username = "test@icloud.com",
            password = "test-password"
        )
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
            calDavClient = calDavClient,
            syncScheduler = syncScheduler,
            widgetUpdateManager = widgetUpdateManager
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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, false) } returns syncResult

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, false) }
    }

    @Test
    fun `full sync with force flag passes to engine`() = runTest {
        // Given
        val inputData = CalDavSyncWorker.createFullSyncInput(forceFullSync = true)
        val worker = createWorker(inputData)
        val testAccount = createTestAccount()

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount)
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, true) } returns
            SyncResult.Success(calendarsSynced = 1, durationMs = 100)

        // When
        worker.doWork()

        // Then
        coVerify { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, true) }
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
        coVerify(exactly = 0) { syncEngine.syncAccountWithProvider(any(), any(), any()) }
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
        coEvery { syncEngine.syncCalendar(calendar, false) } returns SyncResult.Success(
            calendarsSynced = 1,
            durationMs = 500
        )

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify { syncEngine.syncCalendar(calendar, false) }
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
    fun `account sync routes to syncAccount`() = runTest {
        // Given
        val accountId = 7L
        val inputData = CalDavSyncWorker.createAccountSyncInput(accountId)
        val worker = createWorker(inputData)
        val account = createTestAccount(accountId)

        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { syncEngine.syncAccount(account, false) } returns SyncResult.Success(
            calendarsSynced = 3,
            durationMs = 800
        )

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify { syncEngine.syncAccount(account, false) }
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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, any()) } returns syncResult

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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, any()) } returns syncResult

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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, any()) } returns syncResult

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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, any()) } returns syncResult

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
        coEvery { syncEngine.syncCalendar(calendar, false, any(), any()) } returns syncResult

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
        coEvery { syncEngine.syncCalendar(calendar, false, any(), any()) } returns syncResult

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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, any()) } throws
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
        coVerify(exactly = 0) { syncEngine.syncAccountWithProvider(any(), any(), any()) }
        coVerify(exactly = 0) { calDavClient.setCredentials(any(), any()) }
    }

    @Test
    fun `sync loads credentials per account and sets on client`() = runTest {
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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, false) } returns
            SyncResult.Success(calendarsSynced = 1, durationMs = 100)

        // When
        worker.doWork()

        // Then
        verify { calDavClient.setCredentials("user@icloud.com", "app-specific-pwd") }
        coVerify { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, false) }
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
        coEvery { syncEngine.syncAccountWithProvider(testAccount, mockICloudProvider, false) } returns
            SyncResult.Success(calendarsSynced = 1, durationMs = 100)

        // When
        worker.doWork()

        // Then - Credentials set regardless of server URL (server URL used elsewhere)
        verify { calDavClient.setCredentials("user@example.com", "pwd123") }
    }

    // ==================== Helper Functions ====================

    private fun createTestAccount(id: Long = 1L): Account {
        return Account(
            id = id,
            provider = "icloud",
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
}
