package org.onekash.kashcal.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The date rule and the start/end an "All events" save uses when the form was
 * opened on a later occurrence of a series: the series keeps its own first
 * date, and only a changed clock time moves it. The phone is in Berlin and the
 * event in New York, so a date read in the wrong zone shows up.
 */
class EventFormSeriesDatesTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun pinPhoneZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun restorePhoneZone() = TimeZone.setDefault(savedZone)

    private val ny = ZoneId.of(EVENT_ZONE)

    private fun at(date: String, time: String, zone: ZoneId = ny): Long =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun utcMidnight(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The form as it opens on a timed occurrence at [occurrenceTs] lasting an hour. */
    private fun timedForm(occurrenceTs: Long) = EventFormState(timezone = EVENT_ZONE, isAllDay = false)
        .withDateFields(timedFormDateFields(occurrenceTs, occurrenceTs + HOUR, EVENT_ZONE))

    /** The form as it opens on a one-day all-day occurrence at [utcMidnight]. */
    private fun allDayForm(utcMidnight: Long) = EventFormState(timezone = null, isAllDay = true)
        .withDateFields(allDayFormDateFields(utcMidnight, utcMidnight))

    // Weekly series: Tue 5 Mar 2024 10:00 New York (EST); 19 Mar is after the switch to EDT.
    private val seriesStart = at("2024-03-05", "10:00")
    private val occurrence = at("2024-03-19", "10:00")

    // ---- the date rule ----

    @Test
    fun `an unchanged form is not a date change`() {
        assertFalse(timedForm(occurrence).occurrenceDateChanged(occurrence, occurrenceIsAllDay = false))
    }

    @Test
    fun `a new clock time on the same day is not a date change`() {
        val form = timedForm(occurrence).copy(startHour = 10, startMinute = 30, endHour = 11, endMinute = 30)

        assertFalse(form.occurrenceDateChanged(occurrence, occurrenceIsAllDay = false))
    }

    @Test
    fun `moving the occurrence to another day is a date change`() {
        val form = timedForm(occurrence).withDateFields(
            timedFormDateFields(at("2024-03-20", "10:00"), at("2024-03-20", "11:00"), EVENT_ZONE)
        )

        assertTrue(form.occurrenceDateChanged(occurrence, occurrenceIsAllDay = false))
    }

    @Test
    fun `the date is read in the event's zone, not the phone's`() {
        // 23:30 in New York is already the next day in Berlin.
        val late = at("2024-03-19", "23:30")
        val form = timedForm(late).copy(startHour = 23, startMinute = 45)

        assertFalse(form.occurrenceDateChanged(late, occurrenceIsAllDay = false))
    }

    @Test
    fun `a clock time moved across the phone's midnight on the event's own day is not a date change`() {
        // 23:30 New York (Wednesday in Berlin) moved to 17:00 New York (still Tuesday in Berlin):
        // the same Tuesday in the event's zone, different days on the phone.
        val late = at("2024-03-19", "23:30")
        val form = timedForm(late).copy(startHour = 17, startMinute = 0, endHour = 18, endMinute = 0)

        assertFalse(form.occurrenceDateChanged(late, occurrenceIsAllDay = false))
    }

    @Test
    fun `a clock time moved to the next day in the event's zone is a date change`() {
        // 23:30 New York moved to 00:30 the next New York day (same Berlin day as the original).
        val late = at("2024-03-19", "23:30")
        val form = timedForm(late).withDateFields(
            timedFormDateFields(at("2024-03-20", "00:30"), at("2024-03-20", "01:30"), EVENT_ZONE)
        )

        assertTrue(form.occurrenceDateChanged(late, occurrenceIsAllDay = false))
    }

    @Test
    fun `changing only the timezone is not a date change`() {
        val late = at("2024-03-19", "23:30")
        val form = timedForm(late).withTimezone("Asia/Tokyo")

        assertFalse(form.occurrenceDateChanged(late, occurrenceIsAllDay = false))
    }

    @Test
    fun `an all-day occurrence compares by its calendar date`() {
        val day = utcMidnight("2024-03-19")

        assertFalse(allDayForm(day).occurrenceDateChanged(day, occurrenceIsAllDay = true))
        assertTrue(allDayForm(utcMidnight("2024-03-20")).occurrenceDateChanged(day, occurrenceIsAllDay = true))
    }

    @Test
    fun `turning a timed occurrence all-day on the same date is not a date change`() {
        assertFalse(allDayForm(utcMidnight("2024-03-19")).occurrenceDateChanged(occurrence, occurrenceIsAllDay = false))
    }

    // ---- the start and end an All events save writes ----

    @Test
    fun `an untouched start keeps the series start exactly`() {
        val (start, end) = timedForm(occurrence).startEndAnchoredToSeries(occurrence, seriesStart, seriesIsAllDay = false)

        assertEquals(seriesStart, start)
        assertEquals(seriesStart + HOUR, end)
    }

    @Test
    fun `an untouched start keeps a series start that carries seconds`() {
        val withSeconds = seriesStart + 17_000L
        val occ = occurrence + 17_000L

        val (start, _) = timedForm(occ).startEndAnchoredToSeries(occ, withSeconds, seriesIsAllDay = false)

        assertEquals(withSeconds, start)
    }

    @Test
    fun `a new clock time moves the series to that time on its own first date, across a DST change`() {
        val form = timedForm(occurrence).copy(startHour = 10, startMinute = 30, endHour = 11, endMinute = 30)

        val (start, end) = form.startEndAnchoredToSeries(occurrence, seriesStart, seriesIsAllDay = false)

        assertEquals(at("2024-03-05", "10:30"), start)
        assertEquals(at("2024-03-05", "11:30"), end)
    }

    @Test
    fun `a timezone change keeps the series start when the instant is unchanged`() {
        val form = timedForm(occurrence).withTimezone("Asia/Tokyo")

        val (start, _) = form.startEndAnchoredToSeries(occurrence, seriesStart, seriesIsAllDay = false)

        assertEquals(seriesStart, start)
    }

    @Test
    fun `an all-day series keeps its first date and its length`() {
        val first = utcMidnight("2024-03-05")
        val occ = utcMidnight("2024-03-19")
        val form = allDayForm(occ)
        val (formStart, formEnd) = form.toStartEndTs()

        val (start, end) = form.startEndAnchoredToSeries(occ, first, seriesIsAllDay = true)

        assertEquals(first, start)
        assertEquals(formEnd - formStart, end - start)
    }

    @Test
    fun `turning a timed series all-day from a later occurrence starts on the series' first date`() {
        val (start, _) = allDayForm(utcMidnight("2024-03-19"))
            .startEndAnchoredToSeries(occurrence, seriesStart, seriesIsAllDay = false)

        assertEquals(utcMidnight("2024-03-05"), start)
    }

    @Test
    fun `turning an all-day series timed from a later occurrence keeps its first date`() {
        val first = utcMidnight("2024-03-05")
        val occ = utcMidnight("2024-03-19")
        val form = EventFormState(timezone = EVENT_ZONE, isAllDay = false)
            .withDateFields(timedFormDateFields(at("2024-03-19", "09:00"), at("2024-03-19", "10:00"), EVENT_ZONE))

        val (start, end) = form.startEndAnchoredToSeries(occ, first, seriesIsAllDay = true)

        assertEquals(at("2024-03-05", "09:00"), start)
        assertEquals(at("2024-03-05", "10:00"), end)
    }

    // ---- an all-day toggle is never "untouched", even when the instants coincide ----

    private val london = ZoneId.of("Europe/London")

    @Test
    fun `turning a London midnight series all-day from a winter occurrence starts on its own first date`() {
        // Mondays 00:00 London from 21 Oct 2024 (summer time: 20 Oct 23:00Z); 4 Nov is GMT, so
        // its 00:00 equals that day's UTC midnight.
        val first = at("2024-10-21", "00:00", london)
        val nov4 = at("2024-11-04", "00:00", london)
        val form = allDayForm(utcMidnight("2024-11-04")).copy(timezone = "Europe/London")

        val (start, _) = form.startEndAnchoredToSeries(nov4, first, seriesIsAllDay = false)

        assertEquals(utcMidnight("2024-10-21"), start)
    }

    @Test
    fun `turning a London all-day series timed from a winter occurrence keeps the shown clock time`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val first = utcMidnight("2024-10-21")
        val nov4 = utcMidnight("2024-11-04")
        val form = EventFormState(timezone = null, isAllDay = false)
            .withDateFields(timedFormDateFields(nov4, nov4 + HOUR, null))

        val (start, _) = form.startEndAnchoredToSeries(nov4, first, seriesIsAllDay = true)

        assertEquals("00:00 London on the series' first date", at("2024-10-21", "00:00", london), start)
    }

    private companion object {
        const val EVENT_ZONE = "America/New_York"
        const val HOUR = 3_600_000L
    }
}
