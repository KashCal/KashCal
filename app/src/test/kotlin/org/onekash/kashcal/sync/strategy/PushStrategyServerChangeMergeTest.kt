package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When an upload meets a 412 because the event changed on the server, the retry keeps both the
 * server's change and the user's edit, written with the etag of the one GET it was built from, and
 * this cycle's pull then brings the merged copy in, dropping a changed occurrence deleted
 * elsewhere; the changed occurrences it sent count as pushed. A retry that can't be made (resource
 * gone, a GET with no etag) goes to conflict resolution without writing, and a merged write refused
 * with a second 412 goes there too; a failed GET is rescheduled and a failed merged write stays
 * queued, neither as a conflict, and a server copy that can't be read fails the op. A reply built
 * on a retried reply leaves the organizer's edit for the pull too.
 *
 * Real writer, push, pull, client and Room, against a server that keeps its resources, refuses
 * a stale If-Match and answers the pull (ctag, sync-collection, multiget). The pull is given the
 * push result's sets as CalDavSyncEngine passes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyServerChangeMergeTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var pullStrategy: PullStrategy
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var account: Account
    private val dav = FakeDavCalendar()
    private var calendarId = 0L
    private var eventId = 0L

    private val self = "mailto:self@example.test"
    private val guest = "mailto:guest@example.test"
    private val path = "/cal/planning.ics"
    private val url get() = server.url(path).toString()

    // The organizer's meeting, as the server holds it before the guest answers.
    private val meeting = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other//Client 1.0//EN",
        "BEGIN:VEVENT", "UID:planning@example.test", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z",
        "SUMMARY:Planning", "SEQUENCE:0",
        "ORGANIZER;CN=Self:$self",
        "ATTENDEE;CN=Self;ROLE=CHAIR;PARTSTAT=ACCEPTED:$self",
        "ATTENDEE;CN=Guest;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$guest",
        "END:VEVENT", "END:VCALENDAR", "",
    ).joinToString("\r\n")

    @Before
    fun setup() = runTest {
        server = MockWebServer()
        server.dispatcher = dav
        server.start()
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base)
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()
        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "self@example.test", calendarUserAddresses = listOf(self))
        )
        account = database.accountsDao().getById(accountId)!!
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = server.url(dav.collectionPath).toString(), displayName = "Cal", color = -1)
        )
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(accountId) } returns account
        val calendarRepository = CalendarRepositoryImpl(database.calendarsDao())
        pushStrategy = PushStrategy(
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        every { dataStore.defaultReminderMinutes } returns flowOf(-1)
        every { dataStore.defaultAllDayReminder } returns flowOf(-1)
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = DefaultQuirks(base),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /**
     * The server holds [ics]; KashCal stored it as pull does (every row carries the whole body,
     * the URL and the etag) and has the calendar's current ctag and token. Returns the changed
     * occurrences' row ids by instance time.
     */
    private suspend fun seedPulled(ics: String = meeting): Map<Long, Long> {
        dav.put(path, ics)
        val parsed = ICalParser().parseAllEvents(ics).getOrNull()!!
        val seriesParsed = parsed.single { it.recurrenceId == null }
        val mapped = ICalEventMapper.toEntity(seriesParsed, ics, calendarId, url, dav.etag(path))
        eventId = database.eventsDao().insert(mapped.event)
        database.attendeesDao().replaceForEvent(eventId, mapped.attendees.map { it.copy(eventId = eventId) })
        occurrenceGenerator.regenerateOccurrences(database.eventsDao().getById(eventId)!!)
        val occurrences = parsed.filter { it.recurrenceId != null }.associate { change ->
            val m = ICalEventMapper.toEntity(change, ics, calendarId, url, dav.etag(path), masterDtStart = seriesParsed.dtStart)
            val id = database.eventsDao().insert(m.event.copy(originalEventId = eventId))
            database.attendeesDao().replaceForEvent(id, m.attendees.map { it.copy(eventId = id) })
            occurrenceGenerator.linkException(eventId, m.event.originalInstanceTime!!, database.eventsDao().getById(id)!!)
            m.event.originalInstanceTime!! to id
        }
        database.calendarsDao().update(calendar().copy(ctag = dav.ctag, syncToken = dav.syncToken))
        return occurrences
    }

    private suspend fun rename(title: String) {
        val row = database.eventsDao().getById(eventId)!!
        eventWriter.updateEvent(row.copy(title = title))
    }

    private suspend fun calendar() = database.calendarsDao().getById(calendarId)!!

    private suspend fun push() = pushStrategy.pushForCalendar(calendar(), client) as PushResult.Success

    /** Push, then pull the way CalDavSyncEngine runs the two in one cycle. */
    private suspend fun sync(): PushResult.Success {
        val pushed = push()
        pullStrategy.pull(
            calendar(), client = client,
            recentlyPushedEventIds = pushed.pushedEventIds, refetchEventIds = pushed.refetchEventIds
        )
        return pushed
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")
    private fun summary(ics: String) = Regex("""SUMMARY:(.*)""").find(unfold(ics))!!.groupValues[1].trim()
    private fun partstat(ics: String, address: String) =
        unfold(ics).lines().first { it.startsWith("ATTENDEE") && it.trimEnd().endsWith(address) }
            .let { Regex("""PARTSTAT=([A-Z-]+)""").find(it)!!.groupValues[1] }

    private suspend fun localGuestPartstat() =
        database.attendeesDao().getForEventOnce(eventId).single { it.address.endsWith("guest@example.test") }.partstat

    // ---- keeping both changes ----

    @Test
    fun `an edit made before pulling a guest's answer keeps the answer on the server`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$guest", "PARTSTAT=ACCEPTED;RSVP=TRUE:$guest") }
        rename("Planning renamed")

        push()

        assertEquals("Planning renamed", summary(dav.body(path)))
        assertEquals("ACCEPTED", partstat(dav.body(path), guest))
    }

    @Test
    fun `another device's note and KashCal's rename both reach the server`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note") }
        rename("Planning renamed")

        push()

        assertEquals("Planning renamed", summary(dav.body(path)))
        assertTrue("Other device note" in unfold(dav.body(path)))
    }

    @Test
    fun `the merged write carries the etag of the copy it was built from`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note") }
        rename("Planning renamed")

        push()

        assertEquals(listOf("\"e1\"", "\"e2\""), dav.puts.map { it.third })
        val between = dav.requests.dropWhile { !it.startsWith("PUT") }.drop(1).takeWhile { !it.startsWith("PUT") }
        assertEquals("one GET of the server's copy between the two writes", listOf("GET $path"), between.filter { it.startsWith("GET") })
    }

    @Test
    fun `after the merged write this cycle's pull shows both changes in KashCal`() = runTest {
        seedPulled()
        dav.editElsewhere(path) {
            it.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$guest", "PARTSTAT=ACCEPTED;RSVP=TRUE:$guest")
                .replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note")
        }
        rename("Planning renamed")

        val pushed = sync()

        assertTrue("still protected from this cycle's deletions", eventId in pushed.pushedEventIds)
        val row = database.eventsDao().getById(eventId)!!
        assertEquals("Planning renamed", row.title)
        assertEquals("Other device note", row.description)
        assertEquals("ACCEPTED", localGuestPartstat())
        assertEquals(dav.etag(path), row.etag)
        // The multiget's XML carries the body with its CRLFs read as LFs and its end trimmed.
        assertEquals(dav.body(path).replace("\r\n", "\n").trim(), row.rawIcal!!.replace("\r\n", "\n").trim())
    }

    @Test
    fun `a server drift that only changed a guest's answer recovers in one retry without a conflict`() = runTest {
        // The Cyrus shape (#311): the server rewrites the organizer's copy when it processes a
        // reply, so the etag moves while only the guest's PARTSTAT changed.
        seedPulled()
        dav.editElsewhere(path) { it.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$guest", "PARTSTAT=DECLINED;RSVP=TRUE:$guest") }
        rename("Planning renamed")

        val result = push()

        assertEquals(1, result.eventsUpdated)
        assertEquals(0, result.operationsFailed)
        assertFalse(result.pushWarnings.toString(), result.pushWarnings.any { "412" in it })
        assertEquals("DECLINED", partstat(dav.body(path), guest))
    }

    // ---- when the retry can't be made ----

    @Test
    fun `an event deleted on the server meanwhile is not re-created`() = runTest {
        seedPulled()
        dav.resources.remove(path)
        rename("Planning renamed")

        val result = push()

        assertEquals("only the refused write", 1, dav.puts.size)
        assertNull(dav.resources[path])
        assertEquals(1, result.operationsFailed)
        val op = database.pendingOperationsDao().getAllOnce().single()
        assertTrue(op.lastError!!, "Conflict" in op.lastError!!)
    }

    @Test
    fun `a second refusal defers to conflict resolution after one retry`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note") }
        rename("Planning renamed")
        dav.refusePuts += listOf(412, 412)

        val result = push()

        assertEquals("the write and one retry", 2, dav.puts.size)
        assertEquals(1, result.operationsFailed)
        assertTrue(result.pushWarnings.any { "412" in it })
    }

    @Test
    fun `a failed refetch is retried later and never handed to conflict resolution`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note") }
        rename("Planning renamed")
        dav.refuseGets += 503

        push()

        assertEquals(1, dav.puts.size)
        assertTrue(database.pendingOperationsDao().getConflictOperationsForCalendar(calendarId).isEmpty())
        val op = database.pendingOperationsDao().getAllOnce().single()
        assertTrue("rescheduled for a later cycle: ${op.status}", op.nextRetryAt > 0)
    }

    @Test
    fun `a merged write that fails on the server is retried later, not resolved as a conflict`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note") }
        rename("Planning renamed")
        dav.refusePuts += listOf(412, 503)

        push()

        assertEquals(2, dav.puts.size)
        assertTrue(database.pendingOperationsDao().getConflictOperationsForCalendar(calendarId).isEmpty())
        assertEquals(1, database.pendingOperationsDao().getAllOnce().size)
    }

    @Test
    fun `a changed occurrence deleted elsewhere is gone from KashCal after the merged write`() = runTest {
        val series = meeting
            .replace("DTEND:20261201T110000Z", "DTEND:20261201T110000Z\r\nRRULE:FREQ=DAILY;COUNT=5")
            .replace("END:VEVENT\r\nEND:VCALENDAR", listOf(
                "END:VEVENT", "BEGIN:VEVENT", "UID:planning@example.test", "DTSTAMP:20261101T100000Z",
                "RECURRENCE-ID:20261202T100000Z", "DTSTART:20261202T140000Z", "DTEND:20261202T150000Z",
                "SUMMARY:Planning moved", "SEQUENCE:0", "ORGANIZER;CN=Self:$self",
                "END:VEVENT", "END:VCALENDAR",
            ).joinToString("\r\n"))
        val occurrenceId = seedPulled(series).values.single()
        // Another device deletes the changed occurrence: its VEVENT goes and its instance is
        // excluded.
        dav.editElsewhere(path) {
            it.replace(Regex("""BEGIN:VEVENT\r\nUID:planning@example.test\r\nDTSTAMP:20261101T100000Z\r\nRECURRENCE-ID.*?END:VEVENT\r\n""", RegexOption.DOT_MATCHES_ALL), "")
                .replace("RRULE:FREQ=DAILY;COUNT=5", "RRULE:FREQ=DAILY;COUNT=5\r\nEXDATE:20261202T100000Z")
        }
        rename("Planning renamed")

        sync()

        assertFalse("the server keeps the deletion", "RECURRENCE-ID" in dav.body(path))
        assertNull("the deleted occurrence's row is gone", database.eventsDao().getById(occurrenceId))
    }

    @Test
    fun `changed occurrences a merged write sent count as pushed for the same cycle's pull`() = runTest {
        val series = meeting
            .replace("DTEND:20261201T110000Z", "DTEND:20261201T110000Z\r\nRRULE:FREQ=DAILY;COUNT=5")
            .replace("END:VEVENT\r\nEND:VCALENDAR", listOf(
                "END:VEVENT", "BEGIN:VEVENT", "UID:planning@example.test", "DTSTAMP:20261101T100000Z",
                "RECURRENCE-ID:20261202T100000Z", "DTSTART:20261202T140000Z", "DTEND:20261202T150000Z",
                "SUMMARY:Planning moved", "SEQUENCE:0", "ORGANIZER;CN=Self:$self",
                "END:VEVENT", "END:VCALENDAR",
            ).joinToString("\r\n"))
        val occurrenceId = seedPulled(series).values.single()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning\r\n", "SUMMARY:Planning\r\nDESCRIPTION:Other device note\r\n") }
        rename("Planning renamed")

        val pushed = push()

        assertTrue(eventId in pushed.refetchEventIds)
        assertTrue("a stale read can't prune it: ${pushed.pushedEventIds}", occurrenceId in pushed.pushedEventIds)
    }

    @Test
    fun `a refetch with no etag leaves the edit to conflict resolution without writing`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Planning", "SUMMARY:Planning\r\nDESCRIPTION:Other device note") }
        rename("Planning renamed")
        dav.noEtagOnGet = true

        val result = push()

        assertEquals(1, dav.puts.size)
        assertEquals(1, result.operationsFailed)
        assertTrue(database.pendingOperationsDao().getAllOnce().single().lastError!!.contains("Conflict"))
    }

    @Test
    fun `a server copy that can't be read fails the edit without writing or resolving it as a conflict`() = runTest {
        seedPulled()
        rename("Planning renamed")
        dav.editElsewhere(path) { "not a calendar" }

        val result = push()

        assertEquals(1, dav.puts.size)
        assertEquals(1, result.pushErrors.size)
        assertTrue(database.pendingOperationsDao().getConflictOperationsForCalendar(calendarId).isEmpty())
        assertEquals("the edit stays pending in KashCal", "Planning renamed", database.eventsDao().getById(eventId)!!.title)
    }

    // ---- replies ----

    // An invitation with a changed occurrence, as the invitee's server holds it.
    private val invitation = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client 9.1//EN",
        "BEGIN:VEVENT", "UID:weekly@example.test", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z", "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Weekly", "SEQUENCE:1",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:weekly@example.test", "DTSTAMP:20261101T100000Z",
        "RECURRENCE-ID:20261203T100000Z", "DTSTART:20261203T140000Z", "DTEND:20261203T150000Z",
        "SUMMARY:Weekly moved", "SEQUENCE:1",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "END:VEVENT",
        "END:VCALENDAR", "",
    ).joinToString("\r\n")

    @Test
    fun `answering an occurrence and then the series after the organizer's edit leaves the edit visible in KashCal`() = runTest {
        // The server file holds the series and one changed occurrence; both rows are pulled.
        val occurrenceId = seedPulled(invitation).values.single()

        // The organizer retitles the changed occurrence before either answer is sent.
        dav.editElsewhere(path) { it.replace("SUMMARY:Weekly moved", "SUMMARY:Weekly moved to the big room") }

        // The occurrence answer meets the 412 and is retried on the organizer's copy; the series
        // answer then builds on that retry.
        assertTrue(eventWriter.replyRsvp(occurrenceId, account, "ACCEPTED"))
        assertTrue(eventWriter.replyRsvp(eventId, account, "TENTATIVE"))
        val pushed = sync()

        assertTrue("both replies are refreshed by the pull", setOf(eventId, occurrenceId) == pushed.refetchEventIds)
        assertTrue("the organizer's edit is on the server", "Weekly moved to the big room" in unfold(dav.body(path)))
        assertEquals("the pull brought the organizer's edit into KashCal",
            "Weekly moved to the big room", database.eventsDao().getById(occurrenceId)!!.title)
        for (id in listOf(eventId, occurrenceId)) {
            assertEquals("row $id holds the server's etag", dav.etag(path), database.eventsDao().getById(id)!!.etag)
        }
    }

}
