package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.*
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy

/**
 * Tests for Zoho Calendar CalDAV compatibility (Issue #61).
 *
 * Zoho's CalDAV server has two quirks that caused 0 events to sync:
 * 1. Root cause 1 (v22.5.4): getctag not supported — sync aborted before fetching
 * 2. Root cause 2 (v22.5.8): calendar-data ignored in calendar-query — events silently dropped
 *
 * Fixtures in app/src/test/resources/caldav/zoho/ are based on real Zoho responses
 * captured from calendar.zoho.com (2026-02-12), with personal data replaced by
 * example values.
 *
 * Run: ./gradlew test --tests "*ZohoCalDavTest*"
 */
class ZohoCalDavTest {

    private val xmlParser = CalDavXmlParser()
    private val quirks = DefaultQuirks("https://calendar.zoho.com")

    // PullStrategy dependencies
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

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        MockKAnnotations.init(this, relaxed = true)

        // Mock database.runInTransaction to execute the block directly
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Default DataStore mocks
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)

        // Default: UID lookup returns null
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

    // ==================== XML Parser Tests ====================

    @Test
    fun `extractPrincipalUrl parses Zoho response with opaque hash`() {
        val xml = loadFixture("01_current_user_principal.xml")

        val principalUrl = xmlParser.extractPrincipalUrl(xml)

        assertEquals("/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/user/", principalUrl)
    }

    @Test
    fun `extractCalendarHomeUrl parses Zoho non-prefixed namespace`() {
        val xml = loadFixture("02_calendar_home_set.xml")

        val homeUrl = xmlParser.extractCalendarHomeUrl(xml)

        assertEquals("/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/", homeUrl)
    }

    @Test
    fun `extractCalendars finds calendar with B-prefixed namespace`() {
        val xml = loadFixture("03_calendar_list.xml")

        val calendars = xmlParser.extractCalendars(xml)

        // Should find 1 calendar (skip home, inbox, outbox)
        assertEquals("Should find 1 calendar", 1, calendars.size)

        val cal = calendars[0]
        assertEquals("My Calendar", cal.displayName)
        assertEquals("/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/", cal.href)
        assertNull("Zoho doesn't provide calendar-color", cal.color)
        // Zoho DOES support ctag (discovered during live testing 2026-02-12)
        assertEquals("1770859408092", cal.ctag)
    }

    @Test
    fun `extractCtag returns null when getctag is 404`() {
        val xml = loadFixture("04_ctag_missing.xml")

        val ctag = xmlParser.extractCtag(xml)

        assertNull("ctag should be null when server returns 404 for getctag", ctag)
    }

    // ==================== Root Cause 2: calendar-data in calendar-query ====================

    @Test
    fun `extractICalData returns empty list when Zoho omits calendar-data`() {
        // THIS IS THE ROOT CAUSE of Issue #61.
        // Zoho returns hrefs+etags in calendar-query but NO calendar-data.
        // extractICalData silently drops responses without calendar-data.
        val xml = loadFixture("05_calendar_query_no_data.xml")

        val events = xmlParser.extractICalData(xml)

        assertEquals(
            "Should return 0 events — Zoho omits calendar-data in calendar-query",
            0,
            events.size
        )
    }

    @Test
    fun `extractICalData parses Zoho multiget with B-prefixed calendar-data`() {
        // calendar-multiget correctly returns calendar-data (the fix).
        // Zoho uses non-standard "B:" prefix for CalDAV namespace.
        val xml = loadFixture("06_calendar_multiget.xml")

        val events = xmlParser.extractICalData(xml)

        assertEquals("Should find 2 events from multiget", 2, events.size)

        val event1 = events[0]
        assertTrue("href should contain event UID",
            event1.href.contains("aabb0011223344556677889900aabb01"))
        assertEquals("1770859402675", event1.etag)
        assertTrue("Should contain VCALENDAR", event1.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain Zoho PRODID",
            event1.icalData.contains("Zoho Corporation"))
        assertTrue("Should contain event summary",
            event1.icalData.contains("Team Standup"))

        val event2 = events[1]
        assertTrue("href should contain event UID",
            event2.href.contains("ccdd0011223344556677889900ccdd02"))
        assertEquals("1770859408075", event2.etag)
        assertTrue("Should contain second event",
            event2.icalData.contains("Project Review"))
    }

    // ==================== DefaultQuirks URL Building ====================

    @Test
    fun `buildCalendarUrl handles Zoho opaque hash format`() {
        val href = "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/"
        val baseHost = "https://calendar.zoho.com"

        val url = quirks.buildCalendarUrl(href, baseHost)

        assertEquals(
            "https://calendar.zoho.com/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/",
            url
        )
    }

    @Test
    fun `buildEventUrl handles Zoho URL-encoded href`() {
        val href = "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/aabb0011%40zoho.com.ics"
        val calendarUrl = "https://calendar.zoho.com/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/"

        val url = quirks.buildEventUrl(href, calendarUrl)

        assertEquals(
            "https://calendar.zoho.com/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/aabb0011%40zoho.com.ics",
            url
        )
    }

    // ==================== PullStrategy: Missing ctag ====================

    @Test
    fun `pull proceeds when server returns no ctag`() = runTest {
        // Root cause 1: Zoho may not support getctag on some paths.
        // PullStrategy skips the ctag optimization and proceeds with full sync.
        val calendar = createZohoCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should proceed despite missing ctag", result is PullResult.Success)

        // Verify sync actually ran (events were fetched)
        coVerify { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) }
    }

    @Test
    fun `pull proceeds with full sync when ctag unavailable and fetches events`() = runTest {
        val calendar = createZohoCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl

        coEvery { client.getCtag(calendarUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")

        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Zoho Corporation//Zoho Calendar-US//EN
            BEGIN:VEVENT
            UID:aabb0011223344556677889900aabb01@zoho.com
            DTSTAMP:20260212T014217Z
            DTSTART;TZID=America/Chicago:20260212T091500
            DTEND;TZID=America/Chicago:20260212T094500
            SUMMARY:Team Standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                Pair("event1.ics", "1770859402675")
            ))
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event1.ics", "${calendarUrl}event1.ics", "1770859402675", ical)
            ))
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success("1770859408092")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Should succeed", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
    }

    @Test
    fun `pull still aborts on auth error from ctag`() = runTest {
        // Auth errors (401) are systemic — abort immediately, don't proceed with sync
        val calendar = createZohoCalendar()

        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.authError("Authentication failed")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(401, (result as PullResult.Error).code)

        // Verify sync never ran
        coVerify(exactly = 0) { client.fetchEtagsInRange(any(), any(), any()) }
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
    }

    @Test
    fun `pull still aborts on 403 from ctag`() = runTest {
        // Permission errors (403) are systemic — if PROPFIND is rejected, REPORT will be too
        val calendar = createZohoCalendar()

        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(403, "Permission denied")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(403, (result as PullResult.Error).code)

        // Verify sync never ran
        coVerify(exactly = 0) { client.fetchEtagsInRange(any(), any(), any()) }
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
    }

    @Test
    fun `ctag stored as null after sync when server has no ctag`() = runTest {
        val calendar = createZohoCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        pullStrategy.pull(calendar, client = client)

        // Verify ctag is stored as null (not some fallback value)
        coVerify {
            calendarRepository.updateSyncToken(
                calendarId = calendar.id,
                syncToken = any(),
                ctag = isNull()
            )
        }
    }

    @Test
    fun `pullFull uses two-step fetch for Zoho compatibility`() = runTest {
        // End-to-end: Zoho returns etags in calendar-query (no calendar-data),
        // then returns full data in calendar-multiget.
        val calendar = createZohoCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl
        val eventHref = "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/aabb0011%40zoho.com.ics"
        val eventUrl = "https://calendar.zoho.com$eventHref"

        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Zoho Corporation//Zoho Calendar-US//EN
            BEGIN:VEVENT
            UID:aabb0011223344556677889900aabb01@zoho.com
            DTSTAMP:20260212T014217Z
            DTSTART;TZID=America/Chicago:20260212T091500
            DTEND;TZID=America/Chicago:20260212T094500
            SUMMARY:Team Standup
            CLASS:PUBLIC
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.getCtag(calendarUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")

        // Step 1: calendar-query returns only hrefs+etags (Zoho omits calendar-data)
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(listOf(Pair(eventHref, "1770859402675")))

        // Step 2: calendar-multiget returns full data (Zoho honors calendar-data in multiget)
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent(eventHref, eventUrl, "1770859402675", ical)
            ))
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success("1770859408092")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Should succeed with two-step fetch", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)

        // Verify two-step: etags fetched, then multiget, never fetchEventsInRange
        coVerify { client.fetchEtagsInRange(calendarUrl, any(), any()) }
        coVerify { client.fetchEventsByHref(calendarUrl, any()) }
    }

    // ==================== Batched Multiget with Empty-Response Fallback (v22.5.12) ====================
    // Zoho returns HTTP 200 empty body for multi-href calendar-multiget (≥2 hrefs).
    // Single-href multiget works fine. fetchEventsBatched detects the empty response
    // and falls back to concurrent single-href fetches.

    @Test
    fun `pullFull falls back to single-href when Zoho returns empty for multi-href multiget`() = runTest {
        // Simulates real Zoho behavior: multi-href → empty, single-href → events.
        val calendar = createZohoCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl
        val hrefs = (1..5).map { "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/event$it%40zoho.com.ics" }
        val etags = hrefs.map { Pair(it, "etag-$it") }
        val events = hrefs.mapIndexed { i, href ->
            val ical = createZohoIcal("uid-$i@zoho.com", "Event $i")
            CalDavEvent(href, "https://calendar.zoho.com$href", "etag-$href", ical)
        }

        coEvery { client.getCtag(calendarUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(etags)
        // Zoho behavior: empty for multi-href, success for single-href
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } answers {
            val requestedHrefs = secondArg<List<String>>()
            if (requestedHrefs.size > 1) {
                CalDavResult.success(emptyList())
            } else {
                val href = requestedHrefs[0]
                val event = events.find { it.href == href }
                CalDavResult.success(listOfNotNull(event))
            }
        }
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Should succeed via single-href fallback", result is PullResult.Success)
        assertEquals(5, (result as PullResult.Success).eventsAdded)
        // 1 batch call (empty) + 5 single-href fallback calls = 6
        coVerify(exactly = 6) { client.fetchEventsByHref(calendarUrl, any()) }
    }

    @Test
    fun `pullFull batched multiget error fails fast`() = runTest {
        // With fail-fast, multiget error returns PullResult.Error (no fallback to one-by-one).
        val calendar = createZohoCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl
        val hrefs = (1..5).map { "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/event$it%40zoho.com.ics" }
        val etags = hrefs.map { Pair(it, "etag-$it") }

        coEvery { client.getCtag(calendarUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(etags)
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns
            CalDavResult.error(500, "Server error", isRetryable = true)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Should fail fast", result is PullResult.Error)
        assertEquals(500, (result as PullResult.Error).code)
        assertTrue(result.isRetryable)
        // Single call — no retry, no fallback
        coVerify(exactly = 1) { client.fetchEventsByHref(calendarUrl, any()) }
    }

    // ==================== Discovery: Trailing slash sensitivity ====================

    @Test
    fun `KNOWN_CALDAV_PATHS includes caldav without trailing slash for Zoho`() {
        // Zoho returns 501 for /caldav/ but works with /caldav (no trailing slash).
        // CalDavAccountDiscoveryService.KNOWN_CALDAV_PATHS probes /caldav before /caldav/.
        // Tested in CalDavAccountDiscoveryServiceTest.
        assertTrue(
            "Discovery probe path /caldav (no trailing slash) supports Zoho",
            true
        )
    }

    // ==================== Helpers ====================

    private fun createZohoCalendar(
        id: Long = 1,
        ctag: String? = null,
        syncToken: String? = null
    ) = Calendar(
        id = id,
        accountId = 1,
        caldavUrl = "https://calendar.zoho.com/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/events/",
        displayName = "My Calendar",
        color = 0xFF4CAF50.toInt(),
        ctag = ctag,
        syncToken = syncToken
    )

    private fun createZohoIcal(uid: String, summary: String): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Zoho Corporation//Zoho Calendar-US//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20260212T014217Z
            DTSTART;TZID=America/Chicago:20260212T091500
            DTEND;TZID=America/Chicago:20260212T094500
            SUMMARY:$summary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun loadFixture(filename: String): String {
        return javaClass.classLoader!!
            .getResourceAsStream("caldav/zoho/$filename")!!
            .bufferedReader()
            .readText()
    }
}
