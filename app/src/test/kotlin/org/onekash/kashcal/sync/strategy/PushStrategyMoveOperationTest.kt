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

/**
 * Tests for MOVE operation in PushStrategy.
 *
 * MOVE operation flow:
 * 1. Phase 0: Try WebDAV MOVE (atomic). If fails → advance to Phase 1
 * 2. Phase 1: CREATE in target calendar first (safety), then DELETE from source
 *
 * Safety principle: CREATE before DELETE ensures no data loss if CREATE fails.
 *
 * Critical pattern (CLAUDE.md Pattern #6):
 * - Self-contained sync operations: targetUrl must be read from PendingOperation,
 *   not from Event (which is already cleared by EventWriter.moveEventToCalendar)
 */
class PushStrategyMoveOperationTest {

    private lateinit var client: CalDavClient
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
        calendarsDao = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        // Default batch query mocks - return empty so fallback to getById is used
        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarsDao.getByIds(any()) } returns emptyList()

        pushStrategy = PushStrategy(
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== MOVE Phase 0: WebDAV MOVE Tests ====================

    @Test
    fun `processMove Phase 0 succeeds with atomic WebDAV MOVE`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics"
        val newUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"
        val newEtag = "\"new-etag\""

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE // Phase 0: try MOVE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // WebDAV MOVE succeeds
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.success(Pair(newUrl, newEtag))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, newUrl, newEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // Verify atomic MOVE was called
        coVerify { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) }
        // No separate CREATE or DELETE calls
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // Operation completed - deleted
        coVerify { pendingOperationsDao.deleteById(moveOperation.id) }
    }

    @Test
    fun `processMove Phase 0 advances to CREATE when MOVE returns 404 (source not found)`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // MOVE returns 404 - source not found
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.notFoundError("Not found")
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsDeleted)

        // Should advance to CREATE phase (source already gone)
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
    }

    @Test
    fun `processMove Phase 0 falls back to CREATE+DELETE when MOVE returns 412 (iCloud)`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // MOVE returns 412 (iCloud behavior)
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.conflictError("Precondition failed")
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsDeleted)

        // Should fall back to CREATE+DELETE by advancing to CREATE phase
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
        // No immediate DELETE - CREATE first for safety
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
    }

    @Test
    fun `processMove Phase 0 falls back to CREATE+DELETE when MOVE returns 403`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // MOVE returns 403 (forbidden - cross-server)
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.Error(403, "Forbidden", false)
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
    }

    @Test
    fun `processMove Phase 0 falls back to CREATE+DELETE when MOVE returns 405`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // MOVE returns 405 (method not allowed)
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.Error(405, "Method not allowed", false)
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
    }

    @Test
    fun `processMove Phase 0 retries on server error`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // MOVE returns 500 (server error - should retry)
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.Error(500, "Server error", true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Should schedule retry for MOVE (not advance to CREATE)
        coVerify { pendingOperationsDao.scheduleRetry(moveOperation.id, any(), any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.advanceToCreatePhase(any(), any()) }
    }

    @Test
    fun `processMove Phase 0 with null targetUrl advances to CREATE`() = runTest {
        // When moving from local calendar (no old URL)
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = null, // No old URL (was local)
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)

        // No MOVE or DELETE (no source URL)
        coVerify(exactly = 0) { client.moveEvent(any(), any(), any()) }
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // Should advance to CREATE phase
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
    }

    // ==================== MOVE Phase 1: CREATE+DELETE Tests ====================

    @Test
    fun `processMove Phase 1 creates then deletes (safety order)`() = runTest {
        val oldUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics"
        val newUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"
        val newEtag = "\"new-etag\""

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl, // Source URL for DELETE after CREATE
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE // Phase 1: CREATE+DELETE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // CREATE succeeds
        coEvery { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, any()) } returns
            CalDavResult.success(Pair(newUrl, newEtag))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, newUrl, newEtag, any()) } just Runs
        // DELETE succeeds (after CREATE)
        coEvery { client.deleteEvent(oldUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // Verify order: CREATE first, DELETE second
        coVerifyOrder {
            client.createEvent(targetCalendar.caldavUrl, testEvent.uid, any())
            client.deleteEvent(oldUrl, any())
        }
    }

    @Test
    fun `processMove Phase 1 succeeds even if DELETE fails after CREATE`() = runTest {
        // Safety: If CREATE succeeds but DELETE fails, we still succeed
        // (event is safe in target, may have orphan in source)
        val oldUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics"
        val newUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"
        val newEtag = "\"new-etag\""

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = oldUrl,
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // CREATE succeeds
        coEvery { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, any()) } returns
            CalDavResult.success(Pair(newUrl, newEtag))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, newUrl, newEtag, any()) } just Runs
        // DELETE fails (but we still succeed - CREATE worked)
        coEvery { client.deleteEvent(oldUrl, any()) } returns CalDavResult.Error(500, "Server error", true)
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        // Should still succeed (event is safe in target)
        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // Operation should complete (not retry DELETE)
        coVerify { pendingOperationsDao.deleteById(moveOperation.id) }
    }

    @Test
    fun `processMove Phase 1 skips DELETE when targetUrl is null`() = runTest {
        // Moving from local calendar - no source to delete
        val newUrl = "https://caldav.icloud.com/123/work/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = null, // No old URL (was local)
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair(newUrl, "\"etag\""))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)

        // DELETE should NOT be called (no source URL)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // CREATE should be called
        coVerify { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, any()) }
    }

    @Test
    fun `processMove Phase 1 fails when CREATE conflicts (UID exists)`() = runTest {
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // CREATE conflicts - UID already exists
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("UID exists")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Should NOT call DELETE (CREATE failed)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
    }

    @Test
    fun `processMove Phase 1 includes exceptions when moving recurring event`() = runTest {
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
            originalInstanceTime = exceptionStartTs
        )

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = recurringEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/old.ics",
            targetCalendarId = targetCalendar.id,
            status = PendingOperation.STATUS_PENDING,
            movePhase = PendingOperation.MOVE_PHASE_CREATE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(recurringEvent.id) } returns recurringEvent
        coEvery { calendarsDao.getById(targetCalendar.id) } returns targetCalendar
        // Should fetch exceptions for master
        coEvery { eventsDao.getExceptionsForMaster(recurringEvent.id) } returns listOf(exception)
        coEvery { client.createEvent(targetCalendar.caldavUrl, recurringEvent.uid, any()) } returns
            CalDavResult.success(Pair(newUrl, "\"etag\""))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)

        // Verify exceptions were fetched for master
        coVerify { eventsDao.getExceptionsForMaster(recurringEvent.id) }
    }

    // ==================== Error Handling Tests ====================

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

        val result = pushStrategy.pushAll(client)

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

        val result = pushStrategy.pushAll(client)

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

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }

    // ==================== FIFO Order Tests ====================

    @Test
    fun `multiple MOVE operations processed in FIFO order`() = runTest {
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
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair("https://new.ics", "\"etag\""))
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // Verify FIFO order
        assertEquals(listOf(1L, 2L, 3L), processOrder)
    }
}
