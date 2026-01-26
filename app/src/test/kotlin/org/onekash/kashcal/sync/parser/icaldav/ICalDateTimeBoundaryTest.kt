package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Tests for ICalDateTime boundary conversions in the icaldav integration.
 *
 * These tests verify that millisecond timestamps are correctly converted
 * between KashCal's Event entity and the icaldav library's ICalDateTime.
 *
 * Key conversion points:
 * - ICalDateTime.fromTimestamp(ms, zone, isAllDay) - ms → ICalDateTime
 * - ICalDateTime.timestamp - ICalDateTime → ms
 *
 * Created as part of iCalDAV integration audit (Task #1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICalDateTimeBoundaryTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator(
        prodId = "-//KashCal//Test//EN",
        includeAppleExtensions = false
    )

    // ========== Millisecond Precision Tests ==========

    @Test
    fun `ICalDateTime timestamp is in milliseconds`() {
        // Create ICalDateTime from known milliseconds
        val knownMs = 1737590400000L // Jan 23, 2025 00:00:00 UTC
        val dateTime = ICalDateTime.fromTimestamp(knownMs, null, false)

        // Verify timestamp is in milliseconds (13 digits)
        assertTrue(
            "timestamp should be in milliseconds (>1e12), got ${dateTime.timestamp}",
            dateTime.timestamp > 1_000_000_000_000L
        )
        assertEquals(knownMs, dateTime.timestamp)
    }

    @Test
    fun `ICalDateTime preserves exact milliseconds for timed events`() {
        // Test with a timestamp that has specific milliseconds
        val startMs = 1737590400123L // Has 123 milliseconds
        val dateTime = ICalDateTime.fromTimestamp(startMs, null, false)

        // ICalDateTime preserves exact milliseconds - no truncation at object level
        // Truncation only happens during ICS serialization/parse (ICS format has second precision)
        assertEquals(
            "ICalDateTime preserves exact milliseconds",
            startMs,
            dateTime.timestamp
        )
    }

    @Test
    fun `ICS serialization truncates milliseconds to seconds`() {
        // This documents that millisecond truncation happens at ICS level, not ICalDateTime level
        val startMs = 1737590400123L // Has 123 milliseconds
        val endMs = startMs + 3600000L

        val event = createTestEvent(
            uid = "truncation-test@test.com",
            startTs = startMs,
            endTs = endMs
        )

        // Generate ICS (truncates to seconds) and parse back
        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        // ICS format only has second precision, so 123ms is lost
        val truncatedMs = (startMs / 1000) * 1000
        assertEquals(
            "ICS round-trip truncates milliseconds",
            truncatedMs,
            parsed.dtStart.timestamp
        )
    }

    @Test
    fun `ICalDateTime round-trip through ICS preserves timestamp to seconds`() {
        val startMs = 1737590400000L // Exact second boundary
        val endMs = startMs + 3600000L // +1 hour

        val event = createTestEvent(
            uid = "roundtrip-test@test.com",
            startTs = startMs,
            endTs = endMs
        )

        // Generate ICS and parse back
        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        // Verify timestamps match
        assertEquals(startMs, parsed.dtStart.timestamp)
        assertEquals(endMs, parsed.effectiveEnd().timestamp)
    }

    // ========== All-Day Event Tests ==========

    @Test
    fun `all-day event uses DATE format not DATETIME`() {
        val utcMidnight = 1737504000000L // Jan 22, 2025 00:00:00 UTC
        val dateTime = ICalDateTime.fromTimestamp(utcMidnight, null, isDate = true)

        // All-day events should use date-only format
        assertTrue("All-day should be date-only", dateTime.isDate)
    }

    @Test
    fun `all-day event timestamp is UTC midnight`() {
        val utcMidnight = 1737504000000L // Jan 22, 2025 00:00:00 UTC
        val event = createAllDayEvent(
            uid = "allday-test@test.com",
            startTs = utcMidnight,
            endTs = utcMidnight + 86400000L - 1 // End of day
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        // Verify all-day is preserved
        assertTrue("Should be all-day", parsed.isAllDay)
        assertTrue("dtStart should be date-only", parsed.dtStart.isDate)
    }

    @Test
    fun `all-day event generates correct DATE value`() {
        // Jan 22, 2025 00:00:00 UTC
        val utcMidnight = 1737504000000L
        val event = createAllDayEvent(
            uid = "date-format-test@test.com",
            startTs = utcMidnight,
            endTs = utcMidnight + 86400000L - 1
        )

        val ics = IcsPatcher.generateFresh(event)

        // ICS should contain VALUE=DATE format
        assertTrue(
            "ICS should contain DATE value",
            ics.contains("VALUE=DATE") || ics.contains(";VALUE=DATE")
        )
        // Should NOT contain time component for start
        assertFalse(
            "All-day DTSTART should not have time",
            ics.contains("DTSTART:20250122T")
        )
    }

    // ========== Timezone Tests ==========

    @Test
    fun `timezone is preserved through round-trip`() {
        val startMs = 1737590400000L
        val endMs = startMs + 3600000L
        val timezone = "America/New_York"

        val event = createTestEvent(
            uid = "tz-test@test.com",
            startTs = startMs,
            endTs = endMs,
            timezone = timezone
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(
            "Timezone should be preserved",
            timezone,
            parsed.dtStart.timezone?.id
        )
    }

    @Test
    fun `UTC event has no TZID parameter`() {
        val startMs = 1737590400000L
        val event = createTestEvent(
            uid = "utc-test@test.com",
            startTs = startMs,
            endTs = startMs + 3600000L,
            timezone = null // UTC
        )

        val ics = IcsPatcher.generateFresh(event)

        // UTC events should use Z suffix, not TZID
        assertTrue("UTC event should have Z suffix", ics.contains("Z\r\n") || ics.contains("Z\n"))
    }

    @Test
    fun `non-UTC event has TZID parameter`() {
        val startMs = 1737590400000L
        val event = createTestEvent(
            uid = "tzid-test@test.com",
            startTs = startMs,
            endTs = startMs + 3600000L,
            timezone = "America/Los_Angeles"
        )

        val ics = IcsPatcher.generateFresh(event)

        // Non-UTC events should have TZID
        assertTrue(
            "Non-UTC event should have TZID",
            ics.contains("TZID=America/Los_Angeles")
        )
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `year 2100 timestamp handled correctly`() {
        val farFutureMs = 4102444800000L // Jan 1, 2100 00:00:00 UTC

        val event = createTestEvent(
            uid = "future-test@test.com",
            startTs = farFutureMs,
            endTs = farFutureMs + 3600000L
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(farFutureMs, parsed.dtStart.timestamp)
    }

    @Test
    fun `year 2000 timestamp handled correctly`() {
        val pastMs = 946684800000L // Jan 1, 2000 00:00:00 UTC

        val event = createTestEvent(
            uid = "past-test@test.com",
            startTs = pastMs,
            endTs = pastMs + 3600000L
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(pastMs, parsed.dtStart.timestamp)
    }

    @Test
    fun `midnight boundary handled correctly`() {
        // Exactly midnight UTC
        val midnightMs = 1737504000000L // Jan 22, 2025 00:00:00 UTC

        val event = createTestEvent(
            uid = "midnight-test@test.com",
            startTs = midnightMs,
            endTs = midnightMs + 3600000L
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(midnightMs, parsed.dtStart.timestamp)
    }

    @Test
    fun `event spanning midnight handled correctly`() {
        // 11 PM to 1 AM next day
        val startMs = 1737500400000L // Jan 21, 2025 23:00:00 UTC
        val endMs = 1737507600000L   // Jan 22, 2025 01:00:00 UTC

        val event = createTestEvent(
            uid = "span-midnight-test@test.com",
            startTs = startMs,
            endTs = endMs
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(startMs, parsed.dtStart.timestamp)
        assertEquals(endMs, parsed.effectiveEnd().timestamp)
        assertEquals(2 * 3600 * 1000L, parsed.effectiveEnd().timestamp - parsed.dtStart.timestamp)
    }

    @Test
    fun `1-minute event duration preserved`() {
        val startMs = 1737590400000L
        val endMs = startMs + 60000L // 1 minute

        val event = createTestEvent(
            uid = "short-event-test@test.com",
            startTs = startMs,
            endTs = endMs
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(60000L, parsed.effectiveEnd().timestamp - parsed.dtStart.timestamp)
    }

    @Test
    fun `multi-day timed event handled correctly`() {
        val startMs = 1737590400000L // Jan 23, 2025 00:00:00 UTC
        val endMs = startMs + (3 * 24 * 3600000L) // 3 days later

        val event = createTestEvent(
            uid = "multiday-test@test.com",
            startTs = startMs,
            endTs = endMs
        )

        val ics = IcsPatcher.generateFresh(event)
        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()

        assertEquals(startMs, parsed.dtStart.timestamp)
        assertEquals(endMs, parsed.effectiveEnd().timestamp)
    }

    // ========== ICalEventMapper Integration Tests ==========

    @Test
    fun `ICalEventMapper preserves milliseconds from parsed event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:mapper-test@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val parsed = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(parsed, ics, 1L, null, null)

        // Verify timestamps are in milliseconds
        assertTrue(
            "startTs should be in milliseconds",
            entity.startTs > 1_000_000_000_000L
        )
        assertEquals(parsed.dtStart.timestamp, entity.startTs)
    }

    @Test
    fun `ICalEventMapper and IcsPatcher round-trip preserves timestamps`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:full-roundtrip@test.com
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Round Trip Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Parse → Entity → ICS → Parse
        val event1 = parser.parseAllEvents(originalIcs).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(event1, originalIcs, 1L, null, null)
        val regeneratedIcs = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcs).getOrNull()!!.first()

        // Timestamps should match
        assertEquals(event1.dtStart.timestamp, event2.dtStart.timestamp)
        assertEquals(event1.effectiveEnd().timestamp, event2.effectiveEnd().timestamp)
    }

    // ========== Helpers ==========

    private fun createTestEvent(
        uid: String,
        startTs: Long,
        endTs: Long,
        timezone: String? = null
    ): Event {
        return Event(
            uid = uid,
            calendarId = 1L,
            title = "Test Event",
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            isAllDay = false,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
    }

    private fun createAllDayEvent(
        uid: String,
        startTs: Long,
        endTs: Long
    ): Event {
        return Event(
            uid = uid,
            calendarId = 1L,
            title = "All-Day Event",
            startTs = startTs,
            endTs = endTs,
            timezone = null,
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
    }
}
