package org.onekash.kashcal.sync.strategy

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
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

class ConflictResolverTest {

    private lateinit var conflictResolver: ConflictResolver
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var client: CalDavClient

    @Before
    fun setup() {
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()
        occurrenceGenerator = mockk()
        client = mockk()

        conflictResolver = ConflictResolver(
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            occurrenceGenerator = occurrenceGenerator
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
}
