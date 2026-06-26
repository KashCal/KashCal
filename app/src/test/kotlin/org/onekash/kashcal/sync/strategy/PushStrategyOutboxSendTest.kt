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
import org.onekash.kashcal.data.db.entity.PendingCancel
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.OutboxResponse
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the client-side outbox iTIP send (RFC 6638 §6) that runs in
 * the push success branch after the SCHEDULE-STATUS read-back: when an attendee
 * is classified ClientMustDeliver (server stamped SCHEDULE-AGENT=CLIENT) and the
 * account has a discovered outbox URL, the app POSTs a METHOD:REQUEST so the
 * invite reaches the attendee — gated by a per-attendee + SEQUENCE idempotency
 * marker, with class-aware retry, and strictly non-fatal to the push.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyOutboxSendTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var pendingCancelsDao: org.onekash.kashcal.data.db.dao.PendingCancelsDao
    private lateinit var pushStrategy: PushStrategy

    private val outboxUrl = "https://dav.example.test/caldav/me/outbox/"

    private val account = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test"),
        scheduleOutboxUrl = outboxUrl
    )

    private val accountNoOutbox = account.copy(scheduleOutboxUrl = null)

    private val calendar = Calendar(
        id = 1L, accountId = 1L,
        caldavUrl = "https://dav.example.test/cal/", displayName = "Cal", color = -1
    )

    private fun organizerEvent(sequence: Int = 0) = Event(
        id = 100L, uid = "uid-100", calendarId = 1L, title = "Meeting",
        startTs = 1_000L, endTs = 2_000L, timezone = "UTC", isAllDay = false,
        status = "CONFIRMED", organizerEmail = "self@example.test", organizerName = null,
        dtstamp = 1_000L, caldavUrl = null, etag = null, sequence = sequence,
        syncStatus = SyncStatus.PENDING_CREATE
    )

    private fun attendee(
        id: Long = 500L,
        address: String = "mailto:guest@example.test",
        scheduleAgent: String? = "CLIENT",
        scheduleStatus: String? = null,
        itipRequestSequence: Int? = null
    ) = Attendee(
        id = id, eventId = 100L, address = address, partstat = "NEEDS-ACTION",
        scheduleAgent = scheduleAgent, scheduleStatus = scheduleStatus,
        itipRequestSequence = itipRequestSequence
    )

    private val readBackIcs = """
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
        ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-AGENT=CLIENT:mailto:guest@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    // ===== Per-occurrence (exception) delivery fixtures =====

    // RECURRENCE-ID 20251226T140000Z as epoch ms (== the exception's
    // originalInstanceTime, matching the parsed VEVENT to the local row).
    private val exceptionInstanceTime = 1_766_757_600_000L

    // A bundled master+override resource: the master carries the series
    // attendee (server-owned), the override VEVENT carries an EXTRA
    // per-occurrence attendee that the server stamped SCHEDULE-AGENT=CLIENT
    // (so it routes to the client outbox).
    private val readBackIcsWithException = """
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
        ATTENDEE;PARTSTAT=ACCEPTED;SCHEDULE-STATUS=1.2:mailto:alice@example.test
        END:VEVENT
        BEGIN:VEVENT
        UID:uid-100
        RECURRENCE-ID:20251226T140000Z
        DTSTAMP:20251220T100000Z
        DTSTART:20251226T140000Z
        DTEND:20251226T150000Z
        SUMMARY:Meeting
        ORGANIZER:mailto:self@example.test
        ATTENDEE;PARTSTAT=ACCEPTED;SCHEDULE-STATUS=1.2:mailto:alice@example.test
        ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-AGENT=CLIENT:mailto:carol@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    private fun exceptionEvent(sequence: Int = 0) = Event(
        id = 200L, uid = "uid-100", calendarId = 1L, title = "Meeting",
        startTs = exceptionInstanceTime, endTs = exceptionInstanceTime + 3_600_000L,
        timezone = "UTC", isAllDay = false, status = "CONFIRMED",
        organizerEmail = "self@example.test", organizerName = null,
        dtstamp = 1_000L, caldavUrl = null, etag = null, sequence = sequence,
        syncStatus = SyncStatus.SYNCED,
        originalEventId = 100L, originalInstanceTime = exceptionInstanceTime
    )

    private fun exceptionAttendee(
        id: Long = 600L,
        address: String = "mailto:carol@example.test",
        scheduleAgent: String? = "CLIENT",
        scheduleStatus: String? = null,
        itipRequestSequence: Int? = null
    ) = Attendee(
        id = id, eventId = 200L, address = address, partstat = "NEEDS-ACTION",
        scheduleAgent = scheduleAgent, scheduleStatus = scheduleStatus,
        itipRequestSequence = itipRequestSequence
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
        // Default: empty cancel queue so REQUEST-send tests are unaffected;
        // cancel-drain tests override getForEvent.
        coEvery { pendingCancelsDao.getForEvent(any()) } returns emptyList()
        // Default: no surviving attendees on the serialize/read-back path so
        // cancel-drain tests (which don't care about the REQUEST path) don't
        // need to stub it; REQUEST-send tests override per-test.
        coEvery { attendeesDao.getForEventOnce(any()) } returns emptyList()

        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        // Read-back collaborators (the send runs after the read-back in the branch).
        coEvery { attendeesDao.replaceForEvent(any(), any()) } just Runs
        coEvery { eventsDao.updateOrganizerScheduleStatus(any(), any()) } just Runs
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent("/cal/uid-100.ics", "https://dav.example.test/cal/uid-100.ics", "etag-1", readBackIcs)
        )

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

    private fun createOp() = PendingOperation(
        id = 1L, eventId = 100L,
        operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_PENDING
    )

    private fun stubCreateSuccess(event: Event, acct: Account = account) {
        coEvery { pendingOperationsDao.getReadyOperations(any()) } returns listOf(createOp())
        coEvery { eventsDao.getById(event.id) } returns event
        coEvery { calendarRepository.getCalendarById(event.calendarId) } returns calendar
        coEvery { accountRepository.getAccountById(calendar.accountId) } returns acct
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.success(Pair("https://dav.example.test/cal/uid-100.ics", "etag-1"))
    }

    private fun outboxSuccess(recipient: String = "mailto:guest@example.test", status: String = "2.0;Success") =
        CalDavResult.success(OutboxResponse(listOf(OutboxResponse.RecipientStatus(recipient, status))))

    @Test
    fun `posts to outbox when attendee is ClientMustDeliver and account has outbox URL`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        val recipientsSlot = slot<List<String>>()
        val originatorSlot = slot<String>()
        coEvery {
            client.postToOutbox(eq(outboxUrl), capture(originatorSlot), capture(recipientsSlot), any())
        } returns outboxSuccess()
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        coVerify(exactly = 1) { client.postToOutbox(eq(outboxUrl), any(), any(), any()) }
        assertEquals("self@example.test", originatorSlot.captured)
        assertEquals(listOf("guest@example.test"), recipientsSlot.captured)
        // 2.x success advances the marker to the event's current SEQUENCE.
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 0, "2.0;Success") }
    }

    @Test
    fun `no outbox POST when attendee delivery is server-owned (SCHEDULE-STATUS present)`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns
            listOf(attendee(scheduleAgent = null, scheduleStatus = "5.0"))

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
    }

    @Test
    fun `no outbox POST when no receipt (inert server, NoReceipt)`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns
            listOf(attendee(scheduleAgent = null, scheduleStatus = null))

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
    }

    @Test
    fun `no outbox POST when account has no discovered outbox URL`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event, acct = accountNoOutbox)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
    }

    @Test
    fun `no outbox POST when marker equals current SEQUENCE (idempotent re-push)`() = runTest {
        val event = organizerEvent(sequence = 0)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns
            listOf(attendee(itipRequestSequence = 0))

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
    }

    @Test
    fun `outbox POST fires when SEQUENCE advanced beyond the marker (substantive edit)`() = runTest {
        val event = organizerEvent(sequence = 1)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns
            listOf(attendee(itipRequestSequence = 0))
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns outboxSuccess()
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 1, "2.0;Success") }
    }

    @Test
    fun `late-added attendee gets a REQUEST while an already-sent attendee at same SEQUENCE does not`() = runTest {
        val event = organizerEvent(sequence = 0)
        stubCreateSuccess(event)
        // alice already invited at SEQUENCE 0 (marker=0); bob is new (marker null).
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            attendee(id = 500L, address = "mailto:alice@example.test", itipRequestSequence = 0),
            attendee(id = 501L, address = "mailto:bob@example.test", itipRequestSequence = null)
        )
        val recipientsSlot = slot<List<String>>()
        coEvery { client.postToOutbox(any(), any(), capture(recipientsSlot), any()) } returns
            outboxSuccess(recipient = "mailto:bob@example.test")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // Exactly one POST, carrying only bob; alice (already at this SEQUENCE)
        // is excluded by the gate.
        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        assertEquals(listOf("bob@example.test"), recipientsSlot.captured)
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(501L, 0, "2.0;Success") }
        coVerify(exactly = 0) { attendeesDao.markItipRequestSent(500L, any(), any()) }
    }

    @Test
    fun `multiple client-must-deliver attendees each get their own POST and marker`() = runTest {
        // The audit case: a real server (Zoho) returns ONE schedule-response per
        // POST regardless of recipient count, so each recipient MUST be a
        // separate POST or the un-echoed ones never get marked and are re-sent
        // (spam) every cycle. Verify N attendees -> N POSTs, each marked once.
        val event = organizerEvent(sequence = 0)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            attendee(id = 500L, address = "mailto:a@example.test", itipRequestSequence = null),
            attendee(id = 501L, address = "mailto:b@example.test", itipRequestSequence = null),
            attendee(id = 502L, address = "mailto:c@example.test", itipRequestSequence = null)
        )
        val recipientsPerCall = mutableListOf<List<String>>()
        // Mimic Zoho: each response echoes only ONE recipient (the first one).
        coEvery { client.postToOutbox(any(), any(), capture(recipientsPerCall), any()) } answers {
            val rcpts = thirdArg<List<String>>()
            outboxSuccess(recipient = "mailto:${rcpts.first()}")
        }
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // Three separate single-recipient POSTs.
        coVerify(exactly = 3) { client.postToOutbox(any(), any(), any(), any()) }
        assertTrue("each POST carries exactly one recipient", recipientsPerCall.all { it.size == 1 })
        assertEquals(
            setOf("a@example.test", "b@example.test", "c@example.test"),
            recipientsPerCall.flatten().toSet()
        )
        // Each attendee's marker advanced exactly once on its own 2.x receipt.
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 0, "2.0;Success") }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(501L, 0, "2.0;Success") }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(502L, 0, "2.0;Success") }
    }

    @Test
    fun `mixed event sends only the ClientMustDeliver attendee as a recipient`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            attendee(id = 500L, address = "mailto:client@example.test", scheduleAgent = "CLIENT"),
            attendee(id = 501L, address = "mailto:server@example.test", scheduleAgent = null, scheduleStatus = "1.2")
        )
        val recipientsSlot = slot<List<String>>()
        coEvery { client.postToOutbox(any(), any(), capture(recipientsSlot), any()) } returns
            outboxSuccess(recipient = "mailto:client@example.test")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        assertEquals(listOf("client@example.test"), recipientsSlot.captured)
    }

    @Test
    fun `POSTed REQUEST body carries the account address as ORGANIZER and keeps the attendee (lone-author event)`() = runTest {
        // Lone-author event: organizerEmail is null. The body ORGANIZER must be
        // forced to the account address (RFC 6638 §6), else (a) the server rewrites/rejects
        // it and (b) the mapper drops the ATTENDEE block on a blank organizer,
        // POSTing an empty REQUEST. A null-organizer event still reads back as
        // organizer-owned (canEditAsOrganizer treats null as "mine").
        val event = organizerEvent().copy(organizerEmail = null)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        val icsSlot = slot<String>()
        coEvery { client.postToOutbox(any(), any(), any(), capture(icsSlot)) } returns outboxSuccess()
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        val body = icsSlot.captured
        assertTrue("body must carry the account address as ORGANIZER",
            body.contains("ORGANIZER:mailto:self@example.test") ||
                body.contains("ORGANIZER;", ignoreCase = true) && body.contains("self@example.test"))
        assertTrue("body must still include the invitee ATTENDEE",
            body.contains("guest@example.test"))
        // RFC 6638 §7.1: a client MUST NOT echo SCHEDULE-AGENT in a scheduling
        // message it sends. The attendee row carries scheduleAgent=CLIENT (from
        // the read-back); the METHOD:REQUEST body must NOT leak it.
        assertTrue("METHOD:REQUEST must not leak SCHEDULE-AGENT",
            !body.contains("SCHEDULE-AGENT", ignoreCase = true))
        assertTrue("body must be a METHOD:REQUEST", body.contains("METHOD:REQUEST"))
    }

    @Test
    fun `originator prefers the account address matching the event organizer when several exist`() = runTest {
        // Account holds two addresses; the event's ORGANIZER is the second.
        val multiAddr = account.copy(
            calendarUserAddresses = listOf("mailto:primary@example.test", "mailto:alias@example.test")
        )
        val event = organizerEvent().copy(organizerEmail = "alias@example.test")
        stubCreateSuccess(event, acct = multiAddr)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        val originatorSlot = slot<String>()
        coEvery { client.postToOutbox(any(), capture(originatorSlot), any(), any()) } returns outboxSuccess()
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // Must emit the alias the event was organized under, not the first address.
        assertEquals("alias@example.test", originatorSlot.captured)
    }

    @Test
    fun `single recipient whose response href is a non-mailto principal path still advances the marker`() = runTest {
        // Some servers echo the schedule-response recipient as a principal href
        // that won't canonical-match the stored mailto: address. With a single
        // recipient + single response, attribute positionally so the marker
        // advances and the invite is not re-POSTed every cycle.
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            CalDavResult.success(
                OutboxResponse(listOf(OutboxResponse.RecipientStatus("/principals/users/guest/", "2.0;Success")))
            )
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 0, "2.0;Success") }
    }

    @Test
    fun `one recipient throwing does not starve the others in the same cycle`() = runTest {
        // Per-recipient isolation: a build/POST failure for attendee A must not
        // skip B and C. A's POST throws; B and C must still be POSTed + marked.
        val event = organizerEvent(sequence = 0)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            attendee(id = 500L, address = "mailto:a@example.test", itipRequestSequence = null),
            attendee(id = 501L, address = "mailto:b@example.test", itipRequestSequence = null),
            attendee(id = 502L, address = "mailto:c@example.test", itipRequestSequence = null)
        )
        coEvery { client.postToOutbox(any(), any(), match { it.contains("a@example.test") }, any()) } throws
            RuntimeException("boom for A")
        coEvery { client.postToOutbox(any(), any(), match { it.contains("b@example.test") }, any()) } returns
            outboxSuccess(recipient = "mailto:b@example.test")
        coEvery { client.postToOutbox(any(), any(), match { it.contains("c@example.test") }, any()) } returns
            outboxSuccess(recipient = "mailto:c@example.test")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        // A threw -> not marked (retries next cycle); B and C still delivered.
        coVerify(exactly = 0) { attendeesDao.markItipRequestSent(500L, any(), any()) }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(501L, 0, "2.0;Success") }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(502L, 0, "2.0;Success") }
    }

    @Test
    fun `single-recipient POST whose response lists several non-matching entries still advances by position`() = runTest {
        // Exactly one recipient was POSTed, but the server echoes >1 responses
        // (e.g. recipient + originator) none canonical-matching the row. The
        // positional fallback must still attribute the (first) status so the
        // marker advances instead of re-POSTing every cycle.
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            CalDavResult.success(
                OutboxResponse(
                    listOf(
                        OutboxResponse.RecipientStatus("/principals/users/guest/", "2.0;Success"),
                        OutboxResponse.RecipientStatus("mailto:organizer-echo@example.test", "2.0;Success")
                    )
                )
            )
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 0, "2.0;Success") }
    }

    @Test
    fun `transient 5_1 failure leaves the marker unadvanced for retry`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            outboxSuccess(status = "5.1;Service unavailable")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // 5.1 is retryable — must NOT advance the marker.
        coVerify(exactly = 0) { attendeesDao.markItipRequestSent(any(), any(), any()) }
    }

    @Test
    fun `permanent 3_7 failure advances the marker to stop the loop and stores the status`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            outboxSuccess(status = "3.7;Invalid calendar user")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // 3.7 is permanent — advance marker (stop) and persist the raw code.
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 0, "3.7;Invalid calendar user") }
    }

    @Test
    fun `outbox POST network error is non-fatal - push still succeeds and marker unadvanced`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            CalDavResult.networkError("boom")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertEquals(1, (result as PushResult.Success).eventsCreated)
        coVerify(exactly = 0) { attendeesDao.markItipRequestSent(any(), any(), any()) }
    }

    @Test
    fun `outbox POST throwing is swallowed and push still succeeds`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } throws RuntimeException("kaboom")

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
    }

    // ===== Per-occurrence (exception) attendee delivery =====

    /**
     * Stub a CREATE-success push whose read-back returns a bundled
     * master+override resource, with [exceptions] as the master's exception
     * rows and per-event attendee stubs.
     */
    private fun stubExceptionReadBack(
        master: Event,
        exception: Event,
        masterAttendees: List<Attendee>,
        exceptionAttendees: List<Attendee>,
    ) {
        // A real series master carries an RRULE — that's what makes the push
        // load + read back the bundled exceptions.
        val recurringMaster = master.copy(rrule = "FREQ=DAILY;COUNT=10")
        stubCreateSuccess(recurringMaster)
        coEvery { client.fetchEvent(any()) } returns CalDavResult.success(
            CalDavEvent("/cal/uid-100.ics", "https://dav.example.test/cal/uid-100.ics", "etag-1", readBackIcsWithException)
        )
        coEvery { eventsDao.getExceptionsForMaster(recurringMaster.id) } returns listOf(exception)
        coEvery { attendeesDao.getForEventOnce(recurringMaster.id) } returns masterAttendees
        coEvery { attendeesDao.getForEventOnce(exception.id) } returns exceptionAttendees
    }

    @Test
    fun `exception-only attendee gets an outbox POST keyed on the exception's own row`() = runTest {
        val master = organizerEvent(sequence = 0)
        val exception = exceptionEvent(sequence = 0)
        // Master attendee is server-owned (no POST); the exception carries an
        // extra ClientMustDeliver attendee that must be delivered via outbox.
        stubExceptionReadBack(
            master = master,
            exception = exception,
            masterAttendees = listOf(attendee(id = 500L, address = "mailto:alice@example.test", scheduleAgent = null, scheduleStatus = "1.2")),
            exceptionAttendees = listOf(exceptionAttendee(id = 600L)),
        )
        val recipientsSlot = slot<List<String>>()
        coEvery { client.postToOutbox(any(), any(), capture(recipientsSlot), any()) } returns
            outboxSuccess(recipient = "mailto:carol@example.test")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        // Exactly one POST, for the exception-only attendee, marked on its own
        // row id at the exception's sequence.
        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        assertEquals(listOf("carol@example.test"), recipientsSlot.captured)
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(600L, 0, "2.0;Success") }
        // The override's own attendee receipts are written to the exception row.
        coVerify { attendeesDao.replaceForEvent(eq(200L), any()) }
    }

    @Test
    fun `exception-only attendee already sent at the exception SEQUENCE gets no duplicate POST`() = runTest {
        val master = organizerEvent(sequence = 0)
        val exception = exceptionEvent(sequence = 0)
        stubExceptionReadBack(
            master = master,
            exception = exception,
            masterAttendees = listOf(attendee(id = 500L, address = "mailto:alice@example.test", scheduleAgent = null, scheduleStatus = "1.2")),
            // Already sent at SEQUENCE 0.
            exceptionAttendees = listOf(exceptionAttendee(id = 600L, itipRequestSequence = 0)),
        )

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
    }

    @Test
    fun `master and exception attendees are marked on their own rows and SEQUENCEs (no cross-contamination)`() = runTest {
        // Master attendee and exception attendee BOTH ClientMustDeliver, at
        // different SEQUENCEs. Each POST must mark its own row at its own
        // event's sequence — neither leaks the other's id/sequence.
        val master = organizerEvent(sequence = 2)
        val exception = exceptionEvent(sequence = 5)
        stubExceptionReadBack(
            master = master,
            exception = exception,
            masterAttendees = listOf(attendee(id = 500L, address = "mailto:alice@example.test")),
            exceptionAttendees = listOf(exceptionAttendee(id = 600L)),
        )
        coEvery { client.postToOutbox(any(), any(), any(), any()) } answers {
            val rcpts = thirdArg<List<String>>()
            outboxSuccess(recipient = "mailto:${rcpts.first()}")
        }
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // Master attendee marked at master.sequence (2); exception attendee at
        // exception.sequence (5) — on their own row ids.
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 2, "2.0;Success") }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(600L, 5, "2.0;Success") }
    }

    @Test
    fun `exception with null originalInstanceTime does not match the master VEVENT`() = runTest {
        // A malformed exception row with a null instance anchor must NOT match
        // the master VEVENT (whose recurrenceId is also null) — otherwise the
        // master's attendees would be written onto the exception and POSTed on
        // the wrong row.
        val master = organizerEvent(sequence = 0)
        val exception = exceptionEvent(sequence = 0).copy(originalInstanceTime = null)
        stubExceptionReadBack(
            master = master,
            exception = exception,
            masterAttendees = listOf(attendee(id = 500L, address = "mailto:alice@example.test", scheduleAgent = null, scheduleStatus = "1.2")),
            exceptionAttendees = listOf(exceptionAttendee(id = 600L)),
        )
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns outboxSuccess()
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // The exception's rows must not be overwritten from the master VEVENT.
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(eq(200L), any()) }
    }

    @Test
    fun `zero-exception master event delivery path is unchanged`() = runTest {
        // Regression: with no exceptions, the push must behave byte-for-byte
        // like before the exception loop — one POST for the master attendee,
        // marked on the master row at master.sequence, and no extra fetches.
        val event = organizerEvent(sequence = 0)
        stubCreateSuccess(event)
        // setup() already stubs getExceptionsForMaster -> emptyList.
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(attendee())
        val recipientsSlot = slot<List<String>>()
        coEvery { client.postToOutbox(any(), any(), capture(recipientsSlot), any()) } returns outboxSuccess()
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        assertEquals(listOf("guest@example.test"), recipientsSlot.captured)
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 0, "2.0;Success") }
    }

    // ===== Removed-attendee CANCEL drain =====

    private fun pendingCancel(
        id: Long = 700L,
        address: String = "mailto:gone@example.test",
        scheduleAgent: String? = "CLIENT",
        scheduleStatus: String? = null,
        sequence: Int = 1,
        attemptCount: Int = 0,
        recurrenceId: Long? = null,
    ) = PendingCancel(
        id = id, eventId = 100L, recurrenceId = recurrenceId, address = address,
        scheduleAgent = scheduleAgent, scheduleStatus = scheduleStatus,
        sequence = sequence, attemptCount = attemptCount
    )

    @Test
    fun `a ClientMustDeliver removed guest gets a METHOD CANCEL then the row is deleted`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns listOf(pendingCancel())
        val icsSlot = slot<String>()
        val recipientsSlot = slot<List<String>>()
        coEvery { client.postToOutbox(any(), any(), capture(recipientsSlot), capture(icsSlot)) } returns
            outboxSuccess(recipient = "mailto:gone@example.test")
        coEvery { pendingCancelsDao.deleteById(any()) } just Runs

        val result = pushStrategy.pushAll(client)

        assertTrue(result is PushResult.Success)
        assertTrue("body must be a METHOD:CANCEL", icsSlot.captured.contains("METHOD:CANCEL"))
        assertEquals(listOf("gone@example.test"), recipientsSlot.captured)
        // Resolved on 2.x success -> row deleted.
        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
        coVerify(exactly = 0) { pendingCancelsDao.incrementAttempt(any()) }
    }

    @Test
    fun `a per-occurrence CANCEL carries RECURRENCE-ID and no RRULE (single-instance uninvite)`() = runTest {
        // Just-this removal: the CANCEL must scope to the one instance, not the
        // whole series — so the body carries RECURRENCE-ID and omits RRULE.
        val event = organizerEvent().copy(rrule = "FREQ=DAILY;COUNT=5")
        stubCreateSuccess(event)
        val instanceMs = 1_766_757_600_000L // 20251226T140000Z
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns
            listOf(pendingCancel(recurrenceId = instanceMs))
        val icsSlot = slot<String>()
        coEvery { client.postToOutbox(any(), any(), any(), capture(icsSlot)) } returns
            outboxSuccess(recipient = "mailto:gone@example.test")
        coEvery { pendingCancelsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        val body = icsSlot.captured
        assertTrue("per-occurrence CANCEL must carry RECURRENCE-ID", body.contains("RECURRENCE-ID"))
        assertTrue("per-occurrence CANCEL must NOT carry the series RRULE", !body.contains("RRULE"))
        assertTrue("body must be a METHOD:CANCEL", body.contains("METHOD:CANCEL"))
    }

    @Test
    fun `a server-scheduled removed guest is NOT POSTed (shrunk PUT cancels) and the row is deleted`() = runTest {
        // The implicit fleet already cancelled via the shrunk PUT — no client
        // POST, just resolve the queue row. The no-double-send guarantee.
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns
            listOf(pendingCancel(scheduleAgent = null, scheduleStatus = "1.2"))
        coEvery { pendingCancelsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
    }

    @Test
    fun `a NoReceipt removed guest is kept to retry (not deleted, not POSTed)`() = runTest {
        // Server stance unknown (no captured receipt): can't prove the guest was
        // cancelled, so keep the row for a later cycle rather than lose the CANCEL.
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns
            listOf(pendingCancel(scheduleAgent = null, scheduleStatus = null))
        coEvery { pendingCancelsDao.incrementAttempt(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingCancelsDao.deleteById(any()) }
        coVerify(exactly = 1) { pendingCancelsDao.incrementAttempt(700L) }
    }

    @Test
    fun `a NoReceipt removed guest is abandoned once it exhausts the attempt cap`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns
            listOf(pendingCancel(scheduleAgent = null, scheduleStatus = null, attemptCount = 9))
        coEvery { pendingCancelsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        // Hit the cap (9 + 1 >= 10) -> abandoned (deleted), not retried.
        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
        coVerify(exactly = 0) { pendingCancelsDao.incrementAttempt(any()) }
    }

    @Test
    fun `a declined removed guest with no outbox is bounded (kept) not POSTed`() = runTest {
        // Declined (SCHEDULE-AGENT=CLIENT) but no outbox discovered: the shrunk
        // PUT did NOT cancel server-side, so the row must be KEPT (bounded retry)
        // — an outbox may be discovered later — not silently dropped.
        val event = organizerEvent()
        stubCreateSuccess(event, acct = accountNoOutbox)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns listOf(pendingCancel())
        coEvery { pendingCancelsDao.incrementAttempt(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { client.postToOutbox(any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingCancelsDao.deleteById(any()) }
        coVerify(exactly = 1) { pendingCancelsDao.incrementAttempt(700L) }
    }

    @Test
    fun `an accepted CANCEL with an empty schedule-response resolves the row (no retry)`() = runTest {
        // Zoho/SOGo/Mailbox accept a CANCEL POST (HTTP 2xx) but return an EMPTY
        // schedule-response — confirmed live, even for a real recipient. The
        // server took ownership; treat the cancel as done, NOT transient (which
        // would re-POST every cycle up to the attempt cap).
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns listOf(pendingCancel())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            CalDavResult.success(OutboxResponse(emptyList()))
        coEvery { pendingCancelsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
        coVerify(exactly = 0) { pendingCancelsDao.incrementAttempt(any()) }
    }

    @Test
    fun `a transient CANCEL failure keeps the row to retry`() = runTest {
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns listOf(pendingCancel())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            outboxSuccess(status = "5.1;Service unavailable")
        coEvery { pendingCancelsDao.incrementAttempt(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 0) { pendingCancelsDao.deleteById(any()) }
        coVerify(exactly = 1) { pendingCancelsDao.incrementAttempt(700L) }
    }

    @Test
    fun `the CANCEL drain is reachable when the last guest was removed (zero survivors)`() = runTest {
        // Remove-last-guest: the event has NO surviving attendees, so the
        // read-back's attendee-presence gate returns early. The drain must NOT
        // sit behind that gate, or the dropped guest never gets a CANCEL.
        val event = organizerEvent()
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns emptyList() // no survivors
        coEvery { pendingCancelsDao.getForEvent(event.id) } returns listOf(pendingCancel())
        coEvery { client.postToOutbox(any(), any(), any(), any()) } returns
            outboxSuccess(recipient = "mailto:gone@example.test")
        coEvery { pendingCancelsDao.deleteById(any()) } just Runs

        pushStrategy.pushAll(client)

        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
    }
}
