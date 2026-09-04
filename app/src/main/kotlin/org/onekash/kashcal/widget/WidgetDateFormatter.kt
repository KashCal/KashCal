package org.onekash.kashcal.widget

import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Short-form labels for the small circular date-icon (e.g. "SUN" over "19"). */
data class DateWidgetLabels(val dayName: String, val dateNumber: String)

/** Full-form labels for the larger date-card (e.g. "Saturday" over "September 19"). */
data class DateWidgetFullLabels(val weekdayFull: String, val monthDay: String)

object WidgetDateFormatter {
    fun buildDateWidgetLabels(today: LocalDate, locale: Locale): DateWidgetLabels {
        val dayName = today.dayOfWeek
            .getDisplayName(TextStyle.SHORT, locale)
            .uppercase(locale)
        val dateNumber = today.dayOfMonth.toString()
        return DateWidgetLabels(dayName = dayName, dateNumber = dateNumber)
    }

    /**
     * Full weekday plus a year-less, locale-ordered month+day for the card layout.
     *
     * Month+day reuses the app-wide date helper: ICU decides only the field ordering
     * (the "MMMMd" skeleton → a locale pattern), then java.time formats the date.
     * This is the same round-trip already shipping for the week and agenda headers,
     * so the card, the icon's day number, and the accessibility label all read the
     * one Gregorian date rather than a locale's alternate default calendar. It gives
     * CJK the single month marker (e.g. "9月19日") and Slavic locales the date
     * (genitive) month form ("19 сентября"). The full weekday comes from java.time.
     */
    fun buildFullDateWidgetLabels(today: LocalDate, locale: Locale): DateWidgetFullLabels {
        val weekdayFull = today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val monthDay = today.format(
            DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMMd", locale), locale)
        )
        return DateWidgetFullLabels(weekdayFull = weekdayFull, monthDay = monthDay)
    }
}
