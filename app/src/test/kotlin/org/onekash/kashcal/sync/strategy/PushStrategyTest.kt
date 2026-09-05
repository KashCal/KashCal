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

        // Default batch query mocks - return empty so fallback to getById is used
        // Individual tests can override these for specific scenarios
        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()
        // Push path loads attendees before serialize; default to none so
        // existing tests are unaffected. Attendee-specific tests override.
        coEvery { attendeesDao.getForEventOnce(any()) } returns emptyList()
        // Cancel drain: default to an empty queue so existing tests are
        // unaffected; removal-specific tests override getForEvent.
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
    fun `create re-points bundled exceptions at the master's new server url`() = runTest {
        // An override rides in the same server resource as its master, so the
        // master's new url IS the exception's resource url. Leaving the exception
        // on a stale url makes a later pull of this calendar classify it as
        // server-deleted and reap it (which is how a cross-account move used to
        // lose the modified occurrence).
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
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushForCalendar(testCalendar, client)

        assert(result is PushResult.Success)
        // One atomic write carries both the new url and the new etag.
        coVerify(exactly = 1) { eventsDao.markCreatedOnServer(exception.id, serverUrl, serverEtag, any()) }
        coVerify(exactly = 0) { eventsDao.markSynced(exception.id, any(), any()) }
        coVerify(exactly = 1) { eventsDao.markCreatedOnServer(master.id, serverUrl, serverEtag, any()) }
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
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        pushStrategy.pushForCalendar(testCalendar, client)

        coVerify(exactly = 0) { eventsDao.markCreatedOnServer(lateException.id, any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.markSynced(lateException.id, any(), any()) }
        // The pushed one still adopts the url, including from a null starting value
        // (this fixture's exception has never been on a server).
        assertEquals(null, pushedException.caldavUrl)
        coVerify(exactly = 1) { eventsDao.markCreatedOnServer(pushedException.id, serverUrl, serverEtag, any()) }
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
    fun `pushAll preserves rawIcal attendees when the attendee table is empty`() = runTest {
        // Regression: an event synced before the attendees table existed (or
        // whose etag is unchanged so the pull-side backfill never ran) keeps its
        // ATTENDEEs only in rawIcal; getForEventOnce returns empty. Pushing a
        // cosmetic edit must NOT clear them on the wire (an empty table is not an
        // authoritative "no attendees" signal).
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
        // 412 retry: fetchEtag fails → falls through to conflict
        coEvery { client.fetchEtag(any()) } returns CalDavResult.networkError("Connection failed")
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

    // ========== DELETE 412 Conflict Retry ==========
    //
    // A scheduling object's ETag drifts asynchronously when the server
    // auto-processes an attendee reply (RFC 6638 §3.2.10 keeps the schedule-tag
    // stable but the ETag changes). A DELETE with the drifted ETag then 412s
    // even though nothing the user cares about changed. The delete path must
    // refetch the current ETag and retry once — the same self-heal the UPDATE
    // path already has — instead of rescheduling forever with the stale ETag.

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
        // First DELETE with the stale etag → 412.
        coEvery { client.deleteEvent(eventWithUrl.caldavUrl!!, eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // Refetch → fresh etag.
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        // Retry DELETE with the fresh etag → success. Stubbed on the fresh value
        // ONLY: an impl that reused the stale etag would hit no matching stub and
        // the test would fail — this proves the retry uses the refetched etag.
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
        // Adversarial: guard against an unbounded retry loop. Every DELETE 412s
        // and every refetch returns a (different) etag. The delete must be
        // attempted exactly twice total (first + one retry), the refetch exactly
        // once, then defer — never loop within a single push.
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
        // Not deleted locally — it still exists on the server.
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        // Deferred to the normal conflict reschedule path.
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete falls back to conflict when refetch fails on 412`() = runTest {
        // Refetch network-fails → no fresh etag to retry with → defer to the
        // existing reschedule path (do NOT delete locally, do NOT loop).
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

        // The refetch WAS attempted (proves we entered the new retry path, not
        // the old straight-to-conflict path) but failed, so only the first
        // delete ran and no retry followed.
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete treats refetch 404 as already deleted`() = runTest {
        // Adversarial race: the resource is removed elsewhere between our 412'd
        // DELETE and the refetch. A 404 on refetch means it is gone — the user's
        // intent (remove it) is satisfied; delete locally rather than re-freeze.
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

        // No second delete attempt — refetch already proved it is gone.
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify { eventsDao.deleteById(eventWithUrl.id) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `pushAll delete falls back to conflict when refetch returns no etag`() = runTest {
        // Server answers PROPFIND 207 but omits <getetag> (some CDN/edge cases).
        // Without an etag there is nothing to retry with — defer, don't delete,
        // don't loop.
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

        // The refetch was attempted (new path) but yielded no usable etag, so no
        // retry delete followed and the op deferred.
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete falls back to conflict when refetch returns empty etag`() = runTest {
        // Empty-string etag edge case (some servers, e.g. Zoho, return "" rather
        // than a real validator). An empty etag is not usable for a retry — the
        // guard must treat it like a missing etag and defer, NOT retry the delete
        // with an empty If-Match. Distinguishes isNullOrEmpty() from == null.
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

        // Refetch attempted, but empty etag → no retry delete, defer.
        coVerify(exactly = 1) { client.fetchEtag(eventWithUrl.caldavUrl!!) }
        coVerify(exactly = 1) { client.deleteEvent(eventWithUrl.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll delete retry permanent error marks failed instead of conflict`() = runTest {
        // The first delete 412s (drift), refetch succeeds, but the retry delete
        // hits a PERMANENT error (e.g. 403 auth). That is not a benign conflict:
        // it must surface as a non-retryable Error so the caller marks the
        // operation failed immediately, not reschedule it as a conflict for the
        // full 30-day lifetime.
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
        // Permanent error → marked failed, NOT rescheduled as a conflict, NOT deleted locally.
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
        coVerify(exactly = 0) { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `pushAll delete retry transient error reschedules with error not generic conflict`() = runTest {
        // The retry delete hits a TRANSIENT error (network). It should reschedule
        // (retryable) but carry the real error message, and must NOT be marked
        // failed. This distinguishes a real failure from a benign re-conflict.
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
        // Transient error → rescheduled (retryable) with the real message, NOT the
        // generic "Conflict" string, and NOT marked failed.
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Connection reset") }, any()) }
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    // ========== DELETE after a calendar move: row ownership ==========
    //
    // A move re-uses the event's row id for the destination copy while queueing a
    // DELETE keyed on that same id. The DELETE owns the source-collection resource,
    // not the row, so once the row has left sourceCalendarId the local reap must be
    // suppressed — otherwise the server copy is removed AND the moved event is
    // destroyed (#365). Genuine deletes soft-delete to PENDING_DELETE first, which
    // is how they stay distinguishable from a moved row.

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
        // deleteById deliberately NOT stubbed: strict mockk throws if it is called.

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
        // The OLD url is deleted, never the row's current (destination) url.
        coVerify(exactly = 1) { client.deleteEvent(movedSourceUrl, any()) }
        coVerify(exactly = 0) { client.deleteEvent(movedEvent.caldavUrl!!, any()) }
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `delete with no source calendar scope still reaps a row that is not a tombstone`() = runTest {
        // The unscoped-op case: nothing says this row was moved, so the DELETE
        // still owns it. Existing behavior depends on this — the mixed-operations
        // test drives a PENDING_CREATE row through the DELETE path.
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
        // queueOperation mutates an existing pending op in place rather than
        // inserting, so a genuine delete can inherit a prior MOVE's
        // sourceCalendarId. The row is a real tombstone, so it must still be
        // reaped — scoping alone would leave an invisible row that nothing
        // cleans up, since the pull path skips PENDING_DELETE rows.
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
        // Defensive: a move-origin op with no captured targetUrl against a row
        // whose server identity is already cleared. Nothing to delete on the
        // server, and the row is not ours to reap.
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
        // The push loop snapshots rows before the network round-trip. If the user
        // moves the event out of the source calendar while the server DELETE is in
        // flight, the snapshot still shows it in the source calendar — deciding from
        // it would destroy the row the move just re-pointed.
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

        // A retryable failure (will retry next sync) is a soft WARNING, not an error.
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

        // A permanently-failed push (change lost, no retry) is an ERROR, not a warning.
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
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // The bundled exception adopts the master's resource url and etag (v14.2.20).
        coVerify { eventsDao.markCreatedOnServer(exceptionEvent.id, "url", "etag", any()) }
    }

    @Test
    fun `pushAll emits master AND per-exception attendees on the wire`() = runTest {
        // Organizer push of a recurring series: the master's attendees AND each
        // exception VEVENT's own attendees must round-trip. Exception attendees
        // were silently dropped before the per-exception fix.
        // ATTENDEE requires ORGANIZER (RFC 6638 §3.1) — a real organizer push
        // resolves one, so the fixture carries it.
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
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
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
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
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
        coVerify { eventsDao.markCreatedOnServer(exception1.id, "https://caldav.icloud.com/123/calendar/master.ics", "new-etag", any()) }
        coVerify { eventsDao.markCreatedOnServer(exception2.id, "https://caldav.icloud.com/123/calendar/master.ics", "new-etag", any()) }
    }

    // ========== 412 Conflict Retry (v22.5.6) ==========

    @Test
    fun `pushAll retries update with fresh etag on 412 conflict`() = runTest {
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
        // First PUT with stale etag → 412
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag → fresh etag
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        coEvery { eventsDao.updateEtag(eventWithUrl.id, "etag-fresh") } just Runs
        // Retry PUT with fresh etag → success
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-fresh")) } returns
            CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(eventWithUrl.id, "etag-new", any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0) { "Expected 0 failures but got ${success.operationsFailed}" }

        // Verify the retry sequence
        coVerify { eventsDao.updateEtag(eventWithUrl.id, "etag-fresh") }
        coVerify { eventsDao.markSynced(eventWithUrl.id, "etag-new", any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `pushAll falls back to conflict when fetchEtag fails on 412`() = runTest {
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
        coEvery { client.updateEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag fails
        coEvery { client.fetchEtag(any()) } returns CalDavResult.networkError("Connection failed")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // Should fall through to normal conflict handling
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
        // Should NOT have tried updateEtag or second PUT
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
        // First PUT → 412
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag succeeds
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        coEvery { eventsDao.updateEtag(eventWithUrl.id, "etag-fresh") } just Runs
        // Retry PUT → also 412 (another concurrent edit)
        coEvery { client.updateEvent(eventWithUrl.caldavUrl!!, any(), eq("etag-fresh")) } returns
            CalDavResult.conflictError("Modified again")
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // updateEtag was called (intermediate step)
        coVerify { eventsDao.updateEtag(eventWithUrl.id, "etag-fresh") }
        // But ultimately fell through to conflict
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), match { it.contains("Conflict") }, any()) }
    }

    @Test
    fun `pushAll retry updates exception event etags on success`() = runTest {
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
        // First PUT → 412
        coEvery { client.updateEvent(masterEvent.caldavUrl!!, any(), eq("etag-stale")) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag → fresh
        coEvery { client.fetchEtag(masterEvent.caldavUrl!!) } returns CalDavResult.success("etag-fresh")
        coEvery { eventsDao.updateEtag(masterEvent.id, "etag-fresh") } just Runs
        // Retry → success
        coEvery { client.updateEvent(masterEvent.caldavUrl!!, any(), eq("etag-fresh")) } returns
            CalDavResult.success("etag-new")
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 0)

        // Verify master + both exceptions all picked up the retry's etag, the
        // exceptions against the master's resource url (which is also theirs).
        coVerify { eventsDao.markSynced(masterEvent.id, "etag-new", any()) }
        coVerify { eventsDao.markCreatedOnServer(exception1.id, "https://caldav.icloud.com/123/calendar/master.ics", "etag-new", any()) }
        coVerify { eventsDao.markCreatedOnServer(exception2.id, "https://caldav.icloud.com/123/calendar/master.ics", "etag-new", any()) }
    }

    @Test
    fun `pushAll falls back to conflict when fetchEtag returns same stale etag`() = runTest {
        // CDN staleness: fetchEtag returns the same etag that caused the 412
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
        // All PUTs with any etag → 412 (simulates stale CDN returning same etag)
        coEvery { client.updateEvent(any(), any(), any()) } returns
            CalDavResult.conflictError("Modified on server")
        // fetchEtag returns same stale etag (CDN hasn't caught up)
        coEvery { client.fetchEtag(eventWithUrl.caldavUrl!!) } returns CalDavResult.success("etag-stale")
        coEvery { eventsDao.updateEtag(eventWithUrl.id, "etag-stale") } just Runs
        coEvery { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // Retry was attempted with stale etag, also got 412, fell through to conflict
        coVerify { eventsDao.updateEtag(eventWithUrl.id, "etag-stale") }
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
        // Verify final markSynced
        coVerify { eventsDao.markSynced(eventNullEtag.id, "new-etag", any()) }
    }

    @Test
    fun `pushAll retries when null etag and PROPFIND fails with network error`() = runTest {
        // Given: event with caldavUrl but etag=null, PROPFIND returns networkError (isRetryable=true)
        // This should FAIL before the fix is implemented because the current
        // code returns isRetryable=false unconditionally.
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
        // Verify scheduleRetry was called (network error IS retryable)
        coVerify { pendingOperationsDao.scheduleRetry(operation.id, any(), any(), any()) }
        // Verify markFailed was NOT called
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
        // Verify no updateEvent call was made (can't update without etag)
        coVerify(exactly = 0) { client.updateEvent(any(), any(), any()) }
    }

    @Test
    fun `pushAll fails permanently when null etag and PROPFIND fails with auth error`() = runTest {
        // Given: event with caldavUrl but etag=null, PROPFIND returns authError (isRetryable=false)
        // Auth errors should NOT be retried - they need user intervention
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
        // PROPFIND fails with auth error (should NOT be retryable)
        coEvery { client.fetchEtag(eventNullEtag.caldavUrl!!) } returns CalDavResult.authError("Invalid credentials")
        coEvery { pendingOperationsDao.markFailed(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        assert((result as PushResult.Success).operationsFailed == 1)

        // Verify PROPFIND was attempted
        coVerify { client.fetchEtag(eventNullEtag.caldavUrl!!) }
        // Verify markFailed was called (auth error is NOT retryable)
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
        // Verify scheduleRetry was NOT called
        coVerify(exactly = 0) { pendingOperationsDao.scheduleRetry(any(), any(), any(), any()) }
    }

    @Test
    fun `pushAll triggers PROPFIND when etag is empty string`() = runTest {
        // Given: event with empty string etag (edge case from Zoho servers)
        // This should FAIL before the fix is implemented because current code
        // only checks `!= null`, not `isNullOrEmpty()`.
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
        // Combined test: empty string etag + PROPFIND network failure.
        // Verifies both fixes work together
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
        // Should NOT mark as failed
        coVerify(exactly = 0) { pendingOperationsDao.markFailed(any(), any(), any()) }
    }

    @Test
    fun `pushAll recovers null etag via PROPFIND then handles 412 retry`() = runTest {
        // Given: null etag → PROPFIND recovers → PUT gets 412 → normal 412 retry flow
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
        // 1st PROPFIND: recover null etag → "recovered-etag"
        // 2nd fetchEtag: 412 retry → "fresh-etag"
        coEvery { client.fetchEtag(eventNullEtag.caldavUrl!!) } returnsMany listOf(
            CalDavResult.success("recovered-etag"),
            CalDavResult.success("fresh-etag")
        )
        coEvery { eventsDao.updateEtag(eventNullEtag.id, any()) } just Runs
        // PUT with recovered etag → 412
        coEvery { client.updateEvent(eventNullEtag.caldavUrl!!, any(), eq("recovered-etag")) } returns
            CalDavResult.conflictError("Modified on server")
        // 412 retry PUT with fresh etag → success
        coEvery { client.updateEvent(eventNullEtag.caldavUrl!!, any(), eq("fresh-etag")) } returns
            CalDavResult.success("final-etag")
        coEvery { eventsDao.markSynced(eventNullEtag.id, "final-etag", any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        val success = result as PushResult.Success
        assert(success.eventsUpdated == 1)
        assert(success.operationsFailed == 0) { "Expected 0 failures but got ${success.operationsFailed}" }

        // Verify the full sequence: PROPFIND → PUT(412) → PROPFIND → PUT(success)
        coVerify(exactly = 2) { client.fetchEtag(eventNullEtag.caldavUrl!!) }
        coVerify { eventsDao.markSynced(eventNullEtag.id, "final-etag", any()) }
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
        // The PARTSTAT-only branch must NOT serialize the local event verbatim
        // (which would lose the server's other attendees). Instead it must
        // patch only self's PARTSTAT in the rawIcal.
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
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success)
        // The body that hit the wire must contain the new PARTSTAT and preserve Alice.
        val body = sentBody.captured
        assertTrue("self PARTSTAT must update to ACCEPTED", body.contains("PARTSTAT=ACCEPTED"))
        assertTrue("Alice must survive", body.contains("alice@example.test"))
        assertTrue("Self mailto must survive", body.contains("self@example.test"))
        // SEQUENCE NOT bumped — RFC 5546 §2.1.4
        assertTrue("SEQUENCE must remain at 3", body.contains("SEQUENCE:3"))
        // DESCRIPTION not in body, that's fine; SUMMARY must survive
        assertTrue("SUMMARY must survive", body.contains("Quarterly review"))
    }

    @Test
    fun `pushAll partstat_only UPDATE on 412 refetches body and retries`() = runTest {
        // 412 retry path: fetchEtag refreshes the etag AND fetchEvent
        // refreshes rawIcal, then re-run patch and retry once.
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

        // Server's fresh body has a NEW attendee (Carol) the local copy didn't know about.
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
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)
        assert(result is PushResult.Success)
        assertEquals(2, putCount)

        // Retried body must be against the FRESH ICS — Carol must be present.
        val retryBody = putBodies[1]
        assertTrue("retry must include Carol from refreshed body", retryBody.contains("carol@example.test"))
        assertTrue("retry must reflect TENTATIVE PARTSTAT", retryBody.contains("PARTSTAT=TENTATIVE"))
    }

    @Test
    fun `pushAll partstat_only UPDATE on second 412 surfaces snackbar warning`() = runTest {
        // Two 412s in a row — surface "event was modified" warning, don't retry indefinitely.
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

        // Warning string must mention RSVP and the event title so HomeViewModel can surface it.
        val warning = success.pushWarnings.firstOrNull { it.contains("RSVP", ignoreCase = true) }
        assertTrue(
            "expected an RSVP-modified warning, got warnings: ${success.pushWarnings}",
            warning != null
        )
    }

    @Test
    fun `pushAll partstat_only UPDATE uses operation targetUrl when event caldavUrl was cleared`() = runTest {
        // If the queued op captured caldavUrl at queue time, the PUT must succeed
        // even when Event.caldavUrl is cleared between queue and drain. Without
        // this, a future code path that nulls caldavUrl without clearing pending
        // ops would silently turn the queued RSVP into a no-op and other invitees
        // would still see us as NEEDS-ACTION.
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
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)

        assert(result is PushResult.Success) {
            "expected success when targetUrl carries the URL even with null caldavUrl, got $result"
        }
        // PUT must have hit the captured URL; the mock above will fail to match
        // any other URL value (eq matcher) and an unsuccessful result would
        // surface as a failed operation, not Success.
        coVerify(exactly = 1) { client.updateEvent(eq(capturedUrl), any(), any()) }
    }

    @Test
    fun `pushAll partstat_only UPDATE 412 retry also uses operation targetUrl when caldavUrl is null`() = runTest {
        // Regression-prevention assertion: the 412 retry branch reads the same
        // caldavUrl local var as the first PUT, so when event.caldavUrl is null,
        // BOTH PUTs must hit operation.targetUrl.
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
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = pushStrategy.pushAll(client)
        assert(result is PushResult.Success)
        assertEquals(2, putCount)
        // Both PUTs must have used capturedUrl — the eq() matcher above
        // proves that, since a mismatch would have left putCount=0.
        coVerify(exactly = 2) { client.updateEvent(eq(capturedUrl), any(), any()) }
        coVerify(exactly = 1) { client.fetchEtag(eq(capturedUrl)) }
        coVerify(exactly = 1) { client.fetchEvent(eq(capturedUrl)) }
    }
}
