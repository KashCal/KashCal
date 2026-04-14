package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.rrule.RruleBuilder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object RecurrenceRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue

            when {
                // Pattern 1: Standalone RECURRENCE_KEYWORD ("daily", "weekly", etc.)
                token.type == TokenType.RECURRENCE_KEYWORD -> {
                    val rrule = recurrenceKeywordToRrule(token.value as? String ?: continue) ?: continue
                    context.rrule = rrule
                    context.consume(index)
                    return
                }

                // Patterns 2-4: EVERY + ...
                token.type == TokenType.KEYWORD && token.value == "EVERY" -> {
                    val result = parseEveryPattern(tokens, index, context)
                    if (result) return
                }
            }
        }
    }

    /**
     * Parse patterns starting with EVERY:
     * - EVERY + WEEKDAY → weekly with BYDAY
     * - EVERY + UNIT → frequency from unit
     * - EVERY + NUMBER + UNIT [+ ON + WEEKDAY] → frequency with interval, optional BYDAY
     */
    private fun parseEveryPattern(tokens: List<Token>, everyIndex: Int, context: ParseContext): Boolean {
        val nextIndex = everyIndex + 1
        if (nextIndex >= tokens.size) return false
        val next = tokens[nextIndex]
        if (context.isConsumed(nextIndex)) return false

        // EVERY + WEEKDAY → "every Monday"
        if (next.type == TokenType.WEEKDAY) {
            val day = next.value as? DayOfWeek ?: return false
            context.rrule = RruleBuilder.weekly(days = setOf(day))
            context.weekdayDate = resolveBareWeekday(context.reference.toLocalDate(), day)
            context.dateSet = true
            context.consume(everyIndex)
            context.consume(nextIndex)
            return true
        }

        // EVERY + UNIT → "every day", "every week", etc.
        if (next.type == TokenType.UNIT) {
            val rrule = unitToRrule(next.value as? ChronoUnit ?: return false, 1) ?: return false
            context.rrule = rrule
            context.consume(everyIndex)
            context.consume(nextIndex)
            return true
        }

        // EVERY + NUMBER + UNIT [+ ON + WEEKDAY] → "every 2 weeks", "every 2 weeks on Friday"
        if (next.type == TokenType.NUMBER) {
            val interval = next.value as? Int ?: return false
            val unitIndex = nextIndex + 1
            if (unitIndex >= tokens.size) return false
            val unitToken = tokens[unitIndex]
            if (context.isConsumed(unitIndex)) return false
            if (unitToken.type != TokenType.UNIT) return false

            val rrule = unitToRrule(unitToken.value as? ChronoUnit ?: return false, interval) ?: return false
            val consumed = mutableListOf(everyIndex, nextIndex, unitIndex)

            // Check for optional ON + WEEKDAY
            val onIndex = unitIndex + 1
            if (onIndex < tokens.size && !context.isConsumed(onIndex)) {
                val onToken = tokens[onIndex]
                if (onToken.type == TokenType.KEYWORD && onToken.value == "ON") {
                    val weekdayIndex = onIndex + 1
                    if (weekdayIndex < tokens.size && !context.isConsumed(weekdayIndex)) {
                        val weekdayToken = tokens[weekdayIndex]
                        if (weekdayToken.type == TokenType.WEEKDAY) {
                            val day = weekdayToken.value as? DayOfWeek
                            if (day != null) {
                                // Rebuild rrule with BYDAY for weekly
                                val unit = unitToken.value as ChronoUnit
                                val rruleWithDay = if (unit == ChronoUnit.WEEKS) {
                                    RruleBuilder.weekly(interval = interval, days = setOf(day))
                                } else {
                                    rrule
                                }
                                context.rrule = rruleWithDay
                                context.weekdayDate = resolveBareWeekday(context.reference.toLocalDate(), day)
                                context.dateSet = true
                                consumed.add(onIndex)
                                consumed.add(weekdayIndex)
                                context.consume(consumed)
                                return true
                            }
                        }
                    }
                }
            }

            context.rrule = rrule
            context.consume(consumed)
            return true
        }

        return false
    }

    private fun recurrenceKeywordToRrule(keyword: String): String? {
        return when (keyword) {
            "DAILY" -> RruleBuilder.daily()
            "WEEKLY" -> RruleBuilder.weekly()
            "BIWEEKLY" -> RruleBuilder.weekly(interval = 2)
            "MONTHLY" -> RruleBuilder.monthly()
            "YEARLY" -> RruleBuilder.yearly()
            else -> null
        }
    }

    private fun unitToRrule(unit: ChronoUnit, interval: Int): String? {
        return when (unit) {
            ChronoUnit.DAYS -> RruleBuilder.daily(interval)
            ChronoUnit.WEEKS -> RruleBuilder.weekly(interval)
            ChronoUnit.MONTHS -> RruleBuilder.monthly(interval)
            ChronoUnit.YEARS -> RruleBuilder.yearly(interval)
            else -> null
        }
    }

    /**
     * Resolve bare weekday to next occurrence. If today is the same day, advance 7 days.
     * Duplicated from WeekdayRule (private there) — same 3-line logic.
     */
    private fun resolveBareWeekday(refDate: LocalDate, target: DayOfWeek): LocalDate {
        val diff = target.value - refDate.dayOfWeek.value
        val daysToAdd = if (diff <= 0) diff + 7 else diff
        return refDate.plusDays(daysToAdd.toLong())
    }
}
