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
 * Tests for PullStrategy etag-based fallback sync (v16.9.0).
 *
 * When sync-token expires (403/410), instead of pulling all events (~834KB),
 * etag fallback fetches only etags (~33KB), compares with local, and multigets
 * only changed events. Saves ~96% bandwidth.
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

        // Mock database.runInTransaction to execute the block directly
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Default: UID lookup returns null, so tests fall back to caldavUrl lookup
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        // Default: sync status returns SYNCED (matching createEvent default).
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED

        // Default: "All" lookback routes to existing getEtagsByCalendarId() (unfiltered).
        // Tests that verify time-filtered behavior override this with a specific day count.
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Default: PROPFIND Depth:1 not supported — forces fallback to calendar-query.
        // All tests here use non-null syncToken so this is bypassed, but added for future-proofing.
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

    // ========== Etag Fallback Trigger Tests ==========

    @Test
    fun `etag fallback triggered on 403 sync token expired`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // ctag changed (triggers sync)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))

        // sync-collection returns 403 (token expired)
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local has events with etags
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // Server returns same event with same etag (no changes)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair(eventHref, "etag-1")
            ))

        // Get sync token for result
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Verify etag fallback was used (fetchEtagsInRange called)
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
        // Verify pullFull was NOT reached (no multiget for full sync)
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

    // ========== Fallthrough to pullFull Tests ==========

    @Test
    fun `falls through to pullFull when no local events`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // No local events (first sync or empty calendar)
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns emptyList()

        // pullFull will be called
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Verify etag fallback tried but fell through
        coVerify { eventsDao.getEtagsByCalendarId(calendar.id) }
        // Verify pullFull was called
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

        // Local has events
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // Server etag fetch fails first (etag comparison), then succeeds (pullFull)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returnsMany listOf(
            CalDavResult.error(500, "Server error"),
            CalDavResult.success(emptyList())
        )

        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Verify fetchEtagsInRange was called twice (once for etag comparison, once for pullFull)
        coVerify(exactly = 2) { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    // ========== Change Detection Tests ==========

    @Test
    fun `fetches events with different etags (changed)`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local event with old etag
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "old-etag")
        )

        // Server has new etag (event changed)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "new-etag")))

        // Multiget for changed event
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
        // Verify only changed event was fetched
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { it.size == 1 }) }
    }

    @Test
    fun `fetches new events not present locally`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        // Use full URLs with calendar path for consistency
        val existingHref = "/calendars/home/existing.ics"
        val newHref = "/calendars/home/new.ics"
        val existingUrl = "https://caldav.example.com$existingHref"
        val newUrl = "https://caldav.example.com$newHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local has one event
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(existingUrl, "etag-1")
        )

        // Server has two events (one new)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair(existingHref, "etag-1"),  // Unchanged
                Pair(newHref, "etag-new")      // New
            ))

        // Multiget for new event only
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
        // Verify only new event was fetched (not the unchanged one)
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { it.size == 1 && newHref in it }) }
    }

    @Test
    fun `skips events with matching etags`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        // Use full href path that matches the local URL structure
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local event with same etag as server
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "same-etag")
        )

        // Server has same etag (no change) - href matches local URL structure
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "same-etag")))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(0, result.eventsUpdated)
        // No multiget should be called - no changes
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    // ========== Deletion Tests ==========

    @Test
    fun `deletes events not on server`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val deletedHref = "/calendars/home/deleted.ics"
        val deletedUrl = "https://caldav.example.com$deletedHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local has event that's not on server anymore
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(deletedUrl, "etag-deleted")
        )

        // Server returns empty (event deleted)
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

        // Local has event with pending update
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(pendingUrl, "etag-pending")
        )

        // Server doesn't have this event (deleted on server)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        // Event has pending local changes - should NOT be deleted
        val pendingEvent = createEvent(id = 100L, caldavUrl = pendingUrl)
            .copy(syncStatus = SyncStatus.PENDING_UPDATE)
        coEvery { eventsDao.getByCaldavUrl(pendingUrl) } returns pendingEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        // Should NOT have deleted the pending event
        coVerify(exactly = 0) { eventsDao.deleteById(100L) }
    }

    @Test
    fun `etag fallback does not false-delete when server encodes at-sign differently`() = runTest {
        // Regression for issue #333 (etag-fallback path): the local row stores a
        // literal-'@' url; the server (Radicale) reports the SAME resource with the
        // '@' percent-encoded as %40 and an unchanged etag. Before the fix the
        // set-difference classified the still-present event as BOTH deleted and new
        // (a destructive delete + churny re-fetch). It must be recognized as unchanged.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val storedUrl = "https://caldav.example.com/calendars/home/uuid@kashcal.onekash.org.ics"
        val serverHref = "/calendars/home/uuid%40kashcal.onekash.org.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local stored the literal-'@' url.
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(storedUrl, "etag-1")
        )
        // Server reports the same resource with %40 and the SAME etag.
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(serverHref, "etag-1")))

        val storedEvent = createEvent(id = 55L, caldavUrl = storedUrl)
        // Exact-match DB semantics for the deletion-fallback path.
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByCaldavUrl(storedUrl) } returns storedEvent
        coEvery { eventsDao.getEventsWithCaldavUrl(calendar.id) } returns listOf(storedEvent)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Not deleted...
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(55L) }
        // ...and not re-fetched as new/changed (no multiget for the %40 href).
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `etag fallback still fetches a genuinely changed at-sign event`() = runTest {
        // Guards against over-canonicalizing: a real content change to an '@'-in-name
        // event (etag differs) must still be fetched, using the server's exact href.
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
        // Same resource, DIFFERENT etag -> changed.
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
        // Fetched via multiget using the server's exact (encoded) href, not a rewritten one.
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { hrefs -> hrefs.any { it.contains("%40") } }) }
    }

    @Test
    fun `etag fallback uses unfiltered query when sync lookback is All`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // Configure "All" lookback (Int.MAX_VALUE)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local has event
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // Server returns same event (no changes)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "etag-1")))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // "All" should use unfiltered getEtagsByCalendarId, NOT getEtagsByCalendarIdInRange
        coVerify { eventsDao.getEtagsByCalendarId(calendar.id) }
        coVerify(exactly = 0) { eventsDao.getEtagsByCalendarIdInRange(any(), any(), any()) }
    }

    @Test
    fun `etag fallback does not delete events outside sync window`() = runTest {
        // Issue #87 Bug 2: When sync lookback is bounded (e.g., 180 days), local events
        // outside that window should NOT be deleted just because the server's time-range
        // REPORT didn't return them. The time-filtered DAO query excludes them from comparison.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val recentHref = "/calendars/home/recent.ics"
        val recentUrl = "https://caldav.example.com$recentHref"
        val oldUrl = "https://caldav.example.com/calendars/home/old.ics"

        // Configure 180-day lookback
        every { dataStore.syncPastDays } returns flowOf(180)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Time-filtered local etags: only the recent event (old one excluded by DAO)
        coEvery { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(
            EtagEntry(recentUrl, "etag-recent")
        )

        // Server returns empty (recent event deleted on server)
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        val recentEvent = createEvent(id = 10L, caldavUrl = recentUrl)
        coEvery { eventsDao.getByCaldavUrl(recentUrl) } returns recentEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Recent event deleted (it's in the window and not on server)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(10L) }
        // Old event was never considered for deletion (excluded by time-filtered DAO query)
        // Verify time-filtered query was used, NOT unfiltered
        coVerify { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.getEtagsByCalendarId(any()) }
    }

    @Test
    fun `etag fallback preserves recurring events outside time window`() = runTest {
        // Recurring events' start_ts/end_ts represent only the first occurrence.
        // The DAO query includes them via "rrule IS NOT NULL" bypass.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val recurringHref = "/calendars/home/weekly.ics"
        val recurringUrl = "https://caldav.example.com$recurringHref"

        // Configure 180-day lookback
        every { dataStore.syncPastDays } returns flowOf(180)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Time-filtered DAO returns recurring event (rrule IS NOT NULL bypass includes it
        // even though first occurrence is outside window)
        coEvery { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(
            EtagEntry(recurringUrl, "etag-recurring")
        )

        // Server's time-range filter expands recurrences and returns the event
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(recurringHref, "etag-recurring")))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Recurring event NOT deleted (both local and server have it)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(any()) }
    }

    @Test
    fun `etag fallback uses configurable sync lookback from preferences`() = runTest {
        // Verify that fetchEtagsInRange is called with start time ~180 days ago
        // when dataStore.syncPastDays = 180
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // Configure 180-day lookback
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

        // Verify fetchEtagsInRange was called with start time ~180 days ago
        val expectedPastMs = 180L * 24 * 60 * 60 * 1000
        coVerify {
            client.fetchEtagsInRange(
                calendar.caldavUrl,
                match { startMs ->
                    val now = System.currentTimeMillis()
                    val expected = now - expectedPastMs
                    // Allow 5-second tolerance for test execution time
                    kotlin.math.abs(startMs - expected) < 5000
                },
                any()
            )
        }
        // Should use time-filtered DAO query, not unfiltered
        coVerify { eventsDao.getEtagsByCalendarIdInRange(calendar.id, any(), any()) }
        coVerify(exactly = 0) { eventsDao.getEtagsByCalendarId(any()) }
    }

    // ========== Recently Pushed Event Deletion Protection (v23.2.1) ==========
    // RFC 4791 does not guarantee immediate visibility after PUT.

    @Test
    fun `etag fallback does not delete recently pushed event`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val pushedHref = "/calendars/home/pushed.ics"
        val pushedUrl = "https://caldav.example.com$pushedHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local has event that was just pushed
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(pushedUrl, "etag-from-put")
        )

        // Server doesn't have it yet (not indexed)
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

        // Server doesn't have this event
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())

        val staleEvent = createEvent(id = 99L, caldavUrl = staleUrl)
        coEvery { eventsDao.getByCaldavUrl(staleUrl) } returns staleEvent
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(
            calendar,
            client = client,
            recentlyPushedEventIds = setOf(42L)  // Different ID
        )

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 1) { eventsDao.deleteById(99L) }
    }

    // ========== URL Normalization Tests ==========

    @Test
    fun `handles URL normalization for hostname changes`() = runTest {
        // After iCloud URL migration, all URLs are canonical (caldav.icloud.com)
        // This test verifies etag comparison works with canonical URLs
        val calendar = createCalendar(
            ctag = "old-ctag",
            syncToken = "expired-token",
            caldavUrl = "https://caldav.icloud.com/123/calendars/home/"
        )
        // Local event stored with canonical URL (after migration)
        val localUrl = "https://caldav.icloud.com/123/calendars/home/event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(localUrl, "same-etag")
        )

        // Server returns href (relative path) that will be normalized to canonical form
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair("/123/calendars/home/event1.ics", "same-etag")
            ))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Should match - both local and server URLs are canonical
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(0, result.eventsUpdated)
        assertEquals(0, result.eventsDeleted)
    }

    // ========== Helper Methods ==========

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
