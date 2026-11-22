package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalTime

object TimeRule : ParseRule {

    private val fuzzyTimeKeywords = setOf("morning", "afternoon", "evening", "night", "tonight")

    // Day-half words that put a 1 to 11 hour typed without am/pm in the afternoon or evening.
    private val pmDayHalves = setOf("afternoon", "evening", "night", "tonight")

    internal val H_NOTATION = Regex("""\d{1,2}h\d{0,2}""")
    private val DOTTED_TIME = Regex("""(\d{1,2})\.(\d{2})""")

    /**
     * How a time was typed. Only [PLAIN] takes a day-half word's pm: a typed am/pm, a 24-hour
     * time with a leading zero ("08:30", "0830"), h-notation ("9h") and keywords ("noon") say
     * the hour outright.
     */
    private enum class Typed { PLAIN, STATED }

    /**
     * A matched time phrase: its time, how it was typed, and its first and last token. A
     * [looseEnd] is a range end typed without am/pm; it is resolved against the final start.
     */
    private class Match(
        val time: LocalTime,
        val typed: Typed,
        val first: Int,
        val last: Int,
        val looseEnd: LocalTime? = null,
    )

    override fun apply(tokens: List<Token>, context: ParseContext) {
        val match = handleQuarterHalfPattern(tokens, context)
            // "at HHMM" 24-hour time ("at 1500", "at 0930"). A 4-digit run tokenizes as YEAR or
            // NUMBER, so it is read as a time only after "at".
            ?: handleCompactAtTime(tokens, context)
            // Explicit times and precise keywords (noon) win over fuzzy ones (evening).
            ?: handleExplicitTime(tokens, context)
            ?: handleBareAtHour(tokens, context)
            ?: handleDottedAtTime(tokens, context)
            ?: handleHNotationTime(tokens, context)
        if (match == null) {
            handleFuzzyTimeKeyword(tokens, context)
            return
        }
        applyDayHalf(tokens, context, match)
        match.looseEnd?.let { context.endTime = soonestEnd(context.time ?: match.time, it) }
    }

    /**
     * Returns the reading of [end] that comes soonest after [start], past midnight if need be:
     * [end] as typed or, for 1 to 11, twelve hours later. "from 9 to 5" ends at 17:00, "tonight
     * from 9 to 1" at 01:00.
     */
    private fun soonestEnd(start: LocalTime, end: LocalTime): LocalTime {
        val readings = if (end.hour in 1..11) listOf(end, end.plusHours(12)) else listOf(end)
        fun forward(t: LocalTime): Int {
            val minutes = (t.toSecondOfDay() - start.toSecondOfDay()) / 60
            return if (minutes <= 0) minutes + 24 * 60 else minutes
        }
        return readings.minBy { forward(it) }
    }

    private fun handleCompactAtTime(tokens: List<Token>, context: ParseContext): Match? {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.YEAR && token.type != TokenType.NUMBER) continue
            // Only "at", not "this": a 4-digit run is far more often a year than a time.
            if (!isPrecededByAtKeyword(tokens, index, context)) continue

            // Parse the text: NUMBER("0930").value is 930, which lost the leading zero.
            val digits = token.text
            if (digits.length != 4 || !digits.all { it.isDigit() }) continue
            val hour = digits.substring(0, 2).toIntOrNull() ?: continue
            val minute = digits.substring(2, 4).toIntOrNull() ?: continue
            if (hour !in 0..23 || minute !in 0..59) continue

