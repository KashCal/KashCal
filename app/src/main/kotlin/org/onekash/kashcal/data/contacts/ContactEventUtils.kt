package org.onekash.kashcal.data.contacts

import android.content.res.Resources
import org.onekash.kashcal.R
import java.util.Calendar
import java.util.TimeZone

/**
 * A birthday or anniversary date parsed from a contact.
 *
 * @param month 1-12
 * @param day 1-31
 * @param year the event year, or null if the contact has none
 */
data class ContactEventDate(
    val month: Int,
    val day: Int,
    val year: Int?
)

/** Result of [BaseContactEventRepository.syncEvents]. */
sealed class ContactEventSyncResult {
    data class Success(val added: Int, val updated: Int, val deleted: Int) : ContactEventSyncResult()
    data class Error(val message: String) : ContactEventSyncResult()
}

/**
 * Parses contact dates, computes contact event start times, formats their titles and stores
 * the event year in the description.
 */
object ContactEventUtils {

    // Prefix of the event year in the description. It stays "birthYear:" because stored
    // birthday events carry it; anniversaries share it.
    private const val EVENT_YEAR_PREFIX = "birthYear:"

    /**
     * Parses a Contacts Provider date string, or returns null if it's unparseable or invalid.
     *
     * Accepts:
     * - `--MM-DD` (no year, RFC 6350)
     * - `YYYY-MM-DD` or `YYYY/MM/DD`
     * - `MM/DD/YYYY` or `MM-DD-YYYY`; a day-first date is read as month-first, and rejected
     *   when its day is over 12
     *
     * A date with a year must fall in 1900-2100.
     */
    fun parseContactDate(dateString: String?): ContactEventDate? {
        if (dateString.isNullOrBlank()) return null

        val trimmed = dateString.trim()

        // Format: --MM-DD (no year, RFC 6350)
        if (trimmed.startsWith("--")) {
            val parts = trimmed.substring(2).split("-")
            if (parts.size == 2) {
                val month = parts[0].toIntOrNull()
                val day = parts[1].toIntOrNull()
                if (month != null && day != null && isValidMonthDay(month, day)) {
                    return ContactEventDate(month, day, null)
                }
            }
            return null
        }

        // Format: YYYY-MM-DD or YYYY/MM/DD
        val isoPattern = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")
        isoPattern.matchEntire(trimmed)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            val day = match.groupValues[3].toIntOrNull()
            if (year != null && month != null && day != null && isValidDate(year, month, day)) {
                return ContactEventDate(month, day, year)
            }
        }

