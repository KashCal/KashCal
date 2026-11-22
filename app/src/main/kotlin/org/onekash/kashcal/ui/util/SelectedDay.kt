package org.onekash.kashcal.ui.util

import org.onekash.kashcal.ui.model.MonthGrid
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reads and writes the selected day, and the days the Month grids and the inline date picker
 * show, as device-zone days with java.time.
 *
 * HomeViewModel.selectDate stores the start of the selected day as java.time computes it, so the
 * taps that write it and the checks that read it use java.time too. After a cutoff year (2100 on
 * Android 17, an earlier year on Android 13) Android's java.util zone data keeps the offset of
 * the zone's last listed transition while java.time keeps the zone's rule, so in a zone with
 * daylight saving time the two engines are an hour apart for part of each year. A midnight
 * written by one engine and read by the other then lands on a neighbouring day (#435).
 */
object SelectedDay {

    /**
     * Returns whether a cell with [position] and [dayCode] is highlighted for [selectedDayCode]:
     * a day of the grid's own month that is the selected day. Padding days from the neighbouring
     * months are never highlighted.
     */
    fun isSelectedCell(position: MonthGrid.DayPosition, dayCode: Int, selectedDayCode: Int): Boolean =
        position == MonthGrid.DayPosition.MonthDate && dayCode == selectedDayCode

    /** Returns [ms]'s day in the device zone. */
    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Returns the month of [ms]'s day in the device zone. */
    fun yearMonth(ms: Long): YearMonth = YearMonth.from(localDate(ms))

    /** Returns the year and 0-based month (January = 0) of [ms]'s day in the device zone. */
    fun yearMonth0(ms: Long): Pair<Int, Int> = yearMonth(ms).let { it.year to it.monthValue - 1 }

    /** Returns whether [ms]'s day in the device zone is in [year]'s 0-based [month0]. */
    fun isInMonth(ms: Long, year: Int, month0: Int): Boolean = yearMonth0(ms) == (year to month0)

    /** Returns the start of the 1st of [year]'s 0-based [month0] in the device zone. */
    fun monthStartMs(year: Int, month0: Int): Long =
        LocalDate.of(year, month0 + 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Returns [date] at [timeOfDayFromMs]'s local time of day in the device zone, to the
     * millisecond, as a lenient java.util.Calendar resolves it: a time the clocks skip moves
     * forward by the gap, and a time they repeat takes its later, standard-time instant.
     *
     * The result stays on [date]: where the skipped time runs past midnight (Greenland's change
     * at Saturday 23:00 in March), it is the start of [date] instead of a time on the next day.
     * The exception is a day a zone skipped whole when it moved across the date line, such as
     * 2011-12-30 in Pacific/Apia, 1994-12-31 in Pacific/Kiritimati or 1844-12-31 in Asia/Manila:
     * no instant is on [date] there, and the result is the first instant after it.
     */
    fun dateAtTimeOfDayMs(date: LocalDate, timeOfDayFromMs: Long): Long {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(timeOfDayFromMs).atZone(zone).toLocalTime()
        val resolved = ZonedDateTime.ofLocal(date.atTime(time), zone, null).withLaterOffsetAtOverlap()
        val onDate = if (resolved.toLocalDate() == date) resolved else date.atStartOfDay(zone)
        return onDate.toInstant().toEpochMilli()
    }
}
