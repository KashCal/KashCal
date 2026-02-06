package org.onekash.kashcal.sync.engine

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.notification.SyncNotificationManager
import org.onekash.kashcal.sync.session.ErrorType
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.ConflictResult
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.strategy.ConflictStrategy
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy

class CalDavSyncEngineTest {

    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy
    private lateinit var conflictResolver: ConflictResolver
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var syncLogsDao: SyncLogsDao
    private lateinit var syncSessionStore: SyncSessionStore
    private lateinit var notificationManager: SyncNotificationManager
    private lateinit var client: CalDavClient
    private lateinit var syncEngine: CalDavSyncEngine

    private val testAccount = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
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
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()
        syncLogsDao = mockk()
        syncSessionStore = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        client = mockk()

        syncEngine = CalDavSyncEngine(
            pullStrategy = pullStrategy,
            pushStrategy = pushStrategy,
            conflictResolver = conflictResolver,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            syncLogsDao = syncLogsDao,
            syncSessionStore = syncSessionStore,
            notificationManager = notificationManager
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
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 1,
            eventsUpdated = 2,
            eventsDeleted = 0,
            operationsProcessed = 3,
            operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Success(
            eventsAdded = 2,
            eventsUpdated = 1,
            eventsDeleted = 0,
            newSyncToken = "new-token",
            newCtag = "new-ctag"
        )

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.eventsPushedCreated == 1)
        assert(success.eventsPushedUpdated == 2)
        assert(success.eventsPulledAdded == 2)
        assert(success.eventsPulledUpdated == 1)
        assert(success.calendarsSynced == 1)

