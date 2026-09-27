package org.onekash.kashcal.sync.strategy

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Tests that a push marks synced only the exceptions that were in the body it sent.
 *
 * The race: [PushStrategy] serializes a master with its exceptions and starts the PUT; while the
 * request is in flight the user edits another occurrence, creating exception C that isn't in the
 * body. When the response arrives, only the serialized exceptions take the master's URL and
 * etag; C keeps its pending state. `serializeEventWithExceptions` returns the body together with
 * the exceptions it contains, and those are the only rows marked synced.
 */
class PushStrategyExceptionRaceTest {

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

    // Created while the PUT is in flight, so it isn't in the serialized body.
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
        syncStatus = SyncStatus.PENDING_CREATE // still pending
    )

    @Before
    fun setup() {
        client = mockk()
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()

        // Default batch query mocks
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

    /**
     * Serialization captures [exceptionA] and [exceptionB]; the create returns a URL and etag.
     * Only A and B take them; [exceptionC], created after serialization, is left untouched.
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
        coEvery { calendarRepository.getCalendarById(masterEvent.calendarId) } returns testCalendar

        // At serialization time only A and B exist; exceptionC doesn't yet.
        coEvery { eventsDao.getExceptionsForMaster(masterEvent.id) } returns listOf(exceptionA, exceptionB)

                coEvery { client.createEvent(testCalendar.caldavUrl, masterEvent.uid, any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(masterEvent.id, serverUrl, serverEtag, any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(masterEvent.id, serverUrl, serverEtag, any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        // A bundled exception shares the master's resource, so it takes the master's URL and
        // etag together.
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        // Execute
        val result = pushStrategy.pushAll(client)

        // Verify success
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsCreated)

        // One call each for exceptionA and exceptionB.
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(exceptionA.id, serverUrl, serverEtag, any(), any()) }
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(exceptionB.id, serverUrl, serverEtag, any(), any()) }

        // exceptionC wasn't serialized, so it isn't marked synced.
        coVerify(exactly = 0) { eventsDao.markCreatedOnServer(exceptionC.id, any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markCreatedOnServerWithCopy(exceptionC.id, any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSynced(exceptionC.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSyncedWithCopy(exceptionC.id, any(), any(), any()) }
    }

    /** Runs the same race through an UPDATE: only A and B take the new etag. */
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
        coEvery { calendarRepository.getCalendarById(masterEventWithUrl.calendarId) } returns testCalendar

        // At serialization time only A and B exist.
        coEvery { eventsDao.getExceptionsForMaster(masterEventWithUrl.id) } returns listOf(exceptionA, exceptionB)

                coEvery { client.updateEvent(masterEventWithUrl.caldavUrl!!, any(), "existing-etag") } returns
            CalDavResult.success(newEtag)
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        // Execute
        val result = pushStrategy.pushAll(client)

        // Verify success
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.eventsUpdated)

        // The master takes the new etag.
        coVerify(exactly = 1) { eventsDao.markSyncedWithCopy(masterEventWithUrl.id, newEtag, any(), any()) }

        // Only exceptionA and exceptionB are marked synced, at the master's URL (also theirs).
        val masterUrl = masterEventWithUrl.caldavUrl!!
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(exceptionA.id, masterUrl, newEtag, any(), any()) }
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(exceptionB.id, masterUrl, newEtag, any(), any()) }

        // exceptionC isn't marked synced.
        coVerify(exactly = 0) { eventsDao.markCreatedOnServer(exceptionC.id, any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markCreatedOnServerWithCopy(exceptionC.id, any(), any(), any(), any()) }
    }

    /** Creates a recurring master with no exceptions: no exception row is updated. */
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
        coEvery { calendarRepository.getCalendarById(masterEvent.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(masterEvent.id) } returns emptyList()
        // A recurring master goes through serializeWithExceptions even with no exceptions.
                coEvery { client.createEvent(testCalendar.caldavUrl, masterEvent.uid, any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(masterEvent.id, serverUrl, serverEtag, any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(masterEvent.id, serverUrl, serverEtag, any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)

        // Master uses markCreatedOnServerWithCopy, not markSynced
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(masterEvent.id, serverUrl, serverEtag, any(), any()) }

        // No exceptions means no markSynced or markSyncedWithCopy calls
        coVerify(exactly = 0) { eventsDao.markSynced(any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) }
    }

    /**
     * Checks that the exceptions read at serialization time decide which rows are marked synced:
     * `serializeEventWithExceptions` returns the body and its exceptions from one read, so the two
     * can't disagree.
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

        // Records the exceptions returned at serialization time.
        val capturedExceptions = mutableListOf<Event>()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(masterEventWithUrl.id) } returns masterEventWithUrl
        coEvery { calendarRepository.getCalendarById(masterEventWithUrl.calendarId) } returns testCalendar

        // Only exceptionA exists at serialization time.
        coEvery { eventsDao.getExceptionsForMaster(masterEventWithUrl.id) } answers {
            // This list decides which exceptions are marked synced.
            val result = listOf(exceptionA)
            capturedExceptions.addAll(result)
            result
        }

                coEvery { client.updateEvent(masterEventWithUrl.caldavUrl!!, any(), "existing-etag") } returns
            CalDavResult.success("new-etag")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        // Execute
        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)

        // Serialization read exactly exceptionA.
        assertEquals(1, capturedExceptions.size)
        assertEquals(exceptionA.id, capturedExceptions[0].id)

        // Only exceptionA (captured at serialization) gets the etag
        coVerify(exactly = 1) {
            eventsDao.markCreatedOnServerWithCopy(exceptionA.id, masterEventWithUrl.caldavUrl!!, "new-etag", any(), any())
        }

        // The master is the only row going through markSyncedWithCopy
        coVerify(exactly = 1) { eventsDao.markSyncedWithCopy(masterEventWithUrl.id, "new-etag", any(), any()) }
        coVerify(exactly = 1) { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) }
    }
}
