package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneId

/**
 * Tests for ICalEventMapper: verifies correct mapping from icaldav ICalEvent
 * to KashCal Event entity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICalEventMapperTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ========== Basic Mapping Tests ==========

    @Test
    fun `maps basic event fields correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Test Event
            DESCRIPTION:Test description
            LOCATION:Conference Room
            STATUS:CONFIRMED
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val icalEvent = events.first()

        val entity = ICalEventMapper.toEntity(
            icalEvent = icalEvent,
            rawIcal = ics,
            calendarId = 1L,
            caldavUrl = "/calendars/user/calendar/event.ics",
            etag = "abc123"
        )

        assertEquals("test-001@kashcal.test", entity.uid)
        assertEquals("Test Event", entity.title)
        assertEquals("Test description", entity.description)
        assertEquals("Conference Room", entity.location)
        assertEquals("CONFIRMED", entity.status)
        assertEquals(1, entity.sequence)
        assertEquals(1L, entity.calendarId)
        assertEquals("/calendars/user/calendar/event.ics", entity.caldavUrl)
        assertEquals("abc123", entity.etag)
    }

    @Test
    fun `maps all-day event correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:Christmas
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertTrue("Should be all-day", entity.isAllDay)
        // Verify startTs is Dec 25 2025 00:00:00 UTC (1766620800000 ms)
        assertEquals("Dec 25 2025 00:00 UTC in ms", 1766620800000L, entity.startTs)
        // endTs should be Dec 25 23:59:59.999 (exclusive DTEND adjusted)
        assertTrue("endTs should be same day as startTs", entity.endTs / 86400000 == entity.startTs / 86400000)
    }

    @Test
    fun `maps timezone correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tz-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251225T100000
            DTEND;TZID=America/New_York:20251225T110000
            SUMMARY:NY Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals("America/New_York", entity.timezone)
        assertFalse(entity.isAllDay)
    }

    @Test
    fun `maps recurring event with RRULE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
            SUMMARY:Weekly Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have rrule", entity.rrule)
        assertTrue(entity.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(entity.rrule!!.contains("BYDAY=MO,WE,FR"))
    }

    @Test
    fun `maps EXDATE correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:exdate-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20251226T100000Z
            EXDATE:20251227T100000Z
            SUMMARY:Daily with Exceptions
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have exdate", entity.exdate)
        val exdates = entity.exdate!!.split(",")
        assertEquals("Should have 2 EXDATEs", 2, exdates.size)
    }

    // ========== RECURRENCE-ID Tests ==========

    @Test
    fun `maps exception event with RECURRENCE-ID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-001@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251226T100000Z
            DTSTART:20251226T140000Z
            DTEND:20251226T150000Z
            SUMMARY:Moved Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertTrue("Should be exception", ICalEventMapper.isException(events.first()))
        // originalInstanceTime should be the RECURRENCE-ID timestamp
        assertNotNull("Should have originalInstanceTime", entity.originalInstanceTime)
    }

    @Test
    fun `getImportId generates correct format for exception`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recid-001@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251226T100000Z
            DTSTART:20251226T140000Z
            DTEND:20251226T150000Z
            SUMMARY:Moved Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val importId = ICalEventMapper.getImportId(events.first())

        assertTrue(
            "ImportId should contain :RECID: for exceptions",
            importId.contains(":RECID:")
        )
        assertTrue(
            "ImportId should start with UID",
            importId.startsWith("recid-001@kashcal.test")
        )
    }

    @Test
    fun `getImportId returns UID for master events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:master-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Master Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val importId = ICalEventMapper.getImportId(events.first())

        assertEquals(
            "ImportId should equal UID for master events",
            "master-001@kashcal.test",
            importId
        )
    }

    // ========== Alarm Mapping Tests ==========

    @Test
    fun `maps single alarm correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should have 1 reminder", 1, entity.reminders!!.size)
        assertEquals("-PT15M", entity.reminders!!.first())
    }

    @Test
    fun `maps multiple alarms - only closest 3 by duration`() {
        // Alarms happen to be in sorted order here (15m, 30m, 1h, 1d, 1w)
        // Result: keeps 15m, 30m, 1h (closest 3)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-multi@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Many Alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val icalEvent = events.first()

        // icaldav parses all 5 alarms
        assertEquals("icaldav should parse all 5 alarms", 5, icalEvent.alarms.size)

        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        // Entity stores closest 3 reminders (sorted by duration)
        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should store only 3 reminders", 3, entity.reminders!!.size)
        // Verify sorted order: 15m, 30m, 1h (smallest durations)
        assertEquals("-PT15M", entity.reminders!![0])
        assertEquals("-PT30M", entity.reminders!![1])
        assertEquals("-PT1H", entity.reminders!![2])
    }

    @Test
    fun `skips RELATED=END alarms`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-related@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Related End Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT5M
            DESCRIPTION:5 min before end
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before start
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        // Should only include the START-related alarm
        assertNotNull(entity.reminders)
        assertEquals("Should have only 1 reminder (RELATED=END skipped)", 1, entity.reminders!!.size)
        assertEquals("-PT15M", entity.reminders!!.first())
    }

    // ========== Alarm Sorting Tests ==========

    @Test
    fun `alarms are sorted ascending by absolute duration`() {
        // Server sends alarms in arbitrary order: 1 day, 15 min, 1 hour
        // Expected: sorted by duration → 15 min, 1 hour, 1 day
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-sort@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Unsorted Alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should have 3 reminders", 3, entity.reminders!!.size)
        // Verify sorted order: smallest duration first
        assertEquals("First should be 15 min", "-PT15M", entity.reminders!![0])
        assertEquals("Second should be 1 hour", "-PT1H", entity.reminders!![1])
        assertEquals("Third should be 1 day", "-P1D", entity.reminders!![2])
    }

    @Test
    fun `positive triggers sorted after negative triggers`() {
        // Positive trigger (after event start) should sort to end
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-positive@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Positive Trigger
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:PT30M
            DESCRIPTION:30 min after start
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should have 3 reminders", 3, entity.reminders!!.size)
        // Sorted by abs(): 15m, 30m, 1h
        assertEquals("First should be 15 min before", "-PT15M", entity.reminders!![0])
        assertEquals("Second should be 30 min after", "PT30M", entity.reminders!![1])
        assertEquals("Third should be 1 hour before", "-PT1H", entity.reminders!![2])
    }

    @Test
    fun `single alarm remains unchanged`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-single@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Single Alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should have 1 reminder", 1, entity.reminders!!.size)
        assertEquals("-PT15M", entity.reminders!![0])
    }

    @Test
    fun `alarms beyond limit 3 are excluded after sorting - keeps smallest`() {
        // 5 alarms: 1 week, 1 day, 15 min, 1 hour, 30 min
        // After sorting: 15 min, 30 min, 1 hour, 1 day, 1 week
        // Take 3: 15 min, 30 min, 1 hour (smallest 3)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-limit@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with 5 Alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1W
            DESCRIPTION:1 week before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val icalEvent = events.first()

        // icaldav parses all 5 alarms
        assertEquals("icaldav should parse all 5 alarms", 5, icalEvent.alarms.size)

        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null)

        // Entity stores only closest 3 (by duration)
        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should store only 3 reminders", 3, entity.reminders!!.size)
        // Verify it's the smallest 3: 15 min, 30 min, 1 hour
        assertEquals("First should be 15 min", "-PT15M", entity.reminders!![0])
        assertEquals("Second should be 30 min", "-PT30M", entity.reminders!![1])
        assertEquals("Third should be 1 hour", "-PT1H", entity.reminders!![2])
    }

    // ========== Status and Transparency Tests ==========

    @Test
    fun `maps CANCELLED status`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cancelled-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Cancelled Event
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals("CANCELLED", entity.status)
    }

    @Test
    fun `maps TRANSPARENT transp`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:transp-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Free Time Event
            TRANSP:TRANSPARENT
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals("TRANSPARENT", entity.transp)
    }

    // ========== Organizer Tests ==========

    @Test
    fun `maps organizer correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:organizer-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting with Organizer
            ORGANIZER;CN=John Doe:mailto:john@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals("john@example.com", entity.organizerEmail)
        assertEquals("John Doe", entity.organizerName)
    }

    // ========== Raw Properties Tests ==========

    @Test
    fun `preserves extra properties in rawProperties`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:extra-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Extra Props
            X-CUSTOM-PROP:custom value
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have extraProperties", entity.extraProperties)
        assertTrue(
            "Should contain X-CUSTOM-PROP",
            entity.extraProperties!!.containsKey("X-CUSTOM-PROP")
        )
    }

    // ========== RFC 5545/7986 Extended Properties Tests ==========

    @Test
    fun `maps PRIORITY field correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:High Priority Event
            PRIORITY:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals(1, entity.priority)
    }

    @Test
    fun `maps GEO field to geoLat and geoLon`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:geo-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event at Apple Park
            GEO:37.334722;-122.008889
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have geoLat", entity.geoLat)
        assertNotNull("Should have geoLon", entity.geoLon)
        assertEquals(37.334722, entity.geoLat!!, 0.000001)
        assertEquals(-122.008889, entity.geoLon!!, 0.000001)
    }

    @Test
    fun `maps COLOR field to ARGB int`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:color-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Red Event
            COLOR:#FF0000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have color", entity.color)
        // #FF0000 with full alpha = 0xFFFF0000
        assertEquals(0xFFFF0000.toInt(), entity.color)
    }

    @Test
    fun `maps URL field correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:url-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Link
            URL:https://example.com/event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals("https://example.com/event", entity.url)
    }

    @Test
    fun `maps CATEGORIES field to list`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cat-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Categorized Event
            CATEGORIES:MEETING,WORK,IMPORTANT
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertNotNull("Should have categories", entity.categories)
        assertEquals(3, entity.categories!!.size)
        assertTrue(entity.categories!!.contains("MEETING"))
        assertTrue(entity.categories!!.contains("WORK"))
        assertTrue(entity.categories!!.contains("IMPORTANT"))
    }

    @Test
    fun `event without optional RFC 5545 fields uses defaults`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:minimal-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Minimal Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertEquals(0, entity.priority) // Default
        assertNull(entity.geoLat)
        assertNull(entity.geoLon)
        assertNull(entity.color)
        assertNull(entity.url)
        assertNull(entity.categories)
    }

    // ========== Multi-Day All-Day Event Tests ==========

    @Test
    fun `multi-day all-day event has correct endTs - 5 day event`() {
        // Simulates "Ellie: No School" Mar 16-20 (5 days)
        // iCloud sends DTEND=20260321 (exclusive per RFC 5545)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 18.7.2//EN
            BEGIN:VEVENT
            UID:ellie-no-school@test
            DTSTAMP:20250813T013400Z
            DTSTART;VALUE=DATE:20260316
            DTEND;VALUE=DATE:20260321
            SUMMARY:Ellie: No School
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertTrue("Should be all-day", entity.isAllDay)

        // startTs should be Mar 16 00:00:00 UTC
        val startDate = java.time.Instant.ofEpochMilli(entity.startTs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        assertEquals("Start should be Mar 16", java.time.LocalDate.of(2026, 3, 16), startDate)

        // endTs should be Mar 20 23:59:59.999 UTC (exclusive DTEND adjusted by -1ms)
        // NOT Mar 21!
        val endDate = java.time.Instant.ofEpochMilli(entity.endTs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        assertEquals(
            "End should be Mar 20 (DTEND is exclusive, so 21 - 1ms = 20)",
            java.time.LocalDate.of(2026, 3, 20),
            endDate
        )

        // Verify total days = 5
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
        assertEquals("Should span 5 days (Mar 16, 17, 18, 19, 20)", 5, totalDays.toInt())
    }

    @Test
    fun `single-day all-day event has correct endTs`() {
        // Simulates "Neil: No School" Feb 16 (single day)
        // iCloud sends DTEND=20260217 (exclusive per RFC 5545)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 18.7.2//EN
            BEGIN:VEVENT
            UID:neil-no-school@test
            DTSTAMP:20251204T214700Z
            DTSTART;VALUE=DATE:20260216
            DTEND;VALUE=DATE:20260217
            SUMMARY:Neil: No School
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null)

        assertTrue("Should be all-day", entity.isAllDay)

        // startTs should be Feb 16 00:00:00 UTC
        val startDate = java.time.Instant.ofEpochMilli(entity.startTs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        assertEquals("Start should be Feb 16", java.time.LocalDate.of(2026, 2, 16), startDate)

        // endTs should be Feb 16 23:59:59.999 UTC (exclusive DTEND adjusted by -1ms)
        // NOT Feb 17!
        val endDate = java.time.Instant.ofEpochMilli(entity.endTs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        assertEquals(
            "End should be Feb 16 (DTEND is exclusive, so 17 - 1ms = 16)",
            java.time.LocalDate.of(2026, 2, 16),
            endDate
        )

        // Verify it's a single day event
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
        assertEquals("Should be 1 day", 1, totalDays.toInt())
    }
}
