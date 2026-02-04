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
 * Parser tests for Open-Xchange CalDAV responses (mailbox.org).
 *
 * Open-Xchange is a popular groupware platform used by:
 * - mailbox.org
 * - 1&1 / IONOS
 * - Various enterprise deployments
 *
 * Key differences from SabreDAV (Nextcloud/Baikal):
 * - Uses uppercase namespace prefixes (D:, CAL:, CARD:)
 * - Calendar home URL often equals principal URL
 * - Uses OX-specific sync token format
 *
 * Run: ./gradlew test --tests "*OpenXchangeCalDavParserTest*"
 *
 * Related issue: https://github.com/KashCal/KashCal/issues/38
 */
class OpenXchangeCalDavParserTest {

    private lateinit var xmlParser: CalDavXmlParser
    private lateinit var quirks: DefaultQuirks

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        xmlParser = CalDavXmlParser()
        quirks = DefaultQuirks("https://dav.mailbox.org")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Principal Discovery ====================

    @Test
    fun `parse principal URL from Open-Xchange response`() {
        val xml = loadFixture("01_current_user_principal.xml")

        val principalUrl = xmlParser.extractPrincipalUrl(xml)

        assertNotNull("Principal URL should be extracted", principalUrl)
        assertEquals("/caldav/testuser@mailbox.org/", principalUrl)
    }

    @Test
    fun `parse principal URL handles uppercase DAV namespace prefix`() {
        // Open-Xchange uses D: instead of d: for DAV namespace
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/caldav/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:current-user-principal>
                                <D:href>/caldav/user@example.org/</D:href>
                            </D:current-user-principal>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val principalUrl = xmlParser.extractPrincipalUrl(xml)

        assertNotNull(principalUrl)
        assertEquals("/caldav/user@example.org/", principalUrl)
    }

    // ==================== Calendar Home Discovery ====================

    @Test
    fun `parse calendar home URL from Open-Xchange response`() {
        val xml = loadFixture("02_calendar_home_set.xml")

        val homeUrl = xmlParser.extractCalendarHomeUrl(xml)

        assertNotNull("Calendar home URL should be extracted", homeUrl)
        assertEquals("/caldav/testuser@mailbox.org/", homeUrl)
    }

    @Test
    fun `parse calendar home URL handles CAL namespace prefix`() {
        // Open-Xchange uses CAL: for caldav namespace
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CAL="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/caldav/user/</D:href>
                    <D:propstat>
                        <D:prop>
                            <CAL:calendar-home-set>
                                <D:href>/caldav/user/calendars/</D:href>
                            </CAL:calendar-home-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val homeUrl = xmlParser.extractCalendarHomeUrl(xml)

        assertNotNull(homeUrl)
        assertEquals("/caldav/user/calendars/", homeUrl)
    }

    // ==================== Calendar List ====================

    @Test
    fun `parse calendar list from Open-Xchange response`() {
        val xml = loadFixture("03_calendar_list.xml")

        val calendars = xmlParser.extractCalendars(xml)

        // Should find 3 calendars (Kalender, Arbeit, German Holidays)
        // Should NOT include the calendar home collection
        assertEquals("Should find 3 calendars", 3, calendars.size)

        // Verify first calendar (Kalender)
        val kalender = calendars.find { it.displayName == "Kalender" }
        assertNotNull("Should find Kalender calendar", kalender)
        assertEquals("/caldav/testuser@mailbox.org/Kalender/", kalender!!.href)
        assertEquals("#0E61B9FF", kalender.color)
        assertEquals("1706889600", kalender.ctag)
        assertFalse("Kalender should be writable", kalender.isReadOnly)

        // Verify second calendar (Arbeit)
        val arbeit = calendars.find { it.displayName == "Arbeit" }
        assertNotNull("Should find Arbeit calendar", arbeit)
        assertEquals("/caldav/testuser@mailbox.org/Arbeit/", arbeit!!.href)
        assertFalse("Arbeit should be writable", arbeit.isReadOnly)

        // Verify shared calendar (read-only)
        val holidays = calendars.find { it.displayName == "German Holidays" }
        assertNotNull("Should find German Holidays calendar", holidays)
        assertTrue("Shared calendar should be read-only", holidays!!.isReadOnly)
    }

    @Test
    fun `parse calendar list excludes non-calendar collections`() {
        val xml = loadFixture("03_calendar_list.xml")

        val calendars = xmlParser.extractCalendars(xml)

        // Calendar home collection should NOT be included
        val home = calendars.find { it.href == "/caldav/testuser@mailbox.org/" }
        assertNull("Calendar home should not be detected as calendar", home)
    }

    @Test
    fun `parse calendar list handles APPLE calendar-color namespace`() {
        // Open-Xchange uses APPLE: namespace for calendar-color
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CAL="urn:ietf:params:xml:ns:caldav" xmlns:APPLE="http://apple.com/ns/ical/">
                <D:response>
                    <D:href>/caldav/user/calendar/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:resourcetype>
                                <D:collection/>
                                <CAL:calendar/>
                            </D:resourcetype>
                            <D:displayname>My Calendar</D:displayname>
                            <APPLE:calendar-color>#FF5733FF</APPLE:calendar-color>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(1, calendars.size)
        assertEquals("#FF5733FF", calendars[0].color)
    }

    // ==================== Sync Collection ====================

