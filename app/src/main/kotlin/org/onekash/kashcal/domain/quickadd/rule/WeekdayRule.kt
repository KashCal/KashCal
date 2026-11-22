package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object WeekdayRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.WEEKDAY) continue

            val targetDay = token.value as? DayOfWeek ?: continue

            // A next, this or last keyword before the weekday.
            val modifier = findModifier(tokens, index, context)

            val week = findWeekSuffix(tokens, index, context)
            val refDate = context.reference.toLocalDate()
            val date = when {
                // "<weekday> this week" is the coming such day, today included, whatever the
                // week start; "next week" is the calendar week after this one.
                week != null && !week.first -> resolveThisWeekday(refDate, targetDay)
                week != null -> weekdayNextWeek(context, targetDay)
                modifier?.first == "LAST" -> resolveLastWeekday(refDate, targetDay)
                modifier?.first == "NEXT" -> resolveNextWeekday(refDate, targetDay)
                modifier?.first == "THIS" -> resolveThisWeekday(refDate, targetDay)
                else -> resolveBareWeekday(refDate, targetDay)
            }

            context.weekdayDate = date
            context.dateSet = true
            context.consume(index)
            modifier?.let { context.consume(it.second) }
            week?.let { context.consume(it.second) }

            // "<weekday> to <weekday>" makes a multi-day event ending on the second weekday.
            val toIdx = context.findNextUnconsumed(tokens, index + 1)
            if (toIdx != null) {
                val toToken = tokens[toIdx]
                if (toToken.type == TokenType.KEYWORD && toToken.value == "TO") {
                    val endWeekdayIdx = context.findNextUnconsumed(tokens, toIdx + 1)
                    if (endWeekdayIdx != null) {
                        val endToken = tokens[endWeekdayIdx]
                        if (endToken.type == TokenType.WEEKDAY) {
                            val endDay = endToken.value as? DayOfWeek
                            if (endDay != null) {
                                val endDate = resolveBareWeekday(date, endDay)
                                context.endDate = endDate
                                context.consume(toIdx)
                                context.consume(endWeekdayIdx)
                            }
                        }
                    }
                }
            }

            return
        }
    }

    /**
     * Returns the unconsumed NEXT, THIS or LAST keyword right before the weekday and its index,
     * or null.
     */
    private fun findModifier(tokens: List<Token>, weekdayIndex: Int, context: ParseContext): Pair<String, Int>? {
        if (weekdayIndex == 0) return null
        val prev = tokens[weekdayIndex - 1]
        if (context.isConsumed(weekdayIndex - 1)) return null
        if (prev.type != TokenType.KEYWORD) return null

        val value = prev.value as? String ?: return null
        return when (value) {
            "NEXT" -> "NEXT" to (weekdayIndex - 1)
            "THIS" -> "THIS" to (weekdayIndex - 1)
            "LAST" -> "LAST" to (weekdayIndex - 1)
            else -> null
        }
    }

    /**
     * Finds "[of] next week" or "[of] this week" right after the weekday at [weekdayIndex] and
     * returns whether it is next week with the indices to consume, or null.
     */
    private fun findWeekSuffix(
        tokens: List<Token>,
        weekdayIndex: Int,
        context: ParseContext,
    ): Pair<Boolean, List<Int>>? {
        fun keyword(i: Int) =
            tokens.getOrNull(i)?.takeIf { !context.isConsumed(i) && it.type == TokenType.KEYWORD }?.value
        var cursor = weekdayIndex + 1
        val indices = mutableListOf<Int>()
        if (keyword(cursor) == "OF") indices.add(cursor++)
        val which = keyword(cursor)
        if (which != "NEXT" && which != "THIS") return null
        indices.add(cursor++)
        val unit = tokens.getOrNull(cursor)?.takeIf { !context.isConsumed(cursor) && it.type == TokenType.UNIT }
        if (unit?.value != ChronoUnit.WEEKS) return null
        indices.add(cursor)
        return (which == "NEXT") to indices
    }

    /**
     * Returns [target] in the calendar week after the reference's. Weeks start on the first day of
     * the week setting ([ParseContext.firstDayOfWeek]).
     */
    private fun weekdayNextWeek(context: ParseContext, target: DayOfWeek): LocalDate {
        val nextWeek = weekStart(context.reference.toLocalDate(), context.firstDayOfWeek).plusWeeks(1)
        return nextWeek.with(TemporalAdjusters.nextOrSame(target))
    }

    /** Resolves "this [weekday]": the next such day, or today if it is that day. */
    private fun resolveThisWeekday(refDate: LocalDate, target: DayOfWeek): LocalDate {
        val diff = target.value - refDate.dayOfWeek.value
        val daysToAdd = if (diff < 0) diff + 7 else diff
        return refDate.plusDays(daysToAdd.toLong())
    }

    /** Resolves "next [weekday]": the first such day at least 7 days out. */
    private fun resolveNextWeekday(refDate: LocalDate, target: DayOfWeek): LocalDate {
        val bare = resolveBareWeekday(refDate, target)
        val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(refDate, bare)
        return if (daysBetween < 7) bare.plusDays(7) else bare
    }

    /** Resolves "last [weekday]": the most recent such day before today. */
    private fun resolveLastWeekday(refDate: LocalDate, target: DayOfWeek): LocalDate {
        val diff = refDate.dayOfWeek.value - target.value
        val daysToSubtract = if (diff <= 0) diff + 7 else diff
        return refDate.minusDays(daysToSubtract.toLong())
    }
}