            val time = LocalTime.of(hour, minute)
            val first = setTime(tokens, context, time, listOf(index))
            return Match(time, typedHour(digits), first, index)
        }
        return null
    }

    private fun handleExplicitTime(tokens: List<Token>, context: ParseContext): Match? {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue

            when (token.type) {
                TokenType.TIME_RANGE -> {
                    val range = token.value as? WordTokenizer.TimeRange ?: continue
                    val hNotation = 'h' in token.text
                    // After "for" an h-notation range is a length, never a time of day.
                    if (hNotation && isPrecededByKeyword(tokens, index, context, "FOR")) continue
                    context.endTime = range.end
                    val timed = setTime(tokens, context, range.start, listOf(index))
                    val first = consumePrecedingFrom(tokens, timed, context)
                    // A 24-hour range ("15:00-16:30") has no am/pm, so a day-half word can move it;
                    // am/pm and h-notation ("15h30-18h") state the hour.
                    if (token.text.contains('m') || hNotation) return Match(range.start, Typed.STATED, first, index)
                    val parts = token.text.split('-')
                    val endTyped = typedHour(parts.last())
                    return Match(
                        range.start, typedHour(parts.first()), first, index,
                        looseEnd = range.end.takeIf { endTyped == Typed.PLAIN },
                    )
                }
                TokenType.TIME -> {
                    // h-notation waits for handleHNotationTime, after the bare and dotted passes.
                    if (H_NOTATION.matches(token.text)) continue
                    timeTokenAt(tokens, index, context)?.let { return it }
                }
                TokenType.TIME_KEYWORD -> {
                    if (token.text.lowercase() in fuzzyTimeKeywords) continue
                    val time = token.value as? LocalTime ?: continue
                    val first = setTime(tokens, context, time, listOf(index))
                    return Match(time, Typed.STATED, first, index)
                }
                TokenType.STRUCTURED_DATE -> {
                    if (!isDotOnly(token.text)) continue
                    val nextIdx = context.findNextUnconsumed(tokens, index + 1)
                    if (nextIdx == null || tokens[nextIdx].type != TokenType.MERIDIEM) continue
                    val dotParts = token.text.split('.')
                    if (dotParts.size != 2) continue
                    val hour = dotParts[0].toIntOrNull() ?: continue
                    val minute = dotParts[1].toIntOrNull() ?: continue
                    if (minute > 59) continue
                    val meridiem = tokens[nextIdx].text.lowercase().replace(".", "")
                    val time = resolveWithMeridiem(hour, minute, meridiem) ?: continue
                    val first = setTime(tokens, context, time, listOf(index, nextIdx))
                    return Match(time, Typed.STATED, first, nextIdx)
                }
                TokenType.NUMBER -> {
                    val hour = token.value as? Int ?: continue
                    numberRange(tokens, context, index, hour)?.let { return it }
                    val nextIndex = index + 1
                    if (nextIndex < tokens.size && !context.isConsumed(nextIndex)) {
                        val nextToken = tokens[nextIndex]

                        // "NUMBER MERIDIEM", as in "3 pm".
                        if (nextToken.type == TokenType.MERIDIEM) {
                            val meridiem = nextToken.text.lowercase().replace(".", "")
                            val time = resolveWithMeridiem(hour, 0, meridiem) ?: continue
                            val first = setTime(tokens, context, time, listOf(index, nextIndex))
                            return Match(time, Typed.STATED, first, nextIndex)
                        }

                        // "NUMBER NUMBER [MERIDIEM]", as in "2 30 pm" or "at 10 15".
                        if (nextToken.type == TokenType.NUMBER) {
                            val minute = nextToken.value as? Int ?: continue
                            if (minute > 59) continue
                            val meridiemIndex = nextIndex + 1
                            if (meridiemIndex < tokens.size && !context.isConsumed(meridiemIndex)
                                && tokens[meridiemIndex].type == TokenType.MERIDIEM
                            ) {
                                val meridiem = tokens[meridiemIndex].text.lowercase().replace(".", "")
                                val time = resolveWithMeridiem(hour, minute, meridiem) ?: continue
                                val first = setTime(tokens, context, time, listOf(index, nextIndex, meridiemIndex))
                                return Match(time, Typed.STATED, first, meridiemIndex)
                            } else if (hour in 0..23 && hasPrecedingAt(tokens, index, context)) {
                                // Without a meridiem it is 24-hour and needs "at" or "this"
                                // right before it: "at 10 15" is 10:15.
                                val time = LocalTime.of(hour, minute)
                                val first = setTime(tokens, context, time, listOf(index, nextIndex))
                                return Match(time, typedHour(token.text), first, nextIndex)
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        return null
    }

    /**
     * Reads the TIME token at [index] with a following meridiem ("3:30 PM") and a "TIME to TIME"
     * or "TIME - TIME" range ("2pm to 4pm", "15h30 - 18h"), or returns null.
     */
    private fun timeTokenAt(tokens: List<Token>, index: Int, context: ParseContext): Match? {
        val token = tokens[index]
        var time = token.value as? LocalTime ?: return null
        var typed = typedTime(token.text)
        val consumed = mutableListOf(index)
        // A separate meridiem after the time, as in "3:30 PM".
        val nextIdx = index + 1
        if (nextIdx < tokens.size && !context.isConsumed(nextIdx)) {
            val nextToken = tokens[nextIdx]
            if (nextToken.type == TokenType.MERIDIEM) {
                val meridiem = nextToken.text.lowercase().replace(".", "")
                val adjusted = resolveWithMeridiem(time.hour, time.minute, meridiem)
                if (adjusted != null) {
                    time = adjusted
                }
                typed = Typed.STATED
                // An am/pm right after a time belongs to it, not the title, so
                // it is consumed even when it can't apply ("15:00 pm").
                consumed.add(nextIdx)
            }
        }

        // "TIME to TIME" or "TIME - TIME", as in "2pm to 4pm" or "15:00 - 16:30".
        val toIdx = context.findNextUnconsumed(tokens, consumed.last() + 1)
        if (toIdx != null && isRangeSeparator(tokens[toIdx])) {
            val endTimeIdx = context.findNextUnconsumed(tokens, toIdx + 1)
            if (endTimeIdx != null && tokens[endTimeIdx].type == TokenType.TIME) {
                var endTime = tokens[endTimeIdx].value as? LocalTime
                if (endTime != null) {
                    val rangeIndices = (consumed + listOf(toIdx, endTimeIdx)).toMutableList()
                    var endTyped = typedTime(tokens[endTimeIdx].text)
                    // A separate meridiem after the end, as in "2:00 PM - 3:00 PM", belongs to it.
                    val endMeridiemIdx = endTimeIdx + 1
                    if (endMeridiemIdx < tokens.size && !context.isConsumed(endMeridiemIdx) &&
                        tokens[endMeridiemIdx].type == TokenType.MERIDIEM
                    ) {
                        val meridiem = tokens[endMeridiemIdx].text.lowercase().replace(".", "")
                        resolveWithMeridiem(endTime.hour, endTime.minute, meridiem)?.let { endTime = it }
                        endTyped = Typed.STATED
                        rangeIndices.add(endMeridiemIdx)
                        // A start without am/pm borrows the end's, as "2 - 3 pm" does, so
                        // "2:00 - 3:00 PM" is 14:00 to 15:00.
                        val end = endTime
                        if (typed == Typed.PLAIN && end != null) {
                            WordTokenizer.borrowMeridiem(time.hour, time.minute, end.hour, end.minute, meridiem)
                                ?.let { time = time.withHour(it) }
                        }
                    }
                    context.endTime = endTime
                    val timed = setTime(tokens, context, time, rangeIndices)
                    val first = consumePrecedingFrom(tokens, timed, context)
                    val looseEnd = endTime.takeIf { endTyped == Typed.PLAIN }
                    return Match(time, typed, first, rangeIndices.max(), looseEnd)
                }
            }
        }

        val first = setTime(tokens, context, time, consumed)
        return Match(time, typed, first, consumed.last())
    }

    /**
     * Reads an h-notation time ("9h", "15h30") as a time of day when the phrase has no other
     * time; with one ("at 9 2h") or after "for" ("for 2h") DurationRule reads it as a length.
     */
    private fun handleHNotationTime(tokens: List<Token>, context: ParseContext): Match? {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index) || token.type != TokenType.TIME) continue
            if (!H_NOTATION.matches(token.text) || isPrecededByKeyword(tokens, index, context, "FOR")) continue
            timeTokenAt(tokens, index, context)?.let { return it }
        }
        return null
    }

    /**
     * Reads a range that starts with a bare hour typed as digits: "3 to 5pm" (the start borrows
     * the end's am/pm, [WordTokenizer.borrowMeridiem]), "3 - 5 pm", and, after "from" or "at",
     * "from 9 to 5" (the end is resolved against the start, [soonestEnd]).
     */
    private fun numberRange(tokens: List<Token>, context: ParseContext, index: Int, hour: Int): Match? {
        if (!isTypedDigits(tokens[index]) || hour !in 0..23) return null
        val sepIdx = context.findNextUnconsumed(tokens, index + 1) ?: return null
        if (sepIdx != index + 1 || !isRangeSeparator(tokens[sepIdx])) return null
        val endIdx = context.findNextUnconsumed(tokens, sepIdx + 1) ?: return null
        if (endIdx != sepIdx + 1) return null
        val end = tokens[endIdx]

        // The end's am/pm: glued to a TIME ("5pm") or a separate token after a NUMBER ("5 pm").
        var endTime: LocalTime? = null
        var endMeridiem: String? = null
        var last = endIdx
        if (end.type == TokenType.TIME) {
            endTime = end.value as? LocalTime ?: return null
            if (end.text.contains('m')) endMeridiem = if (endTime.hour >= 12) "pm" else "am"
        } else if (end.type == TokenType.NUMBER && isTypedDigits(end)) {
            val endHour = end.value as? Int ?: return null
            val meridiemIdx = endIdx + 1
            if (meridiemIdx < tokens.size && !context.isConsumed(meridiemIdx) &&
                tokens[meridiemIdx].type == TokenType.MERIDIEM
            ) {
                endMeridiem = tokens[meridiemIdx].text.lowercase().replace(".", "")
                endTime = resolveWithMeridiem(endHour, 0, endMeridiem) ?: return null
                last = meridiemIdx
            } else {
                // Two bare hours make a range only after "from" or "at" ("from 9 to 5").
                val led = isPrecededByKeyword(tokens, index, context, "FROM") ||
                    isPrecededByKeyword(tokens, index, context, "AT")
                if (!led || endHour !in 0..23) return null
                endTime = LocalTime.of(endHour, 0)
            }
        } else {
            return null
        }

        val start: LocalTime
        val typed: Typed
        if (endMeridiem != null) {
            val startHour = WordTokenizer.borrowMeridiem(hour, 0, endTime.hour, endTime.minute, endMeridiem)
                ?: return null
            start = LocalTime.of(startHour, 0)
            typed = Typed.STATED
        } else {
            start = LocalTime.of(hour, 0)
            typed = typedHour(tokens[index].text)
        }
        context.endTime = endTime
        val indices = (index..last).toList()
        val first = consumePrecedingFrom(tokens, setTime(tokens, context, start, indices), context)
        val looseEnd = if (endMeridiem == null && typedHour(end.text) == Typed.PLAIN) endTime else null
        return Match(start, typed, first, last, looseEnd)
    }

    /** True when the token's normalized and typed texts are digits ("8", not "a" or "eight"). */
    private fun isTypedDigits(token: Token): Boolean =
        token.text.all { it.isDigit() } && token.originalText.all { it.isDigit() }

    private fun consumePrecedingFrom(tokens: List<Token>, first: Int, context: ParseContext): Int =
        consumeKeywordBefore(tokens, first, context, "FROM")

    /**
     * Reads "at H" with H from 0 to 23 typed as digits as that hour, as typed ("Dinner at 8" is
     * 08:00). A word the number normalizer turned into a digit ("at a cafe", "at a few bars") is
     * not an hour, and neither is a number followed by a unit, month, meridiem or minute. A word
     * right after the number makes it part of a place ("at 10 Downing Street", "at 21 Club",
     * "Dinner at 8 with Sam"), unless it belongs to the hour ([hourSuffix]) or is an emoji.
     */
    private fun handleBareAtHour(tokens: List<Token>, context: ParseContext): Match? {
        for ((atIndex, at) in tokens.withIndex()) {
            if (context.isConsumed(atIndex) || at.type != TokenType.KEYWORD || at.value != "AT") continue
            val hourIndex = atIndex + 1
            if (hourIndex >= tokens.size || context.isConsumed(hourIndex)) continue
            val token = tokens[hourIndex]
            if (token.type != TokenType.NUMBER) continue
            if (!isTypedDigits(token)) continue
            val hour = token.value as? Int ?: continue
            if (hour !in 0..23) continue
            val nextIdx = context.findNextUnconsumed(tokens, hourIndex + 1)
            if (nextIdx != null && tokens[nextIdx].type in NOT_AFTER_BARE_HOUR) continue
            val suffix = hourSuffix(tokens, hourIndex + 1, context)
            val wordAfter = tokens.getOrNull(hourIndex + 1)
                ?.takeIf { !context.isConsumed(hourIndex + 1) }
            if (suffix == null && wordAfter != null && wordAfter.type == TokenType.UNKNOWN &&
                wordAfter.originalText.any { it.isLetterOrDigit() }
            ) continue

            val indices = listOf(hourIndex) + suffix.orEmpty()
            val time = LocalTime.of(hour, 0)
            val first = setTime(tokens, context, time, indices)
            return Match(time, typedHour(token.text), first, indices.last())
        }
        return null
    }

    /**
     * Returns the indices of the words right after a bare hour at [from] that belong to it:
     * "o'clock" (typed with a curly apostrophe it reaches here as "o" "clock") or "sharp"; null
     * when the next word is neither.
     */
    private fun hourSuffix(tokens: List<Token>, from: Int, context: ParseContext): List<Int>? {
        fun word(index: Int) = tokens.getOrNull(index)?.takeIf { !context.isConsumed(index) }?.text
        return when {
            word(from) == "o'clock" || word(from) == "sharp" -> listOf(from)
            word(from) == "o" && word(from + 1) == "clock" -> listOf(from, from + 1)
            else -> null
        }
    }

    private val NOT_AFTER_BARE_HOUR = setOf(
        TokenType.UNIT, TokenType.MONTH, TokenType.MERIDIEM, TokenType.NUMBER,
    )

    /** Reads "at H.MM" ("at 9.30") as a time; [StructuredDateRule] leaves such a pair alone. */
    private fun handleDottedAtTime(tokens: List<Token>, context: ParseContext): Match? {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index) || token.type != TokenType.STRUCTURED_DATE) continue
            if (!isPrecededByAtKeyword(tokens, index, context)) continue
            val time = dottedTime(token.text) ?: continue
            val first = setTime(tokens, context, time, listOf(index))
            return Match(time, typedHour(token.text), first, index)
        }
        return null
    }

    /**
     * Applies a day-half word to [match]. An anchored word modifies the time and is consumed:
     * "tonight" anywhere; a word right after THIS, EVERY, a date word, a weekday or a date
     * ("tomorrow evening", "Aug 5 evening"); or after the time as "in the morning/afternoon/
     * evening" or "at night". An unanchored word before the time ("Movie night at 8") still
     * sets pm but stays in the title. An anchored word wins; among unanchored ones the nearest
     * before the time wins. "morning" never moves the hour, and an hour of 12 stays: "evening at
     * 12" is noon, not midnight.
     */
    private fun applyDayHalf(tokens: List<Token>, context: ParseContext, match: Match) {
        val candidates = tokens.indices.filter {
            !context.isConsumed(it) && tokens[it].type == TokenType.TIME_KEYWORD &&
                tokens[it].text.lowercase() in fuzzyTimeKeywords
        }
        var word: String? = null
        for (index in candidates) {
            val anchor = anchoredIndices(tokens, context, index, match) ?: continue
            word = tokens[index].text.lowercase()
            context.consume(anchor)
            if (word == "tonight") {
                context.dateKeywordDate = context.reference.toLocalDate()
                context.dateSet = true
            }
            break
        }
        if (word == null) {
            word = candidates.lastOrNull { it < match.first }?.let { tokens[it].text.lowercase() }
        }
        if (word == null || word !in pmDayHalves) return
        if (match.typed != Typed.PLAIN || match.time.hour !in 1..11) return
        context.time = match.time.plusHours(12)
    }

    /** Returns the indices an anchored day-half word at [index] consumes, or null if unanchored. */
    private fun anchoredIndices(tokens: List<Token>, context: ParseContext, index: Int, match: Match): List<Int>? {
        if (tokens[index].text.lowercase() == "tonight") return listOf(index)

        if (index > 0) {
            val prev = tokens[index - 1]
            when {
                prev.type == TokenType.KEYWORD && prev.value == "THIS" ->
                    return if (context.isConsumed(index - 1)) listOf(index) else listOf(index - 1, index)
                prev.type == TokenType.KEYWORD && prev.value == "EVERY" -> return listOf(index)
                prev.type == TokenType.DATE_KEYWORD || prev.type == TokenType.WEEKDAY -> return listOf(index)
                context.absoluteDate != null && context.isConsumed(index - 1) && prev.type in DATE_PARTS ->
                    return listOf(index)
            }
        }

        // After the time: "in the evening", "at night".
        fun keywordAt(i: Int, value: String) =
            i in tokens.indices && !context.isConsumed(i) &&
                tokens[i].type == TokenType.KEYWORD && tokens[i].value == value
        val after = match.last + 1
        if (index == after + 2 && keywordAt(after, "IN") && keywordAt(after + 1, "THE")) {
            return listOf(after, after + 1, index)
        }
        if (index == after + 1 && keywordAt(after, "AT") && tokens[index].text.lowercase() == "night") {
            return listOf(after, index)
        }
        return null
    }

    private val DATE_PARTS = setOf(TokenType.MONTH, TokenType.NUMBER, TokenType.YEAR, TokenType.STRUCTURED_DATE)

    private fun handleFuzzyTimeKeyword(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.TIME_KEYWORD) continue
            if (token.text.lowercase() !in fuzzyTimeKeywords) continue
            val time = token.value as? LocalTime ?: continue
            context.time = time
            context.timeSet = true
            context.consume(index)
            if (token.text.lowercase() == "tonight") {
                context.dateKeywordDate = context.reference.toLocalDate()
                context.dateSet = true
            }
            consumePrecedingModifier(tokens, index, context)
            return
        }
    }

    private fun handleQuarterHalfPattern(tokens: List<Token>, context: ParseContext): Match? {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.KEYWORD) continue
            val keyword = token.value as? String ?: continue
            if (keyword !in listOf("QUARTER_PAST", "HALF_PAST", "QUARTER_TO")) continue

            val hourIdx = context.findNextUnconsumed(tokens, index + 1) ?: continue
            val hourToken = tokens[hourIdx]
            if (hourToken.type != TokenType.NUMBER) continue
            var hour = hourToken.value as? Int ?: continue
            if (hour < 0 || hour > 23) continue

            val consumed = mutableListOf(index, hourIdx)
            var typed = typedHour(hourToken.text)

            val meridiemIdx = context.findNextUnconsumed(tokens, hourIdx + 1)
            if (meridiemIdx != null && tokens[meridiemIdx].type == TokenType.MERIDIEM) {
                val meridiem = tokens[meridiemIdx].text.lowercase().replace(".", "")
                val resolved = WordTokenizer.resolveMeridiemHour(hour, meridiem) ?: continue
                hour = resolved
                consumed.add(meridiemIdx)
                typed = Typed.STATED
            }

            val time = when (keyword) {
                "QUARTER_PAST" -> LocalTime.of(hour, 15)
                "HALF_PAST" -> LocalTime.of(hour, 30)
                "QUARTER_TO" -> {
                    val adjustedHour = if (hour == 0) 23 else hour - 1
                    LocalTime.of(adjustedHour, 45)
                }
                else -> continue
            }

            context.time = time
            context.timeSet = true
            context.consume(consumed)
            return Match(time, typed, index, consumed.last())
        }
        return null
    }

    /**
     * Sets [time], consumes [indices] and an "at" or "this" before the first, and returns the
     * first index of the phrase.
     */
    private fun setTime(tokens: List<Token>, context: ParseContext, time: LocalTime, indices: List<Int>): Int {
        context.time = time
        context.timeSet = true
        context.consume(indices)
        val first = indices.min()
        return if (consumePrecedingModifier(tokens, first, context)) first - 1 else first
    }

    /** Returns how a time token was typed: am/pm, h-notation or a leading zero state the hour. */
    private fun typedTime(text: String): Typed = when {
        text.contains('m') -> Typed.STATED // am, pm, a.m., p.m.
        H_NOTATION.matches(text) -> Typed.STATED
        else -> typedHour(text)
    }

    /** A 24-hour time typed with a leading zero ("08:30", "0830") states the hour. */
    private fun typedHour(text: String): Typed =
        if (text.length > 1 && text[0] == '0' && text[1].isDigit()) Typed.STATED else Typed.PLAIN

    /** Returns the time of a dotted "H.MM" pair with H 0..23 and MM 00..59, or null. */
    internal fun dottedTime(text: String): LocalTime? {
        val match = DOTTED_TIME.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /** Returns true when the token right before [currentIndex] is an unconsumed "at". */
    internal fun isPrecededByAtKeyword(tokens: List<Token>, currentIndex: Int, context: ParseContext): Boolean =
        isPrecededByKeyword(tokens, currentIndex, context, "AT")

    private fun isPrecededByKeyword(
        tokens: List<Token>,
        currentIndex: Int,
        context: ParseContext,
        value: String,
    ): Boolean {
        if (currentIndex == 0) return false
        val prev = tokens[currentIndex - 1]
        return !context.isConsumed(currentIndex - 1) &&
            prev.type == TokenType.KEYWORD && prev.value == value
    }

    private fun hasPrecedingAt(tokens: List<Token>, currentIndex: Int, context: ParseContext): Boolean {
        if (currentIndex == 0) return false
        val prev = tokens[currentIndex - 1]
        return !context.isConsumed(currentIndex - 1) && prev.type == TokenType.KEYWORD &&
            (prev.value == "AT" || prev.value == "THIS")
    }

    /** Consumes an unconsumed "at" or "this" right before [currentIndex]; true if it did. */
    private fun consumePrecedingModifier(tokens: List<Token>, currentIndex: Int, context: ParseContext): Boolean {
        if (currentIndex == 0) return false
        val prev = tokens[currentIndex - 1]
        if (!context.isConsumed(currentIndex - 1) && prev.type == TokenType.KEYWORD &&
            (prev.value == "AT" || prev.value == "THIS")) {
            context.consume(currentIndex - 1)
            return true
        }
        return false
    }

    private fun resolveWithMeridiem(hour: Int, minute: Int, meridiem: String): LocalTime? {
        val resolvedHour = WordTokenizer.resolveMeridiemHour(hour, meridiem) ?: return null
        return LocalTime.of(resolvedHour, minute)
    }
}
