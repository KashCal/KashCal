package org.onekash.kashcal.sync.parser.migration

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip preservation tests for KashCal's icaldav integration.
 *
 * These tests verify that iCalendar data survives:
 * 1. Parse → ICalEvent (icaldav)
 * 2. Map → Event (KashCal entity)
 * 3. IcsPatcher serialize → ICS string
 * 4. Parse → ICalEvent (icaldav)
 *
 * This is the core value proposition of N9 (Round-Trip Preservation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoundTripPreservationTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    @Test
    fun `simple event survives KashCal round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:kashcal-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Team Meeting
            DESCRIPTION:Weekly sync
            LOCATION:Conference Room
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Parse → Map → Serialize → Parse
        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Core fields preserved
        assertEquals(event1.uid, event2.uid)
        assertEquals(event1.summary, event2.summary)
        assertEquals(event1.description, event2.description)
        assertEquals(event1.location, event2.location)
        assertEquals(event1.dtStart.timestamp, event2.dtStart.timestamp)
    }

    @Test
    fun `X-APPLE properties preserved on round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud//EN
            BEGIN:VEVENT
            UID:apple-xprops-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Meeting with X-Properties
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS="123 Main St":geo:37.7,-122.4
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]

        // X-properties should be in rawProperties
        assertTrue(
            "X-APPLE properties should be preserved",
            event1.rawProperties.any { it.key.startsWith("X-APPLE") }
        )

        // Map to entity with rawIcal for preservation
        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)

        // extraProperties should contain X-properties
        assertNotNull("extraProperties should not be null", entity.extraProperties)
        assertTrue(
            "X-APPLE properties should be in extraProperties",
            entity.extraProperties!!.any { it.key.startsWith("X-APPLE") }
        )

        // Serialize and parse again
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // X-properties still present
        assertTrue(
            "X-APPLE properties should survive round-trip",
            event2.rawProperties.any { it.key.startsWith("X-APPLE") }
        )
    }

    @Test
    fun `multiple VALARMs preserved on round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-alarm-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Important Meeting
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 minutes before
            END:VALARM
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-PT5M
            DESCRIPTION:5 minutes before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals("Should parse 3 alarms", 3, event1.alarms.size)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        // Note: ICalEventMapper.reminders only keeps first 3, but alarmCount tracks total
        assertEquals("alarmCount should be 3", 3, entity.alarmCount)

        // Serialize with patching (preserves original alarms)
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // All 3 alarms preserved via patching
        assertEquals("All 3 alarms should survive round-trip", 3, event2.alarms.size)
    }

    @Test
    fun `VALARM with UID preserved on round-trip`() {
        // RFC 9074: Alarms can have UID property
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-uid-test-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Meeting with Alarm UID
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            UID:abc123-alarm-uuid
            DESCRIPTION:15 minute reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals(1, event1.alarms.size)
        assertNotNull("Alarm should have uid (RFC 9074)", event1.alarms[0].uid)
        assertEquals("abc123-alarm-uuid", event1.alarms[0].uid)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Alarm UID preserved via rawIcal patching
        assertNotNull("Alarm uid should survive round-trip", event2.alarms[0].uid)
    }

    @Test
    fun `ATTENDEEs preserved on round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:attendees-test-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Team Meeting
            ORGANIZER;CN=Boss:mailto:boss@example.com
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals("Should have 2 attendees", 2, event1.attendees.size)
        assertNotNull("Should have organizer", event1.organizer)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Attendees preserved via patching
        assertEquals("Attendees should survive round-trip", 2, event2.attendees.size)
        assertNotNull("Organizer should survive round-trip", event2.organizer)
    }

    @Test
    fun `CATEGORIES preserved on round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:categories-test-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Tagged Meeting
            CATEGORIES:WORK,IMPORTANT,MEETING
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals("Should have 3 categories", 3, event1.categories.size)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Categories preserved via rawProperties
        assertEquals("Categories should survive round-trip", 3, event2.categories.size)
    }

    @Test
    fun `recurring event with EXDATE survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-exdate-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Daily Standup
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20231217T140000Z
            EXDATE:20231220T140000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertNotNull("Should have RRULE", event1.rrule)
        assertEquals("Should have 2 EXDATEs", 2, event1.exdates.size)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertEquals("RRULE should be mapped", "FREQ=DAILY;COUNT=10", entity.rrule)
        assertNotNull("EXDATE should be mapped", entity.exdate)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        assertEquals("RRULE should survive", event1.rrule?.toICalString(), event2.rrule?.toICalString())
        assertEquals("EXDATEs should survive", event1.exdates.size, event2.exdates.size)
    }

    @Test
    fun `all-day event survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART;VALUE=DATE:20231215
            DTEND;VALUE=DATE:20231216
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertTrue("Should be all-day", event1.isAllDay)
        assertTrue("dtStart should be date-only", event1.dtStart.isDate)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertTrue("Entity should be all-day", entity.isAllDay)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        assertTrue("Should still be all-day", event2.isAllDay)
        assertTrue("Should still be date-only", event2.dtStart.isDate)
        assertEquals(event1.dtStart.toDayCode(), event2.dtStart.toDayCode())
    }

    @Test
    fun `event with timezone survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timezone-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART;TZID=America/New_York:20231215T090000
            DTEND;TZID=America/New_York:20231215T100000
            SUMMARY:New York Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals("America/New_York", event1.dtStart.timezone?.id)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertEquals("America/New_York", entity.timezone)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Timezone should be preserved
        assertEquals("America/New_York", event2.dtStart.timezone?.id)
        ParsedEventComparator.assertTimestampsEquivalent(
            event1.dtStart.timestamp,
            event2.dtStart.timestamp,
            "dtStart with timezone"
        )
    }

    @Test
    fun `event with DURATION survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:duration-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DURATION:PT1H30M
            SUMMARY:90 Minute Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertNotNull("Should have duration", event1.duration)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        // Entity stores effective end time, not duration
        val expectedEndTs = event1.dtStart.timestamp + 90 * 60 * 1000
        assertEquals(expectedEndTs, entity.endTs)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Effective end time should be equivalent
        ParsedEventComparator.assertTimestampsEquivalent(
            event1.effectiveEnd().timestamp,
            event2.effectiveEnd().timestamp,
            "effective end"
        )
    }

    @Test
    fun `CANCELLED status survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cancelled-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Cancelled Meeting
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals(org.onekash.icaldav.model.EventStatus.CANCELLED, event1.status)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertEquals("CANCELLED", entity.status)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        assertEquals(org.onekash.icaldav.model.EventStatus.CANCELLED, event2.status)
    }

    @Test
    fun `TENTATIVE status survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tentative-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Maybe Meeting
            STATUS:TENTATIVE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals(org.onekash.icaldav.model.EventStatus.TENTATIVE, event1.status)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertEquals("TENTATIVE", entity.status)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        assertEquals(org.onekash.icaldav.model.EventStatus.TENTATIVE, event2.status)
    }

    @Test
    fun `PRIVATE classification survives round-trip`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:private-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Private Event
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals(org.onekash.icaldav.model.Classification.PRIVATE, event1.classification)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertEquals("PRIVATE", entity.classification)

        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        assertEquals(org.onekash.icaldav.model.Classification.PRIVATE, event2.classification)
    }

    @Test
    fun `SEQUENCE number survives round-trip with increment`() {
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:sequence-roundtrip-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Meeting
            SEQUENCE:5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]
        assertEquals(5, event1.sequence)

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        assertEquals(5, entity.sequence)

        // IcsPatcher increments sequence on update
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // Sequence incremented by IcsPatcher.patch()
        assertEquals(6, event2.sequence)
    }

    @Test
    fun `iCloud real event structure preserved`() {
        // Simplified iCloud event structure
        val originalIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud Calendar 2426B118//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:icloud-real-event-1@icloud.com
            DTSTAMP:20231215T100000Z
            DTSTART;TZID=America/Los_Angeles:20231215T090000
            DTEND;TZID=America/Los_Angeles:20231215T100000
            SUMMARY:Team Standup
            DESCRIPTION:Daily sync meeting
            LOCATION:Zoom
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            X-WR-ALARMUID:icloud-alarm-uid-1
            DESCRIPTION:Event reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event1 = parser.parseAllEvents(originalIcal).getOrNull()!![0]

        val entity = ICalEventMapper.toEntity(event1, originalIcal, 1L, null, null)
        val regeneratedIcal = IcsPatcher.serialize(entity)
        val event2 = parser.parseAllEvents(regeneratedIcal).getOrNull()!![0]

        // All key fields preserved
        assertEquals(event1.uid, event2.uid)
        assertEquals(event1.summary, event2.summary)
        assertEquals(event1.description, event2.description)
        assertEquals(event1.location, event2.location)
        assertEquals("America/Los_Angeles", event2.dtStart.timezone?.id)
        assertEquals(1, event2.alarms.size)

        // X-APPLE property preserved
        assertTrue(
            "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR should be preserved",
            event2.rawProperties.containsKey("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR")
        )
    }
}
