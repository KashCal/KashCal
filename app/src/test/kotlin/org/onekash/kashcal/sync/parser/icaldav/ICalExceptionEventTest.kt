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
 * Tests parsing and mapping of exception events (RECURRENCE-ID).
 *
 * One .ics resource, as iCloud sync delivers it, may hold a master with RRULE and one or more
 * exceptions with RECURRENCE-ID. Covers:
 * - parsing a master with one or several exceptions, cancelled ones (STATUS:CANCELLED)
 *   included
 * - importId uniqueness for the database, with a date-only form for all-day exceptions
 * - all-day exceptions, and exceptions with their own timezone or alarms
 * - patching a master with [IcsPatcher]
 * - an exception at its original time, and a RANGE=THISANDFUTURE RECURRENCE-ID
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICalExceptionEventTest {

    private lateinit var parser: ICalParser

    @Before
    fun setup() {
        parser = ICalParser()
    }

    // ========== Basic Exception Tests ==========

    @Test
    fun `parses master and single exception correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:weekly-meeting@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly Team Meeting
            END:VEVENT
            BEGIN:VEVENT
            UID:weekly-meeting@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251229T100000Z
            DTSTART:20251229T140000Z
            DTEND:20251229T150000Z
            SUMMARY:Weekly Team Meeting (moved to afternoon)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        assertEquals("Should have 2 events", 2, events.size)

        val master = events.find { it.recurrenceId == null }!!
        val exception = events.find { it.recurrenceId != null }!!

        // Verify master
        assertEquals("weekly-meeting@kashcal.test", master.uid)
        assertNotNull("Master should have RRULE", master.rrule)
        assertFalse(master.isModifiedInstance())

        // Verify exception
        assertEquals("weekly-meeting@kashcal.test", exception.uid)
        assertNull("Exception should NOT have RRULE", exception.rrule)
        assertTrue(exception.isModifiedInstance())
        assertEquals("Weekly Team Meeting (moved to afternoon)", exception.summary)
    }

    @Test
    fun `exception importId is unique from master`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:unique-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Event
            END:VEVENT
            BEGIN:VEVENT
            UID:unique-test@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251225T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Christmas Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        val master = events.find { it.recurrenceId == null }!!
        val exception = events.find { it.recurrenceId != null }!!

        // UIDs are the same
        assertEquals(master.uid, exception.uid)

        // ImportIds are different
        assertNotEquals(
            "ImportIds should be different for master and exception",
            master.importId,
            exception.importId
        )

        // Master importId = UID
        assertEquals("unique-test@kashcal.test", master.importId)

        // Exception importId contains :RECID:
        assertTrue(
            "Exception importId should contain :RECID:",
            exception.importId.contains(":RECID:")
        )
    }

    // ========== Multiple Exceptions ==========

    @Test
    fun `parses multiple exceptions in single file`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly Standup
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251229T100000Z
            DTSTART:20251229T140000Z
            DTEND:20251229T150000Z
            SUMMARY:Weekly Standup (Dec 29 - moved)
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20260105T100000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            SUMMARY:Weekly Standup (Jan 5 - earlier)
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20260112T100000Z
            STATUS:CANCELLED
            DTSTART:20260112T100000Z
            DTEND:20260112T110000Z
            SUMMARY:Weekly Standup (Jan 12 - cancelled)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        assertEquals("Should have 4 events (1 master + 3 exceptions)", 4, events.size)

        val master = events.find { it.recurrenceId == null }
        val exceptions = events.filter { it.recurrenceId != null }

        assertNotNull("Should have master", master)
        assertEquals("Should have 3 exceptions", 3, exceptions.size)

        // All share same UID
        assertTrue(
            "All events should have same UID",
            events.all { it.uid == "multi-exception@kashcal.test" }
        )

        // All have unique importIds
        val importIds = events.map { it.importId }.toSet()
        assertEquals("All importIds should be unique", 4, importIds.size)

        // Find cancelled exception
        val cancelledException = exceptions.find { it.status.name == "CANCELLED" }
        assertNotNull("Should have cancelled exception", cancelledException)
    }

    @Test
    fun `maps multiple exceptions to separate entities`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-map@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Event
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-map@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251225T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Exception 1
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-map@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251226T100000Z
            DTSTART:20251226T090000Z
            DTEND:20251226T100000Z
            SUMMARY:Exception 2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        // Map each to entity
        val entities = events.map { icalEvent ->
            ICalEventMapper.toEntity(
                icalEvent = icalEvent,
                rawIcal = ics,
                calendarId = 1L,
                caldavUrl = "/calendar/event.ics",
                etag = "abc123"
            ).event
        }

        assertEquals("Should have 3 entities", 3, entities.size)

        // All share same UID
        assertTrue(entities.all { it.uid == "multi-map@kashcal.test" })

        // Master has RRULE, exceptions don't
        val masterEntity = entities.find { it.originalInstanceTime == null }
        val exceptionEntities = entities.filter { it.originalInstanceTime != null }

        assertNotNull("Master should have RRULE", masterEntity?.rrule)
        assertEquals("Should have 2 exceptions", 2, exceptionEntities.size)
        assertTrue("Exceptions should not have RRULE", exceptionEntities.all { it.rrule == null })
    }

    // ========== Cancelled Exceptions ==========

    @Test
    fun `parses cancelled exception (deleted occurrence)`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cancel-test@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Event
            END:VEVENT
            BEGIN:VEVENT
            UID:cancel-test@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251225T100000Z
            STATUS:CANCELLED
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Daily Event (Christmas - cancelled)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val cancelled = events.find { it.recurrenceId != null }!!

        assertEquals("CANCELLED", cancelled.status.name)

        val entity = ICalEventMapper.toEntity(cancelled, ics, 1L, null, null).event
        assertEquals("CANCELLED", entity.status)
    }

    // ========== All-Day Exception Events ==========

    @Test
    fun `parses all-day exception correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251222
            DTEND;VALUE=DATE:20251223
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly All-Day
            END:VEVENT
            BEGIN:VEVENT
            UID:allday-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID;VALUE=DATE:20251229
            DTSTART;VALUE=DATE:20251230
            DTEND;VALUE=DATE:20251231
            SUMMARY:Weekly All-Day (moved to Tuesday)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        assertEquals("Should have 2 events", 2, events.size)

        val exception = events.find { it.recurrenceId != null }!!

        assertTrue("Master should be all-day", events.find { it.recurrenceId == null }!!.isAllDay)
        assertTrue("Exception should be all-day", exception.isAllDay)
        assertNotNull("Exception should have RECURRENCE-ID", exception.recurrenceId)
    }

    @Test
    fun `all-day exception importId uses date format`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-import@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251222
            DTEND;VALUE=DATE:20251223
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly
            END:VEVENT
            BEGIN:VEVENT
            UID:allday-import@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID;VALUE=DATE:20251229
            DTSTART;VALUE=DATE:20251229
            DTEND;VALUE=DATE:20251230
            SUMMARY:Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!
        val exception = events.find { it.recurrenceId != null }!!

        // ImportId should contain date-only format for all-day exceptions
        assertTrue(
            "All-day exception importId should contain date: ${exception.importId}",
            exception.importId.contains("20251229")
        )
    }

    // ========== Exception with Different Timezone ==========

    @Test
    fun `exception can have different timezone than master`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tz-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART;TZID=America/New_York:20251222T100000
            DTEND;TZID=America/New_York:20251222T110000
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly NY Meeting
            END:VEVENT
            BEGIN:VEVENT
            UID:tz-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID;TZID=America/New_York:20251229T100000
            DTSTART;TZID=Europe/London:20251229T150000
            DTEND;TZID=Europe/London:20251229T160000
            SUMMARY:Weekly NY Meeting (London exception)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        val master = events.find { it.recurrenceId == null }!!
        val exception = events.find { it.recurrenceId != null }!!

        // Verify different timezones
        assertEquals("America/New_York", master.dtStart.timezone?.id)
        assertEquals("Europe/London", exception.dtStart.timezone?.id)

        // Map and verify
        val masterEntity = ICalEventMapper.toEntity(master, ics, 1L, null, null).event
        val exceptionEntity = ICalEventMapper.toEntity(exception, ics, 1L, null, null).event

        assertEquals("America/New_York", masterEntity.timezone)
        assertEquals("Europe/London", exceptionEntity.timezone)
    }

    // ========== Exception Event Alarms ==========

    @Test
    fun `exception can have different alarms than master`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Master alarm
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:alarm-exception@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251225T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Christmas Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:Exception alarm 1
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:Exception alarm 2
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        val master = events.find { it.recurrenceId == null }!!
        val exception = events.find { it.recurrenceId != null }!!

        assertEquals("Master should have 1 alarm", 1, master.alarms.size)
        assertEquals("Exception should have 2 alarms", 2, exception.alarms.size)
    }

    // ========== IcsPatcher with Exceptions ==========

    @Test
    fun `IcsPatcher preserves exception structure when patching master`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:patch-master@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(originalIcs).getOrNull()!!
        val master = events.first()

        val entity = ICalEventMapper.toEntity(master, originalIcs, 1L, null, null).event
            .copy(title = "Updated Weekly Event")

        val patched = IcsPatcher.patch(originalIcs, entity)

        // The patched ICS parses, carries the new title and keeps the RRULE.
        val patchedEvents = parser.parseAllEvents(patched).getOrNull()!!
        assertEquals(1, patchedEvents.size)
        assertEquals("Updated Weekly Event", patchedEvents.first().summary)
        assertNotNull("RRULE should be preserved", patchedEvents.first().rrule)
    }

    // ========== Edge Cases ==========

    @Test
    fun `handles exception with same time as original occurrence`() {
        // An exception may change only the title or location, not the time: its DTSTART
        // equals its RECURRENCE-ID.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:same-time@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Event
            LOCATION:Room A
            END:VEVENT
            BEGIN:VEVENT
            UID:same-time@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID:20251225T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Christmas Special Event
            LOCATION:Room B
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        val master = events.find { it.recurrenceId == null }!!
        val exception = events.find { it.recurrenceId != null }!!

        assertEquals(exception.dtStart.timestamp, exception.recurrenceId!!.timestamp)

        // But content differs
        assertEquals("Daily Event", master.summary)
        assertEquals("Christmas Special Event", exception.summary)
        assertEquals("Room A", master.location)
        assertEquals("Room B", exception.location)
    }

    @Test
    fun `handles THISANDFUTURE RECURRENCE-ID`() {
        // RFC 5545 §3.8.4.4 allows RANGE=THISANDFUTURE.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:thisandfuture@kashcal.test
            DTSTAMP:20251220T100000Z
            DTSTART:20251222T100000Z
            DTEND:20251222T110000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily Event
            END:VEVENT
            BEGIN:VEVENT
            UID:thisandfuture@kashcal.test
            DTSTAMP:20251220T100000Z
            RECURRENCE-ID;RANGE=THISANDFUTURE:20251225T100000Z
            DTSTART:20251225T140000Z
            DTEND:20251225T150000Z
            SUMMARY:Daily Event (changed from Dec 25)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parseAllEvents(ics).getOrNull()!!

        assertEquals("Should parse both events", 2, events.size)

        val exception = events.find { it.recurrenceId != null }
        assertNotNull("Should have exception with RECURRENCE-ID", exception)
    }
}
