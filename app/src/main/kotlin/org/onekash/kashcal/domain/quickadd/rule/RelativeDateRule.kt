package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.DayOfWeek
import java.time.LocalDate

object RelativeDateRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.DATE_KEYWORD) continue

            val keyword = token.value as? String ?: continue
            val refDate = context.reference.toLocalDate()

            if (keyword == "ALL_DAY") {
                context.consume(index)
                continue
            }

            if (keyword == "weekend") {
                // "every weekend" is a recurrence; RecurrenceRule, which runs after this
                // rule, takes it.
                if (precededByEvery(tokens, index, context)) continue
                if (handleWeekend(tokens, index, context)) return
                continue
            }

            val date = when (keyword) {
                "today" -> refDate
                "tomorrow" -> refDate.plusDays(1)
                "yesterday" -> refDate.minusDays(1)
                "day_after_tomorrow" -> refDate.plusDays(2)
                "day_before_yesterday" -> refDate.minusDays(2)
                else -> continue
            }

            context.dateKeywordDate = date
            context.dateSet = true
            context.consume(index)
            return
        }
    }

    /**
     * Resolves "weekend" to the coming Saturday, a week later after "next", and consumes a
     * preceding "next" or "this" and an "on" or "on the" lead-in. The weekend starts on
     * Saturday whatever the first-day-of-week setting, matching the app's fixed Saturday and
     * Sunday weekend.
     */
    private fun handleWeekend(tokens: List<Token>, index: Int, context: ParseContext): Boolean {
        val refDate = context.reference.toLocalDate()
        val modifier = findModifier(tokens, index, context)

        var date = comingSaturday(refDate)
        if (modifier?.first == "NEXT") {
            date = date.plusDays(7)
        }

        context.dateKeywordDate = date
        context.dateSet = true
        context.consume(index)
        modifier?.let { context.consume(it.second) }
        consumePrecedingOnThe(tokens, index, context)
        return true
    }

    /** Returns today if it is Saturday, else the next Saturday; never a past date. */
    private fun comingSaturday(refDate: LocalDate): LocalDate {
        val diff = DayOfWeek.SATURDAY.value - refDate.dayOfWeek.value
        val daysToAdd = if (diff < 0) diff + 7 else diff
        return refDate.plusDays(daysToAdd.toLong())
    }

    /** Returns the unconsumed NEXT or THIS keyword right before [index] and its index, or null. */
    private fun findModifier(tokens: List<Token>, index: Int, context: ParseContext): Pair<String, Int>? {
        if (index == 0) return null
        val prevIndex = index - 1
        if (context.isConsumed(prevIndex)) return null
        val prev = tokens[prevIndex]
        if (prev.type != TokenType.KEYWORD) return null
        return when (prev.value) {
            "NEXT" -> "NEXT" to prevIndex
            "THIS" -> "THIS" to prevIndex
            else -> null
        }
    }

    /** True when the token before [index] is an unconsumed EVERY keyword. */
    private fun precededByEvery(tokens: List<Token>, index: Int, context: ParseContext): Boolean {
        if (index == 0) return false
        val prevIndex = index - 1
        if (context.isConsumed(prevIndex)) return false
        val prev = tokens[prevIndex]
        return prev.type == TokenType.KEYWORD && prev.value == "EVERY"
    }

    /** Consumes a preceding "on" and an optional "the" between it and the weekend. */
    private fun consumePrecedingOnThe(tokens: List<Token>, index: Int, context: ParseContext) {
        var cursor = index - 1
        // Skip consumed tokens, such as the modifier.
        while (cursor >= 0 && context.isConsumed(cursor)) cursor--
        if (cursor < 0) return
        val maybeThe = tokens[cursor]
        var theConsumedFrom = -1
        if (maybeThe.type == TokenType.KEYWORD && maybeThe.value == "THE") {
            theConsumedFrom = cursor
            cursor--
        }
        if (cursor < 0) return
        val maybeOn = tokens[cursor]
        if (maybeOn.type == TokenType.KEYWORD && maybeOn.value == "ON") {
            context.consume(cursor)
            if (theConsumedFrom >= 0) context.consume(theConsumedFrom)
        }
    }
}
