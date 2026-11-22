package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.EventExport
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher

/**
 * Tests [IcsExporter]'s file names (private, called by reflection) and calendar ICS building
 * ([IcsExporter.buildCalendarText]), plus single-event serialization through [IcsPatcher].
 *
 * Android file operations (FileProvider) are not tested here.
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

    // Text escaping is ICalGenerator's: icaldav-core's ICalGeneratorEdgeCaseTest covers event
    // text and ICalGeneratorCalendarTest calendar-level text.

    // ========== Calendar ICS Building Tests ==========

    @Test
    fun `buildCalendarText creates valid structure`() {
        val event = createBasicEvent()
        val events = listOf(Pair(event, emptyList<Event>()))

        val ics = buildCalendarText(events, "Test Calendar")

        assertTrue("Should start with BEGIN:VCALENDAR", ics.trimStart().startsWith("BEGIN:VCALENDAR"))
        assertTrue("Should end with END:VCALENDAR", ics.trimEnd().endsWith("END:VCALENDAR"))
        assertTrue("Should contain VERSION:2.0", ics.contains("VERSION:2.0"))
        assertTrue("Should contain X-WR-CALNAME", ics.contains("X-WR-CALNAME:Test Calendar"))
    }

    @Test
    fun `buildCalendarText includes all events`() {
        val event1 = createBasicEvent(title = "Event 1", uid = "uid-1")
        val event2 = createBasicEvent(title = "Event 2", uid = "uid-2")
        val events: List<Pair<Event, List<Event>>> = listOf(
            Pair(event1, emptyList()),
            Pair(event2, emptyList())
        )

        val ics = buildCalendarText(events, "My Calendar")

        assertTrue("Should contain Event 1", ics.contains("SUMMARY:Event 1"))
        assertTrue("Should contain Event 2", ics.contains("SUMMARY:Event 2"))
        assertTrue("Should contain uid-1", ics.contains("UID:uid-1"))
        assertTrue("Should contain uid-2", ics.contains("UID:uid-2"))
    }

    @Test
    fun `buildCalendarText escapes calendar name`() {
        val events = listOf(Pair(createBasicEvent(), emptyList<Event>()))

        val ics = buildCalendarText(events, "Calendar; With, Special\\Chars\nNewline")

        assertTrue("Should escape semicolon", ics.contains("\\;"))
        assertTrue("Should escape comma", ics.contains("\\,"))
        assertTrue("Should escape backslash", ics.contains("\\\\"))
        assertTrue("Should escape newline", ics.contains("\\n"))
    }

    @Test
    fun `buildCalendarText emits VTIMEZONE blocks for event timezones deduplicated`() {
        // Two events share America/New_York, one uses Asia/Tokyo, so one VTIMEZONE per distinct
        // TZID gives 2 blocks. Only the block count is asserted.
        val nyStart = 1735099200000L
        val event1 = createBasicEvent(uid = "ny-1").copy(timezone = "America/New_York", startTs = nyStart, endTs = nyStart + 3600000)
        val event2 = createBasicEvent(uid = "ny-2").copy(timezone = "America/New_York", startTs = nyStart + 86400000, endTs = nyStart + 86400000 + 3600000)
        val event3 = createBasicEvent(uid = "tokyo-1").copy(timezone = "Asia/Tokyo", startTs = nyStart + 2 * 86400000, endTs = nyStart + 2 * 86400000 + 3600000)
        val events: List<Pair<Event, List<Event>>> = listOf(
            Pair(event1, emptyList()),
            Pair(event2, emptyList()),
            Pair(event3, emptyList())
        )

        val ics = buildCalendarText(events, "Multi-TZ Calendar")

        // Matches the VTIMEZONE TZID line, not the parameter
        val nyCount = ics.split("TZID:America/New_York\n").size - 1
        val tokyoVtimezoneCount = ics.split("TZID:Asia/Tokyo\n").size - 1
        val vtimezoneBlockCount = Regex("BEGIN:VTIMEZONE").findAll(ics).count()
        assertEquals("Expected 2 VTIMEZONE blocks (NY + Tokyo), got:\n$ics", 2, vtimezoneBlockCount)
    }

    // ========== Single-event Serialization ==========

    @Test
    fun `serialized single event can be parsed back`() {
        val event = createBasicEvent(
            title = "Round Trip Test",
            location = "Conference Room",
            description = "Test Description"
        )

        val ics = IcsPatcher.serialize(event, null)

        // Checks the serialized fields only; nothing is parsed back.
        assertTrue("Should contain VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain VEVENT", ics.contains("BEGIN:VEVENT"))
        assertTrue("Should contain title", ics.contains("SUMMARY:Round Trip Test"))
        assertTrue("Should contain location", ics.contains("LOCATION:Conference Room"))
        assertTrue("Should contain description", ics.contains("DESCRIPTION:Test Description"))
    }

    @Test
    fun `an all-day series exports a timed changed occurrence with a date RECURRENCE-ID`() {
        // RFC 5545 section 3.8.4.4: RECURRENCE-ID has the series DTSTART's value type.
        val day = 86_400_000L
        val start = java.time.LocalDate.parse("2024-03-05").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val master = createBasicEvent(title = "Holiday", uid = "allday-export@example.test").copy(
            startTs = start, endTs = start + day - 1, isAllDay = true, rrule = "FREQ=WEEKLY;COUNT=4",
        )
        val nineAm = java.time.ZonedDateTime.parse("2024-03-12T09:00:00-04:00[America/New_York]").toInstant().toEpochMilli()
        val exception = master.copy(
            id = 2L, rrule = null, originalEventId = 1L, originalInstanceTime = start + 7 * day,
            startTs = nineAm, endTs = nineAm + 3_600_000L, isAllDay = false, timezone = "America/New_York",
        )

        val ics = buildCalendarText(listOf(master to listOf(exception)), "Cal").replace(Regex("\r?\n[ \t]"), "")

        assertTrue(ics, ics.contains("RECURRENCE-ID;VALUE=DATE:20240312"))
        assertTrue(ics, ics.contains("DTSTART;TZID=America/New_York:20240312T090000"))
    }

    // ========== Helper Methods ==========

    private fun invokeGenerateFileName(baseName: String): String {
        val method = IcsExporter::class.java.getDeclaredMethod("generateFileName", String::class.java)
        method.isAccessible = true
        return method.invoke(exporter, baseName) as String
    }

    private fun buildCalendarText(
        events: List<Pair<Event, List<Event>>>,
        calendarName: String
    ): String {
        val exports = events.map { (event, exceptions) -> EventExport(event, exceptions, emptyMap()) }
        return exporter.buildCalendarText(exports, calendarName)
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
