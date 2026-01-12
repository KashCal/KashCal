package org.onekash.kashcal.ui.viewmodels

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Represents date filter options for search.
 *
 * Each filter provides a computed time range based on current date.
 * Used with FTS search to filter results by occurrence date range.
 */
sealed class DateFilter {
    abstract val displayName: String

    /**
     * Compute the time range for this filter.
     * @return Pair of (startMs, endMs) or null for AnyTime
     */
    abstract fun getTimeRange(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long>?

    data object AnyTime : DateFilter() {
        override val displayName = "Any time"
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long>? = null
    }

    data object Today : DateFilter() {
        override val displayName = "Today"
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object Tomorrow : DateFilter() {
        override val displayName = "Tomorrow"
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            val tomorrow = LocalDate.now(zone).plusDays(1)
            val start = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = tomorrow.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object ThisWeek : DateFilter() {
        override val displayName = "This week"
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            // Week starts on Sunday (dayOfWeek: Monday=1, Sunday=7)
            val daysSinceSunday = today.dayOfWeek.value % 7
            val startOfWeek = today.minusDays(daysSinceSunday.toLong())
            val endOfWeek = startOfWeek.plusDays(6)
            val start = startOfWeek.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfWeek.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object NextWeek : DateFilter() {
        override val displayName = "Next week"
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val daysSinceSunday = today.dayOfWeek.value % 7
            val startOfThisWeek = today.minusDays(daysSinceSunday.toLong())
            val startOfNextWeek = startOfThisWeek.plusDays(7)
            val endOfNextWeek = startOfNextWeek.plusDays(6)
            val start = startOfNextWeek.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfNextWeek.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    data object ThisMonth : DateFilter() {
        override val displayName = "This month"
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
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
        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            val today = LocalDate.now(zone)
            val startOfNextMonth = today.plusMonths(1).withDayOfMonth(1)
            val endOfNextMonth = startOfNextMonth.withDayOfMonth(startOfNextMonth.lengthOfMonth())
            val start = startOfNextMonth.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endOfNextMonth.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    /**
     * Single day selection (from date picker tap).
     */
    data class SingleDay(val dateMs: Long) : DateFilter() {
        override val displayName: String
            get() {
                val date = Instant.ofEpochMilli(dateMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                return date.format(DateTimeFormatter.ofPattern("MMM d"))
            }

        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            val date = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    /**
     * Custom date range (from date picker double tap or range selection).
     */
    data class CustomRange(val startMs: Long, val endMs: Long) : DateFilter() {
        override val displayName: String
            get() {
                val startDate = Instant.ofEpochMilli(startMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val endDate = Instant.ofEpochMilli(endMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                return "${startDate.format(formatter)} - ${endDate.format(formatter)}"
            }

        override fun getTimeRange(zone: ZoneId): Pair<Long, Long> {
            // Use start of startMs day and end of endMs day
            val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
            val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            return start to end
        }
    }

    companion object {
        /** Preset filters shown as chips */
        val presets = listOf(AnyTime, Today, Tomorrow, ThisWeek, NextWeek, ThisMonth)
    }
}
