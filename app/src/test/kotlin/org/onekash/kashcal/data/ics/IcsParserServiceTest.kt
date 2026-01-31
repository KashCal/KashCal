package org.onekash.kashcal.data.ics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for IcsParserService.
 *
 * Tests cover:
 * - Basic parsing functionality
 * - CANCELLED event filtering
 * - Edge cases and malformed input handling
 * - Unicode and internationalization
 * - Validation edge cases
 *
 * Migrated from RfcIcsParserAdversarialTest.kt with adjustments for ICalParser behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsParserServiceTest {

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L
    }

    // ==================== Basic Parsing ====================

    @Test
    fun `parses valid ICS content`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Test Event", events[0].title)
        assertEquals("test@test.com", events[0].uid)
    }

    @Test
    fun `parses multiple events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event1@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event 1
            END:VEVENT
            BEGIN:VEVENT
            UID:event2@test.com
            DTSTART:20231216T140000Z
            DTEND:20231216T150000Z
            SUMMARY:Event 2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(2, events.size)
    }

    // ==================== CANCELLED Event Filtering (CRITICAL) ====================

    @Test
    fun `filters CANCELLED events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cancelled@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Cancelled Event
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            UID:confirmed@test.com
            DTSTART:20231216T140000Z
            DTEND:20231216T150000Z
            SUMMARY:Confirmed Event
            STATUS:CONFIRMED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals("Should filter out CANCELLED event", 1, events.size)
        assertEquals("Confirmed Event", events[0].title)
    }

    @Test
    fun `filters CANCELLED exception events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring@test.com
            RECURRENCE-ID:20231222T140000Z
            DTSTART:20231222T140000Z
            DTEND:20231222T150000Z
            SUMMARY:Weekly Meeting (Cancelled)
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Should have master event but filter out the cancelled exception
        assertEquals("Should have master but not cancelled exception", 1, events.size)
        assertEquals("Weekly Meeting", events[0].title)
    }

    // ==================== Structural Edge Cases ====================

    @Test
    fun `empty string returns empty list`() {
        val events = IcsParserService.parseIcsContent("", CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `whitespace only content returns empty list`() {
        val events = IcsParserService.parseIcsContent("   \n\t  ", CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `truncated file returns empty list`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:trunc@test.com\r\nDTSTART:2023121"

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue("Should handle truncated gracefully", events.isEmpty())
    }

    // ==================== Long Fields ====================

    @Test
    fun `extremely long SUMMARY is handled`() {
        val longSummary = "A".repeat(10000)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:long-summary@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:$longSummary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Title should be non-empty", events[0].title.isNotEmpty())
    }

    @Test
    fun `extremely long DESCRIPTION is handled`() {
        val longDescription = "B".repeat(100000)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:long-desc@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Normal Title
            DESCRIPTION:$longDescription
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Normal Title", events[0].title)
    }

    // ==================== Special Characters ====================

    @Test
    fun `null bytes in content are handled gracefully`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:null-byte@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event With${'\u0000'}Null
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Should not crash
        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue("Should handle null bytes gracefully", events.size <= 1)
    }

    @Test
    fun `control characters in content are handled gracefully`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:ctrl-char@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Event${'\u0007'}Bell${'\u001B'}Escape
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue("Should handle control chars gracefully", events.size <= 1)
    }

    // ==================== Line Ending Variations ====================

    @Test
    fun `mixed CRLF and LF line endings are handled`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\nBEGIN:VEVENT\r\n" +
                "UID:mixed-le@test.com\n" +
                "DTSTART:20231215T140000Z\r\n" +
                "DTEND:20231215T150000Z\n" +
                "SUMMARY:Mixed Line Endings\r\n" +
                "END:VEVENT\nEND:VCALENDAR"

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Mixed Line Endings", events[0].title)
    }

    @Test
    fun `folded lines with TAB continuation are handled`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
                "UID:tab-fold@test.com\r\n" +
                "DTSTART:20231215T140000Z\r\n" +
                "DTEND:20231215T150000Z\r\n" +
                "SUMMARY:Long title that is folded with a\r\n" +
                "\ttab character\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR"

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Title should be unfolded", events[0].title.contains("folded"))
    }

    // ==================== Unicode and Internationalization ====================

    @Test
    fun `unicode emoji in SUMMARY is preserved`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:emoji@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Team Meeting 🎉📅✨
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should contain emoji", events[0].title.contains("🎉"))
    }

    @Test
    fun `CJK characters in fields are preserved`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cjk@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:会议 - 日程表
            LOCATION:東京オフィス
            DESCRIPTION:项目计划讨论 프로젝트 미팅
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should contain Chinese", events[0].title.contains("会议"))
        assertTrue("Should contain Japanese", events[0].location?.contains("東京") == true)
        assertTrue("Should contain Korean", events[0].description?.contains("미팅") == true)
    }

    @Test
    fun `RTL text (Arabic, Hebrew) is preserved`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:rtl@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:اجتماع العمل
            LOCATION:תל אביב
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should contain Arabic", events[0].title.contains("اجتماع"))
        assertTrue("Should contain Hebrew", events[0].location?.contains("תל") == true)
    }

    // ==================== Validation Edge Cases ====================

    @Test
    fun `isValidIcs returns false for HTML content`() {
        val html = """
            <!DOCTYPE html>
            <html><body>
            <h1>Not a calendar</h1>
            </body></html>
        """.trimIndent()

        assertFalse(IcsParserService.isValidIcs(html))
    }

    @Test
    fun `isValidIcs returns false for JSON content`() {
        val json = """{"events": [{"title": "Meeting"}]}"""
        assertFalse(IcsParserService.isValidIcs(json))
    }

    @Test
    fun `isValidIcs returns false for XML content`() {
        val xml = """
            <?xml version="1.0"?>
            <calendar><event>Test</event></calendar>
        """.trimIndent()

        assertFalse(IcsParserService.isValidIcs(xml))
    }

    @Test
    fun `isValidIcs returns true for valid ICS with VEVENT`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertTrue(IcsParserService.isValidIcs(ics))
    }

    @Test
    fun `isValidIcs returns true for valid ICS with VTODO`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:todo@test.com
            SUMMARY:Todo Item
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        assertTrue(IcsParserService.isValidIcs(ics))
    }

    // ==================== Calendar Name Extraction ====================

    @Test
    fun `getCalendarName extracts X-WR-CALNAME`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-CALNAME:My Calendar
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val name = IcsParserService.getCalendarName(ics)
        assertEquals("My Calendar", name)
    }

    @Test
    fun `getCalendarName falls back to PRODID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Google Inc//Calendar//EN
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val name = IcsParserService.getCalendarName(ics)
        assertEquals("-//Google Inc//Calendar//EN", name)
    }

    @Test
    fun `getCalendarName returns null when missing`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val name = IcsParserService.getCalendarName(ics)
        assertNull(name)
    }

    // ==================== Real-World Format Examples ====================

    @Test
    fun `Google Calendar export with extra whitespace`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            BEGIN:VEVENT
            UID:google-123@google.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Google Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Google Event", events[0].title)
    }

    @Test
    fun `Outlook style with BOM marker`() {
        // UTF-8 BOM at start of file
        val bom = "\uFEFF"
        val ics = bom + """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:outlook@outlook.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Outlook Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // BOM should be handled gracefully
        assertTrue("Should handle BOM gracefully", events.size <= 1)
    }

    @Test
    fun `Apple iCloud style with X- properties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-CALNAME:My Calendar
            X-APPLE-CALENDAR-COLOR:#FF2968
            BEGIN:VEVENT
            UID:apple@icloud.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Apple Event
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Apple Event", events[0].title)
    }

    // ==================== All-Day Events ====================

    @Test
    fun `all-day event is parsed correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:allday@test.com
            DTSTART;VALUE=DATE:20231225
            DTEND;VALUE=DATE:20231226
            SUMMARY:Christmas Day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should be all-day", events[0].isAllDay)
        assertEquals("Christmas Day", events[0].title)
    }

    // ==================== Source Tracking ====================

    @Test
    fun `caldavUrl contains subscription source prefix`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:source-test@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Source Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue(
            "caldavUrl should contain subscription prefix",
            events[0].caldavUrl?.startsWith("ics_subscription:") == true
        )
        assertTrue(
            "caldavUrl should contain subscription ID",
            events[0].caldavUrl?.contains(":$SUBSCRIPTION_ID:") == true
        )
        assertTrue(
            "caldavUrl should contain UID",
            events[0].caldavUrl?.contains(":source-test@test.com") == true
        )
    }
}