        // Format: MM/DD/YYYY or MM-DD-YYYY (US format)
        val usPattern = Regex("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")
        usPattern.matchEntire(trimmed)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()
            if (year != null && month != null && day != null && isValidDate(year, month, day)) {
                return ContactEventDate(month, day, year)
            }
        }

        return null
    }

    /** Returns the occurrence's year, in the device zone, minus [eventYear]. */
    fun calculateYearsSince(eventYear: Int, occurrenceTs: Long): Int {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = occurrenceTs
        val occurrenceYear = calendar.get(Calendar.YEAR)
        return occurrenceYear - eventYear
    }

    /** Returns [n] with its English ordinal suffix, such as 1st, 12th or 23rd. */
    fun formatOrdinal(n: Int): String {
        return when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
    }

    /** Returns [n] with the localized ordinal suffix chosen by the English rule. */
    fun formatOrdinal(n: Int, resources: Resources): String {
        val suffixRes = when {
            n % 100 in 11..13 -> R.string.ordinal_suffix_th
            n % 10 == 1 -> R.string.ordinal_suffix_st
            n % 10 == 2 -> R.string.ordinal_suffix_nd
            n % 10 == 3 -> R.string.ordinal_suffix_rd
            else -> R.string.ordinal_suffix_th
        }
        return resources.getString(suffixRes, n)
    }

    /**
     * Returns a title like "John Smith's 30th Birthday", or "John Smith's Birthday" when
     * [birthYear] is null or the age isn't 1-149. The overload without [Resources] uses
     * hardcoded English.
     */
    fun formatBirthdayTitle(displayName: String, birthYear: Int?, occurrenceTs: Long): String {
        return if (birthYear != null) {
            val age = calculateYearsSince(birthYear, occurrenceTs)
            if (age > 0 && age < 150) {
                "$displayName's ${formatOrdinal(age)} Birthday"
            } else {
                "$displayName's Birthday"
            }
        } else {
            "$displayName's Birthday"
        }
    }

    fun formatBirthdayTitle(displayName: String, birthYear: Int?, occurrenceTs: Long, resources: Resources): String {
        return if (birthYear != null) {
            val age = calculateYearsSince(birthYear, occurrenceTs)
            if (age > 0 && age < 150) {
                resources.getString(R.string.contact_birthday_with_age, displayName, formatOrdinal(age, resources))
            } else {
                resources.getString(R.string.contact_birthday, displayName)
            }
        } else {
            resources.getString(R.string.contact_birthday, displayName)
        }
    }

    /**
     * Returns a title like "Alice's 10th Anniversary", or "Alice's Anniversary" when
     * [anniversaryYear] is null or the count isn't 1-149. The overload without [Resources]
     * uses hardcoded English.
     */
    fun formatAnniversaryTitle(displayName: String, anniversaryYear: Int?, occurrenceTs: Long): String {
        return if (anniversaryYear != null) {
            val years = calculateYearsSince(anniversaryYear, occurrenceTs)
            if (years > 0 && years < 150) {
                "$displayName's ${formatOrdinal(years)} Anniversary"
            } else {
                "$displayName's Anniversary"
            }
        } else {
            "$displayName's Anniversary"
        }
    }

    fun formatAnniversaryTitle(displayName: String, anniversaryYear: Int?, occurrenceTs: Long, resources: Resources): String {
        return if (anniversaryYear != null) {
            val years = calculateYearsSince(anniversaryYear, occurrenceTs)
            if (years > 0 && years < 150) {
                resources.getString(R.string.contact_anniversary_with_age, displayName, formatOrdinal(years, resources))
            } else {
                resources.getString(R.string.contact_anniversary, displayName)
            }
        } else {
            resources.getString(R.string.contact_anniversary, displayName)
        }
    }

    /** Returns the description holding [eventYear], or null when the year is unknown. */
    fun encodeEventYear(eventYear: Int?): String? {
        return eventYear?.let { "$EVENT_YEAR_PREFIX$it" }
    }

    /** Returns the year [encodeEventYear] stored in [description], or null if none. */
    fun decodeEventYear(description: String?): Int? {
        if (description == null) return null
        val prefix = EVENT_YEAR_PREFIX
        val index = description.indexOf(prefix)
        if (index == -1) return null

        val start = index + prefix.length
        val end = description.indexOfAny(charArrayOf('\n', ' ', '\t'), start).takeIf { it != -1 } ?: description.length
        return description.substring(start, end).toIntOrNull()
    }

    /** RRULE of every birthday and anniversary event. */
    const val YEARLY_RRULE = "FREQ=YEARLY;INTERVAL=1"

    /** Returns UTC midnight of the date, the start of an all-day event. [month] is 1-12. */
    fun getEventTimestamp(month: Int, day: Int, year: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month - 1) // Calendar.MONTH is 0-based
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Returns the DTSTART (UTC midnight) of a contact event: the date in [eventYear] when known.
     *
     * With no year it uses last year, so the yearly rule still yields this year's
     * occurrence. Feb 29 with no year uses the latest leap year before this one, or
     * java.util.Calendar would silently roll it to March 1.
     */
    fun getStartTimestamp(month: Int, day: Int, eventYear: Int?): Long {
        val year = if (eventYear != null) {
            eventYear
        } else {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            if (month == 2 && day == 29) {
                var candidate = currentYear - 1
                while (!isLeapYear(candidate)) {
                    candidate--
                }
                candidate
            } else {
                currentYear - 1
            }
        }
        return getEventTimestamp(month, day, year)
    }

    /**
     * Returns UTC midnight of the date's next occurrence from today (all-day, RFC 5545).
     *
     * Today is judged in the device zone, so today's date still counts late in the day.
     */
    @Deprecated("Use getStartTimestamp() instead — getNextEventTimestamp sets DTSTART to next year for past-month dates, causing RRULE to skip the current year")
    fun getNextEventTimestamp(month: Int, day: Int): Long {
        val now = Calendar.getInstance()  // Local timezone for date comparison
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH) + 1  // Calendar.MONTH is 0-based
        val currentDay = now.get(Calendar.DAY_OF_MONTH)

        val isDateTodayOrLater = when {
            month > currentMonth -> true
            month < currentMonth -> false
            else -> day >= currentDay  // Same month, compare days
        }

        return if (isDateTodayOrLater) {
            getEventTimestamp(month, day, currentYear)
        } else {
            getEventTimestamp(month, day, currentYear + 1)
        }
    }

    /**
     * Converts a reminder in minutes before the start (the CalendarContract convention) to
     * an ISO 8601 trigger duration.
     *
     * Positive minutes (before the start) give a negative trigger, negative minutes (after
     * the start) a positive one: 900 -> "-PT15H", -540 -> "PT9H", 0 -> "PT0M". It emits hours
     * and minutes, never days (`-P1D`), so all-day offsets are DST-stable exact durations.
     */
    fun minutesToIsoDuration(minutes: Int): String {
        if (minutes == 0) return "PT0M"
        val sign = if (minutes > 0) "-" else ""
        val abs = kotlin.math.abs(minutes)
        val hours = abs / 60
        val mins = abs % 60
        val body = when {
            hours == 0 -> "T${mins}M"
            mins == 0 -> "T${hours}H"
            else -> "T${hours}H${mins}M"
        }
        return "${sign}P$body"
    }

    private fun isValidMonthDay(month: Int, day: Int): Boolean {
        return month in 1..12 && day in 1..31
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (year < 1900 || year > 2100) return false
        if (month < 1 || month > 12) return false
        if (day < 1 || day > 31) return false

        val maxDays = when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDays
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}
