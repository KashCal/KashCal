package org.onekash.kashcal.sync.strategy

import io.mockk.mockk
import io.mockk.MockKAnnotations
import io.mockk.Ordering
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.onekash.kashcal.sync.client.model.SyncItem
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.client.model.SyncReport
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType

/**
 * Tests for PullStrategy - CalDAV server to local database sync.
 */
class PullStrategyTest {

    companion object {
        // Fixed event timestamp for createEvent(). Using a constant (rather than
        // System.currentTimeMillis() evaluated three separate times) keeps the
        // helper deterministic: two createEvent() calls produce byte-identical
        // start/end/dtstamp, so content-equality tests can't flake on a clock
        // tick between the calls. 2025-06-01T12:00:00Z, well inside any sync
        // window. Tests that care about a specific time still .copy() their own.
        private const val FIXED_START_TS = 1_748_779_200_000L
    }

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
        // Race condition tests override this to simulate concurrent edits.
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED

        // Default: "All" lookback routes to existing getEtagsByCalendarId() (unfiltered)
        // in pullWithEtagComparison(). Only relevant for token-expiry fallback path.
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Default: PROPFIND Depth:1 not supported — forces fallback to calendar-query.
        // This preserves existing test behavior. Tests for PROPFIND path override this.
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

    // ========== No Changes Detection ==========

