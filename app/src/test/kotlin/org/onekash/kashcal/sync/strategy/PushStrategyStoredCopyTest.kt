package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
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

/**
 * After a successful first-attempt update or a create, the rows of the resource hold the body the
 * upload sent, paired with the etag the server returned for it, so a reply sent in a later push
 * starts from that body. A reply retry that fails after a 412 leaves the row's etag as it was.
 *
 * Real writer, real push, real client and Room, against a server resource that refuses a stale
 * If-Match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyStoredCopyTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var account: Account
    private val dav = FakeDavCalendar()
    private var calendarId = 0L

    private val uid = "standup@example.test"
    private val self = "mailto:self@example.test"
    private val path = "/cal/standup.ics"
    private val url get() = server.url(path).toString()

    private val series = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client 9.1//EN",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z", "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Standup", "SEQUENCE:1",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "RECURRENCE-ID:20261202T100000Z", "DTSTART:20261202T140000Z", "DTEND:20261202T150000Z",
        "SUMMARY:Standup moved", "SEQUENCE:1",
        "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;CN=Self;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
        "END:VEVENT",
        "END:VCALENDAR", "",
    ).joinToString("\r\n")

    private val droppedInstance = Instant.parse("2026-12-04T10:00:00Z").toEpochMilli()
    private var seriesId = 0L
    private var movedId = 0L

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

    /** Store [ics] the way pull does: every row carries the whole body, the URL and the etag. */
    private suspend fun seedPulled(ics: String = series) {
        dav.put(path, ics)
        val parsed = ICalParser().parseAllEvents(ics).getOrNull()!!
        val seriesParsed = parsed.single { it.recurrenceId == null }
        val mapped = ICalEventMapper.toEntity(seriesParsed, ics, calendarId, url, dav.etag(path))
        seriesId = database.eventsDao().insert(mapped.event)
        database.attendeesDao().replaceForEvent(seriesId, mapped.attendees.map { it.copy(eventId = seriesId) })
        occurrenceGenerator.regenerateOccurrences(database.eventsDao().getById(seriesId)!!)
        for (change in parsed.filter { it.recurrenceId != null }) {
            val m = ICalEventMapper.toEntity(change, ics, calendarId, url, dav.etag(path), masterDtStart = seriesParsed.dtStart)
            movedId = database.eventsDao().insert(m.event.copy(originalEventId = seriesId))
            database.attendeesDao().replaceForEvent(movedId, m.attendees.map { it.copy(eventId = movedId) })
            occurrenceGenerator.linkException(seriesId, m.event.originalInstanceTime!!, database.eventsDao().getById(movedId)!!)
        }
    }

    private suspend fun push() = pushStrategy.pushForCalendar(database.calendarsDao().getById(calendarId)!!, client)

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    private fun seriesBlock(ics: String) =
        Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfold(ics))
            .map { it.value }.single { "RECURRENCE-ID" !in it }

    private fun selfPartstat(vevent: String) =
        vevent.lines().first { it.startsWith("ATTENDEE") && it.trimEnd().endsWith(self) }
            .let { Regex("""PARTSTAT=([A-Z-]+)""").find(it)!!.groupValues[1] }

    @Test
    fun `an invitee's deleted occurrence survives a reply sent in a later push`() = runTest {
        seedPulled()
        eventWriter.deleteSingleOccurrence(seriesId, droppedInstance)
        push()
        assertTrue("the deletion reached the server", "EXDATE" in seriesBlock(dav.body(path)))

        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))
        push()

        val onServer = seriesBlock(dav.body(path))
        assertEquals("ACCEPTED", selfPartstat(onServer))
        assertTrue("the reply keeps the deleted occurrence deleted: $onServer", "EXDATE" in onServer)
    }

    @Test
    fun `after an update every row of the resource holds the body sent and its etag`() = runTest {
        seedPulled()
        eventWriter.deleteSingleOccurrence(seriesId, droppedInstance)

        push()

        val (_, sent, _) = dav.puts.single()
        for (id in listOf(seriesId, movedId)) {
            val row = database.eventsDao().getById(id)!!
            assertEquals("row $id body", sent, row.rawIcal)
            assertEquals("row $id etag", dav.etag(path), row.etag)
            assertEquals(SyncStatus.SYNCED, row.syncStatus)
        }
    }

    @Test
    fun `after a create the row holds the body sent and its etag`() = runTest {
        val start = Instant.parse("2026-12-10T09:00:00Z").toEpochMilli()
        val created = eventWriter.createEvent(
            Event(
                uid = "made-here@example.test", calendarId = calendarId, title = "Made here",
                startTs = start, endTs = start + 3_600_000L, timezone = "UTC", dtstamp = start
            )
        )

        push()

        val (putPath, sent, _) = dav.puts.single()
        val row = database.eventsDao().getById(created.id)!!
        assertEquals(sent, row.rawIcal)
        assertEquals(dav.etag(putPath), row.etag)
    }

    @Test
    fun `a reply retry that fails after a conflict leaves the row's etag as it was`() = runTest {
        seedPulled()
        dav.editElsewhere(path) { it.replace("SUMMARY:Standup\r\n", "SUMMARY:Standup renamed\r\n") }
        // The first reply meets the organizer's edit (412); its retry then fails on the server.
        dav.refusePuts += listOf(412, 500)

        assertTrue(eventWriter.replyRsvp(seriesId, account, "ACCEPTED"))
        push()

        assertEquals("e1", database.eventsDao().getById(seriesId)!!.etag)
    }
}
