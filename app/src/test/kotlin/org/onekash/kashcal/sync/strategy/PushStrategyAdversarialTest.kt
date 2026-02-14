package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Adversarial tests for PushStrategy.
 *
 * Tests server errors (5xx), large queues, and edge cases
 * not covered by the main PushStrategyTest.
 */
class PushStrategyAdversarialTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var pushStrategy: PushStrategy

    private val testCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.example.com/calendars/default/",
        displayName = "Test Calendar",
        color = -1,
        ctag = "ctag-123",
        syncToken = null,
        isVisible = true,
        isDefault = false,
        isReadOnly = false,
        sortOrder = 0
    )

    private fun createTestEvent(id: Long, uid: String = "uid-$id"): Event {
        val now = 1700000000000L
        return Event(
            id = id,
            uid = uid,
            calendarId = 1L,
            title = "Event $id",
            location = null,
            description = null,
            startTs = now,
            endTs = now + 3600_000,
            timezone = "UTC",
            isAllDay = false,
            status = "CONFIRMED",
            organizerEmail = null,
            organizerName = null,
            rrule = null,
            rdate = null,
            exdate = null,
            originalEventId = null,
            originalInstanceTime = null,
            originalSyncId = null,
            reminders = emptyList(),
            dtstamp = now,
            caldavUrl = null,
            etag = null,
            sequence = 0,
            syncStatus = SyncStatus.PENDING_CREATE,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = now,
            serverModifiedAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    @Before
    fun setup() {
        client = mockk()
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()

        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Server 5xx Errors ==========

    @Test
    fun `pushAll handles 500 Internal Server Error as retryable`() = runTest {
        val event = createTestEvent(1L)
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { calendarRepository.getCalendarById(1L) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(500, "Internal Server Error", isRetryable = true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Should schedule retry (500 is retryable)
        coVerify { pendingOperationsDao.scheduleRetry(1L, any(), any(), any()) }
    }

    @Test
    fun `pushAll handles 502 Bad Gateway as retryable`() = runTest {
        val event = createTestEvent(1L)
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { calendarRepository.getCalendarById(1L) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(502, "Bad Gateway", isRetryable = true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify { pendingOperationsDao.scheduleRetry(1L, any(), any(), any()) }
    }

    @Test
    fun `pushAll handles 503 Service Unavailable as retryable`() = runTest {
        val event = createTestEvent(1L)
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { calendarRepository.getCalendarById(1L) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(503, "Service Unavailable", isRetryable = true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify { pendingOperationsDao.scheduleRetry(1L, any(), any(), any()) }
    }

    @Test
    fun `pushAll handles 5xx as non-retryable when flagged`() = runTest {
        val event = createTestEvent(1L)
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { calendarRepository.getCalendarById(1L) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // Server explicitly says not retryable
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(500, "Permanent failure", isRetryable = false)
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        // Should mark failed, not retry
        coVerify { pendingOperationsDao.markFailed(1L, any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
    }

    // ========== Large Queue ==========

    @Test
    fun `pushAll processes large queue of 50 operations`() = runTest {
        val operations = (1L..50L).map { id ->
            PendingOperation(
                id = id,
                eventId = id,
                operation = PendingOperation.OPERATION_CREATE,
                status = PendingOperation.STATUS_PENDING
            )
        }

        val events = (1L..50L).map { id -> createTestEvent(id) }
        val eventsMap = events.associateBy { it.id }

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns operations
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { eventsDao.getByIds(any()) } returns events
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns listOf(testCalendar)

        // Use getById fallback for events not in batch (shouldn't be needed but just in case)
        for (event in events) {
            coEvery { eventsDao.getById(event.id) } returns event
        }
        coEvery { calendarRepository.getCalendarById(1L) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair("url", "etag"))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(50, success.eventsCreated)
        assertEquals(50, success.operationsProcessed)
        assertEquals(0, success.operationsFailed)
    }

    // ========== Mixed Success/Failure in Queue ==========

    @Test
    fun `pushAll continues processing after individual operation failure`() = runTest {
        val op1 = PendingOperation(id = 1L, eventId = 1L, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING, retryCount = 0, maxRetries = 5)
        val op2 = PendingOperation(id = 2L, eventId = 2L, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING)
        val op3 = PendingOperation(id = 3L, eventId = 3L, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING, retryCount = 0, maxRetries = 5)

        val event1 = createTestEvent(1L)
        val event2 = createTestEvent(2L)
        val event3 = createTestEvent(3L)

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(op1, op2, op3)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        coEvery { eventsDao.getById(1L) } returns event1
        coEvery { eventsDao.getById(2L) } returns event2
        coEvery { eventsDao.getById(3L) } returns event3
        coEvery { calendarRepository.getCalendarById(any()) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs

        // Op1: server error, Op2: success, Op3: server error
        coEvery { client.createEvent(eq(testCalendar.caldavUrl), eq("uid-1"), any()) } returns
            CalDavResult.error(500, "Server Error", isRetryable = true)
        coEvery { client.createEvent(eq(testCalendar.caldavUrl), eq("uid-2"), any()) } returns
            CalDavResult.success(Pair("url-2", "etag-2"))
        coEvery { client.createEvent(eq(testCalendar.caldavUrl), eq("uid-3"), any()) } returns
            CalDavResult.error(503, "Service Unavailable", isRetryable = true)

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals("Should create 1 event", 1, success.eventsCreated)
        assertEquals("Should have 2 failures", 2, success.operationsFailed)
        assertEquals("Should process all 3", 3, success.operationsProcessed)
    }

    // ========== DELETE with Server Error ==========

    @Test
    fun `pushAll handles DELETE server error and retries`() = runTest {
        val event = createTestEvent(1L).copy(
            caldavUrl = "https://caldav.example.com/calendars/default/uid-1.ics",
            etag = "etag-123",
            syncStatus = SyncStatus.PENDING_DELETE
        )
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.deleteEvent(any(), any()) } returns
            CalDavResult.error(500, "Internal Server Error", isRetryable = true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
        coVerify { pendingOperationsDao.scheduleRetry(1L, any(), any(), any()) }
    }

    // ========== UPDATE with Server Error ==========

    @Test
    fun `pushAll handles UPDATE 500 error and retries`() = runTest {
        val event = createTestEvent(1L).copy(
            caldavUrl = "https://caldav.example.com/calendars/default/uid-1.ics",
            etag = "etag-old",
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.updateEvent(any(), any(), any()) } returns
            CalDavResult.error(500, "Internal Server Error", isRetryable = true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
        coVerify { pendingOperationsDao.scheduleRetry(1L, any(), any(), any()) }
    }

    // ========== Unknown Operation Type ==========

    @Test
    fun `pushAll handles unknown operation type`() = runTest {
        val event = createTestEvent(1L)
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = "UNKNOWN", // Unknown type
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }

    // ========== Event Not Found ==========

    @Test
    fun `pushAll handles CREATE when event not found in DB`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = 999L, // Doesn't exist
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(999L) } returns null
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Should NOT call any server methods
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
    }

    @Test
    fun `pushAll handles UPDATE when calendar not found`() = runTest {
        val event = createTestEvent(1L).copy(
            calendarId = 999L, // Calendar doesn't exist
            caldavUrl = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 1L,
            eventId = 1L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { calendarRepository.getCalendarById(999L) } returns null
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }
}
