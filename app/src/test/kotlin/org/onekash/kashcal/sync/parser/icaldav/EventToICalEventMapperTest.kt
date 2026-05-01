package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.icaldav.model.Classification
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Transparency
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import java.time.ZoneId

/**
 * Tests for `EventToICalEventMapper` — the shared Event -> ICalEvent mapping used
 * by IcsPatcher (sync push) and IcsExporter (export).
 *
 * Mapper preserves behavior of the previously-inline construction in
 * IcsPatcher.generateFresh and IcsPatcher.generateException.
 */
class EventToICalEventMapperTest {

    private fun baseEvent(
        uid: String = "evt-uid-1",
        importId: String? = "evt-uid-1",
        title: String = "Event Title",
        startTs: Long = 1709740800000L, // 2024-03-06 12:00 UTC
        endTs: Long = 1709744400000L,   // 2024-03-06 13:00 UTC
        isAllDay: Boolean = false,
        timezone: String? = null,
        rrule: String? = null,
        exdate: String? = null,
        rdate: String? = null,
        reminders: List<String>? = null,
        categories: List<String>? = null,
        extraProperties: Map<String, String>? = null,
        organizerEmail: String? = null,
        organizerName: String? = null,
        sequence: Int = 0,
        status: String = "CONFIRMED",
        transp: String = "OPAQUE",
        classification: String = "PUBLIC",
        color: Int? = null,
        priority: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null,
        url: String? = null,
        dtstamp: Long = 1709640000000L,
        originalInstanceTime: Long? = null,
    ) = Event(
        id = 1L,
        uid = uid,
        importId = importId,
        calendarId = 1L,
        title = title,
        location = null,
        description = null,
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
        duration = null,
        originalEventId = null,
        originalInstanceTime = originalInstanceTime,
        originalSyncId = null,
        reminders = reminders,
        alarmCount = 0,
        extraProperties = extraProperties,
        rawIcal = null,
        dtstamp = dtstamp,
        caldavUrl = null,
        etag = null,
        sequence = sequence,
        syncStatus = SyncStatus.SYNCED,
        priority = priority,
        geoLat = geoLat,
        geoLon = geoLon,
        color = color,
        url = url,
        categories = categories,
    )

    // ========== Standalone mapper ==========

    @Test
    fun `standalone mapper preserves core fields`() {
        val event = baseEvent(
            title = "Team Meeting",
            timezone = "America/New_York",
            categories = listOf("Work", "Important"),
            sequence = 3,
            priority = 5,
            url = "https://example.com",
        )

        val ical = EventToICalEventMapper.toICalEvent(event)

        assertEquals("evt-uid-1", ical.uid)
        assertEquals("evt-uid-1", ical.importId)
        assertEquals("Team Meeting", ical.summary)
        assertEquals(EventStatus.CONFIRMED, ical.status)
        assertEquals(Transparency.OPAQUE, ical.transparency)
        assertEquals(Classification.PUBLIC, ical.classification)
        assertEquals(3, ical.sequence)
        assertEquals(5, ical.priority)
        assertEquals("https://example.com", ical.url)
        assertEquals(listOf("Work", "Important"), ical.categories)
        assertNull(ical.recurrenceId)
    }

    @Test
    fun `standalone mapper importId falls back to uid when null`() {
        val event = baseEvent(uid = "u-only", importId = null)
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertEquals("u-only", ical.importId)
    }

    @Test
    fun `standalone mapper handles null timezone as floating`() {
        val event = baseEvent(timezone = null)
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNull(ical.dtStart.timezone)
    }

    @Test
    fun `standalone mapper handles invalid timezone by floating-fall-back`() {
        val event = baseEvent(timezone = "Not/A/Real_Zone_XYZ")
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNull(ical.dtStart.timezone)
    }

    @Test
    fun `standalone mapper resolves valid timezone`() {
        val event = baseEvent(timezone = "America/New_York")
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertEquals(ZoneId.of("America/New_York"), ical.dtStart.timezone)
    }

    @Test
    fun `standalone mapper all-day adjusts endTs to exclusive`() {
        val inclusiveEnd = 1709769599999L  // 2024-03-06 23:59:59.999 UTC
        val event = baseEvent(
            startTs = 1709683200000L,
            endTs = inclusiveEnd,
            isAllDay = true,
        )
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNotNull(ical.dtEnd)
        // exclusive = inclusive + 1
        assertEquals(inclusiveEnd + 1, ical.dtEnd!!.timestamp)
    }

