package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.serializer.ICalSerializer

/**
 * Tests for MOVE operation in PushStrategy.
 *
 * MOVE operation is two-step:
 * 1. DELETE from old calendar (using targetUrl from operation, NOT event.caldavUrl)
 * 2. CREATE in new calendar (using targetCalendarId from operation)
 *
 * Critical pattern (CLAUDE.md Pattern #6):
 * - Self-contained sync operations: targetUrl must be read from PendingOperation,
 *   not from Event (which is already cleared by EventWriter.moveEventToCalendar)
 */
class PushStrategyMoveOperationTest {

    private lateinit var client: CalDavClient
    private lateinit var serializer: ICalSerializer
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var pushStrategy: PushStrategy

    private val sourceCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123/personal/",
        displayName = "Personal",
        color = -1
    )

    private val targetCalendar = Calendar(
        id = 2L,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123/work/",
        displayName = "Work",
        color = -1
    )

    private val testEvent = Event(
        id = 100L,
        uid = "move-test-uid-123",
        calendarId = 2L, // Already moved to target calendar
        title = "Moved Event",
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600_000,
        dtstamp = System.currentTimeMillis(),
        // CRITICAL: caldavUrl is null after EventWriter.moveEventToCalendar clears it
        caldavUrl = null,
        etag = null,
        syncStatus = SyncStatus.PENDING_UPDATE
    )

    @Before
    fun setup() {
        client = mockk()
        serializer = mockk()
        calendarsDao = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        // Default batch query mocks - return empty so fallback to getById is used
        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarsDao.getByIds(any()) } returns emptyList()

        pushStrategy = PushStrategy(
            client = client,
            serializer = serializer,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== MOVE Operation Tests ====================

    @Test
    fun `processMove uses targetUrl from operation not event caldavUrl`() = runTest {
        // CRITICAL: This tests Pattern #6 from CLAUDE.md
        // The event's caldavUrl is NULL because EventWriter.moveEventToCalendar cleared it
        // But the operation has the old URL stored at queue time
        // NOTE: This test uses CREATE phase since we're testing full completion

        val oldUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics"
        val newUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"
        val newEtag = "\"new-etag\""

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl, // Stored at queue time BEFORE caldavUrl was cleared
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE // Phase 1: CREATE (DELETE already done)
        )

        val icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent // Event has caldavUrl = null
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { serializer.serialize(any()) } returns icalData
        // CREATE phase - DELETE was already done in phase 0
        coEvery { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, icalData) } returns
            CalDavResult.success(Pair(newUrl, newEtag))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, newUrl, newEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        // MOVE counts as both CREATE and DELETE
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // CREATE phase skips DELETE (already done in phase 0)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // Verify CREATE was called with new calendar URL
        coVerify { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, icalData) }
    }

    @Test
    fun `processMove succeeds when DELETE returns 404 (already deleted)`() = runTest {
        // Tests DELETE phase where event is already deleted (404) - should still advance to CREATE
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE // Testing DELETE phase
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // DELETE returns 404 - event already deleted from old calendar
        coEvery { client.deleteEvent(oldUrl, any()) } returns CalDavResult.notFoundError("Not found")
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll()

        // Should still succeed (404 is OK for DELETE phase, advances to CREATE)
        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(0, success.operationsFailed)
        assertEquals(1, success.eventsDeleted) // DELETE phase counted

        // Should advance to CREATE phase (404 is treated as success)
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
    }

    @Test
    fun `processMove fails when DELETE returns server error`() = runTest {
        // Tests DELETE phase failing - should schedule retry for DELETE phase
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE // Testing DELETE phase
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // DELETE returns 500 server error
        coEvery { client.deleteEvent(oldUrl, any()) } returns
            CalDavResult.Error(500, "Server error", true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.operationsFailed)

        // Should schedule retry for DELETE phase
        coVerify { pendingOperationsDao.scheduleRetry(moveOperation.id, any(), any(), any()) }
        // Should NOT advance to CREATE phase
        coVerify(exactly = 0) { pendingOperationsDao.advanceToCreatePhase(any(), any()) }
    }

    @Test
    fun `processMove fails when CREATE conflicts (UID exists in target)`() = runTest {
        // Tests CREATE phase conflict - should schedule retry

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE // Testing CREATE phase
        )

        val icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { serializer.serialize(any()) } returns icalData
        // CREATE conflicts - UID already exists in target calendar
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("UID exists")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Should NOT call DELETE (CREATE phase)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
    }

    @Test
    fun `processMove with null targetUrl skips DELETE step`() = runTest {
        // Event was moved from local calendar (no old URL to delete)
        // Test CREATE phase completion when there's no old URL
        val newUrl = "https://caldav.icloud.com/123/work/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = null, // No old URL (was local)
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE // Already in CREATE phase
        )

        val icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { serializer.serialize(any()) } returns icalData
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair(newUrl, "\"etag\""))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)

        // DELETE should NOT be called (CREATE phase)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // CREATE should be called
        coVerify { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, icalData) }
    }

    @Test
    fun `processMove fails when event not found`() = runTest {
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = 999L, // Non-existent event
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(999L) } returns null
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }

    @Test
    fun `processMove fails when target calendar not found`() = runTest {
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = 999L, // Non-existent calendar
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(999L) } returns null
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }

    @Test
    fun `processMove fails when targetCalendarId is null`() = runTest {
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = null, // Missing required field
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }

    // ==================== MOVE with Recurring Events ====================

    @Test
    fun `processMove includes exceptions when moving recurring event`() = runTest {
        // Test CREATE phase with recurring event - should include exceptions
        val newUrl = "https://caldav.icloud.com/123/work/recurring.ics"

        val recurringEvent = testEvent.copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            originalEventId = null // Is master, not exception
        )

        val exceptionStartTs = System.currentTimeMillis() + 86400000
        val exception = Event(
            id = 101L,
            uid = recurringEvent.uid, // Same UID as master
            calendarId = recurringEvent.calendarId,
            title = "Exception",
            startTs = exceptionStartTs,
            endTs = exceptionStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = recurringEvent.id,
            originalInstanceTime = exceptionStartTs // Original occurrence time being modified
        )

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = recurringEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE // Testing CREATE phase
        )

        val icalDataWithExceptions = "BEGIN:VCALENDAR\nMASTER+EXCEPTION\nEND:VCALENDAR"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(recurringEvent.id) } returns recurringEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // Should fetch exceptions for master
        coEvery { eventsDao.getExceptionsForMaster(recurringEvent.id) } returns listOf(exception)
        // Should serialize with exceptions
        coEvery { serializer.serializeWithExceptions(recurringEvent, listOf(exception)) } returns icalDataWithExceptions
        coEvery { client.createEvent(targetCalendar.caldavUrl, recurringEvent.uid, icalDataWithExceptions) } returns
            CalDavResult.success(Pair(newUrl, "\"etag\""))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)

        // Verify exceptions were included in serialization
        coVerify { eventsDao.getExceptionsForMaster(recurringEvent.id) }
        coVerify { serializer.serializeWithExceptions(recurringEvent, listOf(exception)) }
        // Should NOT call DELETE (CREATE phase)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
    }

    // ==================== MOVE Phase-Aware Tests (C3 Fix) ====================

    @Test
    fun `processMove advances to CREATE phase after DELETE succeeds`() = runTest {
        // C3 fix: DELETE phase succeeds → advance to CREATE phase with fresh retry budget
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE // Phase 0: DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { client.deleteEvent(oldUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll()

        // Should count DELETE as done
        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsDeleted)

        // Should NOT call CREATE (happens in next sync cycle)
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }

        // Should advance to CREATE phase
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }

        // Should NOT delete the operation (still pending CREATE phase)
        coVerify(exactly = 0) { pendingOperationsDao.deleteById(moveOperation.id) }
    }

    @Test
    fun `processMove CREATE phase runs when movePhase is 1`() = runTest {
        // C3 fix: CREATE phase runs independently with fresh retry budget
        val newUrl = "https://caldav.icloud.com/123/work/event.ics"
        val newEtag = "\"new-etag\""
        val icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE, // Phase 1: CREATE
            retryCount = 0 // Fresh retry budget after phase advance
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { serializer.serialize(any()) } returns icalData
        coEvery { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, icalData) } returns
            CalDavResult.success(Pair(newUrl, newEtag))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, newUrl, newEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted) // MOVE counts as both

        // Should NOT call DELETE (already done in phase 0)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }

        // Should call CREATE
        coVerify { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, icalData) }

        // Should delete operation after success
        coVerify { pendingOperationsDao.deleteById(moveOperation.id) }
    }

    @Test
    fun `processMove CREATE phase retries independently of DELETE failures`() = runTest {
        // C3 fix: CREATE phase has its own 5 retry budget
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE,
            retryCount = 2 // 2 retries in CREATE phase, still has 3 more
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { serializer.serialize(any()) } returns "BEGIN:VCALENDAR\nEND:VCALENDAR"
        // CREATE fails with retryable error
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.Error(500, "Server error", true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Should schedule retry (retryCount=2 < maxRetries=5)
        coVerify { pendingOperationsDao.scheduleRetry(moveOperation.id, any(), any(), any()) }

        // Should NOT mark as failed
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
    }

    @Test
    fun `processMove DELETE phase does not skip when targetUrl is null`() = runTest {
        // When targetUrl is null in DELETE phase, should advance to CREATE phase
        val newUrl = "https://caldav.icloud.com/123/work/event.ics"
        val icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = null, // No old URL (was local-only event)
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll()

        assertTrue(result is PushResult.Success)

        // Should NOT call DELETE (no targetUrl)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }

        // Should still advance to CREATE phase
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
    }

    // ==================== MOVE Operation FIFO Order ====================

    @Test
    fun `multiple MOVE operations processed in FIFO order`() = runTest {
        // Test that MOVE operations in CREATE phase are processed in FIFO order
        val operations = listOf(
            PendingOperation(
                id = 1L,
                eventId = 100L,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = "https://caldav.icloud.com/old1.ics",
                targetCalendarId = targetCalendar.id,
                createdAt = 1000L,
                movePhase = PendingOperation.MOVE_PHASE_CREATE
            ),
            PendingOperation(
                id = 2L,
                eventId = 101L,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = "https://caldav.icloud.com/old2.ics",
                targetCalendarId = targetCalendar.id,
                createdAt = 2000L,
                movePhase = PendingOperation.MOVE_PHASE_CREATE
            ),
            PendingOperation(
                id = 3L,
                eventId = 102L,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = "https://caldav.icloud.com/old3.ics",
                targetCalendarId = targetCalendar.id,
                createdAt = 3000L,
                movePhase = PendingOperation.MOVE_PHASE_CREATE
            )
        )

        val processOrder = mutableListOf<Long>()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns operations
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } answers {
            processOrder.add(firstArg())
        }
        coEvery { eventsDao.getById(any()) } returns testEvent
        coEvery { calendarsDao.getById(any()) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { serializer.serialize(any()) } returns "BEGIN:VCALENDAR\nEND:VCALENDAR"
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.success(Unit)
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair("https://new.ics", "\"etag\""))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll()

        // Verify FIFO order
        assertEquals(listOf(1L, 2L, 3L), processOrder)
    }
}
