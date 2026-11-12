package org.onekash.kashcal.ui.util

import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Months a search date counts in months; from here on it counts in years. */
private const val MONTHS_BEFORE_YEARS = 24

/**
 * Returns how far [date] is from [today] in [locale]'s words, for a search result: "today",
 * "tomorrow" and "in 3 days" under a week, then weeks ("in 3 weeks") under a month, months
 * ("2 months ago", "14 months ago") under two years, then years ("3 years ago").
 */
fun relativeSearchDate(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
    val days = ChronoUnit.DAYS.between(today, date)
    val direction = if (days < 0) Direction.LAST else Direction.NEXT
    val months = if (days < 0) ChronoUnit.MONTHS.between(date, today) else ChronoUnit.MONTHS.between(today, date)
    val absDays = kotlin.math.abs(days)
    return when {
        days == 0L -> formatter.format(Direction.THIS, AbsoluteUnit.DAY)
        absDays == 1L -> formatter.format(direction, AbsoluteUnit.DAY)
        absDays < 7 -> formatter.format(absDays.toDouble(), direction, RelativeUnit.DAYS)
        months == 0L -> formatter.format((absDays / 7).toDouble(), direction, RelativeUnit.WEEKS)
        months < MONTHS_BEFORE_YEARS -> formatter.format(months.toDouble(), direction, RelativeUnit.MONTHS)
        else -> formatter.format((months / 12).toDouble(), direction, RelativeUnit.YEARS)
    }
}
