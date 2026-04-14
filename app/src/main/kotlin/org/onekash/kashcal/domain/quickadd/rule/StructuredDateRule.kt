package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer

object StructuredDateRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.STRUCTURED_DATE) continue

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
