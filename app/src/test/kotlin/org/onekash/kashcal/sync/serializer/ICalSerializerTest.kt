package org.onekash.kashcal.sync.serializer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.Ical4jParser

/**
 * Tests for ICalSerializer - Event to iCal conversion.
 * Includes round-trip tests to verify parser compatibility.
 */
class ICalSerializerTest {

    private lateinit var serializer: ICalSerializer
    private lateinit var parser: Ical4jParser

    @Before
    fun setup() {
        serializer = ICalSerializer()
        parser = Ical4jParser()
    }

    // ========== Basic Serialization Tests ==========

    @Test
    fun `serialize creates valid VCALENDAR structure`() {
        val event = createBasicEvent()
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("BEGIN:VCALENDAR"))
        assertTrue(ical.contains("END:VCALENDAR"))
        assertTrue(ical.contains("VERSION:2.0"))
        assertTrue(ical.contains("PRODID:"))
        assertTrue(ical.contains("CALSCALE:GREGORIAN"))
    }

    @Test
    fun `serialize creates valid VEVENT structure`() {
        val event = createBasicEvent()
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("BEGIN:VEVENT"))
        assertTrue(ical.contains("END:VEVENT"))
        assertTrue(ical.contains("UID:test-uid-123"))
        assertTrue(ical.contains("DTSTAMP:"))
    }

    @Test
    fun `serialize includes SUMMARY`() {
        val event = createBasicEvent(title = "Team Meeting")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("SUMMARY:Team Meeting"))
    }

    @Test
    fun `serialize includes LOCATION when present`() {
        val event = createBasicEvent(location = "Conference Room A")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("LOCATION:Conference Room A"))
    }

    @Test
    fun `serialize excludes LOCATION when null`() {
        val event = createBasicEvent(location = null)
        val ical = serializer.serialize(event)

        assertFalse(ical.contains("LOCATION:"))
    }

    @Test
    fun `serialize includes DESCRIPTION when present`() {
        val event = createBasicEvent(description = "Discuss Q4 goals")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("DESCRIPTION:Discuss Q4 goals"))
    }

    // ========== Date/Time Tests ==========

    @Test
    fun `serialize UTC event uses Z suffix`() {
        val event = createBasicEvent(timezone = null)
        val ical = serializer.serialize(event)

        // Should have UTC format with Z suffix
        assertTrue(ical.contains("DTSTART:") && ical.contains("Z"))
        assertTrue(ical.contains("DTEND:") && ical.contains("Z"))
    }

    @Test
    fun `serialize event with timezone uses TZID`() {
        val event = createBasicEvent(timezone = "America/Chicago")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("DTSTART;TZID=America/Chicago:"))
        assertTrue(ical.contains("DTEND;TZID=America/Chicago:"))
    }

    @Test
    fun `serialize all-day event uses DATE value`() {
        val event = createBasicEvent(isAllDay = true)
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("DTSTART;VALUE=DATE:"))
        assertTrue(ical.contains("DTEND;VALUE=DATE:"))
        // DATE format is 8 characters (YYYYMMDD) vs 16 for DATE-TIME
        val dtstart = ical.lineSequence().find { it.startsWith("DTSTART") }!!
        assertTrue(dtstart.substringAfter(":").length == 8)
    }

    @Test
    fun `serialize all-day event outputs exclusive DTEND`() {
        // Single-day all-day event on Dec 25
        // startTs = Dec 25 00:00:00 UTC, endTs = Dec 25 23:59:59 UTC (inclusive)
        val startTs = getUtcTimestamp(2024, 12, 25, 0, 0)
        val endTs = getUtcTimestamp(2024, 12, 25, 23, 59)  // Inclusive - last second of Dec 25
        val event = createBasicEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true
        )
        val ical = serializer.serialize(event)

        // RFC 5545: DTEND is exclusive, so Dec 25 event should have DTEND=20241226
        assertTrue(ical.contains("DTSTART;VALUE=DATE:20241225"))
        assertTrue(ical.contains("DTEND;VALUE=DATE:20241226"))  // Day AFTER the last day
    }

    @Test
    fun `serialize multi-day all-day event outputs exclusive DTEND`() {
        // 3-day event: Dec 24-26 (inclusive)
        // DTSTART=20241224, stored endTs = Dec 26 23:59:59 UTC
        val startTs = getUtcTimestamp(2024, 12, 24, 0, 0)
        val endTs = getUtcTimestamp(2024, 12, 26, 23, 59)  // Inclusive - last second of Dec 26
        val event = createBasicEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true
        )
        val ical = serializer.serialize(event)

        // RFC 5545: DTEND is exclusive, so Dec 24-26 event should have DTEND=20241227
        assertTrue(ical.contains("DTSTART;VALUE=DATE:20241224"))
        assertTrue(ical.contains("DTEND;VALUE=DATE:20241227"))  // Day AFTER Dec 26
    }

    // ========== Recurrence Tests ==========

    @Test
    fun `serialize includes RRULE when present`() {
        val event = createBasicEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR"))
    }

    @Test
    fun `serialize includes EXDATE when present`() {
        val event = createBasicEvent(exdate = "20240115T120000Z,20240122T120000Z")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("EXDATE:20240115T120000Z,20240122T120000Z"))
    }

    @Test
    fun `serialize includes RDATE when present`() {
        val event = createBasicEvent(rdate = "20240201T120000Z")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RDATE:20240201T120000Z"))
    }

    // ========== Exception Event Tests ==========

    @Test
    fun `serialize exception event includes RECURRENCE-ID`() {
        // Original instance time: Jan 15, 2024 12:00 UTC
        val originalTime = 1705320000000L
        val event = createBasicEvent(
            originalEventId = 100,
            originalInstanceTime = originalTime,
            timezone = null
        )
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RECURRENCE-ID:"))
    }

    @Test
    fun `serialize exception event with timezone includes TZID in RECURRENCE-ID`() {
        val originalTime = 1705320000000L
        val event = createBasicEvent(
            originalEventId = 100,
            originalInstanceTime = originalTime,
            timezone = "America/New_York"
        )
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RECURRENCE-ID;TZID=America/New_York:"))
    }

    @Test
    fun `serialize all-day exception uses DATE in RECURRENCE-ID`() {
        val originalTime = 1705276800000L // Jan 15, 2024 00:00 UTC
        val event = createBasicEvent(
            originalEventId = 100,
            originalInstanceTime = originalTime,
            isAllDay = true
        )
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RECURRENCE-ID;VALUE=DATE:"))
    }

    // ========== Reminder Tests ==========

    @Test
    fun `serialize includes VALARM for reminders`() {
        val event = createBasicEvent(reminders = listOf("-PT15M"))
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("BEGIN:VALARM"))
        assertTrue(ical.contains("ACTION:DISPLAY"))
        assertTrue(ical.contains("TRIGGER:-PT15M"))
        assertTrue(ical.contains("END:VALARM"))
    }

    @Test
    fun `serialize includes multiple VALARMs`() {
        val event = createBasicEvent(reminders = listOf("-PT15M", "-PT1H", "-P1D"))
        val ical = serializer.serialize(event)

        val alarmCount = ical.split("BEGIN:VALARM").size - 1
        assertEquals(3, alarmCount)
    }

    @Test
    fun `serialize VALARM includes Apple-specific properties`() {
        // Apple/iCloud uses these properties to distinguish user alarms from default alarms
        val event = createBasicEvent(reminders = listOf("-PT15M", "-PT30M"))
        val ical = serializer.serialize(event)

        // Each VALARM should have X-APPLE-DEFAULT-ALARM:FALSE
        val appleDefaultCount = ical.split("X-APPLE-DEFAULT-ALARM:FALSE").size - 1
        assertEquals("Each alarm should have X-APPLE-DEFAULT-ALARM:FALSE", 2, appleDefaultCount)

        // Each VALARM should have X-WR-ALARMUID
        val alarmUidCount = ical.split("X-WR-ALARMUID:").size - 1
        assertEquals("Each alarm should have X-WR-ALARMUID", 2, alarmUidCount)

        // Each VALARM should have a UID (inside the VALARM block)
        // Note: The UID inside VALARM is different from the event UID
        assertTrue("VALARM should contain UID", ical.contains("UID:"))
    }

    // ========== Special Properties Tests ==========

    @Test
    fun `serialize includes SEQUENCE when greater than zero`() {
        val event = createBasicEvent(sequence = 5)
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("SEQUENCE:5"))
    }

    @Test
    fun `serialize excludes SEQUENCE when zero`() {
        val event = createBasicEvent(sequence = 0)
        val ical = serializer.serialize(event)

        assertFalse(ical.contains("SEQUENCE:"))
    }

    @Test
    fun `serialize includes STATUS when not CONFIRMED`() {
        val event = createBasicEvent(status = "TENTATIVE")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("STATUS:TENTATIVE"))
    }

    @Test
    fun `serialize includes TRANSP when not OPAQUE`() {
        val event = createBasicEvent(transp = "TRANSPARENT")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("TRANSP:TRANSPARENT"))
    }

    @Test
    fun `serialize includes CLASS when not PUBLIC`() {
        val event = createBasicEvent(classification = "PRIVATE")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("CLASS:PRIVATE"))
    }

    // ========== Organizer Tests ==========

    @Test
    fun `serialize includes ORGANIZER with email`() {
        val event = createBasicEvent(organizerEmail = "john@example.com")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("ORGANIZER:mailto:john@example.com"))
    }

    @Test
    fun `serialize includes ORGANIZER with CN when name present`() {
        val event = createBasicEvent(
            organizerEmail = "john@example.com",
            organizerName = "John Doe"
        )
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("ORGANIZER;CN=John Doe:mailto:john@example.com"))
    }

    // ========== Extra Properties Tests ==========

    @Test
    fun `serialize includes extra properties`() {
        val event = createBasicEvent(
            extraProperties = mapOf(
                "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR" to "AUTOMATIC",
                "X-APPLE-CREATOR-IDENTITY" to "com.apple.mobilecal"
            )
        )
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC"))
        assertTrue(ical.contains("X-APPLE-CREATOR-IDENTITY:com.apple.mobilecal"))
    }

    // ========== Text Escaping Tests ==========

    @Test
    fun `serialize escapes commas in text`() {
        val event = createBasicEvent(title = "Meeting, Important")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("SUMMARY:Meeting\\, Important"))
    }

    @Test
    fun `serialize escapes semicolons in text`() {
        val event = createBasicEvent(title = "Task; Urgent")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("SUMMARY:Task\\; Urgent"))
    }

    @Test
    fun `serialize escapes newlines in text`() {
        val event = createBasicEvent(description = "Line 1\nLine 2")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("DESCRIPTION:Line 1\\nLine 2"))
    }

    @Test
    fun `serialize escapes backslashes in text`() {
        val event = createBasicEvent(title = "Path\\To\\File")
        val ical = serializer.serialize(event)

        assertTrue(ical.contains("SUMMARY:Path\\\\To\\\\File"))
    }

    // ========== Multiple Events Tests ==========

    @Test
    fun `serializeWithExceptions includes master and exceptions`() {
        val master = createBasicEvent(
            uid = "master-uid",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        val exception1 = createBasicEvent(
            uid = "master-uid",
            originalEventId = 1,
            originalInstanceTime = 1705320000000L,
            title = "Modified Meeting"
        )
        val exception2 = createBasicEvent(
            uid = "master-uid",
            originalEventId = 1,
            originalInstanceTime = 1705924800000L,
            title = "Another Modified"
        )

        val ical = serializer.serializeWithExceptions(master, listOf(exception1, exception2))

        // Should have exactly 3 VEVENTs
        val veventCount = ical.split("BEGIN:VEVENT").size - 1
        assertEquals(3, veventCount)

        // Master should have RRULE
        assertTrue(ical.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"))

        // Exceptions should have RECURRENCE-ID
        val recurrenceIdCount = ical.split("RECURRENCE-ID").size - 1
        assertEquals(2, recurrenceIdCount)
    }

    // ========== Round-Trip Tests ==========

    @Test
    fun `round trip preserves basic event properties`() {
        val original = createBasicEvent(
            title = "Important Meeting",
            location = "Room 101",
            description = "Discuss quarterly results"
        )

        val ical = serializer.serialize(original)
        val parseResult = parser.parse(ical)

        assertTrue(parseResult.isSuccess())
        assertEquals(1, parseResult.events.size)

        val parsed = parseResult.events[0]
        assertEquals(original.uid, parsed.uid)
        assertEquals(original.title, parsed.summary)
        assertEquals(original.location, parsed.location)
        assertEquals(original.description, parsed.description)
    }

    @Test
    fun `round trip preserves all-day event`() {
        val original = createBasicEvent(isAllDay = true)

        val ical = serializer.serialize(original)
        val parseResult = parser.parse(ical)

        assertTrue(parseResult.isSuccess())
        assertTrue(parseResult.events[0].isAllDay)
    }

    @Test
    fun `round trip preserves recurrence rule`() {
        val original = createBasicEvent(rrule = "FREQ=DAILY;COUNT=10")

        val ical = serializer.serialize(original)
        val parseResult = parser.parse(ical)

        assertTrue(parseResult.isSuccess())
        assertEquals("FREQ=DAILY;COUNT=10", parseResult.events[0].rrule)
    }

    @Test
    fun `round trip preserves reminders`() {
        val original = createBasicEvent(reminders = listOf("-PT15M", "-PT1H"))

        val ical = serializer.serialize(original)
        val parseResult = parser.parse(ical)

        assertTrue(parseResult.isSuccess())
        val reminders = parseResult.events[0].reminderMinutes
        assertEquals(2, reminders.size)
        assertTrue(reminders.contains(15))
        assertTrue(reminders.contains(60))
    }

    @Test
    fun `round trip preserves multi-day all-day event date span`() {
        // 3-day event: Dec 24-26 (stored as inclusive - last second of Dec 26)
        val startTs = getUtcTimestamp(2024, 12, 24, 0, 0)
        val endTs = getUtcTimestamp(2024, 12, 26, 23, 59)  // Inclusive - last second of Dec 26
        val original = createBasicEvent(
            startTs = startTs,
            endTs = endTs,
            isAllDay = true
        )

        // Serialize (should output exclusive DTEND=20241227)
        val ical = serializer.serialize(original)
        assertTrue(ical.contains("DTSTART;VALUE=DATE:20241224"))
        assertTrue(ical.contains("DTEND;VALUE=DATE:20241227"))  // Exclusive

        // Parse back (should convert to inclusive endTs)
        val parseResult = parser.parse(ical)
        assertTrue(parseResult.isSuccess())
        val parsed = parseResult.events[0]

        // Verify parsed event has same date span (Dec 24-26)
        assertEquals(original.startTs, parsed.startTs * 1000)  // Parser uses seconds
        // endTs after round-trip should represent Dec 26 (inclusive)
        // Parser subtracts 1 sec from exclusive DTEND, so Dec 27 00:00 - 1sec = Dec 26 23:59:59 UTC
        val parsedEndMillis = parsed.endTs * 1000
        val expectedEndMillis = getUtcTimestamp(2024, 12, 26, 23, 59)
        // Allow 1 minute tolerance for the 59 seconds vs 59:59 difference
        assertTrue("Parsed endTs should be Dec 26",
            kotlin.math.abs(parsedEndMillis - expectedEndMillis) < 60000)
    }

    @Test
    fun `round trip multi-day all-day preserves TripIt-style vacation`() {
        // TripIt vacation Dec 25-29 (5 days, stored as inclusive)
        val startTs = getUtcTimestamp(2024, 12, 25, 0, 0)
        val endTs = getUtcTimestamp(2024, 12, 29, 23, 59)  // Inclusive
        val original = createBasicEvent(
            uid = "tripit-vacation-123",
            title = "Winter Vacation",
            startTs = startTs,
            endTs = endTs,
            isAllDay = true
        )

        val ical = serializer.serialize(original)
        val parseResult = parser.parse(ical)

        assertTrue(parseResult.isSuccess())
        val parsed = parseResult.events[0]
        assertTrue(parsed.isAllDay)
        assertEquals(original.startTs, parsed.startTs * 1000)

        // Verify ICS contains correct exclusive dates
        assertTrue(ical.contains("DTSTART;VALUE=DATE:20241225"))
        assertTrue(ical.contains("DTEND;VALUE=DATE:20241230"))  // Dec 30 = day after Dec 29
    }

    // ========== Real iCloud Data Pattern Tests ==========

    @Test
    fun `serialize weekly recurring event like iCloud`() {
        // Pattern from real iCloud: "AC maintenance vinegar thru pipe"
        val event = createBasicEvent(
            uid = "37396123-32E0-43AC-A4C1-C1619A031BDB",
            title = "AC maintenance vinegar thru pipe",
            rrule = "FREQ=WEEKLY;INTERVAL=16;BYDAY=SU",
            timezone = "America/Chicago",
            reminders = listOf("-PT15M")
        )

        val ical = serializer.serialize(event)

        assertTrue(ical.contains("UID:37396123-32E0-43AC-A4C1-C1619A031BDB"))
        assertTrue(ical.contains("SUMMARY:AC maintenance vinegar thru pipe"))
        assertTrue(ical.contains("RRULE:FREQ=WEEKLY;INTERVAL=16;BYDAY=SU"))
        assertTrue(ical.contains("TZID=America/Chicago"))

        // Verify it parses correctly
        val parseResult = parser.parse(ical)
        assertTrue("Parser failed: ${parseResult.errors}. ICS:\n$ical", parseResult.isSuccess())
    }

    @Test
    fun `serialize monthly recurring event like iCloud`() {
        // Pattern from real iCloud: "AC filter check"
        val event = createBasicEvent(
            uid = "8FDEB50C-F05E-48E8-809D-2199C3955660",
            title = "AC filter check",
            rrule = "FREQ=MONTHLY;BYDAY=SA;BYSETPOS=1",
            timezone = "America/Chicago"
        )

        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RRULE:FREQ=MONTHLY;BYDAY=SA;BYSETPOS=1"))

        // Verify it parses correctly
        val parseResult = parser.parse(ical)
        assertTrue(parseResult.isSuccess())
        assertEquals("FREQ=MONTHLY;BYDAY=SA;BYSETPOS=1", parseResult.events[0].rrule)
    }

    @Test
    fun `serialize yearly recurring event like iCloud`() {
        // Pattern from real iCloud: "Pay property tax"
        val event = createBasicEvent(
            uid = "FBEAD0E4-D770-4201-9248-CFA18A03CFB5",
            title = "Pay property tax for Olivers way",
            rrule = "FREQ=YEARLY;BYMONTH=12;BYDAY=3SA",
            timezone = "America/Chicago",
            reminders = listOf("-PT15M", "-P1W")
        )

        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RRULE:FREQ=YEARLY;BYMONTH=12;BYDAY=3SA"))

        // Verify it parses correctly
        val parseResult = parser.parse(ical)
        assertTrue(parseResult.isSuccess())
    }

    @Test
    fun `serialize exception event like iCloud`() {
        // Pattern from real iCloud: exception to "AC filter check"
        val originalTime = 1733490000000L // Dec 6, 2025 in CST
        val event = createBasicEvent(
            uid = "8FDEB50C-F05E-48E8-809D-2199C3955660",
            title = "AC filter check",
            originalEventId = 100,
            originalInstanceTime = originalTime,
            timezone = "America/Chicago",
            reminders = listOf("-PT15M")
        )

        val ical = serializer.serialize(event)

        assertTrue(ical.contains("RECURRENCE-ID;TZID=America/Chicago:"))
        // Exception VEVENT should not have RRULE - extract VEVENT section to check
        // (VTIMEZONE may have RRULE for DST transitions)
        val veventSection = ical.substringAfter("BEGIN:VEVENT").substringBefore("END:VEVENT")
        assertFalse(veventSection.contains("RRULE:")) // Exception should not have RRULE

        // Verify it parses correctly
        val parseResult = parser.parse(ical)
        assertTrue(parseResult.isSuccess())
        assertTrue(parseResult.events[0].isException())
    }

    // ========== Helper Methods ==========

    private fun createBasicEvent(
        uid: String = "test-uid-123",
        title: String = "Test Event",
        location: String? = null,
        description: String? = null,
        startTs: Long = 1704110400000L, // Jan 1, 2024 12:00 UTC
        endTs: Long = 1704114000000L,   // Jan 1, 2024 13:00 UTC
        timezone: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        rdate: String? = null,
        exdate: String? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null,
        reminders: List<String>? = null,
        extraProperties: Map<String, String>? = null,
        sequence: Int = 0,
        status: String = "CONFIRMED",
        transp: String = "OPAQUE",
        classification: String = "PUBLIC",
        organizerEmail: String? = null,
        organizerName: String? = null
    ) = Event(
        id = 1,
        uid = uid,
        calendarId = 1,
        title = title,
        location = location,
        description = description,
        startTs = startTs,
        endTs = endTs,
        timezone = timezone,
        isAllDay = isAllDay,
        status = status,
        transp = transp,
        classification = classification,
        organizerEmail = organizerEmail,
        organizerName = organizerName,
        rrule = rrule,
        rdate = rdate,
        exdate = exdate,
        originalEventId = originalEventId,
        originalInstanceTime = originalInstanceTime,
        reminders = reminders,
        extraProperties = extraProperties,
        dtstamp = System.currentTimeMillis(),
        sequence = sequence,
        syncStatus = SyncStatus.SYNCED
    )

    /**
     * Get UTC timestamp for given date/time components.
     * Used to create test events with specific UTC times.
     */
    private fun getUtcTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, 0)  // month is 0-indexed
        return calendar.timeInMillis
    }
}
