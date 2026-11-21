package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.EventIcsSource
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Tests that a push stores the body it sent as the resource's ICS ([EventIcsSource]) once, on
 * the series row, after the rows' URLs reflect a redirect, and in the same transaction as the
 * etag that goes with it: a reply builds on the last body sent, never on an older one under a
 * newer etag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyEventIcsTest {

    private lateinit var database: KashCalDatabase
    private lateinit var store: EventIcsStore
    private val client: CalDavClient = mockk()
    private val accountRepository: AccountRepository = mockk()
    private val puts = mutableListOf<Triple<String, String, String>>() // url, body, If-Match
    private var nextEtag = 2

    private val account = Account(id = 1L, provider = AccountProvider.CALDAV, email = "self@example.test")
    private val calendar = Calendar(
        id = 9L, accountId = account.id, caldavUrl = "https://caldav.example.test/cal9/", displayName = "Work", color = 0,
    )
    private val url = "${calendar.caldavUrl}s.ics"

    private companion object {
        const val UID = "uid-series"
        val START_MS: Long = Instant.parse("2035-06-01T10:00:00Z").toEpochMilli()
        val INSTANCE_MS: Long = Instant.parse("2035-06-02T10:00:00Z").toEpochMilli()
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        store = EventIcsStore(database)
        database.accountsDao().insert(account)
        database.calendarsDao().insert(calendar)
        coEvery { accountRepository.getAccountById(account.id) } returns account
        coEvery { client.updateEvent(any(), any(), any()) } answers {
            puts += Triple(firstArg(), secondArg(), thirdArg())
            CalDavResult.success("etag-${nextEtag++}")
        }
        coEvery { client.fetchEvent(any()) } returns CalDavResult.error(500, "not expected")
    }

    @After
    fun tearDown() = database.close()

    private fun push(eventsDao: EventsDao = database.eventsDao(), icsSource: EventIcsSource = store) = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
        eventsDao = eventsDao,
        pendingOperationsDao = database.pendingOperationsDao(),
        accountRepository = accountRepository,
        attendeesDao = database.attendeesDao(),
        pendingCancelsDao = database.pendingCancelsDao(),
        icsSource = icsSource,
    )

    private fun vevent(recurrenceId: String?, summary: String, partstat: String = "NEEDS-ACTION", extra: String = "") =
        "BEGIN:VEVENT\r\nUID:$UID\r\nDTSTAMP:20260501T120000Z\r\n" +
            (recurrenceId?.let { "RECURRENCE-ID:$it\r\nDTSTART:20350602T140000Z\r\nDTEND:20350602T150000Z\r\n" }
                ?: "DTSTART:20350601T100000Z\r\nDTEND:20350601T110000Z\r\nRRULE:FREQ=DAILY;COUNT=10\r\n") +
            "SUMMARY:$summary\r\nORGANIZER:mailto:boss@example.test\r\n" +
            "ATTENDEE;PARTSTAT=$partstat:mailto:self@example.test\r\nX-SERVER-ONLY:kept\r\n${extra}END:VEVENT\r\n"

    private fun body(vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" + vevents.joinToString("") + "END:VCALENDAR\r\n"

    private suspend fun insert(event: Event): Long = database.eventsDao().insert(event)

    private suspend fun seedSeriesWithOccurrence(status: SyncStatus): Pair<Long, Long> {
        val seriesId = insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "Series", startTs = START_MS, endTs = START_MS + 3_600_000,
                dtstamp = 0, rrule = "FREQ=DAILY;COUNT=10", caldavUrl = url, etag = "etag-1", syncStatus = status,
            )
        )
        val occurrenceId = insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "Moved", startTs = INSTANCE_MS + 4 * 3_600_000,
                endTs = INSTANCE_MS + 5 * 3_600_000, dtstamp = 0, originalEventId = seriesId,
                originalInstanceTime = INSTANCE_MS, caldavUrl = url, etag = "etag-1",
            )
        )
        store.write(seriesId, body(vevent(null, "Series"), vevent("20350602T100000Z", "Moved")))
        return seriesId to occurrenceId
    }

    private suspend fun queueUpdate(eventId: Long, partstat: String? = null, targetUrl: String? = null) {
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId, operation = PendingOperation.OPERATION_UPDATE,
                partstatOnly = partstat != null, partstatTarget = partstat, targetUrl = targetUrl,
            )
        )
    }

    @Test
    fun `an update stores the body it sent once, on the series, with the new etag on every row`() = runTest {
        val (seriesId, occurrenceId) = seedSeriesWithOccurrence(SyncStatus.PENDING_UPDATE)
        queueUpdate(seriesId)

        push().pushForCalendar(calendar, client)

        val sent = puts.single().second
        assertEquals(sent, store.readOwn(seriesId))
        assertNull(store.readOwn(occurrenceId))
        assertTrue("unknown server lines are patched through", sent.contains("X-SERVER-ONLY:kept"))
        assertEquals("etag-2", database.eventsDao().getById(seriesId)!!.etag)
        assertEquals("etag-2", database.eventsDao().getById(occurrenceId)!!.etag)
    }

    @Test
    fun `a redirected update stores the body on the series and moves the occurrence's URL`() = runTest {
        val (seriesId, occurrenceId) = seedSeriesWithOccurrence(SyncStatus.PENDING_UPDATE)
        val moved = "${calendar.caldavUrl}moved.ics"
        coEvery { client.updateEvent(url, any(), any()) } answers {
            puts += Triple(firstArg(), secondArg(), thirdArg())
            CalDavResult.Success("etag-9", finalUrl = moved)
        }
        queueUpdate(seriesId)

        push().pushForCalendar(calendar, client)

        assertEquals(moved, database.eventsDao().getById(occurrenceId)!!.caldavUrl)
        assertEquals(puts.single().second, store.readOwn(seriesId))
        assertNull(store.readOwn(occurrenceId))
    }

    @Test
    fun `a second reply to an orphan occurrence builds on the first, under its etag`() = runTest {
        val placeholderId = insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "Untitled", startTs = INSTANCE_MS, endTs = INSTANCE_MS,
                dtstamp = 0, status = "CANCELLED", extraProperties = mapOf(PULL_SYNTHETIC_MASTER_EXTRA_KEY to "true"),
            )
        )
        val orphanId = insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "Orphan", startTs = INSTANCE_MS + 4 * 3_600_000,
                endTs = INSTANCE_MS + 5 * 3_600_000, dtstamp = 0, originalEventId = placeholderId,
                originalInstanceTime = INSTANCE_MS, caldavUrl = url, etag = "etag-1",
            )
        )
        store.writeResource(placeholderId, url, body(vevent("20350602T100000Z", "Orphan")))

        queueUpdate(orphanId, partstat = "ACCEPTED", targetUrl = url)
        push().pushForCalendar(calendar, client)
        val first = puts.last()
        assertTrue(first.second.contains("PARTSTAT=ACCEPTED"))
        assertEquals(first.second, store.read(database.eventsDao().getById(orphanId)!!))

        queueUpdate(orphanId, partstat = "DECLINED", targetUrl = url)
        push().pushForCalendar(calendar, client)
        val second = puts.last()
        assertEquals("the second reply uses the first reply's etag", "etag-2", second.third)
        assertEquals(first.second.replace("PARTSTAT=ACCEPTED", "PARTSTAT=DECLINED"), second.second)
    }

    @Test
    fun `a failing etag write leaves the series' etag and status as they were`() = runTest {
        val (seriesId, _) = seedSeriesWithOccurrence(SyncStatus.SYNCED)
        val before = store.readOwn(seriesId)
        val real = database.eventsDao()
        val failing = object : EventsDao by real {
            override suspend fun updateResourceEtag(seriesId: Long, oldUrl: String, newUrl: String, etag: String) {
                error("disk full")
            }
        }
        queueUpdate(seriesId, partstat = "ACCEPTED", targetUrl = url)

        push(eventsDao = failing).pushForCalendar(calendar, client)

        assertEquals(before, store.readOwn(seriesId))
        assertEquals("etag-1", real.getById(seriesId)!!.etag)
    }

    @Test
    fun `a failing body write rolls the etag back`() = runTest {
        val (seriesId, _) = seedSeriesWithOccurrence(SyncStatus.SYNCED)
        val before = store.readOwn(seriesId)
        val failing = object : EventIcsSource by store {
            override suspend fun writeResource(seriesId: Long, resourceUrl: String?, ics: String?) {
                error("disk full")
            }
        }
        queueUpdate(seriesId, partstat = "ACCEPTED", targetUrl = url)

        push(icsSource = failing).pushForCalendar(calendar, client)

        assertEquals(before, store.readOwn(seriesId))
        assertEquals("etag-1", database.eventsDao().getById(seriesId)!!.etag)
        assertEquals(SyncStatus.SYNCED, database.eventsDao().getById(seriesId)!!.syncStatus)
    }

    // ---- a stored copy past the 2 MB cursor window ----

    /** An unknown property of 2.5 M characters, folded as RFC 5545 §3.1 asks. */
    private val bigLine: String = buildString {
        append("X-BIG-ATTACHMENT:")
        append("A".repeat(2_500_000))
    }.chunked(74).joinToString("\r\n ") + "\r\n"

    private fun unfold(ics: String) = ics.replace(Regex("\r?\n[ \t]"), "")

    private fun bigValueLength(ics: String): Int =
        unfold(ics).lines().single { it.startsWith("X-BIG-ATTACHMENT:") }.trimEnd().length

    private suspend fun seedBigSeries(status: SyncStatus = SyncStatus.SYNCED, title: String = "Series"): Long {
        val seriesId = insert(
            Event(
                uid = UID, calendarId = calendar.id, title = title, startTs = START_MS, endTs = START_MS + 3_600_000,
                dtstamp = 0, rrule = "FREQ=DAILY;COUNT=10", caldavUrl = url, etag = "etag-1", syncStatus = status,
                organizerEmail = "boss@example.test", timezone = "UTC",
            )
        )
        store.write(seriesId, body(vevent(null, "Series", extra = bigLine)))
        return seriesId
    }

    @Test
    fun `a reply to a series whose stored copy is past the cursor window keeps the copy whole`() = runTest {
        val seriesId = seedBigSeries()
        queueUpdate(seriesId, partstat = "ACCEPTED", targetUrl = url)

        push().pushForCalendar(calendar, client)

        val sent = puts.single().second
        assertTrue(unfold(sent).contains("PARTSTAT=ACCEPTED"))
        assertEquals(2_500_000 + "X-BIG-ATTACHMENT:".length, bigValueLength(sent))
        assertEquals(sent, store.readOwn(seriesId))
    }

    @Test
    fun `an attendee cancel of a series whose stored copy is past the cursor window keeps the copy whole`() = runTest {
        val seriesId = seedBigSeries()
        database.pendingOperationsDao().insert(
            PendingOperation(eventId = seriesId, operation = PendingOperation.OPERATION_ATTENDEE_CANCEL, targetUrl = url)
        )

        push().pushForCalendar(calendar, client)

        val sent = puts.single().second
        assertTrue(unfold(sent).lines().any { it.trimEnd() == "STATUS:CANCELLED" })
        assertEquals(2_500_000 + "X-BIG-ATTACHMENT:".length, bigValueLength(sent))
        assertEquals(sent, store.readOwn(seriesId))
    }

    @Test
    fun `a 412 merge over a stored copy past the cursor window keeps both changes and the copy whole`() = runTest {
        val seriesId = seedBigSeries(status = SyncStatus.PENDING_UPDATE, title = "Local title")
        queueUpdate(seriesId)
        val serverCopy = body(vevent(null, "Series", extra = "LOCATION:Server room\r\n" + bigLine))
        var calls = 0
        coEvery { client.updateEvent(url, any(), any()) } answers {
            puts += Triple(firstArg(), secondArg(), thirdArg())
            if (calls++ == 0) CalDavResult.error(412, "Precondition Failed") else CalDavResult.success("etag-3")
        }
        coEvery { client.fetchEvent(url) } returns
            CalDavResult.success(org.onekash.kashcal.sync.client.model.CalDavEvent("s.ics", url, "etag-2", serverCopy))

        push().pushForCalendar(calendar, client)

        assertEquals(2, puts.size)
        val merged = unfold(puts[1].second).lines().map { it.trimEnd() }
        assertTrue("the user's title wins", "SUMMARY:Local title" in merged)
        assertTrue("the server's location is kept", "LOCATION:Server room" in merged)
        assertEquals(2_500_000 + "X-BIG-ATTACHMENT:".length, bigValueLength(puts[1].second))
        assertEquals("the merge retry is sent with the server's etag", "etag-2", puts[1].third)
    }
}
