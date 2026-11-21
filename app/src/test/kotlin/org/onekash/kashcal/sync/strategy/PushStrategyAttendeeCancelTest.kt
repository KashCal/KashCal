package org.onekash.kashcal.sync.strategy

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
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
import org.onekash.kashcal.data.db.PendingOperationTriggers
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
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Collections

/**
 * The attendee-side cancel of an imported CANCEL: the server copy with STATUS:CANCELLED patched
 * in, PUT with If-Match, against a server that refuses a stale etag. Refusals put the rows back
 * to the server's copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyAttendeeCancelTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var calendarId = 0L
    private var masterId = 0L

    private val uid = "planning@example.test"
    private val resource = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client//EN",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z", "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Planning", "SEQUENCE:1",
        "ORGANIZER:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:self@example.test",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "RECURRENCE-ID:20261202T100000Z", "DTSTART:20261202T140000Z", "DTEND:20261202T150000Z",
        "SUMMARY:Planning moved", "SEQUENCE:1",
        "ORGANIZER:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:self@example.test",
        "END:VEVENT",
        "END:VCALENDAR", ""
    ).joinToString("\r\n")
    private val moved = Instant.parse("2026-12-02T10:00:00Z").toEpochMilli()
    private val plain = Instant.parse("2026-12-04T10:00:00Z").toEpochMilli()

    // ---- the server ----

    private val path = "/cal/planning.ics"
    private var body = resource
    private var etagNo = 1
    private var refusal: Int? = null
    private var staleOnce = 0
    private val puts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != path) return MockResponse().setResponseCode(404)
            return when (request.method) {
                "PUT" -> {
                    val sent = request.body.readUtf8()
                    puts += sent
                    when {
                        refusal != null -> MockResponse().setResponseCode(refusal!!)
                        staleOnce > 0 -> { staleOnce--; etagNo++; MockResponse().setResponseCode(412) }
                        request.getHeader("If-Match") != "\"e$etagNo\"" -> MockResponse().setResponseCode(412)
                        else -> { body = sent; etagNo++; MockResponse().setResponseCode(204).setHeader("ETag", "\"e$etagNo\"") }
                    }
                }
                "GET" -> MockResponse().setResponseCode(200).setHeader("ETag", "\"e$etagNo\"").setBody(body)
                "PROPFIND" -> MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href>""" +
                        """<d:propstat><d:prop><d:getetag>"e$etagNo"</d:getetag></d:prop>""" +
                        """<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                )
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    @Before
    fun setup() = runTest {
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base))
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback()).allowMainThreadQueries().build()
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "self@example.test", calendarUserAddresses = listOf("mailto:self@example.test"))
        )
        val account = database.accountsDao().getById(accountId)!!
        calendarId = database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = server.url("/cal/").toString(), displayName = "Cal", color = -1))
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(accountId) } returns account
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao(),
            icsSource = EventIcsStore(database),
        )
        seedPulled()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** Stores the server copy as pull does: each row the URL and etag, the series the body. */
    private suspend fun seedPulled() {
        val url = server.url(path).toString()
        val parsed = ICalParser().parseAllEvents(resource).getOrNull()!!
        val series = parsed.single { it.recurrenceId == null }
        val mapped = ICalEventMapper.toEntity(series, resource, calendarId, url, "e1")
        masterId = database.eventsDao().insert(mapped.event)
        EventIcsStore(database).write(masterId, mapped.ics)
        database.attendeesDao().replaceForEvent(masterId, mapped.attendees.map { it.copy(eventId = masterId) })
        occurrenceGenerator.regenerateOccurrences(database.eventsDao().getById(masterId)!!)
        val change = parsed.single { it.recurrenceId != null }
        val ex = ICalEventMapper.toEntity(change, resource, calendarId, url, "e1", masterDtStart = series.dtStart)
        val exId = database.eventsDao().insert(ex.event.copy(originalEventId = masterId))
        occurrenceGenerator.linkException(masterId, ex.event.originalInstanceTime!!, database.eventsDao().getById(exId)!!)
    }

    private fun statuses(ics: String) = ICalParser().parseAllEvents(ics).getOrNull()!!
        .associate { (it.recurrenceId?.timestamp) to it.status.toICalString().ifBlank { "CONFIRMED" } }

    private suspend fun rows() = database.eventsDao().findAllByUid(uid, calendarId)

    @Test
    fun `a series cancel PUTs every VEVENT cancelled and stores the copy`() = runTest {
        eventWriter.applyImportedCancel(masterId, null, sequence = 2, itipDtstamp = 5L, isLocal = false)

        pushStrategy.pushAll(client)

        assertEquals(1, puts.size)
        assertTrue(statuses(puts.single()).values.all { it == "CANCELLED" })
        assertTrue("SEQUENCE as the server holds it", puts.single().contains("SEQUENCE:1"))
        assertTrue(rows().all { it.status == "CANCELLED" && EventIcsStore(database).read(it) == puts.single() })
        assertTrue(database.pendingOperationsDao().getForEvent(masterId).isEmpty())
    }

    @Test
    fun `a 412 from drift re-reads and retries once`() = runTest {
        staleOnce = 1
        eventWriter.applyImportedCancel(masterId, null, 2, 5L, isLocal = false)

        pushStrategy.pushAll(client)

        assertEquals(2, puts.size)
        assertTrue(statuses(body).values.all { it == "CANCELLED" })
        assertTrue(rows().all { it.status == "CANCELLED" })
    }

    @Test
    fun `a refusal puts the rows back to the server's copy and drops the operation`() = runTest {
        for (code in listOf(403, 412)) {
            puts.clear(); refusal = code
            eventWriter.applyImportedCancel(masterId, null, 2, 5L, isLocal = false)

            val result = pushStrategy.pushAll(client) as PushResult.Success

            assertTrue("HTTP $code", rows().all { it.status == "CONFIRMED" })
            assertTrue("HTTP $code", database.pendingOperationsDao().getForEvent(masterId).isEmpty())
            assertTrue("HTTP $code: ${result.pushWarnings}", result.pushWarnings.any { it.contains("cancellation wasn't applied") })
        }
    }

    @Test
    fun `an occurrence with no exception gets a cancelled one, removed again on refusal`() = runTest {
        val inserted = eventWriter.applyImportedCancel(masterId, plain, 2, 5L, isLocal = false).single()
        assertEquals(Event.IMPORT_SOURCE_ICS_CANCEL, inserted.importSource)
        assertEquals(SyncStatus.SYNCED, inserted.syncStatus)
        assertEquals(1, database.attendeesDao().getForEventOnce(inserted.id).size)

        pushStrategy.pushAll(client)

        assertEquals("CANCELLED", statuses(puts.single())[plain])
        assertEquals("the series stays", "CONFIRMED", statuses(puts.single())[null])

        refusal = 403
        val again = eventWriter.applyImportedCancel(masterId, plain + 86_400_000L, 3, 6L, isLocal = false).single()
        pushStrategy.pushAll(client)
        assertNull("the refused, inserted exception is removed", database.eventsDao().getById(again.id))
    }

    @Test
    fun `a series still waiting for its create queues no cancel`() = runTest {
        TestDatabases.raw(database).execSQL("UPDATE events SET sync_status = 'PENDING_CREATE' WHERE id = $masterId")

        eventWriter.applyImportedCancel(masterId, null, 2, 5L, isLocal = false)

        assertTrue(database.pendingOperationsDao().getForEvent(masterId).none { it.operation == PendingOperation.OPERATION_ATTENDEE_CANCEL })
        assertTrue(rows().all { it.status == "CANCELLED" })
    }

    @Test
    fun `the local calendar cancels without an operation`() = runTest {
        eventWriter.applyImportedCancel(masterId, moved, 2, 5L, isLocal = true)

        assertTrue(database.pendingOperationsDao().getAllOnce().isEmpty())
        assertEquals("CANCELLED", rows().single { it.originalInstanceTime == moved }.status)
        assertEquals("CONFIRMED", rows().single { it.originalInstanceTime == null }.status)
    }

    @Test
    fun `a later update never folds into a pending cancel`() = runTest {
        eventWriter.applyImportedCancel(masterId, null, 2, 5L, isLocal = false)
        val master = database.eventsDao().getById(masterId)!!

        eventWriter.updateEvent(master.copy(title = "Renamed"), isLocal = false)

        val ops = database.pendingOperationsDao().getForEvent(masterId).map { it.operation }.sorted()
        assertEquals(listOf(PendingOperation.OPERATION_ATTENDEE_CANCEL, PendingOperation.OPERATION_UPDATE), ops)
    }
}
