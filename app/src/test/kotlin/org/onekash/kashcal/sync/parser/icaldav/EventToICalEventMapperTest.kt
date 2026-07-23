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
        // Preserve the existing null-token importId behavior exactly — not a bug to 'fix' here.
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

    // ========== Exception mapper attendees ==========

    @Test
    fun `exception mapper attendees default empty preserves existing callers`() {
        // No `attendees =` argument: default-empty keeps IcsExporter and any
        // existing call sites compiling and behaving as they did before.
        val master = baseEvent(uid = "m-default")
        val exception = baseEvent(originalInstanceTime = 1709740800000L)
        val ical = EventToICalEventMapper.toICalEvent(master, exception)
        assertTrue(
            "Default-empty attendees: exception VEVENT must serialize with no ATTENDEEs",
            ical.attendees.isEmpty()
        )
    }

    @Test
    fun `exception mapper emits passed-through attendees`() {
        // Fix for the attendee-loss bug: when callers pass attendees,
        // they must reach the emitted ICalEvent so per-exception attendee
        // lists survive recurring-event push.
        // A real schedulable exception inherits the master's resolved
        // ORGANIZER (EventWriter builds it from modifiedEvent.copy), so the
        // fixture carries one — emitting ATTENDEE without ORGANIZER violates
        // RFC 6638 §3.1 and is now blocked by the generator guard.
        val master = baseEvent(uid = "m-with-attendees", organizerEmail = "host@example.test")
        val exception = baseEvent(
            originalInstanceTime = 1709740800000L,
            organizerEmail = "host@example.test"
        )
        val attendees = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                id = 1L,
                eventId = 99L,
                address = "mailto:alice@example.test",
                displayName = "Alice",
                partstat = "ACCEPTED",
                role = "REQ-PARTICIPANT",
                sortOrder = 0
            ),
            org.onekash.kashcal.data.db.entity.Attendee(
                id = 2L,
                eventId = 99L,
                address = "mailto:bob@example.test",
                displayName = "Bob",
                partstat = "NEEDS-ACTION",
                role = "REQ-PARTICIPANT",
                sortOrder = 1
            )
        )

        val ical = EventToICalEventMapper.toICalEvent(master, exception, attendees)

        assertEquals(2, ical.attendees.size)
        val emails = ical.attendees.map { it.email }.toSet()
        assertTrue("alice@example.test" in emails)
        assertTrue("bob@example.test" in emails)
        // PARTSTAT round-trips through the icaldav-core enum mapping.
        val alice = ical.attendees.first { it.email == "alice@example.test" }
        assertEquals(org.onekash.icaldav.model.PartStat.ACCEPTED, alice.partStat)
    }

    @Test
    fun `exception mapper preserves attendee sort order`() {
        val master = baseEvent(uid = "m-sort", organizerEmail = "host@example.test")
        val exception = baseEvent(
            originalInstanceTime = 1709740800000L,
            organizerEmail = "host@example.test"
        )
        // Pass in reverse sortOrder; emitter must respect order, not list position.
        val attendees = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:second@example.test",
                partstat = "ACCEPTED", role = "REQ-PARTICIPANT", sortOrder = 1
            ),
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:first@example.test",
                partstat = "ACCEPTED", role = "REQ-PARTICIPANT", sortOrder = 0
            )
        )
        val ical = EventToICalEventMapper.toICalEvent(master, exception, attendees)
        // Emitted in input order — caller controls ordering.
        assertEquals("second@example.test", ical.attendees[0].email)
        assertEquals("first@example.test", ical.attendees[1].email)
    }

    @Test
    fun `non-exception mapper attendees default empty preserves existing callers`() {
        // The single-arg overload also gets a defaulted attendees parameter.
        val event = baseEvent(uid = "single")
        val ical = EventToICalEventMapper.toICalEvent(event)
        assertTrue(ical.attendees.isEmpty())
    }

    // ===== ATTENDEE requires ORGANIZER (RFC 6638 §3.1) =====

    @Test
    fun `standalone mapper drops attendees when organizer is null`() {
        // A non-mailto-schedulable account (non-email login) resolves no
        // ORGANIZER. Emitting ATTENDEE without ORGANIZER violates RFC 6638 §3.1
        // and conformant servers reject the PUT — so the guard drops them.
        val event = baseEvent(uid = "no-org", organizerEmail = null)
        val attendees = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:alice@example.test",
                partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT", sortOrder = 0
            ),
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:bob@example.test",
                partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT", sortOrder = 1
            )
        )
        val ical = EventToICalEventMapper.toICalEvent(event, attendees)
        assertNull(ical.organizer)
        assertTrue(ical.attendees.isEmpty())
    }

    @Test
    fun `standalone mapper emits attendees when organizer present`() {
        // Regression guard for the happy path: organizer set -> attendees flow.
        val event = baseEvent(uid = "has-org", organizerEmail = "host@example.test")
        val attendees = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:alice@example.test",
                partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT", sortOrder = 0
            ),
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:bob@example.test",
                partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT", sortOrder = 1
            )
        )
        val ical = EventToICalEventMapper.toICalEvent(event, attendees)
        assertNotNull(ical.organizer)
        assertEquals(2, ical.attendees.size)
    }

    @Test
    fun `exception mapper drops attendees when organizer is null`() {
        val master = baseEvent(uid = "m-no-org", organizerEmail = null)
        val exception = baseEvent(originalInstanceTime = 1709740800000L, organizerEmail = null)
        val attendees = listOf(
            org.onekash.kashcal.data.db.entity.Attendee(
                eventId = 99L, address = "mailto:alice@example.test",
                partstat = "ACCEPTED", role = "REQ-PARTICIPANT", sortOrder = 0
            )
        )
        val ical = EventToICalEventMapper.toICalEvent(master, exception, attendees)
        assertNull(ical.organizer)
        assertTrue(ical.attendees.isEmpty())
    }

    // ========== dtStartOf — shared Room-Event → DTSTART reconstruction ==========

    @Test
    fun `dtStartOf reconstructs a timed TZID DTSTART to known values`() {
        // Pin against expected literals, NOT against toICalEvent (which is
        // implemented via dtStartOf, so comparing the two would be tautological).
        val event = baseEvent(
            startTs = 1709740800000L,
            timezone = "America/Chicago",
            isAllDay = false,
        )
        val dt = EventToICalEventMapper.dtStartOf(event)
        assertEquals(1709740800000L, dt.timestamp)
        assertEquals(ZoneId.of("America/Chicago"), dt.timezone)
        assertEquals(false, dt.isDate)
    }

    @Test
    fun `dtStartOf reconstructs a UTC DTSTART to known values`() {
        val event = baseEvent(startTs = 1709740800000L, timezone = null, isAllDay = false)
        val dt = EventToICalEventMapper.dtStartOf(event)
        assertEquals(1709740800000L, dt.timestamp)
        assertNull("UTC/floating event has null zone", dt.timezone)
        assertEquals(false, dt.isDate)
    }

    @Test
    fun `dtStartOf reconstructs an all-day DTSTART to known values`() {
        val event = baseEvent(startTs = 1709740800000L, timezone = null, isAllDay = true)
        val dt = EventToICalEventMapper.dtStartOf(event)
        assertEquals(1709740800000L, dt.timestamp)
        assertEquals(true, dt.isDate)
    }
}
