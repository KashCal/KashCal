package org.onekash.kashcal.ui.viewmodels

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/**
 * Date filter for search. [Today] through [NextMonth] compute their range from today's date,
 * [SingleDay] and [CustomRange] from the picked days; the search keeps events with an
 * occurrence in the range.
 */
@Immutable
sealed class DateFilter {
    abstract val displayName: String

    /**
     * Returns the filter's range as (startMs, endMs), from the first day's midnight in [zone] to
     * the last millisecond of the last day, or null for [Upcoming] and [AnyTime]; the search
     * treats those as from now on and as all dates.
     *
     * @param firstDayOfWeek a Calendar constant or 0 for the locale default; only [ThisWeek] and
     *   [NextWeek] read it.
     */
    abstract fun getTimeRange(
        zone: ZoneId = ZoneId.systemDefault(),
        firstDayOfWeek: Int = Calendar.SUNDAY
    ): Pair<Long, Long>?

    data object Upcoming : DateFilter() {
        override val displayName = "Upcoming"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long>? = null
    }

    data object AnyTime : DateFilter() {
        override val displayName = "Any time"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long>? = null
    }

    data object Today : DateFilter() {
        override val displayName = "Today"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object Tomorrow : DateFilter() {
        override val displayName = "Tomorrow"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val tomorrow = LocalDate.now(zone).plusDays(1)
            val start = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = tomorrow.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object ThisWeek : DateFilter() {
        override val displayName = "This week"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val daysFromWeekStart = DateTimeUtils.getDayOfWeekOffset(today, firstDayOfWeek)
            val startOfWeek = today.minusDays(daysFromWeekStart.toLong())
            val endOfWeek = startOfWeek.plusDays(6)
            val start = startOfWeek.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfWeek.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object NextWeek : DateFilter() {
        override val displayName = "Next week"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val daysFromWeekStart = DateTimeUtils.getDayOfWeekOffset(today, firstDayOfWeek)
            val startOfThisWeek = today.minusDays(daysFromWeekStart.toLong())
            val startOfNextWeek = startOfThisWeek.plusDays(7)
            val endOfNextWeek = startOfNextWeek.plusDays(6)
            val start = startOfNextWeek.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfNextWeek.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object ThisMonth : DateFilter() {
        override val displayName = "This month"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val startOfMonth = today.withDayOfMonth(1)
            val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())
            val start = startOfMonth.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfMonth.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object NextMonth : DateFilter() {
        override val displayName = "Next month"
        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val startOfNextMonth = today.plusMonths(1).withDayOfMonth(1)
            val endOfNextMonth = startOfNextMonth.withDayOfMonth(startOfNextMonth.lengthOfMonth())
            val start = startOfNextMonth.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfNextMonth.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    /** One day, picked by tapping the same day twice in the search date picker. */
    data class SingleDay(val dateMs: Long) : DateFilter() {
        override val displayName: String
            get() {
                val date = Instant.ofEpochMilli(dateMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                return date.format(DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMd"), java.util.Locale.getDefault()))
            }

        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            val date = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    /** A range, picked by tapping two different days in the search date picker. */
    data class CustomRange(val startMs: Long, val endMs: Long) : DateFilter() {
        override val displayName: String
            get() {
                val startDate = Instant.ofEpochMilli(startMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val endDate = Instant.ofEpochMilli(endMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val formatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMd"), java.util.Locale.getDefault())
                return "${startDate.format(formatter)} - ${endDate.format(formatter)}"
            }

        override fun getTimeRange(zone: ZoneId, firstDayOfWeek: Int): Pair<Long, Long> {
            // From the start of startMs's day to the end of endMs's day.
            val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
            val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    companion object {
        /** Preset filters. No app code reads this; HomeScreen builds the search chips itself. */
        val presets = listOf(AnyTime, Today, Tomorrow, ThisWeek, NextWeek, ThisMonth)
    }
}
