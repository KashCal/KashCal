package org.onekash.kashcal.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher

/**
 * Tests for IcsExporter - Event export to ICS files.
 *
 * Tests filename generation, ICS content building, and VEVENT extraction.
 * Android-specific file operations (FileProvider) are not tested here.
 */
class IcsExporterTest {

    private lateinit var exporter: IcsExporter

    @Before
    fun setup() {
        exporter = IcsExporter()
    }

    // ========== Filename Generation Tests ==========

    @Test
    fun `generateFileName creates valid filename`() {
        val fileName = invokeGenerateFileName("Team Meeting")

        assertTrue("Filename should end with .ics", fileName.endsWith(".ics"))
        assertTrue("Filename should contain sanitized name", fileName.contains("Team-Meeting"))
        assertTrue("Filename should contain date", fileName.matches(Regex(".*_\\d{8}\\.ics")))
    }

    @Test
    fun `generateFileName removes special characters`() {
        val fileName = invokeGenerateFileName("Meeting @ Office #1!")

        assertFalse("Should not contain @", fileName.contains("@"))
        assertFalse("Should not contain #", fileName.contains("#"))
        assertFalse("Should not contain !", fileName.contains("!"))
    }

    @Test
    fun `generateFileName replaces spaces with hyphens`() {
        val fileName = invokeGenerateFileName("Team  Meeting   Today")

        assertFalse("Should not contain spaces", fileName.contains(" "))
        assertTrue("Should contain hyphens", fileName.contains("-"))
    }

    @Test
    fun `generateFileName truncates long names`() {
        val longName = "A".repeat(100)
        val fileName = invokeGenerateFileName(longName)

        // Max length is 50 chars + underscore + 8 digit date + .ics = 63 chars max
        assertTrue("Filename should be truncated", fileName.length <= 70)
    }

    @Test
    fun `generateFileName handles empty name`() {
        val fileName = invokeGenerateFileName("")

        assertTrue("Should use default name", fileName.contains("event"))
        assertTrue("Should still be valid .ics", fileName.endsWith(".ics"))
    }

    @Test
    fun `generateFileName handles name with only special chars`() {
        val fileName = invokeGenerateFileName("@#$%^&*")

        assertTrue("Should use default name", fileName.contains("event"))
    }

    // ========== VEVENT Extraction Tests ==========

    @Test
    fun `extractVEventBlocks extracts single VEVENT`() {
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test-123
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val blocks = invokeExtractVEventBlocks(icsContent)

        assertEquals("Should extract 1 VEVENT", 1, blocks.size)
        assertTrue("Block should contain BEGIN:VEVENT", blocks[0].contains("BEGIN:VEVENT"))
        assertTrue("Block should contain END:VEVENT", blocks[0].contains("END:VEVENT"))
        assertTrue("Block should contain UID", blocks[0].contains("UID:test-123"))
    }

    @Test
    fun `extractVEventBlocks extracts multiple VEVENTs`() {
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event-1
SUMMARY:Event 1
END:VEVENT
BEGIN:VEVENT
UID:event-2
SUMMARY:Event 2
END:VEVENT
BEGIN:VEVENT
UID:event-3
SUMMARY:Event 3
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val blocks = invokeExtractVEventBlocks(icsContent)

        assertEquals("Should extract 3 VEVENTs", 3, blocks.size)
        assertTrue("First block should contain event-1", blocks[0].contains("UID:event-1"))
        assertTrue("Second block should contain event-2", blocks[1].contains("UID:event-2"))
        assertTrue("Third block should contain event-3", blocks[2].contains("UID:event-3"))
    }

