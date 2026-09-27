package org.onekash.kashcal.reminder.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.sync.parser.icaldav.RawIcsParser
import java.time.ZoneId

/**
 * Tests the reminder offset parsing: [parseIsoDuration] on time, day, week and combined
 * ISO 8601 durations (RFC 5545 §3.3.6), [parseReminderOffset] on signed offsets and common CalDAV
 * reminder values, trigger arithmetic on a timed start, and zero, blank and malformed input.
 */
class ReminderSchedulerTest {

    // ==================== parseIsoDuration Tests ====================

    @Test
    fun `parseIsoDuration handles PT15M - 15 minutes`() {
        val result = parseIsoDuration("PT15M")

        assertEquals(15 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles PT1H - 1 hour`() {
        val result = parseIsoDuration("PT1H")

        assertEquals(60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles PT1H30M - 1 hour 30 minutes`() {
        val result = parseIsoDuration("PT1H30M")

        assertEquals((60 + 30) * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P1D - 1 day`() {
        val result = parseIsoDuration("P1D")

        assertEquals(24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P7D - 7 days`() {
        val result = parseIsoDuration("P7D")

        assertEquals(7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P1W - 1 week`() {
        val result = parseIsoDuration("P1W")

        assertEquals(7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P2W - 2 weeks`() {
        val result = parseIsoDuration("P2W")

        assertEquals(14 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles P1DT2H30M - combined day and time`() {
        val result = parseIsoDuration("P1DT2H30M")

        val expected = (24 * 60 * 60 * 1000L) + // 1 day
            (2 * 60 * 60 * 1000L) +             // 2 hours
            (30 * 60 * 1000L)                   // 30 minutes
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration handles PT30S - 30 seconds`() {
        val result = parseIsoDuration("PT30S")

        assertEquals(30 * 1000L, result)
    }

    @Test
    fun `parseIsoDuration handles PT1H15M30S - hours minutes seconds`() {
        val result = parseIsoDuration("PT1H15M30S")

        val expected = (60 * 60 * 1000L) +  // 1 hour
            (15 * 60 * 1000L) +              // 15 minutes
            (30 * 1000L)                     // 30 seconds
        assertEquals(expected, result)
    }

    @Test
    fun `parseIsoDuration handles PT0M - zero minutes (at event time)`() {
        val result = parseIsoDuration("PT0M")

        assertEquals(0L, result)
    }

    @Test
    fun `parseIsoDuration handles PT0S - zero seconds (at event time)`() {
        val result = parseIsoDuration("PT0S")

        assertEquals(0L, result)
    }

    @Test
    fun `parseIsoDuration returns null for empty string`() {
        val result = parseIsoDuration("")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for blank string`() {
        val result = parseIsoDuration("   ")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for missing P prefix`() {
        val result = parseIsoDuration("T15M")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration returns null for invalid format`() {
        val result = parseIsoDuration("PXYZ")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration handles large values - P365D`() {
        val result = parseIsoDuration("P365D")

        assertEquals(365L * 24 * 60 * 60 * 1000, result)
    }

    // ==================== parseReminderOffset Tests ====================

    @Test
    fun `parseReminderOffset handles negative PT15M - 15 minutes before`() {
        val result = parseReminderOffset("-PT15M")

        assertEquals(-15 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative PT1H - 1 hour before`() {
        val result = parseReminderOffset("-PT1H")

        assertEquals(-60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative P1D - 1 day before`() {
        val result = parseReminderOffset("-P1D")

        assertEquals(-24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative P1W - 1 week before`() {
        val result = parseReminderOffset("-P1W")

        assertEquals(-7 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles positive PT15M - 15 minutes after`() {
        val result = parseReminderOffset("PT15M")

        assertEquals(15 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles negative P1DT2H - 1 day 2 hours before`() {
        val result = parseReminderOffset("-P1DT2H")

        val expected = -((24 * 60 * 60 * 1000L) + (2 * 60 * 60 * 1000L))
        assertEquals(expected, result)
    }

    @Test
    fun `parseReminderOffset returns null for empty string`() {
        val result = parseReminderOffset("")

        assertNull(result)
    }

    @Test
    fun `parseReminderOffset returns null for blank string`() {
        val result = parseReminderOffset("   ")

        assertNull(result)
    }

    @Test
    fun `parseReminderOffset returns null for invalid format`() {
        val result = parseReminderOffset("invalid")

        assertNull(result)
    }

    // ==================== Common iCloud/CalDAV Reminder Values ====================

    @Test
    fun `parseReminderOffset handles iCloud 10 minutes before`() {
        val result = parseReminderOffset("-PT10M")

        assertEquals(-10 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 30 minutes before`() {
        val result = parseReminderOffset("-PT30M")

        assertEquals(-30 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 1 hour before`() {
        val result = parseReminderOffset("-PT1H")

        assertEquals(-60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 2 hours before`() {
        val result = parseReminderOffset("-PT2H")

        assertEquals(-2 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 1 day before (for all-day events)`() {
        val result = parseReminderOffset("-P1D")

        assertEquals(-24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 2 days before`() {
        val result = parseReminderOffset("-P2D")

        assertEquals(-2 * 24 * 60 * 60 * 1000L, result)
    }

    @Test
    fun `parseReminderOffset handles iCloud 1 week before`() {
        val result = parseReminderOffset("-P1W")

        assertEquals(-7 * 24 * 60 * 60 * 1000L, result)
    }

    // ==================== Trigger Time Calculation Tests ====================

    @Test
    fun `trigger time calculation - 15 minutes before event`() {
        val eventStartTs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val offset = parseReminderOffset("-PT15M")!!

        val triggerTime = eventStartTs + offset

        // Dec 31, 2023 23:45 UTC
        assertEquals(eventStartTs - (15 * 60 * 1000), triggerTime)
    }

    @Test
    fun `trigger time calculation - at event time`() {
        val eventStartTs = 1704067200000L
        val offset = parseReminderOffset("-PT0M") ?: 0L

        val triggerTime = eventStartTs + offset

        assertEquals(eventStartTs, triggerTime)
    }

    @Test
    fun `trigger time calculation - 1 day before event`() {
        val eventStartTs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val offset = parseReminderOffset("-P1D")!!

        val triggerTime = eventStartTs + offset

        // Dec 31, 2023 00:00 UTC
        assertEquals(eventStartTs - (24 * 60 * 60 * 1000), triggerTime)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `parseIsoDuration handles P0D - zero days`() {
        val result = parseIsoDuration("P0D")

        assertNull(result) // Zero is only PT0M or PT0S; any other zero is null
    }

    @Test
    fun `parseReminderOffset handles double negative gracefully`() {
        // Malformed input: only one leading minus is stripped, and the rest doesn't parse.
        val result = parseReminderOffset("--PT15M")

        assertNull(result)
    }

    @Test
    fun `parseIsoDuration handles P1DT0H0M - day with zero time`() {
        val result = parseIsoDuration("P1DT0H0M")

        // The day part still counts.
        assertEquals(24 * 60 * 60 * 1000L, result)
    }
}

/** Tests the [ReminderScheduler] window lengths and the alarm action and extra key. */
class ReminderSchedulerConstantsTest {

    @Test
    fun `reminders are armed seven days ahead`() {
        // Android caps an app at 500 pending alarms; arming a month ahead can reach it
        assertEquals(7, ReminderScheduler.SCHEDULE_WINDOW_DAYS)
    }

    @Test
    fun `occurrences are read far enough ahead for a reminder set weeks before its event`() {
        assertEquals(
            ReminderScheduler.SCHEDULE_WINDOW_DAYS + ReminderScheduler.MAX_REMINDER_LEAD_DAYS,
            ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS
        )
        assertEquals(30, ReminderScheduler.MAX_REMINDER_LEAD_DAYS)
    }

    @Test
    fun `action reminder alarm constant is correct`() {
        assertEquals("org.onekash.kashcal.REMINDER_ALARM", ReminderScheduler.ACTION_REMINDER_ALARM)
    }

    @Test
    fun `extra reminder id constant is correct`() {
        assertEquals("reminder_id", ReminderScheduler.EXTRA_REMINDER_ID)
    }
}

/**
 * Tests [calculateAllDayTriggerTime]: the trigger is the event's local midnight plus the signed
 * offset.
 *
 * - A negative offset is before the event's local-midnight start (-PT15H is 9 AM the day
 *   before), a positive one after it (PT9H is 9 AM on the event day), and PT0M is midnight.
 * - The anchor is the event's local midnight, its date read from the stored UTC midnight, so the
 *   wall-clock fire time is the same in every timezone on a day without a DST change.
 * - Offsets are exact durations (RFC 5545 VALARM relative trigger; matches the platform and
 *   other clients), so on a DST transition day the wall-clock time shifts by the transition
 *   amount. This is deliberate: what is stored is what fires and what is sent, and a fixed
 *   duration can't also hold the wall-clock time across a DST change.
 * - Offsets outside the chip set, such as -P1D and -PT9H, are read the same way.
 *
 * Chip offsets: 9AM = PT9H, 1d = -PT15H, 2d = -PT39H, 1w = -PT159H.
 */
class CalculateAllDayTriggerTimeTest {

    // ==================== Positive offset: "9 AM day of event" (PT9H) ====================

    @Test
    fun `9 AM day of event (PT9H) in PST`() {
        val utcMidnight = 1736121600000L // Jan 6 2025 00:00 UTC
        val offset = parseReminderOffset("PT9H")!! // +32,400,000ms (after midnight)

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight Jan 6 PST (= Jan 6 08:00 UTC) + 9h = Jan 6 09:00 PST = Jan 6 17:00 UTC
        assertEquals(1736182800000L, result)
    }

    @Test
    fun `9 AM day of event (PT9H) in Tokyo`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Tokyo"))

        // local midnight Jan 6 JST (= Jan 5 15:00 UTC) + 9h = Jan 6 09:00 JST = Jan 6 00:00 UTC
        assertEquals(1736121600000L, result)
    }

    @Test
    fun `9 AM day of event (PT9H) in India (IST UTC+530)`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Kolkata"))

        // local midnight Jan 6 IST (= Jan 5 18:30 UTC) + 9h = Jan 6 09:00 IST = Jan 6 03:30 UTC
        assertEquals(1736134200000L, result)
    }

    // ==================== Negative offsets: chip "before" values ====================

    @Test
    fun `1d chip (-PT15H) fires 9 AM the day before in PST`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT15H")!! // -54,000,000ms

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight Jan 6 PST - 15h = Jan 5 09:00 PST = Jan 5 17:00 UTC
        assertEquals(1736096400000L, result)
    }

    @Test
    fun `2d chip (-PT39H) fires 9 AM two days before in PST`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT39H")!! // -140,400,000ms

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight Jan 6 PST - 39h = Jan 4 09:00 PST = Jan 4 17:00 UTC
        assertEquals(1736010000000L, result)
    }

    @Test
    fun `1d chip (-PT15H) fires 9 AM the day before in India`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT15H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("Asia/Kolkata"))

        // local midnight Jan 6 IST - 15h = Jan 5 09:00 IST = Jan 5 03:30 UTC
        assertEquals(1736047800000L, result)
    }

    // ==================== Midnight boundary (PT0M) ====================

    @Test
    fun `PT0M fires at local midnight of the event day`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT0M")!! // 0

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight Jan 6 PST = Jan 6 08:00 UTC
        assertEquals(1736150400000L, result)
    }

    // ==================== Timezone stability: same offset, same wall-clock ====================

    @Test
    fun `PT9H fires at 09 00 local in every timezone`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("PT9H")!!

        for (zoneId in listOf("America/Chicago", "America/New_York", "Asia/Kolkata", "Asia/Tokyo")) {
            val zone = ZoneId.of(zoneId)
            val result = calculateAllDayTriggerTime(utcMidnight, offset, zone)
            val local = java.time.Instant.ofEpochMilli(result).atZone(zone)
            assertEquals("Wrong hour in $zoneId", 9, local.hour)
            assertEquals("Wrong minute in $zoneId", 0, local.minute)
            assertEquals("Wrong date in $zoneId", 6, local.dayOfMonth)
        }
    }

    @Test
    fun `1d chip fires at 09 00 local the day before in every timezone`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT15H")!!

        for (zoneId in listOf("America/Chicago", "America/New_York", "Asia/Kolkata", "Asia/Tokyo")) {
            val zone = ZoneId.of(zoneId)
            val result = calculateAllDayTriggerTime(utcMidnight, offset, zone)
            val local = java.time.Instant.ofEpochMilli(result).atZone(zone)
            assertEquals("Wrong hour in $zoneId", 9, local.hour)
            assertEquals("Wrong date in $zoneId", 5, local.dayOfMonth)
        }
    }

    // ==================== DST: exact-duration semantics (stored == fired) ====================

    @Test
    fun `DST spring forward - PT9H lands 10 AM (exact-duration, matches other clients)`() {
        // March 9 2025: PST -> PDT (02:00 -> 03:00). Event March 9.
        val marchUtcMidnight = 1741478400000L // March 9 2025 00:00 UTC
        val offset = parseReminderOffset("PT9H")!!

        val result = calculateAllDayTriggerTime(marchUtcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight March 9 PST (= March 9 08:00 UTC) + exactly 9h = March 9 17:00 UTC.
        // 02:00-03:00 is skipped, so 9 elapsed hours after midnight is 10:00 wall-clock PDT.
        assertEquals(1741539600000L, result)
        val local = java.time.Instant.ofEpochMilli(result).atZone(ZoneId.of("America/Los_Angeles"))
        assertEquals(10, local.hour)
    }

    @Test
    fun `non-DST day PT9H lands exactly 9 AM wall-clock`() {
        // On a day without a DST change, the exact duration lands on wall-clock 9 AM.
        val utcMidnight = 1736121600000L // Jan 6
        val offset = parseReminderOffset("PT9H")!!

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/New_York"))
        val local = java.time.Instant.ofEpochMilli(result).atZone(ZoneId.of("America/New_York"))

        assertEquals(9, local.hour)
        assertEquals(0, local.minute)
    }

    // ==================== Offsets outside the chip set ====================

    @Test
    fun `legacy -P1D now fires at local midnight the day before (was 9 AM)`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-P1D")!! // -86,400,000ms

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight Jan 6 PST - 24h = Jan 5 00:00 PST = Jan 5 08:00 UTC: midnight the
        // day before, not 9 AM.
        assertEquals(1736064000000L, result)
    }

    @Test
    fun `legacy -PT9H now fires 3 PM the day before (was 9 AM day of)`() {
        val utcMidnight = 1736121600000L
        val offset = parseReminderOffset("-PT9H")!! // -32,400,000ms

        val result = calculateAllDayTriggerTime(utcMidnight, offset, ZoneId.of("America/Los_Angeles"))

        // local midnight Jan 6 PST - 9h = Jan 5 15:00 PST = Jan 5 23:00 UTC. The 9AM chip
        // stores PT9H, not -PT9H.
        assertEquals(1736118000000L, result)
    }
}

/**
 * Tests the rawIcal alarm read the scheduler uses for an event with more than 3 alarms.
 *
 * For an event whose alarmCount is over 3, the scheduler reads every VALARM trigger from rawIcal
 * with [RawIcsParser.getAllAlarmTriggers], not only
 * [org.onekash.kashcal.data.db.entity.Event.reminders], which keeps at most 5. These tests cover
 * that parser on 5 alarms, null and invalid input, and that each trigger it returns parses to an
 * offset; they don't run the scheduler.
 */
class AlarmCountOptimizationTest {

    @Test
    fun `RawIcsParser getAllAlarmTriggers returns all 5 triggers`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:five-alarms@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with 5 alarms
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:30 min before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT2H
            DESCRIPTION:2 hours before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val triggers = RawIcsParser.getAllAlarmTriggers(ics)

        assertEquals("Should extract all 5 alarm triggers", 5, triggers.size)
        assertTrue("Should contain -PT15M", triggers.contains("-PT15M"))
        assertTrue("Should contain -PT30M", triggers.contains("-PT30M"))
        assertTrue("Should contain -PT1H", triggers.contains("-PT1H"))
        assertTrue("Should contain -PT2H", triggers.contains("-PT2H"))
        assertTrue("Should contain -P1D", triggers.contains("-P1D"))
    }

    @Test
    fun `RawIcsParser getAllAlarmTriggers handles empty rawIcal`() {
        val triggers = RawIcsParser.getAllAlarmTriggers(null)

        assertTrue("Should return empty list for null", triggers.isEmpty())
    }

    @Test
    fun `RawIcsParser getAllAlarmTriggers handles invalid rawIcal`() {
        val triggers = RawIcsParser.getAllAlarmTriggers("not valid ics data")

        assertTrue("Should return empty list for invalid ICS", triggers.isEmpty())
    }

    @Test
    fun `all alarm triggers can be parsed to milliseconds`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:parseable-alarms@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT2H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val triggers = RawIcsParser.getAllAlarmTriggers(ics)

        // Every trigger parses to an offset.
        for (trigger in triggers) {
            val offsetMs = parseReminderOffset(trigger)
            assertNotNull("Trigger '$trigger' should be parseable", offsetMs)
        }

        // The offsets for each trigger.
        assertEquals(-15 * 60 * 1000L, parseReminderOffset("-PT15M"))
        assertEquals(-30 * 60 * 1000L, parseReminderOffset("-PT30M"))
        assertEquals(-60 * 60 * 1000L, parseReminderOffset("-PT1H"))
        assertEquals(-2 * 60 * 60 * 1000L, parseReminderOffset("-PT2H"))
        assertEquals(-24 * 60 * 60 * 1000L, parseReminderOffset("-P1D"))
    }
}