    @Test
    fun `standalone mapper parses rrule`() {
        val event = baseEvent(rrule = "FREQ=WEEKLY;BYDAY=MO")
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNotNull(ical.rrule)
    }

    @Test
    fun `standalone mapper includes organizer when email present`() {
        val event = baseEvent(
            organizerEmail = "a@example.com",
            organizerName = "Alice",
        )
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNotNull(ical.organizer)
        assertEquals("a@example.com", ical.organizer?.email)
        assertEquals("Alice", ical.organizer?.name)
    }

    @Test
    fun `standalone mapper omits organizer when email null`() {
        val event = baseEvent(organizerEmail = null)
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNull(ical.organizer)
    }

    @Test
    fun `standalone mapper converts reminders to alarms`() {
        val event = baseEvent(reminders = listOf("-PT15M", "-PT1H"))
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertEquals(2, ical.alarms.size)
    }

    @Test
    fun `standalone mapper geo emits formatted string when both lat-lon set`() {
        val event = baseEvent(geoLat = 37.7749, geoLon = -122.4194)
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertEquals("37.7749;-122.4194", ical.geo)
    }

    @Test
    fun `standalone mapper geo null when either missing`() {
        val event = baseEvent(geoLat = 37.7749, geoLon = null)
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertNull(ical.geo)
    }

    @Test
    fun `standalone mapper rawProperties from extraProperties`() {
        val event = baseEvent(extraProperties = mapOf("X-CUSTOM-KEY" to "value"))
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertEquals("value", ical.rawProperties["X-CUSTOM-KEY"])
    }

    // ========== Exception mapper ==========

    @Test
    fun `exception mapper uid equals master uid`() {
        val master = baseEvent(uid = "master-uid-xyz", importId = "master-uid-xyz")
        val exception = baseEvent(uid = "exception-has-different-uid", originalInstanceTime = 1709740800000L)
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertEquals("master-uid-xyz", ical.uid)
    }

    @Test
    fun `exception mapper recurrenceId built from originalInstanceTime`() {
        val master = baseEvent(uid = "m-1")
        val originalInstance = 1709740800000L // 2024-03-06 12:00 UTC
        val exception = baseEvent(originalInstanceTime = originalInstance, timezone = "America/New_York")
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertNotNull(ical.recurrenceId)
        assertEquals(originalInstance, ical.recurrenceId!!.timestamp)
        assertEquals(ZoneId.of("America/New_York"), ical.recurrenceId!!.timezone)
    }

    @Test
    fun `exception mapper rrule is null even when exception rrule set`() {
        val master = baseEvent(uid = "m-2", rrule = "FREQ=WEEKLY;BYDAY=MO")
        val exception = baseEvent(rrule = "FREQ=DAILY", originalInstanceTime = 1L)
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertNull(ical.rrule)
    }

    @Test
    fun `exception mapper exdates and rdates are empty`() {
        val master = baseEvent(uid = "m-3")
        val exception = baseEvent(
            exdate = "123,456",
            rdate = "789",
            originalInstanceTime = 1L,
        )
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertTrue(ical.exdates.isEmpty())
        assertTrue(ical.rdates.isEmpty())
    }

    @Test
    fun `exception mapper importId format matches RECID pattern when null`() {
        val master = baseEvent(uid = "master-abc")
        val exception = baseEvent(importId = null, originalInstanceTime = 1709740800000L)
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertEquals("master-abc:RECID:1709740800000", ical.importId)
    }

    @Test
    fun `exception mapper originalInstanceTime null produces null-token importId (pre-existing behavior)`() {
        // Per tracker note: preserve IcsPatcher.kt:274 behavior exactly. Do not 'fix' as part of this chunk.
        val master = baseEvent(uid = "master-def")
        val exception = baseEvent(importId = null, originalInstanceTime = null)
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertEquals("master-def:RECID:null", ical.importId)
    }

    @Test
    fun `exception mapper preserves exception importId when set`() {
        val master = baseEvent(uid = "m-5")
        val exception = baseEvent(importId = "explicit:RECID:7", originalInstanceTime = 7L)
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertEquals("explicit:RECID:7", ical.importId)
    }
}
