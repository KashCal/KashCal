package org.onekash.kashcal.ui.viewmodels

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * A date range search narrows to: [ThisWeek] and [ThisMonth] from today's date, [SingleDay] and
 * [CustomRange] from the days picked in the search date sheet. The search lists events with an
 * occurrence in the range.
 */
@Immutable
sealed class DateFilter {

    /**
     * Returns the range as (startMs, endMs), from the first day's midnight in [zone] to the last
     * millisecond of the last day.
     *
     * @param firstDayOfWeek a Calendar constant or 0 for the locale default; only [ThisWeek]
     *   reads it.
     */
    abstract fun getTimeRange(
        zone: ZoneId = ZoneId.systemDefault(),
        firstDayOfWeek: Int = Calendar.SUNDAY
    ): Pair<Long, Long>

    data object ThisWeek : DateFilter() {
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val daysFromWeekStart = DateTimeUtils.getDayOfWeekOffset(today, firstDayOfWeek)
            val startOfWeek = today.minusDays(daysFromWeekStart.toLong())
            return days(startOfWeek, startOfWeek.plusDays(6), zone)
        }
    }

    data object ThisMonth : DateFilter() {
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            return days(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()), zone)
        }
    }

    /** One day, picked by tapping the same day twice in the search date picker. */
    data class SingleDay(val dateMs: Long) : DateFilter() {
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val date = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
            return days(date, date, zone)
        }
    }

    /** A range, picked by tapping two different days in the search date picker. */
    data class CustomRange(val startMs: Long, val endMs: Long) : DateFilter() {
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> = days(
            Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate(),
            zone,
        )
    }

    protected fun days(first: LocalDate, last: LocalDate, zone: ZoneId): Pair<Long, Long> =
        first.atStartOfDay(zone).toInstant().toEpochMilli() to
            last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
}
