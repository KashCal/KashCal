package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Reads an event length and sets the end time from the start.
 *
 * After "for" the length is a chain of parts, each a smaller unit than the one before, with an
 * optional "and" between: "for 90 minutes", "for 2.5 hours", "for 1h 30m", "for 2 hours and 30
 * minutes", "for an hour and a half", "for 2 and a half hours". Without "for", only a glued length
 * that names hours or uses "min" ("2h45m", "1.5h", "90min"), or 24-hour notation TimeRule left
 * because another time was set ("at 9 2h"), counts, and only when a start time exists: a bare
 * "400m" may be metres.
 */
object DurationRule : ParseRule {

    private const val HOURS_RANK = 2
    private const val MINUTES_RANK = 1

    /** One part of a length: its minutes, unit rank and tokens, and whether it took back a date. */
    private class Part(val minutes: Long, val rank: Int, val indices: List<Int>, val tookDate: Boolean = false)

    private class Length(val minutes: Long, val indices: List<Int>, val tookDate: Boolean)

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((i, token) in tokens.withIndex()) {
            if (context.isConsumed(i)) continue
            if (token.type != TokenType.KEYWORD || token.value != "FOR") continue
            val length = readLength(tokens, i + 1, context, bareMinutesFirst = true) ?: continue
            setLength(context, length, listOf(i))
            return
        }

        if (context.resolveTime() == null) return
        for ((i, token) in tokens.withIndex()) {
            if (context.isConsumed(i)) continue
            val bare = when (token.type) {
                TokenType.DURATION -> (token.value as? WordTokenizer.GluedDuration)
                    ?.let { it.hasHourPart || it.minuteWord } == true
                TokenType.TIME -> TimeRule.H_NOTATION.matches(token.text)
                else -> false
            }
            if (!bare) continue
            val length = readLength(tokens, i, context, bareMinutesFirst = false) ?: continue
            setLength(context, length, emptyList())
            return
        }
    }

    private fun setLength(context: ParseContext, length: Length, extra: List<Int>) {
        val startTime = context.resolveTime() ?: context.reference.toLocalTime()
        context.endTime = startTime.plusMinutes(length.minutes)

        // Taking back a decimal StructuredDateRule read as a date clears that date.
        if (length.tookDate) {
            context.absoluteDate = null
            context.dateSet = false
        }
        context.consume(extra)
        context.consume(length.indices)
    }

    /**
     * Reads the chain of parts starting at [start], or null when no part starts there.
     * [bareMinutesFirst] lets a bare "45m" start the chain (after "for"); a later part may always
     * be one ("at 9 2h 45m").
     */
    private fun readLength(tokens: List<Token>, start: Int, context: ParseContext, bareMinutesFirst: Boolean): Length? {
        val first = readPart(tokens, start, context, bareMinutesFirst, previousRank = Int.MAX_VALUE) ?: return null
        var total = first.minutes
        val indices = first.indices.toMutableList()
        var tookDate = first.tookDate
        var rank = first.rank
        var next = indices.max() + 1
        while (next < tokens.size) {
            val and = isAnd(tokens, next, context)
            val at = if (and) next + 1 else next
            val part = readPart(tokens, at, context, bareMinutes = true, previousRank = rank) ?: break
            if (and) indices.add(next)
            indices.addAll(part.indices)
            total += part.minutes
            tookDate = tookDate || part.tookDate
            rank = part.rank
            next = indices.max() + 1
        }
        return Length(total, indices, tookDate)
    }

    private fun readPart(
        tokens: List<Token>,
        index: Int,
        context: ParseContext,
        bareMinutes: Boolean,
        previousRank: Int,
    ): Part? {
        if (index >= tokens.size) return null
        val token = tokens[index]
        val free = !context.isConsumed(index)

        val part = when {
            // "and a half" after an hour part: +30 minutes.
            previousRank == HOURS_RANK && free && token.type == TokenType.NUMBER && token.value == 1 &&
                isWord(tokens, index + 1, context, "half") ->
                Part(30, MINUTES_RANK, listOf(index, index + 1))

            token.type == TokenType.DURATION && free -> {
                val glued = token.value as? WordTokenizer.GluedDuration ?: return null
                // A bare "45m" may be metres, so it can't start a length without "for".
                if (!bareMinutes && !glued.hasHourPart && !glued.minuteWord) return null
                Part(glued.minutes.toLong(), if (glued.hasHourPart) HOURS_RANK else MINUTES_RANK, listOf(index))
            }

            token.type == TokenType.TIME && free && TimeRule.H_NOTATION.matches(token.text) -> {
                val time = token.value as? LocalTime ?: return null
                Part(time.hour * 60L + time.minute, HOURS_RANK, listOf(index))
            }

            // "2 and a half hours"
            token.type == TokenType.NUMBER && free && isAnd(tokens, index + 1, context) &&
                tokens.getOrNull(index + 2)?.let { it.type == TokenType.NUMBER && it.value == 1 } == true &&
                isWord(tokens, index + 3, context, "half") &&
                unitAt(tokens, index + 4, context) == ChronoUnit.HOURS -> {
                val amount = token.value as? Int ?: return null
                Part(amount * 60L + 30, HOURS_RANK, (index..index + 4).toList())
            }

            token.type == TokenType.NUMBER && free -> {
                val amount = token.value as? Int ?: return null
                when (unitAt(tokens, index + 1, context)) {
                    ChronoUnit.HOURS -> Part(amount * 60L, HOURS_RANK, listOf(index, index + 1))
                    ChronoUnit.MINUTES -> Part(amount.toLong(), MINUTES_RANK, listOf(index, index + 1))
                    else -> null
                }
            }

            // StructuredDateRule may have consumed a decimal such as "2.5" as a date; a length
            // takes it back. Any other consumed amount is skipped.
            token.type == TokenType.STRUCTURED_DATE && isDotOnly(token.text) -> {
                val amount = token.text.toDoubleOrNull() ?: return null
                val indices = listOf(index, index + 1)
                when (unitAt(tokens, index + 1, context)) {
                    ChronoUnit.HOURS -> Part((amount * 60).toLong(), HOURS_RANK, indices, tookDate = !free)
                    ChronoUnit.MINUTES -> Part(amount.toLong(), MINUTES_RANK, indices, tookDate = !free)
                    else -> null
                }
            }

            else -> null
        } ?: return null

        // Each later part names a smaller unit: "1h 30m", not "30m 1h".
        return part.takeIf { it.rank < previousRank }
    }

    private fun unitAt(tokens: List<Token>, index: Int, context: ParseContext): ChronoUnit? {
        val token = tokens.getOrNull(index) ?: return null
        if (context.isConsumed(index) || token.type != TokenType.UNIT) return null
        return (token.value as? ChronoUnit)?.takeIf { it == ChronoUnit.HOURS || it == ChronoUnit.MINUTES }
    }

    private fun isAnd(tokens: List<Token>, index: Int, context: ParseContext) = isWord(tokens, index, context, "and")

    private fun isWord(tokens: List<Token>, index: Int, context: ParseContext, word: String): Boolean {
        val token = tokens.getOrNull(index) ?: return false
        return !context.isConsumed(index) && token.type == TokenType.UNKNOWN && token.text == word
    }
}
