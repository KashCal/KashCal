package org.onekash.kashcal.sync.strategy

import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.*
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType

/**
 * Tests for PullStrategy - CalDAV server to local database sync.
 */
class PullStrategyTest {

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

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            syncSessionStore = syncSessionStore
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("ctag-123")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.NoChanges)
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
        coVerify(exactly = 0) { client.fetchEtagsInRange(any(), any(), any()) }
    }

    @Test
    fun `pull proceeds when ctag is different`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
    fun `incremental sync fetches changed events by href`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "sync-token-123")
        val changedHref = "/calendars/home/event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(orphanEvent)

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(orphanEvent.id) }
    }

    @Test
    fun `full sync adds new events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}new-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
    fun `pullFull multiget batch error fails fast`() = runTest {
        // When batched multiget fails, pull should return Error (fail fast, no fallback).
        // WorkManager retries the entire sync with exponential backoff.
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event.ics", "etag-1")))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.error(500, "Server error", isRetryable = true)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Error but got $result", result is PullResult.Error)
        val error = result as PullResult.Error
        assertEquals(500, error.code)
        assertTrue(error.isRetryable)
        // Should be called exactly once — no retry, no fallback
        coVerify(exactly = 1) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    // ========== Force Full Sync Tests ==========

    @Test
    fun `forceFullSync ignores sync token`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("ctag-123")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("same-ctag")
        mockTwoStepFetch(calendar.caldavUrl, emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, forceFullSync = true, client = client)

        // Should NOT return NoChanges
        assertTrue(result is PullResult.Success)
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

    // ========== FK Constraint Error Handling Tests (Issue #55) ==========

    @Test
    fun `FK error on second event still commits first event and continues to third`() = runTest {
        // Verifies: Events processed before the FK error are committed individually.
        // Each event upsert runs in its own transaction, so earlier events survive.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val event1Url = "${calendar.caldavUrl}event1.ics"
        val event2Url = "${calendar.caldavUrl}event2.ics"
        val event3Url = "${calendar.caldavUrl}event3.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
    fun `FK constraint error increments session skip counter`() = runTest {
        // After fix: sessionBuilder.incrementSkipConstraintError() should be called
        // for each FK error, so the counter appears in Sync History UI.
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        val serverEvents = listOf(
            CalDavEvent("e1.ics", "${calendar.caldavUrl}e1.ics", "etag1", createSimpleIcal("uid-1", "Event 1")),
            CalDavEvent("e2.ics", "${calendar.caldavUrl}e2.ics", "etag2", createSimpleIcal("uid-2", "Event 2"))
        )
        mockTwoStepFetch(calendar.caldavUrl, serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null

        // Both events throw FK violations
        coEvery { eventsDao.upsert(any()) } throws
            android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        val sessionBuilder = SyncSessionBuilder(
            calendarId = calendar.id,
            calendarName = calendar.displayName,
            syncType = SyncType.FULL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )

        val result = pullStrategy.pull(calendar, client = client, sessionBuilder = sessionBuilder)

        assertTrue("Expected PullResult.Success but got $result", result is PullResult.Success)

        // Build the session and verify the constraint error counter
        val session = sessionBuilder.build()
        assertTrue("Session should have constraint errors", session.hasConstraintErrors)
        assertEquals("Should have 2 constraint errors", 2, session.skippedConstraintError)
    }

    // ========== Batched Concurrent Multiget Tests (v22.5.11) ==========

    @Test
    fun `batched multiget chunks hrefs into batches of 50`() = runTest {
        // 120 hrefs should be split into 3 batches: [50, 50, 20]
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 120
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        // 120 hrefs / 50 per batch = 3 batches
        coVerify(exactly = 3) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    @Test
    fun `batched multiget with fewer than 50 hrefs sends single batch`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 30
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        // 30 hrefs < 50 batch size → 1 call
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
    fun `batched multiget fails fast on batch error`() = runTest {
        // When one batch fails, entire pull should fail (no partial recovery)
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventCount = 120
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        // All batches fail with server error
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.error(500, "Server error", isRetryable = true)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Error but got $result", result is PullResult.Error)
        val error = result as PullResult.Error
        assertEquals(500, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `batched multiget with empty hrefs returns empty`() = runTest {
        // 0 hrefs → fetchEventsByHref should not be called
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        val eventCount = 200  // 200 / 50 = 4 batches
        val serverEvents = (1..eventCount).map { i ->
            CalDavEvent("event-$i.ics", "${calendar.caldavUrl}event-$i.ics", "etag-$i",
                createSimpleIcal("uid-$i", "Event $i"))
        }

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
        // 200 / 50 = 4 batches, all should execute
        coVerify(exactly = 4) { client.fetchEventsByHref(calendar.caldavUrl, any()) }
    }

    // ========== Empty Multiget Fallback Tests (Zoho compatibility) ==========

    @Test
    fun `non-empty multiget success returns immediately without fallback`() = runTest {
        // Regression guard: working servers (iCloud, Nextcloud, etc.) that return non-empty
        // multiget results should hit the early return and never trigger fallback.
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
    fun `batched multiget error preserves retryable flag`() = runTest {
        // When batched multiget returns a retryable error, PullResult should preserve it
        // so WorkManager knows to retry.
        val calendar = createCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair("event.ics", "etag-1")))
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.error(503, "Service Unavailable", isRetryable = true)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Expected Error but got $result", result is PullResult.Error)
        val error = result as PullResult.Error
        assertEquals(503, error.code)
        assertTrue("Error should be retryable", error.isRetryable)
    }

    // ========== Parse Failure Retry Logic (GAP 6) ==========

    @Test
    fun `incremental pull holds sync token when parse errors exist and retries remain`() = runTest {
        // When parse errors occur and we haven't exceeded MAX_PARSE_RETRIES,
        // the sync token should NOT be advanced (held at old value for retry)
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "old-token")
        val eventHref = "${calendar.caldavUrl}event1.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")

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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")

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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")

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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
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
}
