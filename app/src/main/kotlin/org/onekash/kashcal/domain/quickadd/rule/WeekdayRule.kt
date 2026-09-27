package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.DayOfWeek
import java.time.LocalDate

object WeekdayRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.WEEKDAY) continue

            val targetDay = token.value as? DayOfWeek ?: continue

            // A next, this or last keyword before the weekday.
            val modifier = findModifier(tokens, index, context)

            val date = when (modifier?.first) {
                "LAST" -> resolveLastWeekday(context.reference.toLocalDate(), targetDay)
                "NEXT" -> resolveNextWeekday(context.reference.toLocalDate(), targetDay)
                "THIS" -> resolveThisWeekday(context.reference.toLocalDate(), targetDay)
                else -> resolveBareWeekday(context.reference.toLocalDate(), targetDay)
            }

            context.weekdayDate = date
            context.dateSet = true
            context.consume(index)
            modifier?.let { context.consume(it.second) }

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