    @Test
    fun `pull returns NoChanges when ctag is unchanged`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-123", displayName = null, color = null, isReadOnly = null))

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.NoChanges)
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
        coVerify(exactly = 0) { client.fetchEtagsInRange(any(), any(), any()) }
    }

    @Test
    fun `pull proceeds when ctag is different`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `pull proceeds when local ctag is null (first sync)`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
    }

    // ========== Incremental Sync Tests ==========

    @Test
    fun `pull uses incremental sync when syncToken exists`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = emptyList()
            ))

        pullStrategy.pull(calendar, client = client)

        coVerify { client.syncCollection(calendar.caldavUrl, "sync-token-123") }
        coVerify(exactly = 0) { client.fetchEtagsInRange(any(), any(), any()) }
    }

    @Test
    fun `pull falls back to full sync when sync token expired`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(410, "Sync token expired")
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        coVerify { client.fetchEtagsInRange(any(), any(), any()) }
    }

    @Test
    fun `incremental sync handles deletions`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val deletedHref = "/calendars/home/deleted-event.ics"
        val deletedUrl = "https://caldav.example.com$deletedHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = listOf(deletedHref)
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns createEvent(caldavUrl = deletedUrl)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(any()) }
    }

    @Test
    fun `incremental sync deletes event when server re-encodes at-sign in deletion href`() = runTest {
        // Regression for issue #333: KashCal stores caldav_url with a literal '@'
        // (its UID contains "@kashcal.onekash.org"). Radicale echoes the deleted href
        // with the '@' percent-encoded as %40. Exact string match then fails and the
        // deletion is silently skipped. The stub below mimics real exact-match DB
        // behavior: it returns the row ONLY for the stored literal-'@' url, null for
        // the %40 form the server reports.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val storedUrl = "https://caldav.example.com/calendars/home/uuid@kashcal.onekash.org.ics"
        val deletedHref = "/calendars/home/uuid%40kashcal.onekash.org.ics"
        val storedEvent = createEvent(id = 42, caldavUrl = storedUrl)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = listOf(deletedHref)
            ))
        // Real DB semantics: exact match only. %40 url -> no row; literal '@' url -> the row.
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByCaldavUrl(storedUrl) } returns storedEvent
        // Normalized fallback candidate set for the calendar.
        coEvery { eventsDao.getEventsWithCaldavUrl(calendar.id) } returns listOf(storedEvent)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(42) }
    }

    @Test
    fun `incremental sync counts a duplicated deletion href only once`() = runTest {
        // A server may report the same deleted href more than once (iCloud does this
        // for changed hrefs). The per-loop resolver caches a candidate map, so a
        // repeated href must not resolve the just-deleted row again and double-count
        // the deletion / notification. deleteById(id) on a missing row is a no-op.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val storedUrl = "https://caldav.example.com/calendars/home/uuid@kashcal.onekash.org.ics"
        val deletedHref = "/calendars/home/uuid%40kashcal.onekash.org.ics"
        val storedEvent = createEvent(id = 42, caldavUrl = storedUrl)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = listOf(deletedHref, deletedHref)  // same href twice
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getByCaldavUrl(storedUrl) } returns storedEvent
        coEvery { eventsDao.getEventsWithCaldavUrl(calendar.id) } returns listOf(storedEvent)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        assertEquals(1, result.changes.count { it.type == ChangeType.DELETED })
        coVerify(exactly = 1) { eventsDao.deleteById(42) }
    }

    @Test
    fun `incremental sync fetches changed events by href`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val changedHref = "/calendars/home/event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = listOf(SyncItem(changedHref, "etag-1", SyncItemStatus.OK)),
                deleted = emptyList()
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(changedHref)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = changedHref,
                    url = "${calendar.caldavUrl}event.ics",
                    etag = "etag-1",
                    icalData = createSimpleIcal("uid-1", "Test Event")
                )
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, listOf(changedHref)) }
    }

    @Test
    fun `incremental sync dedupes duplicate hrefs from sync-collection`() = runTest {
        // Regression test: iCloud can return duplicate hrefs in sync-collection response
        // Without deduplication, hrefsReported != eventsFetched even when all events are fetched,
        // causing confusing "Missing: N" in Sync History when nothing is actually missing
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val href1 = "/calendars/home/event1.ics"
        val href2 = "/calendars/home/event2.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        // sync-collection returns duplicate href1
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = listOf(
                    SyncItem(href1, "etag-1", SyncItemStatus.OK),
                    SyncItem(href2, "etag-2", SyncItemStatus.OK),
                    SyncItem(href1, "etag-1", SyncItemStatus.OK)  // Duplicate!
                ),
                deleted = emptyList()
            ))

        // Server returns unique events (deduped request should only have 2 unique hrefs)
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = href1,
                    url = "${calendar.caldavUrl}event1.ics",
                    etag = "etag-1",
                    icalData = createSimpleIcal("uid-1", "Event 1")
                ),
                CalDavEvent(
                    href = href2,
                    url = "${calendar.caldavUrl}event2.ics",
                    etag = "etag-2",
                    icalData = createSimpleIcal("uid-2", "Event 2")
                )
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        // Verify: Both events added successfully
        assertTrue(result is PullResult.Success)
        val success = result as PullResult.Success
        assertEquals(2, success.eventsAdded)

        // Verify: Token should advance (no actual missing events)
        assertEquals("sync-token-456", success.newSyncToken)

        // Verify: fetchEventsByHref should be called with deduped list (2 unique hrefs, not 3)
        coVerify { client.fetchEventsByHref(calendar.caldavUrl, match { it.size == 2 }) }
    }

    // ========== Full Sync Tests ==========

    @Test
    fun `full sync fetches events in time range`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-sync-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        pullStrategy.pull(calendar, client = client)

        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `full sync deletes local events not on server`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val orphanEvent = createEvent(
            caldavUrl = "https://caldav.example.com/calendars/home/orphan.ics"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(orphanEvent)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(orphanEvent.id) }
    }

    @Test
    fun `full sync does not delete event when server encodes at-sign differently`() = runTest {
        // Regression for issue #333 (full-sync stale-deletion path): the local row
        // stores a literal-'@' url; the server (Radicale) reports the SAME resource
        // with the '@' percent-encoded as %40. Before the fix, the raw '!in serverUrls'
        // membership test was true, so the still-present event was deleted (data loss).
        val calendar = createCalendar(ctag = null, syncToken = null)
        val storedUrl = "https://caldav.example.com/calendars/home/uuid@kashcal.onekash.org.ics"
        val serverHref = "/calendars/home/uuid%40kashcal.onekash.org.ics"
        val storedEvent = createEvent(id = 77, caldavUrl = storedUrl)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Server reports the same resource (encoded), same etag as local -> no re-fetch.
        val serverEvent = CalDavEvent(
            href = serverHref,
            url = "https://caldav.example.com$serverHref",
            etag = "etag-1",
            icalData = createSimpleIcal("uuid@kashcal.onekash.org", "Kept Event")
        )
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(serverEvent.href, serverEvent.etag)))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(serverEvent))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns
            listOf(storedEvent.copy(etag = "etag-1"))

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(77) }
    }

    @Test
    fun `full sync adds new events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}new-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "new-event.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = createSimpleIcal("uid-new", "New Event")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        coVerify { eventsDao.upsert(any()) }
    }

    @Test
    fun `full sync updates existing events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}existing-event.ics"
        val existingEvent = createEvent(
            id = 100L,
            caldavUrl = eventUrl,
            title = "Old Title"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "existing-event.ics",
                url = eventUrl,
                etag = "etag-2",
                icalData = createSimpleIcal("uid-existing", "Updated Title")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(1, result.eventsUpdated)
    }

    // ========== Two-Step Fetch Tests (pullFull refactor) ==========

    @Test
    fun `pullFull uses two-step fetch - etags then multiget`() = runTest {
        // Verifies the core two-step flow: fetchEtagsInRange → fetchEventsByHref
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Step 1: etags
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event.ics", "etag-1")))
        // Step 2: multiget
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event.ics", eventUrl, "etag-1",
                    createSimpleIcal("uid-1", "Test Event"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        // Verify two-step: etags fetched first, then multiget
        coVerify(ordering = Ordering.ORDERED) {
            client.fetchEtagsInRange(calendar.caldavUrl, any(), any())
            client.fetchEventsByHref(calendar.caldavUrl, any())
        }
    }

    @Test
    fun `pullFull skips multiget when server has no events`() = runTest {
        // Empty etag list → skip multiget entirely
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        // Multiget should NOT be called when there are no hrefs
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `pullFull returns error when etag fetch fails`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.error(503, "Service Unavailable", true)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(503, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        // Multiget should NOT be attempted after etag error
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `pullFull deletion detection converts hrefs to full URLs`() = runTest {
        // Verifies that deletion detection uses quirks.buildEventUrl() to convert
        // hrefs from etag response to full URLs matching event.caldavUrl in the DB.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventHref = "/calendars/home/event.ics"
        val eventUrl = "https://caldav.example.com$eventHref"
        val orphanEvent = createEvent(
            id = 99L,
            caldavUrl = "https://caldav.example.com/calendars/home/orphan.ics"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Server has one event (href format) — orphan event is NOT on server
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "etag-1")))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(eventHref, eventUrl, "etag-1",
                    createSimpleIcal("uid-1", "Kept Event"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(orphanEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Orphan should be deleted (its URL wasn't in server etag list)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(orphanEvent.id) }
    }

    @Test
    fun `pullFull tracks session metrics for two-step fetch`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair("event.ics", "etag-1"),
                Pair("event2.ics", "etag-2")
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event.ics", eventUrl, "etag-1",
                    createSimpleIcal("uid-1", "Event 1"))
                // event2 "failed" to fetch — only 1 of 2 returned
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        val session = sessionBuilder.build()
        // hrefsReported = etag count (2), eventsFetched = multiget result (1)
        assertEquals("Should report 2 hrefs from etags", 2, session.hrefsReported)
        assertEquals("Should report 1 event fetched", 1, session.eventsFetched)
    }

    @Test
    fun `pullFull multiget batch error falls back to individual fetches`() = runTest {
        // A3: When batched multiget fails, fall back to individual fetches per href.
        // If individual fetches also fail, events are skipped (not the entire sync).
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event.ics", "etag-1")))
        // All fetches fail (batch and individual)
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.error(500, "Server error", isRetryable = true)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        // A3: Sync completes (with 0 events) instead of returning Error
        assertTrue("Expected Success but got $result", result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        // Called twice: batch multiget (fails) + individual fallback (also fails)
        coVerify(exactly = 2) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    // ========== Force Full Sync Tests ==========

    @Test
    fun `forceFullSync ignores sync token`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-123", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        assertTrue(result is PullResult.Success)
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
        coVerify { client.fetchEtagsInRange(any(), any(), any()) }
    }

    @Test
    fun `forceFullSync ignores matching ctag`() = runTest {
        val calendar = createCalendar(ctag = "same-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "same-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        // Should NOT return NoChanges
        assertTrue(result is PullResult.Success)
    }

    @Test
    fun `forceFullSync skips deletion of local events not on server`() = runTest {
        // Issue #87 Bug 1: Force full sync should NOT delete local events missing from
        // server response. The server's time-range REPORT may not return all events
        // (server truncation, RRULE expansion bugs, URL mismatches).
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        val localEvent = createEvent(
            id = 42L,
            caldavUrl = "https://caldav.example.com/calendars/home/existing.ics"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        // Server returns empty — event not in response (but still exists on server)
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(localEvent)

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        // Event should NOT have been deleted
        coVerify(exactly = 0) { eventsDao.deleteById(42L) }
    }

    @Test
    fun `token expiry fallback to pullFull still deletes stale events`() = runTest {
        // pullIncremental() line 253 calls pullFull() as a fallback when sync token expires
        // AND etag comparison returns null. This path should NOT skip deletion (forceFullSync
        // defaults to false) — only user-initiated force full sync skips deletion.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val orphanEvent = createEvent(
            id = 99L,
            caldavUrl = "https://caldav.example.com/calendars/home/orphan.ics"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        // Sync token expired → 403
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")
        // Etag fallback: no local etags → returns null → falls through to pullFull
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns emptyList()
        // pullFull: server returns empty
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(orphanEvent)

        val result = pullStrategy.pull(calendar, forceFullSync = false, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        // Event SHOULD be deleted in the normal fallback path
        coVerify { eventsDao.deleteById(99L) }
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `pull returns error when ctag fetch fails`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.error(401, "Unauthorized", false)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(401, (result as PullResult.Error).code)
        assertFalse(result.isRetryable)
    }

    @Test
    fun `pull returns error when fetch events fails`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.error(500, "Server error", true)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(500, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `pull returns error with network error`() = runTest {
        // Network error on ctag falls through (not auth/permission), then sync also fails
        val calendar = createCalendar(syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.networkError("Connection timeout")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.networkError("Connection timeout")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(0, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `pull exception with null message uses class name not Unknown error`() = runTest {
        val calendar = createCalendar(syncToken = null)
        // NullPointerException() has null message
        coEvery { client.getCtag(calendar.caldavUrl) } throws NullPointerException()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        val error = result as PullResult.Error
        assertEquals("NullPointerException", error.message)
    }

    // ========== Recurring Events Tests ==========

    @Test
    fun `pull generates occurrences for recurring events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}recurring.ics"
        val recurringIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "recurring.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = recurringIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        pullStrategy.pull(calendar, client = client)

        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
    }

    @Test
    fun `pull regenerates occurrences for non-recurring events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}single.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "single.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = createSimpleIcal("single-uid", "Single Event")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        pullStrategy.pull(calendar, client = client)

        coVerify { occurrenceGenerator.regenerateOccurrences(any()) }
    }

    // ========== Exception Events Tests ==========

    @Test
    fun `pull links exception events to master events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}master-with-exception.ics"
        val masterWithExceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:20240108T100000Z
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Modified Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "master-with-exception.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = masterWithExceptionIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getByUid("master-uid") } returns emptyList()
        coEvery { eventsDao.getExceptionByUidAndInstanceTime(any(), any(), any()) } returns null
        coEvery { eventsDao.upsert(any()) } returnsMany listOf(1L, 2L)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Should have added both master and exception
        assertEquals(2, (result as PullResult.Success).eventsAdded)
        // Exception events use linkException to normalize to Model B (prevents duplicates)
        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
        coVerify { occurrenceGenerator.linkException(any<Long>(), any<Long>(), any<Event>()) }
    }

    // ========== Metadata Update Tests ==========

    @Test
    fun `pull updates sync token after success`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns
            CalDavResult.success(SyncReport(
                syncToken = "new-token",
                changed = emptyList(),
                deleted = emptyList()
            ))

        pullStrategy.pull(calendar, client = client)

        coVerify {
            calendarRepository.updateSyncToken(
                calendarId = calendar.id,
                syncToken = "new-token",
                ctag = "new-ctag"
            )
        }
    }

    @Test
    fun `pull does not update metadata on error`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(any(), any(), any()) } returns
            CalDavResult.error(500, "Server error")

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateSyncToken(any(), any(), any()) }
    }

    // ========== LOCAL-FIRST: Pending Changes Protection Tests ==========

    @Test
    fun `pull does not overwrite event with PENDING_CREATE status`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}pending-event.ics"
        val existingEvent = createEvent(
            id = 100L,
            caldavUrl = eventUrl,
            title = "Local New Event"
        ).copy(syncStatus = SyncStatus.PENDING_CREATE)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "pending-event.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = createSimpleIcal("uid-pending", "Server Version")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Should NOT have updated because event has pending local changes
        assertEquals(0, (result as PullResult.Success).eventsUpdated)
        assertEquals(0, result.eventsAdded)
        // eventsDao.upsert should NOT have been called for this event
        coVerify(exactly = 0) { eventsDao.upsert(match { it.caldavUrl == eventUrl }) }
    }

    @Test
    fun `pull does not overwrite event with PENDING_UPDATE status`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}modified-event.ics"
        val existingEvent = createEvent(
            id = 200L,
            caldavUrl = eventUrl,
            title = "Local Modified Title"
        ).copy(syncStatus = SyncStatus.PENDING_UPDATE)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "modified-event.ics",
                url = eventUrl,
                etag = "etag-2",
                icalData = createSimpleIcal("uid-modified", "Server Title")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Event with PENDING_UPDATE should be skipped
        assertEquals(0, (result as PullResult.Success).eventsUpdated)
        coVerify(exactly = 0) { eventsDao.upsert(match { it.caldavUrl == eventUrl }) }
    }

    @Test
    fun `full sync does not delete event with PENDING_DELETE status`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val pendingDeleteEvent = createEvent(
            id = 300L,
            caldavUrl = "https://caldav.example.com/calendars/home/to-delete.ics",
            title = "Pending Delete Event"
        ).copy(syncStatus = SyncStatus.PENDING_DELETE)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList()) // Server doesn't have this event
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(pendingDeleteEvent)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Should NOT delete events with pending local changes
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(pendingDeleteEvent.id) }
    }

    @Test
    fun `incremental sync does not delete event with pending local changes`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val deletedHref = "/calendars/home/pending-local-event.ics"
        val eventUrl = "https://caldav.example.com$deletedHref"
        val pendingEvent = createEvent(
            id = 400L,
            caldavUrl = eventUrl,
            title = "Has Local Changes"
        ).copy(syncStatus = SyncStatus.PENDING_UPDATE)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = listOf(deletedHref) // Server says this was deleted
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns pendingEvent

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Should NOT delete because event has pending local changes
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(pendingEvent.id) }
    }

    @Test
    fun `pull does not overwrite exception event with PENDING_UPDATE status`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}master-with-exception.ics"
        val masterEvent = createEvent(id = 500L, caldavUrl = eventUrl, title = "Master Event")
            .copy(rrule = "FREQ=WEEKLY")
        val existingException = createEvent(
            id = 501L,
            caldavUrl = null, // Exception events may not have caldavUrl
            title = "Local Modified Exception"
        ).copy(
            syncStatus = SyncStatus.PENDING_UPDATE,
            originalEventId = 500L,
            originalInstanceTime = parseDate("2024-01-08 10:00")
        )

        val masterWithExceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:20240108T100000Z
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Server Modified Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "master-with-exception.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = masterWithExceptionIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, any()) } returns existingException
        coEvery { eventsDao.upsert(any()) } returns 500L

        pullStrategy.pull(calendar, client = client)

        // Exception event with PENDING_UPDATE should NOT have been upserted
        // Only the master event should be upserted
        coVerify(exactly = 0) {
            eventsDao.upsert(match {
                it.originalEventId != null && it.syncStatus == SyncStatus.PENDING_UPDATE
            })
        }
    }

    // ========== Etag Comparison Tests (Prevents stale data overwrite after push) ==========

    @Test
    fun `pull skips event when etag unchanged - prevents stale data overwrite`() = runTest {
        // This test verifies the fix for iCloud eventual consistency issue:
        // After push, pull may return stale data with same etag. We skip upsert to prevent overwrite.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"
        val existingEvent = createEvent(
            id = 100L,
            caldavUrl = eventUrl,
            title = "Local Title with Updated Reminder"
        ).copy(
            etag = "etag-123",  // Same etag as server
            reminders = listOf("-PT30M")  // User just changed reminder to 30 mins
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "event.ics",
                url = eventUrl,
                etag = "etag-123",  // Same etag - server may have stale 15 min reminder
                icalData = createSimpleIcal("uid-1", "Title")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Event should be skipped because etag matches - no upsert called
        assertEquals(0, (result as PullResult.Success).eventsUpdated)
        assertEquals(0, result.eventsAdded)
        coVerify(exactly = 0) { eventsDao.upsert(match { it.caldavUrl == eventUrl }) }
    }

    @Test
    fun `pull updates event when etag differs - server has newer data`() = runTest {
        // When etag differs, server has genuinely new data - should update
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"
        val existingEvent = createEvent(
            id = 100L,
            caldavUrl = eventUrl,
            title = "Old Title"
        ).copy(etag = "old-etag")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "event.ics",
                url = eventUrl,
                etag = "new-etag",  // Different etag - server has new data
                icalData = createSimpleIcal("uid-1", "Updated Title")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Event should be updated because etag differs
        assertEquals(1, (result as PullResult.Success).eventsUpdated)
        coVerify { eventsDao.upsert(match { it.caldavUrl == eventUrl }) }
    }

    @Test
    fun `pull adds new event when no existing event found`() = runTest {
        // New event from server (no existing event) should always be added
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}new-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "new-event.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = createSimpleIcal("uid-new", "New Event")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null  // No existing event
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        coVerify { eventsDao.upsert(any()) }
    }

    @Test
    fun `pull skips exception event when etag unchanged`() = runTest {
        // Etag comparison should also work for exception events
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}master-with-exception.ics"
        val masterEvent = createEvent(id = 500L, caldavUrl = eventUrl, title = "Master Event")
            .copy(rrule = "FREQ=WEEKLY", etag = "master-etag")
        val existingException = createEvent(
            id = 501L,
            caldavUrl = eventUrl,
            title = "Local Modified Exception"
        ).copy(
            etag = "exception-etag-123",  // Same etag as server
            originalEventId = 500L,
            originalInstanceTime = parseDate("2024-01-08 10:00")
        )

        val masterWithExceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:20240108T100000Z
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Server Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "master-with-exception.ics",
                url = eventUrl,
                etag = "exception-etag-123",  // Same etag - should skip exception
                icalData = masterWithExceptionIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, any()) } returns existingException
        coEvery { eventsDao.upsert(any()) } returns 500L

        pullStrategy.pull(calendar, client = client)

        // Exception event should be skipped due to etag match
        // Master event update depends on master's etag check
        coVerify(exactly = 0) {
            eventsDao.upsert(match { it.originalEventId == 500L })
        }
    }

    // Helper for date parsing in tests
    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(
            dateParts[0].toInt(),
            dateParts[1].toInt() - 1,
            dateParts[2].toInt(),
            timeParts[0].toInt(),
            timeParts[1].toInt(),
            0
        )
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    // ========== Deduplication Tests ==========

    @Test
    fun `pullFull calls deleteDuplicateMasterEvents at start`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        pullStrategy.pull(calendar, client = client)

        // Should call dedup at start of pullFull
        coVerify { eventsDao.deleteDuplicateMasterEvents() }
    }

    @Test
    fun `pullFull logs when duplicates are cleaned up`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 3 // Found 3 duplicates

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        coVerify { eventsDao.deleteDuplicateMasterEvents() }
    }

    @Test
    fun `pullIncremental calls deleteDuplicateMasterEvents after processing`() = runTest {
        // C2 fix: Incremental sync should also clean up duplicates from hostname changes
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = listOf(SyncItem("/event.ics", "etag-1", SyncItemStatus.OK)),
                deleted = emptyList()
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event.ics", "${calendar.caldavUrl}event.ics", "etag-1",
                    createSimpleIcal("uid-1", "Test Event"))
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 2 // Found 2 duplicates

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Verify dedup was called during incremental sync
        coVerify { eventsDao.deleteDuplicateMasterEvents() }
    }

    @Test
    fun `pullIncremental logs when duplicates are cleaned during incremental sync`() = runTest {
        // C2 fix: Verify logging for incremental sync dedup
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-123") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = emptyList()
            ))
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0 // No duplicates

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Dedup should still be called even when no events changed
        // (handles accumulated duplicates from past syncs)
        coVerify { eventsDao.deleteDuplicateMasterEvents() }
    }

    @Test
    fun `pull uses UID lookup as primary dedup method`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"
        val existingEvent = createEvent(
            id = 100L,
            caldavUrl = "https://different-server.example.com/event.ics", // Different URL
            title = "Existing Event"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "event.ics",
                url = eventUrl,
                etag = "new-etag",
                icalData = createSimpleIcal("existing-uid", "Updated Event")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        // UID lookup finds the event (primary lookup)
        coEvery { eventsDao.getMasterByUidAndCalendar("existing-uid", calendar.id) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Should have updated (not added) because UID lookup found the event
        assertEquals(1, (result as PullResult.Success).eventsUpdated)
        assertEquals(0, result.eventsAdded)
        // UID lookup should have been called
        coVerify { eventsDao.getMasterByUidAndCalendar("existing-uid", calendar.id) }
    }

    @Test
    fun `pull falls back to caldavUrl lookup when UID not found`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"
        val existingEvent = createEvent(
            id = 100L,
            caldavUrl = eventUrl,
            title = "Existing Event"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "event.ics",
                url = eventUrl,
                etag = "new-etag",
                icalData = createSimpleIcal("some-uid", "Updated Event")
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        // UID lookup returns null (not found)
        coEvery { eventsDao.getMasterByUidAndCalendar("some-uid", calendar.id) } returns null
        // Fallback to caldavUrl lookup finds the event
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsUpdated)
        // Should have tried UID lookup first, then caldavUrl
        coVerify { eventsDao.getMasterByUidAndCalendar("some-uid", calendar.id) }
        coVerify { eventsDao.getByCaldavUrl(eventUrl) }
    }

    // ========== Statistics Tests ==========

    @Test
    fun `pull result contains correct statistics`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("e1.ics", "${calendar.caldavUrl}e1.ics", "etag1", createSimpleIcal("uid1", "Event 1")),
            CalDavEvent("e2.ics", "${calendar.caldavUrl}e2.ics", "etag2", createSimpleIcal("uid2", "Event 2")),
            CalDavEvent("e3.ics", "${calendar.caldavUrl}e3.ics", "etag3", createSimpleIcal("uid3", "Event 3"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns
            listOf(createEvent(id = 100, caldavUrl = "orphan-url"))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        val success = result as PullResult.Success
        assertEquals(3, success.eventsAdded)
        assertEquals(0, success.eventsUpdated)
        assertEquals(1, success.eventsDeleted)
        assertEquals(4, success.totalChanges)
    }

    // ========== Default Reminder Tests (Issue #74) ==========

    @Test
    fun `pull does not apply default reminders to events without alarms`() = runTest {
        // Server event has NO VALARM — reminders should stay null
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}no-alarm.ics"
        val ical = createSimpleIcal("uid-no-alarm", "No Alarm Event")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent("no-alarm.ics", eventUrl, "etag-1", ical)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertNull("Event without VALARM should have null reminders", capturedEvent.captured.reminders)
    }

    @Test
    fun `pull preserves server-provided reminders`() = runTest {
        // Server event has VALARM with -PT30M — should be preserved
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}with-alarm.ics"
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:uid-with-alarm
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Event With Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:-PT30M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent("with-alarm.ics", eventUrl, "etag-1", ical)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertNotNull("Event with VALARM should have reminders", capturedEvent.captured.reminders)
        assertEquals(listOf("-PT30M"), capturedEvent.captured.reminders)
    }

    @Test
    fun `pull does not apply default reminders to all-day events without alarms`() = runTest {
        // All-day event with NO VALARM — reminders should stay null
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}allday-no-alarm.ics"
        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:uid-allday-no-alarm
            DTSTAMP:20240101T120000Z
            DTSTART;VALUE=DATE:20240115
            DTEND;VALUE=DATE:20240116
            SUMMARY:All Day No Alarm
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent("allday-no-alarm.ics", eventUrl, "etag-1", ical)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertTrue("All-day event should be marked as all-day", capturedEvent.captured.isAllDay)
        assertNull("All-day event without VALARM should have null reminders", capturedEvent.captured.reminders)
    }

    // ========== Real iCloud Data Tests ==========

    @Test
    fun `pull parses real iCloud recurring event`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}ac-maintenance.ics"
        // Real iCloud event pattern
        val icloudIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:America/Chicago
            BEGIN:DAYLIGHT
            TZOFFSETFROM:-0600
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            DTSTART:20070311T020000
            TZNAME:CDT
            TZOFFSETTO:-0500
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:-0500
            RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
            DTSTART:20071104T020000
            TZNAME:CST
            TZOFFSETTO:-0600
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:37396123-32E0-43AC-A4C1-C1619A031BDB
            DTSTAMP:20240101T120000Z
            DTSTART;TZID=America/Chicago:20240707T100000
            DTEND;TZID=America/Chicago:20240707T103000
            SUMMARY:AC maintenance vinegar thru pipe
            RRULE:FREQ=WEEKLY;INTERVAL=16;BYDAY=SU
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "ac-maintenance.ics",
                url = eventUrl,
                etag = "etag-1",
                icalData = icloudIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)

        // Verify the captured event has correct properties
        assertEquals("AC maintenance vinegar thru pipe", capturedEvent.captured.title)
        assertEquals("FREQ=WEEKLY;INTERVAL=16;BYDAY=SU", capturedEvent.captured.rrule)
        assertEquals("America/Chicago", capturedEvent.captured.timezone)
        assertNotNull(capturedEvent.captured.reminders)
        assertTrue(capturedEvent.captured.reminders!!.isNotEmpty())
    }

    // ========== Error Code Differentiation Tests ==========

    @Test
    fun `pull returns TIMEOUT error code for SocketTimeoutException`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.net.SocketTimeoutException("Read timed out")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(-408, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("Timeout"))
    }

    @Test
    fun `pull returns NETWORK error code for IOException`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.io.IOException("Connection reset")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(0, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("Network"))
    }

    @Test
    fun `pull returns PARSE error code for non-IO Exception`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws IllegalStateException("Unexpected state")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(-1, (result as PullResult.Error).code)
        assertFalse(result.isRetryable)
    }

    @Test
    fun `pull returns TIMEOUT error code for ConnectTimeoutException`() = runTest {
        // ConnectTimeoutException is also a SocketTimeoutException subclass
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.net.SocketTimeoutException("Connect timed out")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(-408, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `pull returns NETWORK error code for UnknownHostException`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.net.UnknownHostException("caldav.icloud.com")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(0, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("Network"))
    }

    // ========== Etag Preservation Tests (v23.2.0) ==========

    @Test
    fun `pull preserves existing etag when server returns null etag`() = runTest {
        // Given: existing event with valid etag, server returns same event with null etag
        // (server omitted <getetag> from REPORT response — CDN inconsistency)
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event-1.ics"
        val existingEvent = createEvent(id = 42L, caldavUrl = eventUrl).copy(
            etag = "valid-etag",
            uid = "uid-1"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event-1.ics", null)))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event-1.ics", eventUrl, null,
                    createSimpleIcal("uid-1", "Test Event"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-1", calendar.id) } returns existingEvent
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns existingEvent.id

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // The key assertion: existing etag must be preserved, not overwritten with null
        assertEquals(
            "Existing etag should be preserved when server returns null",
            "valid-etag", capturedEvent.captured.etag
        )
    }

    @Test
    fun `pull uses server etag when both exist`() = runTest {
        // Given: existing event with old etag, server returns same event with new etag
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event-1.ics"
        val existingEvent = createEvent(id = 42L, caldavUrl = eventUrl).copy(
            etag = "old-etag",
            uid = "uid-1"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event-1.ics", "new-etag")))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event-1.ics", eventUrl, "new-etag",
                    createSimpleIcal("uid-1", "Test Event"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-1", calendar.id) } returns existingEvent
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns existingEvent.id

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // Server etag should win when both exist
        assertEquals(
            "Server etag should overwrite old etag",
            "new-etag", capturedEvent.captured.etag
        )
    }

    @Test
    fun `pull preserves exception event etag when server returns null etag`() = runTest {
        // Given: existing exception event with valid etag, server returns null etag
        // The exception path at line 979 uses the same `meta.etag ?: existingException.etag` pattern
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}master-with-exception.ics"
        val masterEvent = createEvent(id = 500L, caldavUrl = eventUrl, title = "Master Event")
            .copy(rrule = "FREQ=WEEKLY", etag = "master-etag", uid = "master-uid")
        val existingException = createEvent(
            id = 501L,
            caldavUrl = eventUrl,
            title = "Existing Exception"
        ).copy(
            etag = "valid-exception-etag",
            originalEventId = 500L,
            originalInstanceTime = parseDate("2024-01-08 10:00")
        )

        val masterWithExceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:20240108T100000Z
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Server Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "master-with-exception.ics",
                url = eventUrl,
                etag = null,  // Server omitted etag
                icalData = masterWithExceptionIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("master-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, any()) } returns existingException

        val capturedEvents = mutableListOf<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvents)) } returns 500L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)

        // Find the captured exception event (has originalEventId set)
        val capturedExceptionEvent = capturedEvents.find { it.originalEventId == 500L }
        assertNotNull("Exception event should have been upserted", capturedExceptionEvent)
        assertEquals(
            "Exception etag should be preserved when server returns null",
            "valid-exception-etag", capturedExceptionEvent!!.etag
        )

        // Also verify master event etag is preserved
        val capturedMasterEvent = capturedEvents.find { it.originalEventId == null }
        assertNotNull("Master event should have been upserted", capturedMasterEvent)
        assertEquals(
            "Master etag should be preserved when server returns null",
            "master-etag", capturedMasterEvent!!.etag
        )
    }

    @Test
    fun `pull re-fetches event when both etags are null`() = runTest {
        // Null etag means "unknown state" — should always re-fetch, not skip.
        // null == null is true in Kotlin, but null should mean "re-fetch" not "unchanged".
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event-1.ics"
        val existingEvent = createEvent(id = 42L, caldavUrl = eventUrl).copy(
            etag = null,
            uid = "uid-1"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event-1.ics", null)))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event-1.ics", eventUrl, null,
                    createSimpleIcal("uid-1", "Test Event"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-1", calendar.id) } returns existingEvent
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 42L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // Null etag = unknown → event should be upserted (re-fetched), not skipped
        coVerify(atLeast = 1) { eventsDao.upsert(match { it.uid == "uid-1" }) }
    }

    @Test
    fun `pull re-fetches exception event when both etags are null`() = runTest {
        // Same null-etag fix applies to exception events
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}master-with-exception.ics"
        val masterEvent = createEvent(id = 500L, caldavUrl = eventUrl, title = "Master Event")
            .copy(rrule = "FREQ=WEEKLY", etag = "master-etag")
        val existingException = createEvent(
            id = 501L,
            caldavUrl = eventUrl,
            title = "Existing Exception"
        ).copy(
            etag = null,  // Null etag on exception
            originalEventId = 500L,
            originalInstanceTime = parseDate("2024-01-08 10:00")
        )

        val masterWithExceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:20240108T100000Z
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Server Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent(
                href = "master-with-exception.ics",
                url = eventUrl,
                etag = null,  // Server also returns null etag
                icalData = masterWithExceptionIcal
            )
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, any()) } returns existingException
        coEvery { eventsDao.upsert(any()) } returns 500L

        pullStrategy.pull(calendar, client = client)

        // Exception with null etag should be upserted, not skipped
        coVerify(atLeast = 1) {
            eventsDao.upsert(match { it.originalEventId != null })
        }
    }

    // ========== PROPFIND Depth:1 Probe-Based Routing (Issue #102) ==========

    @Test
    fun `pullFull uses calendar-query when forceFullSync is true`() = runTest {
        // forceFullSync bypasses probe — getSyncToken should NOT be called as probe
        val calendar = createCalendar(ctag = null, syncToken = null)
        val events = listOf(
            CalDavEvent("event-1.ics", "${calendar.caldavUrl}event-1.ics", "etag-1",
                createSimpleIcal("uid-1", "Event 1"))
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, events)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client, forceFullSync = true)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // fetchEtagsInRange used (calendar-query), NOT fetchAllEtags (PROPFIND)
        coVerify(exactly = 1) { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
        coVerify(exactly = 0) { client.fetchAllEtags(any()) }
    }

    @Test
    fun `pullFull uses calendar-query when syncToken is not null`() = runTest {
        // Non-null syncToken bypasses probe — goes straight to calendar-query
        val calendar = createCalendar(ctag = null, syncToken = "existing-token")
        val events = listOf(
            CalDavEvent("event-1.ics", "${calendar.caldavUrl}event-1.ics", "etag-1",
                createSimpleIcal("uid-1", "Event 1"))
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        // syncToken non-null → incremental path, but let's force to pullFull via forceFullSync
        // Actually, non-null syncToken goes to pullIncremental, not pullFull.
        // To test non-null syncToken in pullFull, use forceFullSync=true (which overrides).
        // But forceFullSync also bypasses probe. So this test verifies the combined bypass.
        mockTwoStepFetch(calendar.caldavUrl, events)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client, forceFullSync = true)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        coVerify(exactly = 1) { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
        coVerify(exactly = 0) { client.fetchAllEtags(any()) }
    }

    @Test
    fun `pullFull probes getSyncToken and uses calendar-query when server has token`() = runTest {
        // First sync on capable server (iCloud): syncToken=null, probe returns token → calendar-query
        val calendar = createCalendar(ctag = null, syncToken = null)
        val events = listOf(
            CalDavEvent("event-1.ics", "${calendar.caldavUrl}event-1.ics", "etag-1",
                createSimpleIcal("uid-1", "Event 1"))
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, events)
        // Probe returns a token — server supports sync-token
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("probe-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // calendar-query used (fetchEtagsInRange), NOT PROPFIND (fetchAllEtags)
        coVerify(exactly = 1) { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
        coVerify(exactly = 0) { client.fetchAllEtags(any()) }
    }

    @Test
    fun `pullFull probes getSyncToken and uses PROPFIND when server lacks token`() = runTest {
        // Purelymail path: syncToken=null, probe returns null → PROPFIND Depth:1
        val calendar = createCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl

        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Probe returns null — server does NOT support sync-token
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success(null)
        // PROPFIND returns etags
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.success(
            listOf(Pair("event-1.ics", "etag-1"), Pair("event-2.ics", "etag-2"))
        )
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(
            listOf(
                CalDavEvent("event-1.ics", "${calendarUrl}event-1.ics", "etag-1",
                    createSimpleIcal("uid-1", "Event 1")),
                CalDavEvent("event-2.ics", "${calendarUrl}event-2.ics", "etag-2",
                    createSimpleIcal("uid-2", "Event 2"))
            )
        )
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(2, (result as PullResult.Success).eventsAdded)
        // PROPFIND used, NOT calendar-query
        coVerify(exactly = 1) { client.fetchAllEtags(calendarUrl) }
        coVerify(exactly = 0) { client.fetchEtagsInRange(calendarUrl, any(), any()) }
    }

    @Test
    fun `pullFull falls back to calendar-query when PROPFIND fails`() = runTest {
        // PROPFIND fails → fallback to calendar-query, with Log.w warning
        val calendar = createCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl
        val events = listOf(
            CalDavEvent("event-1.ics", "${calendarUrl}event-1.ics", "etag-1",
                createSimpleIcal("uid-1", "Event 1"))
        )

        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Probe returns null → PROPFIND path
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success(null)
        // PROPFIND fails
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.error(501, "PROPFIND not supported")
        // Fallback to calendar-query
        mockTwoStepFetch(calendarUrl, events)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        // Both PROPFIND and calendar-query called (PROPFIND failed, fell back)
        coVerify(exactly = 1) { client.fetchAllEtags(calendarUrl) }
        coVerify(exactly = 1) { client.fetchEtagsInRange(calendarUrl, any(), any()) }
        // Warning should be added to session
        val session = sessionBuilder.build()
        assertTrue("Session should have warnings", session.hasWarnings)
        assertTrue("Warning should mention PROPFIND",
            session.warnings?.any { it.contains("PROPFIND") } == true)
    }

    @Test
    fun `pullFull PROPFIND success detects new and changed events`() = runTest {
        // End-to-end: PROPFIND returns etags, detect new + changed events
        val calendar = createCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl
        val existingEvent = createEvent(id = 42L, caldavUrl = "${calendarUrl}existing.ics").copy(
            etag = "old-etag", uid = "uid-existing"
        )

        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Probe → no token
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success(null)
        // PROPFIND returns 2 events: one with changed etag, one new
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.success(
            listOf(
                Pair("existing.ics", "new-etag"),   // Changed etag
                Pair("brand-new.ics", "etag-new")   // New event
            )
        )
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(
            listOf(
                CalDavEvent("existing.ics", "${calendarUrl}existing.ics", "new-etag",
                    createSimpleIcal("uid-existing", "Updated Event")),
                CalDavEvent("brand-new.ics", "${calendarUrl}brand-new.ics", "etag-new",
                    createSimpleIcal("uid-new", "New Event"))
            )
        )
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl("${calendarUrl}existing.ics") } returns existingEvent
        coEvery { eventsDao.getByCaldavUrl("${calendarUrl}brand-new.ics") } returns null
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-existing", calendar.id) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        val success = result as PullResult.Success
        // 1 new + 1 updated
        assertEquals(1, success.eventsAdded)
        assertEquals(1, success.eventsUpdated)
        // PROPFIND used
        coVerify(exactly = 1) { client.fetchAllEtags(calendarUrl) }
        coVerify(exactly = 0) { client.fetchEtagsInRange(calendarUrl, any(), any()) }
    }

    @Test
    fun `pullFull uses PROPFIND path when getSyncToken probe returns network error`() = runTest {
        // Probe fails with network error → treats as no-token → PROPFIND path
        val calendar = createCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl

        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Probe returns error (network failure)
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.error(0, "Network error")
        // PROPFIND succeeds
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.success(
            listOf(Pair("event-1.ics", "etag-1"))
        )
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(
            listOf(
                CalDavEvent("event-1.ics", "${calendarUrl}event-1.ics", "etag-1",
                    createSimpleIcal("uid-1", "Event 1"))
            )
        )
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // PROPFIND used (probe error → no-token path)
        coVerify(exactly = 1) { client.fetchAllEtags(calendarUrl) }
        coVerify(exactly = 0) { client.fetchEtagsInRange(calendarUrl, any(), any()) }
    }

    // ========== Helper Methods ==========

    /**
     * Mocks the two-step fetch pattern used by pullFull():
     * Step 1: fetchEtagsInRange returns href+etag pairs
     * Step 2: fetchEventsByHref returns full CalDavEvent data
     */
    private fun mockTwoStepFetch(calendarUrl: String, events: List<CalDavEvent>) {
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns
            CalDavResult.success(events)
    }

    private fun createCalendar(
        id: Long = 1,
        ctag: String? = null,
        syncToken: String? = null
    ) = Calendar(
        id = id,
        accountId = 1,
        caldavUrl = "https://caldav.example.com/calendars/home/",
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
        startTs = FIXED_START_TS,
        endTs = FIXED_START_TS + 3600000,
        dtstamp = FIXED_START_TS,
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

    // ========== FK Constraint Error Handling Tests (Issue #55) ==========

    @Test
    fun `FK error on second event still commits first event and continues to third`() = runTest {
        // Verifies: Events processed before the FK error are committed individually.
        // Each event upsert runs in its own transaction, so earlier events survive.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val event1Url = "${calendar.caldavUrl}event1.ics"
        val event2Url = "${calendar.caldavUrl}event2.ics"
        val event3Url = "${calendar.caldavUrl}event3.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("event1.ics", event1Url, "etag1", createSimpleIcal("uid-1", "Event 1")),
            CalDavEvent("event2.ics", event2Url, "etag2", createSimpleIcal("uid-2", "Event 2")),
            CalDavEvent("event3.ics", event3Url, "etag3", createSimpleIcal("uid-3", "Event 3"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null

        // First event succeeds
        coEvery { eventsDao.upsert(match { it.uid == "uid-1" }) } returns 1L
        // Second event throws FK violation
        coEvery { eventsDao.upsert(match { it.uid == "uid-2" }) } throws
            android.database.sqlite.SQLiteConstraintException(
                "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)"
            )
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-2", calendar.id) } returns null
        // Third event would succeed but is never reached
        coEvery { eventsDao.upsert(match { it.uid == "uid-3" }) } returns 3L

        val result = pullStrategy.pull(calendar, client = client)

        // After fix: FK error is skipped, sync continues to third event
        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(2, (result as PullResult.Success).eventsAdded) // Events 1 and 3 succeed
        // All three events were attempted
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-1" }) }
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-2" }) }
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-3" }) }
    }

    @Test
    fun `FK error no longer prevents sync token advancement`() = runTest {
        // After fix: FK error is skipped, sync succeeds, token advances.
        // This breaks the infinite failure loop.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}problem-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("problem-event.ics", eventUrl, "etag-1",
                createSimpleIcal("uid-problem", "Problem Event"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } throws
            android.database.sqlite.SQLiteConstraintException(
                "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)"
            )
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        // First attempt — succeeds (event skipped)
        val result1 = pullStrategy.pull(calendar, client = client)
        assertTrue("First sync should succeed", result1 is PullResult.Success)

        // Sync token WAS updated — loop is broken
        coVerify(atLeast = 1) { calendarRepository.updateSyncToken(any(), any(), any()) }
    }

    // ========== FK Constraint Error Handling Fix Tests (Issue #55 - desired behavior) ==========

    @Test
    fun `FK constraint on master event skips event and continues sync`() = runTest {
        // After fix: FK error on one master event should skip it and continue processing others.
        // Result should be Success (not Error), and sync token should advance.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val event1Url = "${calendar.caldavUrl}event1.ics"
        val event2Url = "${calendar.caldavUrl}event2.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("event1.ics", event1Url, "etag1", createSimpleIcal("uid-1", "Event 1")),
            CalDavEvent("event2.ics", event2Url, "etag2", createSimpleIcal("uid-2", "Event 2"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null

        // First event throws FK violation
        coEvery { eventsDao.upsert(match { it.uid == "uid-1" }) } throws
            android.database.sqlite.SQLiteConstraintException(
                "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)"
            )
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-1", calendar.id) } returns null
        // Second event succeeds
        coEvery { eventsDao.upsert(match { it.uid == "uid-2" }) } returns 2L

        val result = pullStrategy.pull(calendar, client = client)

        // Should be Success, not Error — FK error skipped, sync continued
        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        // Sync token should advance (loop broken)
        coVerify { calendarRepository.updateSyncToken(any(), any(), any()) }
        // Both events should have been attempted
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-1" }) }
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-2" }) }
    }

    @Test
    fun `FK constraint on exception event skips and continues sync`() = runTest {
        // After fix: FK error on exception event upsert should skip it.
        // Master event should still be intact in the database.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}master-with-exception.ics"
        val masterWithExceptionIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:20240108T100000Z
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Modified Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("master-with-exception.ics", eventUrl, "etag-1", masterWithExceptionIcal)
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getByUid("master-uid") } returns emptyList()
        coEvery { eventsDao.getExceptionByUidAndInstanceTime(any(), any(), any()) } returns null

        // Master event upsert succeeds
        coEvery { eventsDao.upsert(match { it.rrule != null }) } returns 1L
        // Exception event upsert throws FK violation
        coEvery { eventsDao.upsert(match { it.rrule == null }) } throws
            android.database.sqlite.SQLiteConstraintException(
                "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)"
            )

        val result = pullStrategy.pull(calendar, client = client)

        // Should be Success — master event saved, exception skipped
        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        // Sync token should advance
        coVerify { calendarRepository.updateSyncToken(any(), any(), any()) }
        // Master event WAS upserted (verify it's intact)
        coVerify(exactly = 1) { eventsDao.upsert(match { it.rrule != null }) }
        // Master's occurrences were generated (intact)
        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
    }

    @Test
    fun `multiple FK errors skip individually without aborting`() = runTest {
        // After fix: Multiple FK errors should each be skipped individually.
        // Events that succeed should still be processed.
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("e1.ics", "${calendar.caldavUrl}e1.ics", "etag1", createSimpleIcal("uid-1", "Event 1")),
            CalDavEvent("e2.ics", "${calendar.caldavUrl}e2.ics", "etag2", createSimpleIcal("uid-2", "Event 2")),
            CalDavEvent("e3.ics", "${calendar.caldavUrl}e3.ics", "etag3", createSimpleIcal("uid-3", "Event 3"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null

        // Event 1: FK error
        coEvery { eventsDao.upsert(match { it.uid == "uid-1" }) } throws
            android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-1", calendar.id) } returns null
        // Event 2: succeeds
        coEvery { eventsDao.upsert(match { it.uid == "uid-2" }) } returns 2L
        // Event 3: FK error
        coEvery { eventsDao.upsert(match { it.uid == "uid-3" }) } throws
            android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-3", calendar.id) } returns null

        val result = pullStrategy.pull(calendar, client = client)

        // Should succeed with 1 event added (event 2)
        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        // All 3 events should have been attempted
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-1" }) }
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-2" }) }
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "uid-3" }) }
    }

    @Test
    fun `FK constraint error no longer creates persistent failure loop`() = runTest {
        // After fix: Two consecutive syncs with FK errors should both succeed.
        // Sync token should advance, breaking the infinite failure loop.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}problem-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("problem-event.ics", eventUrl, "etag-1",
                createSimpleIcal("uid-problem", "Problem Event"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } throws
            android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        // First attempt — should succeed (skip problematic event)
        val result1 = pullStrategy.pull(calendar, client = client)
        assertTrue("First sync should succeed", result1 is PullResult.Success)

        // Sync token WAS updated — loop is broken
        coVerify(atLeast = 1) { calendarRepository.updateSyncToken(any(), any(), any()) }

        // Second attempt — also succeeds
        val result2 = pullStrategy.pull(calendar, client = client)
        assertTrue("Second sync should also succeed", result2 is PullResult.Success)
    }

    @Test
    fun `FK constraint error increments session already-synced counter`() = runTest {
        // After fix: sessionBuilder.incrementSkipAlreadySynced() should be called
        // for each constraint skip, so the counter appears in Sync History UI.
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("e1.ics", "${calendar.caldavUrl}e1.ics", "etag1", createSimpleIcal("uid-1", "Event 1")),
            CalDavEvent("e2.ics", "${calendar.caldavUrl}e2.ics", "etag2", createSimpleIcal("uid-2", "Event 2"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null

        // Both events throw constraint violations (already synced in prior session)
        coEvery { eventsDao.upsert(any()) } throws
            android.database.sqlite.SQLiteConstraintException("UNIQUE constraint failed")
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)

        // Build the session and verify the already-synced counter
        val session = sessionBuilder.build()
        assertTrue("Session should have already-synced skips", session.hasAlreadySynced)
        assertEquals("Should have 2 already-synced skips", 2, session.skippedAlreadySynced)
    }

    // ========== Batched Concurrent Multiget Tests (v22.5.11) ==========

    @Test
    fun `batched multiget chunks hrefs into batches of 20`() = runTest {
        // 120 hrefs should be split into 6 batches: [20, 20, 20, 20, 20, 20]
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 120
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } answers {
            val hrefs = secondArg<List<String>>()
            CalDavResult.success(serverEvents.filter { it.href in hrefs })
        }
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        // 120 hrefs / 20 per batch = 6 batches
        coVerify(exactly = 6) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    @Test
    fun `batched multiget with fewer than 20 hrefs sends single batch`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 15
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        // 15 hrefs < 20 batch size → 1 call
        coVerify(exactly = 1) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    @Test
    fun `batched multiget collects results from all batches`() = runTest {
        // 120 events across 3 batches should all be processed
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 120
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } answers {
            val hrefs = secondArg<List<String>>()
            CalDavResult.success(serverEvents.filter { it.href in hrefs })
        }
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        val success = result as PullResult.Success
        // All 120 events from all 3 batches should be processed
        assertEquals(120, success.eventsAdded)
    }

    @Test
    fun `batched multiget error falls back to individual for all batches`() = runTest {
        // A3: When all batches fail, each falls back to individual fetches.
        // If individual fetches also fail, sync completes with 0 events (not Error).
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 40  // 40 / 20 = 2 batches
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        // All fetches fail (batch and individual)
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.error(500, "Server error", isRetryable = true)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        // A3: Sync completes with 0 events (individual fallbacks also failed)
        assertTrue("Expected Success but got $result", result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `batched multiget with empty hrefs returns empty`() = runTest {
        // 0 hrefs → fetchEventsByHref should not be called
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `batched multiget concurrent batches all execute`() = runTest {
        // Verify all batches are launched by checking call count matches expected batches
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 200  // 200 / 20 = 10 batches
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } answers {
            val hrefs = secondArg<List<String>>()
            CalDavResult.success(serverEvents.filter { it.href in hrefs })
        }
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        assertEquals(200, (result as PullResult.Success).eventsAdded)
        // 200 / 20 = 10 batches, all should execute
        coVerify(exactly = 10) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    // ========== Empty Multiget Fallback Tests (Zoho compatibility) ==========

    @Test
    fun `non-empty multiget success returns immediately without fallback`() = runTest {
        // Regression guard: working servers (iCloud, Nextcloud, etc.) that return non-empty
        // multiget results should hit the early return and never trigger fallback.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair("event1.ics", "etag-1"),
                Pair("event2.ics", "etag-2")
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event1.ics", "${calendar.caldavUrl}event1.ics", "etag-1",
                    createSimpleIcal("uid-1", "Event 1")),
                CalDavEvent("event2.ics", "${calendar.caldavUrl}event2.ics", "etag-2",
                    createSimpleIcal("uid-2", "Event 2"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(2, (result as PullResult.Success).eventsAdded)
        // Should be called exactly 1 time — batch succeeded, no fallback
        coVerify(exactly = 1) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    @Test
    fun `batched multiget falls back to single-href when batch returns empty`() = runTest {
        // Zoho returns HTTP 200 empty body for multi-href calendar-multiget.
        // When a batch returns 0 events for >1 hrefs, fetchEventsBatched should
        // fall back to concurrent single-href fetches.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val hrefs = (1..10).map { "event-$it.ics" }
        val events = hrefs.map { href ->
            CalDavEvent(href, "${calendar.caldavUrl}$href", "etag-$href",
                createSimpleIcal("uid-$href", "Event $href"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(hrefs.map { Pair(it, "etag-$it") })
        // Multi-href batch returns empty (Zoho quirk)
        // Single-href requests return the individual event
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } answers {
            val requestedHrefs = secondArg<List<String>>()
            if (requestedHrefs.size > 1) {
                CalDavResult.success(emptyList()) // Zoho: empty for multi-href
            } else {
                val href = requestedHrefs[0]
                val event = events.find { it.href == href }
                CalDavResult.success(listOfNotNull(event))
            }
        }
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        assertEquals(10, (result as PullResult.Success).eventsAdded)
        // 1 batch call (returns empty) + 10 single-href fallback calls = 11 total
        coVerify(exactly = 11) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    @Test
    fun `batched multiget single-href fallback skips individual failures`() = runTest {
        // When falling back to single-href, individual failures should be skipped
        // (partial data is better than none).
        val calendar = createCalendar(ctag = null, syncToken = null)
        val hrefs = (1..5).map { "event-$it.ics" }
        val events = hrefs.map { href ->
            CalDavEvent(href, "${calendar.caldavUrl}$href", "etag-$href",
                createSimpleIcal("uid-$href", "Event $href"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(hrefs.map { Pair(it, "etag-$it") })
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } answers {
            val requestedHrefs = secondArg<List<String>>()
            if (requestedHrefs.size > 1) {
                CalDavResult.success(emptyList()) // Empty for multi-href
            } else {
                val href = requestedHrefs[0]
                if (href == "event-3.ics") {
                    CalDavResult.error(500, "Server error") // One href fails
                } else {
                    val event = events.find { it.href == href }
                    CalDavResult.success(listOfNotNull(event))
                }
            }
        }
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        // 4 of 5 events should be written (event-3 failed individually)
        assertEquals(4, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `batched multiget error with individual fallback recovery`() = runTest {
        // A3: When batch multiget returns error, individual fallback recovers events.
        // This replaces the old "error preserves retryable flag" test since batch
        // errors no longer produce PullResult.Error.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val href1 = "event1.ics"
        val href2 = "event2.ics"
        val url1 = "${calendar.caldavUrl}event1.ics"
        val url2 = "${calendar.caldavUrl}event2.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(href1, "etag-1"), Pair(href2, "etag-2")))
        // Batch (multi-href) fails
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, match { it.size > 1 }) } returns
            CalDavResult.error(503, "Service Unavailable", isRetryable = true)
        // Individual fetches succeed
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href1)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href1, url1, "etag-1", createSimpleIcal("uid-1", "Event 1"))
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href2)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href2, url2, "etag-2", createSimpleIcal("uid-2", "Event 2"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        assertEquals(2, (result as PullResult.Success).eventsAdded)
    }

    // ========== Parse Failure Retry Logic (GAP 6) ==========

    @Test
    fun `incremental pull holds sync token when parse errors exist and retries remain`() = runTest {
        // When parse errors occur and we haven't exceeded MAX_PARSE_RETRIES,
        // the sync token should NOT be advanced (held at old value for retry)
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        val eventHref = "${calendar.caldavUrl}event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))

        // sync-collection returns changed items (incremental path)
        val syncReport = SyncReport(
            changed = listOf(SyncItem(eventHref, "etag-1", SyncItemStatus.OK)),
            deleted = emptyList(),
            syncToken = "new-token"
        )
        coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns CalDavResult.success(syncReport)

        // Multiget returns event - href must match SyncItem.href for missing-event detection
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns CalDavResult.success(listOf(
            CalDavEvent(eventHref, eventHref, "etag-1",
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nNO-UID-HERE\nEND:VEVENT\nEND:VCALENDAR")
        ))

        // Parse failure retry: currently at 0 retries (below MAX=3)
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } returns 0
        coEvery { dataStore.incrementParseFailureRetry(calendar.id) } returns 1

        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        // Use spyk to control the parse error count returned by getSkippedParseError(),
        // since we can't guarantee the icaldav parser's exact behavior with invalid ICS
        val sessionBuilder = spyk(SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.BACKGROUND_PERIODIC
        ))
        every { sessionBuilder.getSkippedParseError() } returns 1

        val result = pullStrategy.pull(
            calendar, client = client, sessionBuilder = sessionBuilder
        )

        assertTrue("Expected Success", result is PullResult.Success)
        val success = result as PullResult.Success
        // Token should be held at old value (not advanced to new-token)
        assertEquals("old-token", success.newSyncToken)
    }

    @Test
    fun `incremental pull advances sync token after max parse retries exceeded`() = runTest {
        // When parse errors exceed MAX_PARSE_RETRIES, give up and advance the token
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        val eventHref = "${calendar.caldavUrl}event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))

        val syncReport = SyncReport(
            changed = listOf(SyncItem(eventHref, "etag-1", SyncItemStatus.OK)),
            deleted = emptyList(),
            syncToken = "new-token"
        )
        coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns CalDavResult.success(syncReport)

        // Multiget returns event - href must match SyncItem.href for missing-event detection
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns CalDavResult.success(listOf(
            CalDavEvent(eventHref, eventHref, "etag-1",
                "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nNO-UID-HERE\nEND:VEVENT\nEND:VCALENDAR")
        ))

        // Parse failure retry: at max retries (3)
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } returns 3
        coEvery { dataStore.resetParseFailureRetry(calendar.id) } just Runs

        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        // Use spyk to control the parse error count returned by getSkippedParseError(),
        // since we can't guarantee the icaldav parser's exact behavior with invalid ICS
        val sessionBuilder = spyk(SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.BACKGROUND_PERIODIC
        ))
        every { sessionBuilder.getSkippedParseError() } returns 1

        val result = pullStrategy.pull(
            calendar, client = client, sessionBuilder = sessionBuilder
        )

        assertTrue("Expected Success", result is PullResult.Success)
        val success = result as PullResult.Success
        // Token should be advanced to new value (gave up on parse errors)
        assertEquals("new-token", success.newSyncToken)
        // Retry count should be reset
        coVerify { dataStore.resetParseFailureRetry(calendar.id) }
    }

    @Test
    fun `successful incremental pull resets parse failure retry count`() = runTest {
        // When an incremental sync has no parse errors but had previous retries,
        // the retry count should be reset
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        val eventUrl = "${calendar.caldavUrl}event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))

        val syncReport = SyncReport(
            changed = listOf(SyncItem(eventUrl, "etag-1", SyncItemStatus.OK)),
            deleted = emptyList(),
            syncToken = "new-token"
        )
        coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns CalDavResult.success(syncReport)

        // href must match SyncItem.href for missing-event detection
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns CalDavResult.success(listOf(
            CalDavEvent(eventUrl, eventUrl, "etag-1",
                createSimpleIcal("uid-1", "Valid Event"))
        ))

        // Previous retry count was > 0
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } returns 2
        coEvery { dataStore.resetParseFailureRetry(calendar.id) } just Runs

        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-1", calendar.id) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success", result is PullResult.Success)
        // Retry count should be reset since sync succeeded without parse errors
        coVerify { dataStore.resetParseFailureRetry(calendar.id) }
    }

    // ========== No Ctag Fallback (GAP 4 + GAP 6) ==========

    @Test
    fun `pull proceeds when getCtag returns error - no ctag server support`() = runTest {
        // Zoho and some servers don't support getctag. Pull should still proceed.
        val calendar = createCalendar(ctag = null, syncToken = null)

        // getCtag returns error (server doesn't support it)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.error(404, "Not Found")

        // Full pull proceeds
        val serverEvents = listOf(
            CalDavEvent("event1.ics", "${calendar.caldavUrl}event1.ics", "etag-1",
                createSimpleIcal("uid-1", "Event 1"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Success but got $result", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
    }

    // ========== Recently Pushed Event Skip (v22.5.6) ==========

    @Test
    fun `pull skips recently pushed event even when etag differs`() = runTest {
        // When an event was just pushed in this sync cycle, pull should skip it
        // even if the server returns a different etag (CDN staleness protection).
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}pushed-event.ics"
        val existingEvent = createEvent(id = 42, caldavUrl = eventUrl, title = "Local Version").copy(
            uid = "uid-pushed",
            etag = "etag-after-push"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("pushed-event.ics", eventUrl, "etag-stale-from-cdn",
                createSimpleIcal("uid-pushed", "Server Version (stale CDN)"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("uid-pushed", calendar.id) } returns existingEvent

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(
            calendar,
            client = client,
            sessionBuilder = sessionBuilder,
            recentlyPushedEventIds = setOf(42L)
        )

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        // Event should NOT have been upserted (it was skipped)
        coVerify(exactly = 0) { eventsDao.upsert(match { it.uid == "uid-pushed" }) }

        // Session should record the skip
        val session = sessionBuilder.build()
        assertEquals("Should have 1 recently-pushed skip", 1, session.skippedRecentlyPushed)
    }

    // ========== Recently Pushed Event Deletion Protection (v23.2.1) ==========
    // RFC 4791 does not guarantee immediate visibility after PUT.
    // Servers without sync-collection (e.g., Purelymail) always use pullFull.
    // A just-pushed event may not appear in the server's etag response yet.

    @Test
    fun `pullFull does not delete recently pushed event missing from server etags`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val pushedEventUrl = "${calendar.caldavUrl}pushed-event.ics"
        val pushedEvent = createEvent(id = 42, caldavUrl = pushedEventUrl, title = "Just Pushed").copy(
            uid = "uid-pushed",
            etag = "etag-from-put",
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Server returns empty etag list — event not indexed yet
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(pushedEvent)

        val result = pullStrategy.pull(
            calendar,
            client = client,
            recentlyPushedEventIds = setOf(42L)
        )

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 0) { eventsDao.deleteById(42L) }
    }

    @Test
    fun `pullFull still deletes stale events not in recentlyPushedEventIds`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val staleUrl = "${calendar.caldavUrl}stale.ics"
        val staleEvent = createEvent(id = 99, caldavUrl = staleUrl, title = "Stale Event")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(staleEvent)

        val result = pullStrategy.pull(
            calendar,
            client = client,
            recentlyPushedEventIds = setOf(42L)  // Different ID
        )

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify(exactly = 1) { eventsDao.deleteById(99L) }
    }

    // ========== Non-Event Resource Handling (VTODO/VJOURNAL/VFREEBUSY) ==========

    private fun createVtodoIcal(uid: String, summary: String): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VTODO
            UID:$uid
            DTSTAMP:20240101T120000Z
            SUMMARY:$summary
            STATUS:NEEDS-ACTION
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
    }

    private fun createVjournalIcal(uid: String, summary: String): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VJOURNAL
            UID:$uid
            DTSTAMP:20240101T120000Z
            SUMMARY:$summary
            DESCRIPTION:Journal entry content
            END:VJOURNAL
            END:VCALENDAR
        """.trimIndent()
    }

    @Test
    fun `incremental pull — VTODO resource is NOT counted as parse failure`() = runTest {
        val calendar = createCalendar(syncToken = "sync-token-1")
        val todoHref = "todo-1.ics"
        val vtodoUrl = "${calendar.caldavUrl}todo-1.ics"

        // sync-collection returns one VTODO href
        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-1") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-2",
                changed = listOf(SyncItem(todoHref, "etag-todo", SyncItemStatus.OK)),
                deleted = emptyList()
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(todoHref, vtodoUrl, "etag-todo", createVtodoIcal("vtodo-uid-1", "Buy groceries"))
            ))
        coEvery { eventsDao.getByCaldavUrl(vtodoUrl) } returns null
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.BACKGROUND_PERIODIC
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        val session = sessionBuilder.build()
        assertEquals("VTODO should NOT be counted as parse error", 0, session.skippedParseError)
        assertEquals("Session should be SUCCESS, not PARTIAL", org.onekash.kashcal.sync.session.SyncStatus.SUCCESS, session.status)
        // Token should advance (no parse errors holding it back)
        assertEquals("sync-token-2", (result as PullResult.Success).newSyncToken)
    }

    @Test
    fun `incremental pull — mixed VEVENT + VTODO resources parse correctly`() = runTest {
        val calendar = createCalendar(syncToken = "sync-token-1")
        val eventHref = "event-1.ics"
        val todoHref = "todo-1.ics"
        val eventUrl = "${calendar.caldavUrl}event-1.ics"
        val vtodoUrl = "${calendar.caldavUrl}todo-1.ics"

        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-1") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-2",
                changed = listOf(
                    SyncItem(eventHref, "etag-event", SyncItemStatus.OK),
                    SyncItem(todoHref, "etag-todo", SyncItemStatus.OK)
                ),
                deleted = emptyList()
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(eventHref, eventUrl, "etag-event", createSimpleIcal("vevent-uid-1", "Real Meeting")),
                CalDavEvent(todoHref, vtodoUrl, "etag-todo", createVtodoIcal("vtodo-uid-1", "Buy groceries"))
            ))
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getByCaldavUrl(vtodoUrl) } returns null
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.BACKGROUND_PERIODIC
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        val success = result as PullResult.Success
        assertEquals("VEVENT should be written", 1, success.eventsAdded)
        val session = sessionBuilder.build()
        assertEquals("VTODO should NOT be counted as parse error", 0, session.skippedParseError)
        assertEquals("Session should be SUCCESS", org.onekash.kashcal.sync.session.SyncStatus.SUCCESS, session.status)
    }

    @Test
    fun `incremental pull — VJOURNAL resource is silently skipped`() = runTest {
        val calendar = createCalendar(syncToken = "sync-token-1")
        val journalHref = "journal-1.ics"
        val vjournalUrl = "${calendar.caldavUrl}journal-1.ics"

        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-1") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-2",
                changed = listOf(SyncItem(journalHref, "etag-journal", SyncItemStatus.OK)),
                deleted = emptyList()
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(journalHref, vjournalUrl, "etag-journal", createVjournalIcal("vjournal-uid-1", "Meeting notes"))
            ))
        coEvery { eventsDao.getByCaldavUrl(vjournalUrl) } returns null
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.BACKGROUND_PERIODIC
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        val session = sessionBuilder.build()
        assertEquals("VJOURNAL should NOT be counted as parse error", 0, session.skippedParseError)
        assertEquals("Session should be SUCCESS", org.onekash.kashcal.sync.session.SyncStatus.SUCCESS, session.status)
        assertEquals("sync-token-2", (result as PullResult.Success).newSyncToken)
    }

    @Test
    fun `genuinely malformed ICS still counts as parse error`() = runTest {
        val calendar = createCalendar(syncToken = "sync-token-1")
        val badHref = "bad-event.ics"
        val badUrl = "${calendar.caldavUrl}bad-event.ics"

        coEvery { client.syncCollection(calendar.caldavUrl, "sync-token-1") } returns
            CalDavResult.success(SyncReport(
                syncToken = "sync-token-2",
                changed = listOf(SyncItem(badHref, "etag-bad", SyncItemStatus.OK)),
                deleted = emptyList()
            ))
        // Malformed ICS: has VCALENDAR but no valid component inside
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(badHref, badUrl, "etag-bad",
                    """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Test//EN
                    END:VCALENDAR
                    """.trimIndent())
            ))
        coEvery { eventsDao.getByCaldavUrl(badUrl) } returns null
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.BACKGROUND_PERIODIC
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        val session = sessionBuilder.build()
        assertEquals("Genuinely malformed ICS SHOULD count as parse error", 1, session.skippedParseError)
        assertEquals("Session should be PARTIAL for real parse errors", org.onekash.kashcal.sync.session.SyncStatus.PARTIAL, session.status)
    }

    @Test
    fun `full pull — VTODO resource in processEvents is silently skipped`() = runTest {
        // Defense-in-depth: pullFull uses fetchEtagsInRange which has comp-filter VEVENT,
        // so VTODOs shouldn't reach processEvents. But if they do, they should be skipped.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event-1.ics"
        val vtodoUrl = "${calendar.caldavUrl}todo-1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("event-1.ics", eventUrl, "etag-event", createSimpleIcal("vevent-uid-1", "Real Event")),
            CalDavEvent("todo-1.ics", vtodoUrl, "etag-todo", createVtodoIcal("vtodo-uid-1", "Task Item"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getByCaldavUrl(vtodoUrl) } returns null

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        val success = result as PullResult.Success
        assertEquals("VEVENT should be written", 1, success.eventsAdded)
        val session = sessionBuilder.build()
        assertEquals("VTODO should NOT be counted as parse error", 0, session.skippedParseError)
        assertEquals("Session should be SUCCESS", org.onekash.kashcal.sync.session.SyncStatus.SUCCESS, session.status)
    }

    // ========== A1: Parse Exception Resilience Tests ==========

    @Test
    fun `parser exception skips event and continues to next`() = runTest {
        // A1: Verify that an Exception thrown by icalParser.parseAllEvents() is caught
        // and the event is skipped, rather than aborting processEvents().
        // ICalParser internally catches Exception and returns ParseResult.Error, so this
        // is defense-in-depth. We use mockkConstructor to force a throw for testing.
        mockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        try {
            val calendar = createCalendar(ctag = null, syncToken = null)
            val badUrl = "${calendar.caldavUrl}bad.ics"
            val goodUrl = "${calendar.caldavUrl}good.ics"

            coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
            val badIcal = "CRASH-TRIGGER-DATA"
            val goodIcal = createSimpleIcal("uid-good", "Good Event")
            val serverEvents = listOf(
                CalDavEvent("bad.ics", badUrl, "etag-bad", badIcal),
                CalDavEvent("good.ics", goodUrl, "etag-good", goodIcal)
            )
            mockTwoStepFetch(calendar.caldavUrl, serverEvents)
            coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
            coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
            coEvery { eventsDao.getByCaldavUrl(any()) } returns null
            coEvery { eventsDao.upsert(any()) } returns 1L

            // Mock parser: crash on bad data, real parser for good data
            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(badIcal)
            } throws RuntimeException("Unexpected parser crash")
            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(neq(badIcal))
            } answers { callOriginal() }

            val sessionBuilder = SyncSessionBuilder(
                calendarId = calendar.id,
                calendarName = calendar.displayName,
                syncType = SyncType.FULL,
                triggerSource = SyncTrigger.FOREGROUND_MANUAL
            )

            val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

            // Verify: sync completes successfully (bad event skipped, good event processed)
            assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
            assertEquals(1, (result as PullResult.Success).eventsAdded)
            val session = sessionBuilder.build()
            assertEquals("Bad event should be counted as parse error", 1, session.skippedParseError)
        } finally {
            unmockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        }
    }

    // ========== A3: Batch Fallback Resilience Tests ==========

    @Test
    fun `batch multiget failure falls back to individual fetches`() = runTest {
        // A3: When a multiget batch fails, fall back to fetchSingleHrefConcurrent
        // for that batch. Other batches (if any) continue normally.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val href1 = "event1.ics"
        val href2 = "event2.ics"
        val url1 = "${calendar.caldavUrl}event1.ics"
        val url2 = "${calendar.caldavUrl}event2.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        // Step 1: fetchEtagsInRange returns two hrefs
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(href1, "etag-1"), Pair(href2, "etag-2")))

        // Step 2: multiget batch FAILS
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, match { it.size > 1 }) } returns
            CalDavResult.error(500, "Internal Server Error")

        // Step 3: individual fetches succeed
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href1)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href1, url1, "etag-1", createSimpleIcal("uid-1", "Event 1"))
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href2)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href2, url2, "etag-2", createSimpleIcal("uid-2", "Event 2"))
            ))

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        // Verify: both events recovered via individual fallback
        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(2, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `batch multiget failure with one bad individual event recovers the rest`() = runTest {
        // A3 + individual fallback: batch fails, individual fetches recover all except one bad event
        val calendar = createCalendar(ctag = null, syncToken = null)
        val href1 = "event1.ics"
        val href2 = "bad-event.ics"
        val url1 = "${calendar.caldavUrl}event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(href1, "etag-1"), Pair(href2, "etag-2")))

        // Multiget batch fails
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, match { it.size > 1 }) } returns
            CalDavResult.error(500, "Internal Server Error")

        // Individual: first succeeds, second fails
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href1)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href1, url1, "etag-1", createSimpleIcal("uid-1", "Good Event"))
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href2)) } returns
            CalDavResult.error(404, "Not Found")

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        // Verify: good event recovered, bad event silently skipped
        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `MULTIGET_BATCH_SIZE is 20`() {
        // Verify batch size was reduced from 50 to 20
        val field = PullStrategy::class.java.getDeclaredField("MULTIGET_BATCH_SIZE")
        field.isAccessible = true
        assertEquals(20, field.getInt(null))
    }

    // ========== A1: Adverse Tests — All Events Fail Parse ==========

    @Test
    fun `all events have parse exceptions — returns Success with zero events`() = runTest {
        // A1 adverse: When EVERY event throws a parse exception, sync should still
        // complete with Success(eventsAdded=0), not abort or return Error.
        mockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        try {
            val calendar = createCalendar(ctag = null, syncToken = null)

            coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
            val serverEvents = (1..3).map { i ->
                CalDavEvent("bad-$i.ics", "${calendar.caldavUrl}bad-$i.ics", "etag-$i", "BAD-DATA-$i")
            }
            mockTwoStepFetch(calendar.caldavUrl, serverEvents)
            coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
            coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

            // All events crash the parser
            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(any())
            } throws RuntimeException("Corrupt ICS data")

            val sessionBuilder = SyncSessionBuilder(
                calendarId = calendar.id,
                calendarName = calendar.displayName,
                syncType = SyncType.FULL,
                triggerSource = SyncTrigger.FOREGROUND_MANUAL
            )

            val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

            assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
            assertEquals(0, (result as PullResult.Success).eventsAdded)
            val session = sessionBuilder.build()
            assertEquals("All 3 events should be counted as parse errors", 3, session.skippedParseError)
        } finally {
            unmockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        }
    }

    @Test
    fun `multiple parse exceptions counts each one in session stats`() = runTest {
        // A1: Verify session.skippedParseError accurately counts multiple failures
        mockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        try {
            val calendar = createCalendar(ctag = null, syncToken = null)

            coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
            val goodIcal = createSimpleIcal("uid-good", "Good Event")
            val serverEvents = listOf(
                CalDavEvent("bad-1.ics", "${calendar.caldavUrl}bad-1.ics", "etag-1", "BAD-1"),
                CalDavEvent("bad-2.ics", "${calendar.caldavUrl}bad-2.ics", "etag-2", "BAD-2"),
                CalDavEvent("good.ics", "${calendar.caldavUrl}good.ics", "etag-3", goodIcal),
                CalDavEvent("bad-3.ics", "${calendar.caldavUrl}bad-3.ics", "etag-4", "BAD-3")
            )
            mockTwoStepFetch(calendar.caldavUrl, serverEvents)
            coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
            coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
            coEvery { eventsDao.getByCaldavUrl(any()) } returns null
            coEvery { eventsDao.upsert(any()) } returns 1L

            // Bad data crashes, good data uses real parser
            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(match { it.startsWith("BAD-") })
            } throws RuntimeException("Corrupt")
            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(match { !it.startsWith("BAD-") })
            } answers { callOriginal() }

            val sessionBuilder = SyncSessionBuilder(
                calendarId = calendar.id,
                calendarName = calendar.displayName,
                syncType = SyncType.FULL,
                triggerSource = SyncTrigger.FOREGROUND_MANUAL
            )

            val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

            assertTrue("Expected PullResult.Success", result is PullResult.Success)
            assertEquals("Good event should be added", 1, (result as PullResult.Success).eventsAdded)
            val session = sessionBuilder.build()
            assertEquals("3 bad events should be counted", 3, session.skippedParseError)
        } finally {
            unmockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        }
    }

    @Test
    fun `parser exception in incremental pull skips event and continues`() = runTest {
        // A1 on incremental path: parse exception during pullIncremental should
        // skip the event and continue, same as pullFull.
        mockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        try {
            val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
            val badHref = "bad-event.ics"
            val goodHref = "good-event.ics"
            val badUrl = "${calendar.caldavUrl}bad-event.ics"
            val goodUrl = "${calendar.caldavUrl}good-event.ics"

            coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
            coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns
                CalDavResult.success(SyncReport(
                    syncToken = "new-token",
                    changed = listOf(
                        SyncItem(badHref, "etag-bad", SyncItemStatus.OK),
                        SyncItem(goodHref, "etag-good", SyncItemStatus.OK)
                    ),
                    deleted = emptyList()
                ))

            val badIcal = "CRASH-DATA"
            val goodIcal = createSimpleIcal("uid-good", "Good Event")
            coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
                CalDavResult.success(listOf(
                    CalDavEvent(badHref, badUrl, "etag-bad", badIcal),
                    CalDavEvent(goodHref, goodUrl, "etag-good", goodIcal)
                ))

            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(badIcal)
            } throws RuntimeException("Parser crash on bad data")
            every {
                anyConstructed<org.onekash.icaldav.parser.ICalParser>().parseAllEvents(neq(badIcal))
            } answers { callOriginal() }

            coEvery { eventsDao.getByCaldavUrl(any()) } returns null
            coEvery { eventsDao.upsert(any()) } returns 1L
            coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0
            coEvery { dataStore.getParseFailureRetryCount(calendar.id) } returns 0
            coEvery { dataStore.incrementParseFailureRetry(calendar.id) } returns 1

            val sessionBuilder = SyncSessionBuilder(
                calendarId = calendar.id,
                calendarName = calendar.displayName,
                syncType = SyncType.INCREMENTAL,
                triggerSource = SyncTrigger.BACKGROUND_PERIODIC
            )

            val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

            assertTrue("Expected PullResult.Success", result is PullResult.Success)
            assertEquals("Good event should be added", 1, (result as PullResult.Success).eventsAdded)
            val session = sessionBuilder.build()
            assertEquals("Bad event should be counted as parse error", 1, session.skippedParseError)
        } finally {
            unmockkConstructor(org.onekash.icaldav.parser.ICalParser::class)
        }
    }

    // ========== A3: Adverse Tests — Multi-Batch Partial Failure ==========

    @Test
    fun `multi-batch sync with middle batch failing recovers via fallback`() = runTest {
        // A3 adverse: 60 events = 3 batches of 20. Batch 2 fails, batches 1 and 3 succeed.
        // Events from batch 2 should be recovered via individual fallback.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 60
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })

        // Track which batch call this is
        var batchCallCount = 0
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } answers {
            val hrefs = secondArg<List<String>>()
            if (hrefs.size > 1) {
                // Multi-href batch call
                batchCallCount++
                if (batchCallCount == 2) {
                    // Batch 2 fails
                    CalDavResult.error(500, "Internal Server Error")
                } else {
                    // Batches 1 and 3 succeed
                    CalDavResult.success(serverEvents.filter { it.href in hrefs })
                }
            } else {
                // Single-href fallback call (for batch 2 events)
                val href = hrefs[0]
                val event = serverEvents.find { it.href == href }
                CalDavResult.success(listOfNotNull(event))
            }
        }

        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)
        // All 60 events should be processed: 40 from successful batches + 20 from fallback
        assertEquals(60, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `incremental pull batch failure falls back to individual fetches`() = runTest {
        // A3 on incremental path: batch multiget failure during pullIncremental
        // should fall back to individual fetches, same as pullFull.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        val href1 = "event1.ics"
        val href2 = "event2.ics"
        val url1 = "${calendar.caldavUrl}event1.ics"
        val url2 = "${calendar.caldavUrl}event2.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns
            CalDavResult.success(SyncReport(
                syncToken = "new-token",
                changed = listOf(
                    SyncItem(href1, "etag-1", SyncItemStatus.OK),
                    SyncItem(href2, "etag-2", SyncItemStatus.OK)
                ),
                deleted = emptyList()
            ))

        // Batch fetch fails
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, match { it.size > 1 }) } returns
            CalDavResult.error(500, "Internal Server Error")

        // Individual fetches succeed
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href1)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href1, url1, "etag-1", createSimpleIcal("uid-1", "Event 1"))
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, listOf(href2)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href2, url2, "etag-2", createSimpleIcal("uid-2", "Event 2"))
            ))

        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } returns 0

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals("Both events should be recovered via fallback", 2, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `batch fallback with all individual fetches failing returns Success with zero events`() = runTest {
        // A3 adverse: batch fails AND every individual fallback also fails.
        // Sync should still complete with Success(eventsAdded=0).
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair("event1.ics", "etag-1"),
                Pair("event2.ics", "etag-2"),
                Pair("event3.ics", "etag-3")
            ))
        // All fetches fail — batch and individual
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.error(503, "Service Unavailable", isRetryable = true)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        // Verify fetchEventsByHref was called: 1 batch + 3 individual fallbacks = 4
        coVerify(atLeast = 2) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    // ========== Configurable Sync Lookback (pullFull) ==========

    @Test
    fun `pullFull uses configurable sync lookback from preferences`() = runTest {
        // Set lookback to 730 days (2 years) instead of default 365
        every { dataStore.syncPastDays } returns flowOf(730)

        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        // Capture startMs argument from fetchEtagsInRange
        val startMsSlot = slot<Long>()
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, capture(startMsSlot), any()) } returns
            CalDavResult.success(emptyList())

        pullStrategy.pull(calendar, client = client)

        val capturedStartMs = startMsSlot.captured
        val now = System.currentTimeMillis()
        val expected730DaysAgo = now - (730L * 24 * 60 * 60 * 1000)
        val expected365DaysAgo = now - (365L * 24 * 60 * 60 * 1000)

        // startMs should be ~730 days ago (within 5 second tolerance)
        assertTrue(
            "startMs should be ~730 days ago, but was ${(now - capturedStartMs) / (24 * 60 * 60 * 1000)} days ago",
            kotlin.math.abs(capturedStartMs - expected730DaysAgo) < 5000
        )
        // startMs should NOT be ~365 days ago (proves it's not hardcoded)
        assertTrue(
            "startMs should NOT be ~365 days ago (hardcoded value)",
            kotlin.math.abs(capturedStartMs - expected365DaysAgo) > 100 * 24 * 60 * 60 * 1000
        )
    }

    @Test
    fun `pullFull uses unfiltered range when sync lookback is All`() = runTest {
        // "All" lookback = Int.MAX_VALUE should use startMs = 0L
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        val startMsSlot = slot<Long>()
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, capture(startMsSlot), any()) } returns
            CalDavResult.success(emptyList())

        pullStrategy.pull(calendar, client = client)

        assertEquals("startMs should be 0L for 'All' lookback", 0L, startMsSlot.captured)
    }

    @Test
    fun `forceFullSync pullFull uses configurable lookback not hardcoded`() = runTest {
        // Set lookback to 180 days (6 months)
        every { dataStore.syncPastDays } returns flowOf(180)

        // Calendar WITH syncToken — would normally go incremental, but forceFullSync overrides
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-sync-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        val startMsSlot = slot<Long>()
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, capture(startMsSlot), any()) } returns
            CalDavResult.success(emptyList())

        // forceFullSync = true forces pullFull despite having syncToken
        pullStrategy.pull(calendar, client = client, forceFullSync = true)

        val capturedStartMs = startMsSlot.captured
        val now = System.currentTimeMillis()
        val expected180DaysAgo = now - (180L * 24 * 60 * 60 * 1000)

        // startMs should be ~180 days ago (within 5 second tolerance)
        assertTrue(
            "startMs should be ~180 days ago, but was ${(now - capturedStartMs) / (24 * 60 * 60 * 1000)} days ago",
            kotlin.math.abs(capturedStartMs - expected180DaysAgo) < 5000
        )
    }

    // ========== Etag Comparison in pullFull (Bandwidth Optimization) ==========

    @Test
    fun `pullFull skips download for events with matching etags`() = runTest {
        // Scenario: 3 events on server, 2 already exist locally with matching etags
        // Expected: Only 1 event (new/changed) should be downloaded
        val calendar = createCalendar(ctag = null, syncToken = null)
        every { dataStore.syncPastDays } returns flowOf(365)

        // Use absolute paths - ICloudQuirks.buildEventUrl combines baseHost + href
        val href1 = "/calendars/home/event1.ics"
        val href2 = "/calendars/home/event2.ics"
        val href3 = "/calendars/home/event3.ics"
        // buildEventUrl produces: baseHost (https://caldav.example.com) + href
        val url1 = "https://caldav.example.com/calendars/home/event1.ics"
        val url2 = "https://caldav.example.com/calendars/home/event2.ics"
        val url3 = "https://caldav.example.com/calendars/home/event3.ics"

        // Server returns 3 events with their etags
        val serverEtags = listOf(
            Pair(href1, "etag-1"),
            Pair(href2, "etag-2"),
            Pair(href3, "etag-3")
        )

        // Local DB has 2 events with matching etags
        val localEtagEntries = listOf(
            EtagEntry(caldavUrl = url1, etag = "etag-1"),  // Matches server
            EtagEntry(caldavUrl = url2, etag = "etag-2")   // Matches server
            // event3 is NOT in local DB
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEtags)
        coEvery { eventsDao.getByCalendarIdInRange(any<Long>(), any<Long>(), any<Long>()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(any<Long>(), any<Long>(), any<Long>()) } returns localEtagEntries

        // Track which hrefs are actually fetched
        val fetchedHrefsSlot = slot<List<String>>()
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, capture(fetchedHrefsSlot)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href3, url3, "etag-3", createSimpleIcal("uid-3", "Event 3"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // Only event3 should be fetched (event1 and event2 have matching etags)
        assertEquals(listOf(href3), fetchedHrefsSlot.captured)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `pullFull downloads events with different etags`() = runTest {
        // Scenario: 2 events on server, 1 exists locally with DIFFERENT etag (modified on server)
        // Expected: The modified event should be downloaded
        val calendar = createCalendar(ctag = null, syncToken = null)
        every { dataStore.syncPastDays } returns flowOf(365)

        // Use absolute paths - ICloudQuirks.buildEventUrl combines baseHost + href
        val href1 = "/calendars/home/event1.ics"
        val href2 = "/calendars/home/event2.ics"
        val url1 = "https://caldav.example.com/calendars/home/event1.ics"
        val url2 = "https://caldav.example.com/calendars/home/event2.ics"

        // Server: event1 has new etag (modified), event2 unchanged
        val serverEtags = listOf(
            Pair(href1, "etag-1-MODIFIED"),  // Changed on server
            Pair(href2, "etag-2")            // Unchanged
        )

        // Local DB has old etag for event1
        val localEtagEntries = listOf(
            EtagEntry(caldavUrl = url1, etag = "etag-1-OLD"),  // Mismatches server
            EtagEntry(caldavUrl = url2, etag = "etag-2")       // Matches server
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEtags)
        coEvery { eventsDao.getByCalendarIdInRange(any<Long>(), any<Long>(), any<Long>()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(any<Long>(), any<Long>(), any<Long>()) } returns localEtagEntries

        val fetchedHrefsSlot = slot<List<String>>()
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, capture(fetchedHrefsSlot)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href1, url1, "etag-1-MODIFIED", createSimpleIcal("uid-1", "Event 1 Updated"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCaldavUrl(url1) } returns createEvent(id = 1, caldavUrl = url1)
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // Only event1 should be fetched (different etag)
        assertEquals(listOf(href1), fetchedHrefsSlot.captured)
        assertEquals(1, (result as PullResult.Success).eventsUpdated)
    }

    @Test
    fun `pullFull downloads events not in local DB`() = runTest {
        // Scenario: Server has events that don't exist locally at all
        // Expected: All new events should be downloaded
        val calendar = createCalendar(ctag = null, syncToken = null)
        every { dataStore.syncPastDays } returns flowOf(365)

        // Use absolute paths - ICloudQuirks.buildEventUrl combines baseHost + href
        val href1 = "/calendars/home/new-event1.ics"
        val href2 = "/calendars/home/new-event2.ics"
        val url1 = "https://caldav.example.com/calendars/home/new-event1.ics"
        val url2 = "https://caldav.example.com/calendars/home/new-event2.ics"

        val serverEtags = listOf(
            Pair(href1, "etag-1"),
            Pair(href2, "etag-2")
        )

        // Local DB is empty (no etag entries)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEtags)
        coEvery { eventsDao.getByCalendarIdInRange(any<Long>(), any<Long>(), any<Long>()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(any<Long>(), any<Long>(), any<Long>()) } returns emptyList()

        val fetchedHrefsSlot = slot<List<String>>()
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, capture(fetchedHrefsSlot)) } returns
            CalDavResult.success(listOf(
                CalDavEvent(href1, url1, "etag-1", createSimpleIcal("uid-1", "New Event 1")),
                CalDavEvent(href2, url2, "etag-2", createSimpleIcal("uid-2", "New Event 2"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // Both events should be fetched
        assertEquals(listOf(href1, href2), fetchedHrefsSlot.captured)
        assertEquals(2, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `pullFull returns Success with zero downloads when all etags match`() = runTest {
        // Scenario: All server events already exist locally with matching etags
        // Expected: No downloads, Success result
        val calendar = createCalendar(ctag = null, syncToken = null)
        every { dataStore.syncPastDays } returns flowOf(365)

        // Use absolute paths - ICloudQuirks.buildEventUrl combines baseHost + href
        val href1 = "/calendars/home/event1.ics"
        val href2 = "/calendars/home/event2.ics"
        val url1 = "https://caldav.example.com/calendars/home/event1.ics"
        val url2 = "https://caldav.example.com/calendars/home/event2.ics"

        val serverEtags = listOf(
            Pair(href1, "etag-1"),
            Pair(href2, "etag-2")
        )

        // All events exist locally with matching etags
        val localEtagEntries = listOf(
            EtagEntry(caldavUrl = url1, etag = "etag-1"),
            EtagEntry(caldavUrl = url2, etag = "etag-2")
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEtags)
        coEvery { eventsDao.getByCalendarIdInRange(any<Long>(), any<Long>(), any<Long>()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(any<Long>(), any<Long>(), any<Long>()) } returns localEtagEntries
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // fetchEventsByHref should NOT be called since all etags match
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(0, result.eventsUpdated)
    }

    @Test
    fun `incremental sync still works correctly after etag comparison feature`() = runTest {
        // Regression: Ensure etag comparison in pullFull doesn't break incremental sync path
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        val changedHref = "changed-event.ics"
        val changedUrl = "${calendar.caldavUrl}changed-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null))
        coEvery { client.syncCollection(calendar.caldavUrl, "old-token") } returns
            CalDavResult.success(SyncReport(
                syncToken = "new-token",
                changed = listOf(SyncItem(changedHref, "etag-1", SyncItemStatus.OK)),
                deleted = emptyList()
            ))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(changedHref, changedUrl, "etag-1", createSimpleIcal("uid-1", "Changed Event"))
            ))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { dataStore.getParseFailureRetryCount(calendar.id) } returns 0

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)

        // getEtagMapForCalendar should NOT be called for incremental sync
        coVerify(exactly = 0) { eventsDao.getEtagMapForCalendar(any(), any(), any()) }
    }

    // ========== Timestamp Validation Tests (Issue #140) ==========

    @Test
    fun `hasValidTimestamps rejects endTs before startTs`() {
        val event = createEvent().copy(startTs = 1000, endTs = 999)
        assertFalse(PullStrategy.hasValidTimestamps(event))
    }

    @Test
    fun `hasValidTimestamps accepts zero-duration event`() {
        val event = createEvent().copy(startTs = 1000, endTs = 1000)
        assertTrue(PullStrategy.hasValidTimestamps(event))
    }

    @Test
    fun `hasValidTimestamps accepts normal event`() {
        val event = createEvent().copy(startTs = 1000, endTs = 2000)
        assertTrue(PullStrategy.hasValidTimestamps(event))
    }

    @Test
    fun `hasValidTimestamps accepts historical event with negative startTs`() {
        // Pre-1970 events have negative timestamps — legitimate
        val event = createEvent().copy(startTs = -1000000, endTs = -999000)
        assertTrue(PullStrategy.hasValidTimestamps(event))
    }

    @Test
    fun `hasValidTimestamps accepts epoch-zero event`() {
        // Jan 1 1970 is a real date
        val event = createEvent().copy(startTs = 0, endTs = 3600000)
        assertTrue(PullStrategy.hasValidTimestamps(event))
    }

    @Test
    fun `historical event from 2005 is NOT skipped`() = runTest {
        // Events with old but valid timestamps are legitimate — must not be rejected.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}historical.ics"

        val historicalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:old-uid
            DTSTAMP:20050601T120000Z
            DTSTART:20050601T100000Z
            DTEND:20050601T110000Z
            SUMMARY:Historical Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("historical.ics", eventUrl, "etag-1", historicalIcal)
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "old-uid" }) }
    }

    @Test
    fun `zero-duration event is NOT skipped`() = runTest {
        // endTs == startTs is valid (milestones, reminders). Only endTs < startTs is rejected.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}milestone.ics"

        val zeroDurationIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:milestone-uid
            DTSTAMP:20240601T120000Z
            DTSTART:20240601T100000Z
            DTEND:20240601T100000Z
            SUMMARY:Milestone
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("milestone.ics", eventUrl, "etag-1", zeroDurationIcal)
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "milestone-uid" }) }
    }

    // ========== Race Condition: hasPendingChanges() TOCTOU Fix (#9) ==========

    @Test
    fun `pull skips upsert when event gains pending changes between check and transaction`() = runTest {
        // RACE SCENARIO: User edits event between the outer hasPendingChanges() check (line 865)
        // and the inner upsert transaction (line 927). The outer check sees SYNCED, but by the
        // time the transaction runs, the event is PENDING_UPDATE. Without the fix, the server
        // version silently overwrites the user's local edit (data loss).
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}race-event.ics"

        // Existing event starts as SYNCED — passes the outer hasPendingChanges() check
        val existingEvent = createEvent(
            id = 500L,
            caldavUrl = eventUrl,
            title = "Original Title"
        ).copy(syncStatus = SyncStatus.SYNCED, uid = "race-uid", etag = "old-etag")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("race-event.ics", eventUrl, "new-etag",
                createSimpleIcal("race-uid", "Server Title"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        // UID lookup returns the existing event (SYNCED at outer check time)
        coEvery { eventsDao.getMasterByUidAndCalendar("race-uid", calendar.id) } returns existingEvent

        // THE RACE: getSyncStatus returns PENDING_UPDATE inside the transaction,
        // simulating a user edit that happened after the outer check
        coEvery { eventsDao.getSyncStatus(500L) } returns SyncStatus.PENDING_UPDATE

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        // Upsert must NOT be called — the event has pending local changes
        coVerify(exactly = 0) { eventsDao.upsert(match { it.uid == "race-uid" }) }
        // The skip should be counted in session metrics
        val session = sessionBuilder.build()
        assertTrue("Should count race-skipped event as skippedPendingLocal",
            session.skippedPendingLocal > 0)
    }

    @Test
    fun `pull proceeds with upsert when getSyncStatus confirms SYNCED inside transaction`() = runTest {
        // HAPPY PATH: No race — event is still SYNCED when re-checked inside transaction
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}normal-event.ics"

        val existingEvent = createEvent(
            id = 600L,
            caldavUrl = eventUrl,
            title = "Original Title"
        ).copy(syncStatus = SyncStatus.SYNCED, uid = "normal-uid", etag = "old-etag")

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "server-ctag", displayName = null, color = null, isReadOnly = null))
        val serverEvents = listOf(
            CalDavEvent("normal-event.ics", eventUrl, "new-etag",
                createSimpleIcal("normal-uid", "Server Title"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("normal-uid", calendar.id) } returns existingEvent

        // No race: getSyncStatus returns SYNCED (consistent with outer check)
        coEvery { eventsDao.getSyncStatus(600L) } returns SyncStatus.SYNCED
        coEvery { eventsDao.upsert(any()) } returns 600L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected PullResult.Success", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsUpdated)
        // Upsert SHOULD be called — no race detected
        coVerify(exactly = 1) { eventsDao.upsert(match { it.uid == "normal-uid" }) }
    }

    // ========== hasContentChanged Tests ==========

    @Test
    fun `hasContentChanged returns false when only etag differs`() {
        val existing = createEvent().copy(etag = "old-etag")
        val incoming = createEvent().copy(etag = "new-etag")
        assertFalse(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when title changed`() {
        val existing = createEvent(title = "Original")
        val incoming = createEvent(title = "Updated")
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when time changed`() {
        val now = System.currentTimeMillis()
        val existing = createEvent().copy(startTs = now, endTs = now + 3600000)
        val incoming = createEvent().copy(startTs = now + 1800000, endTs = now + 5400000)
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when location changed`() {
        val existing = createEvent().copy(location = null)
        val incoming = createEvent().copy(location = "New York")
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when rrule changed`() {
        val existing = createEvent().copy(rrule = "FREQ=WEEKLY;BYDAY=MO")
        val incoming = createEvent().copy(rrule = "FREQ=WEEKLY;BYDAY=MO,WE")
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when exdate changed`() {
        val existing = createEvent().copy(rrule = "FREQ=WEEKLY", exdate = null)
        val incoming = createEvent().copy(rrule = "FREQ=WEEKLY", exdate = "20240115T100000Z")
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when reminders changed`() {
        val existing = createEvent().copy(reminders = listOf("-PT15M"))
        val incoming = createEvent().copy(reminders = listOf("-PT15M", "-PT1H"))
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns true when sequence changed`() {
        val existing = createEvent().copy(sequence = 1)
        val incoming = createEvent().copy(sequence = 2)
        assertTrue(PullStrategy.hasContentChanged(existing, incoming))
    }

    @Test
    fun `hasContentChanged returns false when only sync metadata differs`() {
        val existing = createEvent().copy(
            dtstamp = 1000L,
            syncStatus = SyncStatus.SYNCED,
            rawIcal = "BEGIN:VCALENDAR...",
            caldavUrl = "https://old.example.com/event.ics",
            importId = "old-import",
            createdAt = 1000L,
            updatedAt = 2000L,
            localModifiedAt = 3000L,
            serverModifiedAt = 4000L,
            lastSyncError = "old error",
            syncRetryCount = 1
        )
        val incoming = createEvent().copy(
            dtstamp = 9000L,
            syncStatus = SyncStatus.PENDING_UPDATE,
            rawIcal = "BEGIN:VCALENDAR...v2",
            caldavUrl = "https://new.example.com/event.ics",
            importId = "new-import",
            createdAt = 5000L,
            updatedAt = 6000L,
            localModifiedAt = 7000L,
            serverModifiedAt = 8000L,
            lastSyncError = null,
            syncRetryCount = 0
        )
        assertFalse(PullStrategy.hasContentChanged(existing, incoming))
    }

    // ========== Sync Change Suppression for Recurring Series ==========

    @Test
    fun `exception removed server-side is deleted locally when master resource omits it`() = runTest {
        // Reproduces the device-tested bug: an occurrence was edited into an
        // exception, then deleted on another client (iPhone). iCloud adds an
        // EXDATE to the master AND drops the override VEVENT from the resource.
        // RFC 4791 §4.1: all same-UID components live in one resource, so when
        // the master is present, the exceptions in that resource are the
        // COMPLETE authoritative set. A local exception row whose instance is no
        // longer present must be deleted — otherwise the stale occurrence
        // lingers on the calendar (observed: "exception not deleted").
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}recur.ics"
        val goneInstanceTime = parseDate("2024-01-09 10:00")

        val masterEvent = createEvent(id = 800L, caldavUrl = eventUrl, title = "Daily")
            .copy(uid = "recur-uid", rrule = "FREQ=DAILY;COUNT=10", etag = "etag-1")
        // Local exception row for the occurrence that was deleted on the phone.
        val staleException = createEvent(id = 801L, caldavUrl = eventUrl, title = "Daily (edited)")
            .copy(
                uid = "recur-uid",
                etag = "etag-1",
                originalEventId = 800L,
                originalInstanceTime = goneInstanceTime,
                startTs = parseDate("2024-01-09 07:55"),
                endTs = parseDate("2024-01-09 08:25")
            )

        // Server now returns ONLY the master, with the deleted slot EXDATE'd.
        // No exception VEVENT in the resource.
        val masterOnlyIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recur-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20240109T100000Z
            SUMMARY:Daily
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        // href is the FULL resource URL so buildEventUrl round-trips to exactly
        // eventUrl — the bundled exception shares the master's caldavUrl, so the
        // generic URL-based stale-delete path (caldavUrl not in server set) does
        // NOT fire. Only exception-aware pruning can delete the stale row, so
        // this isolates the real gap.
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent(eventUrl, eventUrl, "etag-2", masterOnlyIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent, staleException)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("recur-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("recur-uid") } returns listOf(masterEvent, staleException)
        // The pull discovers local exceptions for the master to reconcile.
        coEvery { eventsDao.getExceptionsForMaster(800L) } returns listOf(staleException)
        coEvery { eventsDao.upsert(any()) } returns 800L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should succeed", result is PullResult.Success)
        // The stale exception row must be deleted — it no longer exists on the
        // server (bundled with the master, which now omits it) and its slot is
        // EXDATE'd. The generic URL path can't catch it (URL matches the master).
        coVerify { eventsDao.deleteById(801L) }
    }

    @Test
    fun `exception still present in master resource is NOT pruned`() = runTest {
        // Over-deletion guard: the normal case where the master AND its
        // exception are both in the resource. Pruning must NOT delete the
        // exception that is still present.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}recur.ics"
        val instanceTime = parseDate("2024-01-09 10:00")

        val masterEvent = createEvent(id = 800L, caldavUrl = eventUrl, title = "Daily")
            .copy(uid = "recur-uid", rrule = "FREQ=DAILY;COUNT=10", etag = "etag-1")
        val liveException = createEvent(id = 801L, caldavUrl = eventUrl, title = "Daily (edited)")
            .copy(
                uid = "recur-uid",
                etag = "etag-1",
                originalEventId = 800L,
                originalInstanceTime = instanceTime,
                startTs = parseDate("2024-01-09 07:55"),
                endTs = parseDate("2024-01-09 08:25")
            )

        // Resource still contains BOTH master and the exception VEVENT.
        val bundledIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recur-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            RRULE:FREQ=DAILY;COUNT=10
            SUMMARY:Daily
            END:VEVENT
            BEGIN:VEVENT
            UID:recur-uid
            DTSTAMP:20240109T120000Z
            RECURRENCE-ID:20240109T100000Z
            DTSTART:20240109T075500
            DTEND:20240109T082500
            SUMMARY:Daily (edited)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent(eventUrl, eventUrl, "etag-2", bundledIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent, liveException)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("recur-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("recur-uid") } returns listOf(masterEvent, liveException)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("recur-uid", calendar.id, any()) } returns liveException
        coEvery { eventsDao.getExceptionsForMaster(800L) } returns listOf(liveException)
        coEvery { eventsDao.upsert(any()) } returnsMany listOf(800L, 801L)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should succeed", result is PullResult.Success)
        // The still-present exception must NOT be deleted.
        coVerify(exactly = 0) { eventsDao.deleteById(801L) }
    }

    @Test
    fun `exception is NOT pruned when master absent from batch`() = runTest {
        // RFC 4791 §4.1 allows a resource carrying only overrides (no master).
        // Such a batch is not authoritative for pruning — the master and other
        // overrides may simply be outside this sync window. An exception-only
        // resource must NOT trigger deletion of sibling exceptions.
        //
        // Both local rows share the master resource URL, which IS returned by
        // the batch, so the generic URL-based stale-delete path spares them —
        // isolating the exception-pruning guard as the only thing that could
        // delete row 802.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val masterUrl = "${calendar.caldavUrl}master.ics"
        val instanceTime = parseDate("2024-01-09 10:00")
        val otherInstanceTime = parseDate("2024-01-10 10:00")

        val masterEvent = createEvent(id = 800L, caldavUrl = masterUrl, title = "Daily")
            .copy(uid = "recur-uid", rrule = "FREQ=DAILY;COUNT=10", etag = "m-etag",
                startTs = parseDate("2024-01-01 10:00"), endTs = parseDate("2024-01-01 11:00"), timezone = null)
        // A local exception for a DIFFERENT instance than the one in this batch.
        val otherException = createEvent(id = 802L, caldavUrl = masterUrl, title = "Other edited")
            .copy(uid = "recur-uid", etag = "m-etag", originalEventId = 800L, originalInstanceTime = otherInstanceTime)

        // The batch returns the master's resource, but only the ETag row for it
        // in this window carries just an override (RECURRENCE-ID), no master
        // VEVENT — mirroring a windowed fetch that surfaced only one instance.
        val excOnlyIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recur-uid
            DTSTAMP:20240109T120000Z
            RECURRENCE-ID:20240109T100000Z
            DTSTART:20240109T075500
            DTEND:20240109T082500
            SUMMARY:This instance
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        // href == masterUrl so buildEventUrl round-trips and the URL path spares
        // both local rows (their caldavUrl is in the server set).
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent(masterUrl, masterUrl, "etag-2", excOnlyIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent, otherException)
        coEvery { eventsDao.getByCaldavUrl(masterUrl) } returns masterEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("recur-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("recur-uid") } returns listOf(masterEvent, otherException)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("recur-uid", calendar.id, instanceTime) } returns null
        coEvery { eventsDao.getExceptionsForMaster(800L) } returns listOf(otherException)
        coEvery { eventsDao.upsert(any()) } returns 803L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should succeed", result is PullResult.Success)
        // The master VEVENT was NOT parsed in this batch → uid not in the
        // authoritative-master set → exception pruning must NOT fire for 802.
        coVerify(exactly = 0) { eventsDao.deleteById(802L) }
    }

    /**
     * Shared fixture for the EXDATE-gated prune tests. The server resource
     * contains only the master (the override VEVENT is gone). Callers control
     * whether the master carries an EXDATE for the missing instance, the
     * exception's sync status, and recentlyPushedEventIds.
     */
    private suspend fun runPruneScenario(
        masterExdate: String?,
        exceptionSyncStatus: SyncStatus = SyncStatus.SYNCED,
        recentlyPushed: Set<Long> = emptySet(),
    ): PullResult {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}recur.ics"
        val goneInstance = parseDate("2024-01-09 10:00")

        val masterEvent = createEvent(id = 800L, caldavUrl = eventUrl, title = "Daily")
            .copy(uid = "recur-uid", rrule = "FREQ=DAILY;COUNT=10", etag = "etag-1", exdate = masterExdate)
        val staleException = createEvent(id = 801L, caldavUrl = eventUrl, title = "Daily (edited)")
            .copy(
                uid = "recur-uid", etag = "etag-1", originalEventId = 800L,
                originalInstanceTime = goneInstance,
                startTs = parseDate("2024-01-09 07:55"), endTs = parseDate("2024-01-09 08:25"),
                syncStatus = exceptionSyncStatus
            )

        val exdateLine = masterExdate?.let { "\n            EXDATE:20240109T100000Z" } ?: ""
        val masterOnlyIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recur-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            RRULE:FREQ=DAILY;COUNT=10$exdateLine
            SUMMARY:Daily
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(CalDavEvent(eventUrl, eventUrl, "etag-2", masterOnlyIcal)))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent, staleException)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("recur-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("recur-uid") } returns listOf(masterEvent, staleException)
        coEvery { eventsDao.getExceptionsForMaster(800L) } returns listOf(staleException)
        coEvery { eventsDao.getSyncStatus(801L) } returns exceptionSyncStatus
        coEvery { eventsDao.upsert(any()) } returns 800L

        return pullStrategy.pull(calendar, client = client, recentlyPushedEventIds = recentlyPushed)
    }

    @Test
    fun `absent exception NOT pruned when master has no covering EXDATE`() = runTest {
        // Split-resource server / parser-dropped override: the override is
        // absent from the batch but the master has NO EXDATE for it, so it may
        // still be live on the server. Must NOT prune (erring toward keeping).
        val result = runPruneScenario(masterExdate = null)
        assertTrue(result is PullResult.Success)
        coVerify(exactly = 0) { eventsDao.deleteById(801L) }
    }

    @Test
    fun `absent exception pruned only when EXDATE covers it - occurrence cancelled and change emitted`() = runTest {
        val result = runPruneScenario(masterExdate = parseDate("2024-01-09 10:00").toString())
        assertTrue(result is PullResult.Success)
        // Row deleted, occurrence cancelled, and exactly one DELETED change surfaced.
        coVerify { database.occurrencesDao().markCancelledByException(801L) }
        coVerify { eventsDao.deleteById(801L) }
        val deletes = (result as PullResult.Success).changes.filter { it.type == ChangeType.DELETED }
        assertEquals("Exactly one DELETED change for the pruned occurrence", 1, deletes.size)
    }

    @Test
    fun `EXDATE-covered exception NOT pruned when it has pending local changes`() = runTest {
        // Data-loss guard: an unsynced local edit must survive to be pushed.
        val result = runPruneScenario(
            masterExdate = parseDate("2024-01-09 10:00").toString(),
            exceptionSyncStatus = SyncStatus.PENDING_UPDATE,
        )
        assertTrue(result is PullResult.Success)
        coVerify(exactly = 0) { eventsDao.deleteById(801L) }
    }

    @Test
    fun `EXDATE-covered exception NOT pruned when master recently pushed`() = runTest {
        // CDN protection: a just-pushed master's resource may echo back stale.
        val result = runPruneScenario(
            masterExdate = parseDate("2024-01-09 10:00").toString(),
            recentlyPushed = setOf(800L),
        )
        assertTrue(result is PullResult.Success)
        coVerify(exactly = 0) { eventsDao.deleteById(801L) }
    }

    @Test
    fun `value-type-mismatched RECURRENCE-ID matches its stored exception instead of duplicating`() = runTest {
        // A recurring master (timed) with an exception whose RECURRENCE-ID is a
        // DATE-form value (value-type mismatch: RFC 5545 §3.8.4.4 says it MUST
        // share DTSTART's value type, but peer clients emit the mismatch and
        // servers preserve it verbatim). ICalEventMapper stores the exception's
        // originalInstanceTime NORMALIZED to the master's local time-of-day, so
        // the exception's pull-back lookup MUST normalize the same way. The DAO
        // here returns the stored exception only for the normalized instance
        // time (10:00Z) and null for the raw midnight-UTC value — mirroring a
        // real DB. If the lookup keys off the raw value it misses, and the
        // exception is wrongly re-added as a NEW event (the spurious "N events
        // updated" alert plus a duplicate row).
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}recurring-with-exception.ics"

        // Master DTSTART 10:00Z (timed). Normalizing a DATE-form RECURRENCE-ID of
        // 2024-01-08 against it promotes to the master's time-of-day => 10:00Z.
        val normalizedInstanceTime = parseDate("2024-01-08 10:00")
        val rawMidnightInstanceTime = parseDate("2024-01-08 00:00")

        val masterEvent = createEvent(id = 700L, caldavUrl = eventUrl, title = "Weekly Meeting")
            .copy(uid = "master-uid", rrule = "FREQ=WEEKLY", etag = "resource-etag-2")
        // The exception as stored locally: originalInstanceTime is the NORMALIZED
        // value the mapper wrote. Content matches the echoed VEVENT below so that,
        // once correctly matched, no spurious MODIFIED notification fires either.
        val existingException = createEvent(id = 701L, caldavUrl = eventUrl, title = "Weekly Meeting")
            .copy(
                uid = "master-uid",
                etag = "resource-etag-2",
                originalEventId = 700L,
                originalInstanceTime = normalizedInstanceTime,
                startTs = parseDate("2024-01-08 11:00"),
                endTs = parseDate("2024-01-08 12:00")
            )

        val echoedIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY
            END:VEVENT
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240108T120000Z
            RECURRENCE-ID;VALUE=DATE:20240108
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Weekly Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent("recurring-with-exception.ics", eventUrl, "resource-etag-3", echoedIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getMasterByUidAndCalendar("master-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        // Real-DB behavior: only the NORMALIZED instance time finds the stored row.
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, normalizedInstanceTime)
        } returns existingException
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, rawMidnightInstanceTime)
        } returns null
        coEvery { eventsDao.upsert(any()) } returnsMany listOf(700L, 701L)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should succeed", result is PullResult.Success)
        val success = result as PullResult.Success
        // The core invariant: echoing an EXISTING edited occurrence must never
        // surface it as a brand-new event...
        assertTrue(
            "Existing exception must not be re-added as NEW: " +
                success.changes.map { "${it.type}: ${it.eventTitle}" },
            success.changes.none { it.type == ChangeType.NEW }
        )
        // ...and the matched exception must upsert IN PLACE (existing id 701),
        // never insert a second row for the same instance.
        coVerify { eventsDao.upsert(match { it.id == 701L && it.originalEventId == 700L }) }
    }

    @Test
    fun `value-type-mismatched RECURRENCE-ID matches on incremental pull with master only in DB`() = runTest {
        // Same value-type mismatch, but the INCREMENTAL path: the pulled .ics
        // contains ONLY the changed exception VEVENT — the unchanged master is
        // not re-fetched, so it is absent from this batch and resolved from Room
        // instead. The instance-time key must still normalize against the Room
        // master's DTSTART (reconstructed), or the exception re-adds as NEW.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}exception-only.ics"

        val normalizedInstanceTime = parseDate("2024-01-08 10:00")
        val rawMidnightInstanceTime = parseDate("2024-01-08 00:00")

        // Master lives only in Room (timed, 10:00Z), NOT in the fetched batch.
        val masterEvent = createEvent(id = 700L, caldavUrl = "${calendar.caldavUrl}master.ics", title = "Weekly Meeting")
            .copy(
                uid = "master-uid",
                rrule = "FREQ=WEEKLY",
                etag = "master-etag",
                startTs = parseDate("2024-01-01 10:00"),
                endTs = parseDate("2024-01-01 11:00"),
                timezone = null // UTC — matches DTSTART ...T100000Z
            )
        val existingException = createEvent(id = 701L, caldavUrl = eventUrl, title = "Weekly Meeting")
            .copy(
                uid = "master-uid",
                etag = "exc-etag-1",
                originalEventId = 700L,
                originalInstanceTime = normalizedInstanceTime,
                startTs = parseDate("2024-01-08 11:00"),
                endTs = parseDate("2024-01-08 12:00")
            )

        // Only the exception VEVENT is returned (no master component).
        val exceptionOnlyIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240108T120000Z
            RECURRENCE-ID;VALUE=DATE:20240108
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:Weekly Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent("exception-only.ics", eventUrl, "exc-etag-2", exceptionOnlyIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent, existingException)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingException
        // Master resolved from Room (the batch has no master component).
        coEvery { eventsDao.getMasterByUidAndCalendar("master-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent, existingException)
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, normalizedInstanceTime)
        } returns existingException
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, rawMidnightInstanceTime)
        } returns null
        coEvery { eventsDao.upsert(any()) } returns 701L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should succeed", result is PullResult.Success)
        val success = result as PullResult.Success
        assertTrue(
            "Incremental exception echo must match the Room master's normalized instance " +
                "time, not re-add as NEW: " + success.changes.map { "${it.type}: ${it.eventTitle}" },
            success.changes.none { it.type == ChangeType.NEW }
        )
    }

    @Test
    fun `incremental exception normalizes against an RDATE-only master`() = runTest {
        // A master can recur via RDATE with no RRULE (RFC 5545 §3.8.5.2). On the
        // incremental path the Room master is used to reconstruct the DTSTART for
        // normalization; the recurring check must include rdate, not just rrule,
        // or a value-type-mismatched exception is keyed off the RAW value and its
        // stored (normalized) row is missed -> re-added as NEW.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}exception-only.ics"

        val normalizedInstanceTime = parseDate("2024-01-08 10:00")
        val rawMidnightInstanceTime = parseDate("2024-01-08 00:00")

        // Master recurs via RDATE only (rrule = null) — timed, 10:00Z.
        val masterEvent = createEvent(id = 700L, caldavUrl = "${calendar.caldavUrl}master.ics", title = "RDATE Meeting")
            .copy(
                uid = "master-uid",
                rrule = null,
                rdate = parseDate("2024-01-08 10:00").toString(),
                etag = "master-etag",
                startTs = parseDate("2024-01-01 10:00"),
                endTs = parseDate("2024-01-01 11:00"),
                timezone = null
            )
        val existingException = createEvent(id = 701L, caldavUrl = eventUrl, title = "RDATE Meeting")
            .copy(
                uid = "master-uid",
                etag = "exc-etag-1",
                originalEventId = 700L,
                originalInstanceTime = normalizedInstanceTime,
                startTs = parseDate("2024-01-08 11:00"),
                endTs = parseDate("2024-01-08 12:00")
            )

        val exceptionOnlyIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-uid
            DTSTAMP:20240108T120000Z
            RECURRENCE-ID;VALUE=DATE:20240108
            DTSTART:20240108T110000Z
            DTEND:20240108T120000Z
            SUMMARY:RDATE Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent("exception-only.ics", eventUrl, "exc-etag-2", exceptionOnlyIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(masterEvent, existingException)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingException
        coEvery { eventsDao.getMasterByUidAndCalendar("master-uid", calendar.id) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent, existingException)
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, normalizedInstanceTime)
        } returns existingException
        coEvery {
            eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, rawMidnightInstanceTime)
        } returns null
        coEvery { eventsDao.upsert(any()) } returns 701L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should succeed", result is PullResult.Success)
        val success = result as PullResult.Success
        assertTrue(
            "RDATE-only master must still normalize the exception key, not re-add as NEW: " +
                success.changes.map { "${it.type}: ${it.eventTitle}" },
            success.changes.none { it.type == ChangeType.NEW }
        )
    }

    @Test
    fun `recurring series etag-only change on master suppresses SyncChange`() = runTest {
        // Scenario: A recurring event resource (.ics) is re-fetched with a new etag
        // (because a sibling VEVENT in the same resource changed). The master's content
        // is identical. It should be upserted (new etag) but NOT generate a SyncChange.
        //
        // Strategy: First sync creates the event. We capture what ICalEventMapper produces
        // and use it as the "existing" event for the second sync with only etag changed.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}recurring.ics"

        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly Standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // --- First sync: capture the mapped event ---
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-1", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent(eventUrl, eventUrl, "etag-1", serverIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-1")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getMasterByUidAndCalendar("recurring-uid", calendar.id) } returns null

        val upsertSlot = slot<Event>()
        coEvery { eventsDao.upsert(capture(upsertSlot)) } returns 100L

        val firstResult = pullStrategy.pull(calendar, client = client)
        assertTrue("First sync should succeed", firstResult is PullResult.Success)
        assertEquals(1, (firstResult as PullResult.Success).eventsAdded)

        // Build the "existing" event: what was saved + the DB-assigned id
        val existingMaster = upsertSlot.captured.copy(id = 100L)

        // --- Second sync: same content, new etag ---
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(CalendarMetadataProbe(ctag = "ctag-2", displayName = null, color = null, isReadOnly = null))
        mockTwoStepFetch(calendar.caldavUrl, listOf(
            CalDavEvent(eventUrl, eventUrl, "etag-2", serverIcal)
        ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token-2")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingMaster)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingMaster
        coEvery { eventsDao.getMasterByUidAndCalendar("recurring-uid", calendar.id) } returns existingMaster
        coEvery { eventsDao.getSyncStatus(100L) } returns SyncStatus.SYNCED
        coEvery { eventsDao.upsert(any()) } returns 100L

        val secondResult = pullStrategy.pull(calendar, client = client)

        assertTrue("Second sync should succeed", secondResult is PullResult.Success)
        val success = secondResult as PullResult.Success
        // Upsert still happens (saves new etag) but no SyncChange notification
        coVerify(atLeast = 2) { eventsDao.upsert(any()) }
        assertEquals(
            "Etag-only change should not produce notifications: ${success.changes.map { "${it.type}: ${it.eventTitle}" }}",
            0, success.changes.size
        )
        assertEquals(0, success.eventsUpdated)
    }
}
