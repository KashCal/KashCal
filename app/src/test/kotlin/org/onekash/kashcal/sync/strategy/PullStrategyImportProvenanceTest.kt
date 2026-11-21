package org.onekash.kashcal.sync.strategy

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A pull that rewrites an imported row from server data keeps [Event.importSource] and
 * [Event.itipDtstamp]: the server copy carries neither, and without them the import rules
 * (receipt, reminder checks, iTIP ordering) would stop applying after the first server change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyImportProvenanceTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    private val accountRepository: AccountRepository = mockk()

    private val account = Account(id = 1L, provider = AccountProvider.CALDAV, email = "self@example.test")
    private val calendarId = 9L
    private val calendarUrl = "https://caldav.example.test/cal9/"
    private var pullCount = 0

    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val hour = 3_600_000L
    private val dayMs = 24 * hour
    private val seriesStart = ((System.currentTimeMillis() / dayMs) + 21) * dayMs + 15 * hour
    private val slot = seriesStart + 7 * dayMs

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(
            Calendar(id = calendarId, accountId = account.id, caldavUrl = calendarUrl, displayName = "Work", color = 0)
        )
        val dataStore = TestDataStoreFactory.createDefault()
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(
                database, database.occurrencesDao(), database.eventsDao(), dataStore
            ),
            defaultQuirks = ICloudQuirks(),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true),
            icsSource = EventIcsStore(database),
        )
        coEvery { accountRepository.getAccountById(account.id) } returns account
    }

    @After
    fun tearDown() = database.close()

    private fun utc(ms: Long) = utcFormat.format(Instant.ofEpochMilli(ms))

    private fun body(title: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:series-uid\r\nDTSTAMP:2026010${pullCount + 1}T000000Z\r\n" +
            "DTSTART:${utc(seriesStart)}\r\nDTEND:${utc(seriesStart + hour)}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:$title\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:series-uid\r\nDTSTAMP:2026010${pullCount + 1}T000000Z\r\n" +
            "RECURRENCE-ID:${utc(slot)}\r\nDTSTART:${utc(slot + hour)}\r\nDTEND:${utc(slot + 2 * hour)}\r\n" +
            "SUMMARY:$title moved\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

    private suspend fun pullServing(body: String) {
        pullCount++
        val href = "/cal9/series.ics"
        val events = listOf(CalDavEvent(href, "https://caldav.example.test$href", "etag-$pullCount", body))
        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-$pullCount", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.success(events.map { it.href to it.etag })
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(events.map { it.href to it.etag })
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(events)
        val calendar = database.calendarsDao().getById(calendarId)!!
        val result = pullStrategy.pull(calendar, client = client)
        assertTrue("pull $pullCount must succeed, got $result", result is PullResult.Success)
    }

    private fun markImported(itipDtstamp: Long) {
        TestDatabases.raw(database).execSQL(
            "UPDATE events SET import_source = '${Event.IMPORT_SOURCE_ICS_FILE}', itip_dtstamp = $itipDtstamp " +
                "WHERE uid = 'series-uid'"
        )
    }

    @Test
    fun `a pull rewriting an imported series and its exception keeps import_source and itip_dtstamp`() = runTest {
        pullServing(body("Sync"))
        markImported(itipDtstamp = 1_700_000_000_000L)

        pullServing(body("Sync renamed"))

        val rows = database.eventsDao().findAllByUid("series-uid", calendarId)
        assertEquals("master and exception", 2, rows.size)
        assertTrue("the server change landed: ${rows.map { it.title }}", rows.all { it.title.startsWith("Sync renamed") })
        rows.forEach { row ->
            assertEquals("import_source kept on ${row.title}", Event.IMPORT_SOURCE_ICS_FILE, row.importSource)
            assertEquals("itip_dtstamp kept on ${row.title}", 1_700_000_000_000L, row.itipDtstamp)
        }
    }

    @Test
    fun `a pull leaves a non-imported row without import fields`() = runTest {
        pullServing(body("Sync"))

        pullServing(body("Sync renamed"))

        database.eventsDao().findAllByUid("series-uid", calendarId).forEach { row ->
            assertEquals(null, row.importSource)
            assertEquals(null, row.itipDtstamp)
        }
    }
}
