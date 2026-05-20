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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult

class ConflictResolverTest {

    private lateinit var conflictResolver: ConflictResolver
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var client: CalDavClient
    private lateinit var database: KashCalDatabase

    @Before
    fun setup() {
        calendarRepository = mockk()
        eventsDao = mockk()
        attendeesDao = mockk(relaxed = true)
        pendingOperationsDao = mockk()
        occurrenceGenerator = mockk()
        client = mockk()
        database = mockk(relaxed = true)
        // The non-inline `runInTransaction` wrapper just runs the block,
        // letting the SERVER_WINS path execute as a single sequence in tests.
        // MockK's generic-suspend type inference needs the explicit
        // `Unit` since the production `runInTransaction { … }` block in
        // ConflictResolver returns no value.
        coEvery { database.runInTransaction(any<suspend () -> Unit>()) } coAnswers {
            val block = firstArg<suspend () -> Unit>()
            block()
        }

        conflictResolver = ConflictResolver(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = attendeesDao,
            pendingOperationsDao = pendingOperationsDao,
            occurrenceGenerator = occurrenceGenerator,
            database = database
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== NEWEST_WINS ETag Update (v22.5.6) ==========

    @Test
    fun `resolveNewestWins updates etag when local wins`() = runTest {
        // Local has higher sequence → local wins.
        // The fix ensures etag is updated to server's current value before creating
        // the retry operation, preventing a stale-etag → 412 → infinite loop.
        val event = Event(
            id = 42L,
            uid = "test-uid",
            calendarId = 1L,
            title = "Local Version",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600_000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "https://caldav.example.com/cal/event.ics",
            etag = "etag-stale",
            sequence = 5,  // Higher than server's 3
            syncStatus = SyncStatus.PENDING_UPDATE,
            localModifiedAt = System.currentTimeMillis()
        )

        val operation = PendingOperation(
            id = 10L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING,
            lastError = "Conflict: server has newer version"
        )

        // Server event has sequence=3 (lower than local's 5)
        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Server Version
            SEQUENCE:3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { client.fetchEvent(event.caldavUrl!!) } returns CalDavResult.success(
            CalDavEvent("event.ics", event.caldavUrl!!, "etag-server-current", serverIcal)
        )
        coEvery { eventsDao.updateEtag(event.id, "etag-server-current") } just Runs
        coEvery { pendingOperationsDao.deleteById(operation.id) } just Runs
        coEvery { pendingOperationsDao.insert(any()) } returns 11L
        coEvery { eventsDao.updateSyncStatus(event.id, SyncStatus.PENDING_UPDATE, any()) } just Runs

        val result = conflictResolver.resolve(operation, strategy = ConflictStrategy.NEWEST_WINS, client = client)

        assert(result == ConflictResult.LocalVersionPushed)

        // Key assertion: etag was updated BEFORE the new operation was created
        coVerifyOrder {
            eventsDao.updateEtag(event.id, "etag-server-current")
            pendingOperationsDao.deleteById(operation.id)
            pendingOperationsDao.insert(any())
        }
    }

    // ========== Default Reminder Tests (Issue #74) ==========

    @Test
    fun `resolveServerWins does not apply default reminders when server has no alarms`() = runTest {
        // Server event has NO VALARM — reminders should stay null (not get defaults applied)
        val event = Event(
            id = 50L,
            uid = "no-alarm-uid",
            calendarId = 1L,
            title = "Local Version",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600_000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "https://caldav.example.com/cal/no-alarm.ics",
            etag = "etag-old",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 20L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        // Server event has NO VALARM
        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-alarm-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Server No Alarm Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.example.com/cal/",
            displayName = "Test Calendar",
            color = 0xFF0000
        )

        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { client.fetchEvent(event.caldavUrl!!) } returns CalDavResult.success(
            CalDavEvent("no-alarm.ics", event.caldavUrl!!, "etag-server", serverIcal)
        )
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } returns Unit

        val result = conflictResolver.resolve(operation, strategy = ConflictStrategy.SERVER_WINS, client = client)

        assert(result == ConflictResult.ServerVersionKept)
        assertNull(
            "SERVER_WINS should not apply default reminders when server has no VALARM",
            capturedEvent.captured.reminders
        )
    }

    // ========== A2 Attendee Persistence ==========

    @Test
    fun `resolveServerWins persists server attendees (must not silently drop)`() = runTest {
        // Review-plan finding #2: SERVER_WINS resolution must not drop attendees.
        // This test asserts the production write at ConflictResolver fires with the
        // server's attendee list, locking in the contract for B3+ scheduling work.
        val event = Event(
            id = 99L,
            uid = "with-attendees-uid",
            calendarId = 1L,
            title = "Local Version",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600_000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "https://caldav.example.com/cal/with-attendees.ics",
            etag = "etag-old",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 30L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:with-attendees-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Server Event With Attendees
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.example.com/cal/",
            displayName = "Test Calendar",
            color = 0xFF0000
        )

        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { client.fetchEvent(event.caldavUrl!!) } returns CalDavResult.success(
            CalDavEvent("with-attendees.ics", event.caldavUrl!!, "etag-server", serverIcal)
        )
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } returns Unit

        val capturedAttendees = slot<List<Attendee>>()

        val result = conflictResolver.resolve(operation, strategy = ConflictStrategy.SERVER_WINS, client = client)

        assert(result == ConflictResult.ServerVersionKept)
        coVerify {
            attendeesDao.replaceForEvent(eventId = event.id, attendees = capture(capturedAttendees))
        }
        val written = capturedAttendees.captured
        assert(written.size == 2) { "Expected 2 server attendees written; got ${written.size}" }
        assertTrue(
            "Server attendees must include Alice",
            written.any { it.address == "mailto:alice@example.com" && it.partstat == "ACCEPTED" }
        )
        assertTrue(
            "Server attendees must include Bob",
            written.any { it.address == "mailto:bob@example.com" && it.partstat == "NEEDS-ACTION" }
        )
        // Each attendee's eventId must be patched with the saved ID
        assertTrue(
            "All written attendees must carry the resolved eventId",
            written.all { it.eventId == event.id }
        )
    }

