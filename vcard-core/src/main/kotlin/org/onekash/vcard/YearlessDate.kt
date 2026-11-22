package org.onekash.vcard

import org.onekash.vcard.model.ContactDate
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale

/**
 * The year that stands for "no year" in a contact date.
 *
 * vCard 3.0 has no date without a year: its date value needs year, month and day (RFC 2425
 * §5.8.4), and BDAY takes only a date or a date-time (RFC 2426 §3.1.5). A 3.0 birthday or
 * anniversary without a year therefore goes out as `1604-MM-DD` with [OMIT_YEAR_PARAMETER] set
 * to 1604, the form iCloud, Radicale and Xandikos keep. 1604 is a leap year, so February 29
 * fits. 4.0 has a date with no year, used instead (see VCardWriter's partialDate). No real
 * birthday or anniversary is that old, so a date in 1604 always reads as having no year, with or
 * without the parameter.
 */
internal const val OMIT_YEAR = 1604

/** The parameter naming the year a reader drops from a BDAY, ANNIVERSARY or X-ABDATE value. */
internal const val OMIT_YEAR_PARAMETER = "X-APPLE-OMIT-YEAR"

private val DASHED = Regex("""--(\d{1,2})-(\d{1,2})""")
private val COMPACT = Regex("""--(\d{2})(\d{2})""")

/**
 * Returns the month and day of a contact date without a year, else null: [text] in the
 * `--M-D`, `--MM-DD` or `--MMDD` form, or [date] in [OMIT_YEAR]. A month and day that no year
 * has (`--02-30`) is null.
 */
internal fun yearlessMonthDay(date: LocalDate?, text: String?): MonthDay? {
    if (date != null) return if (date.year == OMIT_YEAR) MonthDay.from(date) else null
    val t = text?.trim() ?: return null
    val match = DASHED.matchEntire(t) ?: COMPACT.matchEntire(t) ?: return null
    val (month, day) = match.destructured
    return runCatching { MonthDay.of(month.toInt(), day.toInt()) }.getOrNull()
}

/**
 * Returns [date] as `--MM-DD` text when it has no year by [yearlessMonthDay], else [date]
 * unchanged, so two spellings of one date without a year compare equal.
 */
internal fun normalizedYearless(date: ContactDate?): ContactDate? {
    val monthDay = date?.let { yearlessMonthDay(it.date, it.text) } ?: return date
    return ContactDate(text = dashedText(monthDay))
}

/**
 * Returns `--MM-DD` when [date] has no year: its year is [OMIT_YEAR] or equals one of
 * [omitYears], the values of [OMIT_YEAR_PARAMETER] on its property. A value that isn't the
 * date's year (malformed or blank included) is ignored.
 */
internal fun yearlessText(date: LocalDate, omitYears: List<String>): String? {
    val year = date.year.toString()
    if (date.year != OMIT_YEAR && omitYears.none { it.trim() == year }) return null
    return dashedText(MonthDay.from(date))
}

/** Returns [monthDay] as `--MM-DD`, the form a 4.0 `--MMDD` value reads back as. */
private fun dashedText(monthDay: MonthDay): String =
    String.format(Locale.ROOT, "--%02d-%02d", monthDay.monthValue, monthDay.dayOfMonth)
