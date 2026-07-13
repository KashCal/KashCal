package org.onekash.kashcal.ui.components

import java.time.LocalDate

/**
 * Derives the month shown in the Agenda top-bar title from the agenda list's
 * topmost visible item. The list is a flat LazyColumn whose item keys all end
 * with the entry's day code in YYYYMMDD form: day headers are "header_<day>"
 * and event cards are "room_<id>_<startTs>_<day>" / "device_<id>_<day>". Taking
 * the trailing '_'-delimited token yields the day code regardless of item type.
 */
object AgendaTitleMonth {

    /**
     * @param key the topmost visible list item's key, or null when the list is empty
     * @param fallback the date whose month/year is used when [key] is absent or unparseable
     * @return (year, 0-indexed month) — 0-indexed to match [LocalDate.getMonthValue] minus one
     *         and the ViewMode.MONTH title inputs
     */
    fun monthYearFromItemKey(key: String?, fallback: LocalDate): Pair<Int, Int> {
        val fallbackPair = fallback.year to (fallback.monthValue - 1)
        if (key == null) return fallbackPair

        val dayCode = key.substringAfterLast('_').toIntOrNull() ?: return fallbackPair
        val year = dayCode / 10000
        val month = (dayCode % 10000) / 100 - 1 // 0-indexed
        if (month !in 0..11) return fallbackPair
        return year to month
    }
}
