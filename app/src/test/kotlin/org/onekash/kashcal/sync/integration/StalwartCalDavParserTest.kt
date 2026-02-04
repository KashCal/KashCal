package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Parser tests for Stalwart CalDAV responses.
 *
 * Stalwart is a modern Rust-based mail server with CalDAV support.
 * It returns multiple propstat elements per RFC 4918 - one with 200 for
 * supported properties, one with 404 for missing optional properties.
 *
 * Bug: CalDavXmlParser.extractCalendars() tracked statusOk globally,
 * so a 404 propstat for calendar-color caused the entire calendar to be rejected.
 *
 * Fix: Track status per-propstat, use only the propstat containing resourcetype.
 *
 * Reference: GitHub Issue stalwartlabs/mail-server#1591
 *
 * Run: ./gradlew app:testDebugUnitTest --tests "*StalwartCalDavParserTest*"
 */
class StalwartCalDavParserTest {

    private lateinit var xmlParser: CalDavXmlParser
    private lateinit var quirks: DefaultQuirks

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        xmlParser = CalDavXmlParser()
        quirks = DefaultQuirks("http://localhost:8085")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Baseline Tests ====================

    @Test
    fun `single propstat calendar detected correctly`() {
        val xml = loadFixture("03_calendar_list_single_propstat.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals("Should find 1 calendar", 1, calendars.size)
        assertEquals("Test Calendar", calendars[0].displayName)
        assertEquals("/dav/cal/admin/test-calendar/", calendars[0].href)
        assertEquals("#0082C9FF", calendars[0].color)
        assertEquals("stalwart-ctag-single-123", calendars[0].ctag)
    }

    // ==================== Bug Reproduction Tests ====================

    @Test
    fun `multiple propstat with 404 detects valid calendar`() {
        // This test documents the bug (fails pre-fix) and verifies the fix (passes post-fix)
        val xml = loadFixture("03_calendar_list_multiple_propstat.xml")

        val calendars = xmlParser.extractCalendars(xml)

        // PRE-FIX: This returns 0 calendars (bug)
        // POST-FIX: This should return 1 calendar
        assertEquals(
            "Calendar should be detected even with 404 propstat for optional properties",
            1,
            calendars.size
        )
        assertEquals("Test Calendar", calendars[0].displayName)
        assertEquals("/dav/cal/admin/test-calendar/", calendars[0].href)
        assertNull("Color should be null (404 propstat)", calendars[0].color)
        assertEquals("stalwart-ctag-multi-456", calendars[0].ctag)
    }

    @Test
    fun `all optional properties 404 still detects calendar`() {
        val xml = loadFixture("03_calendar_list_all_optional_404.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(
            "Calendar should be detected even when all optional properties return 404",
            1,
            calendars.size
        )
        assertEquals("Minimal Calendar", calendars[0].displayName)
        assertEquals("/dav/cal/admin/minimal-calendar/", calendars[0].href)
    }

    // ==================== RFC 4918 Edge Case Tests ====================

    @Test
    fun `propstat without status element treated as OK (RFC 4918 default)`() {
        // RFC 4918 §9.2.1: If status is omitted, it defaults to 200 OK
        val xml = loadFixture("03_calendar_list_no_status.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(
            "Calendar should be detected when propstat has no status element (RFC default = OK)",
            1,
            calendars.size
        )
        assertEquals("No Status Calendar", calendars[0].displayName)
        assertEquals("/dav/cal/admin/no-status-calendar/", calendars[0].href)
        assertEquals("#FF5733FF", calendars[0].color)
        assertEquals("stalwart-ctag-nostatus-111", calendars[0].ctag)
    }

    @Test
    fun `resourcetype in 404 propstat NOT detected as calendar`() {
        // Edge case: If resourcetype itself is in a 404 propstat, it's NOT a calendar
        val xml = loadFixture("03_calendar_list_resourcetype_404.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(
            "Resourcetype in 404 propstat should NOT be detected as calendar",
            0,
            calendars.size
        )
    }

    @Test
    fun `namespace variants all detected as calendars`() {
        // Test C:calendar, A:calendar, and bare calendar element
        val xml = loadFixture("03_calendar_list_namespace_variants.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(
            "All namespace variants should be detected as calendars",
            3,
            calendars.size
        )

        val names = calendars.map { it.displayName }.toSet()
        assertTrue("C: prefix calendar should be detected", names.contains("CalDAV Prefix Calendar"))
        assertTrue("A: prefix calendar should be detected", names.contains("Apple Prefix Calendar"))
        assertTrue("Bare calendar element should be detected", names.contains("Bare Calendar Element"))
    }

    // ==================== Discovery Parsing Tests ====================

    @Test
    fun `extract principal URL from Stalwart response`() {
        val xml = loadFixture("01_current_user_principal.xml")

        val principal = xmlParser.extractPrincipalUrl(xml)

        assertEquals("/dav/principal/admin/", principal)
    }

    @Test
    fun `extract calendar home URL from Stalwart response`() {
        val xml = loadFixture("02_calendar_home_set.xml")

        val home = xmlParser.extractCalendarHomeUrl(xml)

        assertEquals("/dav/cal/admin/", home)
    }

    // ==================== Sync Collection Tests ====================

    @Test
    fun `extract sync collection data from Stalwart response`() {
        val xml = loadFixture("04_sync_collection.xml")

        val data = xmlParser.extractSyncCollectionData(xml)

        // Check sync token
        assertEquals(
            "http://stalwart.example.com/ns/sync/new-token-789",
            data.syncToken
        )

        // Check changed items (2 events with etags)
        assertEquals("Should find 2 changed items", 2, data.changedItems.size)

        val changedHrefs = data.changedItems.map { it.first }
        assertTrue(changedHrefs.contains("/dav/cal/admin/test-calendar/event-changed.ics"))
        assertTrue(changedHrefs.contains("/dav/cal/admin/test-calendar/event-new.ics"))

        // Check deleted items (1 event with 404)
        assertEquals("Should find 1 deleted item", 1, data.deletedHrefs.size)
        assertEquals(
            "/dav/cal/admin/test-calendar/event-deleted.ics",
            data.deletedHrefs[0]
        )
    }

    @Test
    fun `extract sync token from Stalwart response`() {
        val xml = loadFixture("04_sync_collection.xml")

        val token = xmlParser.extractSyncToken(xml)

        assertEquals("http://stalwart.example.com/ns/sync/new-token-789", token)
    }

    @Test
    fun `extract changed items from Stalwart sync response`() {
        val xml = loadFixture("04_sync_collection.xml")

        val changed = xmlParser.extractChangedItems(xml)

        assertEquals("Should find 2 changed .ics files", 2, changed.size)

        // Verify etags are extracted
        val etagMap = changed.associate { it.first to it.second }
        assertEquals(
            "stalwart-etag-changed-abc123",
            etagMap["/dav/cal/admin/test-calendar/event-changed.ics"]
        )
        assertEquals(
            "stalwart-etag-new-def456",
            etagMap["/dav/cal/admin/test-calendar/event-new.ics"]
        )
    }

    @Test
    fun `extract deleted hrefs from Stalwart sync response`() {
        val xml = loadFixture("04_sync_collection.xml")

        val deleted = xmlParser.extractDeletedHrefs(xml)

        assertEquals("Should find 1 deleted href", 1, deleted.size)
        assertEquals("/dav/cal/admin/test-calendar/event-deleted.ics", deleted[0])
    }

    // ==================== Multiget Tests ====================

    @Test
    fun `extract iCal data from Stalwart multiget response`() {
        val xml = loadFixture("05_multiget_response.xml")

        val events = xmlParser.extractICalData(xml)

        assertEquals("Should find 2 events", 2, events.size)

        // Check first event
        val meeting = events.find { it.href.contains("meeting.ics") }
        assertNotNull("Meeting event should be found", meeting)
        assertEquals("stalwart-etag-meeting-111", meeting!!.etag)
        assertTrue(meeting.icalData.contains("SUMMARY:Team Meeting"))
        assertTrue(meeting.icalData.contains("UID:stalwart-meeting-uid-001"))

        // Check second event (recurring)
        val recurring = events.find { it.href.contains("recurring.ics") }
        assertNotNull("Recurring event should be found", recurring)
        assertEquals("stalwart-etag-recurring-222", recurring!!.etag)
        assertTrue(recurring.icalData.contains("RRULE:FREQ=WEEKLY;COUNT=10"))
        assertTrue(recurring.icalData.contains("SUMMARY:Weekly Standup"))
    }

    // ==================== DefaultQuirks Integration ====================

    @Test
    fun `DefaultQuirks extracts calendars from Stalwart response`() {
        val xml = loadFixture("03_calendar_list_multiple_propstat.xml")

        val calendars = quirks.extractCalendars(xml, "http://localhost:8085")

        assertEquals("DefaultQuirks should find 1 calendar", 1, calendars.size)
        assertEquals("Test Calendar", calendars[0].displayName)
    }

    @Test
    fun `DefaultQuirks extracts calendars from no-status response`() {
        val xml = loadFixture("03_calendar_list_no_status.xml")

        val calendars = quirks.extractCalendars(xml, "http://localhost:8085")

        assertEquals("DefaultQuirks should find 1 calendar (no status)", 1, calendars.size)
        assertEquals("No Status Calendar", calendars[0].displayName)
    }

    @Test
    fun `DefaultQuirks rejects resourcetype in 404 propstat`() {
        val xml = loadFixture("03_calendar_list_resourcetype_404.xml")

        val calendars = quirks.extractCalendars(xml, "http://localhost:8085")

        assertEquals(
            "DefaultQuirks should NOT detect calendar when resourcetype has 404",
            0,
            calendars.size
        )
    }

    // ==================== Helper Methods ====================

    private fun loadFixture(filename: String): String {
        return javaClass.classLoader!!
            .getResourceAsStream("caldav/stalwart/$filename")!!
            .bufferedReader()
            .readText()
    }
}
