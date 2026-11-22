package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.testutil.withDeviceTimeZone
import java.time.LocalDate

/**
 * Tests the date rules the date and time sheet applies as the user edits: [withAllDayStart],
 * [withAllDayEnd], [withTimedLength], [withAllDayLength], [lengthMinutes] and
 * [allDayLengthDays].
 */
class EventFormDateRulesTest {

    private val newYork = "America/New_York"

    private fun day(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d)

    private fun allDay(start: LocalDate, end: LocalDate) = EventFormState(
        isAllDay = true,
        dateMillis = phoneMidnight(start),
        endDateMillis = phoneMidnight(end),
    )

    private fun timed(
        start: LocalDate, startHour: Int, startMinute: Int,
        end: LocalDate, endHour: Int, endMinute: Int,
        timezone: String? = null,
    ) = EventFormState(
        isAllDay = false,
        dateMillis = phoneMidnight(start), startHour = startHour, startMinute = startMinute,
        endDateMillis = phoneMidnight(end), endHour = endHour, endMinute = endMinute,
        timezone = timezone,
    )

    private fun EventFormState.startDate() = phoneLocalDate(dateMillis)
    private fun EventFormState.endDate() = phoneLocalDate(endDateMillis)

    // ---- all-day start ----

    @Test
    fun `moving an all-day start keeps the number of days`() = withDeviceTimeZone("Europe/Berlin") {
        val moved = allDay(day(2026, 1, 1), day(2026, 1, 3)).withAllDayStart(phoneMidnight(day(2026, 1, 10)))

        assertEquals(day(2026, 1, 10), moved.startDate())
        assertEquals(day(2026, 1, 12), moved.endDate())
    }

    @Test
    fun `moving a one-day all-day start keeps it one day`() = withDeviceTimeZone("Europe/Berlin") {
        val moved = allDay(day(2026, 1, 1), day(2026, 1, 1)).withAllDayStart(phoneMidnight(day(2026, 1, 10)))

        assertEquals(day(2026, 1, 10), moved.startDate())
        assertEquals(day(2026, 1, 10), moved.endDate())
    }

    @Test
    fun `moving an all-day start stores midnights even when the picked time is mid-day`() =
        withDeviceTimeZone("Europe/Berlin") {
            val picked = phoneMidnight(day(2026, 1, 10)) + 15 * 3_600_000L

            val moved = allDay(day(2026, 1, 1), day(2026, 1, 2)).withAllDayStart(picked)

            assertEquals(phoneMidnight(day(2026, 1, 10)), moved.dateMillis)
            assertEquals(phoneMidnight(day(2026, 1, 11)), moved.endDateMillis)
        }

    // The span between local midnights is 71 hours across the spring-forward change on Mar 8.
    @Test
    fun `moving an all-day start after a spring-forward change keeps the number of days`() =
        withDeviceTimeZone(newYork) {
            val moved = allDay(day(2026, 3, 6), day(2026, 3, 9)).withAllDayStart(phoneMidnight(day(2026, 3, 20)))

            assertEquals(day(2026, 3, 20), moved.startDate())
            assertEquals(day(2026, 3, 23), moved.endDate())
            assertEquals(phoneMidnight(day(2026, 3, 23)), moved.endDateMillis)
        }

    // The new span crosses the fall-back change on Nov 1, where a day is 25 hours long.
    @Test
    fun `moving an all-day start onto a fall-back change keeps the number of days`() =
        withDeviceTimeZone(newYork) {
            val moved = allDay(day(2026, 3, 1), day(2026, 3, 4)).withAllDayStart(phoneMidnight(day(2026, 10, 30)))

            assertEquals(day(2026, 10, 30), moved.startDate())
            assertEquals(day(2026, 11, 2), moved.endDate())
            assertEquals(phoneMidnight(day(2026, 11, 2)), moved.endDateMillis)
        }

    // ---- all-day end ----

