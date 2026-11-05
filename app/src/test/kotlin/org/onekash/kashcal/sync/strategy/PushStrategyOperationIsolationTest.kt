package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
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
import java.io.IOException

/**
 * One queued upload that throws, or whose event row can't be read, must not stop the uploads
 * queued after it. The failed operation stays queued for a retry; cancellation and network
 * errors stop the push.
 *
 * Real writer, push, client and Room against a fake CalDAV collection. The throw comes from an
 * attendees DAO wrapper, or from a real row too large for Android's 2 MB cursor window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyOperationIsolationTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private val dav = FakeDavCalendar()
    private var calendarId = 0L

    /** When set, getForEventOnce throws it for [failingEventId]. */
    private var failure: Exception? = null
    private var failingEventId = -1L

    private fun body(uid: String, summary: String) = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other Client//EN",
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z", "SUMMARY:$summary",
        "END:VEVENT", "END:VCALENDAR", "",
    ).joinToString("\r\n")

    private fun path(name: String) = "${dav.collectionPath}$name.ics"

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
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries().build()
        val occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "self@example.test")
        )
        val account = database.accountsDao().getById(accountId)!!
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = server.url(dav.collectionPath).toString(), displayName = "Cal", color = -1)
        )
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(accountId) } returns account
        val realAttendees = database.attendeesDao()
        val attendees = object : AttendeesDao by realAttendees {
            override suspend fun getForEventOnce(eventId: Long): List<Attendee> {
                failure?.takeIf { eventId == failingEventId }?.let { throw it }
                return realAttendees.getForEventOnce(eventId)
            }
        }
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = attendees,
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** Stores [name] as pulled from the server, then edits its title so an UPDATE is queued. */
    private suspend fun seedEdited(name: String): Long {
        val ics = body("$name@example.test", name)
        dav.put(path(name), ics)
        val parsed = ICalParser().parseAllEvents(ics).getOrNull()!!.single()
        val mapped = ICalEventMapper.toEntity(parsed, ics, calendarId, server.url(path(name)).toString(), dav.etag(path(name)))
        val id = database.eventsDao().insert(mapped.event)
        eventWriter.updateEvent(database.eventsDao().getById(id)!!.copy(title = "$name edited"), isLocal = false)
        return id
    }

    /** A synced row too large to read back, with an UPDATE queued for it directly. */
    private suspend fun seedOversizedWithUpdate(): Long {
        dav.put(path("big"), body("big@example.test", "Big"))
        val id = database.eventsDao().insert(
            Event(
                uid = "big@example.test", calendarId = calendarId, title = "Big",
                startTs = 1_796_119_200_000L, endTs = 1_796_122_800_000L, dtstamp = 0L,
                caldavUrl = server.url(path("big")).toString(), etag = dav.etag(path("big")),
                syncStatus = SyncStatus.PENDING_UPDATE, rawIcal = "x".repeat(2_500_000),
            )
        )
        database.pendingOperationsDao().insert(
            PendingOperation(eventId = id, operation = PendingOperation.OPERATION_UPDATE, createdAt = 1L)
        )
        return id
    }

    private suspend fun calendar() = database.calendarsDao().getById(calendarId)!!

    private fun putPaths() = dav.puts.map { it.first }

    private suspend fun opFor(eventId: Long) = database.pendingOperationsDao().getForEvent(eventId).single()

    @Test
    fun `one operation that throws does not stop the next`() = runTest {
        val first = seedEdited("first")
        seedEdited("second")
        failingEventId = first
        // A message holding "412" or "Conflict" must not reach the stored error, where the
        // conflict sweep would pick the operation up.
        failure = IllegalStateException("broken row 412 Conflict")

        val result = pushStrategy.pushForCalendar(calendar(), client)

        assertTrue("was $result", result is PushResult.Success)
        assertEquals(listOf(path("second")), putPaths())
        val op = opFor(first)
        assertEquals("the failed op is queued for a retry", PendingOperation.STATUS_PENDING, op.status)
        assertTrue("a retry is scheduled", op.nextRetryAt > 0)
        assertEquals("Failed to process (IllegalStateException)", op.lastError)
        assertTrue(
            "the op is not handed to conflict resolution",
            database.pendingOperationsDao().getConflictOperationsForCalendar(calendarId).isEmpty()
        )
        assertTrue(
            "the failure is reported as a warning: ${(result as PushResult.Success).pushWarnings}",
            result.pushWarnings.any { "Failed to process (IllegalStateException)" in it }
        )
        assertEquals(1, result.operationsFailed)
    }

    @Test
    fun `an unreadable event row does not stop other pushes`() = runTest {
        val big = seedOversizedWithUpdate()
        seedEdited("normal")

        val result = pushStrategy.pushForCalendar(calendar(), client)

        assertTrue("was $result", result is PushResult.Success)
        assertEquals(listOf(path("normal")), putPaths())
        assertEquals("the unreadable row's op stays queued", PendingOperation.STATUS_PENDING, opFor(big).status)
    }

    @Test
    fun `pushAll keeps going past an unreadable row`() = runTest {
        val big = seedOversizedWithUpdate()
        seedEdited("normal")

        val result = pushStrategy.pushAll(client)

        assertTrue("was $result", result is PushResult.Success)
        assertEquals(listOf(path("normal")), putPaths())
        val op = opFor(big)
        assertEquals(PendingOperation.STATUS_PENDING, op.status)
        assertTrue("a retry is scheduled", op.nextRetryAt > 0)
        assertEquals(1, (result as PushResult.Success).operationsFailed)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        failingEventId = seedEdited("first")
        failure = CancellationException("cancelled")

        try {
            pushStrategy.pushForCalendar(calendar(), client)
            fail("a cancelled operation must cancel the push")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun `a network error stops the push`() = runTest {
        failingEventId = seedEdited("first")
        seedEdited("second")
        failure = IOException("connection reset")

        try {
            pushStrategy.pushForCalendar(calendar(), client)
            fail("a network error stops the push")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertFalse("the next operation is not attempted", path("second") in putPaths())
    }
}
