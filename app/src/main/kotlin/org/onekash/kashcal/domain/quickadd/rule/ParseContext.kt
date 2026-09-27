package org.onekash.kashcal.domain.quickadd.rule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal fun isDotOnly(text: String): Boolean =
    text.contains('.') && !text.contains('/') && !text.contains('-')

/**
 * Returns the next [target] after [refDate]; when [refDate] is already [target], the one a week
 * later. Shared by the weekday and recurrence rules.
 */
internal fun resolveBareWeekday(refDate: LocalDate, target: DayOfWeek): LocalDate {
    val diff = target.value - refDate.dayOfWeek.value
    val daysToAdd = if (diff <= 0) diff + 7 else diff
    return refDate.plusDays(daysToAdd.toLong())
}

/**
 * Collects what the rules extract from one input and which tokens they consumed.
 *
 * @param firstDayOfWeek a `java.util.Calendar` day constant, or 0 for the system default;
 *   [RecurrenceRule] uses it for the WKST of an "every N weeks on <weekday>" rule.
 */
class ParseContext(
    val reference: LocalDateTime,
    val firstDayOfWeek: Int = 0,
) {

    // When several are set, resolveDate takes the first of these in declaration order.
    var absoluteDate: LocalDate? = null
    var relativeDateTime: LocalDateTime? = null
    var weekdayDate: LocalDate? = null
    var dateKeywordDate: LocalDate? = null

    var time: LocalTime? = null
    var endTime: LocalTime? = null

    // End date of a multi-day event such as "Friday to Sunday".
    var endDate: LocalDate? = null

    var location: String? = null

    var timezone: String? = null

    var rrule: String? = null

    // Whether a rule set the date or time explicitly.
    var dateSet: Boolean = false
    var timeSet: Boolean = false

    // Indices of consumed tokens; the rest form the title.
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
     * Returns the date for [day], [month] and [year], or null if it doesn't exist (Feb 30).
     * Without a year it takes this year if the date is today or later, else next year.
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