    @Test
    fun `an all-day end after the start sets the end`() = withDeviceTimeZone("Europe/Berlin") {
        val changed = allDay(day(2026, 1, 1), day(2026, 1, 1)).withAllDayEnd(phoneMidnight(day(2026, 1, 4)), 9, 0)

        assertEquals(day(2026, 1, 1), changed.startDate())
        assertEquals(day(2026, 1, 4), changed.endDate())
    }

    @Test
    fun `an all-day end before the start swaps them`() = withDeviceTimeZone("Europe/Berlin") {
        val changed = allDay(day(2026, 1, 10), day(2026, 1, 12)).withAllDayEnd(phoneMidnight(day(2026, 1, 5)), 9, 0)

        assertEquals(day(2026, 1, 5), changed.startDate())
        assertEquals(day(2026, 1, 10), changed.endDate())
    }

    @Test
    fun `an all-day end on the start day makes a one-day event`() = withDeviceTimeZone("Europe/Berlin") {
        val changed = allDay(day(2026, 1, 10), day(2026, 1, 12)).withAllDayEnd(phoneMidnight(day(2026, 1, 10)), 9, 0)

        assertEquals(day(2026, 1, 10), changed.startDate())
        assertEquals(day(2026, 1, 10), changed.endDate())
    }

    // ---- lengths ----

    @Test
    fun `a timed length sets the end that long after the start`() = withDeviceTimeZone(newYork) {
        val form = timed(day(2026, 10, 27), 9, 0, day(2026, 10, 27), 9, 30)

        val changed = form.withTimedLength(60)

        assertEquals(10, changed.endHour)
        assertEquals(0, changed.endMinute)
        assertEquals(day(2026, 10, 27), changed.endDate())
        assertEquals(60, changed.lengthMinutes())
    }

    @Test
    fun `a timed length past midnight moves the end to the next day`() = withDeviceTimeZone(newYork) {
        val form = timed(day(2026, 10, 27), 23, 30, day(2026, 10, 27), 23, 45)

        val changed = form.withTimedLength(120)

        assertEquals(1, changed.endHour)
        assertEquals(30, changed.endMinute)
        assertEquals(day(2026, 10, 28), changed.endDate())
    }

    // 01:30 plus one real hour on Mar 8 2026 is 03:30: 02:00 to 03:00 doesn't exist.
    @Test
    fun `a timed length across a spring-forward change counts real elapsed time`() = withDeviceTimeZone(newYork) {
        val form = timed(day(2026, 3, 8), 1, 30, day(2026, 3, 8), 1, 45, timezone = newYork)

        val changed = form.withTimedLength(60)

        assertEquals(3, changed.endHour)
        assertEquals(30, changed.endMinute)
        assertEquals(60, changed.lengthMinutes())
    }

    @Test
    fun `an all-day length is inclusive of the start day`() = withDeviceTimeZone(newYork) {
        val changed = allDay(day(2026, 10, 27), day(2026, 10, 27)).withAllDayLength(5)

        assertEquals(day(2026, 10, 27), changed.startDate())
        assertEquals(day(2026, 10, 31), changed.endDate())
        assertEquals(5, changed.allDayLengthDays())
    }

    @Test
    fun `an all-day length across a spring-forward change counts calendar days`() = withDeviceTimeZone(newYork) {
        val changed = allDay(day(2026, 3, 6), day(2026, 3, 6)).withAllDayLength(4)

        assertEquals(day(2026, 3, 9), changed.endDate())
        assertEquals(phoneMidnight(day(2026, 3, 9)), changed.endDateMillis)
        assertEquals(4, changed.allDayLengthDays())
    }

    @Test
    fun `all-day length counts calendar days across both daylight saving changes`() = withDeviceTimeZone(newYork) {
        assertEquals(4, allDay(day(2026, 3, 6), day(2026, 3, 9)).allDayLengthDays())
        assertEquals(4, allDay(day(2026, 10, 30), day(2026, 11, 2)).allDayLengthDays())
        assertEquals(1, allDay(day(2026, 11, 1), day(2026, 11, 1)).allDayLengthDays())
    }
}
