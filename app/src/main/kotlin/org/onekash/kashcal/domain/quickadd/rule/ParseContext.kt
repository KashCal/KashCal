package org.onekash.kashcal.domain.quickadd.rule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal fun isDotOnly(text: String): Boolean =
    text.contains('.') && !text.contains('/') && !text.contains('-')

/**
 * @param firstDayOfWeek `java.util.Calendar` constant or 0 (system default);
 *   used by RecurrenceRule for biweekly WKST emission.
 */
class ParseContext(
    val reference: LocalDateTime,
    val firstDayOfWeek: Int = 0,
) {

    // Date components (priority: absoluteDate > relativeDate > weekdayDate > dateKeywordDate)
    var absoluteDate: LocalDate? = null
    var relativeDateTime: LocalDateTime? = null
    var weekdayDate: LocalDate? = null
    var dateKeywordDate: LocalDate? = null

    // Time component
    var time: LocalTime? = null
    var endTime: LocalTime? = null

    // End date (for multi-day events like "Friday to Sunday")
    var endDate: LocalDate? = null

    // Location component
    var location: String? = null

    // Timezone component
    var timezone: String? = null

    // Recurrence component
    var rrule: String? = null

    // Whether date or time was explicitly set by a rule
    var dateSet: Boolean = false
    var timeSet: Boolean = false

    // Consumed token indices (for title extraction)
    private val consumedIndices = mutableSetOf<Int>()

    fun consume(index: Int) {
        consumedIndices.add(index)
    }

    fun consume(indices: Collection<Int>) {
        consumedIndices.addAll(indices)
    }

    fun isConsumed(index: Int): Boolean = index in consumedIndices

    fun getConsumedIndices(): Set<Int> = consumedIndices.toSet()

    fun findNextUnconsumed(tokens: List<*>, fromIndex: Int): Int? {
        for (i in fromIndex until tokens.size) {
            if (!isConsumed(i)) return i
        }
        return null
    }

    fun resolveDate(): LocalDate {
        return absoluteDate
            ?: relativeDateTime?.toLocalDate()
            ?: weekdayDate
            ?: dateKeywordDate
            ?: reference.toLocalDate()
    }

    fun resolveTime(): LocalTime? {
        return time ?: relativeDateTime?.toLocalTime()?.takeIf { timeSet }
    }

    /**
     * Resolve a day/month/year to a LocalDate, biased toward future dates.
     * If no year is given, uses the current year if the date is today or later, otherwise next year.
     * Returns null for invalid dates (e.g., Feb 30).
     */
    fun resolveFutureDate(day: Int, month: Int, year: Int?): LocalDate? {
        return try {
            if (year != null) {
                LocalDate.of(year, month, day)
            } else {
                val refDate = reference.toLocalDate()
                val thisYear = LocalDate.of(refDate.year, month, day)
                if (!thisYear.isBefore(refDate)) thisYear
                else LocalDate.of(refDate.year + 1, month, day)
            }
        } catch (_: Exception) {
            null
        }
    }
}
