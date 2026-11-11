package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * A limited [RRuleExpander.expand] returns the first dates of the full expansion, stops early on
 * a dense rule over a long range, and leaves out RDATEs after the last date it read.
 */
class RRuleExpanderLimitTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val local = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun event(rrule: String, dtstart: LocalDateTime, zone: String, rdate: String? = null): ICalEvent {
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\nBEGIN:VEVENT\r\n")
            append("UID:limit@example.test\r\nDTSTAMP:20260101T000000Z\r\n")
            append("DTSTART;TZID=$zone:${local.format(dtstart)}\r\n")
            append("DTEND;TZID=$zone:${local.format(dtstart.plusMinutes(30))}\r\n")
            append("RRULE:$rrule\r\n")
            if (rdate != null) append("RDATE;TZID=$zone:$rdate\r\n")
            append("END:VEVENT\r\nEND:VCALENDAR\r\n")
        }
        return ((parser.parseAllEvents(ics)) as ParseResult.Success).value.single()
    }

    private fun starts(list: List<ICalEvent>) = list.map { it.dtStart.timestamp }

    private val rules = listOf(
        "FREQ=DAILY;BYHOUR=8,12,17",
        "FREQ=WEEKLY;BYDAY=MO,WE,FR",
        "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,SU;WKST=SU",
        "FREQ=MONTHLY;BYDAY=2TU,-1FR",
        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1,-1",
        "FREQ=MONTHLY;BYMONTHDAY=1,15,-1",
        "FREQ=YEARLY;BYWEEKNO=1,20,52;BYDAY=MO,TH",
        "FREQ=YEARLY;BYMONTH=3,11;BYDAY=-1SU",
        "FREQ=HOURLY;INTERVAL=5;BYMINUTE=0,30",
        "FREQ=DAILY;BYDAY=SA,SU;BYHOUR=9;BYMINUTE=0,45",
    )
    private val zones = listOf("UTC", "America/New_York", "Europe/Berlin", "Australia/Sydney")

    @Test
    fun `a limited expansion is the first dates of the full one`() {
        val random = Random(20261110)
        repeat(400) { i ->
            val rule = rules[random.nextInt(rules.size)]
            val zone = zones[random.nextInt(zones.size)]
            val windowStart = LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(random.nextLong(0, 3650))
            val dtstart = windowStart.minusDays(random.nextLong(0, 2000)).withHour(random.nextInt(24))
                .withMinute(random.nextInt(4) * 15)
            val from = windowStart.atZone(ZoneId.of(zone)).toInstant()
            val to = from.plusSeconds(86_400L * random.nextLong(7, 120))
            val limit = random.nextInt(1, 60)
            val master = event(rule, dtstart, zone)

            val full = starts(expander.expand(master, from, to))
            val limited = starts(expander.expand(master, from, to, limit = limit))
            assertEquals(full.take(limit), limited, "case $i: $rule from $dtstart $zone, $from..$to, limit $limit")
        }
    }

    @Test
    fun `a dense rule over 150 years stops at the limit`() {
        val dtstart = LocalDateTime.of(1990, 1, 1, 0, 0)
        val master = event("FREQ=DAILY;BYHOUR=${(0..23).joinToString(",")};BYMINUTE=${(0..59).joinToString(",")}", dtstart, "UTC")
        val from = Instant.parse("1990-01-01T00:00:00Z")
        val to = Instant.parse("2140-01-01T00:00:00Z")
        var limited: List<Long>? = null
        val worker = Thread { limited = starts(expander.expand(master, from, to, limit = 10_000)) }
        worker.isDaemon = true
        worker.start()
        worker.join(30_000)
        assertFalse(worker.isAlive, "a limited expansion over 150 years finishes")

        val got = limited!!
        assertEquals(10_000, got.size)
        val all = starts(expander.expand(master, from, Instant.ofEpochMilli(got.last())))
        assertEquals(all, got)
    }

    @Test
    fun `an RDATE after the last date read is left out, one before it is kept`() {
        val master = event("FREQ=DAILY", LocalDateTime.of(2026, 1, 1, 9, 0), "UTC", rdate = "20260103T150000,20260120T150000")
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-02-01T00:00:00Z")
        val got = starts(expander.expand(master, from, to, limit = 5))

        assertEquals(6, got.size)
        assertTrue(Instant.parse("2026-01-03T15:00:00Z").toEpochMilli() in got)
        assertFalse(Instant.parse("2026-01-20T15:00:00Z").toEpochMilli() in got)
        assertEquals(Instant.parse("2026-01-05T09:00:00Z").toEpochMilli(), got.last())
    }

    @Test
    fun `without a limit, or below it, every RDATE in range is kept`() {
        val master = event("FREQ=DAILY;COUNT=10", LocalDateTime.of(2026, 1, 1, 9, 0), "UTC", rdate = "20260120T150000")
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-02-01T00:00:00Z")
        val rdate = Instant.parse("2026-01-20T15:00:00Z").toEpochMilli()

        val unlimited = starts(expander.expand(master, from, to))
        assertEquals(11, unlimited.size)
        assertTrue(rdate in unlimited)
        assertEquals(unlimited, starts(expander.expand(master, from, to, limit = 10_000)))
    }
}