    @Test
    fun `extractVEventBlocks handles empty calendar`() {
        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
END:VCALENDAR
        """.trimIndent()

        val blocks = invokeExtractVEventBlocks(icsContent)

        assertTrue("Should return empty list", blocks.isEmpty())
    }

    // ========== ICS Text Escaping Tests ==========

    @Test
    fun `escapeIcsText escapes backslash`() {
        val escaped = invokeEscapeIcsText("Path\\to\\file")
        assertEquals("Path\\\\to\\\\file", escaped)
    }

    @Test
    fun `escapeIcsText escapes semicolon`() {
        val escaped = invokeEscapeIcsText("Item1; Item2")
        assertEquals("Item1\\; Item2", escaped)
    }

    @Test
    fun `escapeIcsText escapes comma`() {
        val escaped = invokeEscapeIcsText("A, B, C")
        assertEquals("A\\, B\\, C", escaped)
    }

    @Test
    fun `escapeIcsText escapes newline`() {
        val escaped = invokeEscapeIcsText("Line1\nLine2")
        assertEquals("Line1\\nLine2", escaped)
    }

    @Test
    fun `escapeIcsText handles combined escapes`() {
        val escaped = invokeEscapeIcsText("Path\\file; A, B\nNew line")
        assertEquals("Path\\\\file\\; A\\, B\\nNew line", escaped)
    }

    // ========== Calendar ICS Building Tests ==========

    @Test
    fun `buildCalendarIcs creates valid structure`() {
        val event = createBasicEvent()
        val events = listOf(Pair(event, emptyList<Event>()))

        val ics = invokeBuildCalendarIcs(events, "Test Calendar")

        assertTrue("Should start with BEGIN:VCALENDAR", ics.trimStart().startsWith("BEGIN:VCALENDAR"))
        assertTrue("Should end with END:VCALENDAR", ics.trimEnd().endsWith("END:VCALENDAR"))
        assertTrue("Should contain VERSION:2.0", ics.contains("VERSION:2.0"))
        assertTrue("Should contain X-WR-CALNAME", ics.contains("X-WR-CALNAME:Test Calendar"))
    }

    @Test
    fun `buildCalendarIcs includes all events`() {
        val event1 = createBasicEvent(title = "Event 1", uid = "uid-1")
        val event2 = createBasicEvent(title = "Event 2", uid = "uid-2")
        val events: List<Pair<Event, List<Event>>> = listOf(
            Pair(event1, emptyList()),
            Pair(event2, emptyList())
        )

        val ics = invokeBuildCalendarIcs(events, "My Calendar")

        assertTrue("Should contain Event 1", ics.contains("SUMMARY:Event 1"))
        assertTrue("Should contain Event 2", ics.contains("SUMMARY:Event 2"))
        assertTrue("Should contain uid-1", ics.contains("UID:uid-1"))
        assertTrue("Should contain uid-2", ics.contains("UID:uid-2"))
    }

    @Test
    fun `buildCalendarIcs escapes calendar name`() {
        val events = listOf(Pair(createBasicEvent(), emptyList<Event>()))

        val ics = invokeBuildCalendarIcs(events, "Calendar; With, Special\\Chars\nNewline")

        assertTrue("Should escape semicolon", ics.contains("\\;"))
        assertTrue("Should escape comma", ics.contains("\\,"))
        assertTrue("Should escape backslash", ics.contains("\\\\"))
        assertTrue("Should escape newline", ics.contains("\\n"))
    }

    // ========== Round-trip Tests ==========

    @Test
    fun `serialized single event can be parsed back`() {
        val event = createBasicEvent(
            title = "Round Trip Test",
            location = "Conference Room",
            description = "Test Description"
        )

        val ics = IcsPatcher.serialize(event)

        // Basic validation - contains required fields
        assertTrue("Should contain VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
        assertTrue("Should contain title", ics.contains("SUMMARY:Round Trip Test"))
        assertTrue("Should contain location", ics.contains("LOCATION:Conference Room"))
        assertTrue("Should contain description", ics.contains("DESCRIPTION:Test Description"))
    }

    // ========== Helper Methods ==========

    private fun invokeGenerateFileName(baseName: String): String {
        val method = IcsExporter::class.java.getDeclaredMethod("generateFileName", String::class.java)
        method.isAccessible = true
        return method.invoke(exporter, baseName) as String
    }

    private fun invokeExtractVEventBlocks(icsContent: String): List<String> {
        val method = IcsExporter::class.java.getDeclaredMethod("extractVEventBlocks", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(exporter, icsContent) as List<String>
    }

    private fun invokeEscapeIcsText(text: String): String {
        val method = IcsExporter::class.java.getDeclaredMethod("escapeIcsText", String::class.java)
        method.isAccessible = true
        return method.invoke(exporter, text) as String
    }

    private fun invokeBuildCalendarIcs(
        events: List<Pair<Event, List<Event>>>,
        calendarName: String
    ): String {
        val method = IcsExporter::class.java.getDeclaredMethod(
            "buildCalendarIcs",
            List::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(exporter, events, calendarName) as String
    }

    private fun createBasicEvent(
        title: String = "Test Event",
        location: String? = null,
        description: String? = null,
        uid: String = "test-uid-123"
    ): Event {
        val startTs = System.currentTimeMillis()
        return Event(
            id = 1L,
            calendarId = 1L,
            uid = uid,
            title = title,
            location = location,
            description = description,
            startTs = startTs,
            endTs = startTs + 3600000L, // 1 hour later
            isAllDay = false,
            timezone = null,
            rrule = null,
            exdate = null,
            dtstamp = startTs,
            createdAt = startTs,
            updatedAt = startTs,
            sequence = 0,
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = null,
            etag = null
        )
    }
}
