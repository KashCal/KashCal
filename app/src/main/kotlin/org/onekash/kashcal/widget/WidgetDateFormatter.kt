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
     * Returns the full weekday and a year-less, locale-ordered month and day for the card
     * layout.
     *
     * The month and day use the app-wide date helper: ICU picks only the field order (the
     * "MMMMd" skeleton to a locale pattern), then java.time formats the date, as the Week and
     * Agenda headers do. So the card, the icon's day number and the accessibility label all read
     * one Gregorian date, not a locale's alternate default calendar. CJK gets the single month
     * marker ("9月19日") and Slavic locales the genitive month form ("19 сентября"). The full
     * weekday comes from java.time.
     */
    fun buildFullDateWidgetLabels(today: LocalDate, locale: Locale): DateWidgetFullLabels {
        val weekdayFull = today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val monthDay = today.format(
            DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMMd", locale), locale)
        )
        return DateWidgetFullLabels(weekdayFull = weekdayFull, monthDay = monthDay)
    }
}
