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
import java.time.temporal.TemporalAdjusters

object RecurrenceRule : ParseRule {

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    // Compact weekday shorthands: "MWF" → Mon/Wed/Fri, "TTh" → Tue/Thu.
    // Matched case-insensitively against the lowercased normalized token text.
    private val weekdayShorthands = mapOf(
        "mwf" to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        "tth" to setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
    )

    // Ordinal words that stay UNKNOWN after tokenization. Note "second" is a UNIT
    // (ChronoUnit.SECONDS) and "last" is a KEYWORD, so they're handled separately
    // in ordinalValue(); only these four fall through to UNKNOWN.
    private val ordinalWords = mapOf(
        "first" to 1, "third" to 3, "fourth" to 4, "fifth" to 5,
    )

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // Monthly "… of (the|every) month" patterns must be claimed before the main
        // loop, or the generic EVERY + UNIT branch would grab a bare "month" as a
        // plain FREQ=MONTHLY and drop the ordinal/day-of-month detail.
        var found = tryMonthlyOfPattern(tokens, context)

        if (!found) for ((index, token) in tokens.withIndex()) {
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

                token.type == TokenType.UNKNOWN && weekdayShorthands.containsKey(token.text.lowercase()) -> {
                    val days = weekdayShorthands.getValue(token.text.lowercase())
                    context.rrule = RruleBuilder.weekly(days = days)
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
     * Monthly recurrence anchored by a trailing "of (the|every) month" phrase where
     * "month" is a UNIT(MONTHS) token:
     * - <ordinal> WEEKDAY of (the|every) month → BYDAY=<n><day>  ("first Monday of the month")
     * - <ordinal-number> of (the|every) month → BYMONTHDAY=<n>   ("15th of every month")
     *
     * The trailing "month" anchor is required so we never steal a one-off date like
     * "15th of March" (where "March" is a MONTH token, not the "month" UNIT).
     */
    private fun tryMonthlyOfPattern(tokens: List<Token>, context: ParseContext): Boolean {
        // Locate an unconsumed "of" (KEYWORD OF) immediately followed by an optional
        // (the|every) and then the "month" UNIT.
        for (ofIndex in tokens.indices) {
            if (context.isConsumed(ofIndex)) continue
            val ofToken = tokens[ofIndex]
            if (ofToken.type != TokenType.KEYWORD || ofToken.value != "OF") continue

            // Optional connective before "month": "the"/"every" mean a recurring
            // rule; "this" means a single occurrence in the CURRENT month (not a
            // recurrence). Capture which so the cases below can branch on it.
            var cursor = ofIndex + 1
            var thisMonth = false
            if (cursor < tokens.size) {
                val connective = tokens[cursor]
                if (connective.type == TokenType.KEYWORD &&
                    (connective.value == "THE" || connective.value == "EVERY" || connective.value == "THIS")
                ) {
                    if (connective.value == "THIS") thisMonth = true
                    cursor++
                }
            }

            // Must land on the "month" UNIT (ChronoUnit.MONTHS)
            if (cursor >= tokens.size) continue
            val monthToken = tokens[cursor]
            if (monthToken.type != TokenType.UNIT || monthToken.value != ChronoUnit.MONTHS) continue
            val monthUnitIndex = cursor
            val connectiveIndices = (ofIndex + 1 until monthUnitIndex).toList()

            // Now classify what precedes "of": last-day, ordinal[+weekday], or
            // ordinal-number.
            val beforeIndex = ofIndex - 1
            if (beforeIndex < 0) continue

            // Case D: LAST "day" of ... month → BYMONTHDAY=-1 ("last day of the month").
            // "day" is a UNIT(DAYS) token preceded by the LAST keyword. Only "last"
            // qualifies; "first day"/"second day" carry a different ordinal and fall
            // through (there is no BYMONTHDAY for a non-last ordinal day-of-month).
            if (tokens[beforeIndex].type == TokenType.UNIT &&
                tokens[beforeIndex].value == ChronoUnit.DAYS
            ) {
                val ordinalIndex = beforeIndex - 1
                val isLast = ordinalIndex >= 0 && !context.isConsumed(ordinalIndex) &&
                    ordinalValue(tokens[ordinalIndex]) == -1
                if (isLast) {
                    // Both readings anchor on the reference month's last day (always
                    // on/after the reference). "of this month" is a single occurrence;
                    // "of the/every month" also carries the recurring BYMONTHDAY=-1.
                    context.weekdayDate = YearMonth.from(context.reference.toLocalDate())
                        .atEndOfMonth()
                    context.dateSet = true
                    if (!thisMonth) context.rrule = RruleBuilder.monthlyLastDay()
                    context.consume(listOf(ordinalIndex, beforeIndex, ofIndex, monthUnitIndex))
                    context.consume(connectiveIndices)
                    return true
                }
                // A non-last "<ordinal> day of … month" isn't a rule we model; leave
                // it unclaimed so downstream rules / the title keep the tokens.
                continue
            }

            // Case A: <ordinal> WEEKDAY of ... month → BYDAY=<n><day>
            if (tokens[beforeIndex].type == TokenType.WEEKDAY) {
                val weekday = tokens[beforeIndex].value as? DayOfWeek ?: continue
                val ordinalIndex = beforeIndex - 1
                if (ordinalIndex < 0 || context.isConsumed(ordinalIndex)) continue
                val ordinal = ordinalValue(tokens[ordinalIndex]) ?: continue
                if (thisMonth) {
                    // "of this month": a single occurrence in the current month, not
                    // a recurrence. Anchor to that month's Nth weekday; if the month
                    // has no such occurrence (e.g. no 5th Friday), clamp to the last
                    // occurrence of that weekday so the date stays IN the current
                    // month rather than rolling forward — "this month" must mean this
                    // month. (The recurring path skips such months; a one-off can't.)
                    val currentMonth = YearMonth.from(context.reference.toLocalDate())
                    context.weekdayDate = ordinalWeekdayIn(currentMonth, ordinal, weekday)
                        ?: currentMonth.atDay(1).with(TemporalAdjusters.lastInMonth(weekday))
                    context.dateSet = true
                } else {
                    context.rrule = RruleBuilder.monthlyNthWeekday(ordinal, weekday)
                    // Anchor DTSTART on the first month whose Nth (or last) weekday falls
                    // on/after the reference date, so the start lands on a day the rule
                    // actually recurs on rather than defaulting to the reference itself.
                    context.weekdayDate = firstOrdinalWeekdayOnOrAfter(
                        context.reference.toLocalDate(), ordinal, weekday
                    )
                    context.dateSet = true
                }
                context.consume(listOf(ordinalIndex, beforeIndex, ofIndex, monthUnitIndex))
                context.consume(connectiveIndices)
                return true
            }

            // Case B: <ordinal-number> of ... month → BYMONTHDAY=<n>
            if (tokens[beforeIndex].type == TokenType.NUMBER) {
                val dayOfMonth = tokens[beforeIndex].value as? Int ?: continue
                val validDay = dayOfMonth in 1..31
                if (thisMonth) {
                    // "the Nth of this month": a single occurrence in the current
                    // month. A valid day clamps to the month (e.g. the 31st in
                    // February → Feb 28) so the date stays in the current month
                    // rather than rolling forward. An out-of-range day (0th, 32nd)
                    // sets no date. Either way the phrase is consumed below so it
                    // never leaks into the title.
                    if (validDay) {
                        val currentMonth = YearMonth.from(context.reference.toLocalDate())
                        val day = minOf(dayOfMonth, currentMonth.lengthOfMonth())
                        context.weekdayDate = currentMonth.atDay(day)
                        context.dateSet = true
                    }
                } else {
                    // Recurring "the Nth of every month": an out-of-range day isn't a
                    // valid rule, so leave the phrase unclaimed (unchanged behavior).
                    if (!validDay) continue
                    context.rrule = RruleBuilder.monthly(dayOfMonth = dayOfMonth)
                    // Anchor DTSTART on the first month (on/after the reference) that
                    // actually has this day-of-month, skipping short months rather than
                    // clamping (e.g. day 31 skips Feb/Apr; day 30 skips Feb).
                    context.weekdayDate = firstDayOfMonthOnOrAfter(
                        context.reference.toLocalDate(), dayOfMonth
                    )
                    context.dateSet = true
                }
                context.consume(listOf(beforeIndex, ofIndex, monthUnitIndex))
                context.consume(connectiveIndices)
                return true
            }

            // Case C (this-month only): a recognized ordinal word with no weekday,
            // e.g. "first of this month". It's ambiguous (1st day vs 1st weekday),
            // so we decline to guess a date — but still consume the phrase so it
            // doesn't leak into the title. Only recognized ordinals are consumed;
            // an unrelated word like "best of this month" is left as title text.
            if (thisMonth && ordinalValue(tokens[beforeIndex]) != null) {
                context.consume(listOf(beforeIndex, ofIndex, monthUnitIndex))
                context.consume(connectiveIndices)
                return true
            }
        }
        return false
    }

    /**
     * Map an ordinal token to its numeric value for BYDAY (1-5, or -1 for "last").
     * "second" is a UNIT(SECONDS) token and "last" is a KEYWORD; the remaining
     * ordinals ("first", "third", "fourth", "fifth") stay UNKNOWN.
     */
    private fun ordinalValue(token: Token): Int? {
        return when {
            token.type == TokenType.KEYWORD && token.value == "LAST" -> -1
            token.type == TokenType.UNIT && token.value == ChronoUnit.SECONDS -> 2 // "second"
            token.type == TokenType.NUMBER -> (token.value as? Int)?.takeIf { it in 1..5 }
            token.type == TokenType.UNKNOWN -> ordinalWords[token.text.lowercase()]
            else -> null
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

        // EVERY + "other" + (UNIT | WEEKDAY) → "every other week", "every other Friday" (INTERVAL=2)
        if (next.type == TokenType.UNKNOWN && next.text.lowercase() == "other") {
            val targetIndex = nextIndex + 1
            if (targetIndex < tokens.size && !context.isConsumed(targetIndex)) {
                val target = tokens[targetIndex]
                when (target.type) {
                    TokenType.UNIT -> {
                        val rrule = unitToRrule(target.value as? ChronoUnit ?: return false, 2)
                            ?: return false
                        context.rrule = rrule
                        context.consume(listOf(everyIndex, nextIndex, targetIndex))
                        return true
                    }
                    TokenType.WEEKDAY -> {
                        val day = target.value as? DayOfWeek ?: return false
                        context.rrule = RruleBuilder.weekly(interval = 2, days = setOf(day))
                        context.weekdayDate = resolveBareWeekday(context.reference.toLocalDate(), day)
                        context.dateSet = true
                        context.consume(listOf(everyIndex, nextIndex, targetIndex))
                        return true
                    }
                    else -> {}
                }
            }
        }

        // EVERY + WEEKDAY [and/, WEEKDAY]* → "every Monday", "every Monday and Wednesday"
        if (next.type == TokenType.WEEKDAY) {
            val firstDay = next.value as? DayOfWeek ?: return false
            val days = linkedSetOf(firstDay)
            val consumed = mutableListOf(everyIndex, nextIndex)

            // Greedily collect further weekdays joined by "and" or bare adjacency
            // (commas normalize to spaces upstream). Skip a connecting "and".
            var scan = nextIndex + 1
            while (scan < tokens.size) {
                if (context.isConsumed(scan)) break
                val tok = tokens[scan]
                if (tok.type == TokenType.UNKNOWN && tok.text.lowercase() == "and") {
                    // A trailing "and" only counts if a weekday actually follows.
                    val after = scan + 1
                    if (after < tokens.size && !context.isConsumed(after) &&
                        tokens[after].type == TokenType.WEEKDAY
                    ) {
                        consumed.add(scan)
                        scan++
                        continue
                    }
                    break
                }
                if (tok.type == TokenType.WEEKDAY) {
                    val day = tok.value as? DayOfWeek ?: break
                    days.add(day)
                    consumed.add(scan)
                    scan++
                    continue
                }
                break
            }

            context.rrule = RruleBuilder.weekly(days = days)
            // Anchor the first occurrence on the earliest upcoming selected weekday
            // so DTSTART lands on a day the rule actually recurs on (RFC 5545 leaves
            // a DTSTART that doesn't match the BYDAY set undefined).
            val refDate = context.reference.toLocalDate()
            context.weekdayDate = days.minOf { resolveBareWeekday(refDate, it) }
            context.dateSet = true
            context.consume(consumed)
            return true
        }

        // EVERY + "weekday"/"weekdays" → "every weekday" (MO-FR)
        if (next.type == TokenType.UNKNOWN && next.text.lowercase() in listOf("weekday", "weekdays")) {
            context.rrule = RruleBuilder.weekly(days = WEEKDAYS)
            context.consume(everyIndex)
            context.consume(nextIndex)
            return true
        }

        // EVERY + "weekend" → weekly on Saturday + Sunday
        if (next.type == TokenType.DATE_KEYWORD && next.value == "weekend") {
            context.rrule = RruleBuilder.weekly(days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
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
     * First date matching "<ordinal> <weekday> of the month" that falls on or after
     * [refDate], scanning forward month by month. [ordinal] is 1-5 for 1st-5th or -1
     * for "last". Months that lack the requested ordinal (e.g. a 5th Friday) are
     * skipped rather than allowed to spill into the next month.
     */
    private fun firstOrdinalWeekdayOnOrAfter(
        refDate: LocalDate,
        ordinal: Int,
        weekday: DayOfWeek,
    ): LocalDate {
        var ym = YearMonth.from(refDate)
        repeat(MAX_MONTH_SCAN) {
            val candidate = ordinalWeekdayIn(ym, ordinal, weekday)
            if (candidate != null && !candidate.isBefore(refDate)) return candidate
            ym = ym.plusMonths(1)
        }
        return refDate
    }

    /**
     * The [ordinal]-th [weekday] within [ym], or null when the month has no such
     * occurrence (only happens for ordinal 5). `dayOfWeekInMonth` spills into a later
     * month when the count is too high, so we reject any candidate that left [ym].
     */
    private fun ordinalWeekdayIn(ym: YearMonth, ordinal: Int, weekday: DayOfWeek): LocalDate? {
        val anchor = ym.atDay(1)
        val candidate = if (ordinal == -1) {
            anchor.with(TemporalAdjusters.lastInMonth(weekday))
        } else {
            anchor.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday))
        }
        return if (YearMonth.from(candidate) == ym) candidate else null
    }

    /**
     * First date matching "<n>th of the month" that falls on or after [refDate],
     * scanning forward month by month. Months too short for [dayOfMonth] (e.g. day 31
     * in April, day 30 in February) are skipped rather than clamped.
     */
    private fun firstDayOfMonthOnOrAfter(refDate: LocalDate, dayOfMonth: Int): LocalDate {
        var ym = YearMonth.from(refDate)
        repeat(MAX_MONTH_SCAN) {
            if (dayOfMonth <= ym.lengthOfMonth()) {
                val candidate = ym.atDay(dayOfMonth)
                if (!candidate.isBefore(refDate)) return candidate
            }
            ym = ym.plusMonths(1)
        }
        return refDate
    }

    // Upper bound on the forward month scan. A day-of-month/ordinal-weekday always
    // recurs within a 12-month window; the extra headroom is a cheap safety net.
    private const val MAX_MONTH_SCAN = 24
}
