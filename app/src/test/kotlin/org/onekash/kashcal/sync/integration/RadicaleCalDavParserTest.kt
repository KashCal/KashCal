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
 * Parser tests for Radicale CalDAV responses.
 *
 * Radicale is a lightweight CalDAV/CardDAV server written in Python.
 * Like Stalwart, it returns multiple propstat elements per RFC 4918 -
 * 200 for supported properties, 404 for missing optional properties.
 *
 * This test verifies that the RFC 4918 multi-propstat fix also works
 * for Radicale responses.
 *
 * Run: ./gradlew app:testDebugUnitTest --tests "*RadicaleCalDavParserTest*"
 */
class RadicaleCalDavParserTest {

    private lateinit var xmlParser: CalDavXmlParser
    private lateinit var quirks: DefaultQuirks

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        xmlParser = CalDavXmlParser()
        quirks = DefaultQuirks("http://localhost:5232")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Baseline Test ====================

    @Test
    fun `single propstat calendar detected correctly`() {
        val xml = loadFixture("03_calendar_list_single_propstat.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals("Should find 1 calendar", 1, calendars.size)
        assertEquals("Personal Calendar", calendars[0].displayName)
        assertEquals("/testuser/personal/", calendars[0].href)
        assertEquals("#3366FFFF", calendars[0].color)
        assertEquals("radicale-ctag-personal-123", calendars[0].ctag)
    }

    // ==================== Bug Fix Verification ====================

    @Test
    fun `multiple propstat with 404 detects all calendars`() {
        // This test verifies the RFC 4918 multi-propstat fix works for Radicale
        // PRE-FIX: This would return 0 calendars (same bug as Stalwart)
        // POST-FIX: This should return 2 calendars
        val xml = loadFixture("03_calendar_list_multi_propstat.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(
            "Should find 2 calendars despite 404 propstat for calendar-color",
            2,
            calendars.size
        )

        // Verify Personal calendar
        val personal = calendars.find { it.displayName == "Personal" }
        assertNotNull("Personal calendar should be found", personal)
        assertEquals("/testuser/personal/", personal!!.href)
        assertNull("Color should be null (404 propstat)", personal.color)
        assertEquals("8ab8def1234567890", personal.ctag)

        // Verify Work calendar
        val work = calendars.find { it.displayName == "Work" }
        assertNotNull("Work calendar should be found", work)
        assertEquals("/testuser/work/", work!!.href)
        assertNull("Color should be null (404 propstat)", work.color)
        assertEquals("9bc9abc0987654321", work.ctag)
    }

    // ==================== DefaultQuirks Integration ====================

    @Test
    fun `DefaultQuirks extracts calendars from Radicale multi-propstat response`() {
        val xml = loadFixture("03_calendar_list_multi_propstat.xml")

        val calendars = quirks.extractCalendars(xml, "http://localhost:5232")

        assertEquals(
            "DefaultQuirks should find 2 calendars from Radicale response",
            2,
            calendars.size
        )
    }

    // ==================== Helper Methods ====================

    private fun loadFixture(filename: String): String {
        return javaClass.classLoader!!
            .getResourceAsStream("caldav/radicale/$filename")!!
            .bufferedReader()
            .readText()
    }
}
