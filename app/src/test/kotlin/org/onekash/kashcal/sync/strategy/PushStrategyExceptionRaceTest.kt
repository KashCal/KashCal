package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Regression tests for exception event race condition in PushStrategy.
 *
 * SCENARIO:
 * 1. User modifies recurring event occurrence → creates exception A
 * 2. PushStrategy serializes master with exception A
 * 3. HTTP PUT in progress...
 * 4. User modifies ANOTHER occurrence → creates exception B (not in serialized payload)
 * 5. HTTP response arrives with etag
 *
 * BUG (pre-fix): Both A and B get the etag, but only A was actually pushed
 * FIX: Only update etags for exceptions captured at serialization time
 *
 * The fix is in PushStrategy.processCreate/processUpdate:
 * - serializeEventWithExceptions() captures exceptions at serialization time
 * - Only those captured exceptions get their etag updated
 */
class PushStrategyExceptionRaceTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarsDao: CalendarsDao
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

    private val masterEvent = Event(
        id = 100L,
        uid = "recurring-event-uid",
        calendarId = 1L,
        title = "Weekly Meeting",
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600_000,
        rrule = "FREQ=WEEKLY;COUNT=10",
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.PENDING_CREATE
    )

    private val exceptionA = Event(
        id = 101L,
        uid = "recurring-event-uid",
        calendarId = 1L,
        title = "Modified Instance A",
        startTs = masterEvent.startTs + 7 * 86400000L, // +1 week
        endTs = masterEvent.endTs + 7 * 86400000L,
        originalEventId = masterEvent.id,
        originalInstanceTime = masterEvent.startTs + 7 * 86400000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )

    private val exceptionB = Event(
        id = 102L,
        uid = "recurring-event-uid",
        calendarId = 1L,
        title = "Modified Instance B",
        startTs = masterEvent.startTs + 14 * 86400000L, // +2 weeks
        endTs = masterEvent.endTs + 14 * 86400000L,
        originalEventId = masterEvent.id,
        originalInstanceTime = masterEvent.startTs + 14 * 86400000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )

    // Exception C is created during HTTP PUT (race condition)
    private val exceptionC = Event(
        id = 103L,
        uid = "recurring-event-uid",
        calendarId = 1L,
        title = "Modified Instance C - Created During Push",
        startTs = masterEvent.startTs + 21 * 86400000L, // +3 weeks
        endTs = masterEvent.endTs + 21 * 86400000L,
        originalEventId = masterEvent.id,
        originalInstanceTime = masterEvent.startTs + 21 * 86400000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.PENDING_CREATE // Still pending!
    )

    @Before
    fun setup() {
        client = mockk()
        calendarsDao = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        // Default batch query mocks
        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarsDao.getByIds(any()) } returns emptyList()

        pushStrategy = PushStrategy(
            client = client,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * CREATE only updates etag for exceptions captured at serialization time.
     *
     * Scenario:
     * 1. serializeEventWithExceptions() captures [exceptionA, exceptionB]
     * 2. HTTP PUT creates event on server
     * 3. Server returns etag
     * 4. Verify: only A and B get etag updated, NOT C (which was created after serialization)
     */
    @Test
    fun `CREATE only updates etag for exceptions captured at serialization time`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = masterEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverUrl = "${testCalendar.caldavUrl}${masterEvent.uid}.ics"
        val serverEtag = "etag-new-from-server"

        // Setup mocks
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEvent.id) } returns masterEvent
        coEvery { calendarsDao.getById(masterEvent.calendarId) } returns testCalendar

        // KEY: getExceptionsForMaster returns only A and B at serialization time
        // (exceptionC doesn't exist yet in this snapshot)
        coEvery { eventsDao.getExceptionsForMaster(masterEvent.id) } returns listOf(exceptionA, exceptionB)

                coEvery { client.createEvent(testCalendar.caldavUrl, masterEvent.uid, any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(masterEvent.id, serverUrl, serverEtag, any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        // Execute
        val result = pushStrategy.pushAll()

        // Verify success
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)

        // CRITICAL ASSERTIONS:
        // Only exceptionA and exceptionB should have etag updated (exactly 2 calls)
        coVerify(exactly = 1) { eventsDao.markSynced(exceptionA.id, serverEtag, any()) }
        coVerify(exactly = 1) { eventsDao.markSynced(exceptionB.id, serverEtag, any()) }

        // exceptionC should NOT have its etag updated (it wasn't captured at serialization time)
        coVerify(exactly = 0) { eventsDao.markSynced(exceptionC.id, any(), any()) }
    }

    /**
     * UPDATE only updates etag for exceptions captured at serialization time.
     *
     * Same race condition scenario but for UPDATE operations.
     */
    @Test
    fun `UPDATE only updates etag for exceptions captured at serialization time`() = runTest {
        val masterEventWithUrl = masterEvent.copy(
            caldavUrl = "${testCalendar.caldavUrl}${masterEvent.uid}.ics",
            etag = "existing-etag",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 1L,
            eventId = masterEventWithUrl.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val newEtag = "etag-after-update"

        // Setup mocks
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEventWithUrl.id) } returns masterEventWithUrl
        coEvery { calendarsDao.getById(masterEventWithUrl.calendarId) } returns testCalendar

        // KEY: getExceptionsForMaster returns only A and B at serialization time
        coEvery { eventsDao.getExceptionsForMaster(masterEventWithUrl.id) } returns listOf(exceptionA, exceptionB)

                coEvery { client.updateEvent(masterEventWithUrl.caldavUrl!!, any(), "existing-etag") } returns
            CalDavResult.success(newEtag)
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        // Execute
        val result = pushStrategy.pushAll()

        // Verify success
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsUpdated)

        // CRITICAL ASSERTIONS:
        // Master event etag updated
        coVerify(exactly = 1) { eventsDao.markSynced(masterEventWithUrl.id, newEtag, any()) }

        // Only exceptionA and exceptionB should have etag updated
        coVerify(exactly = 1) { eventsDao.markSynced(exceptionA.id, newEtag, any()) }
        coVerify(exactly = 1) { eventsDao.markSynced(exceptionB.id, newEtag, any()) }

        // exceptionC should NOT have its etag updated
        coVerify(exactly = 0) { eventsDao.markSynced(exceptionC.id, any(), any()) }
    }

    /**
     * CREATE with zero exceptions - verify no exception etag updates.
     */
    @Test
    fun `CREATE with no exceptions does not call markSynced for exceptions`() = runTest {
        val operation = PendingOperation(
            id = 1L,
            eventId = masterEvent.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverUrl = "${testCalendar.caldavUrl}${masterEvent.uid}.ics"
        val serverEtag = "etag-new"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEvent.id) } returns masterEvent
        coEvery { calendarsDao.getById(masterEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(masterEvent.id) } returns emptyList()
        // Note: Even with empty exceptions, recurring events use serializeWithExceptions
                coEvery { client.createEvent(testCalendar.caldavUrl, masterEvent.uid, any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(masterEvent.id, serverUrl, serverEtag, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll()

        assert(result is PushResult.Success)

        // Master uses markCreatedOnServer, not markSynced
        coVerify(exactly = 1) { eventsDao.markCreatedOnServer(masterEvent.id, serverUrl, serverEtag, any()) }

        // No exceptions means no markSynced calls
        coVerify(exactly = 0) { eventsDao.markSynced(any(), any(), any()) }
    }

    /**
     * Verify serialization captures exceptions at call time (not later).
     *
     * This test ensures the architectural guarantee: serializeEventWithExceptions
     * returns BOTH the serialized data AND the list of exceptions, so they're
     * guaranteed to be consistent.
     */
    @Test
    fun `exceptions captured at serialization time determine etag updates`() = runTest {
        val masterEventWithUrl = masterEvent.copy(
            caldavUrl = "${testCalendar.caldavUrl}${masterEvent.uid}.ics",
            etag = "existing-etag",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 1L,
            eventId = masterEventWithUrl.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        // Capture the list of exceptions at mock call time
        val capturedExceptions = mutableListOf<Event>()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEventWithUrl.id) } returns masterEventWithUrl
        coEvery { calendarsDao.getById(masterEventWithUrl.calendarId) } returns testCalendar

        // Return only exceptionA at serialization time
        coEvery { eventsDao.getExceptionsForMaster(masterEventWithUrl.id) } answers {
            // Capture what's returned - this is what should determine etag updates
            val result = listOf(exceptionA)
            capturedExceptions.addAll(result)
            result
        }

                coEvery { client.updateEvent(masterEventWithUrl.caldavUrl!!, any(), "existing-etag") } returns
            CalDavResult.success("new-etag")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        // Execute
        val result = pushStrategy.pushAll()

        assert(result is PushResult.Success)

        // Verify the captured list is used for etag updates
        assertEquals(1, capturedExceptions.size)
        assertEquals(exceptionA.id, capturedExceptions[0].id)

        // Only exceptionA (captured at serialization) gets etag update
        coVerify(exactly = 1) { eventsDao.markSynced(exceptionA.id, "new-etag", any()) }

        // Total markSynced calls: 1 for master + 1 for exceptionA = 2
        coVerify(exactly = 2) { eventsDao.markSynced(any(), any(), any()) }
    }
}
