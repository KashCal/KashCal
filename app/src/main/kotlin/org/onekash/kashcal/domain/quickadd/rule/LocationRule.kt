package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType

object LocationRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // Scan backwards to find the LAST unconsumed "AT" keyword
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

        // Collect unconsumed tokens after this AT
        val locationTokens = mutableListOf<Int>()
        for (i in (lastAtIndex + 1) until tokens.size) {
            if (context.isConsumed(i)) continue
            val token = tokens[i]
            if (token.type == TokenType.UNKNOWN || token.type == TokenType.NUMBER || token.type == TokenType.KEYWORD) {
                locationTokens.add(i)
            } else {
                // Stop at consumed or non-content tokens (TIME, DATE_KEYWORD, etc.)
                break
            }
        }

        if (locationTokens.isEmpty()) return

        // Build location string from original text
        val locationText = locationTokens.joinToString(" ") { tokens[it].originalText }
        context.location = locationText

        // Consume the AT and all location tokens
        context.consume(lastAtIndex)
        context.consume(locationTokens)
    }
}