        // Verify push happens before pull
        coVerifyOrder {
            pushStrategy.pushForCalendar(testCalendar, client)
            pullStrategy.pull(testCalendar, false, any(), client, any())
        }
    }

    @Test
    fun `syncCalendar handles no pending operations`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

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

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0,
            eventsUpdated = 0,
            eventsDeleted = 0,
            operationsProcessed = 1,
            operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.ServerVersionKept
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.conflictsResolved == 1)

        coVerify { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) }
    }

    @Test
    fun `syncCalendar returns auth error on 401`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Error(
            code = 401,
            message = "Unauthorized",
            isRetryable = false
        )

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.AuthError)
        val authError = result as SyncResult.AuthError
        assert(authError.calendarId == testCalendar.id)
        assert(authError.message == "Unauthorized")

        // Pull should not be called after auth error
        coVerify(exactly = 0) { pullStrategy.pull(any(), any(), any(), client, any()) }
    }

    @Test
    fun `syncCalendar returns partial success with errors`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 1,
            eventsUpdated = 0,
            eventsDeleted = 0,
            operationsProcessed = 1,
            operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = 500,
            message = "Server error",
            isRetryable = true
        )

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.PartialSuccess)
        val partial = result as SyncResult.PartialSuccess
        assert(partial.eventsPushedCreated == 1)
        assert(partial.errors.size == 1)
        assert(partial.errors[0].phase == SyncPhase.PULL)
    }

    @Test
    fun `syncCalendar with forceFullSync passes to pull`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, true, any(), client, any()) } returns PullResult.Success(
            eventsAdded = 10,
            eventsUpdated = 0,
            eventsDeleted = 0,
            newSyncToken = "token",
            newCtag = null
        )

        syncEngine.syncCalendar(testCalendar, forceFullSync = true, client = client)

        coVerify { pullStrategy.pull(testCalendar, true, any(), client, any()) }
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

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0,
            eventsUpdated = 0,
            eventsDeleted = 0,
            operationsProcessed = 1,
            operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.NEWEST_WINS, client = client) } returns ConflictResult.LocalVersionPushed
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        syncEngine.syncCalendar(testCalendar, conflictStrategy = ConflictStrategy.NEWEST_WINS, client = client)

        coVerify { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.NEWEST_WINS, client = client) }
    }

    // ========== Account Sync Tests ==========

    @Test
    fun `syncAccount syncs all calendars`() = runTest {
        val calendar2 = testCalendar.copy(id = 2L, displayName = "Calendar 2")

        coEvery { calendarRepository.getCalendarsForAccountOnce(testAccount.id) } returns listOf(testCalendar, calendar2)
        coEvery { pushStrategy.pushForCalendar(any(), client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(any(), any(), any(), client, any()) } returns PullResult.Success(
            eventsAdded = 1,
            eventsUpdated = 0,
            eventsDeleted = 0,
            newSyncToken = null,
            newCtag = null
        )

        val result = syncEngine.syncAccount(testAccount, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.calendarsSynced == 2)
        assert(success.eventsPulledAdded == 2) // 1 per calendar
    }

    @Test
    fun `syncAccount handles read-only calendars (pull only)`() = runTest {
        coEvery { calendarRepository.getCalendarsForAccountOnce(testAccount.id) } returns listOf(readOnlyCalendar)
        coEvery { pullStrategy.pull(readOnlyCalendar, false, any(), client, any()) } returns PullResult.Success(
            eventsAdded = 5,
            eventsUpdated = 0,
            eventsDeleted = 0,
            newSyncToken = null,
            newCtag = null
        )

        val result = syncEngine.syncAccount(testAccount, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.eventsPulledAdded == 5)

        // Push should not be called for read-only calendar
        coVerify(exactly = 0) { pushStrategy.pushForCalendar(any(), client) }
    }

    @Test
    fun `syncAccount returns empty success when no calendars`() = runTest {
        coEvery { calendarRepository.getCalendarsForAccountOnce(testAccount.id) } returns emptyList()

        val result = syncEngine.syncAccount(testAccount, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.calendarsSynced == 0)
    }

    @Test
    fun `syncAccount stops on auth error`() = runTest {
        val calendar2 = testCalendar.copy(id = 2L, displayName = "Calendar 2")

        coEvery { calendarRepository.getCalendarsForAccountOnce(testAccount.id) } returns listOf(testCalendar, calendar2)
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Error(
            code = 401,
            message = "Unauthorized",
            isRetryable = false
        )

        val result = syncEngine.syncAccount(testAccount, client = client)

        assert(result is SyncResult.AuthError)

        // Second calendar should not be synced
        coVerify(exactly = 1) { pushStrategy.pushForCalendar(any(), client) }
    }

    @Test
    fun `syncAccount aggregates errors from multiple calendars`() = runTest {
        val calendar2 = testCalendar.copy(id = 2L, displayName = "Calendar 2")

        coEvery { calendarRepository.getCalendarsForAccountOnce(testAccount.id) } returns listOf(testCalendar, calendar2)
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 1, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = 500, message = "Server error", isRetryable = true
        )
        coEvery { pushStrategy.pushForCalendar(calendar2, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 1, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 0
        )
        coEvery { pullStrategy.pull(calendar2, false, any(), client, any()) } returns PullResult.Success(
            eventsAdded = 1, eventsUpdated = 0, eventsDeleted = 0,
            newSyncToken = null, newCtag = null
        )

        val result = syncEngine.syncAccount(testAccount, client = client)

        assert(result is SyncResult.PartialSuccess)
        val partial = result as SyncResult.PartialSuccess
        assert(partial.calendarsSynced == 2) // Both attempted
        assert(partial.errors.size == 1) // One error from first calendar
    }

    // ========== Duration Tracking Tests ==========

    @Test
    fun `syncCalendar tracks duration`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.durationMs >= 0)
    }

    // ========== Logging Tests ==========

    @Test
    fun `syncCalendar logs sync completion`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        syncEngine.syncCalendar(testCalendar, client = client)

        coVerify { syncLogsDao.insert(match { it.action == "SYNC_COMPLETE" }) }
    }

    // ========== Exception Handling Tests ==========

    @Test
    fun `syncCalendar handles unexpected exceptions`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } throws RuntimeException("Unexpected error")

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Error)
        val error = result as SyncResult.Error
        assert(error.message.contains("Unexpected"))
        assert(error.isRetryable)
    }

    // ========== Total Changes Tests ==========

    @Test
    fun `hasChanges returns true when events were modified`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 1, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 0
        )
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result.hasChanges())
    }

    @Test
    fun `hasChanges returns false when no changes`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

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
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = -408,
            message = "Socket timeout",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.TIMEOUT
            })
        }
    }

    @Test
    fun `syncCalendar maps code -1 to PARSE ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = -1,
            message = "Processing error",
            isRetryable = false
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.PARSE
            })
        }
    }

    @Test
    fun `syncCalendar maps code 0 to NETWORK ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = 0,
            message = "Connection failed",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.NETWORK
            })
        }
    }

    @Test
    fun `syncCalendar maps code 401 to AUTH ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = 401,
            message = "Unauthorized",
            isRetryable = false
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        // Auth error causes early return with session stored
        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.AUTH
            })
        }
    }

    @Test
    fun `syncCalendar maps code 500 to SERVER ErrorType`() = runTest {
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = 500,
            message = "Internal server error",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.SERVER
            })
        }
    }

    @Test
    fun `syncCalendar maps HTTP 408 to TIMEOUT ErrorType`() = runTest {
        // HTTP 408 Request Timeout should also map to TIMEOUT
        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.NoPendingOperations
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.Error(
            code = 408,
            message = "Request timeout",
            isRetryable = true
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        coVerify {
            syncSessionStore.add(match { session ->
                session.errorType == ErrorType.TIMEOUT
            })
        }
    }

    // ========== Conflict Abandonment Tests (v20.12.41) ==========

    private fun createTestEvent(id: Long = 100L, title: String = "Test Event") = Event(
        id = id,
        calendarId = 1L,
        uid = "uid-$id",
        title = title,
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        isAllDay = false,
        syncStatus = SyncStatus.PENDING_UPDATE,
        dtstamp = System.currentTimeMillis()
    )

    @Test
    fun `conflict resolved on first try does not abandon`() = runTest {
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            lastError = "Conflict: server has newer version"
        )

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.ServerVersionKept
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.conflictsResolved == 1)

        // Should NOT abandon - resolved successfully
        coVerify(exactly = 0) { eventsDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.deleteById(any()) }
        coVerify(exactly = 0) { notificationManager.showConflictAbandonedNotification(any(), any()) }
    }

    @Test
    fun `conflict fails with retryCount 2 continues retrying`() = runTest {
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 2,  // Less than MAX_CONFLICT_SYNC_CYCLES (3)
            lastError = "Conflict: server has newer version"
        )

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.Error("Network error")
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        // Should be partial success with error recorded
        assert(result is SyncResult.PartialSuccess)
        val partial = result as SyncResult.PartialSuccess
        assert(partial.errors.any { it.phase == SyncPhase.CONFLICT_RESOLUTION })
        assert(partial.errors[0].message.contains("cycle 2/3"))

        // Should NOT abandon yet
        coVerify(exactly = 0) { eventsDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.deleteById(any()) }
        coVerify(exactly = 0) { notificationManager.showConflictAbandonedNotification(any(), any()) }
    }

    @Test
    fun `conflict fails with retryCount 3 abandons operation`() = runTest {
        val testEvent = createTestEvent(id = 100L, title = "My Meeting")
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 3,  // Equal to MAX_CONFLICT_SYNC_CYCLES
            lastError = "Conflict: server has newer version"
        )
        val refreshedCalendar = testCalendar.copy(ctag = null)

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.Error("Network error")
        coEvery { eventsDao.getById(100L) } returns testEvent
        coEvery { eventsDao.updateSyncStatus(100L, SyncStatus.SYNCED, any()) } just Runs
        coEvery { calendarRepository.updateCtag(1L, null) } just Runs
        coEvery { calendarRepository.getCalendarById(1L) } returns refreshedCalendar
        coEvery { pendingOperationsDao.deleteById(1L) } just Runs
        coEvery { pullStrategy.pull(refreshedCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        // Should be success - conflict was abandoned (counted as resolved)
        assert(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assert(success.conflictsResolved == 1)

        // Verify abandonment
        coVerify { eventsDao.updateSyncStatus(100L, SyncStatus.SYNCED, any()) }
        coVerify { calendarRepository.updateCtag(1L, null) }
        coVerify { pendingOperationsDao.deleteById(1L) }
        coVerify { notificationManager.showConflictAbandonedNotification("My Meeting", 1) }
    }

    @Test
    fun `abandoned conflict with deleted event still deletes operation`() = runTest {
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 5,  // Greater than MAX_CONFLICT_SYNC_CYCLES
            lastError = "Conflict: server has newer version"
        )

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.Error("Network error")
        coEvery { eventsDao.getById(100L) } returns null  // Event was deleted
        coEvery { calendarRepository.getCalendarById(1L) } returns testCalendar  // No ctag cleared (event null)
        coEvery { pendingOperationsDao.deleteById(1L) } just Runs
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Success)

        // Should NOT call updateSyncStatus (event doesn't exist)
        coVerify(exactly = 0) { eventsDao.updateSyncStatus(any(), any(), any()) }
        // Should NOT call updateCtag (event doesn't exist)
        coVerify(exactly = 0) { calendarRepository.updateCtag(any(), any()) }
        // Should still delete the operation
        coVerify { pendingOperationsDao.deleteById(1L) }
        // Should still show notification (with null title)
        coVerify { notificationManager.showConflictAbandonedNotification(null, 1) }
    }

    @Test
    fun `notification shows count without title for multiple abandoned conflicts`() = runTest {
        val testEvent1 = createTestEvent(id = 100L, title = "Meeting 1")
        val testEvent2 = createTestEvent(id = 101L, title = "Meeting 2")
        val conflictOp1 = PendingOperation(
            id = 1L, eventId = 100L, operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING, retryCount = 3,
            lastError = "Conflict"
        )
        val conflictOp2 = PendingOperation(
            id = 2L, eventId = 101L, operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING, retryCount = 4,
            lastError = "Conflict"
        )
        val refreshedCalendar = testCalendar.copy(ctag = null)

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 2, operationsFailed = 2
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp1, conflictOp2)
        coEvery { conflictResolver.resolve(any(), strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.Error("Network error")
        coEvery { eventsDao.getById(100L) } returns testEvent1
        coEvery { eventsDao.getById(101L) } returns testEvent2
        coEvery { eventsDao.updateSyncStatus(any(), SyncStatus.SYNCED, any()) } just Runs
        coEvery { calendarRepository.updateCtag(1L, null) } just Runs
        coEvery { calendarRepository.getCalendarById(1L) } returns refreshedCalendar
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pullStrategy.pull(refreshedCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        syncEngine.syncCalendar(testCalendar, client = client)

        // Should show notification with null title (multiple events) and count = 2
        coVerify { notificationManager.showConflictAbandonedNotification(null, 2) }
    }

    @Test
    fun `PENDING_CREATE conflict abandoned after max cycles`() = runTest {
        val testEvent = createTestEvent(id = 100L, title = "New Event").copy(
            syncStatus = SyncStatus.PENDING_CREATE
        )
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_CREATE,  // CREATE operation
            status = PendingOperation.STATUS_PENDING,
            retryCount = 3,
            lastError = "412: UID already exists"
        )
        val refreshedCalendar = testCalendar.copy(ctag = null)

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.Error("Parse error")
        coEvery { eventsDao.getById(100L) } returns testEvent
        coEvery { eventsDao.updateSyncStatus(100L, SyncStatus.SYNCED, any()) } just Runs
        coEvery { calendarRepository.updateCtag(1L, null) } just Runs
        coEvery { calendarRepository.getCalendarById(1L) } returns refreshedCalendar
        coEvery { pendingOperationsDao.deleteById(1L) } just Runs
        coEvery { pullStrategy.pull(refreshedCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        val result = syncEngine.syncCalendar(testCalendar, client = client)

        assert(result is SyncResult.Success)

        // Should abandon CREATE operations the same as UPDATE
        coVerify { eventsDao.updateSyncStatus(100L, SyncStatus.SYNCED, any()) }
        coVerify { calendarRepository.updateCtag(1L, null) }
        coVerify { pendingOperationsDao.deleteById(1L) }
        coVerify { notificationManager.showConflictAbandonedNotification("New Event", 1) }
    }

    @Test
    fun `conflict abandoned re-reads calendar to get cleared ctag`() = runTest {
        val testEvent = createTestEvent(id = 100L, title = "My Meeting").copy(calendarId = 1L)
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 3,
            lastError = "Conflict"
        )

        // Calendar with cleared ctag (simulates DB state after abandonment)
        val refreshedCalendar = testCalendar.copy(ctag = null)

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.Error("Network error")
        coEvery { eventsDao.getById(100L) } returns testEvent
        coEvery { eventsDao.updateSyncStatus(100L, SyncStatus.SYNCED, any()) } just Runs
        coEvery { calendarRepository.updateCtag(1L, null) } just Runs
        coEvery { calendarRepository.getCalendarById(1L) } returns refreshedCalendar  // Returns calendar with ctag=null
        coEvery { pendingOperationsDao.deleteById(1L) } just Runs
        coEvery { pullStrategy.pull(refreshedCalendar, false, any(), client, any()) } returns PullResult.Success(
            eventsAdded = 0, eventsUpdated = 1, eventsDeleted = 0,
            newSyncToken = "new-token", newCtag = "new-ctag"
        )

        syncEngine.syncCalendar(testCalendar, client = client)

        // Verify ctag cleared in DB
        coVerify { calendarRepository.updateCtag(1L, null) }
        // Verify calendar re-read from DB
        coVerify { calendarRepository.getCalendarById(1L) }
        // Verify pull called with refreshed calendar (ctag=null), not original
        coVerify { pullStrategy.pull(refreshedCalendar, false, any(), client, any()) }
    }

    @Test
    fun `no conflict abandonment does not re-read calendar`() = runTest {
        val conflictOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 1,  // Below threshold - won't abandon
            lastError = "Conflict"
        )

        coEvery { pushStrategy.pushForCalendar(testCalendar, client) } returns PushResult.Success(
            eventsCreated = 0, eventsUpdated = 0, eventsDeleted = 0,
            operationsProcessed = 1, operationsFailed = 1
        )
        coEvery { pendingOperationsDao.getConflictOperations() } returns listOf(conflictOp)
        coEvery { conflictResolver.resolve(conflictOp, strategy = ConflictStrategy.SERVER_WINS, client = client) } returns ConflictResult.ServerVersionKept
        coEvery { pullStrategy.pull(testCalendar, false, any(), client, any()) } returns PullResult.NoChanges

        syncEngine.syncCalendar(testCalendar, client = client)

        // Should NOT re-read calendar (no abandonment occurred)
        coVerify(exactly = 0) { calendarRepository.getCalendarById(any()) }
        // Should use original calendar
        coVerify { pullStrategy.pull(testCalendar, false, any(), client, any()) }
    }
}
