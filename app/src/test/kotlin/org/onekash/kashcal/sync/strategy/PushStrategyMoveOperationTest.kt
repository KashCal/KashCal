package org.onekash.kashcal.sync.strategy

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.CalendarRepository
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
 * Critical invariant (self-contained sync operations):
 * - targetUrl must be read from PendingOperation, not from Event (which is
 *   already cleared by EventWriter.moveEventToCalendar)
 */
class PushStrategyMoveOperationTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
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
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        // Default batch query mocks - return empty so fallback to getById is used
        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()

        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            accountRepository = mockk(relaxed = true),
            attendeesDao = mockk(relaxed = true),
            pendingCancelsDao = mockk { coEvery { getForEvent(any()) } returns emptyList() }
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

        val finalEtag = "\"final-etag\""
        // After the MOVE relocates the resource, the row is repointed at newUrl and
        // kept PENDING_UPDATE; the success path hands off to the shared UPDATE path,
        // which reads this relocated row and PUTs the current body to the new URL.
        val relocatedEvent = testEvent.copy(
            caldavUrl = newUrl,
            etag = newEtag,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOperation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns relocatedEvent
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
        // WebDAV MOVE succeeds
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.success(Pair(newUrl, newEtag))
        // Relocation bookkeeping: repoint URL/etag, keep the row dirty, convert op.
        coEvery { eventsDao.updateCaldavUrl(testEvent.id, newUrl) } just Runs
        coEvery { eventsDao.updateEtag(testEvent.id, newEtag) } just Runs
        coEvery { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.PENDING_UPDATE, any()) } just Runs
        coEvery { pendingOperationsDao.update(any()) } just Runs
        // MOVE is bodyless (RFC 4918 §9.9): the current body is PUT to the new URL
        // via the shared UPDATE path so a same-save edit isn't stranded.
        coEvery { client.updateEvent(newUrl, any(), any()) } returns CalDavResult.success(finalEtag)
        coEvery { eventsDao.markSynced(testEvent.id, finalEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // Verify atomic MOVE, then a body PUT to the new URL
        coVerify { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) }
        coVerify { client.updateEvent(newUrl, any(), any()) }
        // Row kept dirty (PENDING_UPDATE) until the body PUT lands, so an
        // interleaved pull won't overwrite the local edit.
        coVerify { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.PENDING_UPDATE, any()) }
        // No separate CREATE or source DELETE calls (MOVE is atomic)
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
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

    // ========== Moving a series re-points its overrides' resource url ==========
    //
    // A modified occurrence rides in the SAME server resource as its master
    // (RFC 5545 §3.8.4.4 RECURRENCE-ID), so the master's url after a move is
    // also the override's url. The override's row is carried into the target
    // calendar by the local move, so if it keeps the source url the target
    // calendar's next pull sees a row whose resource is absent from the server
    // and reaps it — the series survives and the edited occurrence vanishes
    // (issue #365).

    private val seriesMaster = testEvent.copy(rrule = "FREQ=WEEKLY;COUNT=5")

    private val seriesOverride = testEvent.copy(
        id = 101L,
        title = "Modified occurrence",
        rrule = null,
        originalEventId = testEvent.id,
        originalInstanceTime = testEvent.startTs + 7 * 86400_000L,
        // Still pointing at the SOURCE account's resource.
        caldavUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics",
        etag = "\"source-etag\"",
        syncStatus = SyncStatus.SYNCED
    )

    @Test
    fun `same-account MOVE re-points bundled overrides at the relocated url`() = runTest {
        val finalEtag = "\"final-etag\""
        val relocated = seriesMaster.copy(
            caldavUrl = newUrl,
            etag = "\"moved-etag\"",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOp)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(seriesMaster.id) } returns relocated
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { client.moveEvent(moveOp.targetUrl!!, targetCalendar.caldavUrl, seriesMaster.uid) } returns
            CalDavResult.success(Pair(newUrl, "\"moved-etag\""))
        coEvery { eventsDao.updateCaldavUrl(seriesMaster.id, newUrl) } just Runs
        coEvery { eventsDao.updateEtag(seriesMaster.id, any()) } just Runs
        coEvery { eventsDao.updateSyncStatus(seriesMaster.id, any(), any()) } just Runs
        coEvery { eventsDao.getExceptionsForMaster(seriesMaster.id) } returns listOf(seriesOverride)
        coEvery { pendingOperationsDao.update(any()) } just Runs
        coEvery { client.updateEvent(newUrl, any(), any()) } returns CalDavResult.success(finalEtag)
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // The override adopts the master's new url AND the body PUT's etag in one
        // write. An etag-only update would leave it on the source url.
        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServer(seriesOverride.id, newUrl, finalEtag, any())
        }
        coVerify(exactly = 0) { eventsDao.markSynced(seriesOverride.id, any(), any()) }
        // The master's own bookkeeping is unchanged on this path.
        coVerify(exactly = 1) { eventsDao.markSynced(seriesMaster.id, finalEtag, any()) }
    }

    @Test
    fun `MOVE fallback CREATE re-points bundled overrides at the new url`() = runTest {
        // Servers that decline WebDAV MOVE (403/405/412) go through Phase 1
        // CREATE+DELETE. That CREATE writes a new url for the master, so the
        // overrides it bundled must be re-pointed too.
        val createdUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"
        val createdEtag = "\"created-etag\""

        val phase1Op = moveOp.copy(movePhase = PendingOperation.MOVE_PHASE_CREATE)

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(phase1Op)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(seriesMaster.id) } returns seriesMaster
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { eventsDao.getExceptionsForMaster(seriesMaster.id) } returns listOf(seriesOverride)
        coEvery { client.createEvent(targetCalendar.caldavUrl, seriesMaster.uid, any()) } returns
            CalDavResult.success(Pair(createdUrl, createdEtag))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { client.deleteEvent(phase1Op.targetUrl!!, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServer(seriesOverride.id, createdUrl, createdEtag, any())
        }
        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServer(seriesMaster.id, createdUrl, createdEtag, any())
        }
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
        coEvery { calendarRepository.getCalendarById(999L) } returns null
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
        coEvery { calendarRepository.getCalendarById(any()) } returns targetCalendar
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

    // ============ MOVE-then-PUT adversarial paths (issue #292 fix) ============
    //
    // A successful WebDAV MOVE is bodyless (RFC 4918 §9.9), so after relocating
    // the resource the current body is pushed to the new URL via the shared
    // UPDATE path. These cover the failure branches of that hand-off.

    private val moveOp = PendingOperation(
        id = 1L,
        eventId = testEvent.id,
        operation = PendingOperation.OPERATION_MOVE,
        targetUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics",
        targetCalendarId = targetCalendar.id,
        status = PendingOperation.STATUS_PENDING,
        movePhase = PendingOperation.MOVE_PHASE_DELETE
    )
    private val newUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"

    /**
     * Wire up a scenario where the WebDAV MOVE succeeds (returning [moveEtag])
     * and the subsequent body PUT to the new URL returns [putResult].
     * The relocated row is returned by getById so the delegated UPDATE targets
     * the new URL.
     */
    private fun stubMoveThenPut(
        moveEtag: String,
        putResult: CalDavResult<String>
    ) {
        val relocated = testEvent.copy(
            caldavUrl = newUrl,
            etag = moveEtag,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOp)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns relocated
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { client.moveEvent(moveOp.targetUrl!!, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.success(Pair(newUrl, moveEtag))
        coEvery { eventsDao.updateCaldavUrl(testEvent.id, newUrl) } just Runs
        coEvery { eventsDao.updateEtag(testEvent.id, any()) } just Runs
        coEvery { eventsDao.updateSyncStatus(testEvent.id, any(), any()) } just Runs
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { client.fetchEtag(any()) } returns CalDavResult.success(moveEtag.ifEmpty { "\"recovered\"" })
        coEvery { client.updateEvent(newUrl, any(), any()) } returns putResult
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.update(any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs
    }

    @Test
    fun `MOVE success keeps row PENDING_UPDATE until the body PUT lands`() = runTest {
        // Before the body PUT confirms, the row must NOT be marked SYNCED — a
        // premature SYNCED would let an interleaved pull overwrite the local edit.
        stubMoveThenPut("\"etag\"", CalDavResult.success("\"final\""))

        pushStrategy.pushAll(client)

        // Row is repointed to the new URL but kept dirty until the PUT succeeds.
        coVerify { eventsDao.updateCaldavUrl(testEvent.id, newUrl) }
        coVerify { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.PENDING_UPDATE, any()) }
        // markCreatedOnServer (which sets SYNCED) must NOT be used on this path.
        coVerify(exactly = 0) { eventsDao.markCreatedOnServer(any(), any(), any(), any()) }
    }

    @Test
    fun `MOVE succeeds then body PUT converts the op to a plain UPDATE`() = runTest {
        // A failing body PUT must NOT leave the op as a MOVE (a retry would
        // re-MOVE the now-gone source, 404 -> CREATE -> account-wide UID clash).
        // It must become a real UPDATE against the new URL.
        val captured = slot<PendingOperation>()
        stubMoveThenPut("\"etag\"", CalDavResult.error(500, "server error", isRetryable = true))
        coEvery { pendingOperationsDao.update(capture(captured)) } just Runs

        pushStrategy.pushAll(client)

        assertTrue("op should be converted before the PUT is attempted", captured.isCaptured)
        assertEquals(PendingOperation.OPERATION_UPDATE, captured.captured.operation)
        assertNull("MOVE-only targetUrl must be cleared", captured.captured.targetUrl)
        assertNull("MOVE-only targetCalendarId must be cleared", captured.captured.targetCalendarId)
        // The MOVE itself must not run again.
        coVerify(exactly = 1) { client.moveEvent(any(), any(), any()) }
    }

    @Test
    fun `MOVE succeeds then 412 on body PUT is retried, not permanently failed`() = runTest {
        // A 412 on the follow-up PUT is a genuine conflict; the shared UPDATE
        // path fetches a fresh etag and retries once rather than giving up.
        val relocated = testEvent.copy(
            caldavUrl = newUrl, etag = "\"stale\"", syncStatus = SyncStatus.PENDING_UPDATE
        )
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOp)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns relocated
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { client.moveEvent(moveOp.targetUrl!!, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.success(Pair(newUrl, "\"stale\""))
        coEvery { eventsDao.updateCaldavUrl(testEvent.id, newUrl) } just Runs
        coEvery { eventsDao.updateEtag(testEvent.id, any()) } just Runs
        coEvery { eventsDao.updateSyncStatus(testEvent.id, any(), any()) } just Runs
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.update(any()) } just Runs
        // First PUT (stale etag) -> 412; fresh etag fetched; retry PUT -> success.
        coEvery { client.updateEvent(newUrl, any(), "\"stale\"") } returns
            CalDavResult.conflictError("Precondition failed")
        coEvery { client.fetchEtag(newUrl) } returns CalDavResult.success("\"fresh\"")
        coEvery { client.updateEvent(newUrl, any(), "\"fresh\"") } returns CalDavResult.success("\"final\"")
        coEvery { eventsDao.updateEtag(testEvent.id, "\"fresh\"") } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // Fresh-etag retry happened and won — no re-MOVE.
        coVerify { client.fetchEtag(newUrl) }
        coVerify { client.updateEvent(newUrl, any(), "\"fresh\"") }
        coVerify(exactly = 1) { client.moveEvent(any(), any(), any()) }
    }

    @Test
    fun `MOVE returning empty etag never PUTs with a blank If-Match`() = runTest {
        // Some servers omit ETag on MOVE (moveEvent returns ""). The delegated
        // UPDATE must recover a real etag via PROPFIND rather than PUT If-Match: "".
        val relocated = testEvent.copy(
            caldavUrl = newUrl, etag = "", syncStatus = SyncStatus.PENDING_UPDATE
        )
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(moveOp)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(testEvent.id) } returns relocated
        coEvery { calendarRepository.getCalendarById(targetCalendar.id) } returns targetCalendar
        coEvery { client.moveEvent(moveOp.targetUrl!!, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.success(Pair(newUrl, ""))
        coEvery { eventsDao.updateCaldavUrl(testEvent.id, newUrl) } just Runs
        coEvery { eventsDao.updateEtag(testEvent.id, any()) } just Runs
        coEvery { eventsDao.updateSyncStatus(testEvent.id, any(), any()) } just Runs
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.update(any()) } just Runs
        // Empty etag -> PROPFIND recovery -> PUT with the recovered etag.
        coEvery { client.fetchEtag(newUrl) } returns CalDavResult.success("\"recovered\"")
        val putEtagSlot = slot<String>()
        coEvery { client.updateEvent(newUrl, any(), capture(putEtagSlot)) } returns
            CalDavResult.success("\"final\"")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        assertTrue("PUT must have been attempted", putEtagSlot.isCaptured)
        assertTrue(
            "If-Match etag must be non-blank (recovered via PROPFIND), was '${putEtagSlot.captured}'",
            putEtagSlot.captured.isNotEmpty()
        )
    }
}
