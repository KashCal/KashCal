package org.onekash.kashcal.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Utility functions for day pager page ↔ date conversions.
 *
 * The day pager uses a large page range (73,000 pages) to allow navigation
 * ~100 years in each direction. Pages are lazily composed, so the large
 * range has zero memory cost.
 *
 * Page index is relative to "today" at app launch:
 * - Page 36500 = today
 * - Page 36501 = tomorrow
 * - Page 36499 = yesterday
 */
object DayPagerUtils {
    /** Initial page index (represents "today") */
    const val INITIAL_PAGE = 36500

    /** Total number of pages (~100 years each direction) */
    const val TOTAL_PAGES = 73000

    /** Milliseconds in one day */
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Get today at local midnight (stable reference point).
     *
     * Uses java.time APIs (Android recommended) for timezone-aware calculation.
     * This ensures DST transitions are handled correctly.
     *
     * @return Epoch milliseconds at start of today in local timezone
     */
    fun getTodayMidnightMs(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Convert page index to date (epoch millis at local midnight).
     *
     * Uses calendar-based day arithmetic to correctly handle DST transitions
     * where days can be 23 or 25 hours.
     *
     * @param page Page index from pager
     * @param todayMs Reference point (today at midnight, from [getTodayMidnightMs])
     * @return Epoch milliseconds at midnight for the date represented by this page
     */
    fun pageToDateMs(page: Int, todayMs: Long): Long {
        val today = Instant.ofEpochMilli(todayMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return today.plusDays((page - INITIAL_PAGE).toLong())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Convert date (epoch millis) to page index.
     *
     * Uses calendar-based day arithmetic to correctly handle:
     * - Negative partial days (e.g., 6 hours before midnight = yesterday)
     * - DST transitions where days can be 23 or 25 hours
     *
     * @param dateMs Epoch milliseconds (any time of day)
     * @param todayMs Reference point (today at midnight, from [getTodayMidnightMs])
     * @return Page index for this date
     */
    fun dateToPage(dateMs: Long, todayMs: Long): Int {
        val today = Instant.ofEpochMilli(todayMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val targetDate = Instant.ofEpochMilli(dateMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val dayOffset = ChronoUnit.DAYS.between(today, targetDate).toInt()
        return INITIAL_PAGE + dayOffset
    }

    /**
     * Convert epoch millis to dayCode (YYYYMMDD format).
     *
     * Uses java.time APIs for timezone-aware conversion.
     *
     * @param ms Epoch milliseconds
     * @return DayCode in YYYYMMDD format (e.g., 20260115 for Jan 15, 2026)
     */
    fun msToDayCode(ms: Long): Int {
        val localDate = Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return localDate.year * 10000 + localDate.monthValue * 100 + localDate.dayOfMonth
    }

    /**
     * Convert dayCode to LocalDate.
     *
     * @param dayCode DayCode in YYYYMMDD format
     * @return LocalDate for this dayCode
     */
    fun dayCodeToLocalDate(dayCode: Int): LocalDate {
        val year = dayCode / 10000
        val month = (dayCode % 10000) / 100
        val day = dayCode % 100
        return LocalDate.of(year, month, day)
    }

    /**
     * Convert dayCode to epoch millis at midnight.
     *
     * @param dayCode DayCode in YYYYMMDD format
     * @return Epoch milliseconds at midnight for this date
     */
    fun dayCodeToMs(dayCode: Int): Long {
        return dayCodeToLocalDate(dayCode)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
