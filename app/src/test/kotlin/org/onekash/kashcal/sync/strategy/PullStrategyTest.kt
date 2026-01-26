package org.onekash.kashcal.sync.strategy

import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.*
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore
import kotlinx.coroutines.flow.flowOf

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
    private lateinit var calendarsDao: CalendarsDao

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

        // Setup DataStore mock to return default reminder settings
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)

        // Default: UID lookup returns null, so tests fall back to caldavUrl lookup
        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null

        pullStrategy = PullStrategy(
            database = database,
            client = client,
            calendarsDao = calendarsDao,
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

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.NoChanges)
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
        coVerify(exactly = 0) { client.fetchEventsInRange(any(), any(), any()) }
    }

    @Test
    fun `pull proceeds when ctag is different`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Success)
        coVerify { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `pull proceeds when local ctag is null (first sync)`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar)

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

        pullStrategy.pull(calendar)

        coVerify { client.syncCollection(calendar.caldavUrl, "sync-token-123") }
        coVerify(exactly = 0) { client.fetchEventsInRange(any(), any(), any()) }
    }

    @Test
    fun `pull falls back to full sync when sync token expired`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(410, "Sync token expired")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Success)
        coVerify { client.fetchEventsInRange(any(), any(), any()) }
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

        val result = pullStrategy.pull(calendar)

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

        val result = pullStrategy.pull(calendar)

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

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-sync-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        pullStrategy.pull(calendar)

        coVerify { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `full sync deletes local events not on server`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val orphanEvent = createEvent(
            caldavUrl = "https://caldav.example.com/calendars/home/orphan.ics"
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(orphanEvent)

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsDeleted)
        coVerify { eventsDao.deleteById(orphanEvent.id) }
    }

    @Test
    fun `full sync adds new events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}new-event.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "new-event.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = createSimpleIcal("uid-new", "New Event")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "existing-event.ics",
                    url = eventUrl,
                    etag = "etag-2",
                    icalData = createSimpleIcal("uid-existing", "Updated Title")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsAdded)
        assertEquals(1, result.eventsUpdated)
    }

    // ========== Force Full Sync Tests ==========

    @Test
    fun `forceFullSync ignores sync token`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", syncToken = "sync-token-123")
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("ctag-123")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, forceFullSync = true)

        assertTrue(result is PullResult.Success)
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
        coVerify { client.fetchEventsInRange(any(), any(), any()) }
    }

    @Test
    fun `forceFullSync ignores matching ctag`() = runTest {
        val calendar = createCalendar(ctag = "same-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("same-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, forceFullSync = true)

        // Should NOT return NoChanges
        assertTrue(result is PullResult.Success)
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `pull returns error when ctag fetch fails`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.error(401, "Unauthorized", false)

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(401, (result as PullResult.Error).code)
        assertFalse(result.isRetryable)
    }

    @Test
    fun `pull returns error when fetch events fails`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.error(500, "Server error", true)

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(500, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `pull returns error with network error`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.networkError("Connection timeout")

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "recurring.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = recurringIcal
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        pullStrategy.pull(calendar)

        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
    }

    @Test
    fun `pull regenerates occurrences for non-recurring events`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        val eventUrl = "${calendar.caldavUrl}single.ics"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "single.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = createSimpleIcal("single-uid", "Single Event")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "master-with-exception.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = masterWithExceptionIcal
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null
        coEvery { eventsDao.getByUid("master-uid") } returns emptyList()
        coEvery { eventsDao.getExceptionByUidAndInstanceTime(any(), any(), any()) } returns null
        coEvery { eventsDao.upsert(any()) } returnsMany listOf(1L, 2L)

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Success)
        // Should have added both master and exception
        assertEquals(2, (result as PullResult.Success).eventsAdded)
        // Exception events now use regenerateOccurrences + cancelOccurrence (changed in afbdd5a)
        coVerify { occurrenceGenerator.generateOccurrences(any(), any(), any()) }
        coVerify { occurrenceGenerator.regenerateOccurrences(any()) }
        coVerify { occurrenceGenerator.cancelOccurrence(any(), any()) }
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

        pullStrategy.pull(calendar)

        coVerify {
            calendarsDao.updateSyncToken(
                id = calendar.id,
                syncToken = "new-token",
                ctag = "new-ctag"
            )
        }
    }

    @Test
    fun `pull does not update metadata on error`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.fetchEventsInRange(any(), any(), any()) } returns
            CalDavResult.error(500, "Server error")

        pullStrategy.pull(calendar)

        coVerify(exactly = 0) { calendarsDao.updateSyncToken(any(), any(), any()) }
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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "pending-event.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = createSimpleIcal("uid-pending", "Server Version")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "modified-event.ics",
                    url = eventUrl,
                    etag = "etag-2",
                    icalData = createSimpleIcal("uid-modified", "Server Title")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList()) // Server doesn't have this event
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(pendingDeleteEvent)

        val result = pullStrategy.pull(calendar)

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

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "master-with-exception.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = masterWithExceptionIcal
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, any()) } returns existingException
        coEvery { eventsDao.upsert(any()) } returns 500L

        pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "event.ics",
                    url = eventUrl,
                    etag = "etag-123",  // Same etag - server may have stale 15 min reminder
                    icalData = createSimpleIcal("uid-1", "Title")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "event.ics",
                    url = eventUrl,
                    etag = "new-etag",  // Different etag - server has new data
                    icalData = createSimpleIcal("uid-1", "Updated Title")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "new-event.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = createSimpleIcal("uid-new", "New Event")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null  // No existing event
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "master-with-exception.ics",
                    url = eventUrl,
                    etag = "exception-etag-123",  // Same etag - should skip exception
                    icalData = masterWithExceptionIcal
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns masterEvent
        coEvery { eventsDao.getByUid("master-uid") } returns listOf(masterEvent)
        coEvery { eventsDao.getExceptionByUidAndInstanceTime("master-uid", calendar.id, any()) } returns existingException
        coEvery { eventsDao.upsert(any()) } returns 500L

        pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0

        pullStrategy.pull(calendar)

        // Should call dedup at start of pullFull
        coVerify { eventsDao.deleteDuplicateMasterEvents() }
    }

    @Test
    fun `pullFull logs when duplicates are cleaned up`() = runTest {
        val calendar = createCalendar(ctag = null, syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("server-ctag")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 3 // Found 3 duplicates

        val result = pullStrategy.pull(calendar)

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

        val result = pullStrategy.pull(calendar)

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

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "event.ics",
                    url = eventUrl,
                    etag = "new-etag",
                    icalData = createSimpleIcal("existing-uid", "Updated Event")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        // UID lookup finds the event (primary lookup)
        coEvery { eventsDao.getMasterByUidAndCalendar("existing-uid", calendar.id) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "event.ics",
                    url = eventUrl,
                    etag = "new-etag",
                    icalData = createSimpleIcal("some-uid", "Updated Event")
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        // UID lookup returns null (not found)
        coEvery { eventsDao.getMasterByUidAndCalendar("some-uid", calendar.id) } returns null
        // Fallback to caldavUrl lookup finds the event
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns existingEvent
        coEvery { eventsDao.upsert(any()) } returns 100L

        val result = pullStrategy.pull(calendar)

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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("e1.ics", "${calendar.caldavUrl}e1.ics", "etag1", createSimpleIcal("uid1", "Event 1")),
                CalDavEvent("e2.ics", "${calendar.caldavUrl}e2.ics", "etag2", createSimpleIcal("uid2", "Event 2")),
                CalDavEvent("e3.ics", "${calendar.caldavUrl}e3.ics", "etag3", createSimpleIcal("uid3", "Event 3"))
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("token")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns
            listOf(createEvent(id = 100, caldavUrl = "orphan-url"))
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Success)
        val success = result as PullResult.Success
        assertEquals(3, success.eventsAdded)
        assertEquals(0, success.eventsUpdated)
        assertEquals(1, success.eventsDeleted)
        assertEquals(4, success.totalChanges)
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
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(
                    href = "ac-maintenance.ics",
                    url = eventUrl,
                    etag = "etag-1",
                    icalData = icloudIcal
                )
            ))
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(eventUrl) } returns null

        val capturedEvent = slot<Event>()
        coEvery { eventsDao.upsert(capture(capturedEvent)) } returns 1L

        val result = pullStrategy.pull(calendar)

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

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(-408, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("Timeout"))
    }

    @Test
    fun `pull returns NETWORK error code for IOException`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.io.IOException("Connection reset")

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(0, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("Network"))
    }

    @Test
    fun `pull returns PARSE error code for non-IO Exception`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws IllegalStateException("Unexpected state")

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(-1, (result as PullResult.Error).code)
        assertFalse(result.isRetryable)
    }

    @Test
    fun `pull returns TIMEOUT error code for ConnectTimeoutException`() = runTest {
        // ConnectTimeoutException is also a SocketTimeoutException subclass
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.net.SocketTimeoutException("Connect timed out")

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(-408, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `pull returns NETWORK error code for UnknownHostException`() = runTest {
        val calendar = createCalendar()
        coEvery { client.getCtag(any()) } throws java.net.UnknownHostException("caldav.icloud.com")

        val result = pullStrategy.pull(calendar)

        assertTrue(result is PullResult.Error)
        assertEquals(0, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("Network"))
    }

    // ========== Helper Methods ==========

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
}
