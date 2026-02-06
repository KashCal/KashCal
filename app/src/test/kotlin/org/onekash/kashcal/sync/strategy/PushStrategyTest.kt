package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
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

class PushStrategyTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var pushStrategy: PushStrategy

    private val testCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123/calendar/",
        displayName = "Test Calendar",
        color = -1,
        ctag = "ctag-123",
        syncToken = null,
        isVisible = true,
        isDefault = false,
        isReadOnly = false,
        sortOrder = 0
    )

    private val testEvent = Event(
        id = 100L,
        uid = "test-event-uid-123",
        calendarId = 1L,
        title = "Test Event",
        location = "Test Location",
        description = "Test Description",
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600_000,
        timezone = "America/New_York",
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
        reminders = listOf("-PT15M"),
        dtstamp = System.currentTimeMillis(),
        caldavUrl = null,
        etag = null,
        sequence = 0,
        syncStatus = SyncStatus.PENDING_CREATE,
        lastSyncError = null,
        syncRetryCount = 0,
        localModifiedAt = System.currentTimeMillis(),
        serverModifiedAt = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        client = mockk()
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        // Default batch query mocks - return empty so fallback to getById is used
        // Individual tests can override these for specific scenarios
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

    // ========== No Pending Operations ==========

    @Test
    fun `pushAll returns NoPendingOperations when queue is empty`() = runTest {
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns emptyList()

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.NoPendingOperations)
    }

    // ========== CREATE Operations ==========

    @Test
    fun `pushAll successfully creates event on server`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverUrl = "${testCalendar.caldavUrl}${testEvent.uid}.ics"
        val serverEtag = "etag-new-123"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarRepository.getCalendarById(testEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(eq(testCalendar.caldavUrl), eq(testEvent.uid), any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, serverUrl, serverEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsCreated == 1)
        assert(success.eventsUpdated == 0)
        assert(success.eventsDeleted == 0)
        assert(success.operationsFailed == 0)

        coVerify { eventsDao.markCreatedOnServer(testEvent.id, serverUrl, serverEtag, any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `pushAll handles CREATE conflict (event already exists)`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarRepository.getCalendarById(testEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.conflictError("Event exists")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), any(), any()) }
    }

    // ========== UPDATE Operations ==========

    @Test
    fun `pushAll successfully updates event on server`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event-uid-123.ics",
            etag = "etag-old-123",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventWithUrl.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val newEtag = "etag-new-456"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventWithUrl.id) } returns eventWithUrl
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.updateEvent(eq(eventWithUrl.caldavUrl!!), any(), eq(eventWithUrl.etag!!)) } returns
            CalDavResult.success(newEtag)
        coEvery { eventsDao.markSynced(eventWithUrl.id, newEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0)

        coVerify { eventsDao.markSynced(eventWithUrl.id, newEtag, any()) }
    }

    @Test
    fun `pushAll handles UPDATE conflict (412)`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-old",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventWithUrl.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventWithUrl.id) } returns eventWithUrl
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.updateEvent(any(), any(), any()) } returns CalDavResult.conflictError("Modified on server")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll treats UPDATE without caldavUrl as CREATE`() = runTest {
        // Event has no caldavUrl - should be treated as CREATE
        val eventNoUrl = testEvent.copy(
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventNoUrl.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverUrl = "${testCalendar.caldavUrl}${testEvent.uid}.ics"
        val serverEtag = "etag-new"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventNoUrl.id) } returns eventNoUrl
        coEvery { calendarRepository.getCalendarById(eventNoUrl.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)

        // Should have called createEvent, not updateEvent
        coVerify { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { client.updateEvent(any(), any(), any()) }
    }

    // ========== DELETE Operations ==========

    @Test
    fun `pushAll successfully deletes event from server`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-123",
            syncStatus = SyncStatus.PENDING_DELETE
        )

        val operation = PendingOperation(
            id = 3L,
            eventId = eventWithUrl.id,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventWithUrl.id) } returns eventWithUrl
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eventWithUrl.etag!!) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(eventWithUrl.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsDeleted == 1)

        coVerify { client.deleteEvent(eventWithUrl.caldavUrl!!, eventWithUrl.etag!!) }
        coVerify { eventsDao.deleteById(eventWithUrl.id) }
    }

    @Test
    fun `pushAll handles DELETE for event never synced (no caldavUrl)`() = runTest {
        // Event has no caldavUrl - should just delete locally
        val eventNoUrl = testEvent.copy(caldavUrl = null, syncStatus = SyncStatus.PENDING_DELETE)

        val operation = PendingOperation(
            id = 3L,
            eventId = eventNoUrl.id,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventNoUrl.id) } returns eventNoUrl
        coEvery { eventsDao.deleteById(eventNoUrl.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).eventsDeleted == 1)

        // Should NOT call server delete
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // Should still delete locally
        coVerify { eventsDao.deleteById(eventNoUrl.id) }
    }

    @Test
    fun `pushAll handles DELETE for event already deleted on server (404)`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-123",
            syncStatus = SyncStatus.PENDING_DELETE
        )

        val operation = PendingOperation(
            id = 3L,
            eventId = eventWithUrl.id,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventWithUrl.id) } returns eventWithUrl
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.notFoundError("Already deleted")
        coEvery { eventsDao.deleteById(eventWithUrl.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        // Should succeed - 404 means already deleted
        assert(result is PushResult.Success)
        assert((result as PushResult.Success).eventsDeleted == 1)

        coVerify { eventsDao.deleteById(eventWithUrl.id) }
    }

    @Test
    fun `pushAll handles DELETE when event already deleted locally`() = runTest {
        val operation = PendingOperation(
            id = 3L,
            eventId = 999L, // Event doesn't exist
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(999L) } returns null
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        // Should succeed - nothing to do
        assert(result is PushResult.Success)
        assert((result as PushResult.Success).eventsDeleted == 1)
    }

    // ========== Mixed Operations ==========

    @Test
    fun `pushAll processes multiple operations in order`() = runTest {
        val createOp = PendingOperation(
            id = 1L,
            eventId = 100L,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val updateOp = PendingOperation(
            id = 2L,
            eventId = 101L,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val deleteOp = PendingOperation(
            id = 3L,
            eventId = 102L,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        val eventCreate = testEvent.copy(id = 100L, caldavUrl = null)
        val eventUpdate = testEvent.copy(
            id = 101L,
            caldavUrl = "https://caldav.icloud.com/123/calendar/update.ics",
            etag = "etag"
        )
        val eventDelete = testEvent.copy(
            id = 102L,
            caldavUrl = "https://caldav.icloud.com/123/calendar/delete.ics",
            etag = "etag"
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(createOp, updateOp, deleteOp)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        coEvery { eventsDao.getById(100L) } returns eventCreate
        coEvery { eventsDao.getById(101L) } returns eventUpdate
        coEvery { eventsDao.getById(102L) } returns eventDelete
        coEvery { calendarRepository.getCalendarById(any()) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.success(Pair("url", "etag"))
        coEvery { client.updateEvent(any(), any(), any()) } returns CalDavResult.success("new-etag")
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsCreated == 1)
        assert(success.eventsUpdated == 1)
        assert(success.eventsDeleted == 1)
        assert(success.operationsProcessed == 3)
        assert(success.operationsFailed == 0)
    }

    // ========== Error Handling ==========

    @Test
    fun `pushAll schedules retry for retryable network error`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0,
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarRepository.getCalendarById(testEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.networkError("Connection failed")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), any(), any()) }
        coVerify { eventsDao.recordSyncError(testEvent.id, any(), any()) }
    }

    @Test
    fun `pushAll marks operation failed when max retries exceeded`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 5, // Already at max
            maxRetries = 5
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarRepository.getCalendarById(testEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.networkError("Connection failed")
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)

        // Should mark as failed, not schedule retry
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
    }

    @Test
    fun `pushAll handles auth error (401) as non-retryable`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING,
            retryCount = 0
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarRepository.getCalendarById(testEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
                coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.authError("Invalid credentials")
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        // Should mark as failed immediately (auth error is not retryable)
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
    }

    // ========== Recurring Events ==========

    @Test
    fun `pushAll serializes master event with exceptions`() = runTest {
        val masterEvent = testEvent.copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            originalEventId = null
        )

        val exceptionEvent = testEvent.copy(
            id = 101L,
            originalEventId = masterEvent.id,
            originalInstanceTime = System.currentTimeMillis(),
            rrule = null
        )

        val operation = PendingOperation(
            id = 1L,
            eventId = masterEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEvent.id) } returns masterEvent
        coEvery { calendarRepository.getCalendarById(masterEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(masterEvent.id) } returns listOf(exceptionEvent)
        coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.success(Pair("url", "etag"))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs  // v14.2.20: update exception etags
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // Verify exception etag was updated (v14.2.20)
        coVerify { eventsDao.markSynced(exceptionEvent.id, "etag", any()) }
    }

    @Test
    fun `pushAll skips exception events - they are bundled with master`() = runTest {
        // Exception events (with originalEventId set) should be skipped entirely.
        // They get pushed as part of the master event via serializeWithExceptions().
        val exceptionEvent = testEvent.copy(
            originalEventId = 99L,
            originalInstanceTime = System.currentTimeMillis(),
            rrule = null
        )

        val operation = PendingOperation(
            id = 1L,
            eventId = exceptionEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(exceptionEvent.id) } returns exceptionEvent
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        // Should succeed but NOT call any server methods
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        // Counts as created since operation succeeded (no-op is success)
        assert(success.eventsCreated == 1)

        // Verify NO server calls were made
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { calendarRepository.getCalendarById(any()) }
    }

    @Test
    fun `pushAll skips exception events for UPDATE operations`() = runTest {
        // Exception events should also be skipped for UPDATE operations.
        // The master's UPDATE will include all exceptions via IcsPatcher.serializeWithExceptions().
        val exceptionEvent = testEvent.copy(
            originalEventId = 99L,
            originalInstanceTime = System.currentTimeMillis(),
            caldavUrl = null, // Exception has no caldavUrl (bundled with master)
            etag = null,
            rrule = null
        )

        val operation = PendingOperation(
            id = 1L,
            eventId = exceptionEvent.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(exceptionEvent.id) } returns exceptionEvent
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        // Should succeed (no-op)
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)

        // Verify NO server calls were made
        coVerify(exactly = 0) { client.updateEvent(any(), any(), any()) }
    }

    @Test
    fun `pushAll processes master UPDATE and includes all exceptions`() = runTest {
        // When master event is updated, it should include all its exceptions
        val masterEvent = testEvent.copy(
            id = 200L,
            rrule = "FREQ=DAILY;COUNT=5",
            originalEventId = null,
            caldavUrl = "https://caldav.icloud.com/123/calendar/master.ics",
            etag = "etag-old"
        )

        val exception1 = testEvent.copy(
            id = 201L,
            originalEventId = masterEvent.id,
            originalInstanceTime = System.currentTimeMillis() + 86400_000,
            rrule = null
        )

        val exception2 = testEvent.copy(
            id = 202L,
            originalEventId = masterEvent.id,
            originalInstanceTime = System.currentTimeMillis() + 172800_000,
            rrule = null
        )

        val operation = PendingOperation(
            id = 1L,
            eventId = masterEvent.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEvent.id) } returns masterEvent
        coEvery { eventsDao.getExceptionsForMaster(masterEvent.id) } returns listOf(exception1, exception2)
        coEvery { client.updateEvent(masterEvent.caldavUrl!!, any(), masterEvent.etag!!) } returns CalDavResult.success("new-etag")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs  // v14.2.20: update master and exception etags
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)

        // Verify update was called with correct URL and etag
        coVerify { client.updateEvent(masterEvent.caldavUrl!!, any(), masterEvent.etag!!) }
        // Verify master etag was updated
        coVerify { eventsDao.markSynced(masterEvent.id, "new-etag", any()) }
        // Verify exception etags were updated (v14.2.20)
        coVerify { eventsDao.markSynced(exception1.id, "new-etag", any()) }
        coVerify { eventsDao.markSynced(exception2.id, "new-etag", any()) }
    }

    // ========== Batch Query Optimization (v16.5.5) ==========

    @Test
    fun `pushForCalendar uses batch query instead of N+1`() = runTest {
        // Given: Multiple pending operations for different calendars
        val calendar1 = testCalendar.copy(id = 1L)
        val calendar2 = testCalendar.copy(id = 2L)

        val event1 = testEvent.copy(id = 1L, calendarId = 1L, caldavUrl = null)
        val event2 = testEvent.copy(id = 2L, calendarId = 1L, caldavUrl = null)
        val event3 = testEvent.copy(id = 3L, calendarId = 2L, caldavUrl = null)  // Different calendar

        val op1 = PendingOperation(id = 1L, eventId = 1L, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING)
        val op2 = PendingOperation(id = 2L, eventId = 2L, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING)
        val op3 = PendingOperation(id = 3L, eventId = 3L, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING)

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(op1, op2, op3)
        // Batch query should be called with all event IDs
        coEvery { eventsDao.getByIds(listOf(1L, 2L, 3L)) } returns listOf(event1, event2, event3)

        // pushForCalendar should only process events for calendar1
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar1
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.success(Pair("url", "etag"))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs

        // When
        val result = pushStrategy.pushForCalendar(calendar1, client)

        // Then: getByIds called once (batch), getById NEVER called
        coVerify(exactly = 1) { eventsDao.getByIds(any()) }
        coVerify(exactly = 0) { eventsDao.getById(any()) }

        // Should only have processed 2 events (for calendar1)
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsCreated == 2)
        assert(success.operationsProcessed == 2)
    }
}
