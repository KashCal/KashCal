package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.ICalParser
import org.onekash.kashcal.sync.parser.ParseError
import org.onekash.kashcal.sync.parser.ParseResult
import org.onekash.kashcal.sync.parser.ParsedEvent

class ConflictResolverTest {

    private lateinit var client: CalDavClient
    private lateinit var parser: ICalParser
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var dataStore: KashCalDataStore
    private lateinit var conflictResolver: ConflictResolver

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
        reminders = null,
        dtstamp = System.currentTimeMillis(),
        caldavUrl = "https://caldav.icloud.com/123/calendar/test-event.ics",
        etag = "etag-123",
        sequence = 1,
        syncStatus = SyncStatus.PENDING_UPDATE,
        lastSyncError = null,
        syncRetryCount = 0,
        localModifiedAt = System.currentTimeMillis(),
        serverModifiedAt = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private val testOperation = PendingOperation(
        id = 1L,
        eventId = testEvent.id,
        operation = PendingOperation.OPERATION_UPDATE,
        status = PendingOperation.STATUS_PENDING
    )

    private val testParsedEvent = ParsedEvent(
        uid = testEvent.uid,
        recurrenceId = null,
        summary = "Server Version",
        description = "Updated on server",
        location = "Server Location",
        startTs = testEvent.startTs / 1000,
        endTs = testEvent.endTs / 1000,
        isAllDay = false,
        timezone = "America/New_York",
        rrule = null,
        exdates = emptyList(),
        rdates = emptyList(),
        reminderMinutes = emptyList(),
        sequence = 2, // Higher than local
        status = "CONFIRMED",
        dtstamp = System.currentTimeMillis() / 1000,
        organizerEmail = null,
        organizerName = null,
        rawIcal = ""
    )

