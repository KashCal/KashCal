package org.onekash.kashcal.sync.engine

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.sync.session.ErrorType
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.ConflictResult
import org.onekash.kashcal.sync.strategy.ConflictStrategy
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy

class CalDavSyncEngineTest {

    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy
    private lateinit var conflictResolver: ConflictResolver
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var syncLogsDao: SyncLogsDao
    private lateinit var syncSessionStore: SyncSessionStore
    private lateinit var syncEngine: CalDavSyncEngine

    private val testAccount = Account(
        id = 1L,
        provider = "icloud",
        email = "test@icloud.com",
        displayName = "Test Account",
        principalUrl = "https://caldav.icloud.com/123/",
        homeSetUrl = "https://caldav.icloud.com/123/calendars/",
        credentialKey = "key-123",
        isEnabled = true,
        lastSyncAt = null,
        createdAt = System.currentTimeMillis()
    )

    private val testCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123/calendar/",
        displayName = "Test Calendar",
        color = -1,
        ctag = "ctag-123",
        syncToken = "sync-token-123",
        isVisible = true,
        isDefault = false,
        isReadOnly = false,
        sortOrder = 0
    )

    private val readOnlyCalendar = testCalendar.copy(
        id = 2L,
        displayName = "Read-Only Calendar",
        isReadOnly = true
    )

    @Before
    fun setup() {
        pullStrategy = mockk()
        pushStrategy = mockk()
        conflictResolver = mockk()
        accountsDao = mockk()
        calendarsDao = mockk()
        pendingOperationsDao = mockk()
        syncLogsDao = mockk()
        syncSessionStore = mockk(relaxed = true)

        syncEngine = CalDavSyncEngine(
            pullStrategy = pullStrategy,
            pushStrategy = pushStrategy,
            conflictResolver = conflictResolver,
            accountsDao = accountsDao,
            calendarsDao = calendarsDao,
            pendingOperationsDao = pendingOperationsDao,
            syncLogsDao = syncLogsDao,
            syncSessionStore = syncSessionStore,
            notificationManager = mockk(relaxed = true)
        )

        // Default mock for logging
        coEvery { syncLogsDao.insert(any()) } returns 1L
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Single Calendar Sync Tests ==========

    @Test
    fun `syncCalendar pushes then pulls successfully`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Success(
            eventsCreated = 1,
            eventsUpdated = 2,
            eventsDeleted = 0,
            operationsProcessed = 3,
            operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 2,
            eventsUpdated = 1,
            eventsDeleted = 0,
            newSyncToken = "new-token",
            newCtag = "new-ctag"
        )

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.eventsPushedCreated == 1)
        assert(success.eventsPushedUpdated == 2)
        assert(success.eventsPulledAdded == 2)
        assert(success.eventsPulledUpdated == 1)
        assert(success.calendarsSynced == 1)

        // Verify push happens before pull
        coVerifyOrder {
            pushStrategy.pushForCalendar(testCalendar)
            pullStrategy.pull(testCalendar, false, any(), any(), any())
        }
    }

    @Test
    fun `syncCalendar handles no pending operations`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.totalChanges == 0)
    }

    @Test
    fun `syncCalendar handles push conflicts`() = runTest {
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            lastError = "Conflict: server has newer version"
        )

        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Success(
            eventsCreated = 0,
            eventsUpdated = 0,
            eventsDeleted = 0,
            operationsProcessed = 1,
            operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS) } returns ConflictResult.ServerVersionKept
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.conflictsResolved == 1)

        coVerify { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS) }
    }

    @Test
    fun `syncCalendar returns auth error on 401`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Error(
            code = 401,
            message = "Unauthorized",
            isRetryable = false
        )

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.AuthError)
        val authError = result as SyncResult.AuthError
        assert(authError.calendarId == testCalendar.id)
        assert(authError.message == "Unauthorized")

        // Pull should not be called after auth error
        coVerify(exactly = 0) { pullStrategy.pull(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `syncCalendar returns partial success with errors`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Success(
            eventsCreated = 1,
            eventsUpdated = 0,
            eventsDeleted = 0,
            operationsProcessed = 1,
            operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = 500,
            message = "Server error",
            isRetryable = true
        )

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.PartialSuccess)
        val partial = result as SyncResult.PartialSuccess
        assert(partial.eventsPushedCreated == 1)
        assert(partial.errors.size == 1)
        assert(partial.errors[0].phase == SyncPhase.PULL)
    }

    @Test
    fun `syncCalendar with forceFullSync passes to pull`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, true, any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 10,
            eventsUpdated = 0,
            eventsDeleted = 0,
            newSyncToken = "token",
            newCtag = null
        )

        syncEngine.syncCalendar(testCalendar, forceFullSync = true)

        coVerify { pullStrategy.pull(testCalendar, true, any(), any(), any()) }
    }

    @Test
    fun `syncCalendar uses provided conflict strategy`() = runTest {
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            lastError = "Conflict"
        )

        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Success(
            eventsCreated = 0,
            eventsUpdated = 0,
            eventsDeleted = 0,
            operationsProcessed = 1,
            operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.NEWEST_WINS) } returns ConflictResult.LocalVersionPushed
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        syncEngine.syncCalendar(testCalendar, conflictStrategy = ConflictStrategy.NEWEST_WINS)

        coVerify { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.NEWEST_WINS) }
    }

    // ========== Account Sync Tests ==========

    @Test
    fun `syncAccount syncs all calendars`() = runTest {
        val calendar2 = testCalendar.copy(id = 2L, displayName = "Calendar 2")

        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns listOf(testCalendar, calendar2)
        coEvery { pushStrategy.pushForCalendar(any()) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(any(), any(), any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 1,
            eventsUpdated = 0,
            eventsDeleted = 0,
            newSyncToken = null,
            newCtag = null
        )

        val result = syncEngine.syncAccount(testAccount)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.calendarsSynced == 2)
        assert(success.eventsPulledAdded == 2) // 1 per calendar
    }

    @Test
    fun `syncAccount handles read-only calendars (pull only)`() = runTest {
        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns listOf(readOnlyCalendar)
        coEvery { pullStrategy.pull(readOnlyCalendar, false, any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 5,
            eventsUpdated = 0,
            eventsDeleted = 0,
            newSyncToken = null,
            newCtag = null
        )

        val result = syncEngine.syncAccount(testAccount)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.eventsPulledAdded == 5)

        // Push should not be called for read-only calendar
        coVerify(exactly = 0) { pushStrategy.pushForCalendar(any()) }
    }

    @Test
    fun `syncAccount returns empty success when no calendars`() = runTest {
        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns emptyList()

        val result = syncEngine.syncAccount(testAccount)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.calendarsSynced == 0)
    }

    @Test
    fun `syncAccount stops on auth error`() = runTest {
        val calendar2 = testCalendar.copy(id = 2L, displayName = "Calendar 2")

        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns listOf(testCalendar, calendar2)
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Error(
            code = 401,
            message = "Unauthorized",
            isRetryable = false
        )

        val result = syncEngine.syncAccount(testAccount)

        assert(result is SyncResult.AuthError)

        // Second calendar should not be synced
        coVerify(exactly = 1) { pushStrategy.pushForCalendar(any()) }
    }

    @Test
    fun `syncAccount aggregates errors from multiple calendars`() = runTest {
        val calendar2 = testCalendar.copy(id = 2L, displayName = "Calendar 2")

        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns listOf(testCalendar, calendar2)
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Success(
            eventsCreated = 1, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = 500, message = "Server error", isRetryable = true
        )
        coEvery { pushStrategy.pushForCalendar(calendar2) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 1, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 0
        )
        coEvery { pullStrategy.pull(calendar2, false, any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 1, eventsUpdated = 0, eventsDeleted = 0,
            newSyncToken = null, newCtag = null
        )

        val result = syncEngine.syncAccount(testAccount)

        assert(result is SyncResult.PartialSuccess)
        val partial = result as SyncResult.PartialSuccess
        assert(partial.calendarsSynced == 2) // Both attempted
        assert(partial.errors.size == 1) // One error from first calendar
    }

    // ========== Sync All Tests ==========

    @Test
    fun `syncAll syncs all enabled accounts`() = runTest {
        val account2 = testAccount.copy(id = 2L, email = "test2@icloud.com")
        val calendar2 = testCalendar.copy(id = 2L, accountId = 2L)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount, account2)
        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns listOf(testCalendar)
        coEvery { calendarsDao.getByAccountIdOnce(account2.id) } returns listOf(calendar2)
        coEvery { pushStrategy.pushForCalendar(any()) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(any(), any(), any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 1, eventsUpdated = 0, eventsDeleted = 0,
            newSyncToken = null, newCtag = null
        )

        val result = syncEngine.syncAll()

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.calendarsSynced == 2)
        assert(success.eventsPulledAdded == 2)
    }

    @Test
    fun `syncAll returns success when no accounts`() = runTest {
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()

        val result = syncEngine.syncAll()

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.calendarsSynced == 0)
    }

    @Test
    fun `syncAll continues despite auth error on one account`() = runTest {
        val account2 = testAccount.copy(id = 2L, email = "test2@icloud.com")
        val calendar2 = testCalendar.copy(id = 2L, accountId = 2L)

        coEvery { accountsDao.getEnabledAccounts() } returns listOf(testAccount, account2)
        coEvery { calendarsDao.getByAccountIdOnce(testAccount.id) } returns listOf(testCalendar)
        coEvery { calendarsDao.getByAccountIdOnce(account2.id) } returns listOf(calendar2)

        // First account has auth error
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Error(
            code = 401, message = "Auth failed", isRetryable = false
        )

        // Second account succeeds
        coEvery { pushStrategy.pushForCalendar(calendar2) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(calendar2, false, any(), any(), any()) } returns PullResult.Success(
            eventsAdded = 1, eventsUpdated = 0, eventsDeleted = 0,
            newSyncToken = null, newCtag = null
        )

        val result = syncEngine.syncAll()

        // Should be partial success - one account failed
        assert(result is SyncResult.PartialSuccess)
        val partial = result as SyncResult.PartialSuccess
        assert(partial.calendarsSynced == 1) // Only second account
        assert(partial.errors.any { it.phase == SyncPhase.AUTH })
    }

    // ========== Duration Tracking Tests ==========

    @Test
    fun `syncCalendar tracks duration`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.durationMs >= 0)
    }

    // ========== Logging Tests ==========

    @Test
    fun `syncCalendar logs sync completion`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        syncEngine.syncCalendar(testCalendar)

        coVerify { syncLogsDao.insert(match { it.action == "SYNC_COMPLETE" }) }
    }

    // ========== Exception Handling Tests ==========

    @Test
    fun `syncCalendar handles unexpected exceptions`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } throws RuntimeException("Unexpected error")

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result is SyncResult.Error)
        val error = result as SyncResult.Error
        assert(error.message.contains("Unexpected"))
        assert(error.isRetryable)
    }

    // ========== Total Changes Tests ==========

    @Test
    fun `hasChanges returns true when events were modified`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.Success(
            eventsCreated = 1, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar)

        assert(result.hasChanges())
    }

    @Test
    fun `hasChanges returns false when no changes`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar)

        assert(!result.hasChanges())
    }

    // ========== SyncResult Tests ==========

    @Test
    fun `SyncResult Success calculates totalChanges correctly`() {
        val result = SyncResult.Success(
            calendarsSynced = 1,
            eventsPushedCreated = 1,
            eventsPushedUpdated = 2,
            eventsPushedDeleted = 1,
            eventsPulledAdded = 3,
            eventsPulledUpdated = 1,
            eventsPulledDeleted = 2,
            conflictsResolved = 1,
            durationMs = 100
        )

        assert(result.totalChanges == 10) // 1+2+1+3+1+2
        assert(result.isSuccess())
    }

    @Test
    fun `SyncResult PartialSuccess calculates totalChanges correctly`() {
        val result = SyncResult.PartialSuccess(
            calendarsSynced = 1,
            eventsPushedCreated = 2,
            eventsPushedUpdated = 0,
            eventsPushedDeleted = 0,
            eventsPulledAdded = 3,
            eventsPulledUpdated = 0,
            eventsPulledDeleted = 0,
            conflictsResolved = 0,
            errors = listOf(SyncError(SyncPhase.PULL, message = "Error")),
            durationMs = 100
        )

        assert(result.totalChanges == 5) // 2+3
        assert(result.isPartialSuccess())
    }

    @Test
    fun `SyncResult Error and AuthError report correctly`() {
        val error = SyncResult.Error(500, "Server error", true)
        val authError = SyncResult.AuthError("Auth failed", 1L)

        assert(!error.isSuccess())
        assert(!error.hasChanges())
        assert(!authError.isSuccess())
        assert(!authError.hasChanges())
    }

    // ========== Error Type Mapping Tests (v16.8.1) ==========

    @Test
    fun `syncCalendar maps code -408 to TIMEOUT ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = -408,
            message = "Socket timeout",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.TIMEOUT
            })
        }
    }

    @Test
    fun `syncCalendar maps code -1 to PARSE ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = -1,
            message = "Processing error",
            isRetryable = false
        )

        syncEngine.syncCalendar(testCalendar)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.PARSE
            })
        }
    }

    @Test
    fun `syncCalendar maps code 0 to NETWORK ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = 0,
            message = "Connection failed",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.NETWORK
            })
        }
    }

    @Test
    fun `syncCalendar maps code 401 to AUTH ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = 401,
            message = "Unauthorized",
            isRetryable = false
        )

        syncEngine.syncCalendar(testCalendar)

        // Auth error causes early return with session stored
        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.AUTH
            })
        }
    }

    @Test
    fun `syncCalendar maps code 500 to SERVER ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = 500,
            message = "Internal server error",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.SERVER
            })
        }
    }

    @Test
    fun `syncCalendar maps HTTP 408 to TIMEOUT ErrorType`() = runTest {
        // HTTP 408 Request Timeout should also map to TIMEOUT
        coEvery { pushStrategy.pushForCalendar(testCalendar) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), any(), any()) } returns PullResult.Error(
            code = 408,
            message = "Request timeout",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.TIMEOUT
            })
        }
    }
}
