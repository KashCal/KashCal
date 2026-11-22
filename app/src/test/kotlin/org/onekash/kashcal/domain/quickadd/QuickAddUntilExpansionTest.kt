package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Locale

/**
 * Expands Quick Add "until" series with the app's recurrence engine from the start the event is
 * saved with (QuickAddViewModel.save: the start time in the device zone, or UTC midnight for an
 * all-day event), so the last occurrence the user named is checked end to end.
 */
class QuickAddUntilExpansionTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String, zone: ZoneId) =
        QuickAddParser.parse(input, reference, Locale.US, Calendar.MONDAY, zone)

    /** Returns the occurrences as local date-times in [zone], or UTC dates for an all-day rule. */
    private fun occurrences(result: QuickAddResult, zone: ZoneId, days: Long = 200): List<LocalDateTime> {
        val allDay = result.startTime == null
        val dtstart = if (allDay) {
            result.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            result.startDate.atTime(result.startTime).atZone(zone).toInstant().toEpochMilli()
        }
        val outZone = if (allDay) ZoneOffset.UTC else zone
        return IcalDavRRuleEngine.expandToTimestamps(
            rrule = result.rrule,
            dtstartMs = dtstart,
            rangeStartMs = dtstart,
            rangeEndMs = dtstart + days * 24 * 60 * 60 * 1000,
            timezone = if (allDay) null else zone.id,
            isAllDay = allDay,
            rdateStrings = null,
            exdateStrings = null,
        ).map { Instant.ofEpochMilli(it).atZone(outZone).toLocalDateTime() }
    }

    @Test
    fun `a timed series keeps the named last day in every zone`() {
        val zones = listOf(
            "America/New_York", "Europe/Berlin", "Asia/Tokyo", "Pacific/Kiritimati", "Pacific/Pago_Pago",
        )
        for (id in zones) {
            val zone = ZoneId.of(id)
            val result = parse("class every monday at 9am until june 15", zone)
            val dates = occurrences(result, zone)
            assertEquals("$id: count", 9, dates.size)
            assertEquals("$id: last", LocalDateTime.of(2026, 6, 15, 9, 0), dates.last())
        }
    }

    @Test
    fun `a timed series ends at the end of the named day in the save zone`() {
        val result = parse("class every monday at 9am until june 15", ZoneId.of("America/New_York"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260616T035959Z", result.rrule)
    }

    @Test
    fun `an all-day series gets a date UNTIL that includes the named day`() {
        val zone = ZoneId.of("America/New_York")
        val result = parse("class every monday until june 15", zone)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260615", result.rrule)
        val dates = occurrences(result, zone)
        assertEquals(9, dates.size)
        assertEquals(LocalDate.of(2026, 6, 15), dates.last().toLocalDate())
    }

    @Test
    fun `until a month ends on its last day`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231",
            parse("every Monday until December", zone).rrule
        )
        // Dec 31 23:59:59 in New York (EST) is Jan 1 04:59:59 UTC.
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20270101T045959Z",
            parse("every Monday at 9am until December", zone).rrule
        )
    }

    @Test
    fun `a named day already past rolls to next year`() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20270115",
            parse("every Monday until Jan 15", ZoneId.of("America/New_York")).rrule
        )
    }

    @Test
    fun `an end before the first occurrence makes a single event`() {
        // Monday-first week of Mon Apr 13 ends Sun Apr 19; a bare "monday" on a Monday is Apr 20.
        val result = parse("standup every monday until the end of the week", ZoneId.of("America/New_York"))
        assertNull(result.rrule)
        assertEquals(LocalDate.of(2026, 4, 20), result.startDate)
        assertEquals("standup", result.title)
    }

    @Test
    fun `a year after the until date reaches that year's last day`() {
        val zone = ZoneId.of("America/New_York")
        val result = parse("Gym every monday at 7am until june 15 2027", zone)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270616T035959Z", result.rrule)
        // Jun 15 2027 is a Tuesday, so the last class is Monday Jun 14.
        assertEquals(LocalDateTime.of(2027, 6, 14, 7, 0), occurrences(result, zone, days = 500).last())
    }
}
