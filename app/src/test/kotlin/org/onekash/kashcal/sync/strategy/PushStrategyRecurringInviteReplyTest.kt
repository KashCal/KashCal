package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
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
import java.time.Instant
import java.util.Collections

/**
 * Answering an invitation to a series with changed occurrences, through the
 * real writer, the real push and the real client, against a server resource
 * that keeps its body and etag and refuses a stale If-Match like a real one.
 *
 * A PUT replaces the whole resource, so a reply on the series must send every
 * changed occurrence back as the server holds it, and a reply on one changed
 * occurrence must reach the resource and change only that occurrence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyRecurringInviteReplyTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var account: Account
    private var calendarId = 0L

    private val uid = "planning@example.test"
    private val self = "mailto:self@example.test"

    private val seriesUnknown = "X-UNMODELLED;X-NOTE=\"keep; this: exactly\":value as sent"
    private val movedUnknown = "X-MOVED-BY:organizer"

    private val resource = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client 9.1//EN",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z", "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Planning", "SEQUENCE:1", seriesUnknown,
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "RECURRENCE-ID:20261202T100000Z", "DTSTART:20261202T140000Z", "DTEND:20261202T150000Z",
        "SUMMARY:Planning moved", "SEQUENCE:1", movedUnknown,
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "RECURRENCE-ID:20261203T100000Z", "DTSTART:20261203T100000Z", "DTEND:20261203T110000Z",
        "SUMMARY:Planning with finance", "SEQUENCE:1",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "ATTENDEE;CN=Finance;PARTSTAT=NEEDS-ACTION:mailto:finance@example.test",
        "END:VEVENT",
        "END:VCALENDAR", "",
    ).joinToString("\r\n")

    private val movedInstance = Instant.parse("2026-12-02T10:00:00Z").toEpochMilli()
    private val retitledInstance = Instant.parse("2026-12-03T10:00:00Z").toEpochMilli()
    private val unchangedInstance = Instant.parse("2026-12-04T10:00:00Z").toEpochMilli()

    // ---- the server's copy of the resource ----

    private val resourcePath = "/cal/planning.ics"
    private val movedPath = "/moved/planning.ics"
    private var servedPath = resourcePath
    private var body = resource
    private var etagNo = 1
    private var redirectPuts = false
    private val puts: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val etag get() = "e$etagNo"
    private val resourceUrl get() = server.url(resourcePath).toString()

    /** Changes the resource on the server, as another client would. */
    private fun organizerEdits(change: (String) -> String) {
        body = change(body)
        etagNo++
    }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path
            requests += "${request.method} $path"
            if (redirectPuts && request.method == "PUT" && path == resourcePath) {
                servedPath = movedPath
                return MockResponse().setResponseCode(301).setHeader("Location", movedPath)
            }
            if (path != servedPath) return MockResponse().setResponseCode(404)
            return when (request.method) {
                "PUT" -> {
                    val sent = request.body.readUtf8()
                    val ifMatch = request.getHeader("If-Match")
                    puts += sent to ifMatch
                    if (ifMatch != "\"$etag\"") {
                        MockResponse().setResponseCode(412)
                    } else {
                        body = sent
                        etagNo++
                        MockResponse().setResponseCode(204).setHeader("ETag", "\"$etag\"")
                    }
                }
                "GET" -> MockResponse().setResponseCode(200).setHeader("ETag", "\"$etag\"").setBody(body)
                "PROPFIND" -> MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href>""" +
                        """<d:propstat><d:prop><d:getetag>"$etag"</d:getetag></d:prop>""" +
                        """<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                )
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    // ---- the app ----

    private var seriesId = 0L
    private var movedId = 0L
    private var retitledId = 0L

    @Before
    fun setup() = runTest {
        server = MockWebServer()
        server.dispatcher = dispatcher
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
            Calendar(accountId = accountId, caldavUrl = server.url("/cal/").toString(), displayName = "Cal", color = -1)
        )

        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(accountId) } returns account
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** Stores [ics] the way pull does: every row carries the whole body, the URL and the etag. */
    private suspend fun seedPulled(ics: String = resource) {
        val parsed = ICalParser().parseAllEvents(ics).getOrNull()!!
        val seriesParsed = parsed.single { it.recurrenceId == null }
        val series = ICalEventMapper.toEntity(seriesParsed, ics, calendarId, resourceUrl, etag)
        seriesId = database.eventsDao().insert(series.event)
        database.attendeesDao().replaceForEvent(seriesId, series.attendees.map { it.copy(eventId = seriesId) })
        occurrenceGenerator.regenerateOccurrences(database.eventsDao().getById(seriesId)!!)

        for (change in parsed.filter { it.recurrenceId != null }) {
            val mapped = ICalEventMapper.toEntity(
                change, ics, calendarId, resourceUrl, etag, masterDtStart = seriesParsed.dtStart
            )
            val id = database.eventsDao().insert(mapped.event.copy(originalEventId = seriesId))
            database.attendeesDao().replaceForEvent(id, mapped.attendees.map { it.copy(eventId = id) })
            occurrenceGenerator.linkException(seriesId, mapped.event.originalInstanceTime!!, database.eventsDao().getById(id)!!)
            when (mapped.event.originalInstanceTime) {
                movedInstance -> movedId = id
                retitledInstance -> retitledId = id
            }
        }
    }

    /** Seeds a file holding only the two changed occurrences, with pull's placeholder series. */
    private suspend fun seedChangesOnly() {
        val changesOnly = resource.replace(
            Regex("""BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261101T100000Z\r\nDTSTART:.*?END:VEVENT\r\n""", RegexOption.DOT_MATCHES_ALL), ""
        )
        body = changesOnly
        val parsed = ICalParser().parseAllEvents(changesOnly).getOrNull()!!
        assertEquals(2, parsed.size)
        // Pull keeps a placeholder series row for such a file, with no body, URL or etag.
        seriesId = database.eventsDao().insert(
            synthesizeMasterForOrphanException(uid, calendarId, movedInstance, "Planning")
        )
        for (change in parsed) {
            val mapped = ICalEventMapper.toEntity(change, changesOnly, calendarId, resourceUrl, etag)
            val id = database.eventsDao().insert(mapped.event.copy(originalEventId = seriesId))
            database.attendeesDao().replaceForEvent(id, mapped.attendees.map { it.copy(eventId = id) })
            if (mapped.event.originalInstanceTime == retitledInstance) retitledId = id
        }
    }

    private suspend fun push() = pushStrategy.pushForCalendar(database.calendarsDao().getById(calendarId)!!, client)

    // ---- reading bodies ----

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    private fun vevents(ics: String) =
        Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfold(ics)).map { it.value }.toList()

    private fun block(ics: String, recurrenceId: String?) = vevents(ics).single {
        if (recurrenceId == null) "RECURRENCE-ID" !in it else "RECURRENCE-ID:$recurrenceId" in it
    }

    private fun selfPartstat(veventText: String): String? =
        veventText.lines().firstOrNull { it.startsWith("ATTENDEE") && it.trimEnd().endsWith(self) }
            ?.let { Regex("""PARTSTAT=([A-Z-]+)""").find(it)?.groupValues?.get(1) }

    // ---- replying to the series ----

    @Test
    fun `a series reply sends every changed occurrence back as the server holds it`() = runTest {
        seedPulled()
        // Each answer in turn; every write must differ from the server's copy
        // by this account's series answer alone.
        for (answer in listOf("ACCEPTED", "TENTATIVE", "DECLINED")) {
            val before = body
            puts.clear()

            assertTrue(eventWriter.replyRsvp(seriesId, account, answer))
            push()

            assertEquals("$answer: one write", 1, puts.size)
            val sent = puts.single().first
            assertEquals("$answer: series and both changed occurrences", 3, vevents(sent).size)
            assertEquals(block(resource, "20261202T100000Z"), block(sent, "20261202T100000Z"))
            assertEquals(block(resource, "20261203T100000Z"), block(sent, "20261203T100000Z"))
            val series = block(sent, null)
            assertEquals(answer, selfPartstat(series))
            assertTrue("series keeps its repeat rule", "RRULE:FREQ=DAILY;COUNT=5" in series)
            assertTrue("series keeps what KashCal doesn't model", seriesUnknown in series)
            val a = unfold(before).lines()
            val b = unfold(sent).lines()
            assertEquals(a.size, b.size)
            assertEquals("$answer: only the answer line changed", 1, a.indices.count { a[it] != b[it] })
        }
    }

    @Test
    fun `answering an ordinary occurrence answers the series`() = runTest {
        seedPulled()
        // An unchanged occurrence is shown through the series row, so that is
        // the row its reply is written on.
        val occurrence = database.occurrencesDao().getOccurrenceAtTime(seriesId, unchangedInstance)!!
        assertNull(occurrence.exceptionEventId)
        assertEquals(seriesId, occurrence.eventId)

        assertTrue(eventWriter.replyRsvp(occurrence.eventId, account, "ACCEPTED"))
        push()

        assertEquals("ACCEPTED", selfPartstat(block(body, null)))
        assertEquals("NEEDS-ACTION", selfPartstat(block(body, "20261202T100000Z")))
        assertEquals(3, vevents(body).size)
    }

    // ---- replying to one changed occurrence ----

    @Test
    fun `a reply to a changed occurrence reaches the series' resource and changes only that occurrence`() = runTest {
        seedPulled()

        assertTrue(eventWriter.replyRsvp(retitledId, account, "TENTATIVE"))
        val result = push()

        assertTrue("push reported $result", result is PushResult.Success && result.pushErrors.isEmpty())
        assertEquals("one write to the resource", listOf("PUT $resourcePath"), requests.filter { it.startsWith("PUT") })
        assertEquals("sent with the resource's etag", "\"e1\"", puts.single().second)
        val sent = puts.single().first
        assertEquals("TENTATIVE", selfPartstat(block(sent, "20261203T100000Z")))
        assertEquals("series answer untouched", block(resource, null), block(sent, null))
        assertEquals("other occurrence untouched", block(resource, "20261202T100000Z"), block(sent, "20261202T100000Z"))
        assertEquals(unfold(resource).lines().size, unfold(sent).lines().size)
    }

    @Test
    fun `answering the series and then an occurrence in one push keeps both answers`() = runTest {
        seedPulled()

        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))
        assertTrue(eventWriter.replyRsvp(movedId, account, "DECLINED"))
        push()

        assertEquals(2, puts.size)
        assertEquals("first reply uses the stored etag", "\"e1\"", puts[0].second)
        assertEquals("second reply uses the etag the first returned", "\"e2\"", puts[1].second)
        assertEquals("ACCEPTED", selfPartstat(block(body, null)))
        assertEquals("DECLINED", selfPartstat(block(body, "20261202T100000Z")))
        assertEquals("NEEDS-ACTION", selfPartstat(block(body, "20261203T100000Z")))
    }

    @Test
    fun `answering an occurrence and then the series in one push keeps both answers`() = runTest {
        seedPulled()

        assertTrue(eventWriter.replyRsvp(movedId, account, "TENTATIVE"))
        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))
        push()

        assertEquals(listOf("\"e1\"", "\"e2\""), puts.map { it.second })
        assertEquals("ACCEPTED", selfPartstat(block(body, null)))
        assertEquals("TENTATIVE", selfPartstat(block(body, "20261202T100000Z")))
    }

    @Test
    fun `after a reply every row of the resource holds the server's etag and the body sent`() = runTest {
        seedPulled()

        assertTrue(eventWriter.replyRsvp(movedId, account, "ACCEPTED"))
        push()

        assertEquals("the reply was written", 1, puts.size)
        assertEquals("ACCEPTED", selfPartstat(block(body, "20261202T100000Z")))
        assertEquals("e2", etag)
        for (id in listOf(seriesId, movedId, retitledId)) {
            val row = database.eventsDao().getById(id)!!
            assertEquals("row $id etag", etag, row.etag)
            assertEquals("row $id body", body, row.rawIcal)
        }
    }

    @Test
    fun `a reply in a later push builds on the answer the last push sent`() = runTest {
        seedPulled()
        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))
        push()

        assertTrue(eventWriter.replyRsvp(retitledId, account, "DECLINED"))
        push()

        assertEquals("no conflict", 2, puts.size)
        assertEquals("ACCEPTED", selfPartstat(block(body, null)))
        assertEquals("DECLINED", selfPartstat(block(body, "20261203T100000Z")))
    }

    // ---- when the server changed ----

    @Test
    fun `an occurrence reply after the organizer changed the event is re-applied to the new copy`() = runTest {
        seedPulled()
        organizerEdits { it.replace("SUMMARY:Planning with finance", "SUMMARY:Planning with finance and legal") }

        assertTrue(eventWriter.replyRsvp(retitledId, account, "ACCEPTED"))
        push()

        assertEquals("a 412, then a retry", listOf("\"e1\"", "\"e2\""), puts.map { it.second })
        assertTrue("the organizer's edit survives", "SUMMARY:Planning with finance and legal" in body)
        assertEquals("ACCEPTED", selfPartstat(block(body, "20261203T100000Z")))
        assertEquals("NEEDS-ACTION", selfPartstat(block(body, null)))
        // No row has taken in the organizer's edit, so every row keeps its old
        // etag: the next pull fetches the resource again, whichever row it reads.
        for (id in listOf(seriesId, movedId, retitledId)) {
            assertEquals("row $id etag", "e1", database.eventsDao().getById(id)!!.etag)
        }
    }

    @Test
    fun `an occurrence reply refused twice is reported as changed and leaves the server copy alone`() = runTest {
        seedPulled()
        organizerEdits { it.replace("SUMMARY:Planning with finance", "SUMMARY:Planning with finance and legal") }
        // Every write is refused: the organizer keeps changing the event.
        val refuseAll = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "PUT") {
                    puts += request.body.readUtf8() to request.getHeader("If-Match")
                    MockResponse().setResponseCode(412)
                } else {
                    dispatcher.dispatch(request)
                }
        }
        server.dispatcher = refuseAll
        val before = body

        assertTrue(eventWriter.replyRsvp(retitledId, account, "ACCEPTED"))
        val result = push()

        assertEquals("the write and one retry", 2, puts.size)
        assertEquals(before, body)
        assertTrue(
            "reported as changed, not silent: $result",
            result is PushResult.Success && result.operationsFailed == 1 &&
                result.pushWarnings.any { "re-respond" in it }
        )
    }

    @Test
    fun `a reply after a retry on a file with no series still keeps the organizer's edit`() = runTest {
        seedChangesOnly()
        organizerEdits { it.replace("SUMMARY:Planning with finance", "SUMMARY:Planning with finance and legal") }

        assertTrue(eventWriter.replyRsvp(retitledId, account, "ACCEPTED"))
        push()
        assertTrue(eventWriter.replyRsvp(retitledId, account, "DECLINED"))
        push()

        assertTrue("the organizer's edit survives", "SUMMARY:Planning with finance and legal" in body)
        assertEquals("DECLINED", selfPartstat(block(body, "20261203T100000Z")))
    }

    @Test
    fun `answering the series and then an occurrence through pushAll keeps both answers`() = runTest {
        seedPulled()

        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))
        assertTrue(eventWriter.replyRsvp(movedId, account, "DECLINED"))
        pushStrategy.pushAll(client)

        assertEquals(listOf("\"e1\"", "\"e2\""), puts.map { it.second })
        assertEquals("ACCEPTED", selfPartstat(block(body, null)))
        assertEquals("DECLINED", selfPartstat(block(body, "20261202T100000Z")))
    }

    @Test
    fun `a series edit then a reply in one push keeps the edit on the server`() = runTest {
        seedPulled()
        val series = database.eventsDao().getById(seriesId)!!
        database.eventsDao().update(series.copy(title = "Planning renamed", syncStatus = SyncStatus.PENDING_UPDATE))
        database.pendingOperationsDao().insert(
            // Queued before the reply, as when the edit was made first.
            PendingOperation(eventId = seriesId, operation = PendingOperation.OPERATION_UPDATE, createdAt = 1L)
        )
        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))

        push()

        assertTrue("the edit is on the server", "SUMMARY:Planning renamed" in block(body, null))
        assertEquals("ACCEPTED", selfPartstat(block(body, null)))
    }

    // ---- replies that can't be sent ----

    @Test
    fun `a reply to an occurrence the server file doesn't hold is an error, not a silent success`() = runTest {
        seedPulled()
        // An occurrence changed only on this phone: the stored server copy has no VEVENT for it.
        val moved = database.eventsDao().getById(movedId)!!
        val localOnlyId = database.eventsDao().insert(
            moved.copy(id = 0, originalInstanceTime = unchangedInstance, title = "Changed here only")
        )
        database.attendeesDao().replaceForEvent(
            localOnlyId, database.attendeesDao().getForEventOnce(movedId).map { it.copy(id = 0, eventId = localOnlyId) }
        )

        assertTrue(eventWriter.replyRsvp(localOnlyId, account, "ACCEPTED"))
        val result = push()

        assertTrue("no write", puts.isEmpty())
        assertTrue("push reports the failure: $result", result is PushResult.Success && result.pushErrors.isNotEmpty())
    }

    // ---- other shapes of resource ----

    @Test
    fun `a reply to an occurrence of a file with no series reaches the server`() = runTest {
        seedChangesOnly()

        assertTrue(eventWriter.replyRsvp(retitledId, account, "ACCEPTED"))
        push()

        assertEquals(1, puts.size)
        assertEquals("ACCEPTED", selfPartstat(block(body, "20261203T100000Z")))
        assertEquals("NEEDS-ACTION", selfPartstat(block(body, "20261202T100000Z")))
        val placeholder = database.eventsDao().getById(seriesId)!!
        assertNull("placeholder keeps no body", placeholder.rawIcal)
        assertNull("placeholder keeps no etag", placeholder.etag)
        assertNull("placeholder keeps no URL", placeholder.caldavUrl)
    }

    @Test
    fun `a redirected occurrence reply moves every row of the resource to the new URL`() = runTest {
        seedPulled()
        redirectPuts = true

        assertTrue(eventWriter.replyRsvp(movedId, account, "ACCEPTED"))
        push()

        assertEquals("ACCEPTED", selfPartstat(block(body, "20261202T100000Z")))
        val moved = server.url(movedPath).toString()
        for (id in listOf(seriesId, movedId, retitledId)) {
            assertEquals("row $id URL", moved, database.eventsDao().getById(id)!!.caldavUrl)
        }
    }
}
