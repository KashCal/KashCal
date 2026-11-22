package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.SyncItem
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.client.model.SyncReport
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Tests where a pull keeps a resource's body ([EventIcsStore]): once, on the series row, never on
 * the changed occurrences it holds, and not at all over a series the pull leaves alone (pending
 * local changes, a push this cycle), whose stored body is the base of its pending edit's 412 merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyEventIcsTest {

    private lateinit var database: KashCalDatabase
    private lateinit var store: EventIcsStore
    private lateinit var dataStore: KashCalDataStore
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    private val accountRepository: AccountRepository = mockk()

    private val account = Account(id = 1L, provider = AccountProvider.CALDAV, email = "self@example.test")
    private val calendar = Calendar(
        id = 9L, accountId = account.id, caldavUrl = "https://caldav.example.test/cal9/",
        displayName = "Work", color = 0, ctag = "old-ctag", syncToken = "token-A",
    )

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
        dataStore = TestDataStoreFactory.createDefault()
        var retries = 0
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } answers { retries }
        coEvery { dataStore.incrementParseFailureRetry(calendar.id) } answers { ++retries }
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), dataStore),
            defaultQuirks = DefaultQuirks(calendar.caldavUrl),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true),
            icsSource = store,
        )
        coEvery { accountRepository.getAccountById(account.id) } returns account
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
    }

    @After
    fun tearDown() = database.close()

    private fun url(file: String) = "${calendar.caldavUrl}$file"

    private fun series(summary: String) =
        "BEGIN:VEVENT\r\nUID:$UID\r\nDTSTAMP:20260501T120000Z\r\nDTSTART:20350601T100000Z\r\n" +
            "DTEND:20350601T110000Z\r\nRRULE:FREQ=DAILY;COUNT=10\r\nSUMMARY:$summary\r\nEND:VEVENT\r\n"

    private fun occurrence(day: Int, summary: String) =
        "BEGIN:VEVENT\r\nUID:$UID\r\nDTSTAMP:20260501T120000Z\r\nRECURRENCE-ID:2035060${day}T100000Z\r\n" +
            "DTSTART:2035060${day}T140000Z\r\nDTEND:2035060${day}T150000Z\r\nSUMMARY:$summary\r\nEND:VEVENT\r\n"

    private fun body(vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" + vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun serve(vararg events: CalDavEvent) {
        coEvery { client.syncCollection(calendar.caldavUrl, "token-A") } returns CalDavResult.success(
            SyncReport(syncToken = "token-B", changed = events.map { SyncItem(it.href, it.etag, SyncItemStatus.OK) }, deleted = emptyList())
        )
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns CalDavResult.success(events.toList())
    }

    private suspend fun pull(recentlyPushed: Set<Long> = emptySet()) =
        pullStrategy.pull(calendar, client = client, recentlyPushedEventIds = recentlyPushed)

    private suspend fun seedSeries(status: SyncStatus = SyncStatus.SYNCED, body: String): Long {
        val id = database.eventsDao().insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "Seeded", startTs = START_MS, endTs = START_MS + 3_600_000,
                dtstamp = 0, rrule = "FREQ=DAILY;COUNT=10", caldavUrl = url("s.ics"), etag = "etag-1", syncStatus = status,
            )
        )
        store.write(id, body)
        return id
    }

    private suspend fun rows(): List<Event> = database.eventsDao().getByUid(UID)

    @Test
    fun `a series with three changed occurrences stores its resource once, on the series`() = runTest {
        val resource = body(series("S"), occurrence(2, "A"), occurrence(3, "B"), occurrence(4, "C"))
        serve(CalDavEvent("s.ics", url("s.ics"), "etag-2", resource))

        pull()

        val all = rows()
        val master = all.single { it.originalEventId == null }
        val exceptions = all.filter { it.originalEventId != null }
        assertEquals(3, exceptions.size)
        assertEquals(resource, store.readOwn(master.id))
        exceptions.forEach {
            assertNull("occurrence ${it.id} stores no copy", store.readOwn(it.id))
            assertEquals(resource, store.read(it))
        }
    }

    @Test
    fun `a series with pending local changes keeps the body its edit is based on`() = runTest {
        val base = body(series("Base"), occurrence(2, "A"))
        val id = seedSeries(SyncStatus.PENDING_UPDATE, base)
        serve(CalDavEvent("s.ics", url("s.ics"), "etag-2", body(series("Server edit"), occurrence(2, "A moved"))))

        pull()

        assertEquals(base, store.readOwn(id))
    }

    @Test
    fun `a series pushed in this cycle keeps the body it sent`() = runTest {
        val sent = body(series("Sent"), occurrence(2, "A"))
        val id = seedSeries(body = sent)
        serve(CalDavEvent("s.ics", url("s.ics"), "etag-2", body(series("Stale CDN copy"), occurrence(2, "A"))))

        pull(recentlyPushed = setOf(id))

        assertEquals(sent, store.readOwn(id))
    }

    @Test
    fun `an orphan occurrence's resource is stored on its placeholder`() = runTest {
        val resource = body(occurrence(2, "Orphan"))
        serve(CalDavEvent("o.ics", url("o.ics"), "etag-1", resource))

        pull()

        val all = rows()
        val placeholder = all.single { it.originalEventId == null }
        val orphan = all.single { it.originalEventId != null }
        assertEquals("true", placeholder.extraProperties?.get(PULL_SYNTHETIC_MASTER_EXTRA_KEY))
        assertEquals(resource, store.readOwn(placeholder.id))
        assertNull(store.readOwn(orphan.id))
        assertEquals(resource, store.read(orphan))
        assertNull("a series-level read of a placeholder generates fresh", store.read(placeholder))
        assertEquals(
            "an export of the placeholder's calendar patches nothing onto it",
            emptyMap<Long, String>(),
            EventReader(database, store).getIcsForExport(placeholder, listOf(orphan)),
        )
    }

    @Test
    fun `a pulled occurrence drops a stale copy of its own`() = runTest {
        val id = seedSeries(body = body(series("S"), occurrence(2, "A")))
        val exceptionId = database.eventsDao().insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "A", startTs = INSTANCE_MS, endTs = INSTANCE_MS + 1,
                dtstamp = 0, originalEventId = id, originalInstanceTime = INSTANCE_MS,
                caldavUrl = url("old-host.ics"), etag = "etag-1",
            )
        )
        store.write(exceptionId, "STALE")
        val resource = body(series("S2"), occurrence(2, "A2"))
        serve(CalDavEvent("s.ics", url("s.ics"), "etag-2", resource))

        pull()

        val exception = rows().single { it.originalEventId != null }
        assertNull(store.readOwn(exception.id))
        assertEquals(resource, store.read(exception))
        assertNotNull(store.readOwn(id))
    }

    private fun pullWith(icsSource: org.onekash.kashcal.data.db.EventIcsSource) = PullStrategy(
        database = database,
        calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
        eventsDao = database.eventsDao(),
        attendeesDao = database.attendeesDao(),
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), dataStore),
        defaultQuirks = DefaultQuirks(calendar.caldavUrl),
        dataStore = dataStore,
        inviteNotifier = mockk(relaxed = true),
        accountRepository = accountRepository,
        reminderScheduler = mockk(relaxed = true),
        icsSource = icsSource,
    )

    @Test
    fun `a resource with three changed occurrences is written once`() = runTest {
        var resourceWrites = 0
        val counting = object : org.onekash.kashcal.data.db.EventIcsSource by store {
            override suspend fun writeResource(seriesId: Long, resourceUrl: String?, ics: String?) {
                resourceWrites++
                store.writeResource(seriesId, resourceUrl, ics)
            }
        }
        serve(CalDavEvent("s.ics", url("s.ics"), "etag-2", body(series("S"), occurrence(2, "A"), occurrence(3, "B"), occurrence(4, "C"))))

        pullWith(counting).pull(calendar, client = client)

        assertEquals(1, resourceWrites)
    }

    @Test
    fun `a placeholder with pending changes keeps the body it holds`() = runTest {
        val placeholderId = database.eventsDao().insert(
            Event(
                uid = UID, calendarId = calendar.id, title = "Untitled", startTs = INSTANCE_MS, endTs = INSTANCE_MS,
                dtstamp = 0, status = "CANCELLED", syncStatus = SyncStatus.PENDING_UPDATE,
                extraProperties = mapOf(PULL_SYNTHETIC_MASTER_EXTRA_KEY to "true"),
            )
        )
        store.write(placeholderId, "HELD")
        serve(CalDavEvent("o.ics", url("o.ics"), "etag-1", body(occurrence(2, "Orphan"))))

        pull()

        assertEquals("HELD", store.readOwn(placeholderId))
    }

    @Test
    fun `a pulled resource past the cursor window reads back and exports whole`() = runTest {
        val big = "X-BIG-ATTACHMENT:" + "A".repeat(2_500_000)
        val folded = big.chunked(74).joinToString("\r\n ") + "\r\n"
        val resource = body(series("S").replace("END:VEVENT", folded + "END:VEVENT"), occurrence(2, "A"))
        serve(CalDavEvent("s.ics", url("s.ics"), "etag-2", resource))

        pull()

        val master = rows().single { it.originalEventId == null }
        val exceptions = rows().filter { it.originalEventId != null }
        val export = org.onekash.kashcal.domain.model.EventExport(
            master, exceptions, emptyMap(), EventReader(database, store).getIcsForExport(master, exceptions)
        )
        val text = org.onekash.kashcal.util.IcsExporter().buildCalendarText(listOf(export), "Work")
            .replace(Regex("\r?\n[ \t]"), "")
        assertTrue(text.lines().any { it.trimEnd() == big })
    }
}
