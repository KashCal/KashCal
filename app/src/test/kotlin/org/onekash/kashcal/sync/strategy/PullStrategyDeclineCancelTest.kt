package org.onekash.kashcal.sync.strategy

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.notification.InviteNotifier
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Tests that a decline arriving by pull cancels the event's armed alarms during the pull,
 * without waiting for the daily reminder refresh.
 *
 * Cases:
 *  (a) the user's attendee is DECLINED: cancelRemindersForEvent is called once
 *  (b) the user's attendee is ACCEPTED: not called
 *  (c) no attendee for the user before or after: not called
 *  (d) no account for the calendar (orphan calendar): not called
 *  (e) reminderScheduler throws: the pull still succeeds
 *  (f) uninvite: the stored attendees had the user, the pulled ones don't: called
 *  (g) a PARTSTAT-only change: the etag-only skip must not bypass the cancel
 */
class PullStrategyDeclineCancelTest {

    private lateinit var pullStrategy: PullStrategy

    @MockK
    private lateinit var database: KashCalDatabase

    @MockK
    private lateinit var client: CalDavClient

    @MockK
    private lateinit var calendarRepository: CalendarRepository

    @MockK
    private lateinit var eventsDao: EventsDao

    @MockK
    private lateinit var attendeesDao: AttendeesDao

    @MockK
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    @MockK
    private lateinit var dataStore: KashCalDataStore

    @MockK
    private lateinit var inviteNotifier: InviteNotifier

    @MockK
    private lateinit var accountRepository: AccountRepository

    @MockK
    private lateinit var reminderScheduler: ReminderScheduler

    private val quirks = ICloudQuirks()

    private val account = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)

        // runInTransaction runs the block directly.
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")

        coEvery { accountRepository.getAccountById(account.id) } returns account

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = attendeesDao,
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            inviteNotifier = inviteNotifier,
            accountRepository = accountRepository,
            reminderScheduler = reminderScheduler
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun calendar(accountId: Long = account.id) = Calendar(
        id = 9L,
        accountId = accountId,
        caldavUrl = "https://caldav.example.test/cal9/",
        displayName = "Work",
        color = 0xFF0000,
        ctag = null,
        syncToken = null
    )

    private fun mockTwoStepFetch(calendarUrl: String, events: List<CalDavEvent>) {
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns
            CalDavResult.success(events)
    }

    private fun icalWithSelfPartstat(uid: String, partstat: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260501T120000Z
        DTSTART:20260601T100000Z
        DTEND:20260601T110000Z
        SUMMARY:Quarterly review
        ORGANIZER;CN=Boss:mailto:boss@example.test
        ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
        ATTENDEE;CN=Self;PARTSTAT=$partstat:mailto:self@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun icalNoSelf(uid: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260501T120000Z
        DTSTART:20260601T100000Z
        DTEND:20260601T110000Z
        SUMMARY:Quarterly review
        ORGANIZER;CN=Boss:mailto:boss@example.test
        ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun primeFullSync(
        calendar: Calendar,
        ical: String,
        existing: Event? = null,
        etag: String = "etag-1"
    ): String {
        val href = "evt.ics"
        val url = "${calendar.caldavUrl}$href"
        val serverEvents = listOf(CalDavEvent(href, url, etag, ical))
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns
            (existing?.let { listOf(it) } ?: emptyList())
        coEvery { eventsDao.getByCaldavUrl(url) } returns existing
        coEvery { eventsDao.upsert(any()) } returns (existing?.id ?: 100L)
        return url
    }

    @Test
    fun `case (a) - server pull lands DECLINED self attendee triggers cancelRemindersForEvent`() = runTest {
        val cal = calendar()
        primeFullSync(cal, icalWithSelfPartstat("uid-a", "DECLINED"))

        val result = pullStrategy.pull(cal, client = client)

        assertTrue(result is PullResult.Success)
        coVerify(exactly = 1) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `case (b) - server pull lands ACCEPTED self does not trigger cancel`() = runTest {
        val cal = calendar()
        primeFullSync(cal, icalWithSelfPartstat("uid-b", "ACCEPTED"))

        pullStrategy.pull(cal, client = client)

        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `case (c) - no self attendee anywhere does not trigger cancel`() = runTest {
        val cal = calendar()
        // No stored attendee row and no pulled attendee for the user.
        coEvery { attendeesDao.getForEventOnce(any()) } returns emptyList()
        primeFullSync(cal, icalNoSelf("uid-c"))

        pullStrategy.pull(cal, client = client)

        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `case (d) - orphan calendar with null account skips cancel hook`() = runTest {
        val cal = calendar(accountId = 999L)
        coEvery { accountRepository.getAccountById(999L) } returns null
        primeFullSync(cal, icalWithSelfPartstat("uid-d", "DECLINED"))

        pullStrategy.pull(cal, client = client)

        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `case (e) - reminderScheduler throws but pull still completes`() = runTest {
        val cal = calendar()
        coEvery { reminderScheduler.cancelRemindersForEvent(any()) } throws
            IllegalStateException("alarm cancel failed")
        primeFullSync(cal, icalWithSelfPartstat("uid-e", "DECLINED"))

        val result = pullStrategy.pull(cal, client = client)

        assertTrue(
            "pull must not abort when reminder cancel throws — got $result",
            result is PullResult.Success
        )
    }

    @Test
    fun `case (f) - UNINVITE pre-replace had self row post-replace does not triggers cancel`() = runTest {
        val cal = calendar()
        // The stored attendees include the user; the pulled body omits them (the organizer
        // removed the user).
        coEvery { attendeesDao.getForEventOnce(any()) } returns listOf(
            Attendee(
                id = 0L,
                eventId = 100L,
                address = "mailto:self@example.test",
                partstat = "ACCEPTED"
            )
        )
        primeFullSync(cal, icalNoSelf("uid-f"))

        pullStrategy.pull(cal, client = client)

        coVerify(exactly = 1) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    @Test
    fun `case (g) - PARTSTAT-only delta still triggers cancel even if etag-only short-circuit applies`() = runTest {
        val cal = calendar()
        val url = "${cal.caldavUrl}evt.ics"
        // The stored event matches the pulled one except for the user's DECLINED. A
        // PARTSTAT-only change leaves hasContentChanged false, and the cancel must still run.
        val existing = Event(
            id = 100L,
            uid = "uid-g",
            calendarId = cal.id,
            title = "Quarterly review",
            startTs = 1_780_000_000_000L,
            endTs = 1_780_003_600_000L,
            dtstamp = 0L,
            caldavUrl = url,
            etag = "etag-old",
            syncStatus = SyncStatus.SYNCED
        )
        primeFullSync(cal, icalWithSelfPartstat("uid-g", "DECLINED"), existing = existing, etag = "etag-new")

        pullStrategy.pull(cal, client = client)

        coVerify(exactly = 1) { reminderScheduler.cancelRemindersForEvent(100L) }
    }
}
