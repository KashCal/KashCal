package org.onekash.kashcal.ui.util

import org.onekash.kashcal.ui.util.DayPagerUtils.getTodayMidnightMs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Converts between day pager pages and dates.
 *
 * The pager has [TOTAL_PAGES] (73,000) pages, about 100 years in each direction. Pages are
 * composed lazily, so the range costs no memory.
 *
 * A page index is relative to the `todayMs` reference the caller passes, taken once from
 * [getTodayMidnightMs]:
 * - Page 36500 = today
 * - Page 36501 = tomorrow
 * - Page 36499 = yesterday
 */
object DayPagerUtils {
    /** Page index of the `todayMs` reference day. */
    const val INITIAL_PAGE = 36500

    /** Total page count, about 100 years in each direction. */
    const val TOTAL_PAGES = 73000

    /** Milliseconds in a 24-hour day; not a calendar day across a DST change. */
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Returns the start of today in the system time zone, in epoch milliseconds.
     *
     * Callers take it once and keep it as the stable reference for page conversions.
     */
    fun getTodayMidnightMs(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Returns the start of the page's day in the system time zone, in epoch milliseconds.
     *
     * Adds calendar days, not [DAY_MS] multiples, so a 23- or 25-hour DST day doesn't shift
     * the result off midnight.
     *
     * @param todayMs reference point from [getTodayMidnightMs]
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
     * Returns the page index for the day containing [dateMs] in the system time zone.
     *
     * Counts calendar days, not [DAY_MS] multiples, so 6 hours before today's midnight maps to
     * yesterday and a 23- or 25-hour DST day counts as one.
     *
     * @param dateMs any time of day
     * @param todayMs reference point from [getTodayMidnightMs]
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

    /** Returns the YYYYMMDD day code of [ms] in the system time zone, e.g. 20260115. */
    fun msToDayCode(ms: Long): Int {
        val localDate = Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return localDateToDayCode(localDate)
    }

    /** Packs [date] into a YYYYMMDD day code, e.g. 20260115 for Jan 15, 2026. */
    fun localDateToDayCode(date: LocalDate): Int {
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    /** Unpacks a YYYYMMDD [dayCode] into a [LocalDate]. */
    fun dayCodeToLocalDate(dayCode: Int): LocalDate {
        val year = dayCode / 10000
        val month = (dayCode % 10000) / 100
        val day = dayCode % 100
        return LocalDate.of(year, month, day)
    }

    /** Returns the start of the YYYYMMDD [dayCode]'s day in the system time zone, in epoch ms. */
    fun dayCodeToMs(dayCode: Int): Long {
        return dayCodeToLocalDate(dayCode)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