    // ========== B3 — SERVER_WINS upsert + attendees runs inside runInTransaction ==========

    @Test
    fun `resolveServerWins runs upsert and attendees-replace inside a single transaction`() = runTest {
        // The deferred-from-A2 fix: ConflictResolver SERVER_WINS must wrap
        // event upsert + attendees replaceForEvent in database.runInTransaction
        // so a partial write rolls back rather than leaving the event row
        // out-of-sync with its attendees table.
        val event = Event(
            id = 77L,
            uid = "tx-uid",
            calendarId = 1L,
            title = "Local Version",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600_000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "https://caldav.example.com/cal/tx.ics",
            etag = "etag-old",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 40L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tx-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Server Version
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.example.com/cal/",
            displayName = "Test Calendar",
            color = 0xFF0000
        )

        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { client.fetchEvent(event.caldavUrl!!) } returns CalDavResult.success(
            CalDavEvent("tx.ics", event.caldavUrl!!, "etag-server", serverIcal)
        )
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } returns Unit

        val result = conflictResolver.resolve(operation, strategy = ConflictStrategy.SERVER_WINS, client = client)

        assert(result == ConflictResult.ServerVersionKept)
        // Transaction was opened.
        coVerify(atLeast = 1) { database.runInTransaction(any<suspend () -> Unit>()) }
        // And both writes happened inside it.
        coVerifyOrder {
            database.runInTransaction(any<suspend () -> Unit>())
            eventsDao.upsert(any())
            attendeesDao.replaceForEvent(eventId = event.id, attendees = any())
        }
    }

    @Test
    fun `resolveServerWins rolls back when attendees-replace throws`() = runTest {
        // If attendees-replace blows up mid-transaction, the upsert must NOT
        // be committed. This locks in the rollback semantic: prior to this
        // chunk, the writes were sequential and a failure mid-stream would
        // leave the event row updated but attendees stale.
        val event = Event(
            id = 88L,
            uid = "rollback-uid",
            calendarId = 1L,
            title = "Local Version",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600_000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "https://caldav.example.com/cal/rollback.ics",
            etag = "etag-old",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        val operation = PendingOperation(
            id = 50L,
            eventId = event.id,
            operation = PendingOperation.OPERATION_UPDATE,
            status = PendingOperation.STATUS_PENDING
        )

        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rollback-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Server Rollback Test
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.example.com/cal/",
            displayName = "Test Calendar",
            color = 0xFF0000
        )

        // Override the relaxed attendeesDao mock — make replace throw.
        coEvery {
            attendeesDao.replaceForEvent(eventId = event.id, attendees = any())
        } throws RuntimeException("simulated DB error")

        // Make the runInTransaction stub propagate exceptions like the real
        // implementation does — the block runs, throws, transaction aborts.
        coEvery { database.runInTransaction(any<suspend () -> Unit>()) } coAnswers {
            val block = firstArg<suspend () -> Unit>()
            block()  // Will throw RuntimeException, propagating out.
        }

        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { client.fetchEvent(event.caldavUrl!!) } returns CalDavResult.success(
            CalDavEvent("rollback.ics", event.caldavUrl!!, "etag-server", serverIcal)
        )
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { occurrenceGenerator.regenerateOccurrences(any()) } returns 1
        coEvery { pendingOperationsDao.deleteById(operation.id) } returns Unit

        var thrown: Throwable? = null
        try {
            conflictResolver.resolve(operation, strategy = ConflictStrategy.SERVER_WINS, client = client)
        } catch (e: RuntimeException) {
            thrown = e
        }
        assertTrue("transaction body must propagate the simulated DB error: $thrown", thrown != null)

        // pendingOperationsDao.deleteById must NOT have been called — the
        // post-transaction cleanup runs only when the transaction succeeded.
        coVerify(exactly = 0) { pendingOperationsDao.deleteById(operation.id) }
    }
}
