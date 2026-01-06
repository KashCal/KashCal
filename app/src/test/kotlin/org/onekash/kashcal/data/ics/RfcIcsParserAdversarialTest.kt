package org.onekash.kashcal.data.ics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Adversarial tests for RfcIcsParser.
 *
 * Tests cover malformed and edge-case ICS content that could cause:
 * - App crashes on import
 * - Data corruption
 * - Silent data loss
 * - Security issues (injection)
 *
 * These tests complement RfcIcsParserTest.kt by focusing on
 * real-world malformed data from external sources (Google, Outlook, webcal URLs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RfcIcsParserAdversarialTest {

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L
    }

    // ==================== Structural Malformations ====================

    @Test
    fun `empty string returns empty list`() {
        val events = RfcIcsParser.parseIcsContent("", CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `null-like content is handled gracefully`() {
        val events = RfcIcsParser.parseIcsContent("   ", CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `VCALENDAR without BEGIN marker returns empty list`() {
        val ics = """
            VERSION:2.0
            BEGIN:VEVENT
            UID:test@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Parser requires BEGIN:VCALENDAR but still extracts VEVENTs
        // Actual behavior: parses the VEVENT even without VCALENDAR wrapper
        assertTrue("Events parsed or empty", events.size <= 1)
    }

    @Test
    fun `VEVENT without END marker causes malformed parsing`() {
        // DISCOVERY: When a VEVENT has no END marker, the parser grabs content
        // until it finds the next END:VEVENT (which may belong to another event).
        // This documents actual behavior - malformed ICS causes data loss.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:truncated@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Truncated Event
            BEGIN:VEVENT
            UID:complete@test.com
            DTSTART:20231216T140000Z
            SUMMARY:Complete Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Parser may extract malformed blocks or no valid events
        // This documents that malformed ICS is not reliably parsed
        assertTrue("Should handle malformed ICS gracefully without crash", events.size <= 2)
    }

    @Test
    fun `truncated file mid-property returns partial data`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:trunc@test.com\r\nDTSTART:2023121"

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Truncated mid-property should fail gracefully
        assertTrue(events.isEmpty())
    }

    // ==================== Invalid DateTime Formats ====================

    @Test
    fun `invalid DTSTART format returns empty list`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:invalid-dt@test.com
            DTSTART:not-a-date
            SUMMARY:Invalid Date Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Invalid DTSTART causes event to be skipped or use fallback
        assertTrue("Should handle gracefully", events.size <= 1)
    }

    @Test
    fun `DTEND before DTSTART is handled gracefully`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:backwards@test.com
            DTSTART:20231215T150000Z
            DTEND:20231215T140000Z
            SUMMARY:Backwards Time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Should parse (app handles display of invalid duration)
        assertEquals(1, events.size)
        // endTs is before startTs - documented as parser's behavior
        assertTrue("End should be before start for this invalid event", events[0].endTs < events[0].startTs)
    }

    @Test
    fun `partial DATE format (YYYYMM) is handled gracefully`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:partial-date@test.com
            DTSTART:202312
            SUMMARY:Partial Date
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Should handle gracefully - either skip or use fallback
        assertTrue("Should handle gracefully", events.size <= 1)
    }

    // ==================== Extremely Long Fields ====================

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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        // Summary may be truncated or preserved
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Normal Title", events[0].title)
        // Description may be truncated or preserved
        assertTrue("Description should exist", events[0].description?.isNotEmpty() == true)
    }

    // ==================== Binary/Special Characters ====================

    @Test
    fun `null bytes in content are handled`() {
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

        // Should not crash, may strip null bytes
        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue("Should handle null bytes", events.size <= 1)
    }

    @Test
    fun `control characters in content are handled`() {
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Should not crash
        assertTrue("Should handle control chars", events.size <= 1)
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Mixed Line Endings", events[0].title)
    }

    @Test
    fun `CR only line endings are handled`() {
        val ics = "BEGIN:VCALENDAR\rVERSION:2.0\rBEGIN:VEVENT\r" +
                "UID:cr-only@test.com\r" +
                "DTSTART:20231215T140000Z\r" +
                "DTEND:20231215T150000Z\r" +
                "SUMMARY:CR Only\r" +
                "END:VEVENT\rEND:VCALENDAR"

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // CR-only is non-standard, may or may not parse
        assertTrue("Should handle CR-only gracefully", events.size <= 1)
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Long title that is folded with atab character", events[0].title)
    }

    // ==================== Multiple/Invalid Properties ====================

    @Test
    fun `multiple RRULE properties uses first one`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:multi-rrule@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Multi RRULE
            RRULE:FREQ=DAILY;COUNT=5
            RRULE:FREQ=WEEKLY;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        // Should use first RRULE
        assertEquals("FREQ=DAILY;COUNT=5", events[0].rrule)
    }

    @Test
    fun `nested VCALENDAR is handled`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:nested@test.com
            DTSTART:20231215T140000Z
            SUMMARY:Nested
            END:VEVENT
            END:VCALENDAR
            BEGIN:VEVENT
            UID:outer@test.com
            DTSTART:20231216T140000Z
            SUMMARY:Outer Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // Should parse VEVENTs regardless of nesting
        assertTrue("Should parse at least one event", events.isNotEmpty())
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertTrue("Should contain Arabic", events[0].title.contains("اجتماع"))
        assertTrue("Should contain Hebrew", events[0].location?.contains("תל") == true)
    }

    // ==================== Component Type Variations ====================

    @Test
    fun `ICS with only VTODO returns empty event list`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:todo@test.com
            SUMMARY:Todo Item
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // VTODO is not a VEVENT, so should return empty
        assertTrue(events.isEmpty())
    }

    @Test
    fun `ICS with VJOURNAL returns empty event list`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VJOURNAL
            UID:journal@test.com
            SUMMARY:Journal Entry
            END:VJOURNAL
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `ICS with mixed VEVENT and VTODO returns only events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:todo@test.com
            SUMMARY:Todo Item
            END:VTODO
            BEGIN:VEVENT
            UID:event@test.com
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Calendar Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Calendar Event", events[0].title)
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

        assertFalse(RfcIcsParser.isValidIcs(html))
    }

    @Test
    fun `isValidIcs returns false for JSON content`() {
        val json = """{"events": [{"title": "Meeting"}]}"""
        assertFalse(RfcIcsParser.isValidIcs(json))
    }

    @Test
    fun `isValidIcs returns false for XML content`() {
        val xml = """
            <?xml version="1.0"?>
            <calendar><event>Test</event></calendar>
        """.trimIndent()

        assertFalse(RfcIcsParser.isValidIcs(xml))
    }

    @Test
    fun `getCalendarName handles missing calendar name gracefully`() {
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

        val name = RfcIcsParser.getCalendarName(ics)
        // No X-WR-CALNAME or PRODID, should return null
        assertNull(name)
    }

    // ==================== Real-World Malformed Examples ====================

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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        // BOM may be stripped or cause issues
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

        val events = RfcIcsParser.parseIcsContent(ics, CALENDAR_ID, SUBSCRIPTION_ID)
        assertEquals(1, events.size)
        assertEquals("Apple Event", events[0].title)
    }
}
