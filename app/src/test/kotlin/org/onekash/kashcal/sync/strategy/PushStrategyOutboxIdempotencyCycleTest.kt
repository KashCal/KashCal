package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.OutboxResponse
import org.onekash.kashcal.sync.client.model.SyncReport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composed anti-spam guarantee: across TWO consecutive
 * push cycles against a REAL in-memory database, the client-outbox send must
 * fire exactly once. Cycle 1 sends the REQUEST and advances the per-attendee
 * marker; cycle 2's read-back runs `replaceForEvent` again (server-parsed rows
 * carry no marker) and MUST NOT wipe the marker — so no duplicate REQUEST is
 * POSTed.
 *
 * This exercises the real `AttendeesDao.replaceForEvent` merge composed
 * with the real `PushStrategy` send gate; the mockk-based
 * `PushStrategyOutboxSendTest` stubs `replaceForEvent` and so cannot prove the
 * merge actually preserves the marker across a second read-back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyOutboxIdempotencyCycleTest {

    private lateinit var database: KashCalDatabase
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var pushStrategy: PushStrategy

    private val outboxUrl = "https://dav.example.test/caldav/me/outbox/"
    private var eventId: Long = 0
    private var calendarId: Long = 0

    /**
     * Fake client: PUT succeeds; the read-back GET always returns a body whose
     * attendee is stamped SCHEDULE-AGENT=CLIENT (the Zoho-class "you deliver"
     * signal). Each outbox POST is counted and answered 2.0;Success.
     */
    private class FakeClient(private val readBackIcs: String) : CalDavClient {
        var outboxPostCount = 0
        override suspend fun createEvent(calendarUrl: String, uid: String, icalData: String): CalDavResult<Pair<String, String>> =
            CalDavResult.success(Pair("https://dav.example.test/cal/$uid.ics", "etag-1"))
        override suspend fun updateEvent(eventUrl: String, icalData: String, etag: String): CalDavResult<String> =
            CalDavResult.success("etag-2")
        override suspend fun fetchEvent(eventUrl: String): CalDavResult<CalDavEvent> =
            CalDavResult.success(CalDavEvent(eventUrl, eventUrl, "etag-1", readBackIcs))
        override suspend fun postToOutbox(outboxUrl: String, originator: String, recipients: List<String>, icalData: String): CalDavResult<OutboxResponse> {
            outboxPostCount++
            return CalDavResult.success(
                OutboxResponse(recipients.map { OutboxResponse.RecipientStatus("mailto:$it", "2.0;Success") })
            )
        }
        // Unused surface for this test.
        override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> = CalDavResult.success(serverUrl)
        override suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String> = CalDavResult.success(serverUrl)
        override suspend fun discoverCalendarHome(principalUrl: String): CalDavResult<List<String>> = CalDavResult.success(emptyList())
        override suspend fun discoverCalendarUserAddresses(principalUrl: String): CalDavResult<List<String>> = CalDavResult.success(emptyList())
        override suspend fun discoverScheduleOutboxUrl(principalUrl: String): CalDavResult<String?> = CalDavResult.success(null)
        override suspend fun supportsAutoSchedule(calendarUrl: String): CalDavResult<Boolean> = CalDavResult.success(false)
        override suspend fun listCalendars(calendarHomeUrl: String): CalDavResult<List<CalDavCalendar>> = CalDavResult.success(emptyList())
        override suspend fun getCtag(calendarUrl: String): CalDavResult<CalendarMetadataProbe> = CalDavResult.error(404, "no")
        override suspend fun getSyncToken(calendarUrl: String): CalDavResult<String?> = CalDavResult.success(null)
        override suspend fun syncCollection(calendarUrl: String, syncToken: String?): CalDavResult<SyncReport> = CalDavResult.success(SyncReport(null, emptyList(), emptyList()))
        override suspend fun fetchEventsInRange(calendarUrl: String, startMillis: Long, endMillis: Long): CalDavResult<List<CalDavEvent>> = CalDavResult.success(emptyList())
        override suspend fun fetchAllEtags(calendarUrl: String): CalDavResult<List<Pair<String, String?>>> = CalDavResult.success(emptyList())
        override suspend fun fetchEtagsInRange(calendarUrl: String, startMillis: Long, endMillis: Long): CalDavResult<List<Pair<String, String?>>> = CalDavResult.success(emptyList())
        override suspend fun fetchEventsByHref(calendarUrl: String, hrefs: List<String>): CalDavResult<List<CalDavEvent>> = CalDavResult.success(emptyList())
        override suspend fun fetchEtag(eventUrl: String): CalDavResult<String?> = CalDavResult.success("etag-1")
        override suspend fun deleteEvent(eventUrl: String, etag: String): CalDavResult<Unit> = CalDavResult.success(Unit)
        override suspend fun moveEvent(sourceUrl: String, destinationCalendarUrl: String, uid: String): CalDavResult<Pair<String, String>> = CalDavResult.error(405, "no")
        override suspend fun checkConnection(serverUrl: String): CalDavResult<Unit> = CalDavResult.success(Unit)
    }

    private fun readBackIcs() = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:uid-cycle
        DTSTAMP:20251220T100000Z
        DTSTART:20251225T140000Z
        DTEND:20251225T150000Z
        SUMMARY:Meeting
        ORGANIZER:mailto:self@example.test
        ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-AGENT=CLIENT:mailto:guest@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "self@example.test",
                calendarUserAddresses = listOf("mailto:self@example.test"), scheduleOutboxUrl = outboxUrl)
        )
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/cal/", displayName = "Cal", color = -1)
        )
        eventId = database.eventsDao().insert(
            Event(
                uid = "uid-cycle", calendarId = calendarId, title = "Meeting",
                startTs = 1_000L, endTs = 2_000L, timezone = "UTC", isAllDay = false,
                status = "CONFIRMED", organizerEmail = "self@example.test",
                dtstamp = 1_000L, sequence = 0, syncStatus = SyncStatus.PENDING_CREATE
            )
        )
        database.attendeesDao().replaceForEvent(
            eventId,
            listOf(Attendee(eventId = eventId, address = "mailto:guest@example.test", partstat = "NEEDS-ACTION"))
        )

        calendarRepository = mockk()
        accountRepository = mockk()
        coEvery { calendarRepository.getCalendarById(calendarId) } returns
            database.calendarsDao().getById(calendarId)
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns
            listOf(database.calendarsDao().getById(calendarId)!!)
        coEvery { accountRepository.getAccountById(any()) } returns
            database.accountsDao().getById(accountId)

        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun queueCreate() {
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_CREATE,
                status = PendingOperation.STATUS_PENDING
            )
        )
    }

    @Test
    fun `two consecutive push cycles POST the invitation exactly once`() = runTest {
        val client = FakeClient(readBackIcs())

        // Cycle 1: CREATE -> read-back (CLIENT) -> outbox POST, marker advanced to SEQUENCE 0.
        queueCreate()
        pushStrategy.pushAll(client)
        assertEquals("cycle 1 should send exactly one REQUEST", 1, client.outboxPostCount)

        // The marker must be persisted on the attendee row.
        val afterCycle1 = database.attendeesDao().getForEventOnce(eventId).first()
        assertEquals(0, afterCycle1.itipRequestSequence)
        assertEquals("2.0;Success", afterCycle1.itipRequestStatus)

        // Cycle 2: a fresh UPDATE re-push at the SAME SEQUENCE. The read-back's
        // replaceForEvent runs again with server-parsed rows (no marker) — the
        // merge must preserve the marker, so the gate suppresses a re-POST.
        database.pendingOperationsDao().insert(
            PendingOperation(eventId = eventId, operation = PendingOperation.OPERATION_UPDATE, status = PendingOperation.STATUS_PENDING)
        )
        pushStrategy.pushAll(client)

        assertEquals("cycle 2 must NOT re-POST (idempotent)", 1, client.outboxPostCount)
        // Marker still intact after the second read-back's replaceForEvent.
        val afterCycle2 = database.attendeesDao().getForEventOnce(eventId).first()
        assertEquals(0, afterCycle2.itipRequestSequence)
    }
}
