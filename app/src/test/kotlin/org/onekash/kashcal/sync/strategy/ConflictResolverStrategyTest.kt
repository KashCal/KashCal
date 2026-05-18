package org.onekash.kashcal.sync.strategy

import android.util.Log
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Comprehensive strategy tests for ConflictResolver.
 *
 * Tests all 4 conflict resolution strategies:
 * - SERVER_WINS: Server overwrites local (default, safest)
 * - LOCAL_WINS: Force push local (limited to DELETE)
 * - NEWEST_WINS: Compare sequence/dtstamp, keep newer
 * - MANUAL: Mark for user resolution
 *
 * Plus general edge cases: missing event, calendar mismatch, resolveAll.
 */
class ConflictResolverStrategyTest {

    private lateinit var conflictResolver: ConflictResolver
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var client: CalDavClient

    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()
        occurrenceGenerator = mockk()
        client = mockk()

        conflictResolver = ConflictResolver(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = mockk(relaxed = true),
            pendingOperationsDao = pendingOperationsDao,
            occurrenceGenerator = occurrenceGenerator
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== General ==========

    @Test
    fun `resolve returns EventNotFound when event missing`() = runTest {
        val operation = makeOperation(eventId = 999L)
        coEvery { eventsDao.getById(999L) } returns null

        val result = conflictResolver.resolve(operation, client = client)

        assertEquals(ConflictResult.EventNotFound, result)
    }

    @Test
    fun `resolve returns CalendarMismatch when calendar changed`() = runTest {
        val event = makeEvent(id = 1L, calendarId = 1L)
        val operation = makeOperation(eventId = 1L)
        coEvery { eventsDao.getById(1L) } returns event

        val result = conflictResolver.resolve(
            operation, expectedCalendarId = 99L, client = client
        )

        assertEquals(ConflictResult.CalendarMismatch, result)
    }

    @Test
    fun `resolveAll processes all operations`() = runTest {
        val op1 = makeOperation(eventId = 1L, id = 1L)
        val op2 = makeOperation(eventId = 2L, id = 2L)
        coEvery { eventsDao.getById(1L) } returns null
        coEvery { eventsDao.getById(2L) } returns null

        val results = conflictResolver.resolveAll(
            listOf(op1, op2), strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it == ConflictResult.EventNotFound })
    }

    // ========== SERVER_WINS ==========

    @Test
    fun `serverWins DELETE cancels local delete and preserves server version`() = runTest {
        val event = makeEvent(id = 1L)
        val operation = makeOperation(
            eventId = 1L, operation = PendingOperation.OPERATION_DELETE
        )
        coEvery { eventsDao.getById(1L) } returns event
        coEvery { eventsDao.updateSyncStatus(1L, SyncStatus.SYNCED, any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertEquals(ConflictResult.ServerVersionKept, result)
        coVerify { eventsDao.updateSyncStatus(1L, SyncStatus.SYNCED, any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `serverWins UPDATE fetches server version and overwrites local`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)
        val calendar = makeCalendar(id = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-new", VALID_SERVER_ICS)
        )
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertEquals(ConflictResult.ServerVersionKept, result)
        coVerify { eventsDao.upsert(any()) }
        coVerify { pendingOperationsDao.deleteById(operation.id) }
    }

    @Test
    fun `serverWins UPDATE with server 404 deletes local event`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.error(404, "Not Found")
        coEvery { eventsDao.deleteById(1L) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertEquals(ConflictResult.LocalDeleted, result)
        coVerify { eventsDao.deleteById(1L) }
    }

    @Test
    fun `serverWins returns error when event has no caldavUrl`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = null)
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertTrue("Should be Error", result is ConflictResult.Error)
        assertTrue((result as ConflictResult.Error).message.contains("no server URL"))
    }

    @Test
    fun `serverWins returns error when server fetch fails`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.error(500, "Server Error")

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertTrue("Should be Error", result is ConflictResult.Error)
        assertTrue((result as ConflictResult.Error).message.contains("Failed to fetch"))
    }

    @Test
    fun `serverWins returns error when server ICS parse fails`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-new", "NOT VALID ICS DATA")
        )

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertTrue("Should be Error", result is ConflictResult.Error)
    }

    @Test
    fun `serverWins deletes local when calendar deleted mid-sync`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-new", VALID_SERVER_ICS)
        )
        coEvery { calendarRepository.getCalendarById(1L) } returns null  // Calendar deleted
        coEvery { eventsDao.deleteById(1L) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertEquals(ConflictResult.LocalDeleted, result)
        coVerify { eventsDao.deleteById(1L) }
    }

    @Test
    fun `serverWins regenerates occurrences for recurring event`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)
        val calendar = makeCalendar(id = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-new", RECURRING_SERVER_ICS)
        )
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.generateOccurrences(any(), any(), any()) } returns 10
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertEquals(ConflictResult.ServerVersionKept, result)
        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
    }

    @Test
    fun `serverWins detects non-event VTODO on server`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/todo.ics")
        val operation = makeOperation(eventId = 1L)
        val vtodoIcs = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VTODO
