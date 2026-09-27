package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests calendar-list parsing on Radicale responses. Radicale, a CalDAV/CardDAV server written
 * in Python, splits a response into several propstats (RFC 4918), as Stalwart does: 200 for the
 * properties it has, 404 for missing optional ones. A 404 propstat must not hide the calendar.
 *
 * Run: ./gradlew app:testDebugUnitTest -Pintegration --tests "*RadicaleCalDavParserTest*"
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

    // ==================== Multiple Propstats ====================

    @Test
    fun `multiple propstat with 404 detects all calendars`() {
        // calendar-color sits in a 404 propstat; a parser that took that status for the whole
        // response would find 0 calendars.
        val xml = loadFixture("03_calendar_list_multi_propstat.xml")

        val calendars = xmlParser.extractCalendars(xml)

        assertEquals(
            "Should find 2 calendars despite 404 propstat for calendar-color",
            2,
            calendars.size
        )

        val personal = calendars.find { it.displayName == "Personal" }
        assertNotNull("Personal calendar should be found", personal)
        assertEquals("/testuser/personal/", personal!!.href)
        assertNull("Color should be null (404 propstat)", personal.color)
        assertEquals("8ab8def1234567890", personal.ctag)

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
