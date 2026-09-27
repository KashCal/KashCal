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
 * Tests the client outbox sends that run after a successful push.
 *
 * - REQUEST: after the SCHEDULE-STATUS read-back, an attendee the server stamped
 *   SCHEDULE-AGENT=CLIENT (the client handles scheduling, RFC 6638 §7.1) gets a METHOD:REQUEST
 *   POSTed to the account's discovered outbox. A per-attendee SEQUENCE marker stops duplicates,
 *   the request-status class decides whether it advances, and no send failure fails the push.
 * - CANCEL: each removed guest's pending_cancels row is delivered, resolved or kept for retry
 *   (dropped at the attempt cap) by its captured delivery context, including when no guest is
 *   left.
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

    // RECURRENCE-ID 20251226T140000Z as epoch ms; as the exception's originalInstanceTime it
    // matches the parsed VEVENT to the local row.
    private val exceptionInstanceTime = 1_766_757_600_000L

    // A master and exception in one resource: the master carries the series attendee
    // (server-owned); the exception VEVENT adds a per-occurrence attendee the server stamped
    // SCHEDULE-AGENT=CLIENT, which routes to the client outbox.
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
        // Empty cancel queue by default so REQUEST tests are unaffected; CANCEL tests override
        // getForEvent.
        coEvery { pendingCancelsDao.getForEvent(any()) } returns emptyList()
        // No attendees on the serialize and read-back path by default, so CANCEL tests needn't
        // stub it; REQUEST tests override it.
        coEvery { attendeesDao.getForEventOnce(any()) } returns emptyList()

        coEvery { eventsDao.getByIds(any()) } returns emptyList()
        coEvery { calendarRepository.getCalendarsByIds(any()) } returns emptyList()
        coEvery { eventsDao.getExceptionsForMaster(any()) } returns emptyList()
        coEvery { pendingOperationsDao.markInProgress(any(), any()) } just Runs
        coEvery { pendingOperationsDao.deleteById(any()) } just Runs
        coEvery { eventsDao.markCreatedOnServer(any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just Runs
        coEvery { eventsDao.markSynced(any(), any(), any()) } just Runs
        coEvery { eventsDao.markSyncedWithCopy(any(), any(), any(), any()) } just Runs
        // Read-back collaborators: the send runs after the read-back.
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
        // alice was invited at SEQUENCE 0 (marker 0); bob is new (marker null).
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            attendee(id = 500L, address = "mailto:alice@example.test", itipRequestSequence = 0),
            attendee(id = 501L, address = "mailto:bob@example.test", itipRequestSequence = null)
        )
        val recipientsSlot = slot<List<String>>()
        coEvery { client.postToOutbox(any(), any(), capture(recipientsSlot), any()) } returns
            outboxSuccess(recipient = "mailto:bob@example.test")
        coEvery { attendeesDao.markItipRequestSent(any(), any(), any()) } just Runs

        pushStrategy.pushAll(client)

        // One POST, carrying only bob; the gate excludes alice, already sent at this SEQUENCE.
        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        assertEquals(listOf("bob@example.test"), recipientsSlot.captured)
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(501L, 0, "2.0;Success") }
        coVerify(exactly = 0) { attendeesDao.markItipRequestSent(500L, any(), any()) }
    }

    @Test
    fun `multiple client-must-deliver attendees each get their own POST and marker`() = runTest {
        // Zoho returns one schedule-response per POST whatever the recipient count, so each
        // recipient must be a separate POST, or the un-echoed ones are never marked and are
        // re-sent every cycle. Three attendees give three POSTs, each marked once.
        val event = organizerEvent(sequence = 0)
        stubCreateSuccess(event)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            attendee(id = 500L, address = "mailto:a@example.test", itipRequestSequence = null),
            attendee(id = 501L, address = "mailto:b@example.test", itipRequestSequence = null),
            attendee(id = 502L, address = "mailto:c@example.test", itipRequestSequence = null)
        )
        val recipientsPerCall = mutableListOf<List<String>>()
        // As Zoho does: each response echoes only the first recipient.
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
        // Each attendee's marker advances once, on its own 2.x receipt.
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
        // Lone-author event: organizerEmail is null. The body ORGANIZER is forced to the account
        // address: a POST's ORGANIZER must match an address of the outbox owner (RFC 6638
        // §5.2.2), and the mapper drops the ATTENDEE block on a blank organizer, which would POST
        // an empty REQUEST. canEditAsOrganizer treats a null organizer as the user's, so the
        // read-back still runs.
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
        // RFC 6638 §7.1: clients must not include SCHEDULE-AGENT in scheduling messages they
        // send. The attendee row carries scheduleAgent=CLIENT from the read-back; the
        // METHOD:REQUEST body must not.
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

        // The alias the event was organized under, not the first address.
        assertEquals("alias@example.test", originatorSlot.captured)
    }

    @Test
    fun `single recipient whose response href is a non-mailto principal path still advances the marker`() = runTest {
        // Some servers echo the schedule-response recipient as a principal href that won't
        // canonical-match the stored mailto: address. With one recipient, the first status is
        // taken so the marker advances and the invite isn't re-POSTed every cycle.
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
        // A's POST throws; B and C must still be POSTed and marked.
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
        // A threw, so it isn't marked and retries next cycle; B and C are delivered.
        coVerify(exactly = 0) { attendeesDao.markItipRequestSent(500L, any(), any()) }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(501L, 0, "2.0;Success") }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(502L, 0, "2.0;Success") }
    }

    @Test
    fun `single-recipient POST whose response lists several non-matching entries still advances by position`() = runTest {
        // One recipient was POSTed, but the server echoes two responses (for example
        // recipient and originator), neither canonical-matching the row. The first status is
        // still taken, so the marker advances instead of re-POSTing every cycle.
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

        // 5.1 is transient, so the marker stays.
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

        // 3.7 is permanent: the marker advances to stop the loop, and the raw status is stored.
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
     * Stubs a successful CREATE of a recurring [master] whose read-back returns the master and
     * [exception] in one resource, with [masterAttendees] and [exceptionAttendees] on their rows.
     */
    private fun stubExceptionReadBack(
        master: Event,
        exception: Event,
        masterAttendees: List<Attendee>,
        exceptionAttendees: List<Attendee>,
    ) {
        // The RRULE is what makes the push load and read back the bundled exceptions.
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
        // The master attendee is server-owned (no POST); the exception carries an extra
        // ClientMustDeliver attendee that must go through the outbox.
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
        // One POST, for the exception-only attendee, marked on its own row at the exception's
        // sequence.
        coVerify(exactly = 1) { client.postToOutbox(any(), any(), any(), any()) }
        assertEquals(listOf("carol@example.test"), recipientsSlot.captured)
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(600L, 0, "2.0;Success") }
        // The exception VEVENT's attendee receipts are written to the exception row.
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
        // Master and exception attendees are both ClientMustDeliver, at different SEQUENCEs.
        // Each POST marks its own row at its own event's sequence.
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

        // The master attendee at master.sequence (2), the exception attendee at
        // exception.sequence (5), each on its own row.
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(500L, 2, "2.0;Success") }
        coVerify(exactly = 1) { attendeesDao.markItipRequestSent(600L, 5, "2.0;Success") }
    }

    @Test
    fun `exception with null originalInstanceTime does not match the master VEVENT`() = runTest {
        // An exception row with a null originalInstanceTime must not match the master VEVENT
        // (whose recurrenceId is also null), or the master's attendees would be written onto
        // the exception and POSTed on the wrong row.
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

        // The exception's rows aren't overwritten from the master VEVENT.
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(eq(200L), any()) }
    }

    @Test
    fun `zero-exception master event delivery path is unchanged`() = runTest {
        // With no exceptions: one POST for the master attendee, marked on the master row at
        // master.sequence.
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
        // Resolved on 2.x success, so the row is deleted.
        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
        coVerify(exactly = 0) { pendingCancelsDao.incrementAttempt(any()) }
    }

    @Test
    fun `a per-occurrence CANCEL carries RECURRENCE-ID and no RRULE (single-instance uninvite)`() = runTest {
        // A removal from one occurrence: the CANCEL is scoped to that occurrence, so the body
        // carries RECURRENCE-ID and no RRULE.
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
        // The server already cancelled through the shrunk PUT, so there is no client POST and
        // the queue row is resolved: the guest isn't cancelled twice.
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
        // Server stance unknown (no captured receipt): nothing shows the guest was cancelled,
        // so the row is kept for a later cycle instead of losing the CANCEL.
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

        // Reaches the cap (9 + 1 >= 10), so the row is deleted, not retried.
        coVerify(exactly = 1) { pendingCancelsDao.deleteById(700L) }
        coVerify(exactly = 0) { pendingCancelsDao.incrementAttempt(any()) }
    }

    @Test
    fun `a declined removed guest with no outbox is bounded (kept) not POSTed`() = runTest {
        // Declined (SCHEDULE-AGENT=CLIENT) with no outbox discovered: the shrunk PUT didn't
        // cancel server-side, so the row is kept for a bounded retry, since a later sync may
        // discover an outbox, and isn't silently dropped.
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
        // Zoho, SOGo and Mailbox accept a CANCEL POST (HTTP 2xx) but return an empty
        // schedule-response, confirmed live, even for a real recipient. The server took it, so
        // the cancel is done; treated as transient it would re-POST every cycle up to the cap.
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
        // The last guest was removed, so the read-back's attendee check returns early. The
        // drain must not sit behind that check, or the removed guest never gets a CANCEL.
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
