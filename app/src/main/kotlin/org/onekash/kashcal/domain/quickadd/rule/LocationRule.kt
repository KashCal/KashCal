package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType

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

        // Take the unconsumed words, numbers and keywords after it, skipping consumed tokens.
        val locationTokens = mutableListOf<Int>()
        for (i in (lastAtIndex + 1) until tokens.size) {
            if (context.isConsumed(i)) continue
            val token = tokens[i]
            if (token.type == TokenType.UNKNOWN || token.type == TokenType.NUMBER || token.type == TokenType.KEYWORD) {
                locationTokens.add(i)
            } else {
                // Stop at the first unconsumed token of another type, such as TIME.
                break
            }
        }

        if (locationTokens.isEmpty()) return

        val locationText = locationTokens.joinToString(" ") { tokens[it].originalText }
        context.location = locationText

        context.consume(lastAtIndex)
        context.consume(locationTokens)
    }
}
