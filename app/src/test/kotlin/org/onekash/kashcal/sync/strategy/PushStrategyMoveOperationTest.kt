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
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher

/**
 * Tests the MOVE operation in [PushStrategy].
 *
 * - Phase 0: WebDAV MOVE. On success the op becomes an UPDATE that PUTs the current body to the
 *   new URL. A 403, 405 or 412, a 404, or a missing source URL advances to phase 1; any other
 *   error retries the MOVE.
 * - Phase 1: CREATE in the target calendar, then DELETE from the source, so a failed CREATE
 *   loses nothing.
 *
 * The source URL comes from the op's targetUrl, never from the event:
 * `EventWriter.moveEventToCalendar` has already cleared the event's caldavUrl.
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
        // EventWriter.moveEventToCalendar clears caldavUrl when it queues the move.
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

        // Empty batch reads, so every lookup falls back to getById.
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
        // After the MOVE the row points at newUrl and stays PENDING_UPDATE; the success path
        // hands off to the UPDATE path, which reads this row and PUTs the current body there.
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
        // Relocation bookkeeping: new URL and etag, row kept dirty, op converted.
        coEvery { eventsDao.updateCaldavUrl(testEvent.id, newUrl) } just Runs
        coEvery { eventsDao.updateEtag(testEvent.id, newEtag) } just Runs
        coEvery { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.PENDING_UPDATE, any()) } just Runs
        coEvery { pendingOperationsDao.update(any()) } just Runs
        // MOVE carries no body (RFC 4918 §9.9): the current body is PUT to the new URL through
        // the UPDATE path so an edit made in the same save isn't lost.
        coEvery { client.updateEvent(newUrl, any(), any()) } returns CalDavResult.success(finalEtag)
        coEvery { eventsDao.markSynced(testEvent.id, finalEtag, any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(testEvent.id, finalEtag, any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // The MOVE, then a body PUT to the new URL.
        coVerify { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) }
        coVerify { client.updateEvent(newUrl, any(), any()) }
        // Row kept PENDING_UPDATE until the body PUT lands, so a pull in between won't
        // overwrite the local edit.
        coVerify { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.PENDING_UPDATE, any()) }
        // No separate CREATE or source DELETE: the MOVE did both.
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // The op is done and deleted.
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
        // MOVE returns 404: source not found.
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.notFoundError("Not found")
        coEvery { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsDeleted)

        // Advances to the CREATE phase (source already gone).
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

        // Falls back to CREATE+DELETE by advancing to the CREATE phase.
        coVerify { pendingOperationsDao.advanceToCreatePhase(moveOperation.id, any()) }
        // No DELETE yet: the CREATE comes first.
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
        // MOVE returns 403 (forbidden, cross-server).
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
        // MOVE returns 500: a server error, which retries.
        coEvery { client.moveEvent(oldUrl, targetCalendar.caldavUrl, testEvent.uid) } returns
            CalDavResult.Error(500, "Server error", true)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Schedules a retry of the MOVE and doesn't advance to CREATE.
        coVerify { pendingOperationsDao.scheduleRetry(moveOperation.id, any(), any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.advanceToCreatePhase(any(), any()) }
    }

    @Test
    fun `processMove Phase 0 with null targetUrl advances to CREATE`() = runTest {
        // EventWriter queues a MOVE only with a source URL; this covers processMove's guard.
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = null, // no source URL
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

        // No MOVE or DELETE without a source URL.
        coVerify(exactly = 0) { client.moveEvent(any(), any(), any()) }
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // Advances to the CREATE phase.
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
            targetUrl = oldUrl, // source URL, deleted after the CREATE
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(testEvent.id, newUrl, newEtag, any(), any()) } just Runs
        // DELETE succeeds, after the CREATE.
        coEvery { client.fetchEtag(oldUrl) } returns CalDavResult.success("source-etag")
        coEvery { client.deleteEvent(oldUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // CREATE first, then a DELETE conditional on the source's own ETag.
        coVerifyOrder {
            client.createEvent(targetCalendar.caldavUrl, testEvent.uid, any())
            client.fetchEtag(oldUrl)
            client.deleteEvent(oldUrl, "source-etag")
        }
    }

    @Test
    fun `processMove Phase 1 succeeds even if DELETE fails after CREATE`() = runTest {
        // The op succeeds when the CREATE lands and the DELETE fails: the event is safe in the
        // target, and a copy may remain in the source.
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(testEvent.id, newUrl, newEtag, any(), any()) } just Runs
        // DELETE fails after the CREATE worked.
        coEvery { client.fetchEtag(oldUrl) } returns CalDavResult.success("source-etag")
        coEvery { client.deleteEvent(oldUrl, any()) } returns CalDavResult.Error(500, "Server error", true)
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        // Still succeeds: the event is safe in the target.
        assertTrue(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)
        assertEquals(1, success.eventsDeleted)

        // The op completes; the DELETE isn't retried.
        coVerify { pendingOperationsDao.deleteById(moveOperation.id) }
    }

    /**
     * Runs a MOVE fallback (phase 1: CREATE at the target, then delete the source)
     * where the source's ETag lookup answers [sourceEtag] and a DELETE answers [delete].
     */
    private suspend fun runMoveFallback(
        sourceEtag: CalDavResult<String?>,
        delete: CalDavResult<Unit> = CalDavResult.success(Unit)
    ): Pair<String, PushResult> {
        val oldUrl = "https://caldav.icloud.com/123/personal/move-test-uid-123.ics"
        val newUrl = "https://caldav.icloud.com/123/work/move-test-uid-123.ics"
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
        coEvery { client.createEvent(targetCalendar.caldavUrl, testEvent.uid, any()) } returns
            CalDavResult.success(Pair(newUrl, "\"new-etag\""))
        coEvery { eventsDao.markCreatedOnServer(testEvent.id, newUrl, "\"new-etag\"", any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(testEvent.id, newUrl, "\"new-etag\"", any(), any()) } just Runs
        coEvery { client.fetchEtag(oldUrl) } returns sourceEtag
        coEvery { client.deleteEvent(oldUrl, any()) } returns delete
        coEvery { pendingOperationsDao.deleteById(moveOperation.id) } just Runs
        return oldUrl to pushStrategy.pushAll(client)
    }

    private fun PushResult.warnings() = (this as PushResult.Success).pushWarnings

    @Test
    fun `MOVE fallback deletes the source only if it still has the ETag just read`() = runTest {
        val (oldUrl, result) = runMoveFallback(CalDavResult.success("source-etag"))

        coVerify(exactly = 1) { client.deleteEvent(oldUrl, "source-etag") }
        coVerify(exactly = 0) { client.deleteEvent(any(), "") }
        assertTrue(result.warnings().isEmpty())
    }

    @Test
    fun `MOVE fallback deletes the source without a condition only when the source is not found`() = runTest {
        val (oldUrl, result) = runMoveFallback(CalDavResult.notFoundError("gone"))

        coVerify(exactly = 1) { client.deleteEvent(oldUrl, null) }
        assertTrue(result.warnings().isEmpty())
    }

    @Test
    fun `MOVE fallback leaves the source alone when its ETag can't be read`() = runTest {
        val (_, result) = runMoveFallback(CalDavResult.networkError("offline"))

        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        assertTrue(result.warnings().single().contains("may be duplicated"))
    }

    @Test
    fun `MOVE fallback leaves the source alone when the server gives no ETag for it`() = runTest {
        val (_, result) = runMoveFallback(CalDavResult.success(null))

        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        assertTrue(result.warnings().single().contains("may be duplicated"))
    }

    @Test
    fun `MOVE fallback does not retry blind when the source changed after its ETag was read`() = runTest {
        val (oldUrl, result) = runMoveFallback(
            CalDavResult.success("source-etag"),
            delete = CalDavResult.conflictError("modified")
        )

        coVerify(exactly = 1) { client.deleteEvent(oldUrl, "source-etag") }
        coVerify(exactly = 0) { client.deleteEvent(oldUrl, null) }
        assertTrue(result.warnings().single().contains("may be duplicated"))
    }

    @Test
    fun `processMove Phase 1 skips DELETE when targetUrl is null`() = runTest {
        // No source URL, so nothing to delete.
        val newUrl = "https://caldav.icloud.com/123/work/event.ics"

        val moveOperation = PendingOperation(
            id = 1L,
            eventId = testEvent.id,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = null, // no source URL
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)

        // No DELETE without a source URL.
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // The CREATE runs.
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
        // CREATE conflicts: the UID already exists.
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("UID exists")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // No DELETE after a failed CREATE.
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
    }

    @Test
    fun `processMove Phase 1 includes exceptions when moving recurring event`() = runTest {
        val newUrl = "https://caldav.icloud.com/123/work/recurring.ics"

        val recurringEvent = testEvent.copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            originalEventId = null // a master
        )

        val exceptionStartTs = System.currentTimeMillis() + 86400000
        val exception = Event(
            id = 101L,
            uid = recurringEvent.uid, // same UID as the master
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
        // The master's exceptions are read for the body.
        coEvery { eventsDao.getExceptionsForMaster(recurringEvent.id) } returns listOf(exception)
        coEvery { client.createEvent(targetCalendar.caldavUrl, recurringEvent.uid, any()) } returns
            CalDavResult.success(Pair(newUrl, "\"etag\""))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { client.fetchEtag(any()) } returns CalDavResult.success("source-etag")
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)

        // The master's exceptions were read.
        coVerify { eventsDao.getExceptionsForMaster(recurringEvent.id) }
    }

    // ========== Moving a series re-points its exceptions' URL ==========
    //
    // An exception lives in its master's server resource (RFC 5545 §3.8.4.4 RECURRENCE-ID), so
    // after a move the master's URL is also the exception's. The local move carries the
    // exception's row into the target calendar; if it kept the source URL, the target
    // calendar's next pull would find its resource absent and reap it, so the series survives
    // and the edited occurrence vanishes (#365).

    private val seriesMaster = testEvent.copy(rrule = "FREQ=WEEKLY;COUNT=5")

    private val seriesOverride = testEvent.copy(
        id = 101L,
        title = "Modified occurrence",
        rrule = null,
        originalEventId = testEvent.id,
        originalInstanceTime = testEvent.startTs + 7 * 86400_000L,
        // Still pointing at the source calendar's resource.
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
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // The exception takes the master's new URL and the body PUT's etag in one write. An
        // etag-only update would leave it on the source URL.
        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServerWithCopy(seriesOverride.id, newUrl, finalEtag, any(), any())
        }
        coVerify(exactly = 0) { eventsDao.markSynced(seriesOverride.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSyncedWithCopy(seriesOverride.id, any(), any(), any()) }
        // The master's own bookkeeping is unchanged on this path.
        coVerify(exactly = 1) { eventsDao.markSyncedWithCopy(seriesMaster.id, finalEtag, any(), any()) }
    }

    @Test
    fun `MOVE fallback CREATE re-points bundled overrides at the new url`() = runTest {
        // A server that declines WebDAV MOVE (403, 405, 412) goes through phase 1
        // CREATE+DELETE. That CREATE gives the master a new URL, so the exceptions it bundled
        // must be re-pointed too.
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { client.fetchEtag(phase1Op.targetUrl!!) } returns CalDavResult.success("source-etag")
        coEvery { client.deleteEvent(phase1Op.targetUrl!!, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServerWithCopy(seriesOverride.id, createdUrl, createdEtag, any(), any())
        }
        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServerWithCopy(seriesMaster.id, createdUrl, createdEtag, any(), any())
        }
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `processMove fails when event not found`() = runTest {
        val moveOperation = PendingOperation(
            id = 1L,
            eventId = 999L, // no such event
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
            targetCalendarId = 999L, // no such calendar
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
            targetCalendarId = null, // required for a MOVE
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
        coEvery { client.fetchEtag(any()) } returns CalDavResult.success("source-etag")
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // FIFO order.
        assertEquals(listOf(1L, 2L, 3L), processOrder)
    }

    // ============ MOVE-then-PUT failure paths (#292) ============
    //
    // A WebDAV MOVE carries no body (RFC 4918 §9.9), so after it succeeds the current body is
    // PUT to the new URL through the UPDATE path. These cover that hand-off's edge cases.

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
     * Stubs a WebDAV MOVE that succeeds with [moveEtag], then a body PUT to the new URL that
     * returns [putResult]. getById returns the relocated row, so the UPDATE targets the new URL.
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
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.update(any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs
    }

    @Test
    fun `MOVE success keeps row PENDING_UPDATE until the body PUT lands`() = runTest {
        // The row must not be SYNCED before the body PUT confirms, or a pull in between
        // would overwrite the local edit.
        stubMoveThenPut("\"etag\"", CalDavResult.success("\"final\""))

        pushStrategy.pushAll(client)

        // The row points at the new URL but stays dirty until the PUT succeeds.
        coVerify { eventsDao.updateCaldavUrl(testEvent.id, newUrl) }
        coVerify { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.PENDING_UPDATE, any()) }
        // markCreatedOnServer sets SYNCED, so this path must not call it.
        coVerify(exactly = 0) { eventsDao.markCreatedOnServer(any(), any(), any(), any()) }
    }

    @Test
    fun `MOVE succeeds then body PUT converts the op to a plain UPDATE`() = runTest {
        // A failing body PUT must not leave the op as a MOVE: a retry would re-MOVE the gone
        // source (404, then CREATE, then an account-wide UID clash). The op becomes an UPDATE
        // against the new URL before the PUT.
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
        // A 412 on the follow-up PUT is a conflict; the UPDATE path refetches the server's copy,
        // applies the edit to it and retries once.
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
        // First PUT (stale etag) -> 412; the server's copy refetched; retry PUT -> success.
        coEvery { client.updateEvent(newUrl, any(), "\"stale\"") } returns
            CalDavResult.conflictError("Precondition failed")
        coEvery { client.fetchEvent(newUrl) } returns CalDavResult.success(
            CalDavEvent(href = newUrl, url = newUrl, etag = "\"fresh\"", icalData = IcsPatcher.serialize(relocated, null))
        )
        coEvery { client.updateEvent(newUrl, any(), "\"fresh\"") } returns CalDavResult.success("\"final\"")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client) as PushResult.Success

        // The retry ran with the refetched copy's etag and succeeded, with no second MOVE, and
        // the moved event is reported for this cycle's pull to refresh, like any merged write.
        coVerify { client.fetchEvent(newUrl) }
        coVerify { client.updateEvent(newUrl, any(), "\"fresh\"") }
        coVerify(exactly = 1) { client.moveEvent(any(), any(), any()) }
        assertTrue(testEvent.id in result.refetchEventIds)
        assertTrue(testEvent.id in result.pushedEventIds)
    }

    @Test
    fun `MOVE returning empty etag never PUTs with a blank If-Match`() = runTest {
        // Some servers omit the ETag on MOVE; moveEvent returns "" when its PROPFIND finds
        // none either. The UPDATE must recover an etag via PROPFIND, never PUT If-Match: "".
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
        // Empty etag, then PROPFIND recovery, then a PUT with the recovered etag.
        coEvery { client.fetchEtag(newUrl) } returns CalDavResult.success("\"recovered\"")
        val putEtagSlot = slot<String>()
        coEvery { client.updateEvent(newUrl, any(), capture(putEtagSlot)) } returns
            CalDavResult.success("\"final\"")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        assertTrue("PUT must have been attempted", putEtagSlot.isCaptured)
        assertTrue(
            "If-Match etag must be non-blank (recovered via PROPFIND), was '${putEtagSlot.captured}'",
            putEtagSlot.captured.isNotEmpty()
        )
    }
}
