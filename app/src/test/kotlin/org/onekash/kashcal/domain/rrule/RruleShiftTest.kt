package org.onekash.kashcal.domain.rrule

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests moving a series by a day shift and a new time of day. [RruleShift.shift] returns the
 * rewritten rule only when, anchored at the new start, it generates exactly the old occurrences
 * each moved to (their local date + the shift) at the new local time, in the event's zone
 * (RFC 5545 section 3.8.5.3: a DTSTART out of step with its rule gives an undefined set). Also
 * tests which scopes [RruleShift.dragAvailability] offers for a drop, and
 * [RruleShift.movedStart].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RruleShiftTest {

    private val savedZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinPhoneZone() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))

    @After
    fun restore() = TimeZone.setDefault(savedZone)

    private val ny = ZoneId.of(NY)

    private fun at(date: String, time: String, zone: ZoneId = ny): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant().toEpochMilli()

    private fun utcMidnight(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Monday 4 Mar 2024, 10:00 New York (EST; the US switches to EDT on 10 Mar). */
    private val monday = at("2024-03-04", "10:00")
    private val tuesday = at("2024-03-05", "10:00")

    private fun shift(rule: String, from: Long = monday, to: Long = tuesday, zone: String? = NY, allDay: Boolean = false) =
        RruleShift.shift(rule, from, to, zone, allDay)

    // ---- allowed ----

    @Test
    fun `a weekly single weekday moves to the new weekday`() {
        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=4", shift("FREQ=WEEKLY;BYDAY=MO;COUNT=4"))
    }

    @Test
    fun `several weekly weekdays all move by the same days`() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=TU,TH,SA;COUNT=9",
            shift("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=9"),
        )
    }

    @Test
    fun `a move backwards wraps the weekday`() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=TU;COUNT=4",
            shift("FREQ=WEEKLY;BYDAY=MO;COUNT=4", to = at("2024-02-27", "10:00")),
        )
    }

    @Test
    fun `a single weekday with an interval and week start moves like any other`() {
        // With one BYDAY day that the start is on, the week start doesn't regroup the weeks.
        val sunday = at("2024-03-03", "10:00")
        assertEquals(
            "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=MO;COUNT=4",
            shift("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=SU;COUNT=4", from = sunday, to = monday),
        )
    }

    @Test
    fun `every weekday moves one day later`() {
        assertEquals(
            "FREQ=DAILY;BYDAY=TU,WE,TH,FR,SA;COUNT=10",
            shift("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR;COUNT=10"),
        )
    }

    @Test
    fun `a monthly day of the month moves by the same days`() {
        val fourth = at("2024-03-04", "10:00")
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=6;COUNT=6",
            shift("FREQ=MONTHLY;BYMONTHDAY=4;COUNT=6", from = fourth, to = at("2024-03-06", "10:00")),
        )
    }

    @Test
    fun `a second monday moves to the second tuesday while every one of them follows`() {
        // 2nd Mondays Mar-May 2024: 11 Mar, 8 Apr, 13 May; each + 1 day is a 2nd Tuesday.
        val secondMonday = at("2024-03-11", "10:00")
        assertEquals(
            "FREQ=MONTHLY;BYDAY=2TU;COUNT=3",
            shift("FREQ=MONTHLY;BYDAY=2MO;COUNT=3", from = secondMonday, to = at("2024-03-12", "10:00")),
        )
    }

    @Test
    fun `a yearly month and day move by the same days`() {
        val march5 = at("2024-03-05", "10:00")
        assertEquals(
            "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=6;COUNT=3",
            shift("FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=5;COUNT=3", from = march5, to = at("2024-03-06", "10:00")),
        )
    }

    @Test
    fun `a yearly 31st moved into the next month moves its month too`() {
        val jan31 = at("2024-01-31", "10:00")
        assertEquals(
            "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=1;COUNT=3",
            shift("FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=31;COUNT=3", from = jan31, to = at("2024-02-01", "10:00")),
        )
    }

    @Test
    fun `a rule with no day part comes back unchanged`() {
        assertEquals("FREQ=WEEKLY;COUNT=4", shift("FREQ=WEEKLY;COUNT=4"))
        assertEquals("FREQ=DAILY;COUNT=4", shift("FREQ=DAILY;COUNT=4"))
    }

    @Test
    fun `no move returns the rule as it is`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=4", shift("FREQ=WEEKLY;BYDAY=MO;COUNT=4", to = monday))
    }

    // ---- refused ----

    @Test
    fun `a monthly 29th moved to the 31st is refused`() {
        val the29th = at("2024-01-29", "10:00")
        assertNull(shift("FREQ=MONTHLY;BYMONTHDAY=29;COUNT=6", from = the29th, to = at("2024-01-31", "10:00")))
    }

    @Test
    fun `a monthly rule with no day part starting on the 29th moved two days is refused`() {
        val the29th = at("2024-01-29", "10:00")
        assertNull(shift("FREQ=MONTHLY;COUNT=6", from = the29th, to = at("2024-01-31", "10:00")))
    }

    @Test
    fun `a last day of the month moved a day is refused`() {
        val last = at("2024-01-31", "10:00")
        assertNull(shift("FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=4", from = last, to = at("2024-02-01", "10:00")))
    }

    @Test
    fun `a second monday on the 14th moved a day is refused`() {
        // 2nd Monday 14 Oct 2024: + 1 day is 15 Oct, the 3rd Tuesday.
        val the14th = at("2024-10-14", "10:00")
        assertNull(shift("FREQ=MONTHLY;BYDAY=2MO;COUNT=3", from = the14th, to = at("2024-10-15", "10:00")))
    }

    @Test
    fun `a last friday moved a day is refused`() {
        val lastFriday = at("2024-03-29", "10:00")
        assertNull(shift("FREQ=MONTHLY;BYDAY=-1FR;COUNT=4", from = lastFriday, to = at("2024-03-30", "10:00")))
    }

    @Test
    fun `a two-day weekly rule with an interval whose weeks regroup is refused`() {
        val saturday = at("2024-03-02", "10:00")
        assertNull(
            shift("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=SA,SU;COUNT=6", from = saturday, to = at("2024-03-03", "10:00")),
        )
    }

    @Test
    fun `a set position rule is refused`() {
        assertNull(shift("FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1;COUNT=4"))
    }

    @Test
    fun `an hour rule with a new time of day is refused`() {
        assertNull(shift("FREQ=WEEKLY;BYDAY=MO;BYHOUR=10;COUNT=4", to = at("2024-03-05", "11:30")))
    }

    @Test
    fun `a start that isn't an occurrence of its rule is refused`() {
        // A Tuesday rule anchored on a Monday: the start isn't one of its occurrences.
        assertNull(shift("FREQ=WEEKLY;BYDAY=TU;COUNT=4"))
    }

    @Test
    fun `an unparseable rule is refused, never taken as a match`() {
        assertNull(shift("FREQ=BOGUS;BYDAY=MO;COUNT=4"))
    }

    // ---- bounds ----

    @Test
    fun `an until equal to the last occurrence keeps it when the time moves too`() {
        // Weekly Monday 10:00 New York, four occurrences, UNTIL exactly the last one (25 Mar
        // 14:00Z).
        val rule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20240325T140000Z"
        val moved = shift(rule, to = at("2024-03-05", "11:30"))!!
        val occurrences = IcalDavRRuleEngine.expandToTimestamps(
            moved, at("2024-03-05", "11:30"), at("2024-03-05", "11:30") - 1_000, at("2025-01-01", "00:00"), NY, false, null, null,
        )
        assertEquals((0L..3L).map { at("2024-03-05", "11:30").let { s -> localPlusWeeks(s, it) } }, occurrences)
    }

    @Test
    fun `an all-day until moves as a date`() {
        val from = utcMidnight("2024-03-04")
        assertEquals(
            "FREQ=WEEKLY;BYDAY=TU;UNTIL=20240326",
            RruleShift.shift("FREQ=WEEKLY;BYDAY=MO;UNTIL=20240325", from, utcMidnight("2024-03-05"), null, true),
        )
    }

    // ---- clock terms across DST ----

    @Test
    fun `a move across the spring and autumn switches keeps the local time`() {
        val rule = "FREQ=WEEKLY;BYDAY=MO;COUNT=40" // 4 Mar to 2 Dec 2024: both US switches
        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=40", shift(rule))
        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=40", shift(rule, to = at("2024-03-05", "11:30")))
    }

    @Test
    fun `a series with no zone uses the same fallback zone as the engine`() {
        // No zone: the engine expands in the phone's zone (Berlin here).
        val berlin = ZoneId.of("Europe/Berlin")
        val from = at("2024-03-04", "10:00", berlin)
        assertEquals(
            "FREQ=WEEKLY;BYDAY=TU;COUNT=40",
            RruleShift.shift("FREQ=WEEKLY;BYDAY=MO;COUNT=40", from, at("2024-03-05", "10:00", berlin), null, false),
        )
    }

    // ---- what a drop may offer ----

    private fun series(rule: String, exdate: String? = null, rdate: String? = null) = Event(
        id = 1, uid = "s@example.test", calendarId = 1, title = "Standup",
        startTs = monday, endTs = monday + 3_600_000, timezone = NY,
        rrule = rule, exdate = exdate, rdate = rdate, dtstamp = 1, createdAt = 1, updatedAt = 1,
    )

    private val weekly = "FREQ=WEEKLY;BYDAY=MO;COUNT=6"
    private fun mondayN(n: Int) = localPlusWeeks(monday, n - 1L)
    private fun exceptionAt(originalTs: Long) = series(weekly).copy(id = 9, rrule = null, originalEventId = 1, originalInstanceTime = originalTs)

    @Test
    fun `a same-day drop blocks nothing, even with deletions and edits`() {
        val master = series(weekly, exdate = mondayN(2).toString())
        val result = RruleShift.dragAvailability(master, listOf(exceptionAt(mondayN(4))), mondayN(3), mondayN(3) + 3_600_000)
        assertTrue(result.allEvents && result.thisAndFuture)
    }

    @Test
    fun `a cross-day drop of a clean series offers both scopes`() {
        val result = RruleShift.dragAvailability(series(weekly), emptyList(), mondayN(3), localPlusDays(mondayN(3), 1))
        assertTrue(result.allEvents && result.thisAndFuture)
    }

    @Test
    fun `a deleted occurrence blocks all events and blocks this and future only when it lies ahead`() {
        val earlier = RruleShift.dragAvailability(series(weekly, exdate = mondayN(2).toString()), emptyList(), mondayN(3), localPlusDays(mondayN(3), 1))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = true), earlier)
        val ahead = RruleShift.dragAvailability(series(weekly, exdate = mondayN(5).toString()), emptyList(), mondayN(3), localPlusDays(mondayN(3), 1))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = false), ahead)
    }

    @Test
    fun `an edited occurrence blocks all events and blocks this and future only when it lies ahead`() {
        val earlier = RruleShift.dragAvailability(series(weekly), listOf(exceptionAt(mondayN(2))), mondayN(3), localPlusDays(mondayN(3), 1))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = true), earlier)
        val ahead = RruleShift.dragAvailability(series(weekly), listOf(exceptionAt(mondayN(4))), mondayN(3), localPlusDays(mondayN(3), 1))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = false), ahead)
    }

    @Test
    fun `an extra date blocks all events`() {
        val result = RruleShift.dragAvailability(series(weekly, rdate = localPlusDays(mondayN(2), 2).toString()), emptyList(), mondayN(3), localPlusDays(mondayN(3), 1))
        assertEquals(false, result.allEvents)
    }

    @Test
    fun `an unknown zone is read in the phone's zone, as the engine reads it`() {
        // Berlin phone: Monday 10:00 there, moved to Tuesday.
        val berlin = ZoneId.of("Europe/Berlin")
        val start = at("2024-03-04", "10:00", berlin)
        val master = series(weekly).copy(startTs = start, endTs = start + 3_600_000, timezone = "Not/AZone")
        val result = RruleShift.dragAvailability(master, emptyList(), start, at("2024-03-05", "10:00", berlin))
        assertEquals(DragMoveAvailability(allEvents = true, thisAndFuture = true), result)
    }

    @Test
    fun `a series that no longer repeats blocks both scopes on a cross-day drop`() {
        val result = RruleShift.dragAvailability(series(weekly).copy(rrule = null), emptyList(), mondayN(3), localPlusDays(mondayN(3), 1))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = false), result)
    }

    @Test
    fun `an occurrence that is no longer in the series blocks both scopes`() {
        // The drop was of a Monday; the stored series now repeats on Tuesdays.
        val tuesdays = series("FREQ=WEEKLY;BYDAY=TU;COUNT=6").copy(startTs = tuesday, endTs = tuesday + 3_600_000)
        val result = RruleShift.dragAvailability(tuesdays, emptyList(), mondayN(2), localPlusDays(mondayN(2), 1))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = false), result)
    }

    @Test
    fun `a move the rule can't express blocks both scopes`() {
        val the29th = at("2024-01-29", "10:00")
        val monthly = series("FREQ=MONTHLY;BYMONTHDAY=29;COUNT=6").copy(startTs = the29th, endTs = the29th + 3_600_000)
        val result = RruleShift.dragAvailability(monthly, emptyList(), the29th, at("2024-01-31", "10:00"))
        assertEquals(DragMoveAvailability(allEvents = false, thisAndFuture = false), result)
    }

    @Test
    fun `the moved series start keeps the first date shifted at the new local time`() {
        val master = series(weekly)
        assertEquals(at("2024-03-05", "11:30"), RruleShift.movedStart(master, mondayN(3), at("2024-03-19", "11:30")))
    }

    private fun localPlusWeeks(ts: Long, weeks: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ny).plusWeeks(weeks).toInstant().toEpochMilli()

    private fun localPlusDays(ts: Long, days: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ny).plusDays(days).toInstant().toEpochMilli()

    private companion object {
        const val NY = "America/New_York"
    }
}
