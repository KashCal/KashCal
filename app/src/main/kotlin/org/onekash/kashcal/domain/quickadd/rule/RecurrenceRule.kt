package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object RecurrenceRule : ParseRule {

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    override fun apply(tokens: List<Token>, context: ParseContext) {
        var found = false
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue

            when {
                token.type == TokenType.RECURRENCE_KEYWORD -> {
                    val rrule = recurrenceKeywordToRrule(token.value as? String ?: continue) ?: continue
                    context.rrule = rrule
                    context.consume(index)
                    found = true
                    break
                }

                token.type == TokenType.UNKNOWN && token.text.lowercase() in listOf("weekday", "weekdays") -> {
                    context.rrule = RruleBuilder.weekly(days = WEEKDAYS)
                    context.consume(index)
                    found = true
                    break
                }

                token.type == TokenType.KEYWORD && token.value == "EVERY" -> {
                    if (parseEveryPattern(tokens, index, context)) {
                        found = true
                        break
                    }
                }
            }
        }

        if (found && context.rrule != null) {
            parseEndCondition(tokens, context)
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

        // EVERY + "weekday"/"weekdays" → "every weekday" (MO-FR)
        if (next.type == TokenType.UNKNOWN && next.text.lowercase() in listOf("weekday", "weekdays")) {
            context.rrule = RruleBuilder.weekly(days = WEEKDAYS)
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
                                    val wkstDow = DateTimeUtils.resolveFirstDayOfWeekAsDow(context.firstDayOfWeek)
                                    RruleBuilder.weekly(interval = interval, days = setOf(day), wkst = wkstDow)
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

    private fun parseEndCondition(tokens: List<Token>, context: ParseContext) {
        val rrule = context.rrule ?: return
        val refDate = context.reference.toLocalDate()

        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.KEYWORD) continue
            val value = token.value as? String ?: continue

            when (value) {
                "UNTIL" -> {
                    val nextIdx = context.findNextUnconsumed(tokens, index + 1) ?: continue
                    val nextToken = tokens[nextIdx]
                    if (nextToken.type == TokenType.MONTH) {
                        val month = nextToken.value as? Month ?: continue
                        val consumed = mutableListOf(index, nextIdx)
                        val dayIdx = context.findNextUnconsumed(tokens, nextIdx + 1)
                        val untilDate = if (dayIdx != null && tokens[dayIdx].type == TokenType.NUMBER) {
                            val day = tokens[dayIdx].value as? Int ?: continue
                            consumed.add(dayIdx)
                            resolveFutureDate(refDate, month, day)
                        } else {
                            resolveFutureMonthEnd(refDate, month)
                        }
                        val untilMs = untilDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                        context.rrule = RruleBuilder.withUntil(rrule, untilMs)
                        context.consume(consumed)
                        return
                    }
                }
                "TIMES" -> {
                    if (index == 0) continue
                    val prevIdx = index - 1
                    if (context.isConsumed(prevIdx)) continue
                    val prevToken = tokens[prevIdx]
                    if (prevToken.type != TokenType.NUMBER) continue
                    val count = prevToken.value as? Int ?: continue
                    if (count <= 0) continue
                    context.rrule = RruleBuilder.withCount(rrule, count)
                    context.consume(index)
                    context.consume(prevIdx)
                    // Also consume "for" before the number if present
                    if (prevIdx > 0 && !context.isConsumed(prevIdx - 1)) {
                        val forToken = tokens[prevIdx - 1]
                        if (forToken.type == TokenType.KEYWORD && forToken.value == "FOR") {
                            context.consume(prevIdx - 1)
                        }
                    }
                    return
                }
            }
        }
    }

    private fun resolveFutureDate(refDate: LocalDate, month: Month, day: Int): LocalDate {
        val thisYear = LocalDate.of(refDate.year, month, day.coerceAtMost(month.length(refDate.isLeapYear)))
        return if (thisYear.isBefore(refDate)) thisYear.plusYears(1) else thisYear
    }

    private fun resolveFutureMonthEnd(refDate: LocalDate, month: Month): LocalDate {
        val year = if (month.value < refDate.monthValue) refDate.year + 1 else refDate.year
        return YearMonth.of(year, month).atEndOfMonth()
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
