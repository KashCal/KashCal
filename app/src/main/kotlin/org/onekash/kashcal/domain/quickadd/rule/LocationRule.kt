package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.TypedText
import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer

object LocationRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // The location follows the last unconsumed "at".
        var lastAtIndex: Int? = null
        for (i in tokens.indices.reversed()) {
            if (context.isConsumed(i)) continue
            val token = tokens[i]
            if (token.type == TokenType.KEYWORD && token.value == "AT") {
                lastAtIndex = i
                break
            }
        }

        if (lastAtIndex == null) return

        // Take the unconsumed words, numbers and keywords after it, skipping consumed tokens. A
        // unit word continues the location when a word or number follows it ("Hall Day Spa",
        // "Week Street Cafe") and ends it otherwise ("Bob's next week").
        val locationTokens = mutableListOf<Int>()
        for (i in (lastAtIndex + 1) until tokens.size) {
            if (context.isConsumed(i)) continue
            val token = tokens[i]
            val takes = when (token.type) {
                TokenType.UNKNOWN, TokenType.NUMBER, TokenType.KEYWORD -> true
                // A glued number and bare "m" is a word here ("3M HQ"), not a length.
                TokenType.DURATION -> (token.value as? WordTokenizer.GluedDuration)
                    ?.let { !it.hasHourPart && !it.minuteWord } == true
                TokenType.UNIT -> context.findNextUnconsumed(tokens, i + 1)
                    ?.let { tokens[it].type == TokenType.UNKNOWN || tokens[it].type == TokenType.NUMBER }
                    ?: false
                else -> false
            }
            // Stop at the first unconsumed token of another type, such as TIME.
            if (!takes) break
            locationTokens.add(i)
        }

        if (locationTokens.isEmpty()) return

        context.location = locationText(tokens, locationTokens, context) ?: return

        context.consume(lastAtIndex)
        context.consume(locationTokens)
    }

    /**
     * Returns the typed text from the first to the last location token with the consumed tokens
     * between them cut out, or null when the tokens carry no spans into [ParseContext.source].
     */
    private fun locationText(tokens: List<Token>, locationTokens: List<Int>, context: ParseContext): String? {
        val source = context.source ?: return null
        val spans = locationTokens.map { tokens[it].span }
        if (spans.any { it == null }) return null
        val firstIndex = locationTokens.first()
        val lastIndex = locationTokens.last()
        val taken = locationTokens.toSet()
        val cut = (firstIndex..lastIndex)
            .filter { it !in taken }
            .mapNotNull { tokens[it].span }
        // Brackets and quotes the cleanup blanked sit outside the first and last spans.
        val (from, to) = TypedText.widenOverBrackets(source, spans.first()!!.first, spans.last()!!.last + 1)
        return TypedText.cut(source, from, to, cut)
    }
}
