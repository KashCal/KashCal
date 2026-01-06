package org.onekash.kashcal.sync.strategy

import org.junit.Assert.*
import org.junit.Test
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.ParsedEvent

/**
 * Tests for EventMapper - ParsedEvent to Event entity conversion.
 */
class EventMapperTest {

    // ========== Basic Mapping Tests ==========

    @Test
    fun `toEntity maps basic properties correctly`() {
        val parsed = createParsedEvent(
            uid = "test-uid-123",
            summary = "Team Meeting",
            location = "Room 101",
            description = "Quarterly review"
        )

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1,
            caldavUrl = "https://caldav.example.com/event.ics",
            etag = "etag-123"
        )

        assertEquals("test-uid-123", event.uid)
        assertEquals("Team Meeting", event.title)
        assertEquals("Room 101", event.location)
        assertEquals("Quarterly review", event.description)
        assertEquals(1L, event.calendarId)
        assertEquals("https://caldav.example.com/event.ics", event.caldavUrl)
        assertEquals("etag-123", event.etag)
    }

    @Test
    fun `toEntity converts seconds to milliseconds for timestamps`() {
        val parsed = createParsedEvent(
            startTs = 1704110400, // Jan 1, 2024 12:00 UTC in seconds
            endTs = 1704114000   // Jan 1, 2024 13:00 UTC in seconds
        )

        val event = EventMapper.toEntity(parsed, 1, null, null)

        // Event stores in milliseconds
        assertEquals(1704110400000L, event.startTs)
        assertEquals(1704114000000L, event.endTs)
    }

    @Test
    fun `toEntity maps timezone correctly`() {
        val parsed = createParsedEvent(timezone = "America/Chicago")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("America/Chicago", event.timezone)
    }

    @Test
    fun `toEntity maps isAllDay correctly`() {
        val parsed = createParsedEvent(isAllDay = true)
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertTrue(event.isAllDay)
    }

    @Test
    fun `toEntity uses CONFIRMED as default status`() {
        val parsed = createParsedEvent(status = null)
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("CONFIRMED", event.status)
    }

    @Test
    fun `toEntity preserves non-default status`() {
        val parsed = createParsedEvent(status = "TENTATIVE")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("TENTATIVE", event.status)
    }

    // ========== Recurrence Mapping Tests ==========

    @Test
    fun `toEntity maps rrule correctly`() {
        val parsed = createParsedEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", event.rrule)
    }

    @Test
    fun `toEntity joins exdates with comma`() {
        val parsed = createParsedEvent(exdates = listOf("20240115", "20240122", "20240129"))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("20240115,20240122,20240129", event.exdate)
    }

    @Test
    fun `toEntity joins rdates with comma`() {
        val parsed = createParsedEvent(rdates = listOf("20240201", "20240215"))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("20240201,20240215", event.rdate)
    }

    @Test
    fun `toEntity returns null for empty exdates`() {
        val parsed = createParsedEvent(exdates = emptyList())
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertNull(event.exdate)
    }

    // ========== Reminder Mapping Tests ==========

    @Test
    fun `toEntity converts reminder minutes to ISO duration`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(15))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("-PT15M"), event.reminders)
    }

    @Test
    fun `toEntity converts 60 minutes to hours`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(60))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("-PT1H"), event.reminders)
    }

    @Test
    fun `toEntity converts 90 minutes to hours and minutes`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(90))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("-PT1H30M"), event.reminders)
    }

    @Test
    fun `toEntity converts 1440 minutes (1 day) to days`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(1440))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("-P1D"), event.reminders)
    }

    @Test
    fun `toEntity converts 10080 minutes (1 week) to weeks`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(10080))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("-P1W"), event.reminders)
    }

    @Test
    fun `toEntity handles multiple reminders`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(15, 60, 1440))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("-PT15M", "-PT1H", "-P1D"), event.reminders)
    }

    @Test
    fun `toEntity handles zero minutes`() {
        val parsed = createParsedEvent(reminderMinutes = listOf(0))
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(listOf("PT0M"), event.reminders)
    }

    // ========== Default Reminder Tests ==========

    @Test
    fun `toEntity applies default timed reminder when server has none`() {
        val parsed = createParsedEvent(isAllDay = false, reminderMinutes = emptyList())

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = 15,
            defaultAllDayReminderMinutes = 1440
        )

        assertEquals(listOf("-PT15M"), event.reminders)
    }

    @Test
    fun `toEntity applies default all-day reminder when server has none`() {
        val parsed = createParsedEvent(isAllDay = true, reminderMinutes = emptyList())

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = 15,
            defaultAllDayReminderMinutes = 1440
        )

        assertEquals(listOf("-P1D"), event.reminders)
    }

    @Test
    fun `toEntity preserves server reminders when present`() {
        val parsed = createParsedEvent(isAllDay = false, reminderMinutes = listOf(30))

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = 15,  // Should be ignored
            defaultAllDayReminderMinutes = 1440
        )

        assertEquals(listOf("-PT30M"), event.reminders)  // Server's 30 min, not default 15
    }

    @Test
    fun `toEntity no reminder when default is -1`() {
        val parsed = createParsedEvent(isAllDay = false, reminderMinutes = emptyList())

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = -1,  // No default
            defaultAllDayReminderMinutes = -1
        )

        assertNull(event.reminders)
    }

    @Test
    fun `toEntity no reminder when default is 0`() {
        val parsed = createParsedEvent(isAllDay = false, reminderMinutes = emptyList())

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = 0,  // 0 is not > 0, so no default
            defaultAllDayReminderMinutes = 0
        )

        assertNull(event.reminders)
    }

    @Test
    fun `toEntity uses default all-day reminder for 1 day correctly`() {
        val parsed = createParsedEvent(isAllDay = true, reminderMinutes = emptyList())

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = 15,
            defaultAllDayReminderMinutes = 2880  // 2 days
        )

        assertEquals(listOf("-P2D"), event.reminders)
    }

    @Test
    fun `toEntity uses default timed reminder for 1 hour correctly`() {
        val parsed = createParsedEvent(isAllDay = false, reminderMinutes = emptyList())

        val event = EventMapper.toEntity(
            parsed = parsed,
            calendarId = 1L,
            caldavUrl = "url",
            etag = "etag",
            defaultReminderMinutes = 60,
            defaultAllDayReminderMinutes = 1440
        )

        assertEquals(listOf("-PT1H"), event.reminders)
    }

    // ========== RECURRENCE-ID Parsing Tests ==========

    @Test
    fun `parseOriginalInstanceTime parses UTC datetime correctly`() {
        // 20240115T120000Z = Jan 15, 2024 12:00:00 UTC
        val result = EventMapper.parseOriginalInstanceTime("20240115T120000Z")

        assertNotNull(result)
        // Expected: 1705320000000 ms (Jan 15, 2024 12:00:00 UTC)
        assertEquals(1705320000000L, result)
    }

    @Test
    fun `parseOriginalInstanceTime parses date-only correctly`() {
        // 20240115 = Jan 15, 2024 00:00:00 UTC
        val result = EventMapper.parseOriginalInstanceTime("20240115")

        assertNotNull(result)
        // Should be start of day UTC
        assertEquals(1705276800000L, result)
    }

    @Test
    fun `parseOriginalInstanceTime parses local datetime correctly`() {
        // 20240115T120000 (no Z = local time)
        val result = EventMapper.parseOriginalInstanceTime("20240115T120000")

        assertNotNull(result)
        // Result depends on local timezone, just verify it's not null
    }

    @Test
    fun `parseOriginalInstanceTime handles VALUE=DATE prefix`() {
        val result = EventMapper.parseOriginalInstanceTime("VALUE=DATE:20240115")
        assertNotNull(result)
        assertEquals(1705276800000L, result)
    }

    @Test
    fun `parseOriginalInstanceTime handles VALUE=DATE-TIME prefix`() {
        val result = EventMapper.parseOriginalInstanceTime("VALUE=DATE-TIME:20240115T120000Z")
        assertNotNull(result)
        assertEquals(1705320000000L, result)
    }

    @Test
    fun `parseOriginalInstanceTime returns null for invalid input`() {
        assertNull(EventMapper.parseOriginalInstanceTime(null))
        assertNull(EventMapper.parseOriginalInstanceTime("invalid"))
        assertNull(EventMapper.parseOriginalInstanceTime(""))
    }

    // ========== Exception Event Tests ==========

    @Test
    fun `toEntity marks exception event with originalSyncId`() {
        val parsed = createParsedEvent(
            uid = "master-uid",
            recurrenceId = "20240115T120000Z"
        )

        val event = EventMapper.toEntity(parsed, 1, null, null)

        // isException is determined by recurrenceId being non-null
        assertTrue(parsed.isException())
        assertEquals("master-uid", event.originalSyncId)
        assertNotNull(event.originalInstanceTime)
    }

    @Test
    fun `toEntity does not set originalSyncId for master events`() {
        val parsed = createParsedEvent(recurrenceId = null)
        val event = EventMapper.toEntity(parsed, 1, null, null)

        assertFalse(parsed.isException())
        assertNull(event.originalSyncId)
    }

    // ========== Existing Event Preservation Tests ==========

    @Test
    fun `toEntity preserves existing event id`() {
        val parsed = createParsedEvent()
        val existing = createExistingEvent(id = 100)

        val event = EventMapper.toEntity(parsed, 1, null, null, existing)

        assertEquals(100L, event.id)
    }

    @Test
    fun `toEntity preserves existing originalEventId`() {
        val parsed = createParsedEvent()
        val existing = createExistingEvent(originalEventId = 50)

        val event = EventMapper.toEntity(parsed, 1, null, null, existing)

        assertEquals(50L, event.originalEventId)
    }

    @Test
    fun `toEntity preserves existing localModifiedAt`() {
        val parsed = createParsedEvent()
        val existing = createExistingEvent(localModifiedAt = 1704000000000L)

        val event = EventMapper.toEntity(parsed, 1, null, null, existing)

        assertEquals(1704000000000L, event.localModifiedAt)
    }

    @Test
    fun `toEntity preserves existing createdAt`() {
        val parsed = createParsedEvent()
        val existing = createExistingEvent(createdAt = 1704000000000L)

        val event = EventMapper.toEntity(parsed, 1, null, null, existing)

        assertEquals(1704000000000L, event.createdAt)
    }

    @Test
    fun `toEntity uses 0 as id for new events`() {
        val parsed = createParsedEvent()
        val event = EventMapper.toEntity(parsed, 1, null, null, null)
        assertEquals(0L, event.id)
    }

    // ========== Sync Status Tests ==========

    @Test
    fun `toEntity sets SYNCED status`() {
        val parsed = createParsedEvent()
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals(SyncStatus.SYNCED, event.syncStatus)
    }

    @Test
    fun `toEntity clears sync error`() {
        val parsed = createParsedEvent()
        val event = EventMapper.toEntity(parsed, 1, null, null)

        assertNull(event.lastSyncError)
        assertEquals(0, event.syncRetryCount)
    }

    // ========== RFC 5545 Round-Trip Field Tests ==========

    @Test
    fun `toEntity uses OPAQUE as default transp`() {
        val parsed = createParsedEvent(transp = null)
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("OPAQUE", event.transp)
    }

    @Test
    fun `toEntity preserves TRANSPARENT transp`() {
        val parsed = createParsedEvent(transp = "TRANSPARENT")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("TRANSPARENT", event.transp)
    }

    @Test
    fun `toEntity uses PUBLIC as default classification`() {
        val parsed = createParsedEvent(classification = null)
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("PUBLIC", event.classification)
    }

    @Test
    fun `toEntity preserves PRIVATE classification`() {
        val parsed = createParsedEvent(classification = "PRIVATE")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("PRIVATE", event.classification)
    }

    @Test
    fun `toEntity preserves CONFIDENTIAL classification`() {
        val parsed = createParsedEvent(classification = "CONFIDENTIAL")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("CONFIDENTIAL", event.classification)
    }

    @Test
    fun `toEntity maps extraProperties`() {
        val extras = mapOf(
            "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR" to "AUTOMATIC",
            "X-APPLE-CREATOR-IDENTITY" to "com.apple.mobilecal"
        )
        val parsed = createParsedEvent(extraProperties = extras)
        val event = EventMapper.toEntity(parsed, 1, null, null)

        assertEquals(extras, event.extraProperties)
    }

    @Test
    fun `toEntity handles null extraProperties`() {
        val parsed = createParsedEvent(extraProperties = null)
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertNull(event.extraProperties)
    }

    // ========== Organizer Tests ==========

    @Test
    fun `toEntity maps organizer email`() {
        val parsed = createParsedEvent(organizerEmail = "john@example.com")
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("john@example.com", event.organizerEmail)
    }

    @Test
    fun `toEntity maps organizer name`() {
        val parsed = createParsedEvent(
            organizerEmail = "john@example.com",
            organizerName = "John Doe"
        )
        val event = EventMapper.toEntity(parsed, 1, null, null)
        assertEquals("John Doe", event.organizerName)
    }

    // ========== Helper Methods ==========

    private fun createParsedEvent(
        uid: String = "test-uid",
        recurrenceId: String? = null,
        summary: String = "Test Event",
        description: String = "",
        location: String = "",
        startTs: Long = 1704110400, // Seconds
        endTs: Long = 1704114000,
        isAllDay: Boolean = false,
        timezone: String? = null,
        rrule: String? = null,
        exdates: List<String> = emptyList(),
        rdates: List<String> = emptyList(),
        reminderMinutes: List<Int> = emptyList(),
        sequence: Int = 0,
        status: String? = null,
        dtstamp: Long = 1704110400,
        organizerEmail: String? = null,
        organizerName: String? = null,
        transp: String? = null,
        classification: String? = null,
        extraProperties: Map<String, String>? = null
    ) = ParsedEvent(
        uid = uid,
        recurrenceId = recurrenceId,
        summary = summary,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        timezone = timezone,
        rrule = rrule,
        exdates = exdates,
        rdates = rdates,
        reminderMinutes = reminderMinutes,
        sequence = sequence,
        status = status,
        dtstamp = dtstamp,
        organizerEmail = organizerEmail,
        organizerName = organizerName,
        transp = transp,
        classification = classification,
        extraProperties = extraProperties,
        rawIcal = null
    )

    private fun createExistingEvent(
        id: Long = 1,
        originalEventId: Long? = null,
        localModifiedAt: Long? = null,
        createdAt: Long = System.currentTimeMillis()
    ) = org.onekash.kashcal.data.db.entity.Event(
        id = id,
        uid = "existing-uid",
        calendarId = 1,
        title = "Existing Event",
        startTs = 1704110400000L,
        endTs = 1704114000000L,
        dtstamp = 1704110400000L,
        originalEventId = originalEventId,
        localModifiedAt = localModifiedAt,
        createdAt = createdAt,
        syncStatus = SyncStatus.SYNCED
    )
}
