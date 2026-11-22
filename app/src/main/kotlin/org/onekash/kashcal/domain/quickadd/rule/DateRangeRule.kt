package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.Month
import java.time.temporal.ChronoUnit

/**
 * Reads a calendar-date range as a multi-day event: "Aug 23 to 28", "Aug 30 to Sep 2",
 * "23 to 28 August", the glued "Aug 23-28" / "23-28 August", and a spaced dash in place of "to",
 * each with an optional year after it and a "from" before it.
 *
 * The start takes [ParseContext.resolveFutureDate]'s year; the end takes the start's year, one
 * later when its month comes first and the range then spans at most [MAX_WRAP_DAYS] days
 * ("Dec 24 to Jan 2"). Anything else that doesn't end after its start ("Aug 28 to 23", "Sep 2 to
 * Aug 30") is not a range: the rule sets and consumes nothing, and the single-date rules read
 * the start. An end day followed by am/pm or a unit is a time or an amount, not a day.
 *
 * With a repeat rule already read ("every day Aug 3 to 7") the range ends the series: its end
 * becomes the rule's UNTIL, not a multi-day length for every occurrence.
 */
object DateRangeRule : ParseRule {

    private const val MAX_WRAP_DAYS = 92

    // A glued day pair: two day numbers and a hyphen, no year ("23-28").
    private val DAY_PAIR = Regex("""(\d{1,2})-(\d{1,2})""")

    private class Shape(
        val startDay: Int,
        val startMonth: Month,
        val endDay: Int,
        val endMonth: Month,
        val indices: List<Int>,
    )

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for (i in tokens.indices) {
            if (context.isConsumed(i)) continue
            val shape = matchAt(tokens, i, context) ?: continue

            val yearIndex = shape.indices.max() + 1
            val year = tokens.getOrNull(yearIndex)
                ?.takeIf { it.type == TokenType.YEAR && !context.isConsumed(yearIndex) }
                ?.value as? Int

            val wraps = shape.endMonth < shape.startMonth
            // A year after a range across New Year names the end's year ("Dec 24 to Jan 2 2027").
            val startYear = if (wraps && year != null) year - 1 else year
            val start = context.resolveFutureDate(shape.startDay, shape.startMonth.value, startYear) ?: continue
            val endYear = if (wraps) start.year + 1 else start.year
            val end = context.resolveFutureDate(shape.endDay, shape.endMonth.value, endYear) ?: continue
            if (wraps && ChronoUnit.DAYS.between(start, end) > MAX_WRAP_DAYS) continue
            if (!end.isAfter(start)) continue

            context.absoluteDate = start
            context.dateSet = true
            // The calendar range replaces an earlier weekday range ("Mon to Wed Oct 5 to 7"):
            // without a repeat rule its end is the event's end; with one it ends the series, and
            // the weekday range's end, which belonged to the replaced start, goes.
            val rrule = context.rrule
            if (rrule == null) {
                context.endDate = end
            } else {
                context.endDate = null
                if (context.untilDate == null && "COUNT=" !in rrule) context.untilDate = end
            }
            context.consume(shape.indices)
            if (year != null) context.consume(yearIndex)
            consumeKeywordBefore(tokens, shape.indices.min(), context, "FROM")
            return
        }
    }

    private fun matchAt(tokens: List<Token>, i: Int, context: ParseContext): Shape? {
        fun free(index: Int) = index in tokens.indices && !context.isConsumed(index)
        fun month(index: Int) = tokens.getOrNull(index)?.takeIf { free(index) && it.type == TokenType.MONTH }
            ?.value as? Month
        fun day(index: Int) = tokens.getOrNull(index)?.takeIf { free(index) && it.type == TokenType.NUMBER }
            ?.let { it.value as? Int }?.takeIf { it in 1..31 }
        fun separator(index: Int) = tokens.getOrNull(index)?.takeIf { free(index) }?.let(::isRangeSeparator) == true
        fun dayPair(index: Int): Pair<Int, Int>? {
            val token = tokens.getOrNull(index)?.takeIf { free(index) && it.type == TokenType.STRUCTURED_DATE }
                ?: return null
            // Read the days from the text: the token's date parts can put the month first ("3-5").
            val match = DAY_PAIR.matchEntire(token.text) ?: return null
            val first = match.groupValues[1].toInt()
            val second = match.groupValues[2].toInt()
            return if (first in 1..31 && second in 1..31) first to second else null
        }
        // An end day followed by am/pm or a unit reads as a time or an amount.
        fun endsAsDay(index: Int) = tokens.getOrNull(index + 1)?.type.let {
            it != TokenType.MERIDIEM && it != TokenType.UNIT
        }

        val startMonth = month(i)
        if (startMonth != null) {
            // MONTH <day pair>
            dayPair(i + 1)?.let { (d1, d2) -> return Shape(d1, startMonth, d2, startMonth, listOf(i, i + 1)) }
            val d1 = day(i + 1) ?: return null
            if (!separator(i + 2)) return null
            // MONTH NUMBER to MONTH NUMBER
            month(i + 3)?.let { endMonth ->
                val d2 = day(i + 4)?.takeIf { endsAsDay(i + 4) } ?: return null
                return Shape(d1, startMonth, d2, endMonth, (i..i + 4).toList())
            }
            // MONTH NUMBER to NUMBER
            val d2 = day(i + 3)?.takeIf { endsAsDay(i + 3) } ?: return null
            return Shape(d1, startMonth, d2, startMonth, (i..i + 3).toList())
        }

        // <day pair> MONTH
        dayPair(i)?.let { (d1, d2) ->
            val m = month(i + 1) ?: return null
            return Shape(d1, m, d2, m, listOf(i, i + 1))
        }
        // NUMBER to NUMBER MONTH
        val d1 = day(i) ?: return null
        if (!separator(i + 1)) return null
        val d2 = day(i + 2) ?: return null
        val m = month(i + 3) ?: return null
        return Shape(d1, m, d2, m, (i..i + 3).toList())
    }
}
