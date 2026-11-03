package org.onekash.kashcal.domain.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Tests that [DisplayEvent.Room] takes a timed occurrence's days from its instants in the zone
 * it was built in, not from the codes stored on the row, which were computed in the device
 * zone when the row was generated (#422). All-day rows keep their stored UTC codes.
 */
class DisplayEventRoomDaysTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun saveZone() {
        savedZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(savedZone)
    }

    private fun ms(date: LocalDate, hour: Int, zone: ZoneId): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun event(start: Long, end: Long, allDay: Boolean = false) = Event(
        id = 7L,
        uid = "uid-7",
        calendarId = 1L,
        title = "ZSF meeting",
        startTs = start,
        endTs = end,
        isAllDay = allDay,
        dtstamp = 0L,
    )

    /** A row whose stored codes say Tue Nov 3, as generated in Los Angeles. */
    private fun occurrence(start: Long, end: Long, startDay: Int = 20261103, endDay: Int = 20261103) =
        Occurrence(eventId = 7L, calendarId = 1L, startTs = start, endTs = end, startDay = startDay, endDay = endDay)

    @Test
    fun `timed days come from the instants in the zone the event was built in`() {
        // Tue Nov 3 12:00-14:00 in Los Angeles is Wed Nov 4 05:00-07:00 in Tokyo.
        val start = ms(LocalDate.of(2026, 11, 3), 12, LA)
        val end = start + 2 * HOUR
        val ev = event(start, end)
        val occ = occurrence(start, end)

        val tokyo = DisplayEvent.Room(ev, occ, null, zone = TOKYO)
        assertEquals(20261104, tokyo.startDay)
        assertEquals(20261104, tokyo.endDay)

        val la = DisplayEvent.Room(ev, occ, null, zone = LA)
        assertEquals(20261103, la.startDay)
        assertEquals(20261103, la.endDay)
    }

    @Test
    fun `timed days ignore stored codes that disagree with the instants`() {
        val start = ms(LocalDate.of(2026, 11, 4), 9, TOKYO)
        val occ = occurrence(start, start + HOUR, startDay = 19990101, endDay = 19990101)

        val display = DisplayEvent.Room(event(start, start + HOUR), occ, null, zone = TOKYO)

        assertEquals(20261104, display.startDay)
        assertEquals(20261104, display.endDay)
    }

    @Test
    fun `a timed event ending at local midnight ends on the day before`() {
        // 22:00 to 00:00 in Tokyo occupies one day (issue #209).
        val start = ms(LocalDate.of(2026, 11, 4), 22, TOKYO)
        val end = ms(LocalDate.of(2026, 11, 5), 0, TOKYO)

        val display = DisplayEvent.Room(event(start, end), occurrence(start, end), null, zone = TOKYO)

        assertEquals(20261104, display.startDay)
        assertEquals(20261104, display.endDay)
    }

    @Test
    fun `a timed event over midnight spans both days`() {
        val start = ms(LocalDate.of(2026, 11, 4), 23, TOKYO)
        val end = ms(LocalDate.of(2026, 11, 5), 1, TOKYO)

        val display = DisplayEvent.Room(event(start, end), occurrence(start, end), null, zone = TOKYO)

        assertEquals(20261104, display.startDay)
        assertEquals(20261105, display.endDay)
    }

    @Test
    fun `all-day days keep the stored UTC codes in any zone`() {
        val start = LocalDate.of(2026, 11, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = start + 24 * HOUR - 1
        val ev = event(start, end, allDay = true)
        val occ = occurrence(start, end, startDay = 20261103, endDay = 20261103)

        for (zone in listOf(LA, TOKYO, ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Pacific/Pago_Pago"))) {
            val display = DisplayEvent.Room(ev, occ, null, zone = zone)
            assertEquals("start in $zone", 20261103, display.startDay)
            assertEquals("end in $zone", 20261103, display.endDay)
        }
    }

    @Test
    fun `the default zone is the device zone at construction`() {
        val start = ms(LocalDate.of(2026, 11, 3), 12, LA)
        val ev = event(start, start + 2 * HOUR)
        val occ = occurrence(start, start + 2 * HOUR)

        TimeZone.setDefault(TimeZone.getTimeZone(TOKYO))
        val built = DisplayEvent.Room(ev, occ, null)
        TimeZone.setDefault(TimeZone.getTimeZone(LA))

        assertEquals(20261104, built.startDay)
        assertEquals(TOKYO, built.zone)
    }

    @Test
    fun `events built in different zones are not equal, in the same zone they are`() {
        val start = ms(LocalDate.of(2026, 11, 3), 12, LA)
        val ev = event(start, start + 2 * HOUR)
        val occ = occurrence(start, start + 2 * HOUR)

        assertNotEquals(DisplayEvent.Room(ev, occ, null, zone = LA), DisplayEvent.Room(ev, occ, null, zone = TOKYO))
        assertEquals(DisplayEvent.Room(ev, occ, null, zone = TOKYO), DisplayEvent.Room(ev, occ, null, zone = TOKYO))
    }

    @Test
    fun `a copy with moved times derives its days from the new times`() {
        val start = ms(LocalDate.of(2026, 11, 4), 9, TOKYO)
        val original = DisplayEvent.Room(event(start, start + HOUR), occurrence(start, start + HOUR), null, zone = TOKYO)
        val moved = start + 24 * HOUR

        val copy = original.copy(occurrence = original.occurrence.copy(startTs = moved, endTs = moved + HOUR))

        assertEquals(20261105, copy.startDay)
    }

    private companion object {
        val LA: ZoneId = ZoneId.of("America/Los_Angeles")
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
        const val HOUR = 60 * 60 * 1000L
    }
}
