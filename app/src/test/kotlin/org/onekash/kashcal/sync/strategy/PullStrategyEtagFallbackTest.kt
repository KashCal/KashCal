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
import org.onekash.kashcal.data.db.dao.EtagEntry
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

    // ========== Etag Fallback Trigger Tests ==========

    @Test
    fun `etag fallback triggered on 403 sync token expired`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        // ctag changed (triggers sync)
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")

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
        // Verify pullFull was NOT called (no fetchEventsInRange)
        coVerify(exactly = 0) { client.fetchEventsInRange(any(), any(), any()) }
    }

    @Test
    fun `etag fallback triggered on 410 sync token gone`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "gone-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // No local events (first sync or empty calendar)
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns emptyList()

        // pullFull will be called
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        // Verify etag fallback tried but fell through
        coVerify { eventsDao.getEtagsByCalendarId(calendar.id) }
        // Verify pullFull was called
        coVerify { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `falls through to pullFull when etag fetch fails`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
        coEvery { client.syncCollection(calendar.caldavUrl, "expired-token") } returns
            CalDavResult.error(403, "Sync token invalid")

        // Local has events
        coEvery { eventsDao.getEtagsByCalendarId(calendar.id) } returns listOf(
            EtagEntry(eventUrl, "etag-1")
        )

        // Server etag fetch fails
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.error(500, "Server error")

        // pullFull will be called
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Success)
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
        coVerify { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) }
    }

    // ========== Change Detection Tests ==========

    @Test
    fun `fetches events with different etags (changed)`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag", syncToken = "expired-token")
        val eventHref = "/calendars/home/event1.ics"
        val eventUrl = "https://caldav.example.com$eventHref"

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success("new-ctag")
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
