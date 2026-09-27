package org.onekash.kashcal.sync.strategy

import io.mockk.mockk
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EtagEntry
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore

/**
 * Tests PullStrategy's etag fallback (v16.9.0) after sync-collection rejects the sync-token
 * with 403 or 410.
 *
 * Instead of a full pull (~834KB), the fallback lists only etags (~33KB), diffs them with Room
 * and multigets only new and changed events, saving ~96% bandwidth. It hands over to the full
 * pull when Room has no etags for the window or the etag listing fails. Also covered: pending
 * and recently pushed events aren't deleted, percent-encoding differences in hrefs, the sync
 * lookback window and a relative href against a canonical iCloud URL.
 */
class PullStrategyEtagFallbackTest {

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
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    @MockK
    private lateinit var dataStore: KashCalDataStore

    @MockK
    private lateinit var syncSessionStore: SyncSessionStore

    private val quirks = ICloudQuirks()

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

        // No master by UID, so lookups fall through to caldavUrl.
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        // SYNCED, the Event default, so the pull's re-read before the upsert sees no pending
        // edit.
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED

        // The "All" lookback reads local etags with the unfiltered getEtagsByCalendarId. Tests
        // of the time-filtered query override this with a day count.
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // PROPFIND Depth:1 fails with 501. pullFull tries it only for a calendar without a
        // sync-token, and every calendar here has one.
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Fallback trigger ==========

