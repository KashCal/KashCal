package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object RecurrenceRule : ParseRule {

    private val DAY_HALVES = setOf("morning", "afternoon", "evening", "night")

    private val ORDINAL_SUFFIX = Regex("""\d(st|nd|rd|th)$""")

    private val PLURAL_WEEKDAYS: Map<String, Set<DayOfWeek>> = mapOf(
        "mondays" to setOf(DayOfWeek.MONDAY),
        "tuesdays" to setOf(DayOfWeek.TUESDAY),
        "wednesdays" to setOf(DayOfWeek.WEDNESDAY),
        "thursdays" to setOf(DayOfWeek.THURSDAY),
        "fridays" to setOf(DayOfWeek.FRIDAY),
        "saturdays" to setOf(DayOfWeek.SATURDAY),
        "sundays" to setOf(DayOfWeek.SUNDAY),
        "weekends" to setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    )

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    // Weekday shorthands, matched against the lowercased token text: "MWF" is Monday,
    // Wednesday and Friday, "TTh" Tuesday and Thursday.
    private val weekdayShorthands = mapOf(
        "mwf" to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        "tth" to setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
    )

    // Ordinal words that tokenize as UNKNOWN. "second" is a UNIT (ChronoUnit.SECONDS) and
    // "last" a KEYWORD, so ordinalValue handles those two separately.
    private val ordinalWords = mapOf(
        "first" to 1, "third" to 3, "fourth" to 4, "fifth" to 5,
    )

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // "… of the/every/this month" must be claimed before the loop, or the EVERY + UNIT
        // branch would read "every month" as a plain FREQ=MONTHLY and drop the ordinal or
        // day of month.
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

                // "each" reads as "every". It stays an UNKNOWN token, so a trailing "each" in a
                // title ("Tickets $10 each") is never dropped as an edge keyword.
                (token.type == TokenType.KEYWORD && token.value == "EVERY") ||
                    (token.type == TokenType.UNKNOWN && token.text == "each") -> {
                    if (parseEveryPattern(tokens, index, context)) {
                        found = true
                        break
                    }
                }

                token.type == TokenType.KEYWORD && token.value == "ON" -> {
                    if (parsePluralWeekdays(tokens, index, context)) {
                        found = true
                        break
                    }
                }

                token.type == TokenType.UNKNOWN && token.text == "once" -> {
                    if (parseOncePer(tokens, index, context)) {
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
     * Claims a phrase ending in "of the month", "of every month" or "of this month", where
     * "month" is a UNIT(MONTHS) token:
     * - last day of … month: BYMONTHDAY=-1 ("last day of the month").
     * - <ordinal> WEEKDAY of … month: BYDAY=<n><day> ("first Monday of the month").
     * - <number> of … month: BYMONTHDAY=<n> ("15th of every month").
     * - "of this month" sets a single date in the current month instead of a rule, and also
     *   consumes a bare ordinal ("first of this month") without setting a date.
     *
     * Requiring the "month" unit keeps a one-off date like "15th of March" (a MONTH token)
     * for [AbsoluteDateRule].
     */
    private fun tryMonthlyOfPattern(tokens: List<Token>, context: ParseContext): Boolean {
        // An unconsumed "of", an optional the/every/this, then the "month" UNIT.
        for (ofIndex in tokens.indices) {
            if (context.isConsumed(ofIndex)) continue
            val ofToken = tokens[ofIndex]
            if (ofToken.type != TokenType.KEYWORD || ofToken.value != "OF") continue

            // "the" and "every" mean a recurring rule; "this" means one date in the current
            // month.
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

            if (cursor >= tokens.size) continue
            val monthToken = tokens[cursor]
            if (monthToken.type != TokenType.UNIT || monthToken.value != ChronoUnit.MONTHS) continue
            val monthUnitIndex = cursor
            val connectiveIndices = (ofIndex + 1 until monthUnitIndex).toList()

            // Classify what precedes "of": last day, ordinal and weekday, or a number.
            val beforeIndex = ofIndex - 1
            if (beforeIndex < 0) continue

            // "last day of … month": BYMONTHDAY=-1. Only "last" qualifies; "first day" and
            // the like aren't modeled.
            if (tokens[beforeIndex].type == TokenType.UNIT &&
                tokens[beforeIndex].value == ChronoUnit.DAYS
            ) {
                val ordinalIndex = beforeIndex - 1
                val isLast = ordinalIndex >= 0 && !context.isConsumed(ordinalIndex) &&
                    ordinalValue(tokens[ordinalIndex]) == -1
                if (isLast) {
                    // Both readings start on the reference month's last day, never before
                    // the reference; only "the" and "every" add the rule.
                    context.weekdayDate = YearMonth.from(context.reference.toLocalDate())
                        .atEndOfMonth()
                    context.dateSet = true
                    if (!thisMonth) context.rrule = RruleBuilder.monthlyLastDay()
                    context.consume(listOf(ordinalIndex, beforeIndex, ofIndex, monthUnitIndex))
                    context.consume(connectiveIndices)
                    return true
                }
                // Left unclaimed, so later rules and the title keep the tokens.
                continue
            }

            // "<ordinal> WEEKDAY of … month": BYDAY=<n><day>.
            if (tokens[beforeIndex].type == TokenType.WEEKDAY) {
                val weekday = tokens[beforeIndex].value as? DayOfWeek ?: continue
                val ordinalIndex = beforeIndex - 1
                if (ordinalIndex < 0 || context.isConsumed(ordinalIndex)) continue
                val ordinal = ordinalValue(tokens[ordinalIndex]) ?: continue
                if (thisMonth) {
                    // One date: this month's Nth weekday. A month without one (no 5th
                    // Friday) clamps to its last such weekday, so "this month" stays this
                    // month; the recurring path skips such months instead.
                    val currentMonth = YearMonth.from(context.reference.toLocalDate())
                    context.weekdayDate = ordinalWeekdayIn(currentMonth, ordinal, weekday)
                        ?: currentMonth.atDay(1).with(TemporalAdjusters.lastInMonth(weekday))
                    context.dateSet = true
                } else {
                    context.rrule = RruleBuilder.monthlyNthWeekday(ordinal, weekday)
                    // Start on the first Nth (or last) weekday on or after the reference,
                    // so DTSTART is a day the rule recurs on.
                    context.weekdayDate = firstOrdinalWeekdayOnOrAfter(
                        context.reference.toLocalDate(), ordinal, weekday
                    )
                    context.dateSet = true
                }
                context.consume(listOf(ordinalIndex, beforeIndex, ofIndex, monthUnitIndex))
                context.consume(connectiveIndices)
                return true
            }

            // "<number> of … month": BYMONTHDAY=<n>.
            if (tokens[beforeIndex].type == TokenType.NUMBER) {
                val dayOfMonth = tokens[beforeIndex].value as? Int ?: continue
                val validDay = dayOfMonth in 1..31
                if (thisMonth) {
                    // One date in the current month: a day from 1 to 31 clamps to the
                    // month (the 31st in February is Feb 28); any other day sets no date.
                    // The phrase is consumed either way, so it never leaks into the title.
                    if (validDay) {
                        val currentMonth = YearMonth.from(context.reference.toLocalDate())
                        val day = minOf(dayOfMonth, currentMonth.lengthOfMonth())
                        context.weekdayDate = currentMonth.atDay(day)
                        context.dateSet = true
                    }
                } else {
                    // A day outside 1..31 makes no rule; the phrase stays unclaimed.
                    if (!validDay) continue
                    context.rrule = RruleBuilder.monthly(dayOfMonth = dayOfMonth)
                    // Start on the first month on or after the reference that has this day,
                    // skipping short months (day 31 skips Feb and Apr) instead of clamping.
                    context.weekdayDate = firstDayOfMonthOnOrAfter(
                        context.reference.toLocalDate(), dayOfMonth
                    )
                    context.dateSet = true
                }
                context.consume(listOf(beforeIndex, ofIndex, monthUnitIndex))
                context.consume(connectiveIndices)
                return true
            }

            // "<ordinal> of this month" without a weekday ("first of this month") is
            // ambiguous (day or weekday), so no date is set, but the phrase is consumed so it
            // doesn't leak into the title. Only a recognized ordinal counts: "best of this
            // month" stays title text.
            if (thisMonth && ordinalValue(tokens[beforeIndex]) != null) {
                context.consume(listOf(beforeIndex, ofIndex, monthUnitIndex))
                context.consume(connectiveIndices)
                return true
            }
        }
        return false
    }

    /**
     * Returns an ordinal token's value, 1 to 5 or -1 for "last", or null if it isn't one.
     * [ordinalWords] notes which token type each ordinal word has.
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
     * Parses "on" + plural weekdays joined by "and" or adjacent ("on mondays and wednesdays", "on
     * weekends") as a weekly rule starting on the earliest upcoming one. Only the full plural
     * names count, so "suns" or "sats" stay title words.
     */
    private fun parsePluralWeekdays(tokens: List<Token>, onIndex: Int, context: ParseContext): Boolean {
        val (days, indices) = collectDays(tokens, onIndex + 1, context) { token ->
            PLURAL_WEEKDAYS[token.text].takeIf { token.type == TokenType.UNKNOWN }
        }
        if (days.isEmpty()) return false
        setWeekly(context, days, listOf(onIndex) + indices)
        return true
    }

    /**
     * Collects the days named from [start] on, joined by "and" or adjacent (normalizing turns
     * commas into spaces); [dayOf] reads one token. An "and" counts only between two day words.
     */
    private fun collectDays(
        tokens: List<Token>,
        start: Int,
        context: ParseContext,
        dayOf: (Token) -> Set<DayOfWeek>?,
    ): Pair<Set<DayOfWeek>, List<Int>> {
        val days = linkedSetOf<DayOfWeek>()
        val indices = mutableListOf<Int>()
        var scan = start
        while (scan < tokens.size && !context.isConsumed(scan)) {
            val token = tokens[scan]
            val named = dayOf(token)
            if (named != null) {
                days.addAll(named)
                indices.add(scan)
                scan++
                continue
            }
            val after = scan + 1
            if (days.isNotEmpty() && token.type == TokenType.UNKNOWN && token.text == "and" &&
                after < tokens.size && !context.isConsumed(after) && dayOf(tokens[after]) != null
            ) {
                indices.add(scan)
                scan++
                continue
            }
            break
        }
        return days to indices
    }

    /**
     * Sets a weekly rule on [days] starting on the earliest upcoming one (RFC 5545 leaves a
     * DTSTART outside the BYDAY set undefined) and consumes [indices].
     */
    private fun setWeekly(context: ParseContext, days: Set<DayOfWeek>, indices: List<Int>) {
        context.rrule = RruleBuilder.weekly(days = days)
        val refDate = context.reference.toLocalDate()
        context.weekdayDate = days.minOf { resolveBareWeekday(refDate, it) }
        context.dateSet = true
        context.consume(indices)
    }

    /** Parses "once a day/week/month/year" (the number normalizer makes "a" a 1). */
    private fun parseOncePer(tokens: List<Token>, onceIndex: Int, context: ParseContext): Boolean {
        val countIndex = onceIndex + 1
        val unitIndex = onceIndex + 2
        if (unitIndex >= tokens.size || context.isConsumed(countIndex) || context.isConsumed(unitIndex)) return false
        val count = tokens[countIndex]
        if (count.type != TokenType.NUMBER || count.value != 1) return false
        val unit = tokens[unitIndex].takeIf { it.type == TokenType.UNIT }?.value as? ChronoUnit ?: return false
        context.rrule = unitToRrule(unit, 1) ?: return false
        context.consume(listOf(onceIndex, countIndex, unitIndex))
        return true
    }

    /**
     * Parses a phrase starting with "every":
     * - a day-half word: daily ("every morning", "every evening").
     * - "other" + UNIT or WEEKDAY: INTERVAL=2 ("every other week", "every other Friday").
     * - WEEKDAY, with more joined by "and" or adjacent: weekly with BYDAY.
     * - "weekday" or "weekdays": weekly Monday to Friday; "weekend": weekly Saturday and Sunday.
     * - UNIT: that frequency ("every day").
     * - NUMBER + UNIT [+ "on" WEEKDAY]: that interval; the weekday adds BYDAY and WKST only
     *   for weeks ("every 2 weeks on Friday").
     * - an ordinal day and a month: yearly on that date ("every 25th december").
     * - an ordinal day with no unit, weekday or second ordinal after it: monthly on that day
     *   ("every 1st"), starting on the first month on or after the reference that has it.
     */
    private fun parseEveryPattern(tokens: List<Token>, everyIndex: Int, context: ParseContext): Boolean {
        val nextIndex = everyIndex + 1
        if (nextIndex >= tokens.size) return false
        val next = tokens[nextIndex]
        if (context.isConsumed(nextIndex)) return false

        // "every morning", "every evening": daily. Only "every" is consumed, so TimeRule still
        // reads the day-half word as the default time or as pm for an hour ("every evening at 6").
        if (next.type == TokenType.TIME_KEYWORD && next.text.lowercase() in DAY_HALVES) {
            context.rrule = RruleBuilder.daily()
            context.consume(everyIndex)
            return true
        }

        // "each other" is a phrase ("get to know each other friday"), not "every other".
        if (tokens[everyIndex].text == "each" && next.text == "other") return false

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

        if (next.type == TokenType.WEEKDAY) {
            val (days, indices) = collectDays(tokens, nextIndex, context) { token ->
                (token.value as? DayOfWeek)?.takeIf { token.type == TokenType.WEEKDAY }?.let(::setOf)
            }
            if (days.isEmpty()) return false
            setWeekly(context, days, listOf(everyIndex) + indices)
            return true
        }

        if (next.type == TokenType.UNKNOWN && next.text.lowercase() in listOf("weekday", "weekdays")) {
            context.rrule = RruleBuilder.weekly(days = WEEKDAYS)
            context.consume(everyIndex)
            context.consume(nextIndex)
            return true
        }

        if (next.type == TokenType.DATE_KEYWORD && next.value == "weekend") {
            context.rrule = RruleBuilder.weekly(days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            context.consume(everyIndex)
            context.consume(nextIndex)
            return true
        }

        if (next.type == TokenType.UNIT) {
            val rrule = unitToRrule(next.value as? ChronoUnit ?: return false, 1) ?: return false
            context.rrule = rrule
            context.consume(everyIndex)
            context.consume(nextIndex)
            return true
        }

        if (next.type == TokenType.NUMBER && ORDINAL_SUFFIX.containsMatchIn(next.text)) {
            val after = tokens.getOrNull(nextIndex + 1)
            val day = next.value as? Int
            // "every 25th december", "every 1st of March": yearly on that date. Only "every" is
            // consumed; AbsoluteDateRule reads the date and starts the series on it.
            val monthAfter = after?.type == TokenType.MONTH ||
                (after?.type == TokenType.KEYWORD && after.value == "OF" &&
                    tokens.getOrNull(nextIndex + 2)?.type == TokenType.MONTH)
            if (day != null && day in 1..31 && monthAfter) {
                context.rrule = RruleBuilder.yearly()
                context.consume(everyIndex)
                return true
            }
            // "every 1st and 15th" names two days; one BYMONTHDAY would drop the second.
            val andOrdinal = after?.type == TokenType.UNKNOWN && after.text == "and" &&
                tokens.getOrNull(nextIndex + 2)?.let { ORDINAL_SUFFIX.containsMatchIn(it.text) } == true
            if (day != null && day in 1..31 && !andOrdinal &&
                (after == null || (after.type != TokenType.UNIT && after.type != TokenType.WEEKDAY))
            ) {
                context.rrule = RruleBuilder.monthly(dayOfMonth = day)
                context.weekdayDate = firstDayOfMonthOnOrAfter(context.reference.toLocalDate(), day)
                context.dateSet = true
                context.consume(listOf(everyIndex, nextIndex))
                return true
            }
        }

        if (next.type == TokenType.NUMBER) {
            val interval = next.value as? Int ?: return false
            val unitIndex = nextIndex + 1
            if (unitIndex >= tokens.size) return false
            val unitToken = tokens[unitIndex]
            if (context.isConsumed(unitIndex)) return false
            if (unitToken.type != TokenType.UNIT) return false

            val rrule = unitToRrule(unitToken.value as? ChronoUnit ?: return false, interval) ?: return false
            val consumed = mutableListOf(everyIndex, nextIndex, unitIndex)

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
                                // Only a weekly rule takes the weekday as BYDAY.
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

        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.KEYWORD) continue
            val value = token.value as? String ?: continue

            when (value) {
                "UNTIL" -> {
                    val end = parseUntilEnd(tokens, index, context) ?: continue
                    context.untilDate = end.first
                    context.consume(end.second)
                    return
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
                    // "for 5 times" also consumes the "for".
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

    /**
     * Reads the end date after the UNTIL at [untilIndex] and returns it with the indices to
     * consume, or null when no end date follows. Forms: a month with an optional day and year
     * ("until June 15", "until June 15 2027", "until June" for its last day), and "(the) end of
     * (the|this) week|month|year|<month>", a month optionally with a year. Without a year the date
     * is the next one on or after today. The week ends on the day before the first day of the week
     * by [ParseContext.firstDayOfWeek].
     */
    private fun parseUntilEnd(
        tokens: List<Token>,
        untilIndex: Int,
        context: ParseContext,
    ): Pair<LocalDate, List<Int>>? {
        val refDate = context.reference.toLocalDate()
        val consumed = mutableListOf(untilIndex)
        var cursor = context.findNextUnconsumed(tokens, untilIndex + 1) ?: return null
        val first = tokens[cursor]

        if (first.type == TokenType.MONTH) {
            val month = first.value as? Month ?: return null
            consumed.add(cursor)
            val dayIdx = context.findNextUnconsumed(tokens, cursor + 1)
            // Only 1 to 31 is a day; "until june 0", typed on the way to "june 05", is the month.
            val day = dayIdx?.let { tokens[it] }?.takeIf { it.type == TokenType.NUMBER }
                ?.let { it.value as? Int }?.takeIf { it in 1..31 }
            if (day != null) consumed.add(dayIdx)
            val year = yearAfter(tokens, consumed.max(), context)?.also { consumed.add(it.second) }?.first
            val date = when {
                year != null && day != null -> dayInMonth(year, month, day)
                year != null -> YearMonth.of(year, month).atEndOfMonth()
                day != null -> resolveFutureDate(refDate, month, day)
                else -> resolveFutureMonthEnd(refDate, month)
            }
            return date to consumed
        }

        // [the] end of [the|this] <period>
        fun isKeyword(index: Int?, vararg values: String) =
            index != null && tokens[index].type == TokenType.KEYWORD && tokens[index].value in values
        if (isKeyword(cursor, "THE")) {
            consumed.add(cursor)
            cursor = context.findNextUnconsumed(tokens, cursor + 1) ?: return null
        }
        if (tokens[cursor].type != TokenType.UNKNOWN || tokens[cursor].text != "end") return null
        consumed.add(cursor)
        cursor = context.findNextUnconsumed(tokens, cursor + 1) ?: return null
        if (!isKeyword(cursor, "OF")) return null
        consumed.add(cursor)
        cursor = context.findNextUnconsumed(tokens, cursor + 1) ?: return null
        if (isKeyword(cursor, "THE", "THIS")) {
            consumed.add(cursor)
            cursor = context.findNextUnconsumed(tokens, cursor + 1) ?: return null
        }
        val period = tokens[cursor]
        val date = when {
            period.type == TokenType.MONTH -> {
                val month = period.value as? Month ?: return null
                val year = yearAfter(tokens, cursor, context)?.also { consumed.add(it.second) }?.first
                if (year != null) YearMonth.of(year, month).atEndOfMonth() else resolveFutureMonthEnd(refDate, month)
            }
            period.type == TokenType.UNIT -> when (period.value) {
                ChronoUnit.WEEKS -> {
                    weekStart(refDate, context.firstDayOfWeek).plusDays(6)
                }
                ChronoUnit.MONTHS -> YearMonth.from(refDate).atEndOfMonth()
                ChronoUnit.YEARS -> LocalDate.of(refDate.year, 12, 31)
                else -> return null
            }
            else -> return null
        }
        consumed.add(cursor)
        return date to consumed
    }

    /** Returns the YEAR token right after [index] (the next unconsumed token) and its index. */
    private fun yearAfter(tokens: List<Token>, index: Int, context: ParseContext): Pair<Int, Int>? {
        val next = context.findNextUnconsumed(tokens, index + 1) ?: return null
        val token = tokens[next].takeIf { it.type == TokenType.YEAR } ?: return null
        return (token.value as? Int)?.let { it to next }
    }

    /** Returns [day] of [month] in [year], clamped to that month's length in that year. */
    private fun dayInMonth(year: Int, month: Month, day: Int): LocalDate =
        LocalDate.of(year, month, day.coerceIn(1, YearMonth.of(year, month).lengthOfMonth()))

    private fun resolveFutureDate(refDate: LocalDate, month: Month, day: Int): LocalDate {
        val thisYear = LocalDate.of(refDate.year, month, day.coerceIn(1, month.length(refDate.isLeapYear)))
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
     * Returns the first [ordinal] [weekday] of a month on or after [refDate], or [refDate] if
     * none is found within [MAX_MONTH_SCAN] months. [ordinal] is 1 to 5, or -1 for "last". A
     * month without that ordinal (no 5th Friday) is skipped.
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
     * Returns the [ordinal]-th [weekday] in [ym], or null when the month has none (only for
     * ordinal 5). `dayOfWeekInMonth` spills into the next month when the count is too high,
     * so a candidate outside [ym] is rejected.
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
     * Returns the first [dayOfMonth] of a month on or after [refDate], or [refDate] if none is
     * found within [MAX_MONTH_SCAN] months. A month too short for it (day 31 in April) is
     * skipped, not clamped.
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

    // Bound on the forward month scan. A day of month or ordinal weekday always occurs within
    // 12 months; the rest is headroom.
    private const val MAX_MONTH_SCAN = 24
}
