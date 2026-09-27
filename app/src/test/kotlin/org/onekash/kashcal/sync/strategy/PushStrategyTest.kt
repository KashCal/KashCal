package org.onekash.kashcal.sync.strategy

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

class PushStrategyTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var attendeesDao: org.onekash.kashcal.data.db.dao.AttendeesDao
    private lateinit var pendingCancelsDao: org.onekash.kashcal.data.db.dao.PendingCancelsDao
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

    /** What a GET of [event]'s resource returns after another client changed it. */
    private fun serverCopy(event: Event, etag: String) = CalDavResult.success(
        CalDavEvent(
            href = event.caldavUrl!!, url = event.caldavUrl!!, etag = etag,
            icalData = IcsPatcher.serialize(event.copy(description = "Changed elsewhere"), null)
        )
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
        accountRepository = mockk()
        attendeesDao = mockk()
        pendingCancelsDao = mockk()

        // Batch loads return nothing by default, so the push falls back to per-id reads;
        // batch tests override them.
        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()
        // The push loads attendees before serializing: none by default, attendee tests
        // override.
        coEvery { attendeesDao.getForEventOnce(any()) } returns emptyList()
        // The cancel drain reads pending_cancels: empty by default, removal tests override
        // getForEvent.
        coEvery { pendingCancelsDao.getForEvent(any()) } returns emptyList()

        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            accountRepository = accountRepository,
            attendeesDao = attendeesDao,
            pendingCancelsDao = pendingCancelsDao
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(testEvent.id, serverUrl, serverEtag, any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsCreated == 1)
        assert(success.eventsUpdated == 0)
        assert(success.eventsDeleted == 0)
        assert(success.operationsFailed == 0)

        coVerify { eventsDao.markCreatedOnServerWithCopy(testEvent.id, serverUrl, serverEtag, any(), any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `create re-points bundled exceptions at the master's new server url`() = runTest {
        // An exception rides in its master's server resource, so the master's new url is
        // the exception's too. An exception left on a stale url is classified server-deleted
        // by a later pull of this calendar and reaped, so a cross-account move would lose the
        // changed occurrence.
        val master = testEvent.copy(rrule = "FREQ=WEEKLY")
        val exception = testEvent.copy(
            id = 201L,
            originalEventId = master.id,
            originalInstanceTime = master.startTs,
            rrule = null,
            caldavUrl = "https://old.example/source-account/series.ics",
            syncStatus = SyncStatus.SYNCED
        )
        val operation = PendingOperation(
            id = 1L,
            eventId = master.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverUrl = "${testCalendar.caldavUrl}${master.uid}.ics"
        val serverEtag = "etag-new-123"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getByIds(any()) } returns listOf(master)
        coEvery { eventsDao.getById(master.id) } returns master
        coEvery { calendarRepository.getCalendarById(master.calendarId) } returns testCalendar
        coEvery { eventsDao.getExceptionsForMaster(master.id) } returns listOf(exception)
        coEvery { client.createEvent(eq(testCalendar.caldavUrl), eq(master.uid), any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        // One atomic write carries both the new url and the new etag.
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(exception.id, serverUrl, serverEtag, any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSynced(exception.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSyncedWithCopy(exception.id, any(), any(), any()) }
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(master.id, serverUrl, serverEtag, any(), any()) }
    }

    @Test
    fun `create leaves an exception that was not serialized untouched`() = runTest {
        // Race guard: an exception created while the push was in flight was not in
        // the pushed body, so it must get neither the new etag nor the new url.
        val master = testEvent.copy(rrule = "FREQ=WEEKLY")
        val pushedException = testEvent.copy(
            id = 201L,
            originalEventId = master.id,
            originalInstanceTime = master.startTs,
            rrule = null,
            syncStatus = SyncStatus.SYNCED
        )
        val lateException = pushedException.copy(id = 202L)
        val operation = PendingOperation(
            id = 1L,
            eventId = master.id,
            operation = PendingOperation.OPERATION_CREATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverUrl = "${testCalendar.caldavUrl}${master.uid}.ics"
        val serverEtag = "etag-new-123"

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getByIds(any()) } returns listOf(master)
        coEvery { eventsDao.getById(master.id) } returns master
        coEvery { calendarRepository.getCalendarById(master.calendarId) } returns testCalendar
        // Only the first exception existed at serialize time; the late one appears
        // on any subsequent query. An implementation that re-queried instead of
        // reusing the serialized set would therefore mark it synced.
        coEvery { eventsDao.getExceptionsForMaster(master.id) } returnsMany listOf(
            listOf(pushedException),
            listOf(pushedException, lateException)
        )
        coEvery { client.createEvent(eq(testCalendar.caldavUrl), eq(master.uid), any()) } returns
            CalDavResult.success(Pair(serverUrl, serverEtag))
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        pushStrategy.pushForCalendar(testCalendar, client)

        coVerify(exactly = 0) { eventsDao.markCreatedOnServer(lateException.id, any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markCreatedOnServerWithCopy(lateException.id, any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSynced(lateException.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSyncedWithCopy(lateException.id, any(), any(), any()) }
        // The pushed one still adopts the url, including from a null starting value
        // (this fixture's exception has never been on a server).
        assertEquals(null, pushedException.caldavUrl)
        coVerify(exactly = 1) { eventsDao.markCreatedOnServerWithCopy(pushedException.id, serverUrl, serverEtag, any(), any()) }
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
        coEvery { eventsDao.markSyncedWithCopy(eventWithUrl.id, newEtag, any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0)

        coVerify { eventsDao.markSyncedWithCopy(eventWithUrl.id, newEtag, any(), any()) }
    }

    @Test
    fun `pushAll preserves rawIcal attendees when the attendee table is empty`() = runTest {
        // An event synced before the attendees table existed, or whose unchanged etag kept
        // the pull from backfilling it, has its ATTENDEEs only in rawIcal, and
        // getForEventOnce returns empty. A cosmetic edit must not clear them on the wire: an
        // empty table isn't an authoritative "no attendees".
        val rawIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:preserve-attendees@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Old Title
            ORGANIZER;CN=Boss:mailto:boss@example.com
            ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED:mailto:jane@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val eventWithUrl = testEvent.copy(
            uid = "preserve-attendees@kashcal.test",
            title = "New Title", // cosmetic edit
            caldavUrl = "https://caldav.icloud.com/123/calendar/preserve.ics",
            etag = "etag-old", rawIcal = rawIcal, syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 9L, eventId = eventWithUrl.id,
            operation = PendingOperation.OPERATION_UPDATE, status = PendingOperation.STATUS_PENDING
        )
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventWithUrl.id) } returns eventWithUrl
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // Default attendeesDao.getForEventOnce → emptyList (the empty-table case).
        val bodySlot = slot<String>()
        coEvery { client.updateEvent(any(), capture(bodySlot), any()) } returns CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        assert(bodySlot.isCaptured) { "expected a PUT body" }
        assert(bodySlot.captured.contains("jane@example.com")) {
            "empty table must NOT strip the rawIcal attendee on a cosmetic edit:\n${bodySlot.captured}"
        }
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
        // The refetch finds the resource gone: left to conflict resolution, never re-created.
        coEvery { client.fetchEvent(any()) } returns CalDavResult.notFoundError("Event not found")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
        coVerify(exactly = 1) { client.updateEvent(any(), any(), any()) }
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
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
        // Event has no caldavUrl, so it is only deleted locally
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

        // No server delete
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        // Still deleted locally
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

    // ========== DELETE 412 Conflict Retry ==========
    //
    // A scheduling object's ETag changes when the server auto-processes an attendee
    // reply (RFC 6638 §3.2.10 keeps the schedule-tag unchanged). A DELETE with the old
    // ETag then 412s though nothing the user cares about changed. The delete refetches
    // the ETag and retries once, as the UPDATE path does, instead of rescheduling
    // forever with the stale ETag.

    @Test
    fun `pushAll retries delete with fresh etag on 412 conflict`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        // First DELETE with the stale etag: 412.
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // Refetch: fresh etag.
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        // Retry DELETE with the fresh etag: success. Stubbed only for the fresh etag, so a
        // retry reusing the stale one hits no stub and fails the test.
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-fresh")) } returns
            CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(eventWithUrl.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals("delete should have succeeded on retry", 1, success.eventsDeleted)
        assertEquals("no failures expected", 0, success.operationsFailed)

        // Retry sequence: first delete (412) → refetch → second delete (success).
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-stale")) }
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-fresh")) }
        coVerify { eventsDao.deleteById(eventWithUrl.id) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `pushAll delete retry is bounded to exactly one retry`() = runTest {
        // Every DELETE 412s and every refetch returns a new etag. The delete runs twice
        // (first plus one retry) and the refetch once, then the op defers; one push never
        // loops.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        // Any etag → 412 (server keeps re-drifting).
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals("re-drift should surface as one failed op", 1, (result as PushResult.Success).operationsFailed)

        coVerify(exactly = 2) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        // Not deleted locally: it still exists on the server.
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        // Deferred to the normal conflict reschedule path.
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete falls back to conflict when refetch fails on 412`() = runTest {
        // A refetch network failure leaves no etag to retry with: the op is rescheduled as a
        // conflict, with no local delete and no loop.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.networkError("Connection failed")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // The refetch ran but failed, so only the first delete ran and no retry followed.
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete treats refetch 404 as already deleted`() = runTest {
        // The resource is removed elsewhere between the 412'd DELETE and the refetch. A 404
        // on refetch means it is gone, which is what the user asked for, so the row is
        // deleted locally.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // Refetch says the resource is gone.
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.notFoundError("Event not found")
        coEvery { eventsDao.deleteById(eventWithUrl.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals("gone-on-refetch counts as a completed delete", 1, (result as PushResult.Success).eventsDeleted)

        // No second delete: the refetch showed it is gone.
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify { eventsDao.deleteById(eventWithUrl.id) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `pushAll delete falls back to conflict when refetch returns no etag`() = runTest {
        // Server answers PROPFIND 207 but omits <getetag> (some CDN/edge cases). Without an
        // etag there is nothing to retry with: defer, don't delete, don't loop.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag succeeds but with a null payload (no getetag element parsed).
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success(null)
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // The refetch gave no usable etag, so no retry delete followed and the op deferred.
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete falls back to conflict when refetch returns empty etag`() = runTest {
        // Some servers, e.g. Zoho, return "" instead of a real validator. An empty etag is
        // treated like a missing one: the op defers, with no retry delete carrying an empty
        // If-Match. Distinguishes isNullOrEmpty() from == null.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag succeeds but returns an empty string.
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        // Refetch ran but gave an empty etag: no retry delete, the op defers.
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete retry permanent error marks failed instead of conflict`() = runTest {
        // The first delete 412s, the refetch succeeds, and the retry delete hits a
        // permanent error (here an auth error, 401). That isn't a benign conflict: it
        // surfaces as a non-retryable Error, so the op is marked failed at once instead of
        // rescheduled as a conflict for its 30-day lifetime.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        // Retry with the fresh etag hits a permanent (non-retryable) error.
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-fresh")) } returns
            CalDavResult.authError("Forbidden")
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-fresh")) }
        // Permanent error: marked failed, not rescheduled as a conflict, not deleted locally.
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `pushAll delete retry transient error reschedules with error not generic conflict`() = runTest {
        // The retry delete hits a transient network error. The op is rescheduled with the
        // real error message and not marked failed, which tells a real failure apart from a
        // benign re-conflict.
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-fresh")) } returns
            CalDavResult.networkError("Connection reset")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).operationsFailed)

        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-fresh")) }
        // Transient error: rescheduled with the real message, not the generic "Conflict"
        // string, and not marked failed.
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Connection reset") }, any()) }
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    // ========== DELETE after a calendar move: row ownership ==========
    //
    // A move reuses the event's row for the destination copy and queues a DELETE keyed
    // on the same id. The DELETE owns the source-collection resource, not the row, so
    // once the row has left sourceCalendarId it isn't reaped; otherwise the server copy
    // is removed and the moved event destroyed (#365). A user delete soft-deletes to
    // PENDING_DELETE first, which tells it apart from a moved row. The rule lives in
    // `PushStrategy.deleteOwnsLocalRow`.

    /** Source calendar the move originated from; matches testCalendar.id. */
    private val sourceCalendarId = 1L
    private val movedSourceUrl = "https://caldav.icloud.com/123/calendar/test-event.ics"

    private fun moveOriginDeleteOp(
        eventId: Long = testEvent.id,
        targetUrl: String? = movedSourceUrl,
        linkedMoveId: String? = null
    ) = PendingOperation(
        id = 3L,
        eventId = eventId,
        operation = PendingOperation.OPERATION_DELETE,
        status = PendingOperation.STATUS_PENDING,
        targetUrl = targetUrl,
        sourceCalendarId = sourceCalendarId,
        linkedMoveId = linkedMoveId
    )

    @Test
    fun `delete after move to local calendar removes server copy but keeps the moved row`() = runTest {
        // #365: the row now lives in a local calendar with its server identity
        // cleared. The queued DELETE still carries the old server URL.
        val movedEvent = testEvent.copy(
            calendarId = 9L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs
        // deleteById deliberately not stubbed: strict mockk throws if it is called.

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals("server copy should still be deleted", 1, success.eventsDeleted)
        assertEquals("no failures expected", 0, success.operationsFailed)

        coVerify(exactly = 1) { client.deleteEvent(movedSourceUrl, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `delete after cross-account move keeps the row now living on the target account`() = runTest {
        // The linked CREATE already re-pointed this row at the target account.
        // Reaping it here would destroy the copy that was just created.
        val movedEvent = testEvent.copy(
            calendarId = 7L,
            caldavUrl = "https://other.example/cal/moved.ics",
            etag = "new-etag",
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp(linkedMoveId = "move-1")

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        // The old url is deleted, never the row's current (destination) url.
        coVerify(exactly = 1) { client.deleteEvent(movedSourceUrl, any()) }
        coVerify(exactly = 0) { client.deleteEvent(movedEvent.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `delete with no source calendar scope still reaps a row that is not a tombstone`() = runTest {
        // Nothing says this unscoped op's row was moved, so the DELETE still owns it. The
        // mixed-operations test relies on this: it drives a PENDING_CREATE row through the
        // DELETE path.
        val eventWithUrl = testEvent.copy(
            caldavUrl = movedSourceUrl,
            etag = "etag-123",
            syncStatus = SyncStatus.PENDING_CREATE
        )
        val operation = PendingOperation(
            id = 3L,
            eventId = eventWithUrl.id,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        // With no sourceCalendarId the op is routed to a calendar by the row's own
        // calendarId, which comes from the batch-loaded cache.
        coEvery { eventsDao.getByIds(any()) } returns listOf(eventWithUrl)
        coEvery { eventsDao.getById(eventWithUrl.id) } returns eventWithUrl
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(eventWithUrl.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        pushStrategy.pushForCalendar(testCalendar, client)

        coVerify(exactly = 1) { eventsDao.deleteById(eventWithUrl.id) }
    }

    @Test
    fun `delete still reaps a tombstone row whose op inherited a stale source calendar`() = runTest {
        // Queueing updates an existing pending op in place instead of inserting, so a real
        // delete can inherit a prior move's sourceCalendarId. The row is a tombstone, so it
        // must still be reaped: scoping alone would leave an invisible row nothing cleans
        // up, since the pull skips PENDING_DELETE rows.
        val tombstone = testEvent.copy(
            calendarId = 7L,
            caldavUrl = movedSourceUrl,
            etag = "etag-123",
            syncStatus = SyncStatus.PENDING_DELETE
        )
        val operation = moveOriginDeleteOp()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(tombstone.id) } returns tombstone
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(tombstone.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        pushStrategy.pushForCalendar(testCalendar, client)

        coVerify(exactly = 1) { eventsDao.deleteById(tombstone.id) }
    }

    @Test
    fun `delete after move keeps the row when the server reports 404`() = runTest {
        val movedEvent = testEvent.copy(
            calendarId = 9L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns
            CalDavResult.notFoundError("Not found")
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `delete after move keeps the row when a 412 refetch shows the resource is gone`() = runTest {
        val movedEvent = testEvent.copy(
            calendarId = 9L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEtag(movedSourceUrl) } returns CalDavResult.notFoundError("Gone")
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `delete after move keeps the row when the 412 retry with a fresh etag succeeds`() = runTest {
        val movedEvent = testEvent.copy(
            calendarId = 9L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { client.deleteEvent(movedSourceUrl, eq("")) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEtag(movedSourceUrl) } returns CalDavResult.success("etag-fresh")
        coEvery { client.deleteEvent(movedSourceUrl, eq("etag-fresh")) } returns
            CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        coVerify(exactly = 1) { client.deleteEvent(movedSourceUrl, eq("etag-fresh")) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `delete after move keeps the row when there is no url to delete`() = runTest {
        // A move-origin op with no captured targetUrl against a row whose server identity
        // is already cleared. Nothing to delete on the server, and the row isn't the
        // DELETE's to reap.
        val movedEvent = testEvent.copy(
            calendarId = 9L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp(targetUrl = null)

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
    }

    @Test
    fun `spared row is reported as recently pushed so the same cycle's pull skips it`() = runTest {
        val movedEvent = testEvent.copy(
            calendarId = 9L,
            caldavUrl = null,
            etag = null,
            syncStatus = SyncStatus.SYNCED
        )
        val operation = moveOriginDeleteOp()

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(movedEvent.id) } returns movedEvent
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        val success = result as PushResult.Success
        assertTrue(
            "spared row must be protected from the pull phase of this same cycle",
            movedEvent.id in success.pushedEventIds
        )
    }

    @Test
    fun `a genuinely reaped row is not reported as recently pushed`() = runTest {
        val tombstone = testEvent.copy(
            caldavUrl = movedSourceUrl,
            etag = "etag-123",
            syncStatus = SyncStatus.PENDING_DELETE
        )
        val operation = PendingOperation(
            id = 3L,
            eventId = tombstone.id,
            operation = PendingOperation.OPERATION_DELETE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getByIds(any()) } returns listOf(tombstone)
        coEvery { eventsDao.getById(tombstone.id) } returns tombstone
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(tombstone.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        val success = result as PushResult.Success
        assertTrue(
            "a deleted row has nothing to protect",
            success.pushedEventIds.isEmpty()
        )
    }

    @Test
    fun `delete decides ownership from current state, not the batch snapshot`() = runTest {
        // The push loop snapshots rows before the network round-trip. If the user moves the
        // event out of the source calendar while the server DELETE is in flight, the
        // snapshot still shows it in the source calendar, and deciding from it would destroy
        // the row the move just re-pointed.
        val snapshot = testEvent.copy(
            calendarId = sourceCalendarId,
            caldavUrl = movedSourceUrl,
            syncStatus = SyncStatus.SYNCED
        )
        val afterMove = snapshot.copy(calendarId = 9L)
        val operation = moveOriginDeleteOp(snapshot.id, movedSourceUrl)

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getByIds(any()) } returns listOf(snapshot)
        // Re-read sees the committed move.
        coEvery { eventsDao.getById(snapshot.id) } returns afterMove
        coEvery { client.deleteEvent(movedSourceUrl, any()) } returns CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assertTrue(result is PushResult.Success)
        coVerify(exactly = 1) { client.deleteEvent(movedSourceUrl, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        assertTrue(snapshot.id in (result as PushResult.Success).pushedEventIds)
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
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

        // A retryable failure (retried next sync) is a warning, not an error.
        val success = result as PushResult.Success
        assertEquals("retryable failure should be a warning", 1, success.pushWarnings.size)
        assertTrue("retryable failure must NOT be an error", success.pushErrors.isEmpty())
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

        // A permanently failed push (change lost, no retry) is an error, not a warning.
        val success = result as PushResult.Success
        assertEquals("permanent failure should be an error", 1, success.pushErrors.size)
        assertTrue("permanent failure must NOT be a warning", success.pushWarnings.isEmpty())
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
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // The bundled exception adopts the master's resource url and etag (v14.2.20).
        coVerify { eventsDao.markCreatedOnServerWithCopy(exceptionEvent.id, "url", "etag", any(), any()) }
    }

    @Test
    fun `pushAll emits master AND per-exception attendees on the wire`() = runTest {
        // Organizer push of a recurring series: the master's attendees and each exception
        // VEVENT's own attendees must round-trip. The serializer emits ATTENDEEs only with
        // an ORGANIZER (`EventToICalEventMapper.attendeesIfOrganized`), which a real
        // organizer push resolves, so the fixture carries one.
        val masterEvent = testEvent.copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            originalEventId = null,
            organizerEmail = "host@example.test",
            rawIcal = null // locally created → fresh generation path
        )
        val exceptionEvent = testEvent.copy(
            id = 101L,
            originalEventId = masterEvent.id,
            originalInstanceTime = System.currentTimeMillis(),
            rrule = null,
            organizerEmail = "host@example.test",
            rawIcal = null
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
        coEvery { attendeesDao.getForEventOnce(masterEvent.id) } returns listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = masterEvent.id, address = "mailto:alice@example.test", partstat = "ACCEPTED"
            )
        )
        coEvery { attendeesDao.getForEventOnce(exceptionEvent.id) } returns listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = exceptionEvent.id, address = "mailto:carol@example.test", partstat = "NEEDS-ACTION"
            )
        )
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val bodySlot = slot<String>()
        coEvery {
            client.createEvent(any(), any(), capture(bodySlot))
        } returns CalDavResult.success(Pair("url", "etag"))

        pushStrategy.pushAll(client)

        val body = bodySlot.captured
        assertTrue("master attendee alice must be on the wire", body.contains("alice@example.test"))
        assertTrue("exception attendee carol must be on the wire", body.contains("carol@example.test"))
    }

    @Test
    fun `pushAll skips exception events - they are bundled with master`() = runTest {
        // An exception event (originalEventId set) is skipped: it is pushed inside its
        // master's body (`IcsPatcher.serializeWithExceptions`).
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

        // Succeeds without calling the server
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        // Counts as created since operation succeeded (no-op is success)
        assert(success.eventsCreated == 1)

        // No server calls
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { calendarRepository.getCalendarById(any()) }
    }

    @Test
    fun `pushAll skips exception events for UPDATE operations`() = runTest {
        // An exception is skipped for UPDATE too; the master's UPDATE carries every
        // exception (`IcsPatcher.serializeWithExceptions`).
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

        // No server calls
        coVerify(exactly = 0) { client.updateEvent(any(), any(), any()) }
    }

    @Test
    fun `pushAll processes master UPDATE and includes all exceptions`() = runTest {
        // A master's UPDATE includes all its exceptions
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
        // markSyncedWithCopy stores the master's etag and body; exceptions go through
        // markCreatedOnServerWithCopy
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)

        // Verify update was called with correct URL and etag
        coVerify { client.updateEvent(masterEvent.caldavUrl!!, any(), masterEvent.etag!!) }
        // Verify master etag was updated
        coVerify { eventsDao.markSyncedWithCopy(masterEvent.id, "new-etag", any(), any()) }
        // Each exception adopts the master's url and new etag
        coVerify { eventsDao.markCreatedOnServerWithCopy(exception1.id, "https://caldav.icloud.com/123/calendar/master.ics", "new-etag", any(), any()) }
        coVerify { eventsDao.markCreatedOnServerWithCopy(exception2.id, "https://caldav.icloud.com/123/calendar/master.ics", "new-etag", any(), any()) }
    }

    // ========== 412 Conflict Retry (v22.5.6) ==========

    @Test
    fun `pushAll retries a 412 update once with the etag of the copy it refetched`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { calendarRepository.getCalendarById(any()) } returns null
        // First PUT with stale etag → 412
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // The server's current copy, changed elsewhere
        coEvery { client.fetchEvent(eventWithUrl.caldavUrl!!) } returns serverCopy(eventWithUrl, "etag-fresh")
        // Retry PUT with the refetched copy's etag → success
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-fresh")) } returns
            CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(eventWithUrl.id, "etag-stale", any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0) { "Expected 0 failures but got ${success.operationsFailed}" }
        assertTrue("the same cycle's pull fetches the merged copy", eventWithUrl.id in success.refetchEventIds)

        // The row keeps its old etag and body, and no etag is stored before the retry.
        coVerify { eventsDao.markSynced(eventWithUrl.id, "etag-stale", any()) }
        coVerify(exactly = 0) { eventsDao.updateEtag(any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `a 412 whose refetch fails on the network is retried later, not left to conflict resolution`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { calendarRepository.getCalendarById(any()) } returns null
        coEvery { client.updateEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEvent(any()) } returns CalDavResult.networkError("Connection failed")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // Rescheduled with an error conflict resolution doesn't pick up, with no second PUT and
        // no etag stored.
        coVerify {
            pendingOperationsDao.scheduleRetry(operation.id, any(), match { "Conflict" !in it && "412" !in it }, any())
        }
        coVerify(exactly = 1) { client.updateEvent(any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.updateEtag(any(), any()) }
    }

    @Test
    fun `pushAll falls back to conflict when retry also gets 412`() = runTest {
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { calendarRepository.getCalendarById(any()) } returns null
        // First PUT → 412
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEvent(eventWithUrl.caldavUrl!!) } returns serverCopy(eventWithUrl, "etag-fresh")
        // Retry PUT → also 412 (another concurrent edit)
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-fresh")) } returns
            CalDavResult.conflictError("Modified again")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        coVerify(exactly = 2) { client.updateEvent(any(), any(), any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `a merged retry keeps the old etag on the series and on each bundled exception`() = runTest {
        val masterEvent = testEvent.copy(
            id = 200L,
            rrule = "FREQ=DAILY;COUNT=5",
            originalEventId = null,
            caldavUrl = "https://caldav.icloud.com/123/calendar/master.ics",
            etag = "etag-stale"
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
        // First PUT: 412
        coEvery { client.updateEvent(masterEvent.caldavUrl!!, any(), eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { calendarRepository.getCalendarById(any()) } returns null
        // The server's current copy holds the series and both exceptions.
        coEvery { client.fetchEvent(masterEvent.caldavUrl!!) } returns CalDavResult.success(
            CalDavEvent(
                href = masterEvent.caldavUrl!!, url = masterEvent.caldavUrl!!, etag = "etag-fresh",
                icalData = IcsPatcher.serializeWithExceptions(masterEvent, listOf(exception1, exception2))
            )
        )
        // Retry: success
        coEvery { client.updateEvent(masterEvent.caldavUrl!!, any(), eq("etag-fresh")) } returns
            CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 0)

        // The series keeps its old etag and both exceptions take it, at the master's resource
        // url (which is also theirs), so the pull fetches the merged copy whichever row it reads.
        coVerify { eventsDao.markSynced(masterEvent.id, "etag-stale", any()) }
        coVerify { eventsDao.markCreatedOnServer(exception1.id, "https://caldav.icloud.com/123/calendar/master.ics", "etag-stale", any()) }
        coVerify { eventsDao.markCreatedOnServer(exception2.id, "https://caldav.icloud.com/123/calendar/master.ics", "etag-stale", any()) }
    }

    @Test
    fun `a refetched copy still carrying the refused etag defers after one retry`() = runTest {
        // CDN staleness: the GET returns the etag that caused the 412
        val eventWithUrl = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "etag-stale",
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
        coEvery { calendarRepository.getCalendarById(any()) } returns null
        // All PUTs with any etag → 412
        coEvery { client.updateEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("Modified on server")
        coEvery { client.fetchEvent(eventWithUrl.caldavUrl!!) } returns serverCopy(eventWithUrl, "etag-stale")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // One retry, which also got 412, then conflict
        coVerify(exactly = 2) { client.updateEvent(any(), any(), any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    // ========== Null Etag PROPFIND Recovery (v23.2.0) ==========

    @Test
    fun `pushAll recovers null etag via PROPFIND and updates successfully`() = runTest {
        // Given: event with caldavUrl but etag=null (server omitted <getetag> during pull)
        val eventNullEtag = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventNullEtag.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventNullEtag.id) } returns eventNullEtag
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // PROPFIND recovers the etag
        coEvery { client.fetchEtag(eventNullEtag.caldavUrl!!) } returns CalDavResult.success("recovered-etag")
        coEvery { eventsDao.updateEtag(eventNullEtag.id, "recovered-etag") } just Runs
        // PUT with recovered etag succeeds
        coEvery { client.updateEvent(eventNullEtag.caldavUrl!!, any(), eq("recovered-etag")) } returns
            CalDavResult.success("new-etag")
        coEvery { eventsDao.markSynced(eventNullEtag.id, "new-etag", any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(eventNullEtag.id, "new-etag", any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0) { "Expected 0 failures but got ${success.operationsFailed}" }

        // Verify PROPFIND was called to recover etag
        coVerify { client.fetchEtag(eventNullEtag.caldavUrl!!) }
        // Verify recovered etag was persisted to DB
        coVerify { eventsDao.updateEtag(eventNullEtag.id, "recovered-etag") }
        // Verify PUT used recovered etag
        coVerify { client.updateEvent(eventNullEtag.caldavUrl!!, any(), eq("recovered-etag")) }
        // Verify final markSyncedWithCopy
        coVerify { eventsDao.markSyncedWithCopy(eventNullEtag.id, "new-etag", any(), any()) }
    }

    @Test
    fun `pushAll retries when null etag and PROPFIND fails with network error`() = runTest {
        // Given: event with caldavUrl but etag=null, PROPFIND returns networkError
        // (isRetryable=true). The op keeps the error's retryability.
        val eventNullEtag = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventNullEtag.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventNullEtag.id) } returns eventNullEtag
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // PROPFIND fails with network error (should be retryable)
        coEvery { client.fetchEtag(eventNullEtag.caldavUrl!!) } returns CalDavResult.networkError("Connection failed")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // Verify PROPFIND was attempted
        coVerify { client.fetchEtag(eventNullEtag.caldavUrl!!) }
        // Verify scheduleRetry was called (a network error is retryable)
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), any(), any()) }
        // Verify markFailed was not called
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
        // Verify no updateEvent call was made (can't update without etag)
        coVerify(exactly = 0) { client.updateEvent(any(), any(), any()) }
    }

    @Test
    fun `pushAll fails permanently when null etag and PROPFIND fails with auth error`() = runTest {
        // Given: event with caldavUrl but etag=null, PROPFIND returns authError (isRetryable=false)
        // Auth errors aren't retried: they need user intervention
        val eventNullEtag = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventNullEtag.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventNullEtag.id) } returns eventNullEtag
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // PROPFIND fails with auth error (not retryable)
        coEvery { client.fetchEtag(eventNullEtag.caldavUrl!!) } returns CalDavResult.authError("Invalid credentials")
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // Verify PROPFIND was attempted
        coVerify { client.fetchEtag(eventNullEtag.caldavUrl!!) }
        // Verify markFailed was called (an auth error is not retryable)
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
        // Verify scheduleRetry was not called
        coVerify(exactly = 0) { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
    }

    @Test
    fun `pushAll triggers PROPFIND when etag is empty string`() = runTest {
        // Given: event with empty string etag (edge case from Zoho servers). The recovery
        // checks isNullOrEmpty(), not only `!= null`.
        val eventEmptyEtag = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "",  // Empty string, not null
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventEmptyEtag.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { eventsDao.getById(eventEmptyEtag.id) } returns eventEmptyEtag
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // PROPFIND recovers the etag
        coEvery { client.fetchEtag(eventEmptyEtag.caldavUrl!!) } returns CalDavResult.success("recovered-etag")
        coEvery { eventsDao.updateEtag(eventEmptyEtag.id, "recovered-etag") } just Runs
        // PUT with recovered etag succeeds
        coEvery { client.updateEvent(eventEmptyEtag.caldavUrl!!, any(), eq("recovered-etag")) } returns
            CalDavResult.success("new-etag")
        coEvery { eventsDao.markSynced(eventEmptyEtag.id, "new-etag", any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(eventEmptyEtag.id, "new-etag", any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1) { "Expected 1 update but got ${success.eventsUpdated}" }
        assert(success.operationsFailed == 0) { "Expected 0 failures but got ${success.operationsFailed}" }

        // Verify PROPFIND was called (empty string should trigger recovery like null)
        coVerify { client.fetchEtag(eventEmptyEtag.caldavUrl!!) }
        // Verify recovered etag was persisted to DB
        coVerify { eventsDao.updateEtag(eventEmptyEtag.id, "recovered-etag") }
        // Verify PUT used recovered etag
        coVerify { client.updateEvent(eventEmptyEtag.caldavUrl!!, any(), eq("recovered-etag")) }
    }

    @Test
    fun `pushAll with empty etag and PROPFIND network error schedules retry`() = runTest {
        // Empty string etag plus a PROPFIND network failure: the op is rescheduled, not
        // failed.
        val eventEmptyEtag = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = "",  // Empty string
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventEmptyEtag.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventEmptyEtag.id) } returns eventEmptyEtag
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // PROPFIND fails with network error
        coEvery { client.fetchEtag(eventEmptyEtag.caldavUrl!!) } returns CalDavResult.networkError("Timeout")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)

        // Empty etag should trigger PROPFIND
        coVerify { client.fetchEtag(eventEmptyEtag.caldavUrl!!) }
        // Network error should schedule retry
        coVerify { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
        // Not marked failed
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
    }

    @Test
    fun `pushAll recovers null etag via PROPFIND then handles 412 retry`() = runTest {
        // Given: null etag, PROPFIND recovers it, the PUT gets 412, then the merged retry
        val eventNullEtag = testEvent.copy(
            caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
            etag = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 2L,
            eventId = eventNullEtag.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(eventNullEtag.id) } returns eventNullEtag
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        // PROPFIND recovers the null etag: "recovered-etag"
        coEvery { client.fetchEtag(eventNullEtag.caldavUrl!!) } returns CalDavResult.success("recovered-etag")
        // The GET for the 412 retry: "fresh-etag"
        coEvery { client.fetchEvent(eventNullEtag.caldavUrl!!) } returns serverCopy(eventNullEtag, "fresh-etag")
        coEvery { calendarRepository.getCalendarById(any()) } returns null
        coEvery { eventsDao.updateEtag(eventNullEtag.id, any()) } just Runs
        // PUT with the recovered etag: 412
        coEvery { client.updateEvent(eventNullEtag.caldavUrl!!, any(), eq("recovered-etag")) } returns
            CalDavResult.conflictError("Modified on server")
        // 412 retry PUT with the fresh etag: success
        coEvery { client.updateEvent(eventNullEtag.caldavUrl!!, any(), eq("fresh-etag")) } returns
            CalDavResult.success("final-etag")
        coEvery { eventsDao.markSynced(eventNullEtag.id, "recovered-etag", any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0) { "Expected 0 failures but got ${success.operationsFailed}" }

        // The full sequence: PROPFIND, PUT (412), GET, PUT (success); the row keeps the
        // recovered etag so the pull fetches the merged copy.
        coVerify(exactly = 1) { client.fetchEtag(eventNullEtag.caldavUrl!!) }
        coVerify(exactly = 1) { client.fetchEvent(eventNullEtag.caldavUrl!!) }
        coVerify { eventsDao.markSynced(eventNullEtag.id, "recovered-etag", any()) }
    }

    // ========== Batch Query Optimization (v16.5.5) ==========

    @Test
    fun `pushForCalendar uses batch query instead of N+1`() = runTest {
        // Given: Multiple pending operations for different calendars
        val calendar1 = testCalendar.copy(id = 1L)
        val calendar2 = testCalendar.copy(id = 2L)

        val event1 = testEvent.copy(id = 1L, calendarId = 1L, caldavUrl = null)
        val event2 = testEvent.copy(id = 2L, calendarId = 1L, caldavUrl = null)
        val event3 = testEvent.copy(id = 3L, calendarId = 2L, caldavUrl = null)  // Other calendar

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
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs

        // When
        val result = pushStrategy.pushForCalendar(calendar1, client)

        // Then: getByIds called once (batch), getById never called
        coVerify(exactly = 1) { eventsDao.getByIds(any()) }
        coVerify(exactly = 0) { eventsDao.getById(any()) }

        // Should only have processed 2 events (for calendar1)
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsCreated == 2)
        assert(success.operationsProcessed == 2)
    }

    // ========== PARTSTAT-only RSVP write path ==========

    private val rsvpRawIcal = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//RSVP//EN
        BEGIN:VEVENT
        UID:rsvp-uid
        DTSTAMP:20260101T100000Z
        DTSTART:20260615T100000Z
        DTEND:20260615T110000Z
        SUMMARY:Quarterly review
        SEQUENCE:3
        ORGANIZER;CN=Boss:mailto:boss@example.test
        ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
        ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION:mailto:self@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun rsvpAccount() = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )

    @Test
    fun `pushAll partstat_only UPDATE uses patchAttendeeReply path with patched body`() = runTest {
        // The PARTSTAT-only branch patches only self's PARTSTAT in the stored rawIcal;
        // serializing the local event would lose the server's other attendees.
        val event = testEvent.copy(
            caldavUrl = "https://caldav.example.com/rsvp.ics",
            etag = "etag-old",
            rawIcal = rsvpRawIcal,
            calendarId = testCalendar.id,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 100L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            partstatOnly = true,
            partstatTarget = "ACCEPTED",
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(testCalendar.id) } returns testCalendar
        coEvery { accountRepository.getAccountById(testCalendar.accountId) } returns rsvpAccount()
        val sentBody = slot<String>()
        coEvery { client.updateEvent(eq(event.caldavUrl!!), capture(sentBody), eq(event.etag!!)) } returns
            CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(event.id, any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(event.id, any(), any(), any()) } just Runs
        val storedBody = slot<String>()
        coEvery {
            eventsDao.updateResourceCopy(event.id, event.caldavUrl!!, event.caldavUrl!!, "etag-new", capture(storedBody))
        } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)
        assertEquals("the body sent is stored with its etag", sentBody.captured, storedBody.captured)

        assert(result is PushResult.Success)
        // The body that hit the wire must contain the new PARTSTAT and preserve Alice.
        val body = sentBody.captured
        assertTrue("self PARTSTAT must update to ACCEPTED", body.contains("PARTSTAT=ACCEPTED"))
        assertTrue("Alice must survive", body.contains("alice@example.test"))
        assertTrue("Self mailto must survive", body.contains("self@example.test"))
        // SEQUENCE not bumped (RFC 5546 §2.1.4)
        assertTrue("SEQUENCE must remain at 3", body.contains("SEQUENCE:3"))
        // SUMMARY survives (the fixture has no DESCRIPTION)
        assertTrue("SUMMARY must survive", body.contains("Quarterly review"))
    }

    @Test
    fun `pushAll partstat_only UPDATE on 412 refetches body and retries`() = runTest {
        // On a 412, fetchEtag gives a fresh etag and fetchEvent a fresh body, which is
        // re-patched and retried once.
        val event = testEvent.copy(
            caldavUrl = "https://caldav.example.com/rsvp.ics",
            etag = "etag-old",
            rawIcal = rsvpRawIcal,
            calendarId = testCalendar.id,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 101L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            partstatOnly = true,
            partstatTarget = "TENTATIVE",
            status = PendingOperation.STATUS_PENDING
        )

        // The server's fresh body has a new attendee (Carol) the local copy lacks.
        val freshIcal = rsvpRawIcal.replace(
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION:mailto:self@example.test",
            "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION:mailto:self@example.test\nATTENDEE;CN=Carol;PARTSTAT=ACCEPTED:mailto:carol@example.test"
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(testCalendar.id) } returns testCalendar
        coEvery { accountRepository.getAccountById(testCalendar.accountId) } returns rsvpAccount()

        // First PUT 412.
        // Then fetchEtag returns "etag-fresh". fetchEvent returns the fresh body.
        // Retried PUT succeeds.
        var putCount = 0
        val putBodies = mutableListOf<String>()
        coEvery { client.updateEvent(any(), any(), any()) } answers {
            putCount++
            putBodies.add(secondArg())
            if (putCount == 1) CalDavResult.conflictError("Modified on server")
            else CalDavResult.success("etag-after-retry")
        }
        coEvery { client.fetchEtag(any()) } returns CalDavResult.success("etag-fresh")
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent("rsvp.ics", event.caldavUrl!!, "etag-fresh", freshIcal)
        )
        coEvery { eventsDao.updateEtag(event.id, "etag-fresh") } just Runs
        coEvery { eventsDao.markSynced(event.id, any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(event.id, any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)
        assert(result is PushResult.Success)
        assertEquals(2, putCount)

        // The retried body is patched from the fresh ICS, so Carol is present.
        val retryBody = putBodies[1]
        assertTrue("retry must include Carol from refreshed body", retryBody.contains("carol@example.test"))
        assertTrue("retry must reflect TENTATIVE PARTSTAT", retryBody.contains("PARTSTAT=TENTATIVE"))
    }

    @Test
    fun `pushAll partstat_only UPDATE on second 412 surfaces snackbar warning`() = runTest {
        // Two 412s in a row: an "event was modified" warning, no further retry.
        val event = testEvent.copy(
            caldavUrl = "https://caldav.example.com/rsvp.ics",
            etag = "etag-old",
            rawIcal = rsvpRawIcal,
            title = "Quarterly review",
            calendarId = testCalendar.id,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 102L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            partstatOnly = true,
            partstatTarget = "DECLINED",
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(testCalendar.id) } returns testCalendar
        coEvery { accountRepository.getAccountById(testCalendar.accountId) } returns rsvpAccount()
        coEvery { client.updateEvent(any(), any(), any()) } returns CalDavResult.conflictError("Modified")
        coEvery { client.fetchEtag(any()) } returns CalDavResult.success("etag-fresh")
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent("rsvp.ics", event.caldavUrl!!, "etag-fresh", rsvpRawIcal)
        )
        coEvery { eventsDao.updateEtag(event.id, any()) } just Runs
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)
        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assertEquals(1, success.operationsFailed)

        // The warning mentions RSVP (the event title too, not asserted here). Push warnings
        // are recorded on the sync session ([org.onekash.kashcal.sync.engine.CalDavSyncEngine]).
        val warning = success.pushWarnings.firstOrNull { it.contains("RSVP", ignoreCase = true) }
        assertTrue(
            "expected an RSVP-modified warning, got warnings: ${success.pushWarnings}",
            warning != null
        )
    }

    @Test
    fun `pushAll partstat_only UPDATE uses operation targetUrl when event caldavUrl was cleared`() = runTest {
        // The PUT goes to the URL the op captured at queue time, so it succeeds when
        // Event.caldavUrl is cleared between queue and drain. Otherwise a path that nulls
        // caldavUrl without clearing pending ops would lose the queued RSVP, and other
        // invitees would still see NEEDS-ACTION.
        val capturedUrl = "https://caldav.example.com/rsvp-captured.ics"
        val event = testEvent.copy(
            caldavUrl = null,            // cleared after queue insert
            etag = "etag-old",
            rawIcal = rsvpRawIcal,
            calendarId = testCalendar.id,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 200L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            partstatOnly = true,
            partstatTarget = "ACCEPTED",
            targetUrl = capturedUrl,     // captured at queue time
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(testCalendar.id) } returns testCalendar
        coEvery { accountRepository.getAccountById(testCalendar.accountId) } returns rsvpAccount()
        coEvery { client.updateEvent(eq(capturedUrl), any(), eq(event.etag!!)) } returns
            CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(event.id, any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(event.id, any(), any(), any()) } just Runs
        coEvery { eventsDao.updateResourceCopy(event.id, capturedUrl, capturedUrl, "etag-new", any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success) {
            "expected success when targetUrl carries the URL even with null caldavUrl, got $result"
        }
        // The stub matches only the captured URL (eq matcher), so a PUT to any other URL
        // fails the test.
        coVerify(exactly = 1) { client.updateEvent(eq(capturedUrl), any(), any()) }
    }

    @Test
    fun `pushAll partstat_only UPDATE 412 retry also uses operation targetUrl when caldavUrl is null`() = runTest {
        // With event.caldavUrl null, both PUTs, the 412 retry included, go to
        // operation.targetUrl.
        val capturedUrl = "https://caldav.example.com/rsvp-retry-captured.ics"
        val event = testEvent.copy(
            caldavUrl = null,
            etag = "etag-old",
            rawIcal = rsvpRawIcal,
            calendarId = testCalendar.id,
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val operation = PendingOperation(
            id = 201L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            partstatOnly = true,
            partstatTarget = "TENTATIVE",
            targetUrl = capturedUrl,
            status = PendingOperation.STATUS_PENDING
        )

        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(operation)
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(testCalendar.id) } returns testCalendar
        coEvery { accountRepository.getAccountById(testCalendar.accountId) } returns rsvpAccount()

        var putCount = 0
        coEvery { client.updateEvent(eq(capturedUrl), any(), any()) } answers {
            putCount++
            if (putCount == 1) CalDavResult.conflictError("Modified")
            else CalDavResult.success("etag-after-retry")
        }
        coEvery { client.fetchEtag(eq(capturedUrl)) } returns CalDavResult.success("etag-fresh")
        coEvery { client.fetchEvent(eq(capturedUrl)) } returns CalDavResult.success(
            CalDavEvent("rsvp.ics", capturedUrl, "etag-fresh", rsvpRawIcal)
        )
        coEvery { eventsDao.updateEtag(event.id, any()) } just Runs
        coEvery { eventsDao.markSynced(event.id, any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(event.id, any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)
        assert(result is PushResult.Success)
        assertEquals(2, putCount)
        // Both PUTs used capturedUrl: the stub above matches only it.
        coVerify(exactly = 2) { client.updateEvent(eq(capturedUrl), any(), any()) }
        coVerify(exactly = 1) { client.fetchEtag(eq(capturedUrl)) }
        coVerify(exactly = 1) { client.fetchEvent(eq(capturedUrl)) }
    }
}
