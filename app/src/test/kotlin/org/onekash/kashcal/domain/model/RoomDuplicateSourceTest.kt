package org.onekash.kashcal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests [toEventForDuplicate] on a Room event: which start the copy takes and which
 * recurrence fields it keeps.
 */
class RoomDuplicateSourceTest {

    private val newYork = ZoneId.of("America/New_York")
    private val hour = 3_600_000L

    private fun nyMillis(y: Int, m: Int, d: Int, h: Int) =
        LocalDateTime.of(y, m, d, h, 0).atZone(newYork).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val seriesStart = nyMillis(2026, 1, 5, 9) // a Monday
    private val thirdMonday = nyMillis(2026, 1, 19, 9)
    private val extraWednesday = nyMillis(2026, 1, 14, 9)

    private fun master(
        rrule: String? = "FREQ=WEEKLY;BYDAY=MO",
        startTs: Long = seriesStart,
        rdate: String? = "$extraWednesday",
        exdate: String? = "${nyMillis(2026, 1, 12, 9)}",
    ) = Event(
        id = 1L,
        uid = "series@test",
        calendarId = 1L,
        title = "Standup",
        startTs = startTs,
        endTs = startTs + hour,
        timezone = "America/New_York",
        rrule = rrule,
        rdate = rdate,
        exdate = exdate,
        dtstamp = 0L,
    )

    @Test
    fun `series occurrence copy starts on the tapped occurrence and keeps rule and zone`() {
        val copy = master().toEventForDuplicate(thirdMonday)

        assertEquals(thirdMonday, copy.startTs)
        assertEquals(thirdMonday + hour, copy.endTs)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", copy.rrule)
        assertEquals("America/New_York", copy.timezone)
        assertNull(copy.rdate)
        assertNull(copy.exdate)
    }

    @Test
    fun `series copy without a tapped occurrence keeps the series start`() {
        val copy = master().toEventForDuplicate(null)

        assertEquals(seriesStart, copy.startTs)
        assertEquals(seriesStart + hour, copy.endTs)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", copy.rrule)
    }

    @Test
    fun `extra-date occurrence copy is a one-off at that occurrence`() {
        val copy = master().toEventForDuplicate(extraWednesday)

        assertEquals(extraWednesday, copy.startTs)
        assertEquals(extraWednesday + hour, copy.endTs)
        assertNull(copy.rrule)
        assertNull(copy.rdate)
        assertNull(copy.exdate)
    }

    @Test
    fun `changed occurrence copy keeps its own times and has no recurrence`() {
        val movedStart = nyMillis(2026, 1, 20, 14)
        val exception = master(rrule = null, rdate = null, exdate = null, startTs = movedStart).copy(
            id = 2L,
            originalEventId = 1L,
            originalInstanceTime = thirdMonday,
        )

        val copy = exception.toEventForDuplicate(thirdMonday)

        assertEquals(movedStart, copy.startTs)
        assertEquals(movedStart + hour, copy.endTs)
        assertNull(copy.rrule)
        assertNull(copy.rdate)
        assertNull(copy.exdate)
    }

    @Test
    fun `extra-dates-only event copy keeps its own times`() {
        val event = master(rrule = null)

        val copy = event.toEventForDuplicate(extraWednesday)

        assertEquals(seriesStart, copy.startTs)
        assertEquals(seriesStart + hour, copy.endTs)
        assertNull(copy.rrule)
        assertNull(copy.rdate)
    }

    @Test
    fun `one-off copy keeps its own times and zone`() {
        val event = master(rrule = null, rdate = null, exdate = null)

        val copy = event.toEventForDuplicate(seriesStart)

        assertEquals(seriesStart, copy.startTs)
        assertEquals("America/New_York", copy.timezone)
        assertNull(copy.rrule)
    }

    @Test
    fun `all-day series copy starts on the tapped UTC midnight`() {
        val start = utcMidnight(2026, 3, 2)
        val tapped = utcMidnight(2026, 3, 16)
        val event = master(startTs = start, rdate = null, exdate = null).copy(
            isAllDay = true,
            endTs = start + 24 * hour - 1,
            timezone = null,
        )

        val copy = event.toEventForDuplicate(tapped)

        assertEquals(tapped, copy.startTs)
        assertEquals(tapped + 24 * hour - 1, copy.endTs)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", copy.rrule)
    }
}