    private val testServerIcal = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:test-event-uid-123
        SUMMARY:Server Version
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private val testCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.icloud.com/123/calendar/",
        displayName = "Test Calendar",
        color = -1
    )

    @Before
    fun setup() {
        client = mockk()
        parser = mockk()
        calendarsDao = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()
        occurrenceGenerator = mockk()
        dataStore = mockk()

        // Setup DataStore mock to return default reminder settings
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)

        // Default mock: calendar exists (tests can override)
        coEvery { calendarsDao.getById(any()) } returns testCalendar

        conflictResolver = ConflictResolver(
            client = client,
            parser = parser,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            occurrenceGenerator = occurrenceGenerator,
            dataStore = dataStore
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== SERVER_WINS Tests ==========

    @Test
    fun `SERVER_WINS fetches and applies server version`() = runTest {
        val serverEvent = CalDavEvent(
            href = "/calendar/test-event.ics",
            url = testEvent.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { client.fetchEvent(testEvent.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(testServerIcal) } returns ParseResult(
            events = listOf(testParsedEvent),
            errors = emptyList()
        )
        coEvery { eventsDao.upsert(any()) } returns testEvent.id
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(testOperation.id) } just Runs

        val result = conflictResolver.resolve(testOperation, strategy = ConflictStrategy.SERVER_WINS)

        assert(result is ConflictResult.ServerVersionKept)
        coVerify { client.fetchEvent(testEvent.caldavUrl!!) }
        coVerify { eventsDao.upsert(any()) }
        coVerify { pendingOperationsDao.deleteById(testOperation.id) }
    }

    @Test
    fun `SERVER_WINS deletes local when server returns 404`() = runTest {
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { client.fetchEvent(testEvent.caldavUrl!!) } returns CalDavResult.notFoundError("Not found")
        coEvery { eventsDao.deleteById(testEvent.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(testOperation.id) } just Runs

        val result = conflictResolver.resolve(testOperation, strategy = ConflictStrategy.SERVER_WINS)

        assert(result is ConflictResult.LocalDeleted)
        coVerify { eventsDao.deleteById(testEvent.id) }
        coVerify { pendingOperationsDao.deleteById(testOperation.id) }
    }

    @Test
    fun `SERVER_WINS cancels DELETE operation`() = runTest {
        val deleteOperation = testOperation.copy(operation = PendingOperation.OPERATION_DELETE)

        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.SYNCED, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(deleteOperation.id) } just Runs

        val result = conflictResolver.resolve(deleteOperation, strategy = ConflictStrategy.SERVER_WINS)

        assert(result is ConflictResult.ServerVersionKept)
        coVerify { eventsDao.updateSyncStatus(testEvent.id, SyncStatus.SYNCED, any()) }
        coVerify { pendingOperationsDao.deleteById(deleteOperation.id) }
    }

    @Test
    fun `SERVER_WINS regenerates occurrences for recurring events`() = runTest {
        val recurringEvent = testEvent.copy(rrule = "FREQ=WEEKLY;BYDAY=MO")
        val serverParsedRecurring = testParsedEvent.copy(rrule = "FREQ=WEEKLY;BYDAY=MO")

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = recurringEvent.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(recurringEvent.id) } returns recurringEvent
        coEvery { client.fetchEvent(recurringEvent.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(serverParsedRecurring),
            errors = emptyList()
        )
        coEvery { eventsDao.upsert(any()) } returns recurringEvent.id
        coEvery { occurrenceGenerator.generateOccurrences(any(), any(), any()) } returns 10
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = conflictResolver.resolve(testOperation.copy(eventId = recurringEvent.id), strategy = ConflictStrategy.SERVER_WINS)

        assert(result is ConflictResult.ServerVersionKept)
        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
    }

    @Test
    fun `SERVER_WINS deletes event when calendar no longer exists`() = runTest {
        val serverEvent = CalDavEvent(
            href = "/calendar/test-event.ics",
            url = testEvent.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { client.fetchEvent(testEvent.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(testServerIcal) } returns ParseResult(
            events = listOf(testParsedEvent),
            errors = emptyList()
        )
        // Calendar deleted during sync!
        coEvery { calendarsDao.getById(testEvent.calendarId) } returns null
        coEvery { eventsDao.deleteById(testEvent.id) } just Runs
        coEvery { pendingOperationsDao.deleteById(testOperation.id) } just Runs

        val result = conflictResolver.resolve(testOperation, strategy = ConflictStrategy.SERVER_WINS)

        assert(result is ConflictResult.LocalDeleted)
        coVerify { eventsDao.deleteById(testEvent.id) }
        coVerify { pendingOperationsDao.deleteById(testOperation.id) }
    }

    // ========== NEWEST_WINS Tests ==========

    @Test
    fun `NEWEST_WINS keeps server when server sequence is higher`() = runTest {
        val localLowerSeq = testEvent.copy(sequence = 1)
        val serverHigherSeq = testParsedEvent.copy(sequence = 2)

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = localLowerSeq.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(localLowerSeq.id) } returns localLowerSeq
        coEvery { client.fetchEvent(localLowerSeq.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(serverHigherSeq),
            errors = emptyList()
        )
        coEvery { eventsDao.upsert(any()) } returns localLowerSeq.id
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val result = conflictResolver.resolve(testOperation, strategy = ConflictStrategy.NEWEST_WINS)

        assert(result is ConflictResult.ServerVersionKept)
    }

    @Test
    fun `NEWEST_WINS keeps local when local sequence is higher`() = runTest {
        val localHigherSeq = testEvent.copy(sequence = 5)
        val serverLowerSeq = testParsedEvent.copy(sequence = 2)

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = localHigherSeq.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(localHigherSeq.id) } returns localHigherSeq
        coEvery { client.fetchEvent(localHigherSeq.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(serverLowerSeq),
            errors = emptyList()
        )
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.insert(any()) } returns 2L  // New operation ID
        coEvery { eventsDao.updateSyncStatus(any(), any(), any()) } just Runs

        val result = conflictResolver.resolve(testOperation.copy(eventId = localHigherSeq.id), strategy = ConflictStrategy.NEWEST_WINS)

        assert(result is ConflictResult.LocalVersionPushed)
        coVerify { pendingOperationsDao.deleteById(testOperation.id) }
        coVerify { pendingOperationsDao.insert(any()) }
    }

    @Test
    fun `NEWEST_WINS keeps local when no server version exists`() = runTest {
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { client.fetchEvent(testEvent.caldavUrl!!) } returns CalDavResult.notFoundError("Not found")

        val result = conflictResolver.resolve(testOperation, strategy = ConflictStrategy.NEWEST_WINS)

        assert(result is ConflictResult.LocalVersionPushed)
    }

    @Test
    fun `NEWEST_WINS creates new operation with reset retry count when local wins`() = runTest {
        val localHigherSeq = testEvent.copy(sequence = 5)
        val serverLowerSeq = testParsedEvent.copy(sequence = 2)

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = localHigherSeq.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        // Capture the inserted operation to verify its properties
        val capturedOperation = slot<PendingOperation>()

        coEvery { eventsDao.getById(localHigherSeq.id) } returns localHigherSeq
        coEvery { client.fetchEvent(localHigherSeq.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(serverLowerSeq),
            errors = emptyList()
        )
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.insert(capture(capturedOperation)) } returns 2L
        coEvery { eventsDao.updateSyncStatus(any(), any(), any()) } just Runs

        val result = conflictResolver.resolve(testOperation.copy(eventId = localHigherSeq.id), strategy = ConflictStrategy.NEWEST_WINS)

        assert(result is ConflictResult.LocalVersionPushed)

        // Verify new operation has correct properties
        val newOp = capturedOperation.captured
        assert(newOp.eventId == localHigherSeq.id) { "eventId should match" }
        assert(newOp.operation == PendingOperation.OPERATION_UPDATE) { "operation should be UPDATE" }
        assert(newOp.status == PendingOperation.STATUS_PENDING) { "status should be PENDING" }
        assert(newOp.retryCount == 0) { "retryCount should be reset to 0" }
        assert(newOp.nextRetryAt == 0L) { "nextRetryAt should be 0 for immediate processing" }
    }

    @Test
    fun `NEWEST_WINS deletes old operation before creating new one`() = runTest {
        val localHigherSeq = testEvent.copy(sequence = 5)
        val serverLowerSeq = testParsedEvent.copy(sequence = 2)
        val operationWithRetries = testOperation.copy(
            eventId = localHigherSeq.id,
            retryCount = 3  // Old operation had retries
        )

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = localHigherSeq.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(localHigherSeq.id) } returns localHigherSeq
        coEvery { client.fetchEvent(localHigherSeq.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(serverLowerSeq),
            errors = emptyList()
        )
        coEvery { pendingOperationsDao.deleteById(operationWithRetries.id) } just Runs
        coEvery { pendingOperationsDao.insert(any()) } returns 2L
        coEvery { eventsDao.updateSyncStatus(any(), any(), any()) } just Runs

        conflictResolver.resolve(operationWithRetries, strategy = ConflictStrategy.NEWEST_WINS)

        // Verify old operation was deleted
        coVerify { pendingOperationsDao.deleteById(operationWithRetries.id) }
        // Verify new operation was created
        coVerify { pendingOperationsDao.insert(match { it.retryCount == 0 }) }
    }

    @Test
    fun `NEWEST_WINS new operation is immediately ready for processing`() = runTest {
        val localHigherSeq = testEvent.copy(sequence = 5)
        val serverLowerSeq = testParsedEvent.copy(sequence = 2)

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = localHigherSeq.caldavUrl!!,
            etag = "new-etag",
            icalData = testServerIcal
        )

        val capturedOperation = slot<PendingOperation>()

        coEvery { eventsDao.getById(localHigherSeq.id) } returns localHigherSeq
        coEvery { client.fetchEvent(localHigherSeq.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(serverLowerSeq),
            errors = emptyList()
        )
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { pendingOperationsDao.insert(capture(capturedOperation)) } returns 2L
        coEvery { eventsDao.updateSyncStatus(any(), any(), any()) } just Runs

        conflictResolver.resolve(testOperation.copy(eventId = localHigherSeq.id), strategy = ConflictStrategy.NEWEST_WINS)

        // Verify operation is ready for immediate processing
        val newOp = capturedOperation.captured
        assert(newOp.isReady(System.currentTimeMillis())) { "New operation should be immediately ready" }
    }

    // ========== MANUAL Tests ==========

    @Test
    fun `MANUAL marks conflict for user resolution`() = runTest {
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { pendingOperationsDao.markFailed(testOperation.id, any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(testEvent.id, any(), any()) } just Runs

        val result = conflictResolver.resolve(testOperation, strategy = ConflictStrategy.MANUAL)

        assert(result is ConflictResult.MarkedForManualResolution)
        coVerify { pendingOperationsDao.markFailed(testOperation.id, match { it.contains("manual") }, any()) }
        coVerify { eventsDao.recordSyncError(testEvent.id, any(), any()) }
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `resolve returns EventNotFound when event doesn't exist`() = runTest {
        coEvery { eventsDao.getById(testEvent.id) } returns null

        val result = conflictResolver.resolve(testOperation)

        assert(result is ConflictResult.EventNotFound)
    }

    @Test
    fun `SERVER_WINS returns error when event has no caldavUrl`() = runTest {
        val noUrlEvent = testEvent.copy(caldavUrl = null)

        coEvery { eventsDao.getById(noUrlEvent.id) } returns noUrlEvent

        val result = conflictResolver.resolve(testOperation.copy(eventId = noUrlEvent.id))

        assert(result is ConflictResult.Error)
        assert((result as ConflictResult.Error).message.contains("no server URL"))
    }

    @Test
    fun `SERVER_WINS returns error when fetch fails`() = runTest {
        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { client.fetchEvent(testEvent.caldavUrl!!) } returns CalDavResult.networkError("Connection failed")

        val result = conflictResolver.resolve(testOperation)

        assert(result is ConflictResult.Error)
        assert((result as ConflictResult.Error).message.contains("Failed to fetch"))
    }

    @Test
    fun `SERVER_WINS returns error when parse fails`() = runTest {
        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = testEvent.caldavUrl!!,
            etag = "etag",
            icalData = "invalid ical"
        )

        coEvery { eventsDao.getById(testEvent.id) } returns testEvent
        coEvery { client.fetchEvent(testEvent.caldavUrl!!) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse("invalid ical") } returns ParseResult(
            events = emptyList(),
            errors = listOf(ParseError("Parse error"))
        )

        val result = conflictResolver.resolve(testOperation)

        assert(result is ConflictResult.Error)
        assert((result as ConflictResult.Error).message.contains("parse"))
    }

    // ========== resolveAll Tests ==========

    @Test
    fun `resolveAll processes multiple conflicts`() = runTest {
        val op1 = testOperation.copy(id = 1L, eventId = 100L)
        val op2 = testOperation.copy(id = 2L, eventId = 101L)

        val event1 = testEvent.copy(id = 100L)
        val event2 = testEvent.copy(id = 101L)

        val serverEvent = CalDavEvent(
            href = "/calendar/test.ics",
            url = testEvent.caldavUrl!!,
            etag = "etag",
            icalData = testServerIcal
        )

        coEvery { eventsDao.getById(100L) } returns event1
        coEvery { eventsDao.getById(101L) } returns event2
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(serverEvent)
        coEvery { parser.parse(any()) } returns ParseResult(
            events = listOf(testParsedEvent),
            errors = emptyList()
        )
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs

        val results = conflictResolver.resolveAll(listOf(op1, op2))

        assert(results.size == 2)
        assert(results.all { it is ConflictResult.ServerVersionKept })
    }
}
