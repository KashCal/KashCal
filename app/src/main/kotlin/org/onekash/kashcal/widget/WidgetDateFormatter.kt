package org.onekash.kashcal.widget

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class DateWidgetLabels(val dayName: String, val dateNumber: String)

object WidgetDateFormatter {
    fun buildDateWidgetLabels(today: LocalDate, locale: Locale): DateWidgetLabels {
        val dayName = today.dayOfWeek
            .getDisplayName(TextStyle.SHORT, locale)
            .uppercase(locale)
        val dateNumber = today.dayOfMonth.toString()
        return DateWidgetLabels(dayName = dayName, dateNumber = dateNumber)
    }
}
