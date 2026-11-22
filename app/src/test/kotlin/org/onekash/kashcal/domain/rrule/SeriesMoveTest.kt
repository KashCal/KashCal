package org.onekash.kashcal.domain.rrule

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests [SeriesMove]: whether a series' move can be expressed, and where each cancelled date and
 * changed occurrence's key lands. Each key is checked on its own, so a series that started years
 * ago maps a key from last week, and an occurrence a spring-forward gap moved (02:30 runs at
 * 03:30) keeps its slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SeriesMoveTest {

    private val savedZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinPhoneZone() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))

    @After
    fun restore() = TimeZone.setDefault(savedZone)

    private fun at(date: String, time: String, zone: String = NY): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun utcMidnight(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun move(
        rule: String,
        oldStart: Long,
        newStart: Long,
        oldZone: String? = NY,
        newZone: String? = oldZone,
        oldAllDay: Boolean = false,
        newAllDay: Boolean = oldAllDay,
        typedRule: String? = null,
        anchor: Long = oldStart,
    ) = SeriesMove.plan(
        SeriesMove.Inputs(
            rule = rule, dtstartTs = oldStart, anchorTs = anchor, zone = oldZone, isAllDay = oldAllDay,
            newStartTs = newStart, newZone = newZone, newIsAllDay = newAllDay, typedRule = typedRule,
        )
    )

    private fun moved(m: SeriesMove?, key: Long): Long? = (m!!.mapKey(key) as? SeriesMove.Key.Moved)?.ts

    // ---- the rule ----

    @Test
    fun `no move keeps the rule and every key`() {
        val start = at("2024-03-05", "10:00")
        val m = move("FREQ=WEEKLY;COUNT=4", start, start)
        assertEquals("FREQ=WEEKLY;COUNT=4", m!!.newRule)
        assertEquals(start + 7 * DAY, moved(m, start + 7 * DAY))
    }

    @Test
    fun `an hour later moves every key an hour`() {
        val start = at("2024-03-05", "10:00")
        val m = move("FREQ=WEEKLY;COUNT=4", start, at("2024-03-05", "11:00"))
        assertEquals(at("2024-03-19", "11:00"), moved(m, at("2024-03-19", "10:00")))
    }

    @Test
    fun `a date change shifts the weekday and the keys`() {
        val m = move("FREQ=WEEKLY;BYDAY=TU;COUNT=4", at("2024-03-05", "10:00"), at("2024-03-06", "10:00"))
        assertEquals("FREQ=WEEKLY;BYDAY=WE;COUNT=4", m!!.newRule)
        assertEquals(at("2024-03-20", "10:00"), moved(m, at("2024-03-19", "10:00")))
    }

    @Test
    fun `a zone change keeps the clock time of each key`() {
        val m = move("FREQ=WEEKLY;BYDAY=TU;COUNT=4", at("2024-03-05", "10:00"), at("2024-03-05", "10:00", TOKYO), newZone = TOKYO)
        assertEquals(at("2024-03-19", "10:00", TOKYO), moved(m, at("2024-03-19", "10:00")))
    }

    @Test
    fun `a rule that can't express the move gives no move`() {
        assertNull(move("FREQ=MONTHLY;BYDAY=2TU", at("2024-03-12", "10:00"), at("2024-03-13", "10:00")))
    }

    @Test
    fun `sub-daily rules are not moved`() {
        assertNull(move("FREQ=HOURLY;COUNT=10", at("2024-03-05", "10:00"), at("2024-03-05", "11:00")))
    }

    // ---- keys ----

    @Test
    fun `a key that is not an occurrence is left as it is`() {
        val m = move("FREQ=WEEKLY;COUNT=4", at("2024-03-05", "10:00"), at("2024-03-05", "11:00"))
        assertEquals(SeriesMove.Key.NotAnOccurrence, m!!.mapKey(at("2024-03-07", "10:00")))
    }

    @Test
    fun `an open-ended series years old maps last week's keys`() {
        val start = at("2022-04-01", "09:00")
        val key = at("2024-03-18", "09:00")
        val m = move("FREQ=DAILY", start, at("2022-04-01", "10:00"))
        assertEquals(at("2024-03-18", "10:00"), moved(m, key))
        val weekdays = move("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", at("2022-04-01", "09:00"), at("2022-04-01", "10:00"))
        assertEquals(at("2024-03-18", "10:00"), moved(weekdays, key))
    }

    @Test
    fun `a spring-forward gap occurrence keeps its slot`() {
        // Daily 02:30 New York; on 10 Mar 2024 the 02:30 occurrence runs at 03:30.
        val start = at("2024-03-08", "02:30")
        val gapDay = at("2024-03-10", "03:30")
        val later = move("FREQ=DAILY;COUNT=5", start, at("2024-03-08", "03:30"))
        assertEquals(at("2024-03-10", "03:30"), moved(later, gapDay))
        val tokyo = move("FREQ=DAILY;COUNT=5", start, at("2024-03-08", "02:30", TOKYO), newZone = TOKYO)
        assertEquals(at("2024-03-10", "02:30", TOKYO), moved(tokyo, gapDay))
    }

    @Test
    fun `a London series moved to UTC keeps its summer clock time`() {
        val m = move("FREQ=WEEKLY;COUNT=30", at("2024-07-01", "10:00", LONDON), at("2024-07-01", "10:00", "UTC"),
            oldZone = LONDON, newZone = "UTC")
        assertEquals(at("2025-01-06", "10:00", "UTC"), moved(m, at("2025-01-06", "10:00", LONDON)))
    }

    @Test
    fun `timed to all-day maps each key to its date`() {
        val m = move("FREQ=WEEKLY;COUNT=4", at("2024-03-05", "10:00"), utcMidnight("2024-03-05"), newZone = null, newAllDay = true)
        assertEquals(utcMidnight("2024-03-19"), moved(m, at("2024-03-19", "10:00")))
    }

    @Test
    fun `all-day to timed maps each key to the new time`() {
        val m = move("FREQ=WEEKLY;COUNT=4", utcMidnight("2024-03-05"), at("2024-03-05", "09:00"), oldZone = null, newZone = NY,
            oldAllDay = true, newAllDay = false)
        assertEquals(at("2024-03-19", "09:00"), moved(m, utcMidnight("2024-03-19")))
    }

    @Test
    fun `an all-day series two days later maps each key to its date`() {
        val m = move("FREQ=WEEKLY;BYDAY=TU;COUNT=4", utcMidnight("2024-03-05"), utcMidnight("2024-03-07"),
            oldZone = null, oldAllDay = true)
        assertEquals("FREQ=WEEKLY;BYDAY=TH;COUNT=4", m!!.newRule)
        assertEquals(utcMidnight("2024-03-21"), moved(m, utcMidnight("2024-03-19")))
    }

    // ---- EXDATE lists ----

    @Test
    fun `exdates move per entry and legacy tokens stay`() {
        val m = move("FREQ=WEEKLY;COUNT=4", at("2024-03-05", "10:00"), at("2024-03-05", "11:00"))
        val out = m!!.mapExdates("${at("2024-03-12", "10:00")},20240319")
        assertEquals("${at("2024-03-12", "11:00")},20240319", out)
    }

    @Test
    fun `an exdate that can't land gives no list`() {
        // A typed rule whose first instances don't line up with the old ones (Thursdays added):
        // the cancelled Tuesday can't be mapped.
        val m = move("FREQ=WEEKLY;BYDAY=TU;COUNT=4", at("2024-03-05", "10:00"), at("2024-03-05", "11:00"),
            typedRule = "FREQ=WEEKLY;BYDAY=TU,TH;COUNT=4")
        assertNotNull(m)
        assertNull(m!!.mapExdates("${at("2024-03-26", "10:00")}"))
    }

    @Test
    fun `a split keeps legacy date tokens among the cancelled dates`() {
        val start = at("2024-03-05", "10:00")
        val split = at("2024-03-19", "10:00")
        val master = Event(
            uid = "split@example.test", calendarId = 1L, title = "Split", startTs = start, endTs = start + HOUR,
            timezone = NY, rrule = "FREQ=WEEKLY;COUNT=6", exdate = "20240312,${at("2024-03-26", "10:00")}",
            dtstamp = 1, createdAt = 1, updatedAt = 1,
        )
        val edited = master.copy(startTs = at("2024-03-19", "11:00"), endTs = at("2024-03-19", "12:00"))

        val plan = SeriesMove.planSplit(master, split, edited, pastCount = 2, newSeriesRule = "FREQ=WEEKLY;COUNT=4")

        assertEquals("20240312,${at("2024-03-26", "11:00")}", plan!!.exdate)
    }

    // ---- typed rules ----

    @Test
    fun `a typed count change with a time move maps keys in the shared part`() {
        val m = move("FREQ=WEEKLY;COUNT=10", at("2024-03-05", "10:00"), at("2024-03-05", "11:00"), typedRule = "FREQ=WEEKLY;COUNT=12")
        assertEquals(at("2024-03-19", "11:00"), moved(m, at("2024-03-19", "10:00")))
    }

    @Test
    fun `a typed shorter count leaves a key past its end to the typed rule`() {
        val start = at("2024-03-05", "10:00")
        val m = move("FREQ=WEEKLY;COUNT=12", start, at("2024-03-05", "11:00"), typedRule = "FREQ=WEEKLY;COUNT=10")
        // The 11th occurrence (index 10) is past the typed rule's end.
        assertEquals(SeriesMove.Key.PastTypedEnd, m!!.mapKey(at("2024-05-14", "10:00")))
    }

    @Test
    fun `a typed until a year out on an old series maps recent keys`() {
        val m = move("FREQ=DAILY", at("2022-04-01", "09:00"), at("2022-04-01", "10:00"), typedRule = "FREQ=DAILY;UNTIL=20250101T000000Z")
        assertEquals(at("2024-03-18", "10:00"), moved(m, at("2024-03-18", "09:00")))
    }

    @Test
    fun `this and future maps from the opened occurrence`() {
        // Split on Tue 19 Mar moved to Wed 20 Mar: the new series is Wednesdays.
        val m = move("FREQ=WEEKLY;BYDAY=TU;COUNT=6", at("2024-03-05", "10:00"), at("2024-03-20", "10:00"),
            anchor = at("2024-03-19", "10:00"))
        assertEquals("FREQ=WEEKLY;BYDAY=WE;COUNT=6", m!!.newRule)
        assertEquals(at("2024-03-27", "10:00"), moved(m, at("2024-03-26", "10:00")))
    }

    // ---- the same rule for a move ----

    @Test
    fun `rules equal by meaning or naming the same end date count as the same rule`() {
        fun same(a: String, b: String, bAllDay: Boolean = false) = SeriesMove.sameRuleForMove(a, NY, false, b, NY, bAllDay)
        assertTrue(same("FREQ=WEEKLY;BYDAY=MO;COUNT=4", "FREQ=WEEKLY;COUNT=4;BYDAY=MO"))
        // The last second of Mar 26 in New York, re-anchored as the all-day date Mar 26.
        assertTrue(same("FREQ=DAILY;UNTIL=20240327T035959Z", "FREQ=DAILY;UNTIL=20240326", bAllDay = true))
        // A new end date is the user's own rule.
        assertFalse(same("FREQ=DAILY;UNTIL=20240327T035959Z", "FREQ=DAILY;UNTIL=20240501T035959Z"))
        assertFalse(same("FREQ=DAILY;COUNT=4", "FREQ=DAILY;COUNT=5"))
        assertFalse(same("FREQ=DAILY;UNTIL=20240326", "FREQ=DAILY;COUNT=5"))
    }

    private companion object {
        const val NY = "America/New_York"
        const val TOKYO = "Asia/Tokyo"
        const val LONDON = "Europe/London"
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
    }
}
