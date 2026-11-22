package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer

object StructuredDateRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // A range's start stays with its end; see AbsoluteDateRule.
        if (context.endDate != null) return
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.STRUCTURED_DATE) continue

            // A dotted token before a meridiem ("3.30 pm") is a time; TimeRule takes it.
            if (isDotOnly(token.text)) {
                val nextIdx = context.findNextUnconsumed(tokens, index + 1)
                if (nextIdx != null && tokens[nextIdx].type == TokenType.MERIDIEM) continue
            }

            // "at 9.30" is a time when the pair is a valid H.MM; TimeRule takes it. "at 25.12"
            // isn't, so it stays a date.
            if (TimeRule.isPrecededByAtKeyword(tokens, index, context) && TimeRule.dottedTime(token.text) != null) {
                continue
            }

            // A number pair typed with an en or em dash ("Office hours 2\u20133") is a range or
            // plain text, never one date; a month range was read by DateRangeRule already.
            if ('\u2013' in token.originalText || '\u2014' in token.originalText) continue

            val parts = token.value as? WordTokenizer.DateParts ?: continue

            val date = context.resolveFutureDate(parts.day, parts.month, parts.year)
            if (date != null) {
                context.absoluteDate = date
                context.dateSet = true
                context.consume(index)
            }
            return
        }
    }

}