    @Test
    fun `etag fallback triggered on 403 sync token expired`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // The ctag changed, so the calendar syncs.
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))

        // sync-collection rejects the token with 403.
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has an event with an etag.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // The server lists it with the same etag: no change.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair(eventHref, "etag-1")
            ))

        // The new sync-token for the result.
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // The fallback listed etags.
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
        // Nothing changed, so no multiget.
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `etag fallback triggered on 410 sync token gone`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "gone-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "gone-token") } returns
            CalDavResult.error(410, "Sync token gone")

        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "etag-1")))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    // ========== Handing over to pullFull ==========

    @Test
    fun `falls through to pullFull when no local events`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // No local etags (a first sync or an empty calendar).
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns emptyList()

        // pullFull's listing.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // The fallback read local etags and handed over.
        coVerify { eventsDao.getEtagsByCalendarId(calendar.id) }
        // pullFull listed etags; the fallback returns before listing when Room has none.
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `falls through to pullFull when etag fetch fails`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has an event.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // The fallback's etag listing fails; pullFull's succeeds.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returnsMany listOf(
            CalDavResult.error(500, "Server error"),
            CalDavResult.success(emptyList())
        )

        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Two listings: the fallback's and pullFull's.
        coVerify(exactly = 2) { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    // ========== Change detection ==========

    @Test
    fun `fetches events with different etags (changed)`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // The local event has the old etag.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "old-etag")
        )

        // The server has a new etag: the event changed.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "new-etag")))

        // Multiget of the changed event.
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = eventHref,
                    url = eventUrl,
                    etag = "new-etag",
                    icalData = createSimpleIcal("uid-1", "Updated Event")
                )
            ))

        val existingEvent = createEvent(id = 100L, caldavUrl = eventUrl).copy(etag = "old-etag")
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsUpdated)
        // Only the changed event is fetched.
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { it.size == 1 }) }
    }

    @Test
    fun `fetches new events not present locally`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val existingHref = "/calendars/home/existing.ics"
        val newHref = "/calendars/home/new.ics"
        val existingUrl = "https://caldav.example.com$existingHref"
        val newUrl = "https://caldav.example.com$newHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has one event.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(existingUrl, "etag-1")
        )

        // The server has two, one new.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair(existingHref, "etag-1"),  // unchanged
                Pair(newHref, "etag-new")      // new
            ))

        // Multiget of the new event only.
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = newHref,
                    url = newUrl,
                    etag = "etag-new",
                    icalData = createSimpleIcal("uid-new", "New Event")
                )
            ))

        coEvery { eventsDao.getByCaldavUrl(newUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 200L
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        // Only the new event is fetched, not the unchanged one.
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { it.size == 1 && newHref in it }) }
    }

    @Test
    fun `skips events with matching etags`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        // The href resolves to the stored URL.
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // The local etag matches the server's.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "same-etag")
        )

        // The server lists the same etag: no change.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "same-etag")))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(0, result.eventsUpdated)
        // Nothing changed, so no multiget.
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    // ========== Deletion ==========

    @Test
    fun `deletes events not on server`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val deletedHref = "/calendars/home/deleted.ics"
        val deletedUrl = "https://caldav.example.com$deletedHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has an event the server no longer lists.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(deletedUrl, "etag-deleted")
        )

        // The server lists nothing: the event was deleted.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        val deletedEvent = createEvent(id = 100L, caldavUrl = deletedUrl)
        coEvery { eventsDao.getByCaldavUrl(deletedUrl) } returns deletedEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(100L) }
    }

    @Test
    fun `respects pending local changes on deletion`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val pendingHref = "/calendars/home/pending.ics"
        val pendingUrl = "https://caldav.example.com$pendingHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has an event with a pending update.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(pendingUrl, "etag-pending")
        )

        // The server no longer lists it.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        // It has pending local changes, so it must not be deleted.
        val pendingEvent = createEvent(id = 100L, caldavUrl = pendingUrl)
            .copy(syncStatus = SyncStatus.PENDING_UPDATE)
        coEvery { eventsDao.getByCaldavUrl(pendingUrl) } returns pendingEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        // The pending event is kept.
        coVerify(exactly = 0) { eventsDao.deleteById(100L) }
    }

    @Test
    fun `etag fallback does not false-delete when server encodes at-sign differently`() = runTest {
        // #333 on the etag-fallback path: the stored URL has a literal '@'; the server
        // (Radicale) lists the same resource with '@' encoded as %40 and an unchanged etag. A
        // plain set difference would class it as both deleted and new (a delete plus a
        // re-fetch); it must read as unchanged.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val storedUrl = "https://caldav.example.com/calendars/home/uuid@kashcal.onekash.org.ics"
        val serverHref = "/calendars/home/uuid%40kashcal.onekash.org.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // The stored URL has a literal '@'.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(storedUrl, "etag-1")
        )
        // The server lists the same resource with %40 and the same etag.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(serverHref, "etag-1")))

        val storedEvent = createEvent(id = 55L, caldavUrl = storedUrl)
        // getByCaldavUrl matches exactly, as the database does; the canonical fallback reads
        // getEventsWithCaldavUrl.
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByCaldavUrl(storedUrl) } returns storedEvent
        coEvery { eventsDao.getEventsWithCaldavUrl(calendar.id) } returns listOf(storedEvent)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Not deleted,
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(55L) }
        // and not re-fetched as new or changed (no multiget for the %40 href).
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `etag fallback still fetches a genuinely changed at-sign event`() = runTest {
        // Canonicalizing only the comparison: a changed etag on an event with '@' in its name
        // is still fetched, with the server's exact href.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val storedUrl = "https://caldav.example.com/calendars/home/uuid@kashcal.onekash.org.ics"
        val serverHref = "/calendars/home/uuid%40kashcal.onekash.org.ics"
        val serverUrl = "https://caldav.example.com$serverHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(storedUrl, "etag-old")
        )
        // The same resource with a different etag: changed.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(serverHref, "etag-new")))

        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByCaldavUrl(storedUrl) } returns createEvent(id = 55L, caldavUrl = storedUrl)
        coEvery { eventsDao.getEventsWithCaldavUrl(calendar.id) } returns
            listOf(createEvent(id = 55L, caldavUrl = storedUrl))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Not treated as a deletion.
        coVerify(exactly = 0) { eventsDao.deleteById(55L) }
        // The multiget uses the server's encoded href, not a rewritten one.
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { hrefs -> hrefs.any { it.contains("%40") } }) }
    }

    @Test
    fun `etag fallback uses unfiltered query when sync lookback is All`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // "All" lookback (Int.MAX_VALUE).
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has an event.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // The server lists it unchanged.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "etag-1")))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // "All" reads the unfiltered getEtagsByCalendarId, not getEtagsByCalendarIdInRange.
        coVerify { eventsDao.getEtagsByCalendarId(calendar.id) }
        coVerify(exactly = 0) { eventsDao.getEtagsByCalendarIdInRange(any(), any(), any()) }
    }

    @Test
    fun `etag fallback does not delete events outside sync window`() = runTest {
        // #87: with a bounded lookback (here 180 days), a local event outside the window must
        // not be deleted because the server's time-range REPORT didn't list it. The
        // time-filtered DAO query leaves it out of the comparison.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val recentHref = "/calendars/home/recent.ics"
        val recentUrl = "https://caldav.example.com$recentHref"
        val oldUrl = "https://caldav.example.com/calendars/home/old.ics"

        // 180-day lookback.
        every { dataStore.syncPastDays } returns flowOf(180)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // The time-filtered local etags hold only the recent event; the DAO leaves out the old.
        coEvery { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(
            EtagEntry(recentUrl, "etag-recent")
        )

        // The server lists nothing: the recent event was deleted there.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        val recentEvent = createEvent(id = 10L, caldavUrl = recentUrl)
        coEvery { eventsDao.getByCaldavUrl(recentUrl) } returns recentEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // The recent event is deleted: in the window and not on the server.
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(10L) }
        // The old event was never compared: the time-filtered query ran, not the unfiltered one.
        coVerify { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.getEtagsByCalendarId(any()) }
    }

    @Test
    fun `etag fallback preserves recurring events outside time window`() = runTest {
        // A recurring event's start_ts and end_ts are its first occurrence's, so the DAO query
        // keeps a row with `rrule IS NOT NULL` whatever its times.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val recurringHref = "/calendars/home/weekly.ics"
        val recurringUrl = "https://caldav.example.com$recurringHref"

        // 180-day lookback.
        every { dataStore.syncPastDays } returns flowOf(180)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // The time-filtered query returns the recurring event although its first occurrence
        // is outside the window.
        coEvery { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(
            EtagEntry(recurringUrl, "etag-recurring")
        )

        // The server's time-range filter expands recurrences and lists it.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(recurringHref, "etag-recurring")))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Not deleted: both sides have it.
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `etag fallback uses configurable sync lookback from preferences`() = runTest {
        // With syncPastDays = 180, fetchEtagsInRange starts about 180 days ago.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // 180-day lookback.
        every { dataStore.syncPastDays } returns flowOf(180)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        coEvery { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "etag-1")))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        pullStrategy.pull(calendar, client = client)

        // A start about 180 days ago.
        val expectedPastMs = 180L * 24 * 60 * 60 * 1000
        coVerify {
            client.fetchEtagsInRange(
                calendar.caldavUrl,
                match { startMs ->
                    val now = System.currentTimeMillis()
                    val expected = now - expectedPastMs
                    // 5 seconds of slack for the test's run time
                    kotlin.math.abs(startMs - expected) < 5000
                },
                any()
            )
        }
        // The time-filtered DAO query, not the unfiltered one.
        coVerify { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.getEtagsByCalendarId(any()) }
    }

    // ========== Recently pushed events aren't deleted (v23.2.1) ==========
    // RFC 4791 doesn't require a server to list an event right after its PUT.

    @Test
    fun `etag fallback does not delete recently pushed event`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val pushedHref = "/calendars/home/pushed.ics"
        val pushedUrl = "https://caldav.example.com$pushedHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Room has an event pushed earlier in this sync.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(pushedUrl, "etag-from-put")
        )

        // The server doesn't list it yet.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        val pushedEvent = createEvent(id = 42L, caldavUrl = pushedUrl).copy(
            syncStatus = SyncStatus.SYNCED
        )
        coEvery { eventsDao.getByCaldavUrl(pushedUrl) } returns pushedEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(
            calendar,
            client = client,
            recentlyPushedEventIds = setOf(42L)
        )

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(42L) }
    }

    @Test
    fun `etag fallback still deletes stale events not in recentlyPushedEventIds`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val staleHref = "/calendars/home/stale.ics"
        val staleUrl = "https://caldav.example.com$staleHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(staleUrl, "etag-stale")
        )

        // The server doesn't list this event.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        val staleEvent = createEvent(id = 99L, caldavUrl = staleUrl)
        coEvery { eventsDao.getByCaldavUrl(staleUrl) } returns staleEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(
            calendar,
            client = client,
            recentlyPushedEventIds = setOf(42L)  // a different id
        )

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 1) { eventsDao.deleteById(99L) }
    }

    // ========== URL normalization ==========

    @Test
    fun `handles URL normalization for hostname changes`() = runTest {
        // Stored iCloud URLs use the canonical caldav.icloud.com host. A relative server href,
        // built against the calendar URL, must match the stored URL.
        val calendar = createCalendar(
            ctag = "old-ctag",
            syncToken = "expired-token",
            caldavUrl = "https://caldav.icloud.com/123/calendars/home/"
        )
        // The stored URL is canonical.
        val localUrl = "https://caldav.icloud.com/123/calendars/home/event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(localUrl, "same-etag")
        )

        // The server lists a relative href.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair("/123/calendars/home/event1.ics", "same-etag")
            ))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // They match: no add, update or delete.
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(0, result.eventsUpdated)
        assertEquals(0, result.eventsDeleted)
    }

    // ========== Helpers ==========

    private fun createCalendar(
        id: Long = 1,
        ctag: String? = null,
        syncToken: String? = null,
        caldavUrl: String = "https://caldav.example.com/calendars/home/"
    ) = Calendar(
        id = id,
        accountId = 1,
        caldavUrl = caldavUrl,
        displayName = "Test Calendar",
        color = 0xFF0000,
        ctag = ctag,
        syncToken = syncToken
    )

    private fun createEvent(
        id: Long = 1,
        caldavUrl: String? = null,
        title: String = "Test Event"
    ) = Event(
        id = id,
        uid = "test-uid-$id",
        calendarId = 1,
        title = title,
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        dtstamp = System.currentTimeMillis(),
        caldavUrl = caldavUrl,
        syncStatus = SyncStatus.SYNCED
    )

    private fun createSimpleIcal(uid: String, summary: String): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:$summary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}
