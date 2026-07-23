package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        ).event

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
    fun `toEntity drops ACTION_NONE sentinel from reminders and alarmCount`() {
        // Apple's RFC 9074 ACTION:NONE sentinel (1976 absolute trigger) must never
        // become a reminder duration or inflate alarmCount on pull — otherwise its
        // (trigger - dtStart) offset surfaces as a ~-18000-day phantom reminder.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 26.1//EN
            BEGIN:VEVENT
            UID:none-pull@kashcal.test
            DTSTAMP:20260603T120000Z
            DTSTART;VALUE=DATE:20260605
            DTEND;VALUE=DATE:20260606
            SUMMARY:test alert
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:PT9H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:-P1DT15H
            END:VALARM
            BEGIN:VALARM
            ACTION:NONE
            TRIGGER;VALUE=DATE-TIME:19760401T005545Z
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("alarmCount excludes NONE sentinel", 2, entity.alarmCount)
        val reminders = entity.reminders.orEmpty()
        assertEquals("Only the 2 real DISPLAY alarms become reminders", 2, reminders.size)
        assertFalse(
            "No phantom multi-day reminder from the 1976 absolute trigger",
            reminders.any { it.contains("18") || it.startsWith("-P1") && it.length > 8 }
        )
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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
    fun `stored EXDATE and stored originalInstanceTime agree for the same excluded slot - TZID datetime`() {
        // Guards the prune's EXDATE-gate for the real device shape: master
        // DTSTART, EXDATE, and RECURRENCE-ID are all TZID=America/Chicago
        // datetimes at the SAME original slot. The prune compares an exception's
        // stored originalInstanceTime (normalized via normalizeRecurrenceId
        // against the resolved master) against the master's stored exdate set
        // (normalized via normalizeToMasterValueType against the master's own
        // DTSTART). Those are two different code paths; if they ever produced
        // different ms for the same slot, the prune would silently never fire
        // and the "occurrence not deleted" bug would return. Assert they agree.
        val masterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tz-prune-uid
            DTSTAMP:20260721T000000Z
            DTSTART;TZID=America/Chicago:20260720T200000
            DTEND;TZID=America/Chicago:20260720T203000
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE;TZID=America/Chicago:20260722T200000
            SUMMARY:Recur
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tz-prune-uid
            DTSTAMP:20260721T000000Z
            RECURRENCE-ID;TZID=America/Chicago:20260722T200000
            DTSTART;TZID=America/Chicago:20260722T085500
            DTEND;TZID=America/Chicago:20260722T092500
            SUMMARY:Recur edited
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val master = parser.parseAllEvents(masterIcs).getOrNull()!!.first()
        val exception = parser.parseAllEvents(exceptionIcs).getOrNull()!!.first()

        // Master stored exdate (the set the prune's exdateSet is parsed from).
        val storedExdate = ICalEventMapper.toEntity(master, masterIcs, 1L, null, null)
            .event.exdate!!.split(",").map { it.trim().toLong() }.toSet()

        // Exception stored originalInstanceTime (what the prune tests membership of).
        val storedInstance = ICalEventMapper.toEntity(
            exception, exceptionIcs, 1L, null, null, masterDtStart = master.dtStart
        ).event.originalInstanceTime

        assertNotNull(storedInstance)
        assertTrue(
            "EXDATE-gate would never fire: master exdate set $storedExdate does not contain the " +
                "exception's stored originalInstanceTime $storedInstance for the same excluded slot.",
            storedInstance in storedExdate
        )
    }

    @Test
    fun `normalized lookup key matches stored originalInstanceTime for value-type-mismatched RECURRENCE-ID`() {
        // Timed master, but the exception carries a DATE-form RECURRENCE-ID
        // (value-type mismatch: RFC 5545 §3.8.4.4 says RECURRENCE-ID MUST share
        // DTSTART's value type, but peer clients emit the mismatch and servers
        // preserve it). The mapper NORMALIZES the stored originalInstanceTime to
        // the master's local time-of-day. The exception pull-back LOOKUP must
        // normalize the same way, or the raw midnight-UTC key misses the stored
        // row and the exception is wrongly treated as NEW. This asserts the two
        // derivations agree once both are normalized against the master DTSTART.
        val masterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:mismatch-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Timed Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:mismatch-001@kashcal.test
            DTSTAMP:20251226T100000Z
            RECURRENCE-ID;VALUE=DATE:20251226
            DTSTART:20251226T160000Z
            DTEND:20251226T170000Z
            SUMMARY:Moved Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val master = parser.parseAllEvents(masterIcs).getOrNull()!!.first()
        val exception = parser.parseAllEvents(exceptionIcs).getOrNull()!!.first()

        // What the mapper STORES. The caller passes the MASTER's DTSTART; the
        // stored originalInstanceTime must be normalized against THAT, not the
        // exception's own DTSTART. (Regression guard: a local named masterDtStart
        // once shadowed this parameter and normalized against the exception's own
        // DTSTART, severing the caller's value.)
        val storedInstanceTime = ICalEventMapper.toEntity(
            icalEvent = exception,
            rawIcal = exceptionIcs,
            calendarId = 1L,
            caldavUrl = null,
            etag = null,
            masterDtStart = master.dtStart,
        ).event.originalInstanceTime

        // The lookup key PullStrategy builds: RECURRENCE-ID normalized against the
        // master DTSTART. The store path must agree with it or the exception
        // pull-back lookup misses the stored row and re-adds it as a NEW event.
        val normalizedLookupTime = ICalEventMapper.normalizeRecurrenceId(
            recurrenceId = exception.recurrenceId,
            masterDtStart = master.dtStart,
        )?.timestamp

        // Guard: the raw midnight-UTC form genuinely diverges from the normalized
        // value, so this fixture exercises a real mismatch, not a trivial equality.
        assertNotEquals(
            "Fixture must exercise a real value-type mismatch (raw != normalized)",
            normalizedLookupTime, exception.recurrenceId?.timestamp
        )

        assertNotNull(storedInstanceTime)
        assertNotNull(normalizedLookupTime)
        assertEquals(
            "Stored originalInstanceTime must be normalized against the MASTER DTSTART " +
                "(the caller's parameter), matching the lookup key. " +
                "stored=$storedInstanceTime normalizedLookup=$normalizedLookupTime",
            normalizedLookupTime, storedInstanceTime
        )
    }

    @Test
    fun `toEntity with null masterDtStart falls back to raw RECURRENCE-ID timestamp`() {
        // Documented fallback: when the master DTSTART isn't available (orphan
        // exception, or master not resolved), the mapper stores the RECURRENCE-ID
        // verbatim. The pull-side lookup uses the same null-master fallback, so
        // both still agree. Guards normalizeRecurrenceId's masterDtStart==null
        // pass-through branch.
        val exceptionIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:mismatch-002@kashcal.test
            DTSTAMP:20251226T100000Z
            RECURRENCE-ID;VALUE=DATE:20251226
            DTSTART:20251226T160000Z
            DTEND:20251226T170000Z
            SUMMARY:Orphan Moved Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val exception = parser.parseAllEvents(exceptionIcs).getOrNull()!!.first()

        val stored = ICalEventMapper.toEntity(
            icalEvent = exception,
            rawIcal = exceptionIcs,
            calendarId = 1L,
            caldavUrl = null,
            etag = null,
            masterDtStart = null,
        ).event.originalInstanceTime

        assertEquals(
            "With no master DTSTART, stored originalInstanceTime must equal the raw " +
                "RECURRENCE-ID timestamp (pass-through).",
            exception.recurrenceId?.timestamp, stored
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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should have 1 reminder", 1, entity.reminders!!.size)
        assertEquals("-PT15M", entity.reminders!!.first())
    }

    @Test
    fun `maps multiple alarms - keeps closest 5 by duration`() {
        // Alarms happen to be in sorted order here (15m, 30m, 1h, 1d, 1w)
        // All 5 fit within the limit
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

        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event

        // Entity stores all 5 reminders (within limit of 5, sorted by duration)
        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should store all 5 reminders", 5, entity.reminders!!.size)
        // Verify sorted order: 15m, 30m, 1h, 1d, 1w (DurationUtils normalizes 1W to 7D)
        assertEquals("-PT15M", entity.reminders!![0])
        assertEquals("-PT30M", entity.reminders!![1])
        assertEquals("-PT1H", entity.reminders!![2])
        assertEquals("-P1D", entity.reminders!![3])
        assertEquals("-P7D", entity.reminders!![4])
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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should have 1 reminder", 1, entity.reminders!!.size)
        assertEquals("-PT15M", entity.reminders!![0])
    }

    @Test
    fun `alarms beyond limit 5 are excluded after sorting - keeps smallest from 5 alarms`() {
        // 5 alarms: 1 week, 1 day, 15 min, 1 hour, 30 min
        // After sorting: 15 min, 30 min, 1 hour, 1 day, 1 week
        // Take 5: all 5 fit within the limit
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

        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event

        // Entity stores all 5 (within limit of 5)
        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should store all 5 reminders", 5, entity.reminders!!.size)
        // Verify sorted by duration: 15 min, 30 min, 1 hour, 1 day, 1 week
        // Note: DurationUtils.format() normalizes 1W to 7D
        assertEquals("First should be 15 min", "-PT15M", entity.reminders!![0])
        assertEquals("Second should be 30 min", "-PT30M", entity.reminders!![1])
        assertEquals("Third should be 1 hour", "-PT1H", entity.reminders!![2])
        assertEquals("Fourth should be 1 day", "-P1D", entity.reminders!![3])
        assertEquals("Fifth should be 1 week (7 days)", "-P7D", entity.reminders!![4])
    }

    @Test
    fun `alarms beyond limit 5 are excluded after sorting - keeps smallest 5`() {
        // 7 alarms: 1 week, 1 day, 15 min, 1 hour, 30 min, 2 hours, 4 hours
        // After sorting: 15 min, 30 min, 1 hour, 2 hours, 4 hours, 1 day, 1 week
        // Take 5: 15 min, 30 min, 1 hour, 2 hours, 4 hours
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-limit-7@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with 7 Alarms
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
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT2H
            DESCRIPTION:2 hours before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT4H
            DESCRIPTION:4 hours before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val icalEvent = events.first()

        // icaldav parses all 7 alarms
        assertEquals("icaldav should parse all 7 alarms", 7, icalEvent.alarms.size)

        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event

        // Entity stores only closest 5 (by duration)
        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Should store only 5 reminders", 5, entity.reminders!!.size)
        // Verify it's the smallest 5: 15 min, 30 min, 1 hour, 2 hours, 4 hours
        assertEquals("First should be 15 min", "-PT15M", entity.reminders!![0])
        assertEquals("Second should be 30 min", "-PT30M", entity.reminders!![1])
        assertEquals("Third should be 1 hour", "-PT1H", entity.reminders!![2])
        assertEquals("Fourth should be 2 hours", "-PT2H", entity.reminders!![3])
        assertEquals("Fifth should be 4 hours", "-PT4H", entity.reminders!![4])

        // alarmCount should reflect total count
        assertEquals("alarmCount should be 7", 7, entity.alarmCount)
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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        // Simulates "Alex: No School" Mar 16-20 (5 days)
        // iCloud sends DTEND=20260321 (exclusive per RFC 5545)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iPhone OS 18.7.2//EN
            BEGIN:VEVENT
            UID:no-school-test@test
            DTSTAMP:20250813T013400Z
            DTSTART;VALUE=DATE:20260316
            DTEND;VALUE=DATE:20260321
            SUMMARY:Alex: No School
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

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

    // ========== Server-supplied CREATED / LAST-MODIFIED Tests ==========

    // 2020-01-15T12:00:00Z as unix ms
    private val expectedCreatedMs = 1_579_089_600_000L
    // 2024-06-15T08:30:00Z as unix ms
    private val expectedLastModifiedMs = 1_718_440_200_000L

    @Test
    fun `preserves server-supplied CREATED and LAST-MODIFIED when both present`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamps-both@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            CREATED:20200115T120000Z
            LAST-MODIFIED:20240615T083000Z
            SUMMARY:Timestamped Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event

        assertEquals(
            "Event.createdAt must match server CREATED",
            expectedCreatedMs,
            entity.createdAt
        )
        assertEquals(
            "Event.serverModifiedAt must match server LAST-MODIFIED",
            expectedLastModifiedMs,
            entity.serverModifiedAt
        )
    }

    @Test
    fun `preserves timestamps across two successive mappings of the same ICS`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamps-idempotent@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            CREATED:20200115T120000Z
            LAST-MODIFIED:20240615T083000Z
            SUMMARY:Idempotent Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent1 = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity1 = ICalEventMapper.toEntity(icalEvent1, ics, 1L, null, null).event

        // Simulate a second pull of the same unchanged server event.
        val icalEvent2 = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity2 = ICalEventMapper.toEntity(icalEvent2, ics, 1L, null, null).event

        assertEquals(
            "createdAt must be identical across two mappings of the same ICS",
            entity1.createdAt,
            entity2.createdAt
        )
        assertEquals(
            "serverModifiedAt must be identical across two mappings of the same ICS",
            entity1.serverModifiedAt,
            entity2.serverModifiedAt
        )
    }

    @Test
    fun `falls back to now for CREATED when absent but respects LAST-MODIFIED`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamps-no-created@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            LAST-MODIFIED:20240615T083000Z
            SUMMARY:No CREATED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val before = System.currentTimeMillis()
        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event
        val after = System.currentTimeMillis()

        assertEquals(
            "serverModifiedAt must match server LAST-MODIFIED",
            expectedLastModifiedMs,
            entity.serverModifiedAt
        )
        assertTrue(
            "createdAt should fall back to sync-time now() when CREATED absent",
            entity.createdAt in before..after
        )
    }

    @Test
    fun `falls back to now for LAST-MODIFIED when absent but respects CREATED`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamps-no-lastmod@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            CREATED:20200115T120000Z
            SUMMARY:No LAST-MODIFIED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val before = System.currentTimeMillis()
        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event
        val after = System.currentTimeMillis()

        assertEquals(
            "createdAt must match server CREATED",
            expectedCreatedMs,
            entity.createdAt
        )
        assertTrue(
            "serverModifiedAt should fall back to sync-time now() when LAST-MODIFIED absent",
            entity.serverModifiedAt!! in before..after
        )
    }

    @Test
    fun `falls back to now for both when neither present`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamps-absent@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Minimal Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val before = System.currentTimeMillis()
        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event
        val after = System.currentTimeMillis()

        assertTrue(
            "createdAt should fall back to sync-time now() when CREATED absent",
            entity.createdAt in before..after
        )
        assertTrue(
            "serverModifiedAt should fall back to sync-time now() when LAST-MODIFIED absent",
            entity.serverModifiedAt!! in before..after
        )
    }

    @Test
    fun `server-supplied CREATED from 2020 is not overwritten with sync clock`() {
        // Regression guard for the bug where a server-supplied CREATED was
        // overwritten with the local sync clock:
        // pre-fix code stomped CREATED with System.currentTimeMillis().
        // Any 2020 timestamp is well below 2020-09-13T12:26:40Z (1_600_000_000_000 ms).
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:timestamps-regression@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            CREATED:20200115T120000Z
            SUMMARY:Regression Guard
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalEvent = parser.parseAllEvents(ics).getOrNull()!!.first()
        val entity = ICalEventMapper.toEntity(icalEvent, ics, 1L, null, null).event

        assertTrue(
            "createdAt must not be stomped by sync clock; got ${entity.createdAt}",
            entity.createdAt < 1_600_000_000_000L
        )
    }

    // ========== Signed / absolute trigger handling ==========

    @Test
    fun `preserves a positive (after-start) relative trigger without sign-mangling`() {
        // PT9H = 9 hours after start (e.g. all-day "9 AM day of" anchored at midnight).
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-after@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            SUMMARY:All-day with 9 AM reminder
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:PT9H
            DESCRIPTION:9 AM day of
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNotNull("Should have reminders", entity.reminders)
        assertEquals("Positive trigger preserved", "PT9H", entity.reminders!!.first())
    }

    @Test
    fun `converts an absolute DATE-TIME trigger to a duration from start (not dropped)`() {
        // Absolute trigger one hour before the 10:00 start -> -PT1H.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-abs@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with absolute alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME:20251225T090000Z
            DESCRIPTION:Absolute one hour before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNotNull("Absolute trigger must not be dropped", entity.reminders)
        assertEquals("-PT1H", entity.reminders!!.first())
    }

    // ========== ORGANIZER scheduling-parameter mapping (RFC 6638 §7.3) ==========

    @Test
    fun `maps ORGANIZER SCHEDULE-STATUS into organizerScheduleStatus`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:org-sched-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Meeting
            ORGANIZER;SCHEDULE-STATUS=1.2:mailto:boss@example.test
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:guest@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("1.2", entity.organizerScheduleStatus)
    }

    @Test
    fun `maps ORGANIZER SENT-BY into organizerSentBy`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:org-sentby-001@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Meeting
            ORGANIZER;SENT-BY="mailto:assistant@example.test":mailto:boss@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("assistant@example.test", entity.organizerSentBy)
    }

    @Test
    fun `multi-value ORGANIZER SCHEDULE-STATUS keeps the leading code`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:org-sched-multi@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Meeting
            ORGANIZER;SCHEDULE-STATUS="2.0,2.4":mailto:boss@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertEquals("2.0", entity.organizerScheduleStatus)
    }

    @Test
    fun `ORGANIZER without SCHEDULE-STATUS leaves organizerScheduleStatus null`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:org-nostatus@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Meeting
            ORGANIZER:mailto:boss@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNull(entity.organizerScheduleStatus)
        assertNull(entity.organizerSentBy)
    }

    @Test
    fun `event without ORGANIZER leaves organizer schedule fields null`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-org@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Solo event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val entity = ICalEventMapper.toEntity(events.first(), ics, 1L, null, null).event

        assertNull(entity.organizerScheduleStatus)
        assertNull(entity.organizerSentBy)
    }
}