UID:todo-uid
DTSTAMP:20240101T120000Z
SUMMARY:A todo item
END:VTODO
END:VCALENDAR""".trimIndent()

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/todo.ics") } returns CalDavResult.success(
            CalDavEvent("todo.ics", "https://server/cal/todo.ics", "etag", vtodoIcs)
        )

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.SERVER_WINS, client = client
        )

        assertTrue("Should be Error for non-event", result is ConflictResult.Error)
        assertTrue((result as ConflictResult.Error).message.contains("non-event"))
    }

    // ========== LOCAL_WINS ==========

    @Test
    fun `localWins DELETE force-deletes with empty etag`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(
            eventId = 1L, operation = PendingOperation.OPERATION_DELETE
        )

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.deleteEvent("https://server/cal/event.ics", "") } returns CalDavResult.success(Unit)
        coEvery { eventsDao.deleteById(1L) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.LOCAL_WINS, client = client
        )

        assertEquals(ConflictResult.LocalVersionPushed, result)
        coVerify { client.deleteEvent("https://server/cal/event.ics", "") }
        coVerify { eventsDao.deleteById(1L) }
    }

    @Test
    fun `localWins DELETE succeeds even when server returns 404`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(
            eventId = 1L, operation = PendingOperation.OPERATION_DELETE
        )

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.deleteEvent("https://server/cal/event.ics", "") } returns CalDavResult.error(404, "Not Found")
        coEvery { eventsDao.deleteById(1L) } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.LOCAL_WINS, client = client
        )

        assertEquals(ConflictResult.LocalVersionPushed, result)
    }

    @Test
    fun `localWins DELETE fails on non-404 server error`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(
            eventId = 1L, operation = PendingOperation.OPERATION_DELETE
        )

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.deleteEvent("https://server/cal/event.ics", "") } returns CalDavResult.error(500, "Server Error")

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.LOCAL_WINS, client = client
        )

        assertTrue("Should be Error", result is ConflictResult.Error)
        assertTrue((result as ConflictResult.Error).message.contains("force delete"))
    }

    @Test
    fun `localWins UPDATE returns not-supported error`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(
            eventId = 1L, operation = PendingOperation.OPERATION_UPDATE
        )

        coEvery { eventsDao.getById(1L) } returns event

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.LOCAL_WINS, client = client
        )

        assertTrue("Should be Error", result is ConflictResult.Error)
        assertTrue((result as ConflictResult.Error).message.contains("not supported"))
    }

    // ========== NEWEST_WINS ==========

    @Test
    fun `newestWins server higher sequence wins`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics", sequence = 2)
        val operation = makeOperation(eventId = 1L)
        val calendar = makeCalendar(id = 1L)
        // Server has sequence 5 (higher than local's 2)
        val serverIcs = makeServerIcs(sequence = 5, dtstamp = "20240101T120000Z")

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-server", serverIcs)
        )
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.NEWEST_WINS, client = client
        )

        assertEquals(ConflictResult.ServerVersionKept, result)
    }

    @Test
    fun `newestWins local higher sequence wins`() = runTest {
        val event = makeEvent(
            id = 1L, caldavUrl = "https://server/cal/event.ics",
            sequence = 10, etag = "etag-stale"
        )
        val operation = makeOperation(eventId = 1L)
        // Server has sequence 3 (lower than local's 10)
        val serverIcs = makeServerIcs(sequence = 3, dtstamp = "20240101T120000Z")

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-current", serverIcs)
        )
        coEvery { eventsDao.updateEtag(1L, "etag-current") } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs
        coEvery { pendingOperationsDao.insert(any()) } returns 11L
        coEvery { eventsDao.updateSyncStatus(1L, SyncStatus.PENDING_UPDATE, any()) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.NEWEST_WINS, client = client
        )

        assertEquals(ConflictResult.LocalVersionPushed, result)
        // Verify etag updated to server's current before creating retry
        coVerifyOrder {
            eventsDao.updateEtag(1L, "etag-current")
            pendingOperationsDao.deleteById(operation.id)
            pendingOperationsDao.insert(any())
        }
    }

    @Test
    fun `newestWins equal sequence server newer dtstamp wins`() = runTest {
        // Local modified long ago (year 2020), server modified recently (2026)
        val localModifiedAt = 1_577_836_800_000L // 2020-01-01T00:00:00Z
        val event = makeEvent(
            id = 1L, caldavUrl = "https://server/cal/event.ics",
            sequence = 5, localModifiedAt = localModifiedAt
        )
        val operation = makeOperation(eventId = 1L)
        val calendar = makeCalendar(id = 1L)
        // Server dtstamp is 2026 — clearly newer than local's 2020 timestamp
        val serverIcs = makeServerIcs(sequence = 5, dtstamp = "20260219T030000Z")

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-server", serverIcs)
        )
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.NEWEST_WINS, client = client
        )

        assertEquals(ConflictResult.ServerVersionKept, result)
    }

    @Test
    fun `newestWins equal sequence local newer timestamp wins`() = runTest {
        // Local modified just now, server modified long ago
        val event = makeEvent(
            id = 1L, caldavUrl = "https://server/cal/event.ics",
            sequence = 5, localModifiedAt = now
        )
        val operation = makeOperation(eventId = 1L)
        // Server dtstamp = 2020 (very old)
        val serverIcs = makeServerIcs(sequence = 5, dtstamp = "20200101T120000Z")

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.success(
            CalDavEvent("event.ics", "https://server/cal/event.ics", "etag-current", serverIcs)
        )
        coEvery { eventsDao.updateEtag(1L, "etag-current") } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs
        coEvery { pendingOperationsDao.insert(any()) } returns 11L
        coEvery { eventsDao.updateSyncStatus(1L, SyncStatus.PENDING_UPDATE, any()) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.NEWEST_WINS, client = client
        )

        assertEquals(ConflictResult.LocalVersionPushed, result)
    }

    @Test
    fun `newestWins local wins with no caldavUrl`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = null)
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.NEWEST_WINS, client = client
        )

        assertEquals(ConflictResult.LocalVersionPushed, result)
    }

    @Test
    fun `newestWins server 404 means local wins`() = runTest {
        val event = makeEvent(id = 1L, caldavUrl = "https://server/cal/event.ics")
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { client.fetchEvent("https://server/cal/event.ics") } returns CalDavResult.error(404, "Not Found")

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.NEWEST_WINS, client = client
        )

        assertEquals(ConflictResult.LocalVersionPushed, result)
    }

    // ========== MANUAL ==========

    @Test
    fun `manual marks operation failed and records sync error`() = runTest {
        val event = makeEvent(id = 1L)
        val operation = makeOperation(eventId = 1L)

        coEvery { eventsDao.getById(1L) } returns event
        coEvery { pendingOperationsDao.markFailed(operation.id, any(), any()) } just Runs
        coEvery { eventsDao.recordSyncError(1L, any(), any()) } just Runs

        val result = conflictResolver.resolve(
            operation, strategy = ConflictStrategy.MANUAL, client = client
        )

        assertEquals(ConflictResult.MarkedForManualResolution, result)
        coVerify { pendingOperationsDao.markFailed(operation.id, any(), any()) }
        coVerify { eventsDao.recordSyncError(1L, any(), any()) }
    }

    // ========== ConflictResult ==========

    @Test
    fun `isSuccess returns true for resolution outcomes`() {
        assertTrue(ConflictResult.ServerVersionKept.isSuccess())
        assertTrue(ConflictResult.LocalVersionPushed.isSuccess())
        assertTrue(ConflictResult.LocalDeleted.isSuccess())
        assertTrue(ConflictResult.MarkedForManualResolution.isSuccess())
    }

    @Test
    fun `isSuccess returns false for error outcomes`() {
        assertFalse(ConflictResult.EventNotFound.isSuccess())
        assertFalse(ConflictResult.CalendarMismatch.isSuccess())
        assertFalse(ConflictResult.Error("test").isSuccess())
    }

    // ========== Helpers ==========

    private fun makeEvent(
        id: Long = 1L,
        calendarId: Long = 1L,
        caldavUrl: String? = "https://server/cal/event.ics",
        etag: String? = "etag-old",
        sequence: Int = 0,
        localModifiedAt: Long? = null
    ) = Event(
        id = id,
        uid = "test-uid-$id",
        calendarId = calendarId,
        title = "Test Event $id",
        startTs = now,
        endTs = now + 3600_000,
        dtstamp = now,
        caldavUrl = caldavUrl,
        etag = etag,
        sequence = sequence,
        syncStatus = SyncStatus.PENDING_UPDATE,
        localModifiedAt = localModifiedAt
    )

    private fun makeOperation(
        eventId: Long = 1L,
        id: Long = 10L,
        operation: String = PendingOperation.OPERATION_UPDATE
    ) = PendingOperation(
        id = id,
        eventId = eventId,
        operation = operation,
        status = PendingOperation.STATUS_PENDING
    )

    private fun makeCalendar(id: Long = 1L) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://server/cal/",
        displayName = "Test Calendar",
        color = 0xFF0000
    )

    private fun makeServerIcs(sequence: Int = 0, dtstamp: String = "20240101T120000Z"): String {
        return """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:test-uid-1
DTSTAMP:$dtstamp
DTSTART:20240101T100000Z
DTEND:20240101T110000Z
SUMMARY:Server Version
SEQUENCE:$sequence
END:VEVENT
END:VCALENDAR""".trimIndent()
    }

    companion object {
        private val VALID_SERVER_ICS = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:test-uid-1
DTSTAMP:20240101T120000Z
DTSTART:20240101T100000Z
DTEND:20240101T110000Z
SUMMARY:Server Version
END:VEVENT
END:VCALENDAR""".trimIndent()

        private val RECURRING_SERVER_ICS = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:test-uid-1
DTSTAMP:20240101T120000Z
DTSTART:20240101T100000Z
DTEND:20240101T110000Z
SUMMARY:Recurring Server Event
RRULE:FREQ=WEEKLY;BYDAY=MO
END:VEVENT
END:VCALENDAR""".trimIndent()
    }
}