    @Test
    fun `parse sync-collection response from Open-Xchange`() {
        val xml = loadFixture("04_sync_collection.xml")

        val syncData = xmlParser.extractSyncCollectionData(xml)

        // Verify sync token
        assertNotNull("Sync token should be extracted", syncData.syncToken)
        assertEquals("http://www.open-xchange.com/sync/1706889999", syncData.syncToken)

        // Verify changed items (2 events)
        assertEquals("Should have 2 changed items", 2, syncData.changedItems.size)

        val event1 = syncData.changedItems.find { it.first.contains("event-123") }
        assertNotNull(event1)
        assertEquals("ox-etag-abc123", event1!!.second)

        val event2 = syncData.changedItems.find { it.first.contains("event-456") }
        assertNotNull(event2)
        assertEquals("ox-etag-def456", event2!!.second)

        // Verify deleted items (1 deleted)
        assertEquals("Should have 1 deleted item", 1, syncData.deletedHrefs.size)
        assertTrue(syncData.deletedHrefs[0].contains("deleted-event"))
    }

    @Test
    fun `parse sync token from Open-Xchange response`() {
        val xml = loadFixture("04_sync_collection.xml")

        val syncToken = xmlParser.extractSyncToken(xml)

        assertNotNull(syncToken)
        // OX uses URL-style sync tokens
        assertTrue(syncToken!!.startsWith("http://www.open-xchange.com/sync/"))
    }

    // ==================== Multiget Response ====================

    @Test
    fun `parse multiget response from Open-Xchange`() {
        val xml = loadFixture("05_multiget_response.xml")

        val events = xmlParser.extractICalData(xml)

        assertEquals("Should extract 2 events", 2, events.size)

        // Verify simple event
        val simpleEvent = events.find { it.href.contains("event-123") }
        assertNotNull(simpleEvent)
        assertEquals("ox-etag-abc123", simpleEvent!!.etag)
        assertTrue(simpleEvent.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue(simpleEvent.icalData.contains("UID:event-123@mailbox.org"))
        assertTrue(simpleEvent.icalData.contains("SUMMARY:Team Meeting"))

        // Verify recurring event with exception
        val recurringEvent = events.find { it.href.contains("recurring-456") }
        assertNotNull(recurringEvent)
        assertTrue(recurringEvent!!.icalData.contains("RRULE:FREQ=WEEKLY"))
        assertTrue(recurringEvent.icalData.contains("RECURRENCE-ID:"))
        assertTrue(recurringEvent.icalData.contains("Weekly Review (Rescheduled)"))
    }

    @Test
    fun `parse multiget handles OX PRODID`() {
        val xml = loadFixture("05_multiget_response.xml")

        val events = xmlParser.extractICalData(xml)

        assertTrue(events.isNotEmpty())
        // OX uses its own PRODID
        assertTrue(events[0].icalData.contains("PRODID:-//Open-Xchange//"))
    }

    // ==================== DefaultQuirks Integration ====================

    @Test
    fun `DefaultQuirks extracts principal from Open-Xchange response`() {
        val xml = loadFixture("01_current_user_principal.xml")

        val principalUrl = quirks.extractPrincipalUrl(xml)

        assertNotNull(principalUrl)
        assertEquals("/caldav/testuser@mailbox.org/", principalUrl)
    }

    @Test
    fun `DefaultQuirks extracts calendar home from Open-Xchange response`() {
        val xml = loadFixture("02_calendar_home_set.xml")

        val homeUrl = quirks.extractCalendarHomeUrl(xml)

        assertNotNull(homeUrl)
        assertEquals("/caldav/testuser@mailbox.org/", homeUrl)
    }

    @Test
    fun `DefaultQuirks extracts calendars from Open-Xchange response`() {
        val xml = loadFixture("03_calendar_list.xml")

        val calendars = quirks.extractCalendars(xml, "https://dav.mailbox.org")

        assertEquals(3, calendars.size)
    }

    @Test
    fun `DefaultQuirks builds correct calendar URL for Open-Xchange`() {
        val href = "/caldav/testuser@mailbox.org/Kalender/"
        val baseHost = "https://dav.mailbox.org"

        val url = quirks.buildCalendarUrl(href, baseHost)

        assertEquals("https://dav.mailbox.org/caldav/testuser@mailbox.org/Kalender/", url)
    }

    @Test
    fun `DefaultQuirks builds correct event URL for Open-Xchange`() {
        val href = "/caldav/testuser@mailbox.org/Kalender/event-123.ics"
        val calendarUrl = "https://dav.mailbox.org/caldav/testuser@mailbox.org/Kalender/"

        val url = quirks.buildEventUrl(href, calendarUrl)

        assertEquals("https://dav.mailbox.org/caldav/testuser@mailbox.org/Kalender/event-123.ics", url)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles empty response gracefully`() {
        val principalUrl = xmlParser.extractPrincipalUrl("")
        val homeUrl = xmlParser.extractCalendarHomeUrl("")
        val calendars = xmlParser.extractCalendars("")

        assertNull(principalUrl)
        assertNull(homeUrl)
        assertTrue(calendars.isEmpty())
    }

    @Test
    fun `handles malformed XML gracefully`() {
        val badXml = "<not-valid-xml"

        val principalUrl = xmlParser.extractPrincipalUrl(badXml)
        val homeUrl = xmlParser.extractCalendarHomeUrl(badXml)
        val calendars = xmlParser.extractCalendars(badXml)

        assertNull(principalUrl)
        assertNull(homeUrl)
        assertTrue(calendars.isEmpty())
    }

    @Test
    fun `handles missing elements gracefully`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/caldav/</D:href>
                    <D:propstat>
                        <D:prop>
                            <!-- No current-user-principal -->
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val principalUrl = xmlParser.extractPrincipalUrl(xml)

        assertNull(principalUrl)
    }

    // ==================== Helper Methods ====================

    private fun loadFixture(filename: String): String {
        return javaClass.classLoader!!
            .getResourceAsStream("caldav/openxchange/$filename")!!
            .bufferedReader()
            .readText()
    }
}
