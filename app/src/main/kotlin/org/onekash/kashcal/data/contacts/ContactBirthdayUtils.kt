package org.onekash.kashcal.data.contacts

import java.util.Calendar
import java.util.TimeZone

/**
 * Data class representing a parsed birthday from a contact.
 *
 * @param month Month (1-12)
 * @param day Day of month (1-31)
 * @param year Birth year (null if not available)
 */
data class BirthdayInfo(
    val month: Int,
    val day: Int,
    val year: Int?
)

/**
 * Utility functions for contact birthday parsing and formatting.
 *
 * Handles:
 * - Parsing birthday strings from Android Contacts (multiple formats)
 * - Age calculation from birth year and occurrence date
 * - Birthday event title formatting with ordinal suffixes
 * - Birth year encoding/decoding in event description
 */
object ContactBirthdayUtils {

    // Description field format for storing birth year
    private const val BIRTH_YEAR_PREFIX = "birthYear:"

    /**
     * Parse a birthday string from Android Contacts.
     *
     * Supports formats:
     * - "--MM-DD" (no year, RFC 6350 vCard format)
     * - "YYYY-MM-DD" (full date)
     * - "YYYY/MM/DD" (alternative format)
     * - "MM/DD/YYYY" (US format)
     * - "DD/MM/YYYY" (European format - ambiguous, assumes US)
     *
     * @param birthdayString The birthday string from contacts
     * @return Parsed BirthdayInfo or null if unparseable
     */
    fun parseBirthday(birthdayString: String?): BirthdayInfo? {
        if (birthdayString.isNullOrBlank()) return null

        val trimmed = birthdayString.trim()

        // Format: --MM-DD (no year, RFC 6350)
        if (trimmed.startsWith("--")) {
            val parts = trimmed.substring(2).split("-")
            if (parts.size == 2) {
                val month = parts[0].toIntOrNull()
                val day = parts[1].toIntOrNull()
                if (month != null && day != null && isValidMonthDay(month, day)) {
                    return BirthdayInfo(month, day, null)
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
                return BirthdayInfo(month, day, year)
            }
        }

        // Format: MM/DD/YYYY or MM-DD-YYYY (US format)
        val usPattern = Regex("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")
        usPattern.matchEntire(trimmed)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()
            if (year != null && month != null && day != null && isValidDate(year, month, day)) {
                return BirthdayInfo(month, day, year)
            }
        }

        return null
    }

    /**
     * Calculate age from birth year and occurrence timestamp.
     *
     * @param birthYear The year of birth
     * @param occurrenceTs Occurrence timestamp in milliseconds
     * @return Age at the time of the occurrence
     */
    fun calculateAge(birthYear: Int, occurrenceTs: Long): Int {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = occurrenceTs
        val occurrenceYear = calendar.get(Calendar.YEAR)
        return occurrenceYear - birthYear
    }

    /**
     * Format ordinal suffix for a number (1st, 2nd, 3rd, 4th, etc.)
     *
     * @param n The number
     * @return Formatted string with ordinal suffix
     */
    fun formatOrdinal(n: Int): String {
        return when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
    }

    /**
     * Format birthday event title with optional age.
     *
     * @param displayName Contact display name
     * @param birthYear Birth year (null if unknown)
     * @param occurrenceTs Occurrence timestamp for age calculation
     * @return Formatted title like "John Smith's 30th Birthday" or "John Smith's Birthday"
     */
    fun formatBirthdayTitle(displayName: String, birthYear: Int?, occurrenceTs: Long): String {
        return if (birthYear != null) {
            val age = calculateAge(birthYear, occurrenceTs)
            if (age > 0 && age < 150) {
                "$displayName's ${formatOrdinal(age)} Birthday"
            } else {
                "$displayName's Birthday"
            }
        } else {
            "$displayName's Birthday"
        }
    }

    /**
     * Encode birth year into event description.
     *
     * @param birthYear The birth year (null if unknown)
     * @return Description string with encoded birth year, or null
     */
    fun encodeBirthYear(birthYear: Int?): String? {
        return birthYear?.let { "$BIRTH_YEAR_PREFIX$it" }
    }

    /**
     * Decode birth year from event description.
     *
     * @param description Event description that may contain birth year
     * @return Extracted birth year or null
     */
    fun decodeBirthYear(description: String?): Int? {
        if (description == null) return null
        val prefix = BIRTH_YEAR_PREFIX
        val index = description.indexOf(prefix)
        if (index == -1) return null

        val start = index + prefix.length
        val end = description.indexOfAny(charArrayOf('\n', ' ', '\t'), start).takeIf { it != -1 } ?: description.length
        return description.substring(start, end).toIntOrNull()
    }

    /**
     * Generate RRULE for yearly birthday recurrence.
     */
    fun generateBirthdayRRule(): String = "FREQ=YEARLY;INTERVAL=1"

    /**
     * Calculate the timestamp for a birthday in a given year.
     *
     * All-day event: returns midnight UTC of the birthday date.
     *
     * @param month Birthday month (1-12)
     * @param day Birthday day (1-31)
     * @param year The year to calculate for
     * @return Timestamp in milliseconds
     */
    fun getBirthdayTimestamp(month: Int, day: Int, year: Int): Long {
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
     * Get the next upcoming birthday timestamp from today.
     *
     * Uses local timezone for date comparison to correctly determine if
     * today's birthday has passed. The returned timestamp is still UTC
     * midnight (correct for all-day events per RFC 5545).
     *
     * @param month Birthday month (1-12)
     * @param day Birthday day (1-31)
     * @return Timestamp of the next birthday occurrence (UTC midnight)
     */
    fun getNextBirthdayTimestamp(month: Int, day: Int): Long {
        val now = Calendar.getInstance()  // Local timezone for date comparison
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH) + 1  // Calendar.MONTH is 0-based
        val currentDay = now.get(Calendar.DAY_OF_MONTH)

        // Compare calendar dates in local time (not timestamps)
        // This ensures today's birthday shows up even late in the day
        val isBirthdayTodayOrLater = when {
            month > currentMonth -> true
            month < currentMonth -> false
            else -> day >= currentDay  // Same month, compare days
        }

        return if (isBirthdayTodayOrLater) {
            getBirthdayTimestamp(month, day, currentYear)
        } else {
            // Birthday already passed this year, use next year
            getBirthdayTimestamp(month, day, currentYear + 1)
        }
    }

    private fun isValidMonthDay(month: Int, day: Int): Boolean {
        return month in 1..12 && day in 1..31
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (year < 1900 || year > 2100) return false
        if (month < 1 || month > 12) return false
        if (day < 1 || day > 31) return false

        // Basic day-of-month validation
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
