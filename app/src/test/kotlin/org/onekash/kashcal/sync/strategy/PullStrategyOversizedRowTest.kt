package org.onekash.kashcal.sync.strategy

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.SyncItem
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.client.model.SyncReport
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Tests pulls over a calendar holding a local row too large for Android's CursorWindow (2 MB):
 * every full read of that row throws [SQLiteBlobTooBigException]. The rows are real, not stubbed
 * throws, so a test fails at whichever read reaches one first. The other events in the batch
 * must still land, and a failed lookup must never delete anything.
 *
 * A full or forced pull over such a row still fails: its stale check reads every row of the
 * calendar in one query. Rows already over the limit are not repaired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyOversizedRowTest {

    private lateinit var database: KashCalDatabase
    private lateinit var dataStore: KashCalDataStore
    private lateinit var calendarRepository: CalendarRepositoryImpl
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    private val accountRepository: AccountRepository = mockk()

    private val account = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
    )
    private val calendar = Calendar(
        id = 9L,
        accountId = account.id,
        caldavUrl = "https://caldav.example.test/cal9/",
        displayName = "Work",
        color = 0xFF0000,
        ctag = "old-ctag",
        syncToken = "token-A",
    )

    private companion object {
        const val BIG_UID = "uid-big"
        const val GOOD_UID = "uid-good"
        const val SERIES_UID = "uid-series"
        // The window holds a text column's UTF-8 bytes: 1.5M ASCII chars still read back,
        // 2.2M don't. This is clear of the limit.
        const val BIG_CHARS = 2_500_000
        val START_MS: Long = Instant.parse("2035-06-01T10:00:00Z").toEpochMilli()
        val SECOND_DAY_MS: Long = Instant.parse("2035-06-02T10:00:00Z").toEpochMilli()
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(calendar)
        dataStore = TestDataStoreFactory.createDefault()
        // The token-hold rule reads and bumps this counter; stub it rather than rely on the
        // relaxed default.
        var retryCount = 0
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } answers { retryCount }
        coEvery { dataStore.incrementParseFailureRetry(calendar.id) } answers { ++retryCount }
        calendarRepository = CalendarRepositoryImpl(database.calendarsDao())
        pullStrategy = pullStrategyWith(database.eventsDao())
        coEvery { accountRepository.getAccountById(account.id) } returns account
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun pullStrategyWith(eventsDao: EventsDao) = PullStrategy(
        database = database,
        calendarRepository = calendarRepository,
        eventsDao = eventsDao,
        attendeesDao = database.attendeesDao(),
        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), dataStore
        ),
        defaultQuirks = DefaultQuirks(calendar.caldavUrl),
        dataStore = dataStore,
        inviteNotifier = mockk(relaxed = true),
        accountRepository = accountRepository,
        reminderScheduler = mockk(relaxed = true),
    )

    private fun url(file: String) = "${calendar.caldavUrl}$file"

    private fun vevent(uid: String, summary: String, extra: String = ""): String =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260501T120000Z\r\nDTSTART:20350601T100000Z\r\n" +
            "DTEND:20350601T110000Z\r\nSUMMARY:$summary\r\n$extra" +
            "END:VEVENT\r\n"

    private fun changedOccurrence(uid: String, summary: String): String =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260501T120000Z\r\nRECURRENCE-ID:20350602T100000Z\r\n" +
            "DTSTART:20350602T140000Z\r\nDTEND:20350602T150000Z\r\nSUMMARY:$summary\r\n" +
            "END:VEVENT\r\n"

    private fun calendarBody(vararg vevents: String): String =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun ical(uid: String, summary: String, extra: String = ""): String =
        calendarBody(vevent(uid, summary, extra))

    private fun bigBody(uid: String) =
        ical(uid, "Big", extra = "DESCRIPTION:${"x".repeat(BIG_CHARS)}\r\n")

    /** Inserts a synced row; [rawIcal] decides whether it can be read back. */
    private suspend fun seedRow(
        uid: String,
        file: String,
        rawIcal: String,
        rrule: String? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null,
        etag: String = "etag-$file-1",
        startTs: Long = START_MS,
    ): Long = database.eventsDao().insert(
        Event(
            uid = uid,
            calendarId = calendar.id,
            title = "Seeded $uid",
            startTs = startTs,
            endTs = startTs + 3_600_000L,
            dtstamp = 0L,
            rrule = rrule,
            caldavUrl = url(file),
            etag = etag,
            syncStatus = SyncStatus.SYNCED,
            rawIcal = rawIcal,
            originalEventId = originalEventId,
            originalInstanceTime = originalInstanceTime,
        )
    )

    /** The synced series row whose stored server copy is too large to read back. */
    private suspend fun seedOversizedRow(rrule: String? = null): Long =
        seedRow(BIG_UID, "big.ics", bigBody(BIG_UID), rrule = rrule, etag = "etag-big-1")

    private fun countRows(uid: String): Long =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM events WHERE uid = ?", arrayOf(uid)
        ).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun session() = SyncSessionBuilder(
        calendarId = calendar.id,
        calendarName = calendar.displayName,
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.FOREGROUND_MANUAL,
    )

    /** Serves [changed] and [deletedHrefs] as the delta since token-A. */
    private fun serveDelta(changed: List<CalDavEvent>, deletedHrefs: List<String> = emptyList()) {
        coEvery { client.syncCollection(calendar.caldavUrl, "token-A") } returns CalDavResult.success(
            SyncReport(
                syncToken = "token-B",
                changed = changed.map { SyncItem(it.href, it.etag, SyncItemStatus.OK) },
                deleted = deletedHrefs,
            )
        )
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(changed)
    }

    private fun good() =
        CalDavEvent("good.ics", url("good.ics"), "etag-good", ical(GOOD_UID, "Good"))

    @Test
    fun `the seeded row is too large to read back`() = runTest {
        seedOversizedRow()
        try {
            database.eventsDao().getMasterByUidAndCalendar(BIG_UID, calendar.id)
            fail("reading a row over the CursorWindow limit must throw on this runtime")
        } catch (e: SQLiteBlobTooBigException) {
            // The same exception the phone reports.
        }
    }

    @Test
    fun `incremental pull still saves the other events when one local row is too large`() = runTest {
        seedOversizedRow()
        serveDelta(listOf(
            CalDavEvent("big.ics", url("big.ics"), "etag-big-2", ical(BIG_UID, "Big 2")),
            good(),
        ))
        val session = session()

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session)

        assertTrue("one unreadable row must not fail the calendar's pull, was $result",
            result is PullResult.Success)
        assertEquals("only the good event is added", 1, (result as PullResult.Success).eventsAdded)
        assertNotNull("the good event must land",
            database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, calendar.id))
        assertTrue("the unreadable event is counted as a skipped event",
            session.getSkippedParseError() > 0)
    }

    @Test
    fun `a failed lookup recorded on the incremental path holds the token`() = runTest {
        seedOversizedRow()
        serveDelta(listOf(
            CalDavEvent("big.ics", url("big.ics"), "etag-big-2", ical(BIG_UID, "Big 2")),
            good(),
        ))

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session())

        assertEquals("token-A", (result as PullResult.Success).newSyncToken)
        val stored = calendarRepository.getCalendarById(calendar.id)!!
        assertEquals("token-A", stored.syncToken)
        assertEquals("a held token keeps the old ctag, so the retry isn't skipped", "old-ctag", stored.ctag)
    }

    @Test
    fun `a changed occurrence of an unreadable series is skipped without a placeholder`() = runTest {
        seedOversizedRow(rrule = "FREQ=DAILY;COUNT=5")
        serveDelta(listOf(
            CalDavEvent("big.ics", url("big.ics"), "etag-big-2",
                calendarBody(changedOccurrence(BIG_UID, "Moved"))),
            good(),
        ))
        val session = session()

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session)

        assertTrue("was $result", result is PullResult.Success)
        assertNotNull(database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, calendar.id))
        assertEquals("no placeholder series or occurrence row is added", 1L, countRows(BIG_UID))
        assertTrue(session.getSkippedParseError() > 0)
    }

    @Test
    fun `an unreadable series and its changed occurrence in one resource are skipped together`() = runTest {
        seedOversizedRow(rrule = "FREQ=DAILY;COUNT=5")
        serveDelta(listOf(
            CalDavEvent("big.ics", url("big.ics"), "etag-big-2", calendarBody(
                vevent(BIG_UID, "Big 2", extra = "RRULE:FREQ=DAILY;COUNT=5\r\n"),
                changedOccurrence(BIG_UID, "Moved"),
            )),
            good(),
        ))
        val session = session()

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session)

        assertTrue("was $result", result is PullResult.Success)
        assertNotNull(database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, calendar.id))
        assertEquals("no placeholder series or occurrence row is added", 1L, countRows(BIG_UID))
        assertEquals("the series fails once; its changed occurrence is held, not failed again",
            1, session.getSkippedParseError())
    }

    @Test
    fun `an unreadable changed occurrence is skipped and the rest land`() = runTest {
        val seriesBody = calendarBody(vevent(SERIES_UID, "Series", extra = "RRULE:FREQ=DAILY;COUNT=5\r\n"))
        val seriesId = seedRow(SERIES_UID, "series.ics", seriesBody, rrule = "FREQ=DAILY;COUNT=5", etag = "etag-series-1")
        seedRow(
            SERIES_UID, "series.ics", "y".repeat(BIG_CHARS),
            originalEventId = seriesId, originalInstanceTime = SECOND_DAY_MS,
            etag = "etag-series-1", startTs = SECOND_DAY_MS,
        )
        serveDelta(listOf(
            CalDavEvent("series.ics", url("series.ics"), "etag-series-1", calendarBody(
                vevent(SERIES_UID, "Series", extra = "RRULE:FREQ=DAILY;COUNT=5\r\n"),
                changedOccurrence(SERIES_UID, "Moved again"),
            )),
            good(),
        ))
        val session = session()

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session)

        assertTrue("was $result", result is PullResult.Success)
        assertNotNull(database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, calendar.id))
        assertTrue(session.getSkippedParseError() > 0)
    }

    @Test
    fun `one unreadable deleted event leaves the other deletions applied`() = runTest {
        seedOversizedRow()
        seedRow("uid-small", "small.ics", ical("uid-small", "Small"))
        serveDelta(changed = emptyList(), deletedHrefs = listOf("/cal9/big.ics", "/cal9/small.ics"))
        val session = session()

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session)

        assertTrue("was $result", result is PullResult.Success)
        assertEquals("the readable deleted event is removed", 0L, countRows("uid-small"))
        assertEquals("a failed lookup deletes nothing", 1L, countRows(BIG_UID))
        assertEquals("token-A", (result as PullResult.Success).newSyncToken)
        val stored = calendarRepository.getCalendarById(calendar.id)!!
        assertEquals("token-A", stored.syncToken)
        assertEquals("old-ctag", stored.ctag)
    }

    @Test
    fun `an unreadable changed occurrence skips its series' prune and the pull succeeds`() = runTest {
        val seriesBody = calendarBody(vevent(SERIES_UID, "Series", extra = "RRULE:FREQ=DAILY;COUNT=5\r\n"))
        val seriesId = seedRow(SERIES_UID, "series.ics", seriesBody, rrule = "FREQ=DAILY;COUNT=5", etag = "etag-series-1")
        // Seeded without an occurrences link, so only the prune pass reads this row.
        seedRow(
            SERIES_UID, "series.ics", "y".repeat(BIG_CHARS),
            originalEventId = seriesId, originalInstanceTime = SECOND_DAY_MS,
            etag = "etag-series-1", startTs = SECOND_DAY_MS,
        )
        serveDelta(listOf(
            CalDavEvent("series.ics", url("series.ics"), "etag-series-2", calendarBody(
                vevent(SERIES_UID, "Series renamed",
                    extra = "RRULE:FREQ=DAILY;COUNT=5\r\nEXDATE:20350602T100000Z\r\n"),
            )),
            good(),
        ))

        val session = session()
        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = session)

        assertTrue("was $result", result is PullResult.Success)
        assertEquals("the prune deletes nothing", 2L, countRows(SERIES_UID))
        assertTrue(
            "the skipped prune is reported",
            session.build().warnings.orEmpty().any { it.startsWith("Could not check changed occurrences") }
        )
        assertEquals("Series renamed",
            database.eventsDao().getMasterByUidAndCalendar(SERIES_UID, calendar.id)!!.title)
        assertNotNull(database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, calendar.id))
    }

    @Test
    fun `cancellation from a lookup is rethrown`() = runTest {
        val real = database.eventsDao()
        val cancelling = object : EventsDao by real {
            override suspend fun getMasterByUidAndCalendar(uid: String, calendarId: Long): Event? =
                throw CancellationException("cancelled")
        }
        serveDelta(listOf(good()))

        try {
            pullStrategyWith(cancelling).pull(calendar, client = client, sessionBuilder = session())
            fail("a cancelled lookup must cancel the pull")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    /**
     * A server body under the window limit must still read back once pulled. RFC 5545 §3.8.1.1
     * allows inline binary ATTACH, and Cyrus rewrites managed attachments up to 1 MB to inline
     * BINARY during iTIP delivery.
     */
    @Test
    fun `a pulled event with a 1_2 MB inline attachment reads back`() = runTest {
        val attach = "ATTACH;FMTTYPE=application/pdf;ENCODING=BASE64;VALUE=BINARY:" +
            "QUJD".repeat(300_000) + "\r\n"
        val body = ical(BIG_UID, "With attachment", extra = attach)
        serveDelta(listOf(CalDavEvent("big.ics", url("big.ics"), "etag-big-1", body)))

        pullStrategy.pull(calendar, client = client)

        val sizes = database.openHelper.readableDatabase.query(
            "SELECT length(raw_ical), length(extra_properties), length(description) " +
                "FROM events WHERE uid = ?",
            arrayOf(BIG_UID)
        ).use { c -> if (c.moveToFirst()) listOf(c.getLong(0), c.getLong(1), c.getLong(2)) else null }
        assertNotNull("the event must be stored", sizes)
        try {
            assertNotNull(database.eventsDao().getMasterByUidAndCalendar(BIG_UID, calendar.id))
        } catch (e: SQLiteBlobTooBigException) {
            fail("a ${body.length}-char server body must read back; stored column lengths $sizes")
        }
    }
}
