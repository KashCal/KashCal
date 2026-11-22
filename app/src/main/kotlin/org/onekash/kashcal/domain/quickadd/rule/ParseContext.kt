package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

internal fun isDotOnly(text: String): Boolean =
    text.contains('.') && !text.contains('/') && !text.contains('-')

/** True for the word joining the two ends of a range: "to", or a free-standing "-". */
internal fun isRangeSeparator(token: Token): Boolean =
    (token.type == TokenType.KEYWORD && token.value == "TO") ||
        (token.type == TokenType.UNKNOWN && token.text == "-")

/**
 * Consumes an unconsumed KEYWORD [value] right before [first] ("from" before a range) and
 * returns the phrase's first index.
 */
internal fun consumeKeywordBefore(tokens: List<Token>, first: Int, context: ParseContext, value: String): Int {
    if (first == 0 || context.isConsumed(first - 1)) return first
    val prev = tokens[first - 1]
    if (prev.type != TokenType.KEYWORD || prev.value != value) return first
    context.consume(first - 1)
    return first - 1
}

/** Returns the first day of [refDate]'s week by the first-day-of-week setting [firstDayOfWeek]. */
internal fun weekStart(refDate: LocalDate, firstDayOfWeek: Int): LocalDate =
    refDate.with(TemporalAdjusters.previousOrSame(DateTimeUtils.resolveFirstDayOfWeekAsDow(firstDayOfWeek)))

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
 *   [RecurrenceRule] uses it for the WKST of an "every N weeks on <weekday>" rule and for "until
 *   the end of the week", [WeekdayRule] for "<weekday> next week".
 * @param source the typed text the tokens' spans point into, or null when the tokens carry no
 *   spans; [LocationRule] cuts the location out of it and sets none without it.
 */
class ParseContext(
    val reference: LocalDateTime,
    val firstDayOfWeek: Int = 0,
    val source: String? = null,
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

    var rrule: String? = null

    // Last day an "until" phrase or a date range named for rrule. The rules leave rrule without
    // an UNTIL; QuickAddParser appends it once the time is known.
    var untilDate: LocalDate? = null

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
