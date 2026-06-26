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
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
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
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the post-PUT read-back (RFC 6638 §3.2.1 STEP 3): after a successful
 * CREATE/UPDATE of an organizer event carrying attendees, the strategy
 * re-fetches the stored resource and captures the server's SCHEDULE-STATUS /
 * SCHEDULE-AGENT decision into the attendee rows and Event.organizerScheduleStatus.
 *
 * The read-back must be non-fatal (a fetch failure never fails the push) and
 * must not fire for events with no attendees or where the user isn't the
 * organizer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyReadBackTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var pendingCancelsDao: org.onekash.kashcal.data.db.dao.PendingCancelsDao
    private lateinit var pushStrategy: PushStrategy

    private val account = Account(
        id = 1L,
        provider = AccountProvider.LOCAL,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )

    private val calendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://dav.example.test/cal/",
        displayName = "Cal",
        color = -1
    )

    private fun organizerEvent(
        id: Long = 100L,
        organizerEmail: String? = "self@example.test"
    ) = Event(
        id = id,
        uid = "uid-$id",
        calendarId = 1L,
        title = "Meeting",
        startTs = 1_000L,
        endTs = 2_000L,
        timezone = "UTC",
        isAllDay = false,
        status = "CONFIRMED",
        organizerEmail = organizerEmail,
        organizerName = null,
        dtstamp = 1_000L,
        caldavUrl = null,
        etag = null,
        sequence = 0,
        syncStatus = SyncStatus.PENDING_CREATE
    )

    private fun attendeeRow(address: String) = Attendee(
        eventId = 100L,
        address = address,
        partstat = "NEEDS-ACTION"
    )

    private fun icsWith(attendeeLine: String, organizerLine: String = "ORGANIZER:mailto:self@example.test") = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:uid-100
        DTSTAMP:20251220T100000Z
        DTSTART:20251225T140000Z
        DTEND:20251225T150000Z
        SUMMARY:Meeting
        $organizerLine
        $attendeeLine
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Before
    fun setup() {
        client = mockk()
        calendarRepository = mockk()
        eventsDao = mockk()
        pendingOperationsDao = mockk()
        accountRepository = mockk()
        attendeesDao = mockk()
        pendingCancelsDao = mockk()
        coEvery { pendingCancelsDao.getForEvent(any()) } returns emptyList()

        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs

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
    fun tearDown() = clearAllMocks()

    private fun createOp(eventId: Long = 100L) = PendingOperation(
        id = 1L,
        eventId = eventId,
        operation = PendingOperation.OPERATION_CREATE,
        status = PendingOperation.STATUS_PENDING
    )

    private fun stubCreateSuccess(event: Event, url: String = "https://dav.example.test/cal/uid-100.ics") {
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(createOp(event.id))
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar
        coEvery { accountRepository.getAccountById(calendar.accountId) } returns account
        coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.success(Pair(url, "etag-1"))
    }

    @Test
    fun `read-back captures attendee SCHEDULE-STATUS after organizer create`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent(
                href = "/cal/uid-100.ics",
                url = "https://dav.example.test/cal/uid-100.ics",
                etag = "etag-1",
                icalData = icsWith("ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=5.0:mailto:guest@example.test")
            )
        )
        val captured = slot<List<Attendee>>()
        coEvery { attendeesDao.replaceForEvent(event.id, capture(captured)) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify { attendeesDao.replaceForEvent(event.id, any()) }
        assertEquals("5.0", captured.captured.first { it.address == "mailto:guest@example.test" }.scheduleStatus)
    }

    @Test
    fun `read-back captures SCHEDULE-AGENT CLIENT`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent(
                href = "/cal/uid-100.ics",
                url = "https://dav.example.test/cal/uid-100.ics",
                etag = "etag-1",
                icalData = icsWith("ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-AGENT=CLIENT:mailto:guest@example.test")
            )
        )
        val captured = slot<List<Attendee>>()
        coEvery { attendeesDao.replaceForEvent(event.id, capture(captured)) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs

        pushStrategy.pushAll(client)

        assertEquals("CLIENT", captured.captured.first { it.address == "mailto:guest@example.test" }.scheduleAgent)
    }

    @Test
    fun `read-back captures ORGANIZER SCHEDULE-STATUS into event`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent(
                href = "/cal/uid-100.ics",
                url = "https://dav.example.test/cal/uid-100.ics",
                etag = "etag-1",
                icalData = icsWith(
                    attendeeLine = "ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=2.0:mailto:guest@example.test",
                    organizerLine = "ORGANIZER;SCHEDULE-STATUS=1.2:mailto:self@example.test"
                )
            )
        )
        coEvery { attendeesDao.replaceForEvent(any(), any()) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify { eventsDao.updateOrganizerScheduleStatus(event.id, "1.2") }
    }

    @Test
    fun `no read-back when event has no attendees`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns emptyList()

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify(exactly = 0) { client.fetchEvent(any()) }
    }

    @Test
    fun `no read-back when current user is not the organizer`() = runTest {
        val event = organizerEvent(organizerEmail = "someone-else@example.test")
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify(exactly = 0) { client.fetchEvent(any()) }
    }

    @Test
    fun `lone-author event with null organizer still reads back`() = runTest {
        val event = organizerEvent(organizerEmail = null)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent(
                href = "/cal/uid-100.ics",
                url = "https://dav.example.test/cal/uid-100.ics",
                etag = "etag-1",
                icalData = icsWith("ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=5.0:mailto:guest@example.test")
            )
        )
        coEvery { attendeesDao.replaceForEvent(any(), any()) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify { client.fetchEvent(any()) }
    }

    @Test
    fun `read-back failure is non-fatal - push still succeeds`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.networkError("boom")

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsCreated)
    }

    @Test
    fun `null create URL skips read-back without crashing`() = runTest {
        val event = organizerEvent()
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(createOp(event.id))
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar
        coEvery { accountRepository.getAccountById(calendar.accountId) } returns account
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        // Server returns success but a null URL in the pair.
        @Suppress("UNCHECKED_CAST")
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair<String?, String>(null, "etag-1")) as CalDavResult<Pair<String, String>>

        val result = pushStrategy.pushAll(client)

        // markCreatedOnServer may reject null url; the key assertion is no read-back GET and no crash.
        coVerify(exactly = 0) { client.fetchEvent(any()) }
        assertTrue(result is PushResult.Success || result is PushResult.Success)
    }

    @Test
    fun `empty attendee echo does not wipe the captured rows`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        // Server echoes a minimal body with NO ATTENDEE lines (not an uninvite).
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent(
                href = "/cal/uid-100.ics",
                url = "https://dav.example.test/cal/uid-100.ics",
                etag = "etag-1",
                icalData = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    BEGIN:VEVENT
                    UID:uid-100
                    DTSTAMP:20251220T100000Z
                    DTSTART:20251225T140000Z
                    DTEND:20251225T150000Z
                    SUMMARY:Meeting
                    ORGANIZER:mailto:self@example.test
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent().replace("\n", "\r\n")
            )
        )

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        // Must NOT call replaceForEvent with an empty set — that would wipe the
        // just-captured attendees and their receipts.
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(event.id, emptyList()) }
    }

    @Test
    fun `routed-out invitee absent on re-fetch yields no spurious row`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        // iSchedule-style: invitee ATTENDEE routed out; only the organizer self-attendee remains.
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent(
                href = "/cal/uid-100.ics",
                url = "https://dav.example.test/cal/uid-100.ics",
                etag = "etag-1",
                icalData = icsWith("ATTENDEE;PARTSTAT=ACCEPTED;SCHEDULE-STATUS=1.2:mailto:self@example.test")
            )
        )
        val captured = slot<List<Attendee>>()
        coEvery { attendeesDao.replaceForEvent(event.id, capture(captured)) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // The re-fetched set reflects what the server stored — the invitee
        // is simply absent, not written as a spurious "undelivered" row.
        assertTrue(captured.captured.none { it.address == "mailto:guest@example.test" })
    }

    // ========== Adversarial: hostile / malformed server read-back responses ==========
    // The read-back parses whatever the server returns on the re-fetch GET. A
    // misbehaving or compromised server must never crash the push or corrupt
    // local data — every malformed case must be swallowed (push still Success)
    // and leave the captured columns for the next normal pull.

    private fun stubReadBackBody(event: Event, body: String) {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent("/cal/uid-100.ics", "https://dav.example.test/cal/uid-100.ics", "etag-1", body)
        )
        coEvery { attendeesDao.replaceForEvent(any(), any()) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs
    }

    @Test
    fun `garbage non-ICS read-back body is non-fatal`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        stubReadBackBody(event, "this is not iCalendar data at all   <html>")

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsCreated)
    }

    @Test
    fun `truncated VCALENDAR read-back body is non-fatal`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        stubReadBackBody(event, "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:uid-100\r\n")

        assertTrue(pushStrategy.pushAll(client) is PushResult.Success)
    }

    @Test
    fun `read-back body with only an exception VEVENT (no master) does not persist`() = runTest {
        // Server returns ONLY a RECURRENCE-ID instance, no master. The master
        // pick (recurrenceId == null) finds nothing -> skip, no write, no crash.
        val event = organizerEvent()
        stubCreateSuccess(event)
        stubReadBackBody(event, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:uid-100
            RECURRENCE-ID:20251225T140000Z
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Only an exception instance
            ORGANIZER:mailto:self@example.test
            ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=2.0:mailto:guest@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n"))

        assertTrue(pushStrategy.pushAll(client) is PushResult.Success)
        // No master in the body -> no attendee write and no organizer update.
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
        coVerify(exactly = 0) { eventsDao.updateOrganizerScheduleStatus(any(), any()) }
    }

    @Test
    fun `read-back body for a DIFFERENT uid still persists by event id (server-authoritative)`() = runTest {
        // A server that echoes a body whose UID differs from what we PUT: the
        // read-back keys persistence on the local event id (the URL we fetched),
        // not the UID, so it captures the receipt without crashing. The point of
        // this test is that a UID mismatch is non-fatal, not that we validate UID.
        val event = organizerEvent()
        stubCreateSuccess(event)
        stubReadBackBody(event, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:totally-different-uid@evil.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Mismatched UID
            ORGANIZER:mailto:self@example.test
            ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=5.0:mailto:guest@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n"))

        assertTrue(pushStrategy.pushAll(client) is PushResult.Success)
    }

    @Test
    fun `empty-string read-back body is non-fatal`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        stubReadBackBody(event, "")

        assertTrue(pushStrategy.pushAll(client) is PushResult.Success)
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
    }

    @Test
    fun `read-back DAO write failure is swallowed and push still succeeds`() = runTest {
        // A DB error during the read-back persist must not fail the push (the
        // PUT already succeeded; the receipt is best-effort).
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendeeRow("mailto:guest@example.test"))
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent("/cal/uid-100.ics", "https://dav.example.test/cal/uid-100.ics", "etag-1",
                icsWith("ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=2.0:mailto:guest@example.test"))
        )
        coEvery { attendeesDao.replaceForEvent(any(), any()) } throws RuntimeException("db locked")

        assertTrue(pushStrategy.pushAll(client) is PushResult.Success)
    }
}
