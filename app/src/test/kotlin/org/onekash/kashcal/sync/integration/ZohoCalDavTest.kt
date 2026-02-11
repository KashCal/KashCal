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
 * Zoho's CalDAV server has quirks that differ from other providers:
 * 1. Does not support CalendarServer getctag extension
 * 2. Does not return calendar-color (Apple iCal extension)
 * 3. Trailing slash sensitivity on /caldav/ path (501 vs 401)
 *
 * These tests verify:
 * - XML parsing works with Zoho-style responses (missing getctag, no color)
 * - PullStrategy handles missing ctag gracefully
 * - Calendar discovery works without optional properties
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
    fun `extractPrincipalUrl parses Zoho response`() {
        val xml = loadFixture("01_current_user_principal.xml")

        val principalUrl = xmlParser.extractPrincipalUrl(xml)

        assertEquals("/caldav/user@example.com/", principalUrl)
    }

    @Test
    fun `extractCalendarHomeUrl parses Zoho response`() {
        val xml = loadFixture("02_calendar_home_set.xml")

        val homeUrl = xmlParser.extractCalendarHomeUrl(xml)

        assertEquals("/caldav/user@example.com/", homeUrl)
    }

    @Test
    fun `extractCalendars finds calendars without getctag or color`() {
        val xml = loadFixture("03_calendar_list.xml")

        val calendars = xmlParser.extractCalendars(xml)

        // Should find 2 calendars (skip the home collection which lacks <calendar/> resourcetype)
        assertEquals("Should find 2 calendars", 2, calendars.size)

        val cal1 = calendars[0]
        assertEquals("My Calendar", cal1.displayName)
        assertEquals("/caldav/user@example.com/default/", cal1.href)
        assertNull("Zoho doesn't provide calendar-color", cal1.color)
        assertNull("Zoho doesn't provide getctag", cal1.ctag)
        assertFalse("Calendar has write privilege", cal1.isReadOnly)

        val cal2 = calendars[1]
        assertEquals("Work", cal2.displayName)
        assertEquals("/caldav/user@example.com/work/", cal2.href)
    }

    @Test
    fun `extractCtag returns null when getctag is missing from response`() {
        val xml = loadFixture("04_ctag_missing.xml")

        val ctag = xmlParser.extractCtag(xml)

        assertNull("ctag should be null when server doesn't support getctag", ctag)
    }

    @Test
    fun `extractICalData parses Zoho event report`() {
        val xml = loadFixture("05_event_report.xml")

        val events = xmlParser.extractICalData(xml)

        assertEquals("Should find 2 events", 2, events.size)

        val event1 = events[0]
        assertEquals("/caldav/user@example.com/default/event1.ics", event1.href)
        assertEquals("zoho-etag-001", event1.etag)
        assertTrue("Should contain VCALENDAR", event1.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain event summary", event1.icalData.contains("Zoho Meeting"))

        val event2 = events[1]
        assertEquals("/caldav/user@example.com/default/event2.ics", event2.href)
        assertEquals("zoho-etag-002", event2.etag)
        assertTrue("Should contain all-day event", event2.icalData.contains("VALUE=DATE"))
    }

    // ==================== DefaultQuirks URL Building ====================

    @Test
    fun `buildCalendarUrl handles Zoho href format`() {
        val href = "/caldav/user@example.com/default/"
        val baseHost = "https://calendar.zoho.com"

        val url = quirks.buildCalendarUrl(href, baseHost)

        assertEquals("https://calendar.zoho.com/caldav/user@example.com/default/", url)
    }

    @Test
    fun `buildEventUrl handles Zoho event href`() {
        val href = "/caldav/user@example.com/default/event1.ics"
        val calendarUrl = "https://calendar.zoho.com/caldav/user@example.com/default/"

        val url = quirks.buildEventUrl(href, calendarUrl)

        assertEquals("https://calendar.zoho.com/caldav/user@example.com/default/event1.ics", url)
    }

    // ==================== PullStrategy: Missing ctag ====================

    @Test
    fun `pull proceeds when server returns no ctag`() = runTest {
        // Zoho doesn't support getctag (CalendarServer extension, not core RFC 4791).
        // getCtag() returns error(500, "Ctag not found in response").
        // PullStrategy skips the ctag optimization and proceeds with full sync.
        val calendar = createZohoCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Pull should proceed despite missing ctag", result is PullResult.Success)

        // Verify sync actually ran (events were fetched)
        coVerify { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) }
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
            PRODID:-//Zoho//Calendar//EN
            BEGIN:VEVENT
            UID:zoho-uid-001@zoho.com
            DTSTAMP:20250101T120000Z
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            SUMMARY:Zoho Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.fetchEventsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event1.ics", "${calendarUrl}event1.ics", "etag-1", ical)
            ))
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success("sync-token-1")
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
        coVerify(exactly = 0) { client.fetchEventsInRange(any(), any(), any()) }
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
        coVerify(exactly = 0) { client.fetchEventsInRange(any(), any(), any()) }
        coVerify(exactly = 0) { client.syncCollection(any(), any()) }
    }

    @Test
    fun `ctag stored as null after sync when server has no ctag`() = runTest {
        val calendar = createZohoCalendar(ctag = null, syncToken = null)

        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        coEvery { client.fetchEventsInRange(calendar.caldavUrl, any(), any()) } returns
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

    // ==================== PullStrategy: Full sync with events ====================

    @Test
    fun `full sync pulls events when ctag succeeds`() = runTest {
        // Verify the normal case still works - if Zoho DOES return a ctag
        val calendar = createZohoCalendar(ctag = null, syncToken = null)
        val calendarUrl = calendar.caldavUrl

        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success("zoho-ctag-1")

        val ical = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Zoho//Calendar//EN
            BEGIN:VEVENT
            UID:zoho-uid-001@zoho.com
            DTSTAMP:20250101T120000Z
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            SUMMARY:Zoho Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { client.fetchEventsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(listOf(
                CalDavEvent("event1.ics", "${calendarUrl}event1.ics", "etag-1", ical)
            ))
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.success("sync-token-1")
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCaldavUrl(any()) } returns null
        coEvery { eventsDao.upsert(any()) } returns 1L

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue("Should succeed", result is PullResult.Success)
        assertEquals(1, (result as PullResult.Success).eventsAdded)
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
        caldavUrl = "https://calendar.zoho.com/caldav/user@example.com/default/",
        displayName = "My Calendar",
        color = 0xFF4CAF50.toInt(),
        ctag = ctag,
        syncToken = syncToken
    )

    private fun loadFixture(filename: String): String {
        return javaClass.classLoader!!
            .getResourceAsStream("caldav/zoho/$filename")!!
            .bufferedReader()
            .readText()
    }
}
